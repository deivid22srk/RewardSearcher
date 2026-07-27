#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;

// ---------------------------------------------------------------------------
// Minimal GGUF header reader.
//
// We parse just enough of the GGUF container to log the architecture string
// before handing off to llama_model_load_from_file. This makes load failures
// diagnosable in logcat even when llama.cpp itself stays silent (which is
// what happens when the architecture is simply unknown — the loader returns
// NULL without printing anything).
//
// GGUF v3 layout:
//   uint32 magic        = 0x46554747 ("GGUF" little-endian)
//   uint32 version      = 3
//   uint64 n_tensors
//   uint64 n_kv
//   <n_kv> key/value pairs, where each value is preceded by a uint32 type tag
//
// We only need to find general.architecture, which is a string-typed KV.
// ---------------------------------------------------------------------------
struct GgufHeader {
    uint32_t magic;
    uint32_t version;
    uint64_t n_tensors;
    uint64_t n_kv;
};

enum GgufValueType {
    GGUF_TYPE_UINT8 = 0, GGUF_TYPE_INT8, GGUF_TYPE_UINT16, GGUF_TYPE_INT16,
    GGUF_TYPE_UINT32, GGUF_TYPE_INT32, GGUF_TYPE_FLOAT32, GGUF_TYPE_BOOL,
    GGUF_TYPE_STRING, GGUF_TYPE_ARRAY, GGUF_TYPE_UINT64, GGUF_TYPE_INT64,
    GGUF_TYPE_FLOAT64,
};

// Read a GGUF string (uint64 length + bytes, no null terminator) from a
// byte buffer at the given offset. Advances offset past the string.
// Returns empty string on overflow.
static std::string read_gguf_string(const std::vector<uint8_t> &buf, size_t &off) {
    if (off + 8 > buf.size()) return std::string();
    uint64_t len = 0;
    memcpy(&len, buf.data() + off, 8);
    off += 8;
    if (len > (1u << 24) || off + len > buf.size()) return std::string(); // sanity cap at 16 MB
    std::string s(reinterpret_cast<const char *>(buf.data() + off), (size_t)len);
    off += (size_t)len;
    return s;
}

// Skip a single GGUF value of the given type. Used to walk past KV pairs
// we do not care about. Returns false on overflow.
static bool skip_gguf_value(const std::vector<uint8_t> &buf, size_t &off, uint32_t type) {
    switch (type) {
        case GGUF_TYPE_UINT8:  case GGUF_TYPE_INT8:    case GGUF_TYPE_BOOL: off += 1; break;
        case GGUF_TYPE_UINT16: case GGUF_TYPE_INT16:   off += 2; break;
        case GGUF_TYPE_UINT32: case GGUF_TYPE_INT32:   case GGUF_TYPE_FLOAT32: off += 4; break;
        case GGUF_TYPE_UINT64: case GGUF_TYPE_INT64:   case GGUF_TYPE_FLOAT64: off += 8; break;
        case GGUF_TYPE_STRING: read_gguf_string(buf, off); break;
        case GGUF_TYPE_ARRAY: {
            if (off + 4 > buf.size()) return false;
            uint32_t elemType = 0;
            memcpy(&elemType, buf.data() + off, 4); off += 4;
            if (off + 8 > buf.size()) return false;
            uint64_t nElem = 0;
            memcpy(&nElem, buf.data() + off, 8); off += 8;
            for (uint64_t i = 0; i < nElem; i++) {
                if (!skip_gguf_value(buf, off, elemType)) return false;
            }
            break;
        }
        default:
            return false;
    }
    return off <= buf.size();
}

// Returns the architecture string ("llama", "lfm2", "qwen2", etc.) or
// empty string if the file is not a valid GGUF or has no architecture field.
static std::string inspect_gguf_architecture(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return std::string("<cannot open file>");
    GgufHeader h;
    if (fread(&h, sizeof(h), 1, f) != 1) { fclose(f); return std::string("<short read>"); }
    if (h.magic != 0x46554747u) { fclose(f); return std::string("<bad magic>"); }

    // Read the first 256 KB — plenty to cover all KV metadata for any
    // practical model (the largest GGUF KV sections are ~10 KB).
    size_t want = 256 * 1024;
    std::vector<uint8_t> buf(want);
    fseek(f, sizeof(h), SEEK_SET);
    size_t got = fread(buf.data(), 1, want, f);
    fclose(f);
    buf.resize(got);

    size_t off = 0;
    for (uint64_t i = 0; i < h.n_kv && off < buf.size(); i++) {
        std::string key = read_gguf_string(buf, off);
        if (off + 4 > buf.size()) break;
        uint32_t type = 0;
        memcpy(&type, buf.data() + off, 4); off += 4;
        if (key == "general.architecture" && type == GGUF_TYPE_STRING) {
            return read_gguf_string(buf, off);
        }
        if (!skip_gguf_value(buf, off, type)) break;
    }
    return std::string("<general.architecture not found>");
}

