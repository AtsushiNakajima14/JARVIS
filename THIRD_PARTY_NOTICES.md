# Third-party notices

JARVIS uses AndroidX/Jetpack, Kotlin, kotlinx.coroutines, Dagger Hilt, JNA and Vosk. Their respective upstream licenses apply. Gradle resolves the library artifacts from Google Maven and Maven Central.

## Vosk

- Library: `com.alphacephei:vosk-android:0.3.75`
- Source and license: <https://github.com/alphacep/vosk-api>
- Bundled model: **vosk-model-small-en-us-0.15**, copyright 2020 Alpha Cephei Inc.
- Model download and Apache-2.0 license declaration: <https://alphacephei.com/vosk/models>
- Original model README is retained inside `app/src/main/assets/model-en-us/README`.
- Download archive hash is recorded in `MODEL-SHA256.txt`.

Vosk includes native speech-processing dependencies. Review their upstream notices before redistributing a release. JNA is available under LGPL-2.1-or-later or Apache-2.0; this project selects Apache-2.0. The original software and model copyright notices must be preserved when distributing the APK.
