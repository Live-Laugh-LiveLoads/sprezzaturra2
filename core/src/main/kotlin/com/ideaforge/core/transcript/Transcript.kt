package com.ideaforge.core.transcript

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A single timed word, when the provider returns word-level timestamps. Times are milliseconds. */
@Serializable
data class Word(
    val text: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
)

/** A timed stretch of speech. Times are milliseconds from the start of the session (or chunk). */
@Serializable
data class Segment(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
    val speaker: String? = null,
    val words: List<Word> = emptyList(),
    /** 0..1 where the provider reports it (Whisper's exp(avg_logprob)); null when unknown. */
    val confidence: Double? = null,
)

@Serializable
data class Transcript(
    val segments: List<Segment>,
    val language: String? = null,
) {
    val text: String get() = segments.joinToString(" ") { it.text.trim() }.trim()

    fun shifted(offsetMs: Long): Transcript = copy(
        segments = segments.map { s ->
            s.copy(
                startMs = s.startMs + offsetMs,
                endMs = s.endMs + offsetMs,
                words = s.words.map { it.copy(startMs = it.startMs + offsetMs, endMs = it.endMs + offsetMs) },
            )
        },
    )

    /** Transcript rendered with `[mm:ss] Speaker: text` lines, which is what agents read. */
    fun toTimestampedText(speakerNames: Map<String, String> = emptyMap()): String =
        segments.joinToString("\n") { s ->
            val who = s.speaker?.let { (speakerNames[it] ?: it) + ": " } ?: ""
            "[${formatTimestamp(s.startMs)}] $who${s.text.trim()}"
        }

    companion object {
        val EMPTY = Transcript(emptyList())
    }
}

/** A chunk's transcript with chunk-relative times, plus where the chunk sits in the session. */
data class ChunkTranscript(
    val index: Int,
    val startOffsetMs: Long,
    val durationMs: Long,
    val transcript: Transcript,
)

fun formatTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
