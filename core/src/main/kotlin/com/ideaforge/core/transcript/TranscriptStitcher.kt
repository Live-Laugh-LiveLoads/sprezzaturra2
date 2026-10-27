package com.ideaforge.core.transcript

/**
 * Joins per-chunk transcripts into one session transcript.
 *
 * Meeting chunks overlap by a few seconds, so the same words appear at the end of chunk N and the
 * start of chunk N+1. For each overlap we pick a cut point in the middle of it:
 *  - from the earlier chunk we keep segments that *start* before the cut,
 *  - from the later chunk we keep segments that *end* after the cut.
 * That errs toward keeping too much rather than dropping speech. When word timestamps exist, the
 * straddling segments are trimmed to the exact words on their side of the cut. Any text that is
 * still duplicated at the seam is removed by matching the longest run of words that ends the
 * earlier side and starts the later side.
 */
object TranscriptStitcher {

    /** Longest seam we try to de-duplicate, in words. 5 s of speech is ~15 words; 40 is generous. */
    private const val MAX_SEAM_WORDS = 40

    /** Below this we treat a match as coincidence ("the", "and so") rather than a duplicate. */
    private const val MIN_SEAM_WORDS = 2

    fun stitch(chunks: List<ChunkTranscript>): Transcript {
        val sorted = chunks.sortedBy { it.index }
        val out = mutableListOf<Segment>()
        var previousEnd: Long? = null
        var language: String? = null

        for (chunk in sorted) {
            language = language ?: chunk.transcript.language
            val shifted = chunk.transcript.shifted(chunk.startOffsetMs).segments.filter { it.text.isNotBlank() }
            val overlapStart = chunk.startOffsetMs
            val overlapEnd = previousEnd

            if (overlapEnd == null || overlapEnd <= overlapStart || out.isEmpty()) {
                out += shifted
            } else {
                val cut = (overlapStart + overlapEnd) / 2
                trimTail(out, cut)
                val head = shifted.mapNotNull { trimHead(it, cut) }
                out += dedupeSeam(out, head)
            }
            previousEnd = chunk.startOffsetMs + chunk.durationMs
        }
        return Transcript(out.filter { it.text.isNotBlank() }, language)
    }

    /** Drops earlier-chunk segments at or after the cut, trimming a straddler to its words. */
    private fun trimTail(out: MutableList<Segment>, cut: Long) {
        while (out.isNotEmpty() && out.last().startMs >= cut) out.removeAt(out.lastIndex)
        // Only segments from the last chunk can straddle the cut, and they sit at the end.
        for (i in out.indices.reversed()) {
            val s = out[i]
            if (s.endMs <= cut) break
            if (s.words.isNotEmpty()) {
                val kept = s.words.filter { it.startMs < cut }
                out[i] = s.copy(words = kept, text = joinWords(kept), endMs = kept.lastOrNull()?.endMs ?: s.startMs)
            }
        }
    }

    /** Returns the later-chunk segment trimmed to the part after the cut, or null if none is left. */
    private fun trimHead(s: Segment, cut: Long): Segment? {
        if (s.endMs <= cut) return null
        if (s.startMs >= cut || s.words.isEmpty()) return s
        val kept = s.words.filter { it.startMs >= cut }
        if (kept.isEmpty()) return null
        return s.copy(words = kept, text = joinWords(kept), startMs = kept.first().startMs)
    }

    /** Removes words at the start of [head] that repeat the words at the end of [out]. */
    private fun dedupeSeam(out: List<Segment>, head: List<Segment>): List<Segment> {
        if (out.isEmpty() || head.isEmpty()) return head
        val tailWords = out.takeLast(4).flatMap { tokens(it.text) }.takeLast(MAX_SEAM_WORDS)
        val headWords = head.take(4).flatMap { tokens(it.text) }.take(MAX_SEAM_WORDS)
        val tailNorm = tailWords.map(::normalize)
        val headNorm = headWords.map(::normalize)

        var overlap = 0
        for (k in minOf(tailNorm.size, headNorm.size) downTo MIN_SEAM_WORDS) {
            if (tailNorm.subList(tailNorm.size - k, tailNorm.size) == headNorm.subList(0, k)) {
                overlap = k
                break
            }
        }
        if (overlap == 0) return head
        return dropLeadingWords(head, overlap)
    }

    private fun dropLeadingWords(segments: List<Segment>, count: Int): List<Segment> {
        var remaining = count
        val result = mutableListOf<Segment>()
        for (s in segments) {
            if (remaining == 0) {
                result += s
                continue
            }
            val words = tokens(s.text)
            if (words.size <= remaining) {
                remaining -= words.size
                continue
            }
            val keptText = words.drop(remaining).joinToString(" ")
            val keptTimedWords = if (s.words.size == words.size) s.words.drop(remaining) else s.words
            result += s.copy(
                text = keptText,
                words = keptTimedWords,
                startMs = keptTimedWords.firstOrNull()?.startMs ?: s.startMs,
            )
            remaining = 0
        }
        return result
    }

    private fun tokens(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun normalize(word: String): String = word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    private fun joinWords(words: List<Word>): String = words.joinToString(" ") { it.text.trim() }.trim()
}
