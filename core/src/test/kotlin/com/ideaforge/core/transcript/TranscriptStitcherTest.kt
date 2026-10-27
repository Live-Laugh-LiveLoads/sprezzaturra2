package com.ideaforge.core.transcript

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranscriptStitcherTest {

    private fun words(startMs: Long, text: String, stepMs: Long = 400): List<Word> =
        text.split(" ").mapIndexed { i, w -> Word(w, startMs + i * stepMs, startMs + i * stepMs + stepMs - 50) }

    private fun seg(start: Long, end: Long, text: String, timed: Boolean = false) =
        Segment(start, end, text, words = if (timed) words(start, text) else emptyList())

    @Test fun `single chunk passes through with offset applied`() {
        val out = TranscriptStitcher.stitch(listOf(ChunkTranscript(0, 1_000, 10_000, Transcript(listOf(seg(0, 2_000, "hello there"))))))
        assertThat(out.segments.single().startMs).isEqualTo(1_000)
        assertThat(out.text).isEqualTo("hello there")
    }

    @Test fun `overlapping text without word timings is de-duplicated at the seam`() {
        // Chunk 0 covers 0-300 s, chunk 1 starts at 295 s: the last sentence is in both.
        val a = Transcript(listOf(seg(0, 290_000, "we should open a shop"), seg(293_000, 299_500, "and sell the prints online")))
        val b = Transcript(listOf(seg(0, 4_500, "sell the prints online"), seg(4_500, 9_000, "starting next month")))
        val out = TranscriptStitcher.stitch(listOf(ChunkTranscript(0, 0, 300_000, a), ChunkTranscript(1, 295_000, 300_000, b)))
        assertThat(out.text).isEqualTo("we should open a shop and sell the prints online starting next month")
    }

    @Test fun `word timings trim straddling segments exactly at the cut`() {
        // Overlap is 295-300 s; cut at 297.5 s.
        val a = Transcript(listOf(seg(294_000, 299_900, "one two three four five six seven eight nine ten", timed = true)))
        // In chunk 1, 0 ms is session time 295 s; its words begin at 295.2 s, matching chunk 0.
        val b = Transcript(listOf(seg(200, 6_000, "four five six seven eight nine ten eleven twelve", timed = true)))
        val out = TranscriptStitcher.stitch(listOf(ChunkTranscript(0, 0, 300_000, a), ChunkTranscript(1, 295_000, 300_000, b)))
        assertThat(out.text).isEqualTo("one two three four five six seven eight nine ten eleven twelve")
        assertThat(out.segments.zipWithNext().all { (x, y) -> x.startMs <= y.startMs }).isTrue()
    }

    @Test fun `non-overlapping text is not removed by coincidental single-word matches`() {
        val a = Transcript(listOf(seg(290_000, 299_000, "and then we left")))
        val b = Transcript(listOf(seg(5_000, 8_000, "left turn ahead")))
        val out = TranscriptStitcher.stitch(listOf(ChunkTranscript(0, 0, 300_000, a), ChunkTranscript(1, 295_000, 300_000, b)))
        assertThat(out.text).isEqualTo("and then we left left turn ahead")
    }

    @Test fun `chunks are ordered by index and empty chunks are tolerated`() {
        val c0 = ChunkTranscript(0, 0, 300_000, Transcript(listOf(seg(1_000, 3_000, "first"))))
        val c1 = ChunkTranscript(1, 295_000, 300_000, Transcript.EMPTY)
        val c2 = ChunkTranscript(2, 590_000, 100_000, Transcript(listOf(seg(10_000, 12_000, "third"))))
        val out = TranscriptStitcher.stitch(listOf(c2, c0, c1))
        assertThat(out.segments.map { it.text }).containsExactly("first", "third").inOrder()
        assertThat(out.segments.last().startMs).isEqualTo(600_000)
    }

    @Test fun `timestamps render as mm-ss and h-mm-ss`() {
        assertThat(formatTimestamp(65_000)).isEqualTo("01:05")
        assertThat(formatTimestamp(3_725_000)).isEqualTo("1:02:05")
    }
}
