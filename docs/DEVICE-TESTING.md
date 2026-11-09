# Device acceptance checks

Run on a physical Android device before relying on JARVIS as a daily companion. Record the app version, phone model, Android version, permissions, speech source and result for each check. Build and JVM results do not establish microphone accuracy, manufacturer background policy or reminder delivery.

Target: Redmi A7, Android 15, 3 GB physical RAM. The owner reported that 1.1.0 worked. The 1.3.0 companion upgrade requires fresh testing; the phone's 1 GB memory extension is not additional physical RAM.

## Install and baseline

1. Install the new build with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Confirm the version in Settings and that existing approved memories survive an update with the same signing key. Keep the repository private; GitHub downloads require an account with access.
2. Deny Microphone. Type a phone question and a multi-turn request; replies should work without starting a microphone or wake lock. A voice request should explain the missing permission without crashing. Grant Microphone and retry voice. Allow notifications to use service controls, timers, reminders and background action prompts.
3. Confirm Home shows conversation, status, a phone snapshot and an editable composer. Visit Memory, Reminders, Apps and Device information. App icons, package names and versions should match the phone.
4. Tap the microphone, wait for the ready tone, and ask for battery status. Compare the result with Android battery settings. Repeat "Jarvis, battery percentage" as one continuous utterance, then with a pause after the wake word; the beginning must be retained.
5. Install a launcher app while JARVIS runs and ask to open it without refreshing manually. Uninstall it and check that the index changes. Repeat after reopening JARVIS. Try a spoken app alias such as "you tube" and an ambiguous or misspelled app name; uncertain matches should clarify.
6. In Permissions, open **Default digital assistant**. Cancel Android's chooser and confirm the previous assistant remains. Then choose JARVIS and confirm Android reports it as the active assistant. While unlocked and backgrounded, request an app by voice: it should open directly without an action-notification tap once the system service is ready. Repeat the system assistant gesture, role removal, reboot/rebinding, and a locked phone. Device policy can still restrict launching; the fallback must explain the tap. Becoming the default assistant by itself must not start microphone recording or capture screen contents.

## Version 1.3.0 native conversation

Run these with **Optional local AI replies off**, including in airplane mode. The core must need no API account, key or cloud connection.

- Ask "I need to talk to someone." Answer with a unique saved contact, then choose a call or message. JARVIS must retain the person while asking for the remaining detail. Allow Contacts only when needed. Duplicate names or numbers must require clarification instead of guessing.
- Ask "Help me write something." Answer "My friend," give the message idea, review the draft, and ask to make it shorter. When opening the draft, provide the saved contact name if asked; previously supplied message content must be retained. Confirm that Android's composer contains the intended recipient and text, and that JARVIS never sends it itself.
- Dictate a message containing words such as "open," "call," "battery" or "delete." They must remain message text. Repeat with case-sensitive names and with politeness inside the message. A clearly introduced new question, such as "Actually, what is my battery?", should replace the pending topic.
- Cancel during each missing-detail question. Start an unrelated explicit request. Wait more than two minutes before replying to a pending question. Stop/restart the service and lock/unlock the screen. Old recipients, drafts or reminder details must not execute after their context is reset.
- Ask "Can you show me Messenger?", "Open Messenger" and "I want to check my messages." With multiple chat apps, the last request should ask which app. Save "Remember I use Messenger for chatting," then repeat and verify the approved preference is used. Uninstall the preferred app and check the explanation or choice.
- Ask "My phone is getting slow." Compare the reported RAM and storage with native settings close in time. Without Usage access it must not invent app usage. With access, verify the bounded recent usage snapshot. The reply must not claim that it inspected every process, killed other apps or cleaned RAM.
- Ask "Prepare my phone." Check measured battery/connectivity/storage, unfinished reminder information and the consent-gated notification count. Repeat without notification access, with preview reading off, and while locked.
- Ask a native follow-up such as "And storage?" or "What about RAM?" after a phone query. Ask for a timer without a duration, then answer "five minutes." The next reply should continue the goal instead of requiring the whole request again.
- Observe Listening, Understanding, Working and Speaking states. Continue a follow-up without the wake word. Say cancel or stop listening. Disable Follow-up conversation and verify there is no automatic follow-up microphone window.

