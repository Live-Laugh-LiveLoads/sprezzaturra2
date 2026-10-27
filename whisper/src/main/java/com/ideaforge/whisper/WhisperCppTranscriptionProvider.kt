package com.ideaforge.whisper

import com.ideaforge.core.llm.ProviderException
import com.ideaforge.core.transcript.Segment
import com.ideaforge.core.transcript.Transcript
import com.ideaforge.core.transcription.TranscriptionOptions
import com.ideaforge.core.transcription.TranscriptionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * On-device transcription with whisper.cpp. Slow on old hardware: expect tiny.en to run roughly
 * in real time on a 2018 mid-range phone and base.en two to three times slower.
 *
 * A running native transcription cannot be interrupted; cancelling the coroutine takes effect
 * once the current chunk finishes.
 */
class WhisperCppTranscriptionProvider(
    private val modelFile: File,
    modelName: String,
    private val threads: Int,
) : TranscriptionProvider {
    override val modelId: String = "whisper.cpp-$modelName"
    override val requiresNetwork: Boolean = false
    override val supportsDiarization: Boolean = false

    override suspend fun transcribe(audio: File, options: TranscriptionOptions): Transcript =
        withContext(Dispatchers.Default) {
            if (!WhisperNative.isAvailable()) {
                throw ProviderException(
                    "On-device transcription is not available in this build (native library missing).",
                    retryable = false,
                )
            }
            if (!modelFile.isFile) {
                throw ProviderException(
                    "Whisper model ${modelFile.name} is missing. Download the on-device model in Settings > Transcription.",
                    retryable = false,
                )
            }
            val pcm = try {
                PcmDecoder.decode(audio)
            } catch (e: IOException) {
                throw ProviderException("Could not read audio: ${e.message}", retryable = false, cause = e)
            }
            if (pcm.isEmpty()) return@withContext Transcript(emptyList(), options.language)

            val raw = lock.withLock {
                val ctx = contextFor(modelFile)
                WhisperNative.nativeTranscribe(
                    ctx,
                    pcm,
                    threads.coerceAtLeast(1),
                    options.language?.takeIf { it.isNotBlank() },
                    options.vocabularyHint?.takeIf { it.isNotBlank() },
                )
            }
            if (raw.startsWith("ERROR:")) {
                throw ProviderException("Whisper failed: ${raw.removePrefix("ERROR:").trim()}", retryable = false)
            }
            Transcript(parseSegments(raw), options.language)
        }

    /** Loads (or reuses) the context for [file]; must be called with [lock] held. */
    private fun contextFor(file: File): Long {
        val path = file.absolutePath
        if (cachedCtx != 0L && cachedPath == path) return cachedCtx
        releaseLocked()
        val ctx = WhisperNative.nativeInit(path)
        if (ctx == 0L) {
            throw ProviderException(
                "Could not load Whisper model ${file.name}. It may be corrupt: delete and download it again in Settings > Transcription.",
                retryable = false,
            )
        }
        cachedCtx = ctx
        cachedPath = path
        return ctx
    }

    companion object {
        // Whisper contexts are not thread-safe and each holds the whole model in RAM, so exactly one
        // is kept and calls are serialized.
        private val lock = Mutex()
        private var cachedCtx: Long = 0L
        private var cachedPath: String? = null

        private fun releaseLocked() {
            if (cachedCtx != 0L) WhisperNative.nativeFree(cachedCtx)
            cachedCtx = 0L
            cachedPath = null
        }

        /** Frees the cached model, e.g. before deleting its file or on low memory. */
        suspend fun release() = lock.withLock { releaseLocked() }

        /** Parses `t0_ms\tt1_ms\tavg_prob\ttext` lines, dropping empty and blank-audio segments. */
        internal fun parseSegments(raw: String): List<Segment> =
            raw.lineSequence().mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size < 4) return@mapNotNull null
                val start = parts[0].toLongOrNull() ?: return@mapNotNull null
                val end = parts[1].toLongOrNull() ?: return@mapNotNull null
                val text = parts[3].trim()
                if (text.isEmpty() || text == "[BLANK_AUDIO]") return@mapNotNull null
                Segment(start, end, text, confidence = parts[2].toDoubleOrNull()?.coerceIn(0.0, 1.0))
            }.toList()
    }
}
