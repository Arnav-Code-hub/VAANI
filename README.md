# VAANI Android prototype

VAANI records emergency evidence locally, encrypts it with an Android Keystore
AES-GCM key, and links committed vault records in a SHA-256 chain. The current
project keeps the dark Compose UI, Notes disguise, media vault, trusted-contact
SMS, and Leaflet community map.

## Current recording behavior

- Manual and pocket-triggered audio run in a microphone foreground service, so
  manual recording continues when the app moves to the background. Both stop
  after five minutes for now, or earlier when the user stops them or available
  storage falls below 100 MiB. The cap stays until segment hand-off is verified
  on a physical phone.
- The recorder hands off approximately 30-second files. One seal worker commits
  them in capture order. A seal failure retains the raw file and later segments
  for recovery. Startup recovery also handles finalized encrypted orphans and
  preserves audio files that cannot be validated as playable.
- The service sends one emergency SMS batch to the contacts configured when
  an incident starts. It sends evidence receipts to that same contact snapshot
  after committed segments and tracks multipart send/delivery callbacks.
  Settings reports alert and receipt outcomes separately for each contact.
  SMS permission, SIM, signal, and carrier delivery remain external dependencies.
- Pocket detection uses a wake-up accelerometer when available. Otherwise it
  holds a partial wake lock while armed and reports that fallback in Settings.
  A persistent foreground-service notification remains visible.
- The debug tamper demo alters a temporary encrypted copy; it does not modify
  the vault's original ciphertext.

## Voice and language limitations

The app bundles a multilingual Whisper model and uses local 16 kHz PCM capture
for safeword recognition. It does not invoke an Android speech provider. Voice
arming requires a successful local test for the selected safeword and language
(English, Hindi, Bengali, Marathi, or Tamil). The offline engine currently
supports arm64 Android devices only. Continuous mode holds the microphone open
while the app is foregrounded; gesture and pocket safeword modes open a 12-second
window after a jerk. Manual and instant-gesture recording remain available if
voice recognition is unavailable.

The app also bundles an IndicTrans2 INT8 ONNX export for generated, noncritical
Vault summaries. Fixed safety text and emergency SMS remain in English until
their translations receive human review. Contact language preferences are saved
but do not yet change SMS content. See [offline model provenance](docs/OFFLINE_MODELS.md)
for versions, hashes, and licenses. Recognition accuracy, inference latency, and
translation output have not yet passed physical-phone and fresh-install
airplane-mode tests; do not rely on voice protection for an emergency yet.

## Build and check

Run `./gradlew assembleDebug testDebugUnitTest`. The application ID remains
`com.bithead.shelter` and the Room database remains `shelter.db` so an APK
signed with the same key can upgrade an existing installation without deleting
its vault. Test the microphone foreground service, 30-second hand-offs,
screen-locked pocket trigger, SMS callbacks, and migration on a physical phone
before relying on it for emergency use.

## Publishing source to GitHub

Install Git LFS before adding files. The models under
`app/src/main/assets/models/` are LFS-tracked by `.gitattributes`; a regular Git
push cannot accept the largest model file. Do not commit signing keys,
`keystore.properties`, `local.properties`, APKs, build outputs, or the cached
downloads under `third_party/`. Those are excluded by `.gitignore`.

The existing local `app/vaani-debug.keystore` keeps debug APK updates compatible
on this computer. It is private and not uploaded. A fresh clone uses Android's
normal generated debug key, so its debug APK will not update an installation
signed with this computer's key. Keep the permanent release keystore backed up
separately for future release updates.
