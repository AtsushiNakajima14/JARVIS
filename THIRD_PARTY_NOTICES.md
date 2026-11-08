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

## On-phone language model and runtime

- Model: HuggingFaceTB SmolLM2-135M-Instruct (Apache-2.0), quantized Q4_0 by bartowski, 91,893,088 bytes.
- Quantized model revision: `09816acd5d99df7be770d85ea30822623dab342c`; SHA-256: `bcc3af2849ad6095af57e9b5cd43775256efdc66e306acb529172f92d0c04b03`.
- Publisher model card: <https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct>.
- Quantized artifact: <https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF>.
- Runtime: ggml-org llama.cpp (MIT), commit `bb4caa7540188872173c44d161602d9271386413` (v0.2.0).
- Source: <https://github.com/ggml-org/llama.cpp>.
- The model's Apache-2.0 license, source metadata and llama.cpp MIT notice are bundled in `app/src/main/assets/local-ai` and retained in the APK. Native source is fetched from the checksum-pinned upstream archive during developer setup/build; no runtime AI endpoint is used.

## Google phone speech service

Google speech recognition uses an installed Google Android recognition service through Android's `SpeechRecognizer` API, under the provider's own terms. Users configure it through the Google app, Speech Services or Google Go and Android voice settings. Google Cloud Speech-to-Text credentials and billing are not required. The service may process speech online; see the privacy notes in [README.md](README.md).
