package com.ideaforge.core.pipeline

import com.ideaforge.core.agents.ExecutedDeliverable
import com.ideaforge.core.agents.Plan
import com.ideaforge.core.transcription.TranscriptionOptions
import com.ideaforge.core.transcript.Transcript
import java.io.File

/**
 * Pipeline stages in execution order. BRAINSTORM and EVALUATE repeat per round (round >= 1);
 * the others use round 0. REVIEW is the optional "pause for review" gate after VERIFY.
 */
enum class Stage(val label: String) {
    TRANSCRIBE("Transcribe"),
    VERIFY("Verify"),
    REVIEW("Review"),
    BRAINSTORM("Brainstorm"),
    EVALUATE("Evaluate"),
    PLAN("Plan");

    /** Total order across stages and rounds, used to find everything downstream of an edit. */
    fun position(round: Int): Int = when (this) {
        TRANSCRIBE -> 0
        VERIFY -> 1
        REVIEW -> 2
        BRAINSTORM -> 10 + 2 * round
        EVALUATE -> 11 + 2 * round
        PLAN -> 1000
    }
}

enum class StageStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class StageRecord(
    val stage: Stage,
    val round: Int,
    val status: StageStatus,
    val outputJson: String? = null,
    /** The user's hand edit of [outputJson]; downstream stages read this when present. */
    val editedJson: String? = null,
    val error: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val note: String? = null,
    val updatedAt: Long = 0,
) {
    val effectiveJson: String? get() = editedJson ?: outputJson
}

/** Overall state shown as the session's status chip. */
enum class PipelineState {
    RECORDING,
    QUEUED,
    RUNNING,
    WAITING_NETWORK,
    AWAITING_REVIEW,
    AWAITING_APPROVAL,
    PAUSED_BUDGET,
    FAILED,
    COMPLETE,
}

enum class SessionMode { MEMO, MEETING }

data class SessionInfo(
    val id: Long,
    val mode: SessionMode,
    val isRecording: Boolean,
    val userNotes: String,
    val speakerNames: Map<String, String>,
)

data class ChunkInfo(
    val id: Long,
    val index: Int,
    val startOffsetMs: Long,
    val durationMs: Long,
    val transcript: Transcript?,
)

enum class PlanStatus { PENDING, APPROVED, REJECTED, EXECUTING, EXECUTED, FAILED }

data class NewPlan(
    val plan: Plan,
    val score: Double?,
    val effort: String,
    val rank: Int,
)

data class StoredPlan(
    val id: Long,
    val sessionId: Long,
    val plan: Plan,
    val editedPlan: Plan?,
    val score: Double?,
    val effort: String,
    val status: PlanStatus,
    val rejectReason: String?,
) {
    val effectivePlan: Plan get() = editedPlan ?: plan
}

/** User-tunable pipeline behaviour, read from Settings for every run. */
data class PipelineConfig(
    val verifyEnabled: Boolean = true,
    val brainstormEnabled: Boolean = true,
    val evaluateEnabled: Boolean = true,
    val planEnabled: Boolean = true,
    val pauseAfterVerify: Boolean = false,
    val rounds: Int = 2,
    val shortlistSize: Int = 5,
    val optionsPerIdea: Int = 10,
    val glossary: String = "",
    val transcription: TranscriptionOptions = TranscriptionOptions(),
) {
    init {
        require(rounds in 1..MAX_ROUNDS) { "rounds must be 1..$MAX_ROUNDS" }
    }

    companion object {
        const val MAX_ROUNDS = 4
    }
}

/**
 * Persistence the orchestrator needs. The app implements it over Room; tests use an in-memory map.
 * Raw and cleaned transcripts are insert-only: every insert is a new version and the latest wins,
 * so the original raw transcript is never overwritten.
 */
interface PipelineStore {
    suspend fun session(sessionId: Long): SessionInfo
    suspend fun chunks(sessionId: Long): List<ChunkInfo>
    suspend fun saveChunkTranscript(chunkId: Long, transcript: Transcript)
    suspend fun clearChunkTranscripts(sessionId: Long)

    suspend fun latestRawTranscript(sessionId: Long): Transcript?
    suspend fun insertRawTranscript(sessionId: Long, transcript: Transcript)
    suspend fun latestCleanedTranscript(sessionId: Long): Transcript?
    suspend fun insertCleanedTranscript(sessionId: Long, transcript: Transcript)

    suspend fun stages(sessionId: Long): List<StageRecord>
    suspend fun upsertStage(sessionId: Long, record: StageRecord)
    suspend fun deleteStage(sessionId: Long, stage: Stage, round: Int)

    /** Rebuilds the searchable idea rows for a round (scores and shortlist flags included). */
    suspend fun publishIdeas(sessionId: Long, round: Int, ideasJson: String, evaluationJson: String?, shortlistJson: String?)

    /** Replaces plans still awaiting a decision; approved, rejected and executed plans are kept. */
    suspend fun replacePendingPlans(sessionId: Long, plans: List<NewPlan>)
    suspend fun plans(sessionId: Long): List<StoredPlan>
    suspend fun plan(planId: Long): StoredPlan?
    suspend fun updatePlanStatus(planId: Long, status: PlanStatus, error: String? = null)
    suspend fun deliverables(planId: Long): List<ExecutedDeliverable>
    suspend fun saveDeliverable(planId: Long, sessionId: Long, deliverable: ExecutedDeliverable)

    suspend fun setPipelineState(sessionId: Long, state: PipelineState, message: String? = null)
}

/** Gives the orchestrator an uploadable file for a chunk (the app remuxes ADTS to M4A here). */
fun interface ChunkAudioSource {
    suspend fun fileFor(chunk: ChunkInfo): File
}

/** Side effects the app turns into notifications. All are fire-and-forget. */
interface PipelineEvents {
    suspend fun transcriptionFinished(sessionId: Long) {}
    suspend fun reviewNeeded(sessionId: Long) {}
    suspend fun plansReady(sessionId: Long, count: Int) {}
    suspend fun executionFinished(sessionId: Long, planId: Long, deliverables: Int) {}
    suspend fun budgetPaused(sessionId: Long, message: String) {}
    suspend fun failed(sessionId: Long, stage: String, message: String) {}

    object None : PipelineEvents
}

sealed interface PipelineOutcome {
    /** Recording is still running; finished chunks were transcribed. */
    data object WaitingForRecording : PipelineOutcome
    data object AwaitingReview : PipelineOutcome
    data class AwaitingApproval(val planCount: Int) : PipelineOutcome
    /** The pipeline ran to the end of the enabled stages without producing plans. */
    data object Complete : PipelineOutcome
    data class PausedForBudget(val message: String) : PipelineOutcome
    data class Failed(val stage: String, val message: String) : PipelineOutcome
}