// Helper: tokenize a string, growing the buffer if needed.
static std::vector<llama_token> tokenize_prompt(const llama_vocab *vocab,
                                                const std::string &text) {
    int n = llama_tokenize(vocab, text.c_str(), text.size(), nullptr, 0, true, true);
    if (n < 0) n = -n;
    std::vector<llama_token> tokens(n);
    int m = llama_tokenize(vocab, text.c_str(), text.size(),
                           tokens.data(), tokens.size(), true, true);
    if (m < 0) {
        tokens.resize(-m);
        m = llama_tokenize(vocab, text.c_str(), text.size(),
                           tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(std::max(0, m));
    return tokens;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_deivid22srk_rewardsearcher_data_LocalAIManager_nativeLoadModel(
    JNIEnv *env, jobject, jstring modelPath, jint nCtx, jint nThreads) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    // Pre-flight: log the GGUF magic + architecture so load failures are
    // diagnosable. This runs BEFORE llama_backend_init() so it works even
    // if the backend itself has problems.
    std::string arch = inspect_gguf_architecture(path);
    LOGI("GGUF architecture: %s", arch.c_str());

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        // This is the failure the user was hitting: llama_model_load_from_file
        // returns NULL with no further explanation. The most common cause
        // is that the linked llama.cpp build does not support the model's
        // architecture (e.g. loading an LFM2 GGUF with a llama.cpp release
        // that predates LFM2 support). We log the architecture we found
        // so the user can compare against the llama.cpp release notes.
        LOGE("Failed to load model (architecture=%s). Check that the llama.cpp "
             "version linked into the app supports this architecture. "
             "LFM2 support landed in llama.cpp b6000 (May 2025).",
             arch.c_str());
        llama_backend_free();
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = (uint32_t)nCtx;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;

    g_ctx = llama_init_from_model(g_model, ctx_params);

    if (!g_ctx) {
        LOGE("Failed to create context (n_ctx=%d)", (int)nCtx);
        llama_model_free(g_model);
        g_model = nullptr;
        llama_backend_free();
        return 0;
    }

    LOGI("Model loaded successfully (arch=%s, n_ctx=%d)", arch.c_str(), (int)nCtx);
    return reinterpret_cast<jlong>(g_ctx);
}

// Run a single prompt → token stream. Shared by nativeGenerate (searches)
// and nativeChat (chat). The prompt must already be fully formatted.
static void run_generation(JNIEnv *env, const std::string &promptText,
                           jint maxTokens, jfloat temperature,
                           jobject callback,
                           jmethodID onToken, jmethodID onComplete) {
    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    auto tokens = tokenize_prompt(vocab, promptText);

    llama_batch batch = llama_batch_init(512, 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[batch.n_tokens] = tokens[i];
        batch.pos[batch.n_tokens] = (llama_pos)i;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = (i == tokens.size() - 1);
        batch.n_tokens++;
    }

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Decode failed (prompt phase, n_tokens=%zu)", tokens.size());
        llama_batch_free(batch);
        env->CallVoidMethod(callback, onComplete);
        return;
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature > 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(0xFFFFFFFF));
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    llama_token newToken;
    int nGenerated = 0;
    int nCur = (int)tokens.size();

    while (nGenerated < maxTokens) {
        newToken = llama_sampler_sample(sampler, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, newToken)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, newToken, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string piece(buf, n);
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        batch.n_tokens = 0;
        batch.token[0] = newToken;
        batch.pos[0] = nCur;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;
        batch.n_tokens = 1;

        if (llama_decode(g_ctx, batch) != 0) break;
        nCur++;
        nGenerated++;
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);
    env->CallVoidMethod(callback, onComplete);
}

JNIEXPORT void JNICALL
Java_com_deivid22srk_rewardsearcher_data_LocalAIManager_nativeGenerate(
    JNIEnv *env, jobject, jstring prompt, jint maxTokens, jfloat temperature, jobject callback) {

    if (!g_model || !g_ctx) {
        LOGE("Model not loaded (nativeGenerate)");
        jclass cbClass = env->GetObjectClass(callback);
        jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "()V");
        env->CallVoidMethod(callback, onComplete);
        return;
    }

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptText(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "()V");

    run_generation(env, promptText, maxTokens, temperature, callback, onToken, onComplete);
}

