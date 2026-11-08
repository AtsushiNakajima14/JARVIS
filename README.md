# JARVIS

A native personal Android voice assistant: Kotlin, Compose Material 3, Hilt, Room, DataStore and coroutines. Minimum Android 8.0 (API 26), compile/target Android 17 (API 37).

Created by **Cyril Matt O. Encenso**, also known as **CyCy**. JARVIS uses that identity in its local greetings and creator responses.

## Install on your phone

Download [JARVIS-1.2.0-debug.apk](https://github.com/AtsushiNakajima14/JARVIS/releases/download/v1.2.0/JARVIS-1.2.0-debug.apk). Open the APK on your Android phone, allow installation from your browser or Files app when prompted, then install JARVIS. Allow Microphone and Notifications. In Settings, enable **Prefer Google speech** and choose your speech language. Say **Jarvis**, wait for the listening tone, then speak naturally. The microphone button also starts Google command recognition.

Install this update over the existing app to keep your saved data. The downloadable APK is debug-signed for personal testing. The owner reported that 1.1.0 works; the new 1.2.0 notification and authentication features still need testing on the Redmi A7 running Android 15 with 3 GB physical RAM. Use the [device acceptance checks](docs/DEVICE-TESTING.md).

## Build

Open this directory in Android Studio with JDK 17 or newer and Android SDK 37 installed. The wake model is bundled in `app/src/main/assets/model-en-us`, and the 91.9 MB quantized SmolLM2-135M-Instruct model is bundled in `app/src/main/assets/local-ai`. No AI model download, cloud AI key, or separate model setup is needed on the phone.

On Windows, this project can provision its own isolated tools:

```powershell
python scripts/bootstrap.py
./scripts/build.ps1
```

The bootstrap provisions Amazon Corretto 17, Gradle 9.3.1, Android SDK/NDK/CMake, pinned llama.cpp source and the two models. Build tools are kept in `.tooling/`. Large native-tool downloads use verified resumable ranges. Downloaded Android SDK license text is retained there. Nothing is installed system-wide. `local.properties` is machine-local and ignored by Git. The Gradle wrapper supports the usual commands when JDK and SDK are configured:

```powershell
./gradlew.bat :assistant:parser:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew.bat :app:assembleDebugAndroidTest
./gradlew.bat :app:connectedDebugAndroidTest
```

The installable, debug-signed APK is `app/build/outputs/apk/debug/app-debug.apk`. A release build uses `assembleRelease`; configure your own signing key locally before installing or distributing a release. No private signing key is committed.

## First use

1. Install the APK and open JARVIS. Let the local application scan finish.
2. Tap **Enable “Jarvis” wake word** and allow microphone access. Enable notifications in the Permissions screen so you can see service status, receive timers and complete background actions.
3. Say **Jarvis**, wait for the listening tone, then speak naturally. You can also tap the microphone. A short combined request such as **Jarvis, battery percentage** keeps its beginning through wake detection and can run directly through offline English recognition.
4. **Prefer Google speech** uses the phone's installed Google app, Speech Services or Google Go recognition service through Android's speech API. Setup requires no Google Cloud Speech-to-Text account, API key or billing. Audio may be processed online by Google. If the service is missing or disabled, Settings links to Android voice settings and Google speech installation; offline English commands remain available.
5. The small on-phone AI answers simple English questions with a personal CyCy persona and short conversational context. After a response, follow-up listening briefly lets you continue without repeating the wake word. Say **cancel** to end a conversation or **stop listening** to pause JARVIS.
6. Optional: allow Camera for the torch, Contacts for name lookup, Calendar for a seven-day event index, Usage access for usage/storage statistics, or Notification access for music controls.
7. For notification awareness, open **Settings → Manage Android notification access** and allow JARVIS. Separately enable **Read notification previews** to capture/read permitted previews, or **Announce new notifications** for app-name-only spoken alerts while JARVIS is listening. Both switches start off. Notification access by itself does not enable preview reading.
8. Install an offline TTS voice in Android settings if your device has none. Select the language/voice under Settings. Exact alarms improve timer precision; battery settings can improve screen-off reliability.

Examples:

- “Open Discord” / “Buksan ang Discord” / “Ablihi ang Discord”
- “Bring up Discord” / “Take me to YouTube”
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
- “Hello Jarvis” / “Who made you?” / “What is my name?”
- “Read my notifications” / “Read my notifications on Discord”
- “Who messaged me?” / “Who did I recently chat with on Discord?”
- “Catch me up” / “Give me a briefing”
- “Unlock my phone” → opens Android authentication; you authenticate there

Apps are discovered automatically at startup, after installation/removal/update and when JARVIS returns to the foreground. You do not register individual app commands or manually enter application names. Local matching handles installed labels and spoken spellings such as “you tube”. The inventory covers apps visible in your current profile's launcher.

Contact names must uniquely match a saved name and phone number. Ambiguous names, missing permissions and unrecognized commands return an explanation rather than guessing. Confirmations expire after 30 seconds and are cancelled when the screen locks or another command arrives.

## Notification awareness

With **Read notification previews** enabled and Android Notification access granted, JARVIS uses the titles, senders and message previews Android shares. “Read my notifications” describes captured active notifications; dismissed notifications leave the active list. “Who messaged me?” uses captured incoming messaging notifications with sender names. It does not establish whom you messaged, read full inboxes, or recover conversations that never produced a permitted notification. Muted chats, hidden previews and work/private profiles can limit what Android shares.

Permitted previews stay encrypted on the phone, with at most 200 records. Recent-chat lookups use a seven-day window; capturing new records or looking up recent chats prunes expired rows. Notification readouts and briefings containing notification information require an unlocked phone. Locking during a notification readout stops speech and clears that private display. Captured notification text is excluded from AI prompts and ordinary command history. Turn off **Read notification previews**, use **Delete captured notifications**, or revoke Android Notification access to clear captured previews. **Saved memory & selected data** also lets you delete individual captured records.

**Announce new notifications** says only “New notification from [app]”. It does not speak sender names or message bodies. Eligible alerts wait for idle listening, avoid interrupting a command, and are suppressed during calls or Do Not Disturb. Duplicate updates and bursts are limited; this is not a guarantee that every notification is announced. JARVIS does not start microphone listening just because a notification arrives. Enable wake/background listening yourself when wanted.

## Architecture

`assistant/parser` is an independent JVM module with a deterministic parser and fuzzy application-name matcher. Android integration lives in `app/src/main/kotlin/dev/jarvis` with the following package boundaries:

| Boundary | Responsibility |
| --- | --- |
| `core/common` | Hilt wiring, settings, session state and lifecycle events |
| `core/database`, `core/security` | Room entities/DAOs and Keystore AES-GCM field encryption |
| `core/permissions`, `core/device` | Permission checks, hardware readings and Android activity delivery |
| `core/voice` | Microphone foreground service, retry/backoff, notification actions and boot recovery |
| `assistant/wakeword` | Replaceable `WakeWordEngine`; bundled Vosk implementation |
| `assistant/speech` | Explicit Google SpeechRecognizer selection, alternatives and constrained offline English fallback |
| `assistant/localai` | CPU-only llama.cpp JNI runtime, cancellation and bounded on-phone inference |
| `assistant/memory`, `assistant/response` | Encrypted preference recall, offline TTS and the real small local `AIProvider` |
| `assistant/notifications` | Permission-gated previews, bounded encrypted incoming-message history and app-only alert events |
| `device/security` | Android-owned screen-lock authentication activity |
| `skills/*` | Independent implementations of `AssistantSkill`; unique confidence-gated registry |
| `device/applications`, `device/scanner`, `device/usage` | Launcher index, permission-gated metadata scans and usage/storage queries |
| `ui/*` | Compose screens and Hilt MVVM view model, Navigation Compose |

The wake listener retains transient PCM audio across “Jarvis” and the rest of the same utterance, passing the buffered beginning into the offline command decoder while recording continues. Confidently matched short English phone commands can run directly offline. A wake word spoken alone, the microphone button and follow-up listening use the selected command recognizer, preferring the installed Google phone service when enabled. A combined request that needs Google recognition prompts you to repeat after the listening tone.

The microphone is released before TTS or a handoff to Google's recognizer, then reacquired for the next listening window. Wake failures retry with bounded exponential backoff; command listening has a timeout and a single retry. The microphone foreground service uses a renewable partial wake lock, released when the service stops.

## Privacy and Android limitations

- **Google speech, local intelligence.** Google's installed Android speech service is preferred by default; a previously saved opt-out stays off. It may process microphone audio online under its own permissions. Installed-app lists are not sent as online recognition hints. JARVIS itself has no Internet permission or cloud AI endpoint. AI replies, saved preferences, device information and app matching stay on the phone. Turn off **Prefer Google speech** to use offline English commands or supported on-device language packs. TTS selects offline voices independently of the recognition preference.
- **No saved recordings.** Audio buffers are used transiently by recognizers. Contact names/numbers, history, saved memory, indexed events, selected-file URIs and device snapshots are encrypted in Room using randomized AES-GCM. Keystore keys are non-exportable. Launcher metadata is not sensitive and is stored plainly for indexing. App backup and device transfer are disabled.
- **Explicit optional scans.** Calendar indexing is a user-triggered seven-day snapshot. Selected files contribute name, size and URI only; file contents are never read. **Notification metadata** stores only package names and timestamps. The separate **Read notification previews** opt-in permits bounded encrypted titles, senders and previews. Security/OTP-like content, secret-visibility notifications and sensitive app categories are filtered conservatively; Android may additionally redact content. Music control requires Android notification-listener access but does not require preview or metadata storage.
- **Ordinary Android privileges.** This is not a privileged system assistant. Android may require a notification tap to open an external app while JARVIS is in the background. Calls and messages deliberately open system confirmation/composition interfaces and do not silently call or send SMS.
- **Secure unlock.** “Unlock my phone” opens Android's own authentication screen using `requestDismissKeyguard`. Android may require a fingerprint, face or device credential; the phrase alone cannot bypass a secure lock. JARVIS never stores or enters a screen-lock PIN. Commands that attempt to save, disclose or retrieve credentials are omitted from prompts, memory and command history. Background activity restrictions can require tapping the action notification first.
- **Reboot and force-stop.** Microphone services cannot be silently launched from boot on modern Android. A reboot notification offers a visible route to resume listening. `START_STICKY` requests recovery after process death, subject to Android and manufacturer policy. Force-stop requires reopening the app. Saved timers are rescheduled after reboot; timers without exact-alarm access may be delayed.
- **Language availability.** The parser includes English, Filipino/Taglish aliases and a small set of Cebuano phrases. Speech transcription in Filipino, Taglish and Cebuano depends on installed Android language support or the optional provider. The bundled Vosk transcription fallback is English. It does not claim full Cebuano speech recognition.
- **Small on-phone AI.** SmolLM2-135M-Instruct Q4_0 is included, with a 512-token native context, two CPU threads, at most 64 generated tokens and a 20-second inference deadline. Byte-bounded prompts can keep two short recent turns; unusually dense inputs can retry a compact prompt within the same deadline. Saved preferences and prior conversation are excluded while the phone is locked. Vosk is released before inference; the AI model/context are freed after each reply. A free-memory guard avoids starting AI under acute memory pressure. These limits target a phone with 3 GB physical RAM; memory extension does not increase physical RAM. The tiny model is intended for brief English chat and can be inaccurate; native skills handle actions and confirmations. Phone speed and peak memory still require device measurement.
- **Wake battery cost.** Vosk is a real offline software recognizer, not a dedicated DSP hotword engine. Its small model uses roughly 300 MB runtime memory and continuously processes audio. Measure battery usage on your device; disable background listening when unnecessary. Replace `WakeWordEngine` with Porcupine or a hardware-assisted engine if needed.
- **Application visibility.** The index covers launcher-visible apps without `QUERY_ALL_PACKAGES`; category labels use local metadata/name heuristics. Android does not grant access to other apps' private files.
- **No unverified production claim.** A successful build and automated checks establish compilation and the tested logic. Actual microphone, wake accuracy, screen-off behavior, OEM battery policy, speech packs, notification delivery and external apps require the device checks below.

## Validation

JVM parser tests cover natural phrasing, personal/notification commands, dynamic app aliases, grammar bounds, timer limits, explicit confirmations and protected message bodies. Native waveform regressions exercise continuous/paused requests, readiness tones, prior-speech trimming and unrelated speech. Actual native result fixtures are passed through the Kotlin reader, selector and parser. App unit tests cover Google-provider policy, notification extraction/filtering/retention, private-command handling, automatic app indexing, decoder/model leases, bounded personal conversation, cancellation-safe model copying, Room history and encrypted memory. The local JNI module tests UTF-8, request serialization, cancellation and resource limits. Device tests cover Compose listening/permission states and Android Keystore randomized authenticated encryption.

See [device test checklist](docs/DEVICE-TESTING.md) before relying on background listening. See [third-party notices](THIRD_PARTY_NOTICES.md) for the bundled recognizer/model.

Recorded build results, APK checksums and remaining device checks are in [VALIDATION.md](docs/VALIDATION.md).

