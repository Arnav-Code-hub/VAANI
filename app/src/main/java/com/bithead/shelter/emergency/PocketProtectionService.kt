package com.bithead.shelter.emergency

import com.bithead.shelter.i18n.AppLanguage
import com.bithead.shelter.i18n.str
import com.bithead.shelter.R

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Display
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bithead.shelter.MainActivity
import com.bithead.shelter.ai.ThreatAnalyzer
import com.bithead.shelter.ai.OfflineSafeword
import com.bithead.shelter.data.AppDatabase
import com.bithead.shelter.data.EvidenceSealer
import com.bithead.shelter.data.TrustedContact
import com.bithead.shelter.data.TrustedContacts
import com.bithead.shelter.security.AppDisguiseManager
import com.bithead.shelter.security.Crypto
import com.bithead.shelter.sensors.DoubleJerkGate
import com.bithead.shelter.sensors.GestureDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class PocketState { DISARMED, STARTING, ARMED, LISTENING, RECORDING, SEALING, ERROR }
enum class PocketTriggerMode { INSTANT_RECORD, SAFEWORD_WINDOW }

object PocketProtection {
    private const val PREFS = "shelter_prefs"
    private const val ENABLED = "pocket_protection_enabled"
    private const val MODE = "pocket_trigger_mode"
    val state = MutableStateFlow(PocketState.DISARMED)
    val activeIncidentId = MutableStateFlow<String?>(null)
    val recordingCapped = MutableStateFlow(false)
    val threat = MutableStateFlow(ThreatAnalyzer.Result("Ambient Noise", 0))
    val statusDetail = MutableStateFlow("Disarmed")

    /** Outlives the service so a stop-and-seal is never cancelled by onDestroy. */
    internal val sealScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, false)

    fun mode(context: Context) = runCatching {
        PocketTriggerMode.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MODE, PocketTriggerMode.INSTANT_RECORD.name)!!)
    }.getOrDefault(PocketTriggerMode.INSTANT_RECORD)

    fun setEnabled(context: Context, value: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(ENABLED, value).apply()

    fun setMode(context: Context, value: PocketTriggerMode) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(MODE, value.name).apply()
}

/**
 * Screen-off emergency trigger. While armed, a double jerk or three power
 * presses either record immediately or open a 12 s safeword window. Every
 * pocket recording is hands-free, so it is captured in sealed 30 s segments
 * and stops by itself after five minutes (or earlier from the notification).
 */
