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
import com.bithead.shelter.ui.VaaniApp
import com.bithead.shelter.ui.components.SafewordDialog
import com.bithead.shelter.ui.theme.ShelterTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import javax.crypto.SecretKey
import kotlin.coroutines.resume

class MainActivity : FragmentActivity() {

    private lateinit var db: AppDatabase
    private var recorder: MediaRecorder? = null
    private var rawFile: File? = null
    private lateinit var key: SecretKey
    private val analyzer by lazy { ThreatAnalyzer(this) }
    private var mediaPlayer: MediaPlayer? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var autoStopJob: Job? = null
    private val snackbarHostState = SnackbarHostState()

    // ---- UI state, observed by Compose -----------------------------------
    private var emergency by mutableStateOf(false)
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
    private var foreground = false
    private var restartListeningJob: Job? = null
    private var communityLocationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        db = AppDatabase.get(this)
        key = loadOrCreateMasterKey()
        safewordState = loadSafeword()
        listeningEnabled = getSharedPreferences("shelter_prefs", MODE_PRIVATE).getBoolean("listening", true)
        requestPermissionsIfNeeded()
        initSpeechRecognizer()
        checkBiometricAvailability()

        setContent {
            ShelterTheme {
                val evidenceList by db.evidenceDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                VaaniApp(
                    isEmergency = emergency,
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
                    onTrigger = { if (emergency) stopEmergency() else activateEmergency() },
                    onEditSafeword = { showSafewordDialog = true },
                    onPlay = { playEvidence(it) },
                    onExport = { exportChainOfCustody(it) },
                    onVerifyChain = { verifyEvidenceChain() },
                    onUnlockVault = { unlockVault() },
                    listening = listeningEnabled,
                    onListeningChange = { updateListeningEnabled(it) },
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
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { if (!emergency) restartSafewordListening() }
                override fun onResults(results: Bundle?) {
                    checkSafewordMatches(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                    if (!emergency) restartSafewordListening()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    checkSafewordMatches(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun checkSafewordMatches(matches: ArrayList<String>?) {
        if (!listeningEnabled || !foreground) return
        matches?.forEach { phrase -> if (phrase.contains(safewordState, ignoreCase = true)) activateEmergency() }
    }

    private fun startSafewordListening() {
        if (emergency || !foreground || !listeningEnabled || speechRecognizer == null ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
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
        } catch (_: Exception) {}
    }

    private fun restartSafewordListening() {
        restartListeningJob?.cancel()
        if (listeningEnabled && foreground && !emergency) {
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
        speechRecognizer?.cancel()
        if (enabled) startSafewordListening()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        checkBiometricAvailability()
        startSafewordListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                listeningEnabled = false
            } else {
                startSafewordListening()
            }
        }
    }

    // ---- Emergency capture -----------------------------------------------------

    private fun activateEmergency() {
        if (emergency) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch { snackbarHostState.showSnackbar("Microphone permission denied — cannot record evidence") }
            requestPermissionsIfNeeded()
            return
        }
        restartListeningJob?.cancel()
        emergency = true
        threatLabel = "Monitoring peaks…"
        threatScore = 0
        speechRecognizer?.stopListening()
        analyzer.startListening()

        // Live-tick the threat meter in the UI while recording, instead of
        // only showing a number after the fact.
        liveThreatJob?.cancel()
        liveThreatJob = lifecycleScope.launch {
            analyzer.liveResult.collect { live ->
                if (emergency) {
                    threatLabel = live.label
                    threatScore = live.score
                }
            }
        }

        try {
            val file = File(cacheDir, "raw_${System.currentTimeMillis()}.m4a")
            rawFile = file
            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            autoStopJob?.cancel()
            autoStopJob = lifecycleScope.launch {
                delay(5 * 60 * 1000L)
                if (emergency) stopEmergency()
            }
        } catch (e: Exception) {
            threatLabel = "Recording failed: ${e.message}"
            emergency = false
        }
    }

    private fun stopEmergency() {
        if (!emergency) return
        liveThreatJob?.cancel()
        liveThreatJob = null
        autoStopJob?.cancel()
        autoStopJob = null

        val result = analyzer.stopAndAnalyze()
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        val raw = rawFile ?: return
        emergency = false

        val encrypted = File(filesDir, "evidence_${System.currentTimeMillis()}.enc")
        try {
            Crypto.encrypt(raw, encrypted, key)
            val fileHash = Crypto.sha256(encrypted)

            lifecycleScope.launch {
                val (lat, lng) = getLocation()
                lastLat = lat
                lastLng = lng
                val previous = db.evidenceDao().latest()?.sha256
                val chain = Crypto.chainHash(fileHash, previous)
                db.evidenceDao().insert(
                    Evidence(
                        createdAt = System.currentTimeMillis(),
                        encryptedFile = encrypted.name,
                        latitude = lat,
                        longitude = lng,
                        threatLabel = result.label,
                        threatScore = result.score,
                        sha256 = chain,
                        previousHash = previous
                    )
                )
                threatLabel = result.label
                threatScore = result.score
                snackbarHostState.showSnackbar("Evidence sealed and chained ✓")
                startSafewordListening()
            }
        } catch (e: Exception) {
            threatLabel = "Sealing failed: ${e.message}"
            startSafewordListening()
        } finally {
            rawFile?.delete()
            rawFile = null
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
        restartListeningJob?.cancel()
        communityLocationJob?.cancel()
        speechRecognizer?.cancel()
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
        speechRecognizer?.destroy()
        mediaPlayer?.release()
        mediaPlayer = null
        analyzer.close()
    }
}
