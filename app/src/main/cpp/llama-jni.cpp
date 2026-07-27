#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;

// Helper: tokenize a string, growing the buffer if needed.
static std::vector<llama_token> tokenize_prompt(const llama_vocab *vocab,
                                                const std::string &text) {
    // First pass to get required size.
    int n = llama_tokenize(vocab, text.c_str(), text.size(), nullptr, 0, true, true);
    if (n < 0) n = -n; // negative return = required size
    std::vector<llama_token> tokens(n);
    int m = llama_tokenize(vocab, text.c_str(), text.size(),
                           tokens.data(), tokens.size(), true, true);
    if (m < 0) {
        // Should not happen after the sizing pass, but be defensive.
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

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;

    g_ctx = llama_init_from_model(g_model, ctx_params);

    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return 0;
    }

    LOGI("Model loaded successfully");
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
        LOGE("Decode failed (prompt phase)");
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
        LOGE("Model not loaded");
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
// so any GGUF model that ships a template (e.g. LFM2.5-230M uses ChatML)
// will be rendered correctly without us hard-coding the format.
JNIEXPORT void JNICALL
Java_com_deivid22srk_rewardsearcher_data_LocalAIManager_nativeChat(
    JNIEnv *env, jobject, jobjectArray roles, jobjectArray contents,
    jint maxTokens, jfloat temperature, jobject callback) {

    if (!g_model || !g_ctx) {
        LOGE("Model not loaded (chat)");
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

    // First pass: query the required buffer size for the templated prompt.
    int needed = llama_chat_apply_template(g_model, nullptr,
                                           msgs.data(), nMsgs, true,
                                           nullptr, 0);
    if (needed < 0) {
        LOGE("llama_chat_apply_template (sizing) failed: %d", needed);
        env->CallVoidMethod(callback, onComplete);
        return;
    }

    std::string templated(needed > 0 ? needed : 1, '\0');
    int written = llama_chat_apply_template(g_model, nullptr,
                                            msgs.data(), nMsgs, true,
                                            &templated[0], templated.size());
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
