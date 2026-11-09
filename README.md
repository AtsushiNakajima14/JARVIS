# JARVIS

A private Android phone companion built with Kotlin, Compose Material 3, Hilt, Room, DataStore and coroutines. Android 8.0+ (API 26); compile/target SDK 37.

Created by **Cyril Matt O. Encenso**, also known as **CyCy**.

## Install

Download [JARVIS 1.3.0](https://github.com/AtsushiNakajima14/JARVIS/releases/tag/v1.3.0), expand **Assets**, and install **JARVIS-1.3.0-debug.apk**. The repository is private: sign in to GitHub as **AtsushiNakajima14** or another account with access before opening the link. Choose **Update** to keep your saved data.

For a local build, install `app/build/outputs/apk/debug/app-debug.apk`, or use:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install updates over the existing app when their signing certificates match. The downloadable debug APKs are personal device test builds. Build results and checksums are recorded in [VALIDATION.md](docs/VALIDATION.md); test the new behavior using [DEVICE-TESTING.md](docs/DEVICE-TESTING.md).

## Start talking

Open JARVIS, allow Microphone, then tap the microphone or start listening for **Jarvis**. When prompted, wait for the listening tone and speak. You can also type in the conversation box without granting Microphone; a text-only session does not start voice listening. Allow notifications for reminder alerts, service controls and background action prompts.

To open apps from background voice without the action-notification tap, open **Permissions → Default digital assistant** and choose **JARVIS** in Android's chooser. This replaces your current default assistant only if you approve Android's prompt. Once Android binds JARVIS as its active voice assistant, unlocked voice actions can open apps directly. The system assistant gesture also opens JARVIS for listening. If the role is unavailable, not selected, still binding, or blocked by device policy, the action notification remains the fallback. Secure screen locks still require Android authentication.

The companion resolves supported goals and asks for missing details. Examples:

| You say | JARVIS helps by |
| --- | --- |
| "I need to talk to someone." | Asking who, then whether to call or write a message |
| "Help me write something." | Asking who it is for and what you want to say, then preparing an editable draft |
| "My phone is getting slow." | Checking measured battery, storage and RAM, with practical suggestions |
| "Prepare my phone." | Reporting the phone overview, reminders and permitted notification count |
| "I want to check my messages." | Using your saved chat-app preference or asking which installed app to open |
| "Can you show me Messenger?" | Finding the installed app, with clarification for uncertain matches |
| "What is the time?" / "What day is it?" | Reading the phone's local clock and date with offline speech support |
| "Remind me to check my project." | Asking when; you can answer "tomorrow at 9 AM" |
| "What should I do today?" | Showing unfinished reminders |
| "Remember I use Messenger for chatting." | Saving an approved preference locally |
| "Show my memories." / "Forget this." | Reviewing saved facts or clarifying which fact to remove |
| "Find my documents about school." | Searching granted document metadata and accessible MediaStore files |

Follow-up conversation briefly listens after a reply so you can continue without repeating the wake word. Say **cancel** to abandon a conversation or **stop listening** to pause. Pending details expire after two minutes and reset on screen lock, stopping or privacy clearing. Calls and messages require confirmation, then open Android's dialer or composer; you complete the call or send there.

Home shows the conversation, assistant status and a phone snapshot. **Memory** groups approved facts into profile, preferences, routines and important information. Its Files tab lets you select important documents. **Reminders** lets you complete or delete tasks. Installed apps are discovered automatically at startup, on package changes and on foreground return; no manual app-command registration is needed.

## Local speech and intelligence

Current conversation turns show **Typed** or **Voice**, and Home displays the active speech source. Split Spotify pronunciations and the YouTube Music / YT Music label are matched locally. When an app name or action is unclear, JARVIS asks for clarification.

The core companion needs **no external AI service, API key or cloud processing**. Android `SpeechRecognizer.createOnDeviceSpeechRecognizer` is preferred when available. Otherwise, or when that recognizer fails, JARVIS uses the bundled offline English Vosk recognizer. The old network-speech preference is ignored on upgrade. TextToSpeech selects installed offline voices; install a voice in Android Settings if necessary. Other speech languages depend on installed on-device support; natural understanding currently works best in English.

`AgentCore` performs local goal classification, keyword extraction, synonym-based semantic similarity, fuzzy app matching and bounded conversation state. It can clarify supported intentions; Android's built-in APIs do not provide unrestricted general intelligence. Unknown requests receive a useful local explanation instead of executing a guessed action.

**Optional local AI replies** uses the included SmolLM2-135M-Instruct Q4_0 model for broader short English questions. It is off by default on new installs; an explicitly saved setting is preserved. The native companion works with it off. The model adds about 92 MB of bundled storage and consumes RAM while answering. It uses two CPU threads, a 512-token context, up to 64 output tokens and a 20-second deadline. Replies can be slow or inaccurate on a 3 GB phone. Model output never chooses or authorizes Android actions.

## Data and Android access

- **Phone awareness:** PackageManager, BatteryManager, ActivityManager, storage, connectivity and Bluetooth readings are collected on demand. App usage requires optional Usage access. Android restricts inspection and control of other apps' processes; JARVIS does not claim to clean RAM or automatically speed up the phone.
- **Communication:** Contacts permission enables exact, unambiguous saved-name lookup. Notification access supports permitted notification awareness and music controls. JARVIS cannot inspect an app's private chat database or recover conversations it never received.
- **Notification consent:** In Settings, use **Manage Android notification access**, then separately opt into **Read notification previews** or **Announce new notifications**. Both start off. Captured previews are encrypted and bounded to 200 records with a seven-day lookup window; capture and queries prune expired records. Turning preview reading off, deleting captured data or revoking access clears previews. Readouts require an unlocked phone. App-only announcements wait for quiet wake listening and avoid calls and Do Not Disturb.
- **Files:** Android's document picker grants access to files you select. JARVIS stores bounded encrypted names, URIs and metadata, with no file-content index or broad storage permission. Search also covers recent MediaStore rows Android makes accessible. Forgetting a selected file releases its saved grant. This is not a complete filesystem scan.
- **Reminders:** Encrypted local tasks are scheduled with WorkManager using an opaque ID. Alerts contain generic text so task names stay private on the lockscreen. Android may delay delivery to save battery. Deleting or completing a task cancels its pending work. Existing work survives reboot; startup and boot recovery reconcile missing work.
- **Storage:** Sensitive Room fields use AES-GCM with an Android Keystore key. Launcher app metadata remains plain for indexing. Audio is transient and never saved. Backup and device transfer are disabled. Ordinary conversation history is optional and bounded; private ability readouts are excluded from it and from optional model context.
- **Lock and background behavior:** Private memories, files, reminders, contacts and notification readouts require an unlocked phone. "Unlock my phone" requests Android's own authentication screen; JARVIS never stores or enters a device PIN. The optional default-assistant role provides Android's system-bound voice-service path for direct app opening while unlocked. Otherwise Android may require tapping an action notification. The assistant service does not collect screen structure or screenshots, and binding it does not start the microphone.

Background wake listening uses a visible microphone foreground service and a renewable wake lock. Its Vosk software recognizer consumes CPU, RAM and battery; it is not a hardware DSP hotword engine. Pause when unnecessary and measure screen-off reliability on your phone. Modern Android prevents silently starting microphone listening at boot; a visible reboot notification lets you resume. Force-stop requires reopening JARVIS.

## Architecture

```text
Speech / typed conversation
  -> JarvisAgent + local AgentCore
  -> goal, missing details, conversation context
  -> AbilityRegistry
  -> permission-gated Android abilities
  -> local response + offline TextToSpeech
```

| Boundary | Responsibility |
| --- | --- |
| `assistant/parser/.../agent` | Pure Kotlin NLU, goal planning, follow-up slots and local reminder times |
| `app/.../assistant/agent` | Approved device knowledge supplied to the native agent |
| `skills/*`, `assistant/abilities` | Android abilities, compatibility executors, memories, files and reminders |
| `device/knowledge`, `device/applications` | Measured phone facts, launcher inventory, optional usage and Android icon references |
| `assistant/wakeword`, `assistant/speech` | Replaceable wake interface, microphone ownership and offline recognition |
| `assistant/response`, `assistant/localai` | `AIProvider` interface, native replies, optional bounded local LLM and offline TTS |
| `assistant/notifications` | Consent, filtering, encrypted bounded capture and private readouts |
| `core/*` | Foreground lifecycle, permissions, encryption, Room and Android activity delivery |
| `ui/*` | Companion conversation, status, memory, files, reminders and phone awareness |

The existing explicit parser remains an internal resolver for tested Android actions. The agent owns the user-facing conversation and ability selection. Future providers can implement `AIProvider`; none is required for the current core.

## Build and verify

Open the project in Android Studio with JDK 17+ and Android SDK 37, or provision isolated Windows tools:

```powershell
python scripts/bootstrap.py
./scripts/build.ps1
```

Bootstrap downloads the JDK, Gradle, Android SDK/NDK/CMake, pinned llama.cpp source and bundled models into `.tooling/`. Building therefore needs initial network access; running the installed core does not. `local.properties`, private signing keys, caches and build outputs are ignored by Git.

```powershell
./scripts/build.ps1 -Tasks @(
    ':assistant:parser:test', ':assistant:localai:testDebugUnitTest',
    ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug',
    ':app:assembleDebugAndroidTest', '--no-daemon', '--no-parallel', '--max-workers=1'
)
./gradlew.bat :app:connectedDebugAndroidTest
```

`assembleRelease` creates an optimized unsigned APK; configure a local release signing key before installing or distributing it. Automated checks cover the implemented logic and compilation. Actual microphone accuracy, installed speech support, reminder delivery, screen-off behavior, memory and battery require the [physical-device checklist](docs/DEVICE-TESTING.md). See [third-party notices](THIRD_PARTY_NOTICES.md) for bundled licenses.
