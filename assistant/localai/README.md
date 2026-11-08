# Tiny local inference runtime

This module builds a CPU-only JNI bridge against llama.cpp **v0.2.0**, commit
`bb4caa7540188872173c44d161602d9271386413`. CMake uses the source in
`.tooling/llama.cpp` when provisioned and otherwise downloads the immutable,
SHA-256-verified upstream archive. Android Gradle Plugin can provision Android
NDK 28.2.13676358 and CMake 3.22.1 through the SDK manager. The optional
`python scripts/provision_local_ai.py` bootstrap provisions that source and SDK
tooling ahead of time and verifies the bundled SmolLM2-135M-Instruct Q4_0 model.

`LocalLlm.generate()` accepts the absolute model path and a fully formatted prompt.
It runs on a single native worker, uses two CPU threads, a 512-token context,
64-token batches, and at most 128 generated tokens. It maps model weights from
disk, closes the model/context after every request, and supports cancellation and
a bounded timeout through a native CPU abort callback. Input/output are UTF-8 byte
arrays across JNI to preserve non-ASCII text safely.

The app must release its speech recognizers/model before starting inference, then
wait for `awaitIdle()` before reacquiring microphone model resources. This avoids
keeping both large model working sets resident on a 3 GB phone. Actual Android
peak memory and latency must be measured on the target device; file size is not a
RAM requirement.

Sources: [llama.cpp release](https://github.com/ggml-org/llama.cpp/releases/tag/v0.2.0),
[upstream Android build documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md),
[SmolLM2 publisher model card](https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct).
