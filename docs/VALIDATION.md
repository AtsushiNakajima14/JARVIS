# Build validation

Verified on 2026-11-09 (Asia/Manila), version **1.2.0**, version code **3**.

- Debug APK: built and APK Signature Scheme v2 verifies.
- Optimized release APK: built with R8/resource shrinking; unsigned and requires a release signing key before installation.
- Android test APK: compiled (three Compose tests and one Keystore test); no connected-device execution.
- Unit tests: **157 passed**, zero failures, errors or skipped tests.
- Android lint: **0 errors, 20 warnings**, all dependency update suggestions.
- APK metadata: `dev.jarvis`, minimum SDK 26, target/compile SDK 37; launcher `dev.jarvis.MainActivity`.
- No Internet permission is present in the APK. Google's separately installed recognition service may process speech online under its own permissions; local answers and notification processing use no cloud AI endpoint.
- Packaged `UnlockActivity` is `exported=false` and excluded from recents. It requests Android authentication and never collects a device credential.
- The debug signing certificate matches published 1.0.0 and 1.1.0, allowing an update without uninstalling. Certificate SHA-256: `d84aea7c56747fc78ee90001ddf15ab4331e59179f6e226f61b28b2043cc57fb`.
- Both APKs contain Vosk and local AI native libraries for arm64-v8a, armeabi-v7a, x86 and x86_64. R8 retains the local AI JNI entry points.
- Every packaged 64-bit native library has 16 KB ELF LOAD alignment. The local AI library has 16 KB alignment on all four ABIs. Both APKs pass `zipalign -c -P 16 4`.
- Both models and their licenses are present. The 91,893,088-byte local AI model matches its pinned SHA-256 in both APKs.

## Checks run

```powershell
./scripts/build.ps1 -Tasks @(
    ':assistant:parser:test', ':assistant:localai:testDebugUnitTest',
    ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug',
    ':app:assembleDebugAndroidTest', ':app:assembleRelease',
    '--no-daemon', '--no-parallel', '--max-workers=1'
)
```

| Suite | Passed |
| --- | ---: |
| `CommandLexiconTest` | 7 |
| `CommandParserTest` | 20 |
| `PersonalCommandParserTest` | 10 |
| `SpeechActivityTest` | 6 |
| `SpeechSelectionTest` | 7 |
| `LocalLlmTest` | 4 |
| `ApplicationIndexSchedulerTest` | 3 |
| `ApplicationIndexSyncTest` | 4 |
| `ConversationContextTest` | 4 |
| `DatabaseMemoryTest` | 6 |
| `GoogleRecognitionPolicyTest` | 11 |
| `LocalModelCopyTest` | 5 |
| `LocalPromptTest` | 10 |
| `NativeSpeechRegressionTest` | 1 |
| `NotificationPolicyTest` | 14 |
| `NotificationRepositoryTest` | 7 |
| `PrivateCommandPolicyTest` | 2 |
| `SkillMatchingTest` | 3 |
| `SpeechRecognitionTest` | 17 |
| `UnlockActivityTest` | 3 |
| `VoskModelLeaseTest` | 9 |
| `VoskResultReaderTest` | 4 |

## Personal assistant and notification evidence

Parser tests cover creator/owner identity, greetings, briefings, natural notification requests, arbitrary app filters, explicit notification follow-ups and protected message bodies. Six PCM-energy tests cover the new quiet-alert signal without modifying recognition input.

Seven Robolectric/Room notification repository tests exercise permission and opt-in requirements, importing current notifications after first consent, AES-GCM storage rather than plaintext, locked readouts, code/secret filtering, stale capture after deletion, independent app-only announcements, metadata-only operation, recent incoming senders and preview opt-out. These use a real in-memory Room database and AES-GCM test converter; they are not physical Android Keystore verification. Fourteen policy/ledger tests cover bounded history, expiry, deduplication, cooldowns, filtering and truthful notification-only scope.

Three Robolectric unlock tests verify that a secure locked device is not unlocked by the request, Android callback success finishes the flow, cancellation leaves it locked, and an already-unlocked device needs no authentication UI. The real Android authentication screen still requires device testing.

Credential-command tests cover omission of PIN/password disclosures and preservation of ordinary security-help questions. Notification text is not recorded in ordinary command history or local AI conversation. Source review and lifecycle guards cover cancelling a private readout on screen lock, deletion or permission revocation; actual TTS interruption and live notification timing remain device checks.

The on-phone AI prompt has a friendly CyCy persona and creator attribution, up to two short turns, a normal 1350-byte limit and a 440-byte compact fallback. A matching host-native tokenizer measured one normal prompt at 194 tokens and a dense Unicode fixture at 622 tokens; its compact retry used 165 tokens. This establishes the tested fallback behavior, not phone speed. Private context is excluded while locked and rechecked after model preparation/inference.

## Native host checks

The previous synthetic voice regressions passed **98 waveform checks** using the bundled model with Windows Vosk 0.3.45 (Android uses 0.3.75): 66 baseline cases across two English voices and three speaking rates, plus 32 edge cases for tones, prior speech, replay and combined/paused commands. Eight actual native results still pass through the Kotlin reader, wake trimming, confidence selector and parser in the current test suite. These synthetic checks do not establish real microphone or accent accuracy.

The pinned quantized model previously generated a real reply with the matching llama.cpp CPU host runtime. The Android JNI runtime remains compiled for all four ABIs; JNI unit tests use a fake backend for serialization, UTF-8, cancellation and resource limits. Android-native AI inference has not been run on attached hardware here.

Runtime: llama.cpp `bb4caa7540188872173c44d161602d9271386413`. Model SHA-256: `bcc3af2849ad6095af57e9b5cd43775256efdc66e306acb529172f92d0c04b03`.

## APK artifacts

- `app/build/outputs/apk/debug/app-debug.apk`: **199.2 MiB** (208,924,858 bytes).
  - Published as `JARVIS-1.2.0-debug.apk`.
  - SHA-256: `21980e23a097c58bcd7b2e3de8e670c44b9a9c43c003a400736c0add19a3cac7`.
- `app/build/outputs/apk/release/app-release-unsigned.apk`: **180.6 MiB** (189,388,514 bytes).
  - SHA-256: `050ed32e5c187dc14301f8a0615b73c28bff6047e5b3c95dca8aa0a73cb86f0a`.

## Remaining device checks

The owner reports that 1.1.0 works. No phone or emulator was connected during the 1.2.0 validation. New notification access/setup, live incoming previews, alert timing, RMS quiet detection, TTS cancellation on locking/revocation, actual authentication UI, microphone accuracy, app launching, latency, peak memory, battery and screen-off/OEM reliability still require the [device checklist](DEVICE-TESTING.md) on the Redmi A7 running Android 15 with 3 GB physical RAM. On-device Compose and Keystore tests were not run.

The tiny English model is intended for short conversation and can give inaccurate answers. Its CPU/thread/context/output/deadline limits and sequential model release do not establish acceptable speed on that phone. The downloadable APK is a debug-signed personal device test prerelease.