## Offline speech and optional local model

- Ask "What is the time?", "What time is it?", "What is the date?" and "What day is it?" after both the wake word and microphone button. Compare replies with the phone clock and timezone. Record the exact transcript and recognition-source label if a request is wrong. Test normal speaking speed and your actual accent; synthetic Windows voice probes do not establish phone accuracy.
- With Spotify installed, ask to open it through both wake and microphone paths. Check split-name transcripts such as "spot if i" and "spo te fi" resolve to the installed app. A fragment such as "bathing spo te fi" must ask whether you meant to open Spotify and wait for yes; cancel, ambiguous names, questions and dictated message/memory text must not open an app.
- Settings must report Android on-device speech availability or the bundled offline English fallback. The old **Prefer Google speech** switch is absent. A stored network-speech preference from an older build must not reactivate cloud recognition.
- With a supported installed language pack, tap the microphone and verify the displayed source is on-device speech. Disable or remove that support where possible; the bundled English recognizer should be used gracefully. Test absent language packs, provider failure, permission revocation and a bounded no-speech timeout.
- In airplane mode, test typed and spoken native requests and follow-ups with the optional model off. Verify offline TTS. If the device lacks an offline voice, the app should explain how to install one.
- A new install should have Optional local AI replies off. An explicitly saved previous choice should remain. Enable it, ask a short general English question, then continue briefly. Compare behavior with it off; phone abilities, memories and reminders must still work.
- Measure reply latency and peak RAM during optional inference. Test low-memory handling and the 20-second inference deadline. Cancel during model preparation and generation, clear history/memory while busy, and rapidly restart listening. There must be no stale reply, restored deleted data, competing microphones or overlapping heavy models. The tiny model can be inaccurate; record actual output without treating it as phone knowledge.

## Approved memory and files

- Say "Remember my name," provide a name, and inspect the profile category. Save a chat preference, routine and important project; verify categories and original text. Show memories, immediately say "Forget this" after saving one, and try an ambiguous deletion query. Only the intended approved fact should be removed.
- Disable Local memory and attempt to remember/recall. Review and delete stored facts in the viewer. Confirm notification, reminder and file implementation records do not appear as approved memories or raw JSON.
- In Memory > Files, select a local document through Android's picker. Search for part of its name and ask for recent documents. Only selected file metadata and MediaStore rows Android makes accessible should appear. Test a missing name, no shared documents, an unavailable provider and a cancelled picker.
- Revoke a selected file's grant and return to JARVIS; its saved metadata should be pruned. Forget an important file and confirm its lasting grant is released. File contents must not be read or indexed, and the app must not request broad all-files access.
- Lock during memory/file speech or while a related result is being prepared. Private text must stop or be replaced with an unlock explanation. Saved-data views and conversation context must clear or hide while locked.

## Reminders and daily overview

- Say "Remind me," provide a task, then "in ten minutes." Repeat a complete request such as "Remind me to check my project tomorrow at 9 AM." Confirm the title and local time in Reminders. Ambiguous bare times such as "at 9" should prompt for clarification; invalid/past explicit times must not silently schedule a guessed time.
- Ask "What should I do today?" and "Give me a briefing." Compare unfinished tasks and the daily overview with saved tasks. Mark an exact task done by voice and through the UI. Duplicate/ambiguous task titles must require a more specific choice.
- Let a reminder become due with notifications allowed, denied and later re-enabled. The lockscreen notification must remain generic and expose no task title. Android may deliver later than the requested time; measure delay with battery optimization enabled and disabled.
- Delete or complete a reminder before it is due and confirm its pending alert is cancelled. Restart the app and reboot with pending tasks; saved reminders should survive and missing work should be reconciled. Force-stop and reopen, then inspect outstanding tasks and delivery behavior.
- Clear all local data with reminders pending. No deleted task or stale alert should return after app restart or reboot. Selected-file grants, approved memories and permitted notification previews should also be cleared.

