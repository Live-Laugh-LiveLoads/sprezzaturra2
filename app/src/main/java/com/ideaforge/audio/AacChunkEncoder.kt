package com.ideaforge.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Encodes mono PCM16 to AAC-LC with [MediaCodec] and writes it to [file] as a plain ADTS stream.
 *
 * ADTS has no index or trailer, so the file stays decodable if the process dies mid-chunk. The
 * stream is flushed to disk at least every [FLUSH_INTERVAL_MS] so a crash loses at most that much
 * audio. Not thread-safe: the recorder touches each instance only from its audio thread.
 */
class AacChunkEncoder(
    val file: File,
    private val sampleRate: Int,
    bitrateKbps: Int,
) {
    private val info = MediaCodec.BufferInfo()
    private var codec: MediaCodec? = null
    private var fileStream: FileOutputStream? = null
    private var out: BufferedOutputStream? = null

    /** Reused for every output frame (header + payload) so encoding allocates nothing per frame. */
    private var frame = ByteArray(2048)
    private var lastFlush = SystemClock.elapsedRealtime()
    private var closed = false

    /** PCM samples accepted so far; also drives presentationTimeUs. */
    var samplesFed: Long = 0
        private set

    /** Complete ADTS frames written so far. */
    var framesWritten: Int = 0
        private set

    init {
        Adts.sampleRateIndex(sampleRate) // fail early with a clear message
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = c
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val fos = FileOutputStream(file)
            fileStream = fos
            out = BufferedOutputStream(fos, 16 * 1024)
        } catch (t: Throwable) {
            abort()
            throw if (t is IOException) t else IOException("Could not start the AAC encoder: ${t.message}", t)
        }
    }

    /** Encodes [length] bytes of little-endian PCM16 from [pcm] starting at [offset]. */
    @Throws(IOException::class)
    fun feed(pcm: ByteArray, offset: Int, length: Int) {
        check(!closed) { "Encoder already finished" }
        val c = codec ?: throw IOException("Encoder released")
        var pos = offset
        var left = length and 1.inv() // whole 16-bit samples only
        var stalls = 0
        try {
            while (left > 0) {
                val idx = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (idx < 0) {
                    // Input is full because output has not been drained; drain and retry.
                    drain(0)
                    if (++stalls > MAX_STALLS) throw IOException("AAC encoder stalled")
                    continue
                }
                stalls = 0
                val buf = c.getInputBuffer(idx) ?: throw IOException("No encoder input buffer")
                buf.clear()
                val n = minOf(left, buf.capacity())
                buf.put(pcm, pos, n)
                c.queueInputBuffer(idx, 0, n, ptsUs(), 0)
                samplesFed += n / 2
                pos += n
                left -= n
                drain(0)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("AAC encoding failed: ${e.message}", e)
        }
    }

    /** Pushes buffered bytes to disk (used on pause and by the periodic flush). */
    fun flush() {
        try {
            out?.flush()
            lastFlush = SystemClock.elapsedRealtime()
        } catch (e: IOException) {
            Log.w(TAG, "flush failed", e)
        }
    }

    /**
     * Signals end of stream, drains the remaining frames and closes the file. Codec trouble here is
     * logged rather than thrown because the file is still a valid ADTS stream up to the last frame;
     * only a failure to write or close the file is reported.
     */
    @Throws(IOException::class)
    fun finish() {
        if (closed) return
        val c = codec
        try {
            if (c != null) {
                val deadline = SystemClock.elapsedRealtime() + FINISH_TIMEOUT_MS
                var queued = false
                while (!queued && SystemClock.elapsedRealtime() < deadline) {
                    val idx = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (idx >= 0) {
                        c.queueInputBuffer(idx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        queued = true
                    } else {
                        drain(0)
                    }
                }
                if (queued) {
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (drain(INPUT_TIMEOUT_US)) break
                    }
                }
            }
        } catch (e: IOException) {
            closeQuietly()
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "encoder failed while finishing ${file.name}", e)
        }
        closeStream()
    }

    /** Releases everything without draining. Safe to call repeatedly. */
    fun abort() {
        closed = true
        releaseCodec()
        closeQuietly()
    }

    private fun ptsUs(): Long = samplesFed * 1_000_000L / sampleRate

    /** Writes every available output frame; returns true once the end-of-stream buffer was seen. */
    private fun drain(timeoutUs: Long): Boolean {
        val c = codec ?: return false
        while (true) {
            val idx = c.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                idx < 0 -> continue // format changed or buffers changed: nothing to do for ADTS
                else -> {
                    var eos = false
                    try {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        // The codec-config (AudioSpecificConfig) buffer is not an audio frame; ADTS
                        // carries the same information in every header.
                        if (!isConfig && info.size > 0) {
                            val data = c.getOutputBuffer(idx) ?: throw IOException("No encoder output buffer")
                            writeFrame(data)
                        }
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        c.releaseOutputBuffer(idx, false)
                    }
                    if (eos) return true
                }
            }
        }
    }

    private fun writeFrame(data: ByteBuffer) {
        val size = info.size
        val total = size + Adts.HEADER_SIZE
        if (frame.size < total) frame = ByteArray(total + total / 2)
        Adts.writeHeader(frame, 0, total, sampleRate, CHANNELS)
        data.position(info.offset)
        data.limit(info.offset + size)
        data.get(frame, Adts.HEADER_SIZE, size)
        val stream = out ?: throw IOException("Encoder output closed")
        stream.write(frame, 0, total)
        framesWritten++
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlush >= FLUSH_INTERVAL_MS) {
            stream.flush()
            lastFlush = now
        }
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (_: RuntimeException) { }
        try { c.release() } catch (_: RuntimeException) { }
    }

    @Throws(IOException::class)
    private fun closeStream() {
        closed = true
        releaseCodec()
        val o = out
        val f = fileStream
        out = null
        fileStream = null
        try {
            o?.flush()
            try { f?.fd?.sync() } catch (_: IOException) { }
        } finally {
            try { o?.close() } catch (_: IOException) { }
            try { f?.close() } catch (_: IOException) { }
        }
    }

    private fun closeQuietly() {
        try { closeStream() } catch (_: IOException) { }
    }

    private companion object {
        const val TAG = "AacChunkEncoder"
        const val CHANNELS = 1
        const val MAX_INPUT_BYTES = 16 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_STALLS = 300
        const val FINISH_TIMEOUT_MS = 3_000L
        const val FLUSH_INTERVAL_MS = 2_000L
    }
}
