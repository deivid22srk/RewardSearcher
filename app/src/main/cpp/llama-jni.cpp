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

    int nPromptMax = promptText.size() + 256;
    std::vector<llama_token> tokens(nPromptMax);
    int nTokens = llama_tokenize(vocab, promptText.c_str(), promptText.size(),
                                  tokens.data(), tokens.size(), true, true);
    if (nTokens < 0) {
        tokens.resize(-nTokens);
        nTokens = llama_tokenize(vocab, promptText.c_str(), promptText.size(),
                                  tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(nTokens);

    llama_batch batch = llama_batch_init(512, 0, 1);
    for (int i = 0; i < nTokens; i++) {
        batch.token[batch.n_tokens] = tokens[i];
        batch.pos[batch.n_tokens] = i;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = (i == nTokens - 1);
        batch.n_tokens++;
    }

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Decode failed");
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
    int nCur = nTokens;

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

        llama_batch_clear(batch);
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
