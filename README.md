# VAANI — Android Hackathon Prototype

VAANI is a privacy-first emergency safety prototype: a spoken safeword triggers
encrypted, tamper-evident audio evidence capture, entirely stored on-device.

## What is implemented
- Safe Mode with no persistent recording
- Voice-activated SafeWord trigger using Android's SpeechRecognizer (on-device
  where the OS/device supports it — see "Known limitations" below)
- Emergency recording using Android MediaRecorder
- On-device audio threat scoring via a bundled YAMNet TFLite model
- Active GPS/network location fix at the moment evidence is sealed (with a
  timeout fallback to the last known fix)
- AES-256-GCM encryption of evidence, with the key generated and held in the
  **Android Keystore** (hardware-backed on supported devices) — the raw key
  material is never stored in SharedPreferences or app files
- SHA-256 hash chain over sealed evidence, stored in local Room SQLite
- In-app **"Verify Chain Integrity"** action that recomputes the hash of every
  sealed evidence file on disk and re-derives the chain, flagging the exact
  entry where the recomputed hash breaks from the recorded one
- Local evidence vault UI with permission-denied feedback (mic/location)

## Known limitations (be upfront about these in the demo)
- **Safeword detection is not guaranteed fully offline.** We request the
  on-device recognizer via `EXTRA_PREFER_OFFLINE`, but Android does not
  guarantee this on every device/OS version — some devices will still route
  audio through a cloud speech API. A dedicated offline wake-word engine
  (e.g. OpenWakeWord/Porcupine) is the correct long-term fix; see below.
- The bundled YAMNet threat-scoring weights are a reasonable first pass but
  have not been validated against a labeled real-world dataset.
- No background/foreground-service mode yet — the app only listens/records
  while in the foreground.

## What remains for the full version
1. Replace the SpeechRecognizer safeword trigger with a dedicated offline
   wake-word model (OpenWakeWord/TFLite) for a real offline guarantee.
2. Validate/tune ThreatAnalyzer scoring weights against real recordings.
3. Add a background/foreground service for always-on listening, with proper
   Android 14+/15 foreground-service-type handling.
4. Add an evidence detail screen (waveform, map pin, full chain history view).
5. Add automated tests around Crypto.encrypt/decrypt and the chain-integrity
   verification logic.

## Run
1. Open the `SHELTER` folder in Android Studio.
2. Let Gradle sync.
3. Connect an Android phone (Android 8+ recommended) and Run.
4. Grant microphone and location permissions.
5. Press `TEST SAFEWORD — ACTIVATE`.
6. Record for a few seconds, then press `STOP & SEAL EVIDENCE`.
7. The encrypted evidence and hash chain will appear in the vault.
8. Press `Verify Chain Integrity` to confirm no evidence has been tampered
   with since it was sealed.

## Demo script
Safe Mode → press Test SafeWord → Emergency Mode → record 5–10 s → Stop & Seal
→ show encrypted file + threat score + SHA-256 chain → press "Verify Chain
Integrity" to show it passes → (optional, for a stronger demo) manually edit
one sealed `.enc` file on disk and re-run verification to show it correctly
flags that entry as tampered → explain that a real offline wake-word model is
the next integration layer.

## Important prototype disclosure
The threat analyzer runs real on-device YAMNet inference (not simulated), and
evidence keys are Keystore-backed. The safeword *trigger path*, however, is
still built on Android's general-purpose SpeechRecognizer rather than a
dedicated offline wake-word model — do not describe safeword detection as
fully offline until that replacement is made.
