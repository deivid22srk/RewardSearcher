#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;

// ---------------------------------------------------------------------------
// Minimal GGUF header reader (for diagnostic logging only).
// ---------------------------------------------------------------------------
struct GgufHeader {
    uint32_t magic;
    uint32_t version;
    uint64_t n_tensors;
    uint64_t n_kv;
};

namespace {
enum LocalGgufValueType {
    LOCAL_GGUF_TYPE_UINT8 = 0, LOCAL_GGUF_TYPE_INT8, LOCAL_GGUF_TYPE_UINT16, LOCAL_GGUF_TYPE_INT16,
    LOCAL_GGUF_TYPE_UINT32, LOCAL_GGUF_TYPE_INT32, LOCAL_GGUF_TYPE_FLOAT32, LOCAL_GGUF_TYPE_BOOL,
    LOCAL_GGUF_TYPE_STRING, LOCAL_GGUF_TYPE_ARRAY, LOCAL_GGUF_TYPE_UINT64, LOCAL_GGUF_TYPE_INT64,
    LOCAL_GGUF_TYPE_FLOAT64,
};
}

static std::string read_gguf_string(const std::vector<uint8_t> &buf, size_t &off) {
    if (off + 8 > buf.size()) return std::string();
    uint64_t len = 0;
    memcpy(&len, buf.data() + off, 8);
    off += 8;
    if (len > (1u << 24) || off + len > buf.size()) return std::string();
    std::string s(reinterpret_cast<const char *>(buf.data() + off), (size_t)len);
    off += (size_t)len;
    return s;
}

