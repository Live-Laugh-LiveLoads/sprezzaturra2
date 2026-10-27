package com.ideaforge.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room schema. Stage outputs, transcripts and plans are stored as JSON produced by the core
 * module's serializers; the columns next to them exist for listing, sorting and search.
 * Every child table cascades on session delete.
 */

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** [com.ideaforge.core.pipeline.SessionMode] name. */
    val mode: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    val isRecording: Boolean = false,
    /** [com.ideaforge.core.pipeline.PipelineState] name. */
    val pipelineState: String,
    val stateMessage: String? = null,
    /** When the all-party consent reminder was confirmed (Meeting mode only). */
    val consentConfirmedAt: Long? = null,
    val userNotes: String = "",
    val audioDeleted: Boolean = false,
    /** Directory holding this session's audio chunks. */
    val audioDir: String,
)

@Entity(
    tableName = "chunks",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index(value = ["sessionId", "chunkIndex"], unique = true)],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val chunkIndex: Int,
    /** .aac (ADTS) while recording; replaced by the remuxed .m4a once finalized. */
    val path: String,
    val startOffsetMs: Long,
    val durationMs: Long = 0,
    val finalized: Boolean = false,
    /** Chunk-relative [com.ideaforge.core.transcript.Transcript] JSON once transcribed. */
    val transcriptJson: String? = null,
)

@Entity(
    tableName = "transcripts",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "kind", "version"], unique = true)],
)
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** "RAW" or "CLEANED". Rows are insert-only; the highest version is current. */
    val kind: String,
    val version: Int,
    val json: String,
    /** Plain text, for search. */
    val text: String,
    val createdAt: Long,
) {
    companion object {
        const val RAW = "RAW"
        const val CLEANED = "CLEANED"
    }
}

@Entity(
    tableName = "stage_runs",
    primaryKeys = ["sessionId", "stage", "round"],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class StageRunEntity(
    val sessionId: Long,
    /** [com.ideaforge.core.pipeline.Stage] name. */
    val stage: String,
    val round: Int,
    /** [com.ideaforge.core.pipeline.StageStatus] name. */
    val status: String,
    val outputJson: String? = null,
    val editedJson: String? = null,
    val error: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val note: String? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "ideas",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "round"])],
)
data class IdeaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val round: Int,
    val ideaId: String,
    val coreIdeaId: String,
    val coreIdeaTitle: String,
    val title: String,
    val description: String,
    val sourceTimestampMs: Long?,
    val effort: String,
    val style: String,
    val overall: Double?,
    val rationale: String?,
    /** 1-based rank when shortlisted, else null. */
    val shortlistRank: Int?,
)

@Entity(
    tableName = "plans",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("status")],
)
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val ideaId: String,
    val title: String,
    val summary: String,
    val score: Double?,
    val effort: String,
    val rank: Int,
    /** [com.ideaforge.core.agents.Plan] JSON from the Planner. */
    val planJson: String,
    /** The user's edit, if they used Edit-then-approve. */
    val editedPlanJson: String? = null,
    /** [com.ideaforge.core.pipeline.PlanStatus] name. */
    val status: String,
    val rejectReason: String? = null,
    val statusMessage: String? = null,
    val createdAt: Long,
    val decidedAt: Long? = null,
)

@Entity(
    tableName = "deliverables",
    foreignKeys = [ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId"), Index("sessionId")],
)
data class DeliverableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val sessionId: Long,
    val deliverableId: String,
    val title: String,
    val type: String,
    val fileName: String,
    /** Absolute path of the Markdown (or code) file under filesDir/deliverables. */
    val path: String,
    val complete: Boolean,
    /** JSON array of strings: what the user still has to do. */
    val humanActionsJson: String,
    val createdAt: Long,
)

@Entity(tableName = "usage", indices = [Index("atEpochMs"), Index("sessionId")])
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long?,
    val stage: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val audioMs: Long,
    val costUsd: Double,
    val atEpochMs: Long,
)

@Entity(
    tableName = "speakers",
    primaryKeys = ["sessionId", "label"],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class SpeakerEntity(
    val sessionId: Long,
    /** Label from the transcript, e.g. "Speaker 1". */
    val label: String,
    val displayName: String,
)

/** Timeline of notable things during a session: consent, pauses, interruptions, recovery. */
@Entity(
    tableName = "session_events",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val atEpochMs: Long,
    /** Short machine-readable type, see [SessionEventTypes]. */
    val type: String,
    val message: String,
)

object SessionEventTypes {
    const val CONSENT_CONFIRMED = "consent_confirmed"
    const val STARTED = "started"
    const val PAUSED = "paused"
    const val RESUMED = "resumed"
    const val STOPPED = "stopped"
    const val WARNING = "warning"
    const val RECOVERED = "recovered"
}
