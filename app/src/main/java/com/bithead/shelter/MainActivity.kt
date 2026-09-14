package com.bithead.shelter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.bithead.shelter.ai.ThreatAnalyzer
import com.bithead.shelter.ai.IncidentSummary
import com.bithead.shelter.data.AppDatabase
import com.bithead.shelter.data.Evidence
import com.bithead.shelter.security.Crypto
import com.bithead.shelter.security.AppDisguiseManager
import com.bithead.shelter.sensors.GestureDetector
import com.bithead.shelter.ui.VaaniApp
import com.bithead.shelter.ui.components.SafewordDialog
import com.bithead.shelter.ui.theme.ShelterTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.crypto.SecretKey
import kotlin.coroutines.resume

class MainActivity : FragmentActivity() {

    private enum class CaptureState { IDLE, RECORDING, SEALING }

    private data class SealResult(val entryId: Long, val rawDeleted: Boolean)

    private data class RecoveryResult(val recovered: Int, val failure: String? = null)

    companion object {
        // Shared across Activity recreation so a new startup recovery cannot
        // race an in-flight seal owned by the previous Activity instance.
        private val sealMutex = Mutex()

        @Volatile
        private var startupRecoveryComplete = false

        @Volatile
        private var sealingInProgress = false
    }

    private lateinit var db: AppDatabase
    private var recorder: MediaRecorder? = null
    private var rawFile: File? = null
    private lateinit var key: SecretKey
    private val analyzer by lazy { ThreatAnalyzer(this) }
    private var mediaPlayer: MediaPlayer? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var gestureDetector: GestureDetector
    private var autoStopJob: Job? = null
    private val snackbarHostState = SnackbarHostState()

    // ---- UI state, observed by Compose -----------------------------------
    private var captureState by mutableStateOf(CaptureState.IDLE)
    private var safewordState by mutableStateOf("HELP")
    private var threatLabel by mutableStateOf("Ready")
    private var threatScore by mutableIntStateOf(0)
    private var lastLat by mutableStateOf<Double?>(null)
    private var lastLng by mutableStateOf<Double?>(null)
    private var communityLat by mutableStateOf<Double?>(null)
    private var communityLng by mutableStateOf<Double?>(null)
    private var showSafewordDialog by mutableStateOf(false)
    private var liveThreatJob: Job? = null
    private var vaultUnlocked by mutableStateOf(false)
    private var biometricAvailable by mutableStateOf(true)
    private var listeningEnabled by mutableStateOf(true)
    private var gestureWakeMode by mutableStateOf(false)
    private var safewordListeningActive by mutableStateOf(false)
    private var disguiseEnabled by mutableStateOf(false)
    private var foreground = false
    private var restartListeningJob: Job? = null
    private var safewordWindowJob: Job? = null
    private var communityLocationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        db = AppDatabase.get(this)
        key = loadOrCreateMasterKey()
        val needsStartupRecovery = !startupRecoveryComplete
        val needsPipelineBarrier = needsStartupRecovery || sealingInProgress
        if (needsPipelineBarrier) captureState = CaptureState.SEALING
        safewordState = loadSafeword()
        listeningEnabled = getSharedPreferences("shelter_prefs", MODE_PRIVATE).getBoolean("listening", true)
        gestureWakeMode = getSharedPreferences("shelter_prefs", MODE_PRIVATE).getBoolean("gesture_wake_mode", false)
        disguiseEnabled = AppDisguiseManager.isEnabled(this)
        AppDisguiseManager.applyLauncherState(this, disguiseEnabled)
        requestPermissionsIfNeeded()
        initSpeechRecognizer()
        gestureDetector = GestureDetector(this) {
            if (gestureWakeMode && listeningEnabled && foreground) startSafewordWindow()
        }
        checkBiometricAvailability()

