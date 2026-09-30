package com.bithead.shelter

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.bithead.shelter.ai.IncidentSummary
import com.bithead.shelter.ai.OfflineSafeword
import com.bithead.shelter.data.AppDatabase
import com.bithead.shelter.data.Evidence
import com.bithead.shelter.data.EvidenceSealer
import com.bithead.shelter.data.TrustedContact
import com.bithead.shelter.data.TrustedContacts
import com.bithead.shelter.emergency.EmergencySms
import com.bithead.shelter.emergency.AudioEvidenceFile
import com.bithead.shelter.emergency.LocationCache
import com.bithead.shelter.emergency.LocationTrackingService
import com.bithead.shelter.emergency.PocketProtection
import com.bithead.shelter.emergency.PocketProtectionService
import com.bithead.shelter.emergency.PocketState
import com.bithead.shelter.emergency.PocketTriggerMode
import com.bithead.shelter.emergency.SegmentedRecorder
import com.bithead.shelter.i18n.AppLanguage
import com.bithead.shelter.i18n.str
import com.bithead.shelter.security.Crypto
import com.bithead.shelter.security.AppDisguiseManager
import com.bithead.shelter.sensors.GestureDetector
import com.bithead.shelter.ui.VaaniApp
import com.bithead.shelter.ui.components.EvidencePlaybackState
import com.bithead.shelter.ui.components.EvidenceMediaPreviewState
import com.bithead.shelter.ui.components.SafewordDialog
import com.bithead.shelter.ui.theme.ShelterTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.crypto.SecretKey
import kotlin.coroutines.resume

class MainActivity : FragmentActivity() {

    private enum class CaptureState { IDLE, RECORDING, SEALING }

    private data class SealResult(val entryId: Long, val chainHash: String, val rawDeleted: Boolean)

    private data class RecoveryResult(val recovered: Int, val failure: String? = null)

