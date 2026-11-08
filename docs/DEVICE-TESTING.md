# Device acceptance checks

Run on your own physical Android device before treating this as a dependable daily assistant. Automated verification cannot establish microphone quality, speech language support or manufacturer background policies.

The updated APK has not yet been tested on the target Redmi A7 running Android 15 with 3 GB physical RAM. Record results from that phone separately from build, JVM and host-native checks. Its 1 GB memory extension is not additional physical RAM.

1. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Open the launcher entry. Confirm initial scanning completes and installed applications have real icons and versions.
2. Deny Microphone. Confirm the app explains the missing permission and does not crash. Grant it in Android app settings and retry.
3. Enable notifications and wake listening while the activity is visible. Say “Jarvis”, wait for the listening tone, then ask for battery status. Also say “Jarvis, battery percentage” as one continuous utterance without waiting for a tone. Confirm the command beginning is retained and check the spoken value against Android battery settings.
4. Lock the screen and repeat after 1, 10 and 30 minutes. Repeat unplugged with battery optimization enabled and disabled. Record battery drain over several hours; software Vosk hotword recognition has a measurable cost.
5. Tap Pause in the persistent notification. Confirm Android's microphone indicator stops. Restart from the visible app. Revoke Microphone while listening and confirm bounded retry and a safe stop.
6. Open installed apps from the foreground. After the listening tone, try natural requests such as “Bring up Discord” and “Take me to YouTube”. Repeat in the background and confirm the action notification opens the requested app after a tap.
7. Deny Camera, request flashlight, and check the explanation. Grant Camera and verify on/off on hardware; repeat while another app holds the camera.
8. Try media volume, camera, and active music play/pause/next/previous. Disable Notification access and verify the music permission explanation.
9. Test “Call [unique contact]” and “Send a message to [unique contact] saying [text]”. Cancel, allow the 30-second expiry, lock the screen, and issue an unrelated command. None should execute a pending action. Confirm only once and check the intended dialer/composer and recipient. Test duplicate saved contact names.
10. Create a 10-second timer with notifications enabled. Repeat locked. Compare exact-alarm granted and denied behavior. Reboot with a pending timer and verify recovery. Clear all data and confirm old timers no longer fire.
11. Store the Discord/school memory, ask the school question, delete the item, and repeat. Disable memory and history separately. Reopen after process death and verify only enabled persistent data remains.
12. Enable Contacts, Calendar, Usage access and file selection separately. Check actual data appears locally and denied access is handled. Revoke a selected file grant and delete its index entry.
13. With Prefer Google speech enabled, test the installed Google phone service with and without connectivity and supported language packs. Disable the preference and test offline English commands; test Filipino, Taglish and Cebuano only where installed on-device language support is available. Verify missing TTS packs leave a visible response and an installation explanation.
14. Reboot: JARVIS should offer a resume notification without illegally launching the microphone service. Force-stop: reopening should be required.
15. Run `connectedDebugAndroidTest` with an emulator or attached device to execute Compose and Keystore tests. Check logcat for crashes during all cases.

# Authoritative implementation references

- [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk)
- [AGP 9.1 compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes)
- [Foreground microphone service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Background activity launch restrictions](https://developer.android.com/guide/components/activities/background-starts)
- [Vosk models and resource requirements](https://alphacephei.com/vosk/models)
- [Vosk Android source](https://github.com/alphacep/vosk-api)


## Version 1.1.0 Google speech and local AI checks

- Update over 1.0.0 without uninstalling; verify Settings shows 1.1.0 and existing saved memory remains.
- On the target device with 3 GB physical RAM, select English and enable Prefer Google speech. Confirm an installed Google service is listed; enable/install it using the Settings links if necessary. This uses Google's Android phone service and requires no Google Cloud Speech-to-Text API key or billing setup.
- Say “battery percentage”, “bring up Discord” and “take me to YouTube” after the listening tone. Compare the “I heard” text with your speech and the displayed recognition source. Confirm the requested app launches while JARVIS is visible and uses an action notification when backgrounded.
- Say “Jarvis, battery percentage” and “Jarvis, open Discord” continuously, then repeat with a short pause after “Jarvis”. Verify confidently recognized short commands use offline English recognition without losing the beginning. Say only “Jarvis” and confirm the listening tone starts the Google command window. Tap the microphone and verify the same Google service is selected. If a combined request needs repeating, wait for its prompt and tone.
- Install a new launcher app while JARVIS runs, then ask it to open that app without manually refreshing. Uninstall it and verify the local inventory updates. Repeat after stopping/reopening JARVIS.
- Ask a short general question. Verify a real local AI reply, then continue with a follow-up. Say cancel to end the follow-up window and stop listening to pause the service.
- Disable Google speech, enable airplane mode, and ask a typed general question. The AI reply must still work with no network or external AI app. Wake/English device commands should continue offline; Google recognition itself may need connectivity/language packs.
- Check memory while waking, during AI inference, and after Pause. The app releases the Vosk model during AI inference and frees the AI model/context after replying; paused wake resources should also be freed. Measure latency, peak memory and battery on the physical 3 GB phone. Check the low-memory explanation under memory pressure and the 20-second inference deadline; the bundled tiny English model can still give inaccurate answers.
- Cancel during local model preparation/inference, clear conversation/history/memory while busy, and restart listening rapidly. There must be no stale reply, restored deleted history, simultaneous heavy models, crash or leaked microphone.
- Remove/disable the Google recognizer, revoke Microphone and change network availability. Verify provider failure falls back gracefully and reports the active recognition source.
