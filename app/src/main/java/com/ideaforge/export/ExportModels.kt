package com.ideaforge.export

import com.ideaforge.core.agents.BrainstormOutput
import com.ideaforge.core.agents.EvaluationOutput
import com.ideaforge.core.agents.FlaggedPassage
import com.ideaforge.core.agents.Plan
import com.ideaforge.core.agents.TranscriptChange
import com.ideaforge.core.transcript.Transcript
import kotlinx.serialization.Serializable
import java.time.ZoneId

/*
 * Plain, Android-free input for MarkdownBuilder. SessionExporter fills these from Room rows and the
 * core DTOs; keeping them separate lets the Markdown layout be unit tested on the JVM.
 */

/** Everything the session Markdown report is built from. */
data class ExportData(
    val title: String,
    /** "MEMO" or "MEETING". */
    val mode: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val pipelineState: String,
    /** When all-party consent was confirmed (meetings only). */
    val consentConfirmedAtMs: Long?,
    val notes: String,
    /** Transcript label to display name, e.g. "Speaker 1" to "Alice". */
    val speakers: Map<String, String>,
    /** Cleaned transcript when there is one, otherwise the raw one. */
    val transcript: Transcript?,
    val transcriptIsRaw: Boolean,
    val flags: List<FlaggedPassage>,
    val changes: List<TranscriptChange>,
    val shortlist: List<ShortlistRow>,
    val rounds: List<RoundData>,
    val plans: List<PlanData>,
    val usage: List<UsageRow>,
    val zone: ZoneId = ZoneId.systemDefault(),
)

data class ShortlistRow(
    val rank: Int,
    val ideaId: String,
    val title: String,
    val overall: Double?,
    val effort: String,
    val rationale: String?,
)

/** One brainstorm and evaluate round of the debate history. Either half may be missing. */
data class RoundData(
    val round: Int,
    val brainstorm: BrainstormOutput?,
    val evaluation: EvaluationOutput?,
)

data class DeliverableData(
    val title: String,
    val type: String,
    val fileName: String,
    val complete: Boolean,
    val humanActions: List<String>,
    /** File content; null when the file is gone from disk. */
    val content: String?,
)

data class PlanData(
    val rank: Int,
    val status: String,
    val rejectReason: String?,
    val score: Double?,
    val effort: String,
    /** True when the user edited the Planner's plan before approving. */
    val edited: Boolean,
    val plan: Plan,
    val deliverables: List<DeliverableData>,
)

data class UsageRow(
    val stage: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val audioMs: Long,
    val costUsd: Double,
)

/** usage.json entry in the ZIP export. */
@Serializable
data class UsageExport(
    val sessionId: Long?,
    val stage: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val audioMs: Long,
    val costUsd: Double,
    val atEpochMs: Long,
)

/** events.json entry in the ZIP export. */
@Serializable
data class EventExport(
    val atEpochMs: Long,
    val type: String,
    val message: String,
)
