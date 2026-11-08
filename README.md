# JARVIS

A native personal Android voice assistant: Kotlin, Compose Material 3, Hilt, Room, DataStore and coroutines. Minimum Android 8.0 (API 26), compile/target Android 17 (API 37).

## Install on your phone

Download `JARVIS-1.0.0-debug.apk` from [GitHub Releases](https://github.com/AtsushiNakajima14/JARVIS/releases). Open the APK on your Android phone, allow installation from your browser or Files app when prompted, then install JARVIS. Allow Microphone and Notifications, enable the wake word, say **Jarvis**, wait for **Yes?**, then speak your command.

The downloadable APK is debug-signed for personal testing. Automated checks pass, but actual device installation, microphone accuracy and screen-off reliability still need the [device acceptance checks](docs/DEVICE-TESTING.md).

## Build

Open this directory in Android Studio with JDK 17 or newer and Android SDK 37 installed. The bundled English Vosk model is in `app/src/main/assets/model-en-us`; the app needs no model download at runtime.

On Windows, this project can provision its own isolated tools:

```powershell
python scripts/bootstrap.py
./scripts/build.ps1
```

The bootstrap downloads Amazon Corretto 17, Gradle 9.3.1, the Android SDK and the Vosk English model into `.tooling/`. Downloaded Android SDK license text is retained there. Nothing is installed system-wide. `local.properties` is machine-local and ignored by Git. The Gradle wrapper supports the usual commands when JDK and SDK are configured:

```powershell
./gradlew.bat :assistant:parser:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew.bat :app:assembleDebugAndroidTest
./gradlew.bat :app:connectedDebugAndroidTest
```

The installable, debug-signed APK is `app/build/outputs/apk/debug/app-debug.apk`. A release build uses `assembleRelease`; configure your own signing key locally before installing or distributing a release. No private signing key is committed.

## First use

1. Install the APK and open JARVIS. Let the local application scan finish.
2. Tap **Enable “Jarvis” wake word** and allow microphone access. Enable notifications in the Permissions screen so you can see service status, receive timers and complete background actions.
3. Say **Jarvis**, wait for **Yes?**, then speak a command. Alternatively tap the microphone or type a command.
4. Optional: allow Camera for the torch, Contacts for name lookup, Calendar for a seven-day event index, Usage access for usage/storage statistics, or Notification access for music controls.
5. Install an offline TTS voice in Android settings if your device has none. Select the language/voice under Settings. Exact alarms improve timer precision; battery settings can improve screen-off reliability.

Examples:

- “Open Discord” / “Buksan ang Discord” / “Ablihi ang Discord”
- “Turn on flashlight” / “Patayin ang flashlight”
- “What is my battery percentage?” / “Set volume to 50 percent”
- “Set a timer for five minutes”
- “Call Mom” → confirmation → opens the dialer; you place the call there
- “Send a message to John saying I will be there” → confirmation → opens the composer; you send it there
- “Pause music” / “Next song” (an active player and notification access are required)
- “What apps do I have?” / “Find my editing apps” / “What apps did I install recently?”
- “What apps do I use most?” / “What apps do I rarely use?”
- “Check storage” / “Check memory usage” / “Show system status”
- “Remember that I use Discord for school” → “How do I contact my school group?”

Contact names must uniquely match a saved name and phone number. Ambiguous names, missing permissions and unrecognized commands return an explanation rather than guessing. Confirmations expire after 30 seconds and are cancelled when the screen locks or another command arrives.

## Architecture

`assistant/parser` is an independent JVM module with a deterministic parser and fuzzy application-name matcher. Android integration lives in `app/src/main/kotlin/dev/jarvis` with the following package boundaries:

| Boundary | Responsibility |
| --- | --- |
| `core/common` | Hilt wiring, settings, session state and lifecycle events |
| `core/database`, `core/security` | Room entities/DAOs and Keystore AES-GCM field encryption |
| `core/permissions`, `core/device` | Permission checks, hardware readings and Android activity delivery |
| `core/voice` | Microphone foreground service, retry/backoff, notification actions and boot recovery |
| `assistant/wakeword` | Replaceable `WakeWordEngine`; bundled Vosk implementation |
| `assistant/speech` | Android SpeechRecognizer and offline English fallback |
| `assistant/memory`, `assistant/response` | Local preference recall, TTS and optional `AIProvider` contract |
| `skills/*` | Independent implementations of `AssistantSkill`; unique confidence-gated registry |
| `device/applications`, `device/scanner`, `device/usage` | Launcher index, permission-gated metadata scans and usage/storage queries |
| `ui/*` | Compose screens and Hilt MVVM view model, Navigation Compose |

The wake recognizer owns the microphone only while awaiting “Jarvis”. It releases the microphone before TTS and command recognition, then reacquires it after the response. Wake failures retry with bounded exponential backoff; command listening has a timeout and a single retry. The microphone foreground service uses a renewable partial wake lock, released when the service stops.

## Privacy and Android limitations

- **No AI or cloud backend.** JARVIS itself has no Internet permission. Wake recognition and the bundled English fallback run locally. On-device Android SpeechRecognizer is preferred when available. The optional network speech setting permits your installed Android speech provider to process audio online using its own permissions. Provider behavior is outside this app's control.
- **No saved recordings.** Audio buffers are used transiently by recognizers. Contact names/numbers, history, saved memory, indexed events, selected-file URIs and device snapshots are encrypted in Room using randomized AES-GCM. Keystore keys are non-exportable. Launcher metadata is not sensitive and is stored plainly for indexing. App backup and device transfer are disabled.
- **Explicit optional scans.** Calendar indexing is a user-triggered seven-day snapshot. Selected files contribute name, size and URI only; file contents are never read. Notification indexing, when separately enabled, stores package names and timestamps only, never notification bodies or security codes. Music control requires Android notification-listener access but does not require enabling metadata storage.
- **Ordinary Android privileges.** This is not a privileged system assistant. Android may require a notification tap to open an external app while JARVIS is in the background. Calls and messages deliberately open system confirmation/composition interfaces and do not silently call or send SMS.
- **Reboot and force-stop.** Microphone services cannot be silently launched from boot on modern Android. A reboot notification offers a visible route to resume listening. `START_STICKY` requests recovery after process death, subject to Android and manufacturer policy. Force-stop requires reopening the app. Saved timers are rescheduled after reboot; timers without exact-alarm access may be delayed.
- **Language availability.** The parser includes English, Filipino/Taglish aliases and a small set of Cebuano phrases. Speech transcription in Filipino, Taglish and Cebuano depends on installed Android language support or the optional provider. The bundled Vosk transcription fallback is English. It does not claim full Cebuano speech recognition.
- **Wake battery cost.** Vosk is a real offline software recognizer, not a dedicated DSP hotword engine. Its small model uses roughly 300 MB runtime memory and continuously processes audio. Measure battery usage on your device; disable background listening when unnecessary. Replace `WakeWordEngine` with Porcupine or a hardware-assisted engine if needed.
- **Application visibility.** The index covers launcher-visible apps without `QUERY_ALL_PACKAGES`; category labels use local metadata/name heuristics. Android does not grant access to other apps' private files.
- **No unverified production claim.** A successful build and automated checks establish compilation and the tested logic. Actual microphone, wake accuracy, screen-off behavior, OEM battery policy, speech packs, notification delivery and external apps require the device checks below.

## Validation

JVM parser tests cover aliases, language phrases, timer bounds/units, explicit confirmations, message slots, unknown input and conservative fuzzy names. App unit tests cover unique skill dispatch, low-confidence refusal, Room indexing/history retention, encrypted persistence, memory recall/deletion and complete data deletion. Device tests cover Compose listening/permission states and Android Keystore randomized authenticated encryption.

See [device test checklist](docs/DEVICE-TESTING.md) before relying on background listening. See [third-party notices](THIRD_PARTY_NOTICES.md) for the bundled recognizer/model.

Recorded build results, APK checksums and remaining device checks are in [VALIDATION.md](docs/VALIDATION.md).