// Feature 4: multi-turn chat.
// roles[] and contents[] are parallel arrays of equal length.
// roles must contain "system" | "user" | "assistant".
// The model's embedded chat template is applied via llama_chat_apply_template,
// so any GGUF model that ships a template (e.g. LFM2.5-230M uses a Jinja
// template stored in tokenizer.chat_template) will be rendered correctly
// without us hard-coding the format.
JNIEXPORT void JNICALL
Java_com_deivid22srk_rewardsearcher_data_LocalAIManager_nativeChat(
    JNIEnv *env, jobject, jobjectArray roles, jobjectArray contents,
    jint maxTokens, jfloat temperature, jobject callback) {

    if (!g_model || !g_ctx) {
        LOGE("Model not loaded (nativeChat)");
        jclass cbClass = env->GetObjectClass(callback);
        jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "()V");
        env->CallVoidMethod(callback, onComplete);
        return;
    }

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "()V");

    jsize nMsgs = env->GetArrayLength(roles);
    if (nMsgs == 0 || env->GetArrayLength(contents) != nMsgs) {
        env->CallVoidMethod(callback, onComplete);
        return;
    }

    // Build llama_chat_message array. We must keep the underlying std::string
    // buffers alive for the lifetime of the C-string pointers we hand over.
    std::vector<llama_chat_message> msgs(nMsgs);
    std::vector<std::string> roleStorage(nMsgs);
    std::vector<std::string> contentStorage(nMsgs);

    for (jsize i = 0; i < nMsgs; i++) {
        jstring jrole = (jstring)env->GetObjectArrayElement(roles, i);
        jstring jcontent = (jstring)env->GetObjectArrayElement(contents, i);
        const char *r = env->GetStringUTFChars(jrole, nullptr);
        const char *c = env->GetStringUTFChars(jcontent, nullptr);
        roleStorage[i] = std::string(r);
        contentStorage[i] = std::string(c);
        env->ReleaseStringUTFChars(jrole, r);
        env->ReleaseStringUTFChars(jcontent, c);
        env->DeleteLocalRef(jrole);
        env->DeleteLocalRef(jcontent);

        msgs[i].role = roleStorage[i].c_str();
        msgs[i].content = contentStorage[i].c_str();
    }

    // Look up the model's embedded chat template (Jinja string stored in
    // the GGUF as tokenizer.chat_template). llama_chat_apply_template takes
    // this string as its first argument (NOT a model pointer).
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    if (!tmpl) {
        LOGE("Model has no embedded chat template; llama_chat_apply_template "
             "will fall back to its built-in ChatML default — this may be "
             "wrong for non-ChatML models.");
    } else {
        LOGI("Chat template: %.120s...", tmpl);
    }

    // First pass: query the required buffer size for the templated prompt.
    int needed = llama_chat_apply_template(tmpl,
                                           msgs.data(), (size_t)nMsgs, true,
                                           nullptr, 0);
    if (needed < 0) {
        LOGE("llama_chat_apply_template (sizing) failed: %d", needed);
        env->CallVoidMethod(callback, onComplete);
        return;
    }

    std::string templated(needed > 0 ? needed : 1, '\0');
    int written = llama_chat_apply_template(tmpl,
                                            msgs.data(), (size_t)nMsgs, true,
                                            &templated[0], (int32_t)templated.size());
    if (written < 0) {
        LOGE("llama_chat_apply_template (write) failed: %d", written);
        env->CallVoidMethod(callback, onComplete);
        return;
    }
    templated.resize(written);

    LOGI("Chat prompt (%d chars, %d msgs)", written, nMsgs);
    run_generation(env, templated, maxTokens, temperature, callback, onToken, onComplete);
}

JNIEXPORT void JNICALL
Java_com_deivid22srk_rewardsearcher_data_LocalAIManager_nativeFreeModel(
    JNIEnv *env, jobject) {
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
    LOGI("Model freed");
}

JNIEXPORT jboolean JNICALL
Java_com_deivid22srk_rewardsearcher_data_LocalAIManager_nativeIsLoaded(
    JNIEnv *env, jobject) {
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

}