        setContent {
            ShelterTheme {
                val evidenceList by db.evidenceDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
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
                    onPlay = { playEvidence(it) },
                    onExport = { exportChainOfCustody(it) },
                    onVerifyChain = { verifyEvidenceChain() },
                    onUnlockVault = { unlockVault() },
                    listening = listeningEnabled,
                    listeningActive = safewordListeningActive,
                    onListeningChange = { updateListeningEnabled(it) },
                    gestureWakeMode = gestureWakeMode,
                    onGestureWakeModeChange = { updateGestureWakeMode(it) },
                    disguiseEnabled = disguiseEnabled,
                    onDisguiseEnabledChange = { updateAppDisguise(it) },
                    onLockVault = { vaultUnlocked = false },
                    onRefreshMapLocation = { refreshCommunityLocation() }
                )
                if (showSafewordDialog) {
                    SafewordDialog(
                        current = safewordState,
                        onDismiss = { showSafewordDialog = false },
                        onSave = { saveSafeword(it); showSafewordDialog = false }
                    )
                }
            }
        }
        if (needsPipelineBarrier) {
            lifecycleScope.launch {
                try {
                    if (needsStartupRecovery) recoverOrphanedEvidence()
                    awaitActiveSeal()
                } finally {
                    captureState = CaptureState.IDLE
                    if (!gestureWakeMode) restartSafewordListening()
                }
            }
        }
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
        getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit().putString("user_safeword", safewordState).apply()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 10)
    }

    /**
     * Requests a fresh GPS/network fix instead of relying on a possibly stale
     * or null getLastKnownLocation() reading. Falls back to the last-known
     * fix only if a live update can't be obtained within the timeout.
     */
    private suspend fun getLocation(): Pair<Double?, Double?> {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Location permission denied — location features are unavailable") }
            return Pair(null, null)
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            snackbarHostState.showSnackbar("Location services are off — using last known fix if any")
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            return Pair(loc?.latitude, loc?.longitude)
        }

        return suspendCancellableCoroutine { cont ->
            var resumed = false
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    if (!resumed) {
                        resumed = true
                        lm.removeUpdates(this)
                        cont.resume(Pair(location.latitude, location.longitude))
                    }
                }
            }
            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, mainLooper)
            } catch (_: SecurityException) {
                if (!resumed) { resumed = true; cont.resume(Pair(null, null)) }
                return@suspendCancellableCoroutine
            }

            // Timeout: fall back to last-known fix rather than hanging forever.
            lifecycleScope.launch {
                delay(4000)
                if (!resumed) {
                    resumed = true
                    lm.removeUpdates(listener)
                    val loc = lm.getLastKnownLocation(provider)
                    cont.resume(Pair(loc?.latitude, loc?.longitude))
                }
            }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }
    }

    private fun updateAppDisguise(enabled: Boolean) {
        AppDisguiseManager.setEnabled(this, enabled)
        disguiseEnabled = enabled
    }

    private fun refreshCommunityLocation() {
        communityLocationJob?.cancel()
        communityLocationJob = lifecycleScope.launch {
            val (latitude, longitude) = getLocation()
            communityLat = latitude
            communityLng = longitude
        }
    }

    // ---- Safeword voice trigger ----------------------------------------------

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listeningEnabled = false
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    safewordListeningActive = captureState == CaptureState.IDLE
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    safewordListeningActive = false
                    if (captureState == CaptureState.IDLE && !gestureWakeMode) restartSafewordListening()
                }
                override fun onResults(results: Bundle?) {
                    checkSafewordMatches(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                    safewordListeningActive = false
                    if (captureState == CaptureState.IDLE && !gestureWakeMode) restartSafewordListening()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    checkSafewordMatches(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun checkSafewordMatches(matches: ArrayList<String>?) {
        if (captureState != CaptureState.IDLE || !listeningEnabled || !foreground ||
            (gestureWakeMode && !safewordListeningActive)) return
        matches?.forEach { phrase -> if (phrase.contains(safewordState, ignoreCase = true)) activateEmergency() }
    }

    private fun startSafewordListening(): Boolean {
        if (captureState != CaptureState.IDLE || !foreground || !listeningEnabled || speechRecognizer == null ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Prefer the on-device recognizer where the OS/device supports it,
                // so the safeword phrase isn't sent to a cloud speech API by default.
                // NOTE: not all Android versions/devices honor this — see README
                // "Known limitations" for the real offline-guarantee status.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            speechRecognizer?.startListening(intent)
            safewordListeningActive = true
            return true
        } catch (_: Exception) {
            safewordListeningActive = false
            return false
        }
    }

    private fun stopSafewordListening() {
        speechRecognizer?.stopListening()
        safewordListeningActive = false
    }

    private fun startSafewordWindow() {
        if (!gestureWakeMode || safewordListeningActive || captureState != CaptureState.IDLE ||
            !foreground || !listeningEnabled) return
        getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }?.vibrate(
            VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
        )
        if (!startSafewordListening()) return
        safewordWindowJob?.cancel()
        safewordWindowJob = lifecycleScope.launch {
            delay(5_000L)
            stopSafewordListening()
        }
    }

    private fun restartSafewordListening() {
        restartListeningJob?.cancel()
        if (listeningEnabled && foreground && captureState == CaptureState.IDLE && !gestureWakeMode) {
            restartListeningJob = lifecycleScope.launch { delay(1000); startSafewordListening() }
        }
    }

    private fun updateListeningEnabled(enabled: Boolean) {
        if (enabled && (speechRecognizer == null ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Enable microphone access and a speech recognition service to listen for your safeword") }
            requestPermissionsIfNeeded()
            return
        }
        listeningEnabled = enabled
        getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit().putBoolean("listening", enabled).apply()
        restartListeningJob?.cancel()
        safewordWindowJob?.cancel()
        speechRecognizer?.cancel()
        safewordListeningActive = false
        if (enabled && !gestureWakeMode) startSafewordListening()
    }

    private fun updateGestureWakeMode(enabled: Boolean) {
        gestureWakeMode = enabled
        getSharedPreferences("shelter_prefs", MODE_PRIVATE).edit().putBoolean("gesture_wake_mode", enabled).apply()
        restartListeningJob?.cancel()
        safewordWindowJob?.cancel()
        speechRecognizer?.cancel()
        safewordListeningActive = false
        if (!enabled && listeningEnabled && foreground) startSafewordListening()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        checkBiometricAvailability()
        gestureDetector.start()
        if (!gestureWakeMode) startSafewordListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                listeningEnabled = false
            } else if (!gestureWakeMode) {
                startSafewordListening()
            }
        }
    }

    // ---- Emergency capture -----------------------------------------------------

    private fun activateEmergency() {
        if (captureState != CaptureState.IDLE || sealingInProgress) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Microphone permission denied — cannot record evidence") }
            requestPermissionsIfNeeded()
            return
        }
        restartListeningJob?.cancel()
        safewordWindowJob?.cancel()
        safewordListeningActive = false
        captureState = CaptureState.RECORDING
        threatLabel = "Monitoring peaks…"
        threatScore = 0
        speechRecognizer?.cancel()

        // Live-tick the threat meter in the UI while recording, instead of
        // only showing a number after the fact.
        liveThreatJob?.cancel()
        liveThreatJob = lifecycleScope.launch {
            analyzer.liveResult.collect { live ->
                if (captureState == CaptureState.RECORDING) {
                    threatLabel = live.label
                    threatScore = live.score
                }
            }
        }

        try {
            val pendingDir = File(filesDir, "pending")
            if (!pendingDir.exists() && !pendingDir.mkdirs()) {
                throw IOException("Could not create private pending-evidence storage")
            }
            val captureId = System.currentTimeMillis()
            val file = File(pendingDir, "evidence_$captureId.raw.m4a")
            rawFile = file
            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            // The evidence recorder gets the microphone first. Starting the
            // analyzer before MediaRecorder can starve or reject capture on
            // devices that do not support concurrent microphone clients.
            analyzer.startListening()

            autoStopJob?.cancel()
            autoStopJob = lifecycleScope.launch {
                delay(5 * 60 * 1000L)
                if (captureState == CaptureState.RECORDING) stopEmergency()
            }
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            analyzer.stopAndAnalyze()
            val preservedRaw = rawFile?.takeIf { it.exists() }
            rawFile = null
            threatLabel = "Recording failed: ${e.message}"
            captureState = CaptureState.IDLE
            lifecycleScope.launch {
                val preserved = if (preservedRaw != null) " Any captured bytes were retained for recovery." else ""
                snackbarHostState.showSnackbar("Recording could not start: ${e.message ?: "microphone unavailable"}.$preserved")
            }
            if (!gestureWakeMode) restartSafewordListening()
        }
    }

    private fun stopEmergency() {
        if (captureState != CaptureState.RECORDING) return
        sealingInProgress = true
        captureState = CaptureState.SEALING
        liveThreatJob?.cancel()
        liveThreatJob = null
        autoStopJob?.cancel()
        autoStopJob = null

        val result = analyzer.stopAndAnalyze()
        val recorderStopped = recorder?.runCatching { stop() }?.isSuccess == true
        recorder?.release()
        recorder = null
        val raw = rawFile
        rawFile = null

        if (!recorderStopped || raw == null || !raw.exists() || raw.length() == 0L) {
            threatLabel = "Recording could not be finalized"
            sealingInProgress = false
            captureState = CaptureState.IDLE
            lifecycleScope.launch {
                snackbarHostState.showSnackbar(
                    "Evidence could not be finalized. The pending raw file was retained for recovery."
                )
            }
            if (!gestureWakeMode) restartSafewordListening()
            return
        }

        lifecycleScope.launch {
            val sealed = try {
                sealEvidence(raw, result.label, result.score)
            } catch (e: Exception) {
                threatLabel = "Sealing failed: ${e.message}"
                snackbarHostState.showSnackbar(
                    "Evidence could not be sealed: ${e.message ?: "storage error"}. Raw audio was retained for recovery."
                )
                return@launch
            } finally {
                sealingInProgress = false
                captureState = CaptureState.IDLE
                if (!gestureWakeMode) restartSafewordListening()
            }

            threatLabel = result.label
            threatScore = result.score
            snackbarHostState.showSnackbar(
                if (sealed.rawDeleted) {
                    "Evidence sealed and added to the vault ✓"
                } else {
                    "Evidence sealed. The encrypted record is safe, but plaintext cleanup needs attention."
                }
            )

            // Location is optional metadata. It must never block the
            // evidence row from appearing in the Vault or invalidate it.
            runCatching {
                val (latitude, longitude) = getLocation()
                lastLat = latitude
                lastLng = longitude
                if (latitude != null && longitude != null) {
                    withContext(Dispatchers.IO) {
                        db.evidenceDao().updateLocation(sealed.entryId, latitude, longitude)
                    }
                }
            }
        }
    }

    private suspend fun sealEvidence(raw: File, label: String, score: Int): SealResult =
        withContext(NonCancellable + Dispatchers.IO) {
            sealMutex.withLock {
                val createdAt = raw.name
                    .removePrefix("evidence_")
                    .removeSuffix(".raw.m4a")
                    .toLongOrNull()
                    ?: raw.lastModified().takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                val encrypted = File(filesDir, "evidence_$createdAt.enc")
                val temporary = File(filesDir, "${encrypted.name}.tmp")
                var finalized = false
                var committed = false

                try {
                    if (encrypted.exists()) throw IOException("Evidence destination already exists")
                    if (temporary.exists() && !temporary.delete()) {
                        throw IOException("Could not clear an old temporary evidence file")
                    }

                    Crypto.encrypt(raw, temporary, key)
                    moveAtomically(temporary, encrypted)
                    finalized = true

                    val fileHash = Crypto.sha256(encrypted)
                    val entryId = db.evidenceDao().insertChained(
                        Evidence(
                            createdAt = createdAt,
                            encryptedFile = encrypted.name,
                            latitude = null,
                            longitude = null,
                            threatLabel = label,
                            threatScore = score,
                            sha256 = "",
                            previousHash = null
                        ),
                        fileHash
                    )
                    committed = true
                    SealResult(entryId, raw.delete())
                } catch (failure: Exception) {
                    temporary.delete()
                    if (finalized && !committed) {
                        val lookup = runCatching { db.evidenceDao().entryIdForFile(encrypted.name) }
                        if (lookup.isSuccess && lookup.getOrNull() != null) {
                            return@withLock SealResult(lookup.getOrThrow()!!, raw.delete())
                        }
                        // Delete only when Room positively confirms that the
                        // transaction did not commit. Otherwise leave the final
                        // file for startup recovery to reconcile safely.
                        if (lookup.isSuccess) encrypted.delete()
                    }
                    throw failure
                }
            }
        }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private suspend fun awaitActiveSeal() = withContext(NonCancellable + Dispatchers.IO) {
        do {
            sealMutex.withLock { }
        } while (sealingInProgress)
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
        restartListeningJob?.cancel()
        safewordWindowJob?.cancel()
        communityLocationJob?.cancel()
        speechRecognizer?.cancel()
        safewordListeningActive = false
        // Re-lock the vault whenever the app leaves the foreground, so
        // background/multitasking can't be used to skip the biometric check.
        vaultUnlocked = false
    }

    private fun verifyEvidenceChain() {
        lifecycleScope.launch {
            val entries = db.evidenceDao().allAscending()
            if (entries.isEmpty()) {
                snackbarHostState.showSnackbar("No evidence to verify yet")
                return@launch
            }
            var previousChain: String? = null
            var brokenAt: Long? = null
            for (item in entries) {
                val file = File(filesDir, item.encryptedFile)
                if (!file.exists()) { brokenAt = item.id; break }
                val currentFileHash = Crypto.sha256(file)
                val expectedChain = Crypto.chainHash(currentFileHash, previousChain)
                if (expectedChain != item.sha256 || previousChain != item.previousHash) {
                    brokenAt = item.id
                    break
                }
                previousChain = item.sha256
            }
            if (brokenAt == null) {
                snackbarHostState.showSnackbar("Chain verified ✓ — all ${entries.size} entries intact, no tampering detected")
            } else {
                snackbarHostState.showSnackbar("⚠ Tamper detected: evidence #$brokenAt does not match its recorded hash chain")
            }
        }
    }

    private suspend fun recoverOrphanedEvidence() {
        val result = withContext(NonCancellable + Dispatchers.IO) {
            sealMutex.withLock {
                if (startupRecoveryComplete) return@withLock null
                try {
                    val dao = db.evidenceDao()
                    val knownFiles = dao.allEncryptedFileNames().toHashSet()

                    // A crash after the Room commit but before plaintext cleanup
                    // can leave the matching pending raw file behind. A present
                    // encrypted file plus its DB row proves that cleanup is safe.
                    knownFiles.forEach { fileName ->
                        if (File(filesDir, fileName).exists()) pendingRawFor(fileName)?.delete()
                    }

                    val orphanedFiles = filesDir.listFiles()
                        .orEmpty()
                        .filter { file ->
                            file.isFile && file.name.matches(Regex("evidence_\\d+\\.enc")) &&
                                file.name !in knownFiles
                        }
                        .sortedWith(compareBy<File>(
                            { evidenceTimestamp(it.name) ?: Long.MAX_VALUE },
                            { it.lastModified() },
                            { it.name }
                        ))
                    var recovered = 0
                    var failure: String? = null

                    for (file in orphanedFiles) {
                        try {
                            if (file.length() <= 28L) throw IOException("${file.name} is incomplete")
                            val fileHash = Crypto.sha256(file)
                            val createdAt = evidenceTimestamp(file.name) ?: file.lastModified()
                            dao.insertChained(
                                Evidence(
                                    createdAt = createdAt,
                                    encryptedFile = file.name,
                                    latitude = null,
                                    longitude = null,
                                    threatLabel = "Recovered evidence",
                                    threatScore = 0,
                                    sha256 = "",
                                    previousHash = null
                                ),
                                fileHash
                            )
                            pendingRawFor(file.name)?.delete()
                            recovered++
                        } catch (e: Exception) {
                            // Preserve this and all later files. Appending a newer
                            // orphan first would reverse their capture order.
                            failure = e.message ?: "storage error"
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
            snackbarHostState.showSnackbar("Evidence recovery paused: $it. Files were preserved.")
        }
    }

    private fun evidenceTimestamp(fileName: String): Long? = fileName
        .removePrefix("evidence_")
        .removeSuffix(".enc")
        .toLongOrNull()

    private fun pendingRawFor(encryptedFileName: String): File? {
        val captureId = evidenceTimestamp(encryptedFileName) ?: return null
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

    private fun playEvidence(evidence: Evidence) {
        if (!vaultUnlocked) return
        lifecycleScope.launch {
            val encryptedFile = File(filesDir, evidence.encryptedFile)
            if (!encryptedFile.exists()) {
                snackbarHostState.showSnackbar("Encrypted file not found")
                return@launch
            }
            val tempAudio = File(cacheDir, "temp_play_${evidence.id}.m4a")
            try {
                Crypto.decrypt(encryptedFile, tempAudio, key)
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempAudio.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener { tempAudio.delete() }
                }
                snackbarHostState.showSnackbar("Playing evidence #${evidence.id}…")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Decryption failed: ${e.message}")
                tempAudio.delete()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gestureDetector.stop()
        speechRecognizer?.destroy()
        mediaPlayer?.release()
        mediaPlayer = null
        analyzer.close()
    }
}