    private data class LocationFix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val timestampMillis: Long
    )

    companion object {
        // Shared across Activity recreation so a new startup recovery cannot
        // race an in-flight seal owned by the previous Activity instance.
        private val sealMutex = EvidenceSealer.mutex

        @Volatile
        private var startupRecoveryComplete = false

        private const val GESTURE_WINDOW_SECONDS = 12
        private const val GESTURE_READY_TIMEOUT_MS = 3_000L
    }

    private lateinit var db: AppDatabase
    private var activeIncidentId by mutableStateOf<String?>(null)
    private var mediaSealsPending by mutableIntStateOf(0)
    private var trustedContacts by mutableStateOf<List<TrustedContact>>(emptyList())
    private var pendingTrackingEnable = false
    private lateinit var key: SecretKey
    private var mediaPlayer: MediaPlayer? = null
    private var playbackTempFile: File? = null
    private var playbackLoadJob: Job? = null
    private var playbackPositionJob: Job? = null
    private var playbackRequestId = 0L
    private var mediaPreviewTempFile: File? = null
    private var mediaPreviewJob: Job? = null
    private var mediaPreviewRequestId = 0L
    private var offlineListeningJob: Job? = null
    private lateinit var gestureDetector: GestureDetector
    private val snackbarHostState = SnackbarHostState()

    // ---- UI state, observed by Compose -----------------------------------
    private var captureState by mutableStateOf(CaptureState.IDLE)
    private var serviceTriggerPending = false
    private var safewordState by mutableStateOf("HELP")
    private var threatLabel by mutableStateOf("Ready")
    private var threatScore by mutableIntStateOf(0)
    private var lastLat by mutableStateOf<Double?>(null)
    private var lastLng by mutableStateOf<Double?>(null)
    private var communityLat by mutableStateOf<Double?>(null)
    private var communityLng by mutableStateOf<Double?>(null)
    private var communityAccuracy by mutableStateOf<Float?>(null)
    private var communityLocationTime by mutableLongStateOf(0L)
    private var showSafewordDialog by mutableStateOf(false)
    private var showSafewordTest by mutableStateOf(false)
    private var safewordTestStatus by mutableStateOf("Say your safeword when the microphone opens.")
    private var safewordTestRunning by mutableStateOf(false)
    private var vaultUnlocked by mutableStateOf(false)
    private var biometricAvailable by mutableStateOf(true)
    private var listeningEnabled by mutableStateOf(true)
    private var speechLanguage by mutableStateOf("system")
    private var pocketEnabled by mutableStateOf(false)
    private var pocketMode by mutableStateOf(PocketTriggerMode.INSTANT_RECORD)
    private var gestureWakeMode by mutableStateOf(false)
    private var safewordListeningActive by mutableStateOf(false)
    private var gestureWindowOpening by mutableStateOf(false)
    private var gestureWindowPending = false
    private var gestureWindowDeadlineMs = 0L
    private var gestureWindowSecondsRemaining by mutableIntStateOf(0)
    private var safewordActivationPending = false
    private var pendingGestureEnable = false
    private var disguiseEnabled by mutableStateOf(false)
    private var playbackState by mutableStateOf(EvidencePlaybackState())
    private var mediaPreviewState by mutableStateOf(EvidenceMediaPreviewState())
    private var foreground = false
    private var restartListeningJob: Job? = null
    private var safewordWindowJob: Job? = null
    private var safewordActivationJob: Job? = null
    private var communityLocationJob: Job? = null

    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri ->
            runCatching {
                contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        trustedContacts = TrustedContacts.add(this, cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty())
                    }
                }
            }.onFailure { error ->
                lifecycleScope.launch { snackbarHostState.showSnackbar("Could not add contact: ${error.message}") }
            }
        }
    }
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importMedia(uri)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        db = AppDatabase.get(this)
        key = loadOrCreateMasterKey()
        trustedContacts = TrustedContacts.load(this)
        EmergencySms.restore(this)
        LocationCache.restore(this)
        lifecycleScope.launch(Dispatchers.IO) {
            cacheDir.listFiles { file -> file.name.startsWith("temp_play_") ||
                file.name.startsWith("temp_preview_") || file.name.startsWith("recovery_") }
                ?.forEach(File::delete)
        }
        val needsStartupRecovery = !startupRecoveryComplete
        val needsPipelineBarrier = needsStartupRecovery
        if (needsPipelineBarrier) captureState = CaptureState.SEALING
        safewordState = loadSafeword()
        listeningEnabled = getSharedPreferences("shelter_prefs", MODE_PRIVATE).getBoolean("listening", true)
        speechLanguage = getSharedPreferences("shelter_prefs", MODE_PRIVATE)
            .getString("speech_language", "system") ?: "system"
        if (!OfflineSafeword.isVerified(this, safewordState, selectedSpeechLanguageTag())) {
            listeningEnabled = false
        }
        pocketEnabled = PocketProtection.enabled(this)
        pocketMode = PocketProtection.mode(this)
        gestureWakeMode = getSharedPreferences("shelter_prefs", MODE_PRIVATE).getBoolean("gesture_wake_mode", false)
        if (gestureWakeMode && !listeningEnabled) {
            listeningEnabled = true
            getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit().putBoolean("listening", true).apply()
        }
        disguiseEnabled = AppDisguiseManager.isEnabled(this)
        applyTaskIdentity(disguiseEnabled)
        AppDisguiseManager.applyLauncherState(this, disguiseEnabled)
        requestPermissionsIfNeeded()
        if (!OfflineSafeword.available(this)) {
            listeningEnabled = false
            gestureWakeMode = false
        }
        gestureDetector = GestureDetector(this, onJerkDetected = {
            if (gestureWakeMode && listeningEnabled && foreground) startSafewordWindow()
        })
        checkBiometricAvailability()

        setContent {
            ShelterTheme {
                val evidenceList by db.evidenceDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                val smsStatuses by EmergencySms.statuses.collectAsStateWithLifecycle()
                val cachedLocation by LocationCache.latest.collectAsStateWithLifecycle()
                val locationTracking by LocationCache.trackingEnabled.collectAsStateWithLifecycle()
                VaaniApp(
                    isEmergency = captureState == CaptureState.RECORDING,
                    isSealing = captureState == CaptureState.SEALING,
                    safeword = safewordState,
                    threatLabel = threatLabel,
                    threatScore = threatScore,
                    lat = lastLat,
                    lng = lastLng,
                    mapLat = communityLat,
                    mapLng = communityLng,
                    mapAccuracyMeters = communityAccuracy,
                    mapLocationTimestamp = communityLocationTime.takeIf { it > 0L },
                    evidence = evidenceList,
                    vaultUnlocked = vaultUnlocked,
                    biometricAvailable = biometricAvailable,
                    snackbarHostState = snackbarHostState,
                    onTrigger = {
                        when (captureState) {
                            CaptureState.IDLE -> activateEmergency()
                            CaptureState.RECORDING -> stopEmergency()
                            CaptureState.SEALING -> lifecycleScope.launch {
                                snackbarHostState.showSnackbar("Please wait while the previous recording is sealed")
                            }
                        }
                    },
                    onEditSafeword = { showSafewordDialog = true },
                    playbackState = playbackState,
                    onPlaybackToggle = { toggleEvidencePlayback(it) },
                    onPlaybackStop = { stopEvidencePlayback() },
                    onPlaybackSeek = { evidence, position -> seekEvidencePlayback(evidence, position) },
                    onExport = { exportChainOfCustody(it) },
                    mediaPreviewState = mediaPreviewState,
                    onOpenMedia = { openMediaPreview(it) },
                    onCloseMedia = { closeMediaPreview() },
                    onDeleteEvidence = { deleteEvidence(it) },
                    trustedContacts = trustedContacts,
                    smsStatuses = smsStatuses,
                    locationTracking = locationTracking,
                    cachedLocation = cachedLocation,
                    onAddContact = { name, number -> addTrustedContact(name, number) },
                    onRemoveContact = { number -> trustedContacts = TrustedContacts.remove(this, number) },
                    onContactLanguageChange = { number, language ->
                        trustedContacts = TrustedContacts.setSmsLanguage(this, number, language)
                    },
                    onPickContact = { contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
                    onLocationTrackingChange = { updateLocationTracking(it) },
                    onRequestSmsPermission = { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 11) },
                    onImportMedia = { mediaPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    pendingEvidenceDir = File(filesDir, "pending"),
                    activeIncidentId = activeIncidentId,
                    onMediaCaptured = { sealMedia(it) },
                    recordingCapped = PocketProtection.recordingCapped.collectAsStateWithLifecycle().value,
                    onTamperDemo = if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                        ({ runSafeTamperDemo() }) else null,
                    appLanguage = AppLanguage.get(this),
                    onAppLanguageChange = { updateAppLanguage(it) },
                    onDisguiseShown = { shown -> applyTaskIdentity(shown || disguiseEnabled) },
                    onVerifyChain = { verifyEvidenceChain() },
                    onUnlockVault = { unlockVault() },
                    listening = listeningEnabled,
                    speechLanguage = speechLanguage,
                    onSpeechLanguageChange = { updateSpeechLanguage(it) },
                    listeningActive = safewordListeningActive,
                    pocketEnabled = pocketEnabled,
                    pocketState = PocketProtection.state.collectAsStateWithLifecycle().value.name,
                    pocketDetail = PocketProtection.statusDetail.collectAsStateWithLifecycle().value,
                    pocketMode = pocketMode,
                    onPocketProtectionChange = { updatePocketProtection(it) },
                    onPocketModeChange = { updatePocketMode(it) },
                    onListeningChange = { updateListeningEnabled(it) },
                    gestureWakeMode = gestureWakeMode,
                    gestureWindowOpening = gestureWindowOpening,
                    gestureWindowSecondsRemaining = gestureWindowSecondsRemaining,
                    onGestureWakeModeChange = { updateGestureWakeMode(it) },
                    disguiseEnabled = disguiseEnabled,
                    onDisguiseEnabledChange = { updateAppDisguise(it) },
                    onLockVault = { stopEvidencePlayback(); closeMediaPreview(); vaultUnlocked = false },
                    onVaultHidden = { stopEvidencePlayback(); closeMediaPreview() },
                    onRefreshMapLocation = { refreshCommunityLocation() }
                )
                if (showSafewordDialog) {
                    SafewordDialog(
                        current = safewordState,
                        onDismiss = { showSafewordDialog = false },
                        onSave = { saveSafeword(it); showSafewordDialog = false; showSafewordTest = true }
                    )
                }
                if (showSafewordTest) {
                    AlertDialog(
                        onDismissRequest = { if (!safewordTestRunning) showSafewordTest = false },
                        title = { Text("Test offline safeword") },
                        text = { Text(safewordTestStatus) },
                        confirmButton = {
                            TextButton(onClick = { testOfflineSafeword() }, enabled = !safewordTestRunning) {
                                Text("Record 4-second test")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSafewordTest = false }, enabled = !safewordTestRunning) {
                                Text("Close")
                            }
                        }
                    )
                }
            }
        }
        if (needsPipelineBarrier) {
            lifecycleScope.launch {
                try {
                    if (needsStartupRecovery) recoverOrphanedEvidence()
                } finally {
                    captureState = when (PocketProtection.state.value) {
                        PocketState.RECORDING -> CaptureState.RECORDING
                        PocketState.SEALING -> CaptureState.SEALING
                        else -> CaptureState.IDLE
                    }
                    if (!gestureWakeMode) restartSafewordListening()
                }
            }
        }
        lifecycleScope.launch {
            PocketProtection.state.collect { state ->
                if (!startupRecoveryComplete) return@collect
                captureState = when (state) {
                    PocketState.RECORDING -> CaptureState.RECORDING
                    PocketState.SEALING -> CaptureState.SEALING
                    else -> CaptureState.IDLE
                }
                if (state in setOf(PocketState.STARTING, PocketState.LISTENING, PocketState.RECORDING)) {
                    cancelSafewordSession()
                }
                if (state in setOf(PocketState.ARMED, PocketState.DISARMED) && foreground) {
                    restartSafewordListening()
                }
                if (state == PocketState.RECORDING || state == PocketState.ERROR) serviceTriggerPending = false
                if (state == PocketState.ERROR) {
                    startupRecoveryComplete = false
                    recoverOrphanedEvidence()
                    snackbarHostState.showSnackbar(
                        "Background recording needs attention. Pending raw evidence was preserved for recovery."
                    )
                }
            }
        }
        lifecycleScope.launch { PocketProtection.activeIncidentId.collect { activeIncidentId = it } }
        lifecycleScope.launch { PocketProtection.threat.collect { threat ->
            threatLabel = threat.label
            threatScore = threat.score
        } }
    }

    // ---- Keys & prefs -------------------------------------------------------

    private fun loadOrCreateMasterKey(): SecretKey {
        // Key now lives in the Android Keystore (hardware-backed where available)
        // instead of being persisted as raw base64 bytes in SharedPreferences.
        return Crypto.getOrCreateKeystoreKey()
    }

    private fun loadSafeword(): String {
        val prefs = getSharedPreferences("shelter_prefs", MODE_PRIVATE)
        return prefs.getString("user_safeword", "HELP") ?: "HELP"
    }

    private fun saveSafeword(newWord: String) {
        safewordState = newWord.trim().uppercase(Locale.getDefault())
        listeningEnabled = false
        gestureWakeMode = false
        cancelSafewordSession()
        getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit()
            .putString("user_safeword", safewordState)
            .putBoolean("listening", false)
            .putBoolean("gesture_wake_mode", false).apply()
    }

    private fun selectedSpeechLanguageTag(): String =
        if (speechLanguage == "system") Locale.getDefault().toLanguageTag() else speechLanguage

    private fun testOfflineSafeword() {
        if (safewordTestRunning || captureState != CaptureState.IDLE) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            safewordTestStatus = "Microphone permission is required for the test."
            requestPermissionsIfNeeded()
            return
        }
        cancelSafewordSession()
        safewordTestRunning = true
        safewordTestStatus = "Opening microphone…"
        lifecycleScope.launch {
            try {
                val samples = OfflineSafeword.capture(this@MainActivity, 4_000L) {
                    runOnUiThread { safewordTestStatus = "Listening for 4 seconds…" }
                }
                safewordTestStatus = "Checking the phrase on this device…"
                val tag = selectedSpeechLanguageTag()
                val inferenceStarted = SystemClock.elapsedRealtime()
                val heard = OfflineSafeword.transcribe(this@MainActivity, samples, tag)
                val inferenceMs = SystemClock.elapsedRealtime() - inferenceStarted
                if (heard.contains(safewordState, ignoreCase = true) && inferenceMs <= 3_500L) {
                    OfflineSafeword.markVerified(this@MainActivity, safewordState, tag)
                    safewordTestStatus = "Test passed in ${inferenceMs} ms. You can now enable Safeword Protection."
                } else if (inferenceMs > 3_500L) {
                    safewordTestStatus = "Voice recognition took ${inferenceMs} ms, too slow for reliable arming on this phone. Use the manual alert or instant pocket trigger."
                } else {
                    safewordTestStatus = "Test failed. Heard: ${heard.ifBlank { "nothing" }}. Try again in a quiet place."
                }
            } catch (error: Exception) {
                safewordTestStatus = "Offline test failed: ${error.message}"
            } finally {
                safewordTestRunning = false
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (!hasLocationPermission()) {
            // Android 12+ requires both permissions in the same request so the
            // system can offer the user its Precise / Approximate choice.
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 10)
    }

    private fun hasLocationPermission(): Boolean {
        val hasFine = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return hasFine || hasCoarse
    }

    /**
     * Requests a fresh GPS/network fix instead of relying on a possibly stale
     * or null getLastKnownLocation() reading. Falls back to the last-known
     * fix only if a live update can't be obtained within the timeout.
     */
    private suspend fun getLocation(): LocationFix? {
        val hasFine = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            snackbarHostState.showSnackbar("Location permission denied — location features are unavailable")
            return null
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val lastKnownProviders = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (hasFine) add(LocationManager.GPS_PROVIDER)
        }
        val bestLastKnown = lastKnownProviders
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .maxWithOrNull(compareBy<Location>({ it.time }, { if (it.hasAccuracy()) -it.accuracy else Float.NEGATIVE_INFINITY }))

        val liveProviders = buildList {
            if (runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
            if (hasFine && runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.GPS_PROVIDER)
            }
        }
        if (liveProviders.isEmpty()) {
            snackbarHostState.showSnackbar("Location services are off — using the most recent saved fix")
            return bestLastKnown?.toLocationFix()
        }

        // Request network and GPS together. Indoors, the network provider can
        // win immediately; otherwise the 3-second cap falls back to the best
        // last-known reading instead of waiting indefinitely for satellites.
        val liveLocation = withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                var completed = false
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!completed && cont.isActive) {
                            completed = true
                            runCatching { lm.removeUpdates(this) }
                            cont.resume(location)
                        }
                    }
                }
                var registered = false
                liveProviders.forEach { provider ->
                    try {
                        lm.requestLocationUpdates(provider, 0L, 0f, listener, mainLooper)
                        registered = true
                    } catch (_: SecurityException) {
                        // Try any remaining provider. The permission state may
                        // have changed between the initial check and request.
                    } catch (_: IllegalArgumentException) {
                        // Provider disappeared or is unsupported on this device.
                    }
                }
                if (!registered && !completed) {
                    completed = true
                    cont.resume(null)
                }
                cont.invokeOnCancellation {
                    completed = true
                    runCatching { lm.removeUpdates(listener) }
                }
            }
        }
        return (liveLocation ?: bestLastKnown)?.toLocationFix()
    }

    private fun Location.toLocationFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy() && accuracy.isFinite() && accuracy > 0f) accuracy else null,
        timestampMillis = time.takeIf { it > 0L } ?: System.currentTimeMillis()
    )

    private fun updateAppDisguise(enabled: Boolean) {
        if (enabled) {
            stopEvidencePlayback()
            closeMediaPreview()
            vaultUnlocked = false
        }
        AppDisguiseManager.setEnabled(this, enabled)
        disguiseEnabled = enabled
        applyTaskIdentity(enabled)
    }

    private fun applyTaskIdentity(disguised: Boolean) {
        val label = if (disguised) str(R.string.notes) else "VAANI"
        val background = ContextCompat.getColor(this,
            if (disguised) R.color.notes_icon_background else R.color.vaani_icon_background)
        @Suppress("DEPRECATION")
        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ActivityManager.TaskDescription(label,
                if (disguised) R.drawable.ic_notes_task else R.drawable.vaani_logo, background)
        } else {
            ActivityManager.TaskDescription(label)
        }
        setTaskDescription(description)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!disguised)
    }

    private fun updateAppLanguage(tag: String) {
        val pocketBusy = PocketProtection.state.value.name in setOf("RECORDING", "SEALING")
        if (captureState != CaptureState.IDLE || pocketBusy || mediaSealsPending > 0) {
            lifecycleScope.launch { snackbarHostState.showSnackbar(str(R.string.language_change_blocked)) }
            return
        }
        if (tag == AppLanguage.get(this)) return
        AppLanguage.set(this, tag)
        recreate()
    }

    private fun refreshCommunityLocation() {
        communityLocationJob?.cancel()
        communityLocationJob = lifecycleScope.launch {
            getLocation()?.let { fix ->
                communityLat = fix.latitude
                communityLng = fix.longitude
                communityAccuracy = fix.accuracyMeters
                communityLocationTime = fix.timestampMillis
                LocationCache.save(this@MainActivity,
                    com.bithead.shelter.emergency.CachedLocation(fix.latitude, fix.longitude, fix.timestampMillis, fix.accuracyMeters))
            }
        }
    }

    private fun addTrustedContact(name: String, number: String) {
        runCatching { TrustedContacts.add(this, name, number) }
            .onSuccess { trustedContacts = it }
            .onFailure { error -> lifecycleScope.launch { snackbarHostState.showSnackbar(error.message ?: "Invalid contact") } }
    }

    private fun updateLocationTracking(enabled: Boolean) {
        if (!enabled) {
            LocationCache.setEnabled(this, false)
            stopService(Intent(this, LocationTrackingService::class.java))
            return
        }
        if (!hasLocationPermission()) {
            pendingTrackingEnable = true
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
            return
        }
        LocationCache.setEnabled(this, true)
        startLocationProtection()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
    }

    private fun startLocationProtection() {
        try {
            ContextCompat.startForegroundService(this,
                Intent(this, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START))
        } catch (error: Exception) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Location protection could not start: ${error.message}") }
        }
    }

    private fun importMedia(uri: Uri) {
        if (captureState == CaptureState.SEALING || mediaSealsPending > 0) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Wait for the current evidence to finish sealing") }
            return
        }
        val mime = contentResolver.getType(uri).orEmpty()
        val type = when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            else -> {
                lifecycleScope.launch { snackbarHostState.showSnackbar("Choose an image or video") }
                return
            }
        }
        val extension = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> if (type == "video") "mp4" else "jpg"
        }
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        } ?: 0L
        if (size > 250L * 1024 * 1024) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Choose a file under 250 MB") }
            return
        }
        val session = activeIncidentId ?: System.currentTimeMillis().toString()
        lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    val directory = File(filesDir, "pending").apply { mkdirs() }
                    val timestamp = System.currentTimeMillis()
                    val target = File(directory, "evidence_${timestamp}_${type}_${session}.raw.$extension")
                    contentResolver.openInputStream(uri).use { source ->
                        requireNotNull(source) { "Cannot open the selected media" }
                        target.outputStream().use { output -> source.copyTo(output) }
                    }
                    target
                }
                sealMedia(raw)
            } catch (error: Exception) {
                snackbarHostState.showSnackbar("Media import failed: ${error.message}")
            }
        }
    }

    // ---- Safeword voice trigger ----------------------------------------------

    private fun checkSafewordMatches(matches: ArrayList<String>?): Boolean {
        if (captureState != CaptureState.IDLE || !listeningEnabled || !foreground || safewordActivationPending ||
            (gestureWakeMode && !gestureWindowPending)) return false
        val matched = matches?.any { it.contains(safewordState, ignoreCase = true) } == true
        if (matched) queueEmergencyFromSafeword()
        return matched
    }

    private fun queueEmergencyFromSafeword() {
        if (safewordActivationPending) return
        safewordActivationPending = true
        restartListeningJob?.cancel()
        if (gestureWakeMode) finishGestureWindow(cancelRecognizer = true)
        else {
            safewordListeningActive = false
            offlineListeningJob?.cancel()
        }
        safewordActivationJob?.cancel()
        safewordActivationJob = lifecycleScope.launch {
            delay(200L)
            safewordActivationPending = false
            if (foreground && listeningEnabled && captureState == CaptureState.IDLE) activateEmergency()
        }
    }

    private fun startSafewordListening(): Boolean {
        if (captureState != CaptureState.IDLE || !foreground || !listeningEnabled ||
            PocketProtection.state.value in setOf(PocketState.STARTING, PocketState.LISTENING, PocketState.RECORDING) ||
            offlineListeningJob?.isActive == true ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false

        val window = gestureWakeMode
        offlineListeningJob = lifecycleScope.launch {
            try {
                if (window) {
                    val audio = OfflineSafeword.capture(this@MainActivity,
                        GESTURE_WINDOW_SECONDS * 1_000L) {
                        runOnUiThread {
                            safewordListeningActive = true
                            if (gestureWindowPending) {
                                gestureWindowOpening = false
                                gestureWindowDeadlineMs = SystemClock.elapsedRealtime() + GESTURE_WINDOW_SECONDS * 1_000L
                                getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }?.vibrate(
                                    VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
                                startGestureWindowCountdown()
                            }
                        }
                    }
                    safewordListeningActive = false
                    val phrase = OfflineSafeword.transcribe(this@MainActivity, audio, selectedSpeechLanguageTag())
                    checkSafewordMatches(arrayListOf(phrase))
                    if (gestureWindowPending) finishGestureWindow(cancelRecognizer = false)
                } else {
                    OfflineSafeword.stream(this@MainActivity, selectedSpeechLanguageTag(),
                        onReady = { runOnUiThread { safewordListeningActive = true } },
                        onPhrase = { phrase -> withContext(Dispatchers.Main) {
                            checkSafewordMatches(arrayListOf(phrase))
                        } })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (window) finishGestureWindow(cancelRecognizer = false)
                else {
                    listeningEnabled = false
                    getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit().putBoolean("listening", false).apply()
                }
                snackbarHostState.showSnackbar("Offline safeword failed: ${error.message}")
            } finally {
                safewordListeningActive = false
                offlineListeningJob = null
                if (!window) restartSafewordListening()
            }
        }
        return true
    }

    private fun startSafewordWindow() {
        if (!gestureWakeMode || gestureWindowPending || safewordActivationPending || captureState != CaptureState.IDLE ||
            !foreground || !listeningEnabled) return
        gestureWindowPending = true
        gestureWindowOpening = true
        gestureWindowDeadlineMs = 0L
        gestureWindowSecondsRemaining = 0
        if (!startSafewordListening()) {
            finishGestureWindow(cancelRecognizer = true)
            lifecycleScope.launch {
                snackbarHostState.showSnackbar("Could not open the safeword window — check microphone access")
            }
            return
        }
        safewordWindowJob?.cancel()
        safewordWindowJob = lifecycleScope.launch {
            delay(GESTURE_READY_TIMEOUT_MS)
            if (gestureWindowPending && gestureWindowOpening) {
                finishGestureWindow(cancelRecognizer = true)
                snackbarHostState.showSnackbar("Microphone did not become ready — gesture remains armed")
            }
        }
    }

    private fun startGestureWindowCountdown() {
        safewordWindowJob?.cancel()
        gestureWindowSecondsRemaining = GESTURE_WINDOW_SECONDS
        safewordWindowJob = lifecycleScope.launch {
            while (gestureWindowPending) {
                val remainingMs = gestureWindowDeadlineMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) break
                gestureWindowSecondsRemaining = ((remainingMs + 999L) / 1_000L).toInt()
                delay(remainingMs.coerceAtMost(1_000L))
            }
            if (!gestureWindowPending) return@launch
            gestureWindowSecondsRemaining = 0
            safewordListeningActive = false
        }
    }

    private fun finishGestureWindow(cancelRecognizer: Boolean) {
        gestureWindowPending = false
        gestureWindowOpening = false
        gestureWindowDeadlineMs = 0L
        gestureWindowSecondsRemaining = 0
        safewordListeningActive = false
        safewordWindowJob?.cancel()
        safewordWindowJob = null
        if (cancelRecognizer) offlineListeningJob?.cancel()
    }

    private fun cancelSafewordSession() {
        restartListeningJob?.cancel()
        safewordActivationJob?.cancel()
        safewordActivationPending = false
        finishGestureWindow(cancelRecognizer = true)
    }

    private fun restartSafewordListening() {
        restartListeningJob?.cancel()
        if (listeningEnabled && foreground && captureState == CaptureState.IDLE && !gestureWakeMode &&
            PocketProtection.state.value !in setOf(PocketState.STARTING, PocketState.LISTENING, PocketState.RECORDING)) {
            restartListeningJob = lifecycleScope.launch { delay(1000); startSafewordListening() }
        }
    }

    private fun updateListeningEnabled(enabled: Boolean) {
        if (enabled && !OfflineSafeword.isVerified(this, safewordState, selectedSpeechLanguageTag())) {
            safewordTestStatus = "Say your safeword in a local test before voice protection can be enabled."
            showSafewordTest = true
            return
        }
        if (enabled && (!OfflineSafeword.available(this) ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Offline speech recognition and microphone permission are required for the safeword") }
            requestPermissionsIfNeeded()
            return
        }
        listeningEnabled = enabled
        if (!enabled) gestureWakeMode = false
        getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit()
            .putBoolean("listening", enabled)
            .putBoolean("gesture_wake_mode", gestureWakeMode)
            .apply()
        cancelSafewordSession()
        if (enabled && !gestureWakeMode) restartSafewordListening()
    }

    private fun updateGestureWakeMode(enabled: Boolean) {
        if (enabled && !OfflineSafeword.isVerified(this, safewordState, selectedSpeechLanguageTag())) {
            safewordTestStatus = "Test this language and safeword locally before enabling Gesture-Wake."
            showSafewordTest = true
            return
        }
        if (enabled && !gestureDetector.isAvailable) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("This device has no accelerometer for Gesture-Wake") }
            return
        }
        if (enabled && !OfflineSafeword.available(this)) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Offline speech recognition is unavailable on this device; use manual or instant gesture recording") }
            return
        }
        if (enabled && ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingGestureEnable = true
            requestPermissionsIfNeeded()
            return
        }
        if (enabled && foreground && !gestureDetector.start()) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Accelerometer could not be started") }
            return
        }
        pendingGestureEnable = false
        gestureWakeMode = enabled
        if (enabled) listeningEnabled = true
        getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit()
            .putBoolean("gesture_wake_mode", enabled)
            .putBoolean("listening", listeningEnabled)
            .apply()
        cancelSafewordSession()
        if (!enabled && listeningEnabled && foreground) restartSafewordListening()
    }

    private fun updateSpeechLanguage(language: String) {
        speechLanguage = language.takeIf { it in setOf("system", "en-IN", "hi-IN", "bn-IN", "mr-IN", "ta-IN") } ?: "system"
        getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit()
            .putString("speech_language", speechLanguage).apply()
        cancelSafewordSession()
        if (!OfflineSafeword.isVerified(this, safewordState, selectedSpeechLanguageTag())) {
            listeningEnabled = false
            gestureWakeMode = false
            getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit()
                .putBoolean("listening", false).putBoolean("gesture_wake_mode", false).apply()
            safewordTestStatus = "Test the safeword in the selected language before arming voice protection."
            showSafewordTest = true
        }
    }

    private fun updatePocketMode(mode: PocketTriggerMode) {
        if (mode == PocketTriggerMode.SAFEWORD_WINDOW &&
            !OfflineSafeword.isVerified(this, safewordState, selectedSpeechLanguageTag())) {
            safewordTestStatus = "Test this safeword locally before using the pocket voice window."
            showSafewordTest = true
            return
        }
        pocketMode = mode
        PocketProtection.setMode(this, mode)
    }

    private fun updatePocketProtection(enabled: Boolean) {
        if (enabled) {
            if (pocketMode == PocketTriggerMode.SAFEWORD_WINDOW &&
                !OfflineSafeword.isVerified(this, safewordState, selectedSpeechLanguageTag())) {
                safewordTestStatus = "Test this safeword locally before arming pocket voice protection."
                showSafewordTest = true
                return
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                lifecycleScope.launch { snackbarHostState.showSnackbar("Microphone permission is required for Pocket Protection") }
                requestPermissionsIfNeeded()
                return
            }
            if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
            }
            pocketEnabled = true
            PocketProtection.setEnabled(this, true)
            val intent = Intent(this, PocketProtectionService::class.java).setAction(PocketProtectionService.ACTION_ARM)
            ContextCompat.startForegroundService(this, intent)
        } else {
            pocketEnabled = false
            PocketProtection.setEnabled(this, false)
            startService(Intent(this, PocketProtectionService::class.java).setAction(PocketProtectionService.ACTION_DISARM))
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (PocketProtection.enabled(this) && PocketProtection.state.value == PocketState.DISARMED) {
            runCatching { ContextCompat.startForegroundService(this,
                Intent(this, PocketProtectionService::class.java).setAction(PocketProtectionService.ACTION_ARM)) }
        }
        if (LocationCache.trackingEnabled.value && hasLocationPermission()) startLocationProtection()
        checkBiometricAvailability()
        val sensorStarted = gestureDetector.start()
        if (gestureWakeMode && !sensorStarted) {
            gestureWakeMode = false
            listeningEnabled = false
            getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit()
                .putBoolean("gesture_wake_mode", false)
                .putBoolean("listening", false)
                .apply()
            lifecycleScope.launch { snackbarHostState.showSnackbar("Gesture-Wake disabled because the accelerometer is unavailable") }
        } else if (!gestureWakeMode) {
            startSafewordListening()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                listeningEnabled = false
                if (pendingGestureEnable) {
                    gestureWakeMode = false
                    pendingGestureEnable = false
                    getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit()
                        .putBoolean("gesture_wake_mode", false)
                        .putBoolean("listening", false)
                        .apply()
                    lifecycleScope.launch { snackbarHostState.showSnackbar("Gesture-Wake needs microphone permission") }
                }
            } else if (pendingGestureEnable) {
                updateGestureWakeMode(true)
            } else if (!gestureWakeMode) {
                startSafewordListening()
            }
            if (hasLocationPermission()) refreshCommunityLocation()
            if (pendingTrackingEnable && hasLocationPermission()) {
                pendingTrackingEnable = false
                updateLocationTracking(true)
            }
        } else if (requestCode == 11) {
            lifecycleScope.launch {
                snackbarHostState.showSnackbar(if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                    "SMS alerts are ready" else "SMS permission denied — emergency recording still works")
            }
        }
    }

    // ---- Emergency capture -----------------------------------------------------

    private fun activateEmergency() {
        if (captureState != CaptureState.IDLE || serviceTriggerPending ||
            PocketProtection.state.value in setOf(PocketState.RECORDING, PocketState.SEALING)) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Microphone permission denied — cannot record evidence") }
            requestPermissionsIfNeeded()
            return
        }
        stopEvidencePlayback()
        cancelSafewordSession()
        serviceTriggerPending = true
        try {
            ContextCompat.startForegroundService(this,
                Intent(this, PocketProtectionService::class.java).setAction(PocketProtectionService.ACTION_TRIGGER_RECORDING))
            lifecycleScope.launch {
                delay(3_000L)
                serviceTriggerPending = false
            }
        } catch (error: Exception) {
            serviceTriggerPending = false
            lifecycleScope.launch { snackbarHostState.showSnackbar("Recording could not start: ${error.message}") }
        }
    }

    private fun stopEmergency() {
        if (PocketProtection.state.value != PocketState.RECORDING) return
        startService(Intent(this, PocketProtectionService::class.java).setAction(PocketProtectionService.ACTION_STOP))
    }

    private suspend fun sealEvidence(
        raw: File, label: String, score: Int,
        mediaType: String = "AUDIO", mimeType: String = "audio/mp4", incidentId: String? = null
    ): SealResult {
        val result = EvidenceSealer.seal(this, db, key, EvidenceSealer.Request(
            raw = raw,
            label = label,
            score = score,
            mediaType = mediaType,
            mimeType = mimeType,
            incidentId = incidentId
        ))
        return SealResult(result.entryId, result.chainHash, result.rawDeleted)
    }

    private fun sealMedia(raw: File) {
        val match = Regex("^evidence_(\\d+)_(image|video)_(\\d+)\\.raw\\.(jpg|png|webp|heic|mp4)$")
            .matchEntire(raw.name)
        if (match == null || !raw.exists() || raw.length() == 0L) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Media capture is incomplete; no vault entry was created") }
            return
        }
        val kind = match.groupValues[2]
        val session = match.groupValues[3]
        val extension = match.groupValues[4]
        val mime = when (extension) {
            "jpg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            else -> "video/mp4"
        }
        mediaSealsPending++
        lifecycleScope.launch {
            try {
                val sealed = sealEvidence(raw, if (kind == "video") "Video evidence" else "Image evidence", 0,
                    kind.uppercase(Locale.US), mime, session)
                LocationCache.latest.value?.let { fix ->
                    withContext(Dispatchers.IO) {
                        db.evidenceDao().updateLocation(sealed.entryId, fix.latitude, fix.longitude)
                    }
                }
                snackbarHostState.showSnackbar(if (sealed.rawDeleted) "Media sealed in Vault" else
                    "Media sealed; private temporary file cleanup needs attention")
            } catch (error: Exception) {
                snackbarHostState.showSnackbar("Media could not be sealed: ${error.message}. Private raw file retained for recovery")
            } finally {
                mediaSealsPending--
            }
        }
    }

    /**
     * Re-computes SHA-256 of every sealed evidence file currently on disk and
     * re-derives the hash chain, comparing it against what's stored in Room.
     * This is the live "tamper test" demo: if any encrypted file has been
     * modified (or deleted/swapped) since sealing, the recomputed chain value
     * will not match the stored one and verification fails at that entry.
     */
    /**
     * Checks once whether this device can actually do biometric auth
     * (enrolled fingerprint/face) or a device PIN/pattern as fallback, so the
     * UI can show an honest "Unlock" vs. "Biometrics unavailable" state
     * instead of a button that silently fails.
     */
    private fun checkBiometricAvailability() {
        val manager = BiometricManager.from(this)
        val result = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        biometricAvailable = result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Gates the Evidence Vault behind fingerprint/face (or device PIN as
     * fallback) so that recordings can't be browsed just by picking up an
     * unlocked phone — mirrors the biometric-gated evidence access pattern
     * used by comparable safety apps (e.g. UNDERCOVER).
     */
    private fun unlockVault() {
        if (!biometricAvailable) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("No fingerprint/face/PIN set up on this device") }
            return
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Evidence Vault")
            .setSubtitle("Verify it's you before viewing sealed evidence")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    vaultUnlocked = true
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    lifecycleScope.launch { snackbarHostState.showSnackbar("Unlock failed: $errString") }
                }
                override fun onAuthenticationFailed() {
                    lifecycleScope.launch { snackbarHostState.showSnackbar("Fingerprint/face not recognized — try again") }
                }
            }
        )
        prompt.authenticate(promptInfo)
    }

    override fun onStop() {
        super.onStop()
        foreground = false
        gestureDetector.stop()
        cancelSafewordSession()
        communityLocationJob?.cancel()
        stopEvidencePlayback()
        closeMediaPreview()
        // Re-lock the vault whenever the app leaves the foreground, so
        // background/multitasking can't be used to skip the biometric check.
        vaultUnlocked = false
    }

    private fun verifyEvidenceChain() {
        lifecycleScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    sealMutex.withLock {
                        val entries = db.evidenceDao().allAscending()
                        if (entries.isEmpty()) return@withLock "No evidence to verify yet"
                        var previousChain: String? = null
                        for (item in entries) {
                            val file = File(filesDir, item.encryptedFile)
                            val storedHash = item.deletedFileHash
                            val fileHash = when {
                                storedHash != null -> {
                                    if (file.exists() && Crypto.sha256(file) != storedHash) {
                                        return@withLock "⚠ Tamper detected: evidence #${item.id} does not match its deletion record"
                                    }
                                    if (item.deletedAt != null && file.exists()) {
                                        return@withLock "⚠ Deletion incomplete for evidence #${item.id}"
                                    }
                                    storedHash
                                }
                                file.exists() -> Crypto.sha256(file)
                                else -> return@withLock "⚠ Tamper detected: evidence #${item.id} is missing"
                            }
                            if (Crypto.chainHash(fileHash, previousChain) != item.sha256 || previousChain != item.previousHash) {
                                return@withLock "⚠ Tamper detected: evidence #${item.id} does not match its recorded hash chain"
                            }
                            previousChain = item.sha256
                        }
                        val deleted = entries.count { it.deletedAt != null }
                        val pending = entries.count { it.deletedFileHash != null && it.deletedAt == null }
                        when {
                            pending > 0 -> "Chain links verified; $pending evidence deletion${if (pending == 1) "" else "s"} still pending"
                            deleted > 0 -> "Chain verified ✓ — ${entries.size - deleted} evidence items intact; $deleted deletion record${if (deleted == 1) "" else "s"} retained"
                            else -> "Chain verified ✓ — all ${entries.size} evidence items intact"
                        }
                    }
                }
            } catch (e: Exception) {
                "Chain verification failed: ${e.message ?: "storage error"}"
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    private fun runSafeTamperDemo() {
        if (!vaultUnlocked) return
        lifecycleScope.launch {
            val message = runCatching {
                withContext(Dispatchers.IO) {
                    sealMutex.withLock {
                        val entry = db.evidenceDao().allAscending().lastOrNull { it.deletedFileHash == null }
                            ?: return@withLock "Seal a recording first, then run the tamper test"
                        val original = File(filesDir, entry.encryptedFile)
                        if (!original.exists()) return@withLock "Evidence file is unavailable for the tamper test"
                        check(Crypto.chainHash(Crypto.sha256(original), entry.previousHash) == entry.sha256) {
                            "Original evidence already fails verification"
                        }
                        val copy = File(cacheDir, "tamper_probe_${entry.id}.enc")
                        try {
                            original.copyTo(copy, overwrite = true)
                            java.io.RandomAccessFile(copy, "rw").use { probe ->
                                val offset = probe.length() - 1L
                                check(offset >= 0L) { "Evidence copy is empty" }
                                probe.seek(offset)
                                val changed = probe.readByte().toInt() xor 1
                                probe.seek(offset)
                                probe.writeByte(changed)
                            }
                            check(Crypto.chainHash(Crypto.sha256(copy), entry.previousHash) != entry.sha256) {
                                "Tamper probe was not detected"
                            }
                            "Temporary copy failed integrity verification; the original vault file is unchanged"
                        } finally {
                            copy.delete()
                        }
                    }
                }
            }.getOrElse { "Tamper test failed: ${it.message ?: "storage error"}" }
            snackbarHostState.showSnackbar(message)
        }
    }

    private fun deleteEvidence(evidence: Evidence) {
        if (!vaultUnlocked) return
        if (playbackState.evidenceId == evidence.id) stopEvidencePlayback()
        if (mediaPreviewState.evidenceId == evidence.id) closeMediaPreview()
        lifecycleScope.launch {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    sealMutex.withLock {
                        val dao = db.evidenceDao()
                        val current = dao.byId(evidence.id) ?: throw IOException("Evidence no longer exists")
                        if (current.deletedAt != null) return@withLock
                        val encrypted = File(filesDir, current.encryptedFile)
                        val fileHash = current.deletedFileHash ?: run {
                            if (!encrypted.exists()) throw IOException("Encrypted evidence is missing; verify the chain")
                            Crypto.sha256(encrypted)
                        }
                        if (Crypto.chainHash(fileHash, current.previousHash) != current.sha256) {
                            throw IOException("Evidence hash does not match the vault chain")
                        }
                        if (current.deletedFileHash == null && dao.requestDeletion(current.id, fileHash) != 1) {
                            throw IOException("Could not save the deletion record")
                        }
                        completePendingDeletion(current.copy(deletedFileHash = fileHash))
                    }
                }
                snackbarHostState.showSnackbar("Evidence deleted; its hash-chain deletion record remains")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Delete incomplete: ${e.message ?: "storage error"}. Tap Delete to retry")
            }
        }
    }

    private suspend fun completePendingDeletion(entry: Evidence) {
        val hash = entry.deletedFileHash ?: throw IOException("Deletion record is missing its file hash")
        val encrypted = File(filesDir, entry.encryptedFile)
        if (encrypted.exists() && Crypto.sha256(encrypted) != hash) {
            throw IOException("Encrypted recording changed after deletion was requested")
        }
        pendingRawFor(entry.encryptedFile)?.let { raw ->
            if (raw.exists() && !raw.delete()) throw IOException("Could not remove the pending raw recording")
        }
        if (encrypted.exists() && !encrypted.delete()) throw IOException("Could not remove the encrypted recording")
        if (db.evidenceDao().finishDeletion(entry.id, System.currentTimeMillis()) != 1) {
            throw IOException("Could not finish the deletion record")
        }
    }

    private suspend fun recoverOrphanedEvidence() {
        val result = withContext(NonCancellable + Dispatchers.IO) {
            sealMutex.withLock {
                if (startupRecoveryComplete) return@withLock null
                try {
                    val dao = db.evidenceDao()
                    val entries = dao.allAscending()
                    val knownFiles = entries.mapTo(HashSet()) { it.encryptedFile }
                    var failure: String? = null

                    // A crash can interrupt deletion after the hash-only marker
                    // commits. Finish removing those files before scanning orphans.
                    for (entry in entries.filter { it.deletedFileHash != null && it.deletedAt == null }) {
                        try {
                            completePendingDeletion(entry)
                        } catch (e: Exception) {
                            failure = "Deletion #${entry.id}: ${e.message ?: "storage error"}"
                            break
                        }
                    }

                    // A row and a filename alone do not prove that encryption
                    // finished intact. Keep raw audio until ciphertext authenticates
                    // and its hash still matches the committed chain entry.
                    for (entry in entries.filter { it.deletedFileHash == null }) {
                        val raw = pendingRawFor(entry.encryptedFile)?.takeIf { it.exists() } ?: continue
                        val encrypted = File(filesDir, entry.encryptedFile)
                        if (!encrypted.exists()) continue
                        val hashMatches = Crypto.chainHash(Crypto.sha256(encrypted), entry.previousHash) == entry.sha256
                        if (!hashMatches || !encryptedEvidenceValid(encrypted, entry.mediaType == "AUDIO")) {
                            failure = "Evidence #${entry.id} could not be verified; its raw copy was retained"
                            break
                        }
                        raw.delete()
                    }

                    val orphanedFiles = filesDir.listFiles()
                        .orEmpty()
                        .filter { file ->
                            file.isFile && isEvidenceEncryptedFile(file.name) &&
                                file.name !in knownFiles
                        }
                    val pendingAudio = File(filesDir, "pending").listFiles()
                        .orEmpty()
                        .filter { file -> file.isFile && file.name.matches(Regex("evidence_\\d+(?:_audio_\\d+)?\\.raw\\.m4a")) &&
                            file.length() > 0L && !SegmentedRecorder.isInFlight(file) }
                    val candidates = (orphanedFiles + pendingAudio)
                        .sortedWith(compareBy<File>(
                            { evidenceTimestamp(it.name) ?: Long.MAX_VALUE },
                            { if (it.extension == "enc") 0 else 1 },
                            { it.lastModified() },
                            { it.name }
                        ))
                    var recovered = 0

                    for (file in if (failure == null) candidates else emptyList()) {
                        try {
                            if (file.extension == "enc") {
                                if (file.length() <= 28L) throw IOException("${file.name} is incomplete")
                                val createdAt = evidenceTimestamp(file.name) ?: file.lastModified()
                                val media = mediaMetadata(file.name)
                                if (!encryptedEvidenceValid(file, media?.first == "AUDIO" || media == null)) {
                                    throw IOException("${file.name} could not be decrypted; any raw copy was retained")
                                }
                                dao.insertChained(Evidence(
                                    createdAt = createdAt,
                                    encryptedFile = file.name,
                                    latitude = null,
                                    longitude = null,
                                    threatLabel = "Recovered evidence",
                                    threatScore = 0,
                                    sha256 = "",
                                    previousHash = null,
                                    incidentId = media?.second ?: createdAt.toString(),
                                    mediaType = media?.first ?: "AUDIO",
                                    mimeType = media?.third ?: "audio/mp4"
                                ), Crypto.sha256(file))
                                knownFiles.add(file.name)
                                pendingRawFor(file.name)?.delete()
                            } else {
                                val encryptedName = file.name.removeSuffix(".raw.m4a") + ".enc"
                                if (encryptedName in knownFiles && File(filesDir, encryptedName).exists()) {
                                    file.delete()
                                    continue
                                }
                                if (!AudioEvidenceFile.isPlayable(file)) {
                                    throw IOException("${file.name} is not a finalized audio file; raw bytes were retained")
                                }
                                val createdAt = evidenceTimestamp(file.name) ?: file.lastModified()
                                val media = mediaMetadata(encryptedName)
                                EvidenceSealer.sealLocked(this@MainActivity, db, key,
                                    EvidenceSealer.Request(file, "Recovered audio", 0,
                                        incidentId = media?.second ?: createdAt.toString()))
                                knownFiles.add(encryptedName)
                            }
                            recovered++
                        } catch (e: Exception) {
                            // Preserve this and all later files. Appending a newer
                            // orphan first would reverse their capture order.
                            if (failure == null) failure = e.message ?: "storage error"
                            break
                        }
                    }
                    RecoveryResult(recovered, failure)
                } catch (e: Exception) {
                    RecoveryResult(0, e.message ?: "storage error")
                } finally {
                    startupRecoveryComplete = true
                }
            }
        } ?: return

        if (result.recovered > 0) {
            snackbarHostState.showSnackbar(
                "Recovered ${result.recovered} sealed recording${if (result.recovered == 1) "" else "s"} into the vault"
            )
        }
        result.failure?.let {
            snackbarHostState.showSnackbar("Evidence recovery needs attention: $it")
        }
    }

    private fun evidenceTimestamp(fileName: String): Long? =
        Regex("^evidence_(\\d+)").find(fileName)?.groupValues?.get(1)?.toLongOrNull()

    private fun encryptedEvidenceValid(encrypted: File, audio: Boolean): Boolean {
        val temporary = File.createTempFile("recovery_", ".raw", cacheDir)
        return try {
            Crypto.decrypt(encrypted, temporary, key)
            !audio || AudioEvidenceFile.isPlayable(temporary)
        } catch (_: Exception) {
            false
        } finally {
            temporary.delete()
        }
    }

    private fun isEvidenceEncryptedFile(fileName: String): Boolean =
        fileName.matches(Regex("evidence_\\d+\\.enc")) ||
            mediaMetadata(fileName) != null

    private fun mediaMetadata(fileName: String): Triple<String, String, String>? {
        val audio = Regex("^evidence_\\d+_audio_(\\d+)\\.enc$").matchEntire(fileName)
        if (audio != null) return Triple("AUDIO", audio.groupValues[1], "audio/mp4")
        val match = Regex("^evidence_\\d+_(image|video)_(\\d+)\\.(jpg|png|webp|heic|mp4)\\.enc$")
            .matchEntire(fileName) ?: return null
        val mime = when (match.groupValues[3]) {
            "jpg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            else -> "video/mp4"
        }
        return Triple(match.groupValues[1].uppercase(Locale.US), match.groupValues[2], mime)
    }

    private fun pendingRawFor(encryptedFileName: String): File? {
        val captureId = evidenceTimestamp(encryptedFileName) ?: return null
        if (encryptedFileName.matches(Regex("^evidence_\\d+_audio_\\d+\\.enc$"))) {
            return File(File(filesDir, "pending"), encryptedFileName.removeSuffix(".enc") + ".raw.m4a")
        }
        if (mediaMetadata(encryptedFileName) != null) {
            val name = encryptedFileName.removeSuffix(".enc")
            val extension = name.substringAfterLast('.')
            return File(File(filesDir, "pending"), "${name.removeSuffix(".$extension")}.raw.$extension")
        }
        return File(File(filesDir, "pending"), "evidence_$captureId.raw.m4a")
    }

    /**
     * Writes a shareable chain-of-custody JSON for one evidence entry and
     * hands it to the Android share sheet — the "give this to a lawyer/
     * officer" wow moment that ties the crypto work to a real-world outcome.
     */
    private fun exportChainOfCustody(evidence: Evidence) {
        if (!vaultUnlocked) return
        try {
            val json = IncidentSummary.toChainOfCustodyJson(evidence)
            val exportDir = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(exportDir, "shelter_evidence_${evidence.id}_custody.json")
            file.writeText(json)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.files", file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share chain of custody record"))
        } catch (e: Exception) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Export failed: ${e.message}") }
        }
    }

    private fun toggleEvidencePlayback(evidence: Evidence) {
        if (!vaultUnlocked) return
        val player = mediaPlayer
        if (playbackState.evidenceId == evidence.id && playbackState.isPreparing) return
        if (playbackState.evidenceId == evidence.id && player != null) {
            if (runCatching { player.isPlaying }.getOrDefault(false)) {
                runCatching { player.pause() }
                playbackPositionJob?.cancel()
                playbackState = playbackState.copy(isPlaying = false)
            } else {
                try {
                    if (playbackState.durationMs > 0L && playbackState.positionMs >= playbackState.durationMs) {
                        player.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
                        playbackState = playbackState.copy(positionMs = 0L)
                    }
                    player.start()
                    playbackState = playbackState.copy(isPlaying = true)
                    startPlaybackPositionUpdates(playbackRequestId)
                } catch (e: Exception) {
                    stopEvidencePlayback()
                    lifecycleScope.launch { snackbarHostState.showSnackbar("Playback failed: ${e.message}") }
                }
            }
            return
        }
        playEvidence(evidence)
    }

    private fun playEvidence(evidence: Evidence) {
        if (!vaultUnlocked) return
        stopEvidencePlayback()
        val encryptedFile = File(filesDir, evidence.encryptedFile)
        if (!encryptedFile.exists()) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Encrypted file not found") }
            return
        }

        val requestId = ++playbackRequestId
        val tempAudio = File(cacheDir, "temp_play_${evidence.id}_$requestId.m4a")
        playbackState = EvidencePlaybackState(evidenceId = evidence.id, isPreparing = true)
        playbackLoadJob = lifecycleScope.launch {
            var playerOwnsTemp = false
            try {
                withContext(Dispatchers.IO) {
                    if (tempAudio.exists() && !tempAudio.delete()) throw IOException("Could not clear playback cache")
                    Crypto.decrypt(encryptedFile, tempAudio, key)
                }
                if (requestId != playbackRequestId || !vaultUnlocked || !foreground) return@launch

                val player = MediaPlayer()
                mediaPlayer = player
                playbackTempFile = tempAudio
                player.setOnPreparedListener { prepared ->
                    if (requestId != playbackRequestId || !vaultUnlocked || !foreground || mediaPlayer !== prepared) {
                        releasePlaybackResources(prepared, tempAudio)
                        return@setOnPreparedListener
                    }
                    try {
                        val duration = prepared.duration.toLong().coerceAtLeast(0L)
                        prepared.start()
                        playbackState = EvidencePlaybackState(
                            evidenceId = evidence.id,
                            isPlaying = true,
                            durationMs = duration
                        )
                        startPlaybackPositionUpdates(requestId)
                    } catch (e: Exception) {
                        stopEvidencePlayback()
                        lifecycleScope.launch { snackbarHostState.showSnackbar("Playback failed: ${e.message}") }
                    }
                }
                player.setOnCompletionListener { completed ->
                    if (requestId != playbackRequestId || mediaPlayer !== completed) return@setOnCompletionListener
                    playbackPositionJob?.cancel()
                    playbackState = playbackState.copy(
                        isPreparing = false,
                        isPlaying = false,
                        positionMs = playbackState.durationMs
                    )
                    releasePlaybackResources(completed, tempAudio)
                }
                player.setOnErrorListener { failed, _, _ ->
                    if (requestId == playbackRequestId && mediaPlayer === failed) {
                        releasePlaybackResources(failed, tempAudio)
                        playbackState = EvidencePlaybackState()
                        lifecycleScope.launch { snackbarHostState.showSnackbar("This evidence audio could not be played") }
                    }
                    true
                }
                player.setDataSource(tempAudio.absolutePath)
                player.prepareAsync()
                playerOwnsTemp = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (requestId == playbackRequestId) {
                    playbackRequestId++
                    playbackPositionJob?.cancel()
                    playbackPositionJob = null
                    mediaPlayer?.let { releasePlaybackResources(it, playbackTempFile) }
                    playbackState = EvidencePlaybackState()
                    snackbarHostState.showSnackbar("Decryption or playback failed: ${e.message}")
                }
            } finally {
                if (!playerOwnsTemp) withContext(NonCancellable + Dispatchers.IO) { tempAudio.delete() }
            }
        }
    }

    private fun startPlaybackPositionUpdates(requestId: Long) {
        playbackPositionJob?.cancel()
        playbackPositionJob = lifecycleScope.launch {
            while (requestId == playbackRequestId) {
                delay(250L)
                val player = mediaPlayer ?: return@launch
                val isPlaying = runCatching { player.isPlaying }.getOrDefault(false)
                if (!isPlaying) return@launch
                val position = runCatching { player.currentPosition.toLong() }.getOrDefault(playbackState.positionMs)
                playbackState = playbackState.copy(isPlaying = true, positionMs = position)
            }
        }
    }

    private fun seekEvidencePlayback(evidence: Evidence, positionMs: Long) {
        if (!vaultUnlocked || playbackState.evidenceId != evidence.id) return
        val player = mediaPlayer ?: return
        val bounded = positionMs.coerceIn(0L, playbackState.durationMs)
        try {
            player.seekTo(bounded, MediaPlayer.SEEK_CLOSEST)
            playbackState = playbackState.copy(positionMs = bounded)
        } catch (e: Exception) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Could not seek in this recording: ${e.message}") }
        }
    }

    private fun stopEvidencePlayback() {
        playbackRequestId++
        playbackLoadJob?.cancel()
        playbackLoadJob = null
        playbackPositionJob?.cancel()
        playbackPositionJob = null
        mediaPlayer?.let { releasePlaybackResources(it, playbackTempFile) }
        mediaPlayer = null
        playbackTempFile?.delete()
        playbackTempFile = null
        playbackState = EvidencePlaybackState()
    }

    private fun openMediaPreview(evidence: Evidence) {
        if (!vaultUnlocked || evidence.mediaType.equals("AUDIO", ignoreCase = true)) return
        closeMediaPreview()
        val encryptedFile = File(filesDir, evidence.encryptedFile)
        if (!encryptedFile.exists()) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Encrypted media file not found") }
            return
        }
        val requestId = ++mediaPreviewRequestId
        val extension = when {
            evidence.mimeType.contains("png", true) -> "png"
            evidence.mimeType.contains("webp", true) -> "webp"
            evidence.mimeType.contains("heic", true) -> "heic"
            evidence.mediaType.equals("VIDEO", true) -> "mp4"
            else -> "jpg"
        }
        val temporary = File(cacheDir, "temp_preview_${evidence.id}_$requestId.$extension")
        mediaPreviewState = EvidenceMediaPreviewState(
            evidenceId = evidence.id,
            mediaType = evidence.mediaType,
            mimeType = evidence.mimeType,
            isPreparing = true
        )
        mediaPreviewJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (temporary.exists() && !temporary.delete()) throw IOException("Could not clear preview cache")
                    Crypto.decrypt(encryptedFile, temporary, key)
                }
                if (requestId != mediaPreviewRequestId || !vaultUnlocked || !foreground) {
                    temporary.delete()
                    return@launch
                }
                mediaPreviewTempFile = temporary
                mediaPreviewState = EvidenceMediaPreviewState(
                    evidenceId = evidence.id,
                    mediaType = evidence.mediaType,
                    mimeType = evidence.mimeType,
                    filePath = temporary.absolutePath
                )
            } catch (cancelled: CancellationException) {
                temporary.delete()
                throw cancelled
            } catch (error: Exception) {
                temporary.delete()
                if (requestId == mediaPreviewRequestId) {
                    mediaPreviewState = EvidenceMediaPreviewState()
                    snackbarHostState.showSnackbar("Media preview failed: ${error.message}")
                }
            }
        }
    }

    private fun closeMediaPreview() {
        mediaPreviewRequestId++
        mediaPreviewJob?.cancel()
        mediaPreviewJob = null
        mediaPreviewTempFile?.delete()
        mediaPreviewTempFile = null
        mediaPreviewState = EvidenceMediaPreviewState()
    }

    private fun releasePlaybackResources(player: MediaPlayer, tempAudio: File?) {
        if (mediaPlayer === player) mediaPlayer = null
        runCatching { player.release() }
        tempAudio?.delete()
        if (playbackTempFile == tempAudio) playbackTempFile = null
    }

    override fun onDestroy() {
        super.onDestroy()
        gestureDetector.stop()
        offlineListeningJob?.cancel()
        stopEvidencePlayback()
        closeMediaPreview()
    }
}