class PocketProtectionService : Service() {
    companion object {
        const val ACTION_ARM = "com.bithead.shelter.POCKET_ARM"
        const val ACTION_DISARM = "com.bithead.shelter.POCKET_DISARM"
        const val ACTION_STOP = "com.bithead.shelter.POCKET_STOP"
        const val ACTION_TRIGGER_RECORDING = "com.bithead.shelter.TRIGGER_RECORDING"
        private const val CHANNEL = "vaani_pocket_protection"
        private const val NOTIFICATION_ID = 702
        private const val MAX_RECORDING_MS = 5 * 60 * 1000L
        private const val SAFEWORD_WINDOW_MS = 12_000L
        private const val TRIGGER_PRESSES = 3
        private const val TRIGGER_WINDOW_MS = 5_000L
        private const val RECEIPT_EVERY_SEGMENTS = 4
        private const val MIN_FREE_BYTES = 100L * 1024L * 1024L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var detector: GestureDetector
    private val doubleJerkGate = DoubleJerkGate()
    private val pressTrigger = PowerPressTrigger(TRIGGER_PRESSES, TRIGGER_WINDOW_MS)
    private var recognitionJob: Job? = null
    private var segments: SegmentedRecorder? = null
    private var incidentId: String? = null
    private var segmentIndex = 0
    private var sealQueue: Channel<File>? = null
    private var sealWorker: Job? = null
    private var sealFailure: String? = null
    private var recordingFailure: String? = null
    private var lastSealed: EvidenceSealer.Result? = null
    private var incidentContacts: List<TrustedContact> = emptyList()
    private var autoStop: Job? = null
    private var storageWatch: Job? = null
    private var analyzer: ThreatAnalyzer? = null
    private var analyzerUpdates: Job? = null
    private var captureWakeLock: PowerManager.WakeLock? = null
    private var standbyWakeLock: PowerManager.WakeLock? = null
    private var registered = false
    private var activationBusy = false
    @Volatile private var disarmRequested = false
    private var displayOn: Boolean? = null

    // Every power press toggles the screen, so interactive on/off transitions are
    // a proxy for presses without accessibility access. Two signals feed one
    // de-duplicated counter because neither is reliable alone: Android 14+
    // coalesces SCREEN_ON/OFF broadcasts ("most recent only"), and newer Android
    // versions stop reporting display state to plain DisplayListeners.
    private fun onPossibleScreenToggle(interactive: Boolean) {
        val previous = displayOn
        displayOn = interactive
        if (previous == null || previous == interactive) return
        if (pressTrigger.onScreenToggle(SystemClock.elapsedRealtime())) trigger("Power-button proxy")
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onPossibleScreenToggle(true)
                Intent.ACTION_SCREEN_OFF -> onPossibleScreenToggle(false)
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            onPossibleScreenToggle(getSystemService(PowerManager::class.java)?.isInteractive == true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        // Low-rate, wake-up accelerometer when the device has one: the sensor hub
        // wakes the CPU for motion, so no wake lock is held while merely armed.
        detector = GestureDetector(this, {
            if (doubleJerkGate.onJerk(SystemClock.elapsedRealtime())) trigger("Double-jerk sensor")
        }, cooldownMs = 250L, preferWakeUpSensor = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISARM -> { disarm(); return START_NOT_STICKY }
            ACTION_STOP -> { stopRecording(); return START_NOT_STICKY }
            ACTION_TRIGGER_RECORDING -> {
                if (PocketProtection.enabled(this) && PocketProtection.state.value == PocketState.DISARMED) arm()
                if (PocketProtection.state.value == PocketState.DISARMED) {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                        if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                }
                startRecording("Manual trigger")
                return START_NOT_STICKY
            }
        }
        if (PocketProtection.enabled(this)) arm() else stopSelf()
        return START_NOT_STICKY
    }

    private fun arm() {
        if (PocketProtection.state.value in setOf(PocketState.ARMED, PocketState.RECORDING, PocketState.SEALING)) return
        if (PocketProtection.mode(this) == PocketTriggerMode.SAFEWORD_WINDOW) {
            val prefs = getSharedPreferences("shelter_prefs", MODE_PRIVATE)
            val language = prefs.getString("speech_language", "system").let {
                if (it == "system") Locale.getDefault().toLanguageTag() else it.orEmpty()
            }
            val word = prefs.getString("user_safeword", "HELP").orEmpty()
            if (!OfflineSafeword.available(this) || !OfflineSafeword.isVerified(this, word, language)) {
                PocketProtection.setEnabled(this, false)
                PocketProtection.state.value = PocketState.ERROR
                PocketProtection.statusDetail.value = "Pocket voice trigger needs a successful local safeword test"
                stopSelf()
                return
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            PocketProtection.state.value = PocketState.ERROR
            PocketProtection.statusDetail.value = "Microphone permission missing"
            stopSelf()
            return
        }
        disarmRequested = false
        PocketProtection.state.value = PocketState.STARTING
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            if (!detector.start()) throw IllegalStateException(str(R.string.accelerometer_unavailable))
            if (!detector.usesWakeUpSensor) acquireStandbyWakeLock()
            displayOn = getSystemService(PowerManager::class.java)?.isInteractive == true
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            })
            getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            registered = true
            PocketProtection.setEnabled(this, true)
            PocketProtection.state.value = PocketState.ARMED
            PocketProtection.statusDetail.value = if (detector.usesWakeUpSensor)
                "Armed with wake-up accelerometer" else "Armed with wake lock fallback"
            updateNotification()
        } catch (error: Exception) {
            PocketProtection.state.value = PocketState.ERROR
            PocketProtection.statusDetail.value = error.message ?: "Could not arm pocket protection"
            cleanupSensors()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun trigger(source: String) {
        if (PocketProtection.state.value != PocketState.ARMED || activationBusy) return
        activationBusy = true
        PocketProtection.state.value = PocketState.STARTING
        PocketProtection.statusDetail.value = "Physical trigger detected"
        scope.launch {
            // Let the foreground Activity release its microphone before this
            // service opens the same microphone on dual-trigger setups.
            delay(150L)
            if (disarmRequested) return@launch
            buzz()
            if (PocketProtection.mode(this@PocketProtectionService) == PocketTriggerMode.SAFEWORD_WINDOW) {
                openSafewordWindow()
            } else {
                startRecording(source)
            }
        }
    }

    private fun openSafewordWindow() {
        val prefs = getSharedPreferences("shelter_prefs", MODE_PRIVATE)
        val language = prefs.getString("speech_language", "system").let {
            if (it == "system") Locale.getDefault().toLanguageTag() else it.orEmpty()
        }
        val word = prefs.getString("user_safeword", "HELP").orEmpty()
        if (!OfflineSafeword.available(this) || !OfflineSafeword.isVerified(this, word, language)) {
            PocketProtection.state.value = PocketState.ERROR
            PocketProtection.statusDetail.value = "Offline safeword model or local test unavailable; choose instant gesture recording"
            activationBusy = false
            updateNotification(str(R.string.speech_recognition_unavailable))
            return
        }
        acquireCaptureWakeLock()
        PocketProtection.state.value = PocketState.LISTENING
        PocketProtection.statusDetail.value = "Listening for safeword for 12 seconds"
        updateNotification(str(R.string.listening_for_safeword))
        recognitionJob?.cancel()
        recognitionJob = scope.launch {
            try {
                val audio = OfflineSafeword.capture(this@PocketProtectionService, SAFEWORD_WINDOW_MS) { buzz() }
                val tag = prefs.getString("speech_language", "system").let {
                    if (it == "system") Locale.getDefault().toLanguageTag() else it.orEmpty()
                }
                val phrase = OfflineSafeword.transcribe(this@PocketProtectionService, audio, tag)
                val safeword = prefs.getString("user_safeword", "HELP").orEmpty()
                if (safeword.isNotBlank() && phrase.contains(safeword, ignoreCase = true)) {
                    closeSafewordWindow(keepWakeLock = true)
                    startRecording("Safeword")
                } else closeSafewordWindow()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                closeSafewordWindow()
                PocketProtection.statusDetail.value = "Offline safeword failed: ${error.message}"
                updateNotification()
            }
        }
    }

    private fun closeSafewordWindow(keepWakeLock: Boolean = false) {
        recognitionJob?.cancel()
        recognitionJob = null
        if (PocketProtection.state.value == PocketState.LISTENING) {
            PocketProtection.state.value = PocketState.ARMED
            PocketProtection.statusDetail.value = "Armed for the next trigger"
            activationBusy = false
            if (!keepWakeLock) releaseCaptureWakeLock()
            updateNotification()
        }
    }

    private fun startRecording(source: String) {
        if (segments != null || PocketProtection.state.value in setOf(PocketState.SEALING, PocketState.ERROR)) return
        closeSafewordWindow(keepWakeLock = true)
        acquireCaptureWakeLock()
        try {
            if (filesDir.usableSpace < MIN_FREE_BYTES) {
                throw IllegalStateException("Less than 100 MiB of free storage; recording cannot start")
            }
            val incident = System.currentTimeMillis().toString()
            val queue = Channel<File>(Channel.UNLIMITED)
            segmentIndex = 0
            sealFailure = null
            recordingFailure = null
            PocketProtection.threat.value = ThreatAnalyzer.Result("Ambient Noise", 0)
            lastSealed = null
            incidentContacts = TrustedContacts.load(this)
            PocketProtection.recordingCapped.value = true
            sealQueue = queue
            sealWorker = PocketProtection.sealScope.launch {
                for (file in queue) sealSegmentInOrder(file, incident, source)
            }
            val recorder = SegmentedRecorder(this, File(filesDir, "pending"), incident, onSegmentReady = { file ->
                if (queue.trySend(file).isFailure) SegmentedRecorder.release(file)
            }, onFailure = { error ->
                recordingFailure = "Recording interrupted: ${error.message ?: "microphone error"}"
                stopRecording()
            })
            recorder.start()
            segments = recorder
            runCatching {
                analyzer = ThreatAnalyzer(this).also { active ->
                    active.startListening()
                    analyzerUpdates = scope.launch { active.liveResult.collect { PocketProtection.threat.value = it } }
                }
            }
            incidentId = incident
            PocketProtection.activeIncidentId.value = incident
            PocketProtection.state.value = PocketState.RECORDING
            PocketProtection.statusDetail.value = "Recording for up to five minutes"
            updateNotification(str(R.string.recording_evidence_2))
            buzz()
            runCatching { EmergencySms.send(this, incidentContacts, LocationCache.latest.value) }
            autoStop?.cancel()
            autoStop = scope.launch { delay(MAX_RECORDING_MS); stopRecording() }
            storageWatch?.cancel()
            storageWatch = scope.launch {
                while (segments != null) {
                    delay(5_000L)
                    if (filesDir.usableSpace < MIN_FREE_BYTES) {
                        recordingFailure = "Recording stopped: less than 100 MiB of free storage"
                        stopRecording()
                        break
                    }
                }
            }
        } catch (error: Exception) {
            sealQueue?.close()
            sealQueue = null
            incidentContacts = emptyList()
            segments?.stop()?.forEach(SegmentedRecorder::release)
            segments = null
            incidentId = null
            PocketProtection.activeIncidentId.value = null
            PocketProtection.recordingCapped.value = false
            PocketProtection.state.value = PocketState.ERROR
            PocketProtection.statusDetail.value = error.message ?: "Recording unavailable"
            activationBusy = false
            releaseCaptureWakeLock()
            updateNotification(str(R.string.recording_unavailable))
        }
    }

    private fun stopRecording() {
        val active = segments ?: return
        segments = null
        analyzerUpdates?.cancel()
        runCatching { analyzer?.stopAndAnalyze() }.getOrNull()?.let { PocketProtection.threat.value = it }
        autoStop?.cancel()
        storageWatch?.cancel()
        val tail = active.stop()
        val queue = sealQueue
        sealQueue = null
        tail.forEach { file -> if (queue?.trySend(file)?.isFailure != false) SegmentedRecorder.release(file) }
        queue?.close()
        val incident = incidentId
        incidentId = null
        PocketProtection.activeIncidentId.value = null
        PocketProtection.recordingCapped.value = false
        PocketProtection.state.value = PocketState.SEALING
        PocketProtection.statusDetail.value = "Sealing completed audio segments"
        updateNotification(str(R.string.sealing_evidence_3))
        PocketProtection.sealScope.launch {
            try {
                sealWorker?.join()
                val last = lastSealed
                if (sealFailure == null && last != null && segmentIndex > 1 && segmentIndex % RECEIPT_EVERY_SEGMENTS != 0) {
                    sendReceipt(last, incident)
                }
            } finally {
                sealWorker = null
                incidentContacts = emptyList()
                PocketProtection.state.value = when {
                    sealFailure != null || recordingFailure != null -> PocketState.ERROR
                    disarmRequested || !PocketProtection.enabled(this@PocketProtectionService) -> PocketState.DISARMED
                    else -> PocketState.ARMED
                }
                PocketProtection.statusDetail.value = sealFailure ?: recordingFailure ?: when (PocketProtection.state.value) {
                    PocketState.ARMED -> "Armed for the next trigger"
                    else -> "Recording sealed"
                }
                activationBusy = false
                releaseCaptureWakeLock()
                if (PocketProtection.state.value == PocketState.DISARMED) shutdown()
                else updateNotification(sealFailure ?: recordingFailure)
            }
        }
    }

    /** One consumer commits segments in capture order; a failure retains this and later raw files. */
    private suspend fun sealSegmentInOrder(file: File, incident: String, source: String) {
        try {
            if (sealFailure != null) return
            require(withContext(Dispatchers.IO) { AudioEvidenceFile.isPlayable(file) }) {
                "Audio segment is incomplete; raw file preserved"
            }
            val index = ++segmentIndex
            val threat = PocketProtection.threat.value
            val sealed = EvidenceSealer.seal(this, AppDatabase.get(this), Crypto.getOrCreateKeystoreKey(),
                EvidenceSealer.Request(file, "Segment $index • $source • ${threat.label}", threat.score, incidentId = incident))
            lastSealed = sealed
            LocationCache.latest.value?.let { fix -> runCatching {
                AppDatabase.get(this).evidenceDao().updateLocation(sealed.entryId, fix.latitude, fix.longitude)
            } }
            if (index == 1 || index % RECEIPT_EVERY_SEGMENTS == 0) {
                sendReceipt(sealed, incident)
            }
        } catch (error: Exception) {
            sealFailure = "Evidence pending recovery: ${error.message ?: "storage error"}"
        } finally {
            SegmentedRecorder.release(file)
        }
    }

    private suspend fun sendReceipt(sealed: EvidenceSealer.Result, incident: String?) {
        runCatching {
            EmergencySms.sendSealReceipt(this, incidentContacts, sealed.entryId, sealed.chainHash, incident)
        }.onFailure { error ->
            incidentContacts.forEach { contact ->
                EmergencySms.update(this, contact.number,
                    "Failed: ${error.message ?: "receipt unavailable"}", evidenceId = sealed.entryId)
            }
        }
    }

    private fun disarm() {
        disarmRequested = true
        PocketProtection.setEnabled(this, false)
        if (segments != null) {
            // Seal what was captured first; shutdown follows once sealing ends.
            stopRecording()
            cleanupSensors(keepWakeLock = true)
        } else if (PocketProtection.state.value == PocketState.SEALING) {
            cleanupSensors(keepWakeLock = true)
        } else {
            PocketProtection.state.value = PocketState.DISARMED
            PocketProtection.statusDetail.value = "Disarmed"
            shutdown()
        }
    }

    private fun shutdown() {
        cleanupSensors()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupSensors(keepWakeLock: Boolean = false) {
        detector.stop()
        closeSafewordWindow(keepWakeLock)
        if (registered) {
            runCatching { unregisterReceiver(screenReceiver) }
            runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        }
        registered = false
        standbyWakeLock?.let { if (it.isHeld) it.release() }
        standbyWakeLock = null
        if (!keepWakeLock) releaseCaptureWakeLock()
    }

    private fun acquireStandbyWakeLock() {
        val lock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VAANI:PocketStandby")
            ?.apply { setReferenceCounted(false) }
        lock?.acquire()
        standbyWakeLock = lock
    }

    private fun acquireCaptureWakeLock() {
        val lock = captureWakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VAANI:PocketCapture")
            ?.apply { setReferenceCounted(false) }
            ?.also { captureWakeLock = it }
        if (lock?.isHeld == false) lock.acquire()
    }

    private fun releaseCaptureWakeLock() {
        captureWakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun buzz() = getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
        ?.vibrate(VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE))

    private fun updateNotification(text: String? = null) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun notification(text: String? = null): android.app.Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, str(R.string.background_protection), NotificationManager.IMPORTANCE_LOW))
        val disguised = AppDisguiseManager.isEnabled(this)
        val recording = PocketProtection.state.value == PocketState.RECORDING
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = androidx.core.app.NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (disguised) android.R.drawable.ic_menu_edit else android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(if (disguised) str(R.string.notes) else str(R.string.pocket_protection_2))
            .setContentText(if (disguised) {
                if (PocketProtection.state.value == PocketState.ERROR) str(R.string.recording_unavailable) else str(R.string.sync_on)
            } else text ?: if (recording) str(R.string.recording_evidence_stops_after_5_min)
              else str(R.string.armed_jerk_or_power_trigger))
            .setContentIntent(open).setOngoing(true).setSilent(true)
        if (recording) {
            val stop = PendingIntent.getService(this, 3, Intent(this, PocketProtectionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_pause, if (disguised) str(R.string.done) else str(R.string.stop_seal_2), stop)
        }
        return builder.build()
    }

    override fun onDestroy() {
        // Capture in progress is stopped and sealed on the long-lived seal scope.
        stopRecording()
        cleanupSensors(keepWakeLock = PocketProtection.state.value == PocketState.SEALING)
        scope.cancel()
        analyzer?.close()
        if (PocketProtection.state.value != PocketState.SEALING) PocketProtection.state.value = PocketState.DISARMED
        super.onDestroy()
    }
}

/** Counts screen on/off toggles and fires once [presses] happen within [windowMs]. */
internal class PowerPressTrigger(private val presses: Int, private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    fun onScreenToggle(nowMs: Long): Boolean {
        times.addLast(nowMs)
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) times.removeFirst()
        if (times.size < presses) return false
        times.clear()
        return true
    }
}
