package com.ideaforge.whisper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A downloadable ggml model. [sizeMb] is approximate and only used for display. */
data class WhisperModelInfo(
    val name: String,
    val sizeMb: Int,
    val url: String,
    val note: String,
)

/**
 * Downloads and stores whisper.cpp models under `filesDir/whisper/`.
 *
 * On-device transcription is slow on old hardware: tiny.en runs roughly in real time on a 2018
 * mid-range phone, base.en is two to three times slower (but more accurate).
 */
class WhisperModelManager(
    context: Context,
    private val client: OkHttpClient,
) {
    private val dir = File(context.filesDir, "whisper")

    val models: List<WhisperModelInfo> = listOf(
        WhisperModelInfo(
            name = "tiny.en",
            sizeMb = 75,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
            note = "Fastest, roughly real-time on a 2018 mid-range phone. English only, lower accuracy.",
        ),
        WhisperModelInfo(
            name = "base.en",
            sizeMb = 142,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
            note = "Better accuracy, about 2-3x slower than tiny.en. English only.",
        ),
    )

    fun modelFile(name: String): File = File(dir, "ggml-$name.bin")

    fun isDownloaded(name: String): Boolean = modelFile(name).let { it.isFile && it.length() > MIN_MODEL_BYTES }

    fun delete(name: String) {
        modelFile(name).delete()
        File(dir, "ggml-$name.bin.part").delete()
    }

    /**
     * Streams the model to a `.part` file, verifies it and renames it into place. [onProgress]
     * receives 0..1 (stays 0 if the server sends no Content-Length). Cancelling the calling
     * coroutine aborts the HTTP call; the partial file is deleted on any failure.
     */
    @Throws(IOException::class)
    suspend fun download(name: String, onProgress: (Float) -> Unit) {
        val info = models.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown Whisper model: $name")
        withContext(Dispatchers.IO) {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
            val target = modelFile(name)
            val part = File(dir, target.name + ".part")
            val call = client.newCall(Request.Builder().url(info.url).build())
            // The loop below checks ensureActive() between reads; the completion handler also cancels the
            // HTTP call so a stalled connection is torn down once the coroutine has been cancelled.
            val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Download failed: empty response")
                    val total = body.contentLength()
                    var done = 0L
                    var lastReported = -1f
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = done.toFloat() / total
                                    // Throttle to ~1% steps so the UI is not flooded.
                                    if (p - lastReported >= 0.01f) {
                                        lastReported = p
                                        onProgress(p.coerceAtMost(1f))
                                    }
                                }
                            }
                        }
                    }
                }
                verify(part)
                target.delete()
                if (!part.renameTo(target)) throw IOException("Could not move model into place")
                onProgress(1f)
            } catch (t: Throwable) {
                part.delete()
                throw t
            } finally {
                handle?.dispose()
            }
        }
    }

    /** Rejects truncated downloads and HTML error pages by size and the ggml magic number. */
    private fun verify(file: File) {
        if (file.length() <= MIN_MODEL_BYTES) throw IOException("Downloaded model is too small (${file.length()} bytes)")
        val magic = ByteArray(4)
        file.inputStream().use { if (it.read(magic) != 4) throw IOException("Downloaded model is truncated") }
        // 0x67676d6c ("ggml") stored little-endian reads as "lmgg".
        val expected = byteArrayOf(0x6c, 0x6d, 0x67, 0x67)
        if (!magic.contentEquals(expected)) throw IOException("Downloaded file is not a ggml model")
    }

    private companion object {
        const val MIN_MODEL_BYTES = 10L * 1024 * 1024
    }
}