static bool skip_gguf_value(const std::vector<uint8_t> &buf, size_t &off, uint32_t type) {
    switch (type) {
        case LOCAL_GGUF_TYPE_UINT8:  case LOCAL_GGUF_TYPE_INT8:    case LOCAL_GGUF_TYPE_BOOL: off += 1; break;
        case LOCAL_GGUF_TYPE_UINT16: case LOCAL_GGUF_TYPE_INT16:   off += 2; break;
        case LOCAL_GGUF_TYPE_UINT32: case LOCAL_GGUF_TYPE_INT32:   case LOCAL_GGUF_TYPE_FLOAT32: off += 4; break;
        case LOCAL_GGUF_TYPE_UINT64: case LOCAL_GGUF_TYPE_INT64:   case LOCAL_GGUF_TYPE_FLOAT64: off += 8; break;
        case LOCAL_GGUF_TYPE_STRING: read_gguf_string(buf, off); break;
        case LOCAL_GGUF_TYPE_ARRAY: {
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

static std::string inspect_gguf_architecture(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return std::string("<cannot open file>");
    GgufHeader h;
    if (fread(&h, sizeof(h), 1, f) != 1) { fclose(f); return std::string("<short read>"); }
    if (h.magic != 0x46554747u) { fclose(f); return std::string("<bad magic>"); }

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
        if (key == "general.architecture" && type == LOCAL_GGUF_TYPE_STRING) {
            return read_gguf_string(buf, off);
        }
        if (!skip_gguf_value(buf, off, type)) break;
    }
    return std::string("<general.architecture not found>");
}

// ---------------------------------------------------------------------------
// UTF-8 handling for JNI NewStringUTF.
//
// PROBLEM: JNI's NewStringUTF expects "Modified UTF-8" — a JVM-specific
// variant where:
//   * The null byte U+0000 is encoded as 0xC0 0x80 (2 bytes).
//   * Characters above U+FFFF (4-byte UTF-8, e.g. emojis) are encoded as
//     TWO 3-byte sequences (the UTF-16 surrogate pair), NOT as a single
//     4-byte sequence.
//
// llama.cpp's llama_token_to_piece returns standard UTF-8. When a token
// contains an emoji (4-byte UTF-8 lead byte 0xF0-0xF4), NewStringUTF sees
// the 4-byte sequence and aborts the JVM with:
//   "JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8:
//    illegal continuation byte 0"
//
// This was the cause of the SIGABRT crash reported in the user's logcat
// after sending a chat message that produced emoji tokens.
//
// SOLUTION: We do two things here:
//   1. Buffer partial UTF-8 sequences. When a multi-byte character is split
//      across token boundaries (very common with BPE tokenizers), we hold
//      the partial bytes in `g_utf8_buf` and only emit once we have a
//      complete, well-formed UTF-8 sequence.
//   2. Convert well-formed 4-byte UTF-8 to Modified UTF-8 (surrogate pair)
//      before calling NewStringUTF.
// ---------------------------------------------------------------------------

// Returns the expected byte length of a UTF-8 sequence given its lead byte,
// or 0 if `c` is not a valid lead byte (i.e. it is ASCII or a continuation).
static size_t utf8_seq_len(unsigned char c) {
    if (c < 0x80) return 1;        // 0xxxxxxx
    if ((c & 0xE0) == 0xC0) return 2;  // 110xxxxx
    if ((c & 0xF0) == 0xE0) return 3;  // 1110xxxx
    if ((c & 0xF8) == 0xF0) return 4;  // 11110xxx
    return 0; // continuation byte or invalid
}

// Decode a single UTF-8 code point starting at `off` in `s`. Returns the
// code point in *cp and the number of bytes consumed in *len. Returns
// false if the sequence is malformed (caller should flush the buffer).
static bool utf8_decode(const std::string &s, size_t off, uint32_t *cp, size_t *len) {
    if (off >= s.size()) return false;
    unsigned char c = (unsigned char)s[off];
    size_t n = utf8_seq_len(c);
    if (n == 0 || off + n > s.size()) return false;

    uint32_t v = 0;
    switch (n) {
        case 1: v = c; break;
        case 2:
            v = (c & 0x1F);
            if (((unsigned char)s[off+1] & 0xC0) != 0x80) return false;
            v = (v << 6) | ((unsigned char)s[off+1] & 0x3F);
            if (v < 0x80) return false; // overlong
            break;
        case 3:
            v = (c & 0x0F);
            if (((unsigned char)s[off+1] & 0xC0) != 0x80) return false;
            if (((unsigned char)s[off+2] & 0xC0) != 0x80) return false;
            v = (v << 6) | ((unsigned char)s[off+1] & 0x3F);
            v = (v << 6) | ((unsigned char)s[off+2] & 0x3F);
            if (v < 0x800) return false; // overlong
            if (v >= 0xD800 && v <= 0xDFFF) return false; // surrogate
            break;
        case 4:
            v = (c & 0x07);
            if (((unsigned char)s[off+1] & 0xC0) != 0x80) return false;
            if (((unsigned char)s[off+2] & 0xC0) != 0x80) return false;
            if (((unsigned char)s[off+3] & 0xC0) != 0x80) return false;
            v = (v << 6) | ((unsigned char)s[off+1] & 0x3F);
            v = (v << 6) | ((unsigned char)s[off+2] & 0x3F);
            v = (v << 6) | ((unsigned char)s[off+3] & 0x3F);
            if (v < 0x10000) return false; // overlong
            if (v > 0x10FFFF) return false; // out of range
            break;
    }
    *cp = v;
    *len = n;
    return true;
}

// Convert a well-formed UTF-8 string to Modified UTF-8 (the format expected
// by JNI NewStringUTF). Characters above U+FFFF become UTF-16 surrogate
// pairs encoded as two 3-byte sequences; U+0000 becomes 0xC0 0x80.
static std::string utf8_to_modified_utf8(const std::string &in) {
    std::string out;
    out.reserve(in.size() + 8);
    size_t i = 0;
    while (i < in.size()) {
        uint32_t cp = 0;
        size_t n = 0;
        if (!utf8_decode(in, i, &cp, &n)) {
            // Should not happen if caller pre-validated; emit '?' as fallback.
            out.push_back('?');
            i++;
            continue;
        }
        if (cp == 0) {
            out.push_back((char)0xC0);
            out.push_back((char)0x80);
        } else if (cp < 0x80) {
            out.push_back((char)cp);
        } else if (cp < 0x800) {
            out.push_back((char)(0xC0 | (cp >> 6)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back((char)(0xE0 | (cp >> 12)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else {
            // Surrogate pair.
            cp -= 0x10000;
            uint16_t hi = 0xD800 + (cp >> 10);
            uint16_t lo = 0xDC00 + (cp & 0x3FF);
            // High surrogate
            out.push_back((char)(0xE0 | (hi >> 12)));
            out.push_back((char)(0x80 | ((hi >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (hi & 0x3F)));
            // Low surrogate
            out.push_back((char)(0xE0 | (lo >> 12)));
            out.push_back((char)(0x80 | ((lo >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (lo & 0x3F)));
        }
        i += n;
    }
    return out;
}

// A small UTF-8 buffering sink. Token pieces are appended via feed(); each
// time the buffer contains at least one complete, well-formed UTF-8
// sequence, that sequence (and any subsequent complete sequences) is
// converted to Modified UTF-8 and emitted to the JNI callback. Partial
// trailing bytes are kept in the buffer for the next feed() call.
//
// flush() emits any remaining bytes as a best-effort string (sanitised to
// '?' for invalid sequences) so we never lose data at end-of-generation.
class Utf8TokenSink {
public:
    Utf8TokenSink(JNIEnv *env, jobject callback, jmethodID onToken)
        : env_(env), callback_(callback), onToken_(onToken) {}

    void feed(const std::string &piece) {
        buf_ += piece;
        size_t i = 0;
        while (i < buf_.size()) {
            unsigned char c = (unsigned char)buf_[i];
            size_t n = utf8_seq_len(c);
            if (n == 0) {
                // Stray continuation byte or invalid lead — emit '?' and skip.
                emit_char('?');
                i++;
                continue;
            }
            if (i + n > buf_.size()) {
                // Incomplete sequence — wait for more bytes.
                break;
            }
            uint32_t cp = 0;
            size_t decoded = 0;
            if (!utf8_decode(buf_, i, &cp, &decoded)) {
                emit_char('?');
                i++;
                continue;
            }
            // Emit the complete sequence.
            std::string seq = buf_.substr(i, n);
            std::string mutf8 = utf8_to_modified_utf8(seq);
            emit(mutf8);
            i += n;
        }
        // Drop consumed bytes; keep any partial tail.
        if (i > 0) buf_.erase(0, i);
    }

    void flush() {
        if (!buf_.empty()) {
            // Best effort — sanitise to '?' for any incomplete sequence.
            std::string safe;
            safe.reserve(buf_.size());
            for (size_t i = 0; i < buf_.size(); i++) {
                unsigned char c = (unsigned char)buf_[i];
                if (c < 0x80) safe.push_back((char)c);
                else safe.push_back('?');
            }
            emit(safe);
            buf_.clear();
        }
    }

private:
    void emit(const std::string &mutf8) {
        jstring jpiece = env_->NewStringUTF(mutf8.c_str());
        env_->CallVoidMethod(callback_, onToken_, jpiece);
        env_->DeleteLocalRef(jpiece);
    }
    void emit_char(char c) {
        char buf[2] = {c, 0};
        jstring jpiece = env_->NewStringUTF(buf);
        env_->CallVoidMethod(callback_, onToken_, jpiece);
        env_->DeleteLocalRef(jpiece);
    }

    JNIEnv *env_;
    jobject callback_;
    jmethodID onToken_;
    std::string buf_;
};

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

    std::string arch = inspect_gguf_architecture(path);
    LOGI("GGUF architecture: %s", arch.c_str());

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    // use_mmap=true avoids copying the whole GGUF into RAM on load —
    // instead, pages are demand-paged from the file. This cuts load
    // time on the moto g34 5G from several seconds to under 1 second
    // for the 250 MB LFM2.5-230M-Q8_0 model.
    model_params.use_mmap = true;
    model_params.use_mlock = false;

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
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
    // Match pocketpal-ai defaults: a larger batch size speeds up prompt
    // processing for the search-generation prompt (~50 tokens) without
    // costing meaningfully more RAM.
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;

    g_ctx = llama_init_from_model(g_model, ctx_params);

    if (!g_ctx) {
        LOGE("Failed to create context (n_ctx=%d)", (int)nCtx);
        llama_model_free(g_model);
        g_model = nullptr;
        llama_backend_free();
        return 0;
    }

    LOGI("Model loaded successfully (arch=%s, n_ctx=%d, n_threads=%d)",
         arch.c_str(), (int)nCtx, (int)nThreads);
    return reinterpret_cast<jlong>(g_ctx);
}

// Run a single prompt → token stream. Shared by nativeGenerate (searches)
// and nativeChat (chat). The prompt must already be fully formatted.
// All token pieces go through Utf8TokenSink so partial multi-byte
// sequences are buffered and 4-byte UTF-8 (emoji) is converted to
// Modified UTF-8 before crossing the JNI boundary.
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

    Utf8TokenSink sink(env, callback, onToken);

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
            sink.feed(piece);
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

    // Flush any buffered partial UTF-8 so the UI gets the final bytes.
    sink.flush();

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

    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    if (!tmpl) {
        LOGE("Model has no embedded chat template; llama_chat_apply_template "
             "will fall back to its built-in ChatML default — this may be "
             "wrong for non-ChatML models.");
    } else {
        LOGI("Chat template: %.120s...", tmpl);
    }

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
