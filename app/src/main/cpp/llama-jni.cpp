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

JNIEXPORT void JNICALL
Java_com_deivid22srk_rewardsearcher_data_LocalAIManager_nativeGenerate(
    JNIEnv *env, jobject, jstring prompt, jint maxTokens, jfloat temperature, jobject callback) {

    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        return;
    }

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptText(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "()V");

    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    std::vector<llama_token> tokens;
    tokens = llama_tokenize(vocab, promptText.c_str(), promptText.size(), nullptr, 0, true, true);
    std::vector<llama_token> tmp(tokens.size());
    int nTokens = llama_tokenize(vocab, promptText.c_str(), promptText.size(), tmp.data(), tmp.size(), true, true);
    tokens.resize(nTokens);

    llama_batch batch = llama_batch_init(512, 0, 1);
    for (int i = 0; i < nTokens; i++) {
        llama_batch_add(batch, tokens[i], i, {0}, (i == nTokens - 1));
    }

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Decode failed");
        llama_batch_free(batch);
        env->CallVoidMethod(callback, onComplete);
        return;
    }

    llama_token newToken;
    std::string result;
    int nGenerated = 0;

    while (nGenerated < maxTokens) {
        float logits[llama_vocab_n_tokens(vocab)];
        llama_get_logits(g_ctx, logits);

        int nVocab = llama_vocab_n_tokens(vocab);
        std::vector<llama_token_data> candidates(nVocab);
        for (int i = 0; i < nVocab; i++) {
            candidates[i] = {i, logits[i], 0.0f};
        }

        llama_token_data_array cur = {candidates.data(), candidates.size(), false};

        if (temperature > 0.0f) {
            llama_sample_temp(&cur, temperature);
            newToken = llama_sample_token(g_ctx, &cur);
        } else {
            newToken = llama_sample_token_greedy(g_ctx, &cur);
        }

        if (llama_vocab_is_eog(vocab, newToken)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, newToken, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string piece(buf, n);
            result += piece;
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        llama_batch_clear(batch);
        llama_batch_add(batch, newToken, nTokens + nGenerated, {0}, true);

        if (llama_decode(g_ctx, batch) != 0) break;
        nGenerated++;
    }

    llama_batch_free(batch);
    env->CallVoidMethod(callback, onComplete);
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
