#include <jni.h>
#include "llama.h"
#include "ggml-backend.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_set>
#include <vector>

namespace {
std::once_flag backend_once;
std::mutex cancellation_mutex;
std::unordered_set<int64_t> cancelled;
std::atomic<int64_t> active_request{0};
std::atomic<bool> active_cancelled{false};

struct RequestGuard {
    int64_t id;
    explicit RequestGuard(int64_t value) : id(value) {
        std::lock_guard<std::mutex> guard(cancellation_mutex);
        active_request.store(id, std::memory_order_release);
        active_cancelled.store(cancelled.count(id) != 0, std::memory_order_release);
    }
    ~RequestGuard() {
        std::lock_guard<std::mutex> guard(cancellation_mutex);
        active_request.store(0, std::memory_order_release);
        active_cancelled.store(false, std::memory_order_release);
        cancelled.erase(id);
    }
};

struct AbortState {
    std::chrono::steady_clock::time_point deadline;
    std::atomic<bool> timed_out{false};
};

bool should_abort(void * opaque) {
    auto * state = static_cast<AbortState *>(opaque);
    if (active_cancelled.load(std::memory_order_acquire)) return true;
    if (std::chrono::steady_clock::now() >= state->deadline) {
        state->timed_out.store(true, std::memory_order_release);
        return true;
    }
    return false;
}

void check_abort(AbortState & state) {
    if (active_cancelled.load(std::memory_order_acquire)) throw std::runtime_error("cancelled");
    if (should_abort(&state)) throw std::runtime_error("timeout");
}

std::string bytes(JNIEnv * env, jbyteArray data) {
    const jsize length = env->GetArrayLength(data);
    std::string value(static_cast<size_t>(length), '\0');
    if (length > 0) env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte *>(&value[0]));
    return value;
}

void throw_java(JNIEnv * env, const char * type, const char * text) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls != nullptr) env->ThrowNew(cls, text);
}

using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;

std::string infer(const std::string & path, const std::string & prompt,
                  int32_t max_tokens, AbortState & abort) {
    std::call_once(backend_once, [] { llama_backend_init(); ggml_backend_load_all(); });
    check_abort(abort);
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    model_params.progress_callback = [](float, void * opaque) -> bool { return !should_abort(opaque); };
    model_params.progress_callback_user_data = &abort;
    Model model(llama_model_load_from_file(path.c_str(), model_params), llama_model_free);
    if (!model) { check_abort(abort); throw std::runtime_error("Unable to load the bundled local AI model."); }
    check_abort(abort);
    const llama_vocab * vocab = llama_model_get_vocab(model.get());
    const int32_t count = -llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (count <= 0 || count + max_tokens > 512) throw std::invalid_argument("The question is too long for the small local AI context.");
    std::vector<llama_token> tokens(static_cast<size_t>(count));
    if (llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(), count, true, true) < 0) {
        throw std::runtime_error("Local AI could not tokenize the prompt.");
    }
    auto context_params = llama_context_default_params();
    context_params.n_ctx = 512;
    context_params.n_batch = 64;
    context_params.n_ubatch = 64;
    context_params.n_threads = 2;
    context_params.n_threads_batch = 2;
    context_params.n_seq_max = 1;
    context_params.offload_kqv = false;
    context_params.op_offload = false;
    context_params.no_perf = true;
    context_params.abort_callback = should_abort;
    context_params.abort_callback_data = &abort;
    Context context(llama_init_from_model(model.get(), context_params), llama_free);
    if (!context) { check_abort(abort); throw std::runtime_error("Local AI could not allocate its small context."); }
    auto sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    Sampler sampler(llama_sampler_chain_init(sampler_params), llama_sampler_free);
    if (!sampler) throw std::runtime_error("Local AI could not create its sampler.");
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.12f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.20f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(42));
    for (int32_t start = 0; start < count; start += 64) {
        check_abort(abort);
        const int32_t length = std::min<int32_t>(64, count - start);
        auto batch = llama_batch_get_one(tokens.data() + start, length);
        if (llama_decode(context.get(), batch) != 0) { check_abort(abort); throw std::runtime_error("Local AI prompt evaluation failed."); }
    }
    std::string result;
    for (int32_t n = 0; n < max_tokens; ++n) {
        check_abort(abort);
        llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char small[256];
        int32_t length = llama_token_to_piece(vocab, token, small, sizeof(small), 0, false);
        if (length < 0) {
            std::vector<char> large(static_cast<size_t>(-length));
            length = llama_token_to_piece(vocab, token, large.data(), static_cast<int32_t>(large.size()), 0, false);
            if (length < 0) throw std::runtime_error("Local AI could not decode an output token.");
            result.append(large.data(), static_cast<size_t>(length));
        } else result.append(small, static_cast<size_t>(length));
        auto batch = llama_batch_get_one(&token, 1);
        if (llama_decode(context.get(), batch) != 0) { check_abort(abort); throw std::runtime_error("Local AI generation failed."); }
    }
    check_abort(abort);
    return result;
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_jarvis_localai_NativeLlm_generate(JNIEnv * env, jobject, jlong request_id,
    jbyteArray model_path, jbyteArray prompt_bytes, jint max_tokens, jlong timeout_ms) {
    RequestGuard request(request_id);
    AbortState abort{std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms)};
    try {
        if (max_tokens < 1 || max_tokens > 128 || timeout_ms < 1 || timeout_ms > 60000) throw std::invalid_argument("Invalid local AI resource limits.");
        const auto output = infer(bytes(env, model_path), bytes(env, prompt_bytes), max_tokens, abort);
        jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result != nullptr && !output.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::invalid_argument & error) {
        throw_java(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::exception & error) {
        if (active_cancelled.load(std::memory_order_acquire)) throw_java(env, "java/util/concurrent/CancellationException", "Local AI request cancelled.");
        else if (abort.timed_out.load(std::memory_order_acquire)) throw_java(env, "java/util/concurrent/TimeoutException", "Local AI reached its time limit.");
        else throw_java(env, "java/lang/IllegalStateException", error.what());
    } catch (...) { throw_java(env, "java/lang/IllegalStateException", "Local AI inference failed."); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_jarvis_localai_NativeLlm_cancel(JNIEnv *, jobject, jlong request_id) {
    std::lock_guard<std::mutex> guard(cancellation_mutex);
    cancelled.insert(request_id);
    if (active_request.load(std::memory_order_acquire) == request_id) active_cancelled.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_jarvis_localai_NativeLlm_forget(JNIEnv *, jobject, jlong request_id) {
    std::lock_guard<std::mutex> guard(cancellation_mutex);
    cancelled.erase(request_id);
}
