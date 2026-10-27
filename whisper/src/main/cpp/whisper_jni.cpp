// JNI bridge between com.ideaforge.whisper.WhisperNative and whisper.cpp (v1.7.4).
// Every entry point catches all C++ exceptions: letting one cross the JNI boundary aborts the process.
#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <exception>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "WhisperJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Converts UTF-8 (as produced by whisper, which may split multi-byte characters across tokens or
// emit invalid bytes) to UTF-16, replacing invalid sequences with U+FFFD. Going through NewString
// avoids NewStringUTF's modified-UTF-8 pitfalls (4-byte sequences, invalid input aborts under CheckJNI).
std::vector<jchar> utf8ToUtf16(const std::string &s) {
    std::vector<jchar> out;
    out.reserve(s.size());
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        uint32_t cp = 0xFFFD;
        size_t len = 1;
        if (c < 0x80) {
            cp = c;
        } else if (c >= 0xC2 && c <= 0xDF && i + 1 < n && (s[i + 1] & 0xC0) == 0x80) {
            cp = ((c & 0x1F) << 6) | (s[i + 1] & 0x3F);
            len = 2;
        } else if (c >= 0xE0 && c <= 0xEF && i + 2 < n && (s[i + 1] & 0xC0) == 0x80 &&
                   (s[i + 2] & 0xC0) == 0x80) {
            const uint32_t v = ((c & 0x0F) << 12) | ((s[i + 1] & 0x3F) << 6) | (s[i + 2] & 0x3F);
            if (v >= 0x800 && (v < 0xD800 || v > 0xDFFF)) {
                cp = v;
                len = 3;
            }
        } else if (c >= 0xF0 && c <= 0xF4 && i + 3 < n && (s[i + 1] & 0xC0) == 0x80 &&
                   (s[i + 2] & 0xC0) == 0x80 && (s[i + 3] & 0xC0) == 0x80) {
            const uint32_t v = ((c & 0x07) << 18) | ((s[i + 1] & 0x3F) << 12) |
                               ((s[i + 2] & 0x3F) << 6) | (s[i + 3] & 0x3F);
            if (v >= 0x10000 && v <= 0x10FFFF) {
                cp = v;
                len = 4;
            }
        }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<jchar>(cp));
        }
        i += len;
    }
    return out;
}

jstring toJString(JNIEnv *env, const std::string &s) {
    const std::vector<jchar> u16 = utf8ToUtf16(s);
    return env->NewString(u16.data(), static_cast<jsize>(u16.size()));
}

// Copies a Java string into a std::string (modified UTF-8, fine for prompts/language codes).
std::string fromJString(JNIEnv *env, jstring js) {
    if (js == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(js, nullptr);
    if (chars == nullptr) return std::string();
    std::string s(chars);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

// Tabs and newlines would break the line protocol, so flatten them in segment text.
std::string sanitizeText(const char *text) {
    std::string s = text != nullptr ? text : "";
    for (char &c : s) {
        if (c == '\t' || c == '\n' || c == '\r') c = ' ';
    }
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ideaforge_whisper_WhisperNative_nativeInit(JNIEnv *env, jobject, jstring modelPath) {
    try {
        const std::string path = fromJString(env, modelPath);
        whisper_context_params cparams = whisper_context_default_params();
        cparams.use_gpu = false;
        whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
        return reinterpret_cast<jlong>(ctx);  // 0 on failure
    } catch (...) {
        LOGE("nativeInit threw");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_ideaforge_whisper_WhisperNative_nativeFree(JNIEnv *, jobject, jlong ctxPtr) {
    try {
        if (ctxPtr != 0) whisper_free(reinterpret_cast<whisper_context *>(ctxPtr));
    } catch (...) {
        LOGE("nativeFree threw");
    }
}

JNIEXPORT jstring JNICALL
Java_com_ideaforge_whisper_WhisperNative_nativeTranscribe(JNIEnv *env, jobject, jlong ctxPtr,
                                                           jfloatArray pcm, jint threads,
                                                           jstring language, jstring prompt) {
    try {
        auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
        if (ctx == nullptr) return toJString(env, "ERROR: model is not loaded");
        if (pcm == nullptr) return toJString(env, "ERROR: no audio");

        const std::string lang = fromJString(env, language);
        const std::string initialPrompt = fromJString(env, prompt);

        const jsize n = env->GetArrayLength(pcm);
        jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
        if (samples == nullptr) return toJString(env, "ERROR: could not access audio buffer");

        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.no_context = true;
        params.single_segment = false;
        params.token_timestamps = false;
        params.translate = false;
        params.n_threads = threads > 0 ? threads : 2;
        params.language = lang.empty() ? "auto" : lang.c_str();
        params.detect_language = lang.empty();
        params.initial_prompt = initialPrompt.empty() ? nullptr : initialPrompt.c_str();

        int rc;
        try {
            rc = whisper_full(ctx, params, samples, static_cast<int>(n));
        } catch (...) {
            env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
            throw;
        }
        // JNI_ABORT: the buffer was only read, so skip the copy back.
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        if (rc != 0) {
            char msg[64];
            snprintf(msg, sizeof(msg), "ERROR: whisper_full failed (%d)", rc);
            return toJString(env, msg);
        }

        std::string result;
        const int segments = whisper_full_n_segments(ctx);
        const whisper_token eot = whisper_token_eot(ctx);
        for (int i = 0; i < segments; i++) {
            // t0/t1 are in 10 ms units.
            const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
            const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
            double sum = 0.0;
            int count = 0;
            const int tokens = whisper_full_n_tokens(ctx, i);
            for (int j = 0; j < tokens; j++) {
                // Special tokens (EOT, timestamps) would skew the confidence of the spoken text.
                if (whisper_full_get_token_id(ctx, i, j) >= eot) continue;
                sum += whisper_full_get_token_p(ctx, i, j);
                count++;
            }
            const double avg = count > 0 ? sum / count : 0.0;
            char head[96];
            snprintf(head, sizeof(head), "%lld\t%lld\t%.4f\t", static_cast<long long>(t0),
                     static_cast<long long>(t1), avg);
            result += head;
            result += sanitizeText(whisper_full_get_segment_text(ctx, i));
            result += '\n';
        }
        return toJString(env, result);
    } catch (const std::exception &e) {
        LOGE("nativeTranscribe threw: %s", e.what());
        return toJString(env, std::string("ERROR: ") + e.what());
    } catch (...) {
        LOGE("nativeTranscribe threw");
        return toJString(env, "ERROR: unknown native failure");
    }
}

}  // extern "C"
