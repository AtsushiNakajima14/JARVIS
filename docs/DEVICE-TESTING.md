# Device acceptance checks

Run on your own physical Android device before treating this as a dependable daily assistant. Automated verification cannot establish microphone quality, speech language support or manufacturer background policies.

1. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Open the launcher entry. Confirm initial scanning completes and installed applications have real icons and versions.
2. Deny Microphone. Confirm the app explains the missing permission and does not crash. Grant it in Android app settings and retry.
3. Enable notifications and wake listening while the activity is visible. Say “Jarvis”, wait for “Yes?”, then ask for battery status. Check the spoken value against Android battery settings.
4. Lock the screen and repeat after 1, 10 and 30 minutes. Repeat unplugged with battery optimization enabled and disabled. Record battery drain over several hours; software Vosk hotword recognition has a measurable cost.
5. Tap Pause in the persistent notification. Confirm Android's microphone indicator stops. Restart from the visible app. Revoke Microphone while listening and confirm bounded retry and a safe stop.
6. Open installed apps from the foreground. Repeat in the background and confirm the action notification opens the requested app after a tap.
7. Deny Camera, request flashlight, and check the explanation. Grant Camera and verify on/off on hardware; repeat while another app holds the camera.
8. Try media volume, camera, and active music play/pause/next/previous. Disable Notification access and verify the music permission explanation.
9. Test “Call [unique contact]” and “Send a message to [unique contact] saying [text]”. Cancel, allow the 30-second expiry, lock the screen, and issue an unrelated command. None should execute a pending action. Confirm only once and check the intended dialer/composer and recipient. Test duplicate saved contact names.
10. Create a 10-second timer with notifications enabled. Repeat locked. Compare exact-alarm granted and denied behavior. Reboot with a pending timer and verify recovery. Clear all data and confirm old timers no longer fire.
11. Store the Discord/school memory, ask the school question, delete the item, and repeat. Disable memory and history separately. Reopen after process death and verify only enabled persistent data remains.
12. Enable Contacts, Calendar, Usage access and file selection separately. Check actual data appears locally and denied access is handled. Revoke a selected file grant and delete its index entry.
13. Test English and installed Filipino packs in airplane mode; test Taglish/Cebuano only where your provider supports them. Enable network speech deliberately to test provider fallback. Verify missing TTS packs leave a visible response and an installation explanation.
14. Reboot: JARVIS should offer a resume notification without illegally launching the microphone service. Force-stop: reopening should be required.
15. Run `connectedDebugAndroidTest` with an emulator or attached device to execute Compose and Keystore tests. Check logcat for crashes during all cases.

# Authoritative implementation references

- [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk)
- [AGP 9.1 compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes)
- [Foreground microphone service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Background activity launch restrictions](https://developer.android.com/guide/components/activities/background-starts)
- [Vosk models and resource requirements](https://alphacephei.com/vosk/models)
- [Vosk Android source](https://github.com/alphacep/vosk-api)

