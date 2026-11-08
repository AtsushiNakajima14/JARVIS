# Build validation

Verified on 2026-11-09 (Asia/Manila), version **1.1.0**, version code **2**.

- Debug APK build: passed; APK Signature Scheme v2 verifies.
- Optimized release APK: passed with R8 and resource shrinking; unsigned and requires a release signing key before installation.
- Android test APK: compiled (three Compose tests and one Keystore test); device execution remains pending.
- Unit tests: **110 passed**, zero failures, errors or skipped tests.
- Android lint: **0 errors, 20 warnings**, all dependency update suggestions (12 `GradleDependency`, 8 `NewerVersionAvailable`).
- APK metadata: `dev.jarvis`, minimum SDK 26, target/compile SDK 37; launcher `dev.jarvis.MainActivity`.
- No Internet permission is present in the APK. The separately installed Google speech service may process audio online under its own permissions.
- Both APKs contain arm64-v8a, armeabi-v7a, x86 and x86_64 native libraries for Vosk and the real local AI runtime.
- Every packaged 64-bit native library has at least 16 KB ELF LOAD-segment alignment. The new local AI library has 16 KB alignment on all four ABIs. Both APKs pass `zipalign -c -P 16 4`.
- Both bundled models, the Apache model licenses and llama.cpp MIT notice are present in both APKs. The local AI model is exactly 91,893,088 bytes and matches its pinned SHA-256.
- R8 retains `dev.jarvis.localai.NativeLlm` and its JNI methods.
- The debug APK has the same signing certificate as published 1.0.0, allowing an update without uninstalling. Certificate SHA-256: `d84aea7c56747fc78ee90001ddf15ab4331e59179f6e226f61b28b2043cc57fb`.

## Checks run

The full debug/release build passed. A repeated incremental lint run hit an internal Kotlin FIR analyzer exception while examining tests. Repeating all checks in a fresh, single-worker process passed without disabling lint checks:

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
| `SpeechSelectionTest` | 7 |
| `LocalLlmTest` | 4 |
| `ApplicationIndexSchedulerTest` | 3 |
| `ApplicationIndexSyncTest` | 4 |
| `ConversationContextTest` | 4 |
| `DatabaseMemoryTest` | 6 |
| `GoogleRecognitionPolicyTest` | 10 |
| `LocalModelCopyTest` | 5 |
| `LocalPromptTest` | 6 |
| `NativeSpeechRegressionTest` | 1 |
| `SkillMatchingTest` | 3 |
| `SpeechRecognitionTest` | 17 |
| `VoskModelLeaseTest` | 9 |
| `VoskResultReaderTest` | 4 |

## Native host checks

Synthetic speech tests passed **98 waveform checks**: 66 baseline cases across two Windows English voices and three speaking rates, plus 32 edge cases covering readiness tones, earlier speech, replay boundaries, unrelated speech and combined/paused battery and Discord commands. These used the bundled model with **Windows Vosk 0.3.45**; Android uses **Vosk 0.3.75**. Eight actual decoder results are additionally passed through the Kotlin reader, wake trimming, confidence selector and parser in `NativeSpeechRegressionTest`. Synthetic host checks do not establish real microphone or accent accuracy.

The quantized SmolLM2-135M-Instruct model generated a real answer with the matching pinned llama.cpp CPU host runtime. This confirms genuine model inference, not target-phone performance. Android JNI libraries compiled and linked for all four ABIs, and their three JNI entry points were verified. JNI unit tests exercise serialization, UTF-8, cancellation and resource limits using a fake backend; native inference itself has not been run on Android hardware here.

Runtime source: llama.cpp commit `bb4caa7540188872173c44d161602d9271386413`. Model SHA-256: `bcc3af2849ad6095af57e9b5cd43775256efdc66e306acb529172f92d0c04b03`.

## APK artifacts

- `app/build/outputs/apk/debug/app-debug.apk`: **199.2 MiB** (208,888,223 bytes).
  - Published as `JARVIS-1.1.0-debug.apk`.
  - SHA-256: `25cea77ceef9c7474a2214b328bf89a7270d70fe152f079a6f3754c014655fa2`.
- `app/build/outputs/apk/release/app-release-unsigned.apk`: **180.6 MiB** (189,355,670 bytes).
  - SHA-256: `055f587478d01c7a1ea5c0ceaa8c74a67063cc973aea0ef823c8ae796de660d9`.

## Not verified here

No Android device or emulator was connected. Installation, actual Google speech binding/recognition, real microphone accuracy, latency, peak memory, battery use, screen-off/OEM reliability, provider language packs, permission dialogs, external app launches and on-device Compose/Keystore tests have **not** been run. The target Redmi A7 with Android 15 and 3 GB physical RAM needs the [device checklist](DEVICE-TESTING.md).

The bundled tiny English model is intended for brief chat and can produce inaccurate answers. Its 512-token context, two CPU threads, 64-token output cap, 20-second deadline, memory guard and sequential model loading bound work; they do not establish acceptable phone speed. The downloadable debug APK is a personal device test prerelease.
