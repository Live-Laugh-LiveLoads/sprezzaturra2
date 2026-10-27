package com.ideaforge.data

import com.ideaforge.core.agents.Plan
import com.ideaforge.core.json.AppJson
import com.ideaforge.core.pipeline.PipelineOrchestrator
import com.ideaforge.core.pipeline.PipelineState
import com.ideaforge.core.pipeline.PlanStatus
import com.ideaforge.core.pipeline.SessionMode
import com.ideaforge.core.pipeline.Stage
import com.ideaforge.core.transcript.Transcript
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.data.db.SessionEntity
import com.ideaforge.data.db.SessionEventEntity
import com.ideaforge.data.db.SessionEventTypes
import com.ideaforge.data.db.SpeakerEntity
import com.ideaforge.settings.SettingsRepository
import com.ideaforge.settings.TranscriptionEngine
import com.ideaforge.work.WorkScheduler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Every user action that changes a session, in one place, so ViewModels stay thin and the
 * pipeline is always re-queued consistently after a change.
 */
@Singleton
class SessionActions @Inject constructor(
    private val db: AppDatabase,
    private val orchestrator: PipelineOrchestrator,
    private val scheduler: WorkScheduler,
    private val settings: SettingsRepository,
    @Named("sessionsDir") private val sessionsDir: File,
) {

    /** Creates a session row and its audio directory. [consentAt] is required for meetings. */
    suspend fun createSession(mode: SessionMode, consentAt: Long?): SessionEntity {
        require(mode != SessionMode.MEETING || consentAt != null) { "Meeting mode requires the consent confirmation" }
        val now = System.currentTimeMillis()
        val title = (if (mode == SessionMode.MEETING) "Meeting " else "Memo ") +
            SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(now))
        val placeholder = SessionEntity(
            title = title,
            mode = mode.name,
            createdAt = now,
            isRecording = true,
            pipelineState = PipelineState.RECORDING.name,
            consentConfirmedAt = consentAt,
            audioDir = "",
        )
        val id = db.sessions().insert(placeholder)
        val dir = File(sessionsDir, "session-$id").apply { mkdirs() }
        // audioDir depends on the generated id, so it is written in a second step.
        db.sessions().setAudioDir(id, dir.absolutePath)
        if (consentAt != null) {
            db.events().insert(SessionEventEntity(sessionId = id, atEpochMs = consentAt, type = SessionEventTypes.CONSENT_CONFIRMED,
                message = "Confirmed: everyone present has agreed to be recorded"))
        }
        return db.sessions().get(id)!!
    }

    suspend fun logEvent(sessionId: Long, type: String, message: String) {
        db.events().insert(SessionEventEntity(sessionId = sessionId, atEpochMs = System.currentTimeMillis(), type = type, message = message))
    }

    /** Queues the pipeline for a session (also used to resume after a pause or failure). */
    suspend fun runPipeline(sessionId: Long) {
        val onDevice = settings.current.transcription.engine == TranscriptionEngine.ON_DEVICE
        val recording = db.sessions().get(sessionId)?.isRecording == true
        // Transcribing on-device during a recording needs no network; everything else does.
        scheduler.enqueuePipeline(sessionId, requireNetwork = !(onDevice && recording))
        if (!recording) db.sessions().setState(sessionId, PipelineState.QUEUED.name, "Queued")
    }

    /**
     * Continues a session waiting on the user: passes the review gate if verification is done,
     * otherwise (auto-start was off) simply starts the pipeline.
     */
    suspend fun continueAfterReview(sessionId: Long) {
        val verified = db.stages().forSession(sessionId).any { it.stage == Stage.VERIFY.name && it.status in setOf("DONE", "SKIPPED") }
        if (verified) orchestrator.approveReview(sessionId)
        runPipeline(sessionId)
    }

    suspend fun rerunStage(sessionId: Long, stage: Stage, round: Int) {
        orchestrator.resetStage(sessionId, stage, round)
        runPipeline(sessionId)
    }

    /** Returns validation problems; empty means saved and downstream stages were cleared. */
    suspend fun editStageOutput(sessionId: Long, stage: Stage, round: Int, json: String): List<String> =
        orchestrator.editStageOutput(sessionId, stage, round, json)

    suspend fun editCleanedTranscript(sessionId: Long, transcript: Transcript) =
        orchestrator.editCleanedTranscript(sessionId, transcript)

    suspend fun renameSession(sessionId: Long, title: String) = db.sessions().rename(sessionId, title.trim().ifEmpty { "Untitled" })

    suspend fun setNotes(sessionId: Long, notes: String) = db.sessions().setNotes(sessionId, notes)

    suspend fun renameSpeaker(sessionId: Long, label: String, name: String) =
        db.speakers().upsert(SpeakerEntity(sessionId, label, name.trim().ifEmpty { label }))

    suspend fun deleteSession(sessionId: Long) {
        scheduler.cancelSession(sessionId)
        val s = db.sessions().get(sessionId) ?: return
        db.deliverables().forSession(sessionId).forEach { File(it.path).delete() }
        if (s.audioDir.isNotEmpty()) File(s.audioDir).deleteRecursively()
        db.sessions().delete(sessionId)
    }

    // ---- Plan approval ------------------------------------------------------------------------

    suspend fun approve(planIds: Collection<Long>) {
        val now = System.currentTimeMillis()
        planIds.forEach { id ->
            db.plans().decide(id, PlanStatus.APPROVED.name, null, now)
            scheduler.enqueueExecution(id)
        }
    }

    /** "Approve all above score X": approves pending plans of [sessionId] scoring >= [threshold]. */
    suspend fun approveAbove(sessionId: Long, threshold: Double): Int {
        val ids = db.plans().forSession(sessionId)
            .filter { it.status == PlanStatus.PENDING.name && (it.score ?: 0.0) >= threshold }
            .map { it.id }
        approve(ids)
        return ids.size
    }

    suspend fun reject(planId: Long, reason: String) =
        db.plans().decide(planId, PlanStatus.REJECTED.name, reason.trim().ifEmpty { "No reason given" }, System.currentTimeMillis())

    /** Restores an archived (rejected) plan to pending. */
    suspend fun unreject(planId: Long) = db.plans().decide(planId, PlanStatus.PENDING.name, null, System.currentTimeMillis())

    suspend fun editAndApprove(planId: Long, edited: Plan) {
        db.plans().saveEdit(planId, AppJson.encodeToString(Plan.serializer(), edited), edited.title, edited.summary)
        approve(listOf(planId))
    }

    /** Re-queues a plan whose execution failed. */
    suspend fun retryExecution(planId: Long) {
        db.plans().setStatus(planId, PlanStatus.APPROVED.name, null)
        scheduler.enqueueExecution(planId)
    }
}
