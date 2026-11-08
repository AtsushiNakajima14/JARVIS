# Build validation

Verified on 2026-11-08 (Asia/Manila).

- Debug APK build: passed; APK Signature Scheme v2 verifies.
- Optimized release APK build: passed with R8 and resource shrinking; unsigned, requiring your release signing key.
- Android test APK: compiled successfully (three Compose tests and one Keystore test).
- Unit tests: **22 passed**, zero failures/errors.
- Android lint: **0 errors, 20 warnings**. Remaining warnings recommend newer dependency versions; dependencies are explicitly pinned.
- APK metadata: `dev.jarvis`, version `1.0.0`, minimum SDK 26, target/compile SDK 37; launcher `dev.jarvis.MainActivity`.
- No Internet permission is present in the final app package.
- Packaged ABIs: arm64-v8a, armeabi-v7a, x86, x86_64. Every ABI includes Vosk.
- All packaged 64-bit native libraries have 16 KB ELF LOAD-segment alignment. The debug APK passes `zipalign -c -P 16 4`.
- Bundled offline model and Apache license are present in both APKs.
- Synthetic model smoke checks: ?Jarvis? recognized, unrelated speech rejected by wake grammar, ?open Discord? transcribed. These used the same model with the Windows Vosk 0.3.45 runtime; Android uses Vosk 0.3.75. This does not establish real microphone accuracy.

## Test suites

| Suite | Passed |
| --- | ---: |
| `dev.jarvis.assistant.parser.CommandParserTest` | 13 |
| `dev.jarvis.DatabaseMemoryTest` | 6 |
| `dev.jarvis.SkillMatchingTest` | 3 |

## APK artifacts

- `app/build/outputs/apk/debug/app-debug.apk` ? 97.5 MiB
  - SHA-256: `0d34f6c47c106847a3ca65ce0c2834cf54c5e922c529e5aea1a433845b541997`
- `app/build/outputs/apk/release/app-release-unsigned.apk` ? 79.3 MiB
  - SHA-256: `1623e951e100219fc23c8fe7eb1208f7de285e17dbb6ae4cf4ed0dffb26a3a81`

## Not verified here

No Android device or emulator was connected. Installation, full activity launch on hardware, actual microphone recognition, wake accuracy, screen-off/OEM reliability, provider language packs, permission dialogs, external apps and on-device Compose/Keystore tests have **not** been run. Follow [DEVICE-TESTING.md](DEVICE-TESTING.md) before relying on JARVIS as a daily assistant.

The debug APK is signed for local development and installation. The unsigned release artifact is not directly installable until signed.
