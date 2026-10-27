package com.ideaforge.audio

import java.io.IOException
import java.io.InputStream
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ADTS (Audio Data Transport Stream) framing for AAC. ADTS is self-synchronising, so a file that
 * was cut off by a crash is still decodable up to the last complete frame; that is why recording
 * writes ADTS and only remuxes to MP4 once a chunk is closed. Pure JVM code (unit tested).
 */
object Adts {
    const val HEADER_SIZE = 7

    /** Samples per AAC-LC frame. */
    const val SAMPLES_PER_FRAME = 1024

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )

    /** Index of [sampleRate] in the ADTS frequency table; throws for rates ADTS cannot express. */
    fun sampleRateIndex(sampleRate: Int): Int {
        val i = SAMPLE_RATES.indexOf(sampleRate)
        require(i >= 0) { "Sample rate $sampleRate Hz is not valid for ADTS" }
        return i
    }

    /**
     * Writes a 7-byte MPEG-4, AAC-LC, no-CRC header for a frame whose total length including the
     * header is [frameLength] bytes.
     */
    fun writeHeader(dst: ByteArray, offset: Int, frameLength: Int, sampleRate: Int, channels: Int) {
        require(frameLength in HEADER_SIZE..0x1FFF) { "ADTS frame length out of range: $frameLength" }
        require(channels in 1..7) { "Invalid channel configuration: $channels" }
        val profile = 1 // AAC-LC (audio object type 2) minus one
        val freq = sampleRateIndex(sampleRate)
        dst[offset] = 0xFF.toByte()
        dst[offset + 1] = 0xF1.toByte() // sync, MPEG-4, layer 0, protection absent
        dst[offset + 2] = ((profile shl 6) or (freq shl 2) or (channels shr 2)).toByte()
        dst[offset + 3] = (((channels and 3) shl 6) or (frameLength shr 11)).toByte()
        dst[offset + 4] = ((frameLength shr 3) and 0xFF).toByte()
        dst[offset + 5] = (((frameLength and 7) shl 5) or 0x1F).toByte()
        dst[offset + 6] = 0xFC.toByte() // buffer fullness (VBR) low bits, one raw data block
    }

    /** True if the 7 bytes at [offset] start with an ADTS sync word. */
    fun isHeader(b: ByteArray, offset: Int): Boolean =
        (b[offset].toInt() and 0xFF) == 0xFF && (b[offset + 1].toInt() and 0xF6) == 0xF0

    /** Total frame length (header included) encoded in the header at [offset]. */
    fun frameLength(b: ByteArray, offset: Int): Int =
        ((b[offset + 3].toInt() and 3) shl 11) or ((b[offset + 4].toInt() and 0xFF) shl 3) or ((b[offset + 5].toInt() and 0xFF) shr 5)

    /** Sample rate encoded in the header at [offset], or -1 for a reserved index. */
    fun sampleRate(b: ByteArray, offset: Int): Int =
        SAMPLE_RATES.getOrElse((b[offset + 2].toInt() shr 2) and 0xF) { -1 }

    /** Result of walking an ADTS stream: complete frames only, a trailing partial frame is ignored. */
    data class Scan(val frames: Int, val sampleRate: Int, val validBytes: Long) {
        val durationMs: Long
            get() = if (sampleRate > 0) frames.toLong() * SAMPLES_PER_FRAME * 1000L / sampleRate else 0L
    }

    /**
     * Counts the complete frames in [input] by reading headers and skipping bodies. Stops quietly at
     * the first sign of damage (bad sync, impossible length, truncated body), so a half-written
     * file yields everything that is recoverable.
     */
    fun scan(input: InputStream): Scan {
        val header = ByteArray(HEADER_SIZE)
        val scratch = ByteArray(4096)
        var frames = 0
        var rate = 0
        var pos = 0L
        var valid = 0L
        while (true) {
            if (!readFully(input, header, HEADER_SIZE)) break
            if (!isHeader(header, 0)) break
            val len = frameLength(header, 0)
            val r = sampleRate(header, 0)
            if (len < HEADER_SIZE || r <= 0) break
            if (rate == 0) rate = r else if (r != rate) break
            var left = len - HEADER_SIZE
            var complete = true
            while (left > 0) {
                val n = input.read(scratch, 0, minOf(left, scratch.size))
                if (n < 0) { complete = false; break }
                left -= n
            }
            if (!complete) break
            pos += len
            valid = pos
            frames++
        }
        return Scan(frames, rate, valid)
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var got = 0
        while (got < len) {
            val n = input.read(buf, got, len - got)
            if (n < 0) return false
            got += n
        }
        return true
    }
}

/**
 * Where chunks begin and end, in audio time (samples actually captured). Chunk k starts at
 * k * stride samples and lasts [chunkSamples]; consecutive chunks therefore share
 * (chunkSamples - stride) = overlap samples, produced by feeding one mic stream to two encoders.
 */
class ChunkGeometry(val sampleRate: Int, chunkMinutes: Int, overlapSeconds: Int) {
    val chunkSamples: Long = chunkMinutes.coerceAtLeast(1) * 60L * sampleRate

    /** Overlap is capped at half a chunk so the stride always makes forward progress. */
    val overlapSamples: Long = overlapSeconds.coerceAtLeast(0).toLong().coerceAtMost(chunkMinutes.coerceAtLeast(1) * 30L) * sampleRate

    val strideSamples: Long = chunkSamples - overlapSamples

    enum class Action { NONE, START_NEXT, FINISH_FIRST }

    /** Start offset of chunk [index] within the recording's audio time. */
    fun startOffsetMs(index: Int): Long = index.toLong() * strideSamples * 1000L / sampleRate

    fun samplesToMs(samples: Long): Long = samples * 1000L / sampleRate

    /**
     * What must happen now, given how many encoders are live ([activeCount], 1 or 2) and how many
     * samples the oldest one ([firstSamples]) has received. Call repeatedly until [Action.NONE].
     */
    fun nextAction(activeCount: Int, firstSamples: Long): Action = when {
        activeCount <= 1 && firstSamples >= strideSamples -> Action.START_NEXT
        activeCount >= 2 && firstSamples >= chunkSamples -> Action.FINISH_FIRST
        else -> Action.NONE
    }

    /** How many more samples can be fed before [nextAction] stops returning NONE. Always > 0 when settled. */
    fun samplesUntilBoundary(activeCount: Int, firstSamples: Long): Long =
        if (activeCount <= 1) strideSamples - firstSamples else chunkSamples - firstSamples
}

/** Input level metering. */
object LevelMeter {
    private const val FLOOR_DB = -60.0

    /** Maps an RMS amplitude (0..32768) to 0..1 over -60..0 dBFS. */
    fun levelFromRms(rms: Double): Float {
        if (rms <= 0.0) return 0f
        val db = 20.0 * log10(rms / 32768.0)
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
    }

    /** Level of [byteCount] bytes of little-endian PCM16 starting at [offset]; allocation free. */
    fun levelOfPcm16(pcm: ByteArray, offset: Int, byteCount: Int): Float {
        val samples = byteCount / 2
        if (samples == 0) return 0f
        var sum = 0.0
        var i = offset
        val end = offset + samples * 2
        while (i < end) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sum += (s * s).toDouble()
            i += 2
        }
        return levelFromRms(sqrt(sum / samples))
    }
}
