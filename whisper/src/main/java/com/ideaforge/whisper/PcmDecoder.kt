package com.ideaforge.whisper

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes an audio file (AAC in .m4a/.aac, or anything MediaCodec supports) to 16 kHz mono float PCM
 * in [-1, 1], which is what whisper.cpp expects.
 *
 * Memory: samples are streamed into one growable float buffer; a 5-minute chunk is 4.8 M floats
 * (about 19 MB), plus a second copy of that size at the end (trim, or resample when the source
 * rate is not 16 kHz). That is acceptable for one chunk at a time, but do not pass hour-long files.
 */
object PcmDecoder {
    const val TARGET_RATE = 16_000
    private const val TIMEOUT_US = 10_000L

    /** Growable float array that avoids boxing and avoids copying until [toArray]. */
    private class FloatBuffer(initial: Int) {
        var data = FloatArray(initial)
        var size = 0

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size + (data.size shr 1) + 1024)
            data[size++] = v
        }

        fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
    }

    @Throws(IOException::class)
    fun decode(file: File): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in ${file.name}")
            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!

            var sampleRate = inFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_RATE)
            var channels = inFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var floatPcm = false

            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val out = FloatBuffer(((durationUs / 1_000_000.0) * sampleRate).toInt().coerceIn(16_000, 20_000_000))

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        sampleRate = f.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = f.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        floatPcm = f.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buf = dec.getOutputBuffer(outIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            appendMono(buf, channels.coerceAtLeast(1), floatPcm, out)
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val mono = out.toArray()
            return if (sampleRate == TARGET_RATE) mono else resampleLinear(mono, sampleRate, TARGET_RATE)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not decode ${file.name}: ${e.message}", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Averages the interleaved channels of each frame into one mono sample. */
    private fun appendMono(buf: java.nio.ByteBuffer, channels: Int, floatPcm: Boolean, out: FloatBuffer) {
        if (floatPcm) {
            val fb = buf.asFloatBuffer()
            while (fb.remaining() >= channels) {
                var sum = 0f
                repeat(channels) { sum += fb.get() }
                out.add(sum / channels)
            }
        } else {
            val sb = buf.asShortBuffer()
            while (sb.remaining() >= channels) {
                var sum = 0
                repeat(channels) { sum += sb.get() }
                out.add(sum / (channels * 32768f))
            }
        }
    }

    /** Linear interpolation; adequate for speech going into a model that works on log-mel features. */
    internal fun resampleLinear(input: FloatArray, from: Int, to: Int): FloatArray {
        if (input.isEmpty() || from == to) return input
        val outLen = (input.size.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        val ratio = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = input[idx.coerceAtMost(input.size - 1)]
            val b = input[(idx + 1).coerceAtMost(input.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}
