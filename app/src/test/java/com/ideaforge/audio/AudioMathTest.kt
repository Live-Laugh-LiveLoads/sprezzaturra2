package com.ideaforge.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AdtsTest {
    private fun frame(payload: Int, rate: Int = 16_000): ByteArray {
        val total = payload + Adts.HEADER_SIZE
        val b = ByteArray(total)
        Adts.writeHeader(b, 0, total, rate, 1)
        return b
    }

    @Test
    fun headerRoundTrips() {
        val b = frame(100)
        assertThat(Adts.isHeader(b, 0)).isTrue()
        assertThat(Adts.frameLength(b, 0)).isEqualTo(107)
        assertThat(Adts.sampleRate(b, 0)).isEqualTo(16_000)
    }

    @Test
    fun headerMatchesKnownBytesFor16kMono() {
        val b = ByteArray(7)
        Adts.writeHeader(b, 0, 0x57, 16_000, 1)
        // 0xFFF1, AAC-LC + freq index 8 + channel config 1, length 0x57, fullness 0x7FF
        assertThat(b.map { it.toInt() and 0xFF }).containsExactly(0xFF, 0xF1, 0x60, 0x40, 0x0A, 0xFF, 0xFC).inOrder()
    }

    @Test
    fun largeFrameLengthUsesAllThirteenBits() {
        val b = ByteArray(7)
        Adts.writeHeader(b, 0, 0x1FFF, 44_100, 2)
        assertThat(Adts.frameLength(b, 0)).isEqualTo(0x1FFF)
        assertThat(Adts.sampleRate(b, 0)).isEqualTo(44_100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSampleRateIsRejected() {
        Adts.writeHeader(ByteArray(7), 0, 20, 12_345, 1)
    }

    @Test
    fun scanCountsFramesAndDuration() {
        val out = ByteArrayOutputStream()
        repeat(250) { out.write(frame(60)) }
        val scan = Adts.scan(ByteArrayInputStream(out.toByteArray()))
        assertThat(scan.frames).isEqualTo(250)
        assertThat(scan.sampleRate).isEqualTo(16_000)
        assertThat(scan.validBytes).isEqualTo(250L * 67)
        // 250 * 1024 / 16000 = 16 s
        assertThat(scan.durationMs).isEqualTo(16_000)
    }

    @Test
    fun scanIgnoresTruncatedLastFrame() {
        val out = ByteArrayOutputStream()
        repeat(10) { out.write(frame(60)) }
        val whole = out.toByteArray()
        val cut = whole.copyOf(whole.size - 20)
        val scan = Adts.scan(ByteArrayInputStream(cut))
        assertThat(scan.frames).isEqualTo(9)
        assertThat(scan.validBytes).isEqualTo(9L * 67)
    }

    @Test
    fun scanStopsAtTruncatedHeader() {
        val out = ByteArrayOutputStream()
        repeat(3) { out.write(frame(10)) }
        out.write(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x60))
        assertThat(Adts.scan(ByteArrayInputStream(out.toByteArray())).frames).isEqualTo(3)
    }

    @Test
    fun scanStopsAtGarbage() {
        val out = ByteArrayOutputStream()
        repeat(4) { out.write(frame(10)) }
        out.write(ByteArray(40) { 0x11 })
        val scan = Adts.scan(ByteArrayInputStream(out.toByteArray()))
        assertThat(scan.frames).isEqualTo(4)
        assertThat(scan.validBytes).isEqualTo(4L * 17)
    }

    @Test
    fun emptyInputHasNoFrames() {
        val scan = Adts.scan(ByteArrayInputStream(ByteArray(0)))
        assertThat(scan.frames).isEqualTo(0)
        assertThat(scan.durationMs).isEqualTo(0)
    }
}

class ChunkGeometryTest {
    private val g = ChunkGeometry(sampleRate = 16_000, chunkMinutes = 5, overlapSeconds = 5)

    @Test
    fun sizesAreInSamples() {
        assertThat(g.chunkSamples).isEqualTo(5L * 60 * 16_000)
        assertThat(g.overlapSamples).isEqualTo(5L * 16_000)
        assertThat(g.strideSamples).isEqualTo(295L * 16_000)
    }

    @Test
    fun chunkStartOffsetsAdvanceByStride() {
        assertThat(g.startOffsetMs(0)).isEqualTo(0)
        assertThat(g.startOffsetMs(1)).isEqualTo(295_000)
        assertThat(g.startOffsetMs(3)).isEqualTo(885_000)
    }

    @Test
    fun nextChunkStartsAtLengthMinusOverlap() {
        assertThat(g.nextAction(1, g.strideSamples - 1)).isEqualTo(ChunkGeometry.Action.NONE)
        assertThat(g.nextAction(1, g.strideSamples)).isEqualTo(ChunkGeometry.Action.START_NEXT)
    }

    @Test
    fun firstChunkFinishesAtFullLengthWhileTwoAreLive() {
        assertThat(g.nextAction(2, g.chunkSamples - 1)).isEqualTo(ChunkGeometry.Action.NONE)
        assertThat(g.nextAction(2, g.chunkSamples)).isEqualTo(ChunkGeometry.Action.FINISH_FIRST)
    }

    @Test
    fun samplesUntilBoundaryIsExact() {
        assertThat(g.samplesUntilBoundary(1, 1_000)).isEqualTo(g.strideSamples - 1_000)
        assertThat(g.samplesUntilBoundary(2, 1_000)).isEqualTo(g.chunkSamples - 1_000)
    }

    @Test
    fun simulationProducesOverlappingChunksOfFullLength() {
        // Replays the recorder's feed loop on a 12 minute recording using only the pure geometry.
        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        var total = 0L
        val firstSamples = ArrayList<Long>() // samples per live chunk, oldest first
        fun settle() {
            while (true) {
                when (g.nextAction(firstSamples.size, firstSamples[0])) {
                    ChunkGeometry.Action.START_NEXT -> { starts.add(total); firstSamples.add(0) }
                    ChunkGeometry.Action.FINISH_FIRST -> { ends.add(total); firstSamples.removeAt(0) }
                    ChunkGeometry.Action.NONE -> return
                }
            }
        }
        starts.add(0); firstSamples.add(0)
        val readSamples = 1_600L
        val target = 12L * 60 * 16_000
        while (total < target) {
            var remaining = readSamples
            while (remaining > 0) {
                val take = minOf(remaining, g.samplesUntilBoundary(firstSamples.size, firstSamples[0]))
                for (i in firstSamples.indices) firstSamples[i] += take
                total += take
                remaining -= take
                settle()
            }
        }
        assertThat(starts.take(3)).containsExactly(0L, g.strideSamples, 2 * g.strideSamples).inOrder()
        assertThat(ends.take(2)).containsExactly(g.chunkSamples, g.strideSamples + g.chunkSamples).inOrder()
    }

    @Test
    fun zeroOverlapStartsAndFinishesAtTheSameInstant() {
        val z = ChunkGeometry(16_000, 1, 0)
        assertThat(z.nextAction(1, z.strideSamples)).isEqualTo(ChunkGeometry.Action.START_NEXT)
        assertThat(z.nextAction(2, z.chunkSamples)).isEqualTo(ChunkGeometry.Action.FINISH_FIRST)
    }

    @Test
    fun overlapIsCappedAtHalfAChunk() {
        val c = ChunkGeometry(16_000, 1, 600)
        assertThat(c.overlapSamples).isEqualTo(30L * 16_000)
        assertThat(c.strideSamples).isEqualTo(30L * 16_000)
    }
}

class LevelMeterTest {
    private fun pcm(vararg samples: Int): ByteArray {
        val b = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            b[2 * i] = (s and 0xFF).toByte()
            b[2 * i + 1] = (s shr 8).toByte()
        }
        return b
    }

    @Test
    fun silenceIsZero() {
        assertThat(LevelMeter.levelOfPcm16(pcm(0, 0, 0, 0), 0, 8)).isEqualTo(0f)
    }

    @Test
    fun fullScaleIsOne() {
        assertThat(LevelMeter.levelOfPcm16(pcm(-32768, -32768), 0, 4)).isWithin(1e-4f).of(1f)
    }

    @Test
    fun minusThirtyDbIsHalf() {
        // 20*log10(x/32768) = -30 dB -> x ~= 1036
        assertThat(LevelMeter.levelFromRms(32768 * Math.pow(10.0, -30.0 / 20.0))).isWithin(1e-3f).of(0.5f)
    }

    @Test
    fun belowFloorClampsToZero() {
        assertThat(LevelMeter.levelOfPcm16(pcm(1, -1, 1, -1), 0, 8)).isEqualTo(0f)
    }

    @Test
    fun negativeSamplesAreSignExtended() {
        val loud = LevelMeter.levelOfPcm16(pcm(-16384, 16384), 0, 4)
        assertThat(loud).isWithin(1e-3f).of(((20 * Math.log10(0.5) + 60) / 60).toFloat())
    }
}
