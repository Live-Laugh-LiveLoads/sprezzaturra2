package com.ideaforge.whisper

import android.util.Log

/**
 * Thin JNI surface over whisper.cpp. Not thread-safe per context: callers (see
 * [WhisperCppTranscriptionProvider]) serialize access.
 */
object WhisperNative {
    private val loaded: Boolean = try {
        System.loadLibrary("whisper_jni")
        true
    } catch (t: Throwable) {
        // UnsatisfiedLinkError when built with ideaforge.whisperNative=false or on an unsupported ABI.
        Log.w("WhisperNative", "whisper_jni not available: ${t.message}")
        false
    }

    /** True when the native library loaded; false means on-device transcription cannot be used. */
    fun isAvailable(): Boolean = loaded

    /** Loads a ggml model; returns a context pointer, or 0 on failure. */
    external fun nativeInit(modelPath: String): Long

    external fun nativeFree(ctx: Long)

    /**
     * Transcribes 16 kHz mono float PCM. Returns one line per segment,
     * `t0_ms\tt1_ms\tavg_token_prob\ttext`, or a string starting with `ERROR:`.
     */
    external fun nativeTranscribe(
        ctx: Long,
        pcm16k: FloatArray,
        threads: Int,
        language: String?,
        prompt: String?,
    ): String
}
