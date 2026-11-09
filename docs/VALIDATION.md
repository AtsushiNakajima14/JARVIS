# Build validation

Verified on 2026-11-09 (Asia/Manila), version **1.3.0**, version code **4**.

- **255 tests passed**, with no failures, errors or skipped tests: 102 parser, 149 app and 4 local-runtime tests.
- Android lint: **0 errors, 25 warnings** (22 dependency update suggestions, two Kotlin style suggestions and one inlined constant compatibility warning).
- Debug APK and optimized R8 release APK built. The release APK is unsigned; the downloadable personal test APK is debug-signed.
- Android test APK compiled (three Compose tests and one Keystore test). No connected device or emulator was available; these tests were not executed on Android hardware.
- Debug APK Signature Scheme v2 verifies. The certificate matches releases 1.0.0 through 1.2.0, allowing an update without uninstalling. Certificate SHA-256: `d84aea7c56747fc78ee90001ddf15ab4331e59179f6e226f61b28b2043cc57fb`.
- APK metadata: `dev.jarvis`, minimum SDK 26, compile/target SDK 37, launcher `dev.jarvis.MainActivity`.
- The packaged APK has **no Internet permission**. Speech uses Android's on-device recognizer or the bundled offline recognizer; the platform factory cannot fall back to network speech. TTS selects offline voices. The native agent works with optional local-model replies disabled.
- Both APKs pass `zipalign -c -P 16 4`. Packaged 64-bit native libraries have at least 16 KB ELF LOAD alignment; the local AI library has 16 KB alignment on all four ABIs.
- Both APKs contain Vosk/local AI libraries for arm64-v8a, armeabi-v7a, x86 and x86_64, plus both models and bundled licenses. The 91,893,088-byte optional local model matches its pinned SHA-256. R8 retains `dev.jarvis.localai.NativeLlm` and JNI entry points.

## Checks run

```powershell
$env:ANDROID_USER_HOME = Join-Path (Get-Location) '.tooling/android-user-home'
./scripts/build.ps1 -Tasks @(
    ':assistant:parser:test', ':assistant:localai:testDebugUnitTest',
    ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug',
    ':app:assembleDebugAndroidTest', ':app:assembleRelease',
    '-Pkotlin.compiler.execution.strategy=in-process', '-Pksp.incremental=false',
    '--no-daemon', '--no-parallel', '--max-workers=1'
)
```

The final build completed successfully. The isolated Android user directory uses the existing debug signing key so updates retain the same certificate. Build caches, keys and APKs are ignored by Git.

| Module | Suite | Passed |
| --- | --- | ---: |
| parser | `AgentCoreTest` | 34 |
| parser | `ReportedPhoneTranscriptsTest` | 4 |
| parser | `SpokenApplicationRecoveryTest` | 9 |
| parser | `CommandLexiconTest` | 7 |
| parser | `CommandParserTest` | 20 |
| parser | `PersonalCommandParserTest` | 10 |
| parser | `SpeechActivityTest` | 6 |
| parser | `SpeechSelectionTest` | 7 |
| parser | `TimeCommandParserTest` | 5 |
| localai | `LocalLlmTest` | 4 |
| app | `ApplicationIndexSchedulerTest` | 3 |
| app | `ApplicationIndexSyncTest` | 4 |
| app | `CompanionPoliciesTest` | 6 |
| app | `CompanionRepositoryTest` | 10 |
| app | `ConversationContextTest` | 4 |
| app | `DatabaseMemoryTest` | 6 |
| app | `DefaultAssistantTest` | 5 |
| app | `DeviceHealthAdviceTest` | 4 |
| app | `DeviceKnowledgeRepositoryTest` | 2 |
| app | `GoogleRecognitionPolicyTest` | 11 |
| app | `LocalModelCopyTest` | 5 |
| app | `LocalPromptTest` | 10 |
| app | `MicrophoneLeaseTest` | 2 |
| app | `NativeCompanionIntegrationTest` | 5 |
| app | `NativeSpeechRegressionTest` | 1 |
| app | `NotificationPolicyTest` | 14 |
| app | `NotificationRepositoryTest` | 10 |
| app | `OfflineLanguagePolicyTest` | 2 |
| app | `PrivateCommandPolicyTest` | 2 |
| app | `SkillMatchingTest` | 3 |
| app | `SpeechRecognitionTest` | 17 |
| app | `TimeSpeechTest` | 7 |
| app | `UnlockActivityTest` | 3 |
| app | `VoskModelLeaseTest` | 9 |
| app | `VoskResultReaderTest` | 4 |

## Companion and speech evidence

The pure Kotlin agent tests exercise goal classification, synonym-based semantic similarity, fuzzy/segmented app names, slot collection, communication and writing follow-ups, timer/reminder times, timezone handling, cancellation, two-minute expiry, explicit topic changes, and literal message/memory bodies. The native core can select abilities and clarify supported goals without a language model.