## Notification awareness and secure unlock

- With notification access denied, ask to read notifications and recent message senders. Grant access while preview reading remains off; content reading must still stay disabled. Music controls should work without opting into preview capture.
- Enable Read notification previews and send benign messages from another device. Compare app, incoming sender and preview with Android's notification. Ask an app-filtered question, dismiss a notification, and check active readouts again. Recent incoming senders do not establish whom you messaged or full chat histories.
- Test grouped summaries, repeated updates, ongoing notifications and JARVIS's own notifications. They should not produce loops or duplicate bursts. Use synthetic OTP/security-like text without real credentials; conservative filtering and Android 15 redaction should hide sensitive previews.
- Enable Announce new notifications while JARVIS listens. Fresh alerts should speak only the app name after a quiet window. Test arrival during a request, follow-up, optional inference and TTS. Test calls, voice calls, Do Not Disturb, burst cooldowns and stale alerts. A notification alone must never start the microphone service.
- Lock during a private readout. Speech, the private Home display and ephemeral context must clear. Ordinary saved conversation history, foreground notifications and optional model prompts must not contain private ability readouts.
- Turn off preview reading, revoke notification access, delete one captured item, delete all captured previews and clear all data in separate runs. Queued callbacks must not restore deleted content. Re-enable preview capture deliberately before importing current notifications again. Exercise the seven-day retention window and 200-record limit.
- Say "Unlock my phone" when already unlocked and securely locked. Tap the background action notification if Android requires it. Only successful Android authentication may unlock the device; cancel must leave it locked. Test a synthetic "remember my PIN" request with no real credential: it should be omitted from transcript, history and saved memory.

## Background, hardware and regression checks

- Leave wake listening active with the screen off for 1, 10 and 30 minutes. Repeat unplugged with battery optimization enabled and disabled. Record several hours of battery drain, peak memory and microphone reliability on the physical 3 GB phone.
- Pause from the persistent notification and confirm Android's microphone indicator stops. Revoke Microphone while listening and verify bounded retry followed by a safe stop. Rapidly pause/resume without crashes or leaked audio resources.
- Open an app while JARVIS is visible and while backgrounded. When Android blocks background activity launch, the action notification should open the requested app after a tap. Reboot and confirm JARVIS offers a visible resume action rather than silently opening the microphone.
- Deny Camera, request the flashlight, then grant it and test on/off hardware. Repeat while another app holds the camera. Test volume, camera and active music play/pause/next/previous, including missing notification access.
- Cancel, expire and screen-lock call/message confirmations. An unrelated request must cancel the old pending action. Confirm once and verify the actual Android dialer/composer and intended recipient.
- Test a 10-second timer with notifications enabled and exact-alarm access granted/denied. Reboot with a pending timer. Clear all data and confirm no old timer alert returns. Timers and inexact WorkManager reminders have different scheduling behavior.
- Run `./gradlew.bat :app:connectedDebugAndroidTest` on an emulator or attached device to execute Compose and Keystore tests. Inspect logcat for crashes throughout the checks above.

## Implementation references

- [Android on-device SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Foreground microphone service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Background activity launches](https://developer.android.com/guide/components/activities/background-starts)
- [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent)
- [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Android keyguard dismissal](https://developer.android.com/reference/android/app/KeyguardManager#requestDismissKeyguard(android.app.Activity,%20android.app.KeyguardManager.KeyguardDismissCallback))
- [Vosk models and resource requirements](https://alphacephei.com/vosk/models)

Older 1.1.0 builds used the phone's Google recognition service. That historical setup is superseded by 1.3.0's offline recognition policy. Existing notification, authentication, microphone and native-model regression checks remain applicable as described above.