Reported transcript regressions cover split Spotify names, the uncertain fragment "bathing spo te fi" with explicit confirmation, YouTube Music matching the installed YT Music label, "ready my notifications", existing creator/notification questions, and an unclear app slot followed by a corrected name. Questions, negation, missing/ambiguous apps, saved facts and dictated bodies cannot authorize a guessed app launch. Current conversation turns identify typed versus voice input; the Home screen exposes the active speech source. Old history entries do not establish voice accuracy.

Ten new clock/date utterances across Microsoft David and Zira were decoded by the real Windows Vosk 0.3.45 runtime. The saved raw results pass through the current Kotlin Vosk reader, confidence selector and parser. One observed free transcript, "what is the dave", is rescued by confident date grammar. Spotify host probes also reproduced "spot if i", "spot of i" and "spo te fi" spellings; installed-app canonical matching and bounded pronunciation aliases cover those forms. Android uses Vosk 0.3.75, so synthetic Windows probes do not establish microphone or accent accuracy on the Redmi.

The previous 98 synthetic native waveform checks and eight retained raw regression fixtures cover continuous/paused wake requests, readiness tones, stale pre-roll and unrelated speech. Those earlier waveform runs are historical evidence; the current suite rechecks their retained Kotlin fixtures. The local language model previously produced a real CPU host reply; its Android runtime remains compiled for all four ABIs. Four JNI-wrapper tests use a fake backend for UTF-8, serialization, cancellation and bounds, not physical Android inference.

## Android abilities and privacy evidence

Robolectric/Room/WorkManager tests verify encrypted approved memories and reminders, opaque worker input, durable scheduling, completion/deletion cancellation, generic reminder notifications, ambiguity handling, file metadata encryption, persisted grant requirements and revocation, and rejection of stale picker results after clearing. Memory categories remain local and only explicit memories are used for preference recall.

Device knowledge tests check native battery capture and timestamped persistence, unavailable permissions, lock gating, measured health advice and truthful process-access limits. Reminder summaries and permitted notification counts are grounded in local records. Notification tests cover consent, access revocation, lock checks, bounded retention, filtering, deduplication, incoming senders and the count-only briefing API.

Five default-assistant tests exercise selected/system-ready/unlocked background delivery, role selection without binding, secure-lock fallback, visible-activity delivery and notification fallback. The packaged voice and recognition services require Android's binding permissions. Android owns role selection; the service never silently chooses itself, records upon binding, or requests screen structure/screenshots. Actual role binding and background-launch permission on the Redmi remain device checks.

A shared microphone lease prevents overlap between wake recording, Android speech recognition and the system assistant recognition service. Cancellation tests ensure a new owner waits for old recorder cleanup and a cancelled waiter cannot retain the microphone. Screen locking cancels private work, clears context and returns an active voice session to serialized wake listening. Text-only sessions need no microphone permission or automatic voice follow-up. Real microphone handoffs and lock-screen TTS interruption still require hardware testing.

Credentials are excluded from transcripts, memory and ordinary history. Android's secure unlock prompt owns authentication; no device PIN is stored or entered. Private ability readouts are excluded from ordinary history and optional model context. Privacy clearing cancels reminder work, releases file grants and invalidates queued context/imports.

## APK artifacts

- `app/build/outputs/apk/debug/app-debug.apk`: **199.9 MiB** (209,580,507 bytes).
  - SHA-256: `0cc591a7a15bf3daee8014aa824f86d4598475e4a314747c79a79ad56306d5f4`.
- `app/build/outputs/apk/release/app-release-unsigned.apk`: **180.8 MiB** (189,538,282 bytes).
  - SHA-256: `9f4600716f8c75125ba6e47a3f3cf2589d336911e45051340e81566a0ded0df6`.

Release asset name: `JARVIS-1.3.0-debug.apk`, with a SHA-256 checksum asset. The repository remains private; downloads require a signed-in account with repository access.

## Remaining device checks

No phone or emulator was connected during this validation. The owner reports that some earlier successful entries were typed; those entries are not evidence of successful voice recognition. New speech intake and pronunciation recovery, live on-device language packs, actual accent/microphone accuracy, default-assistant role setup, unlocked direct app opening, reminder timing, notification delivery, real authentication UI, screen-off/OEM behavior, peak RAM and battery require the [device checklist](DEVICE-TESTING.md) on the Redmi A7 running Android 15 with 3 GB physical RAM.

The optional SmolLM2-135M-Instruct Q4_0 model can be slow or inaccurate and does not authorize Android actions. Its thread/context/output/deadline limits are not a measurement of phone performance. Native goal understanding is bounded; Android does not provide unrestricted general intelligence or access to other apps' private databases.

Runtime: llama.cpp `bb4caa7540188872173c44d161602d9271386413`. Model SHA-256: `bcc3af2849ad6095af57e9b5cd43775256efdc66e306acb529172f92d0c04b03`.
