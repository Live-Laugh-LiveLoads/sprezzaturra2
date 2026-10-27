package com.ideaforge.data

import androidx.room.withTransaction
import com.ideaforge.core.agents.BrainstormOutput
import com.ideaforge.core.agents.EvaluationOutput
import com.ideaforge.core.agents.ExecutedDeliverable
import com.ideaforge.core.agents.Plan
import com.ideaforge.core.agents.Shortlist
import com.ideaforge.core.json.AppJson
import com.ideaforge.core.llm.UsageLedger
import com.ideaforge.core.llm.UsageRecord
import com.ideaforge.core.pipeline.ChunkInfo
import com.ideaforge.core.pipeline.NewPlan
import com.ideaforge.core.pipeline.PipelineState
import com.ideaforge.core.pipeline.PipelineStore
import com.ideaforge.core.pipeline.PlanStatus
import com.ideaforge.core.pipeline.SessionInfo
import com.ideaforge.core.pipeline.SessionMode
import com.ideaforge.core.pipeline.Stage
import com.ideaforge.core.pipeline.StageRecord
import com.ideaforge.core.pipeline.StageStatus
import com.ideaforge.core.pipeline.StoredPlan
import com.ideaforge.core.transcript.Transcript
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.data.db.DeliverableEntity
import com.ideaforge.data.db.IdeaEntity
import com.ideaforge.data.db.PlanEntity
import com.ideaforge.data.db.StageRunEntity
import com.ideaforge.data.db.TranscriptEntity
import com.ideaforge.data.db.UsageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** [PipelineStore] over Room. Deliverable bodies are written as files, metadata as rows. */
@Singleton
class RoomPipelineStore @Inject constructor(
    private val db: AppDatabase,
    @Named("deliverablesDir") private val deliverablesDir: File,
) : PipelineStore {

    override suspend fun session(sessionId: Long): SessionInfo {
        val s = db.sessions().get(sessionId) ?: error("Session $sessionId not found")
        return SessionInfo(
            id = s.id,
            mode = SessionMode.valueOf(s.mode),
            isRecording = s.isRecording,
            userNotes = s.userNotes,
            speakerNames = db.speakers().forSession(sessionId).associate { it.label to it.displayName },
        )
    }

    override suspend fun chunks(sessionId: Long): List<ChunkInfo> = db.chunks().forSession(sessionId).map {
        ChunkInfo(
            id = it.id,
            index = it.chunkIndex,
            startOffsetMs = it.startOffsetMs,
            durationMs = it.durationMs,
            transcript = it.transcriptJson?.let { j -> AppJson.decodeFromString(Transcript.serializer(), j) },
        )
    }

    override suspend fun saveChunkTranscript(chunkId: Long, transcript: Transcript) =
        db.chunks().setTranscript(chunkId, AppJson.encodeToString(Transcript.serializer(), transcript))

    override suspend fun clearChunkTranscripts(sessionId: Long) = db.chunks().clearTranscripts(sessionId)

    override suspend fun latestRawTranscript(sessionId: Long) = latest(sessionId, TranscriptEntity.RAW)
    override suspend fun insertRawTranscript(sessionId: Long, transcript: Transcript) = insert(sessionId, TranscriptEntity.RAW, transcript)
    override suspend fun latestCleanedTranscript(sessionId: Long) = latest(sessionId, TranscriptEntity.CLEANED)
    override suspend fun insertCleanedTranscript(sessionId: Long, transcript: Transcript) = insert(sessionId, TranscriptEntity.CLEANED, transcript)

    private suspend fun latest(sessionId: Long, kind: String): Transcript? =
        db.transcripts().latest(sessionId, kind)?.let { AppJson.decodeFromString(Transcript.serializer(), it.json) }

    private suspend fun insert(sessionId: Long, kind: String, transcript: Transcript) {
        db.withTransaction {
            val version = db.transcripts().maxVersion(sessionId, kind) + 1
            db.transcripts().insert(
                TranscriptEntity(
                    sessionId = sessionId,
                    kind = kind,
                    version = version,
                    json = AppJson.encodeToString(Transcript.serializer(), transcript),
                    text = transcript.text,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun stages(sessionId: Long): List<StageRecord> = db.stages().forSession(sessionId).map { it.toRecord() }

    override suspend fun upsertStage(sessionId: Long, record: StageRecord) = db.stages().upsert(
        StageRunEntity(
            sessionId = sessionId,
            stage = record.stage.name,
            round = record.round,
            status = record.status.name,
            outputJson = record.outputJson,
            editedJson = record.editedJson,
            error = record.error,
            model = record.model,
            promptVersion = record.promptVersion,
            note = record.note,
            updatedAt = record.updatedAt,
        ),
    )

    override suspend fun deleteStage(sessionId: Long, stage: Stage, round: Int) {
        db.stages().delete(sessionId, stage.name, round)
        if (stage == Stage.BRAINSTORM || stage == Stage.EVALUATE) db.ideas().deleteRound(sessionId, round)
    }

    override suspend fun publishIdeas(sessionId: Long, round: Int, ideasJson: String, evaluationJson: String?, shortlistJson: String?) {
        val ideas = AppJson.decodeFromString(BrainstormOutput.serializer(), ideasJson)
        val evals = evaluationJson?.let { AppJson.decodeFromString(EvaluationOutput.serializer(), it) }
            ?.evaluations?.associateBy { it.ideaId }.orEmpty()
        val ranks = shortlistJson?.let { AppJson.decodeFromString(Shortlist.serializer(), it) }
            ?.items?.associate { it.idea.id to it.rank }.orEmpty()
        val coreTitles = ideas.coreIdeas.associate { it.id to it.title }
        db.ideas().replaceRound(sessionId, round, ideas.ideas.map { i ->
            IdeaEntity(
                sessionId = sessionId,
                round = round,
                ideaId = i.id,
                coreIdeaId = i.coreIdeaId,
                coreIdeaTitle = coreTitles[i.coreIdeaId] ?: i.coreIdeaId,
                title = i.title,
                description = i.description,
                sourceTimestampMs = i.sourceTimestampMs,
                effort = i.effort,
                style = i.style,
                overall = evals[i.id]?.overall,
                rationale = evals[i.id]?.rationale,
                shortlistRank = ranks[i.id],
            )
        })
    }

    override suspend fun replacePendingPlans(sessionId: Long, plans: List<NewPlan>) {
        val now = System.currentTimeMillis()
        db.plans().replacePending(sessionId, plans.map { p ->
            PlanEntity(
                sessionId = sessionId,
                ideaId = p.plan.ideaId,
                title = p.plan.title,
                summary = p.plan.summary,
                score = p.score,
                effort = p.effort,
                rank = p.rank,
                planJson = AppJson.encodeToString(Plan.serializer(), p.plan),
                status = PlanStatus.PENDING.name,
                createdAt = now,
            )
        })
    }

    override suspend fun plans(sessionId: Long): List<StoredPlan> = db.plans().forSession(sessionId).map { it.toStored() }
    override suspend fun plan(planId: Long): StoredPlan? = db.plans().get(planId)?.toStored()

    override suspend fun updatePlanStatus(planId: Long, status: PlanStatus, error: String?) =
        db.plans().setStatus(planId, status.name, error)

    override suspend fun deliverables(planId: Long): List<ExecutedDeliverable> = db.deliverables().forPlan(planId).map { d ->
        ExecutedDeliverable(
            deliverableId = d.deliverableId,
            title = d.title,
            type = d.type,
            filename = d.fileName,
            contentMarkdown = File(d.path).takeIf { it.exists() }?.readText().orEmpty(),
            complete = d.complete,
            humanActions = AppJson.decodeFromString(ListSerializer(String.serializer()), d.humanActionsJson),
        )
    }

    override suspend fun saveDeliverable(planId: Long, sessionId: Long, deliverable: ExecutedDeliverable) {
        val dir = File(deliverablesDir, "session-$sessionId/plan-$planId").apply { mkdirs() }
        val file = File(dir, "${deliverable.deliverableId}-${deliverable.filename}")
        val body = buildString {
            append(deliverable.contentMarkdown.trimEnd())
            if (deliverable.humanActions.isNotEmpty()) {
                append("\n\n---\n\n## What you need to do\n\n")
                deliverable.humanActions.forEach { append("- [ ] ").append(it).append('\n') }
            }
        }
        file.writeText(body)
        db.deliverables().insert(
            DeliverableEntity(
                planId = planId,
                sessionId = sessionId,
                deliverableId = deliverable.deliverableId,
                title = deliverable.title,
                type = deliverable.type,
                fileName = deliverable.filename,
                path = file.absolutePath,
                complete = deliverable.complete,
                humanActionsJson = AppJson.encodeToString(ListSerializer(String.serializer()), deliverable.humanActions),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun setPipelineState(sessionId: Long, state: PipelineState, message: String?) =
        db.sessions().setState(sessionId, state.name, message)

    private fun StageRunEntity.toRecord() = StageRecord(
        stage = Stage.valueOf(stage),
        round = round,
        status = StageStatus.valueOf(status),
        outputJson = outputJson,
        editedJson = editedJson,
        error = error,
        model = model,
        promptVersion = promptVersion,
        note = note,
        updatedAt = updatedAt,
    )

    private fun PlanEntity.toStored() = StoredPlan(
        id = id,
        sessionId = sessionId,
        plan = AppJson.decodeFromString(Plan.serializer(), planJson),
        editedPlan = editedPlanJson?.let { AppJson.decodeFromString(Plan.serializer(), it) },
        score = score,
        effort = effort,
        status = PlanStatus.valueOf(status),
        rejectReason = rejectReason,
    )
}

/** Usage rows in Room; "this month" is the current calendar month in the phone's time zone. */
@Singleton
class RoomUsageLedger @Inject constructor(private val db: AppDatabase) : UsageLedger {

    override suspend fun record(usage: UsageRecord) = db.usage().insert(
        UsageEntity(
            sessionId = usage.sessionId,
            stage = usage.stage,
            model = usage.model,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            audioMs = usage.audioMs,
            costUsd = usage.costUsd,
            atEpochMs = usage.atEpochMs,
        ),
    )

    override suspend fun monthToDateUsd(nowEpochMs: Long): Double = db.usage().costSince(monthStart(nowEpochMs))

    companion object {
        fun monthStart(nowEpochMs: Long): Long {
            val zone = ZoneId.systemDefault()
            val now = java.time.Instant.ofEpochMilli(nowEpochMs).atZone(zone)
            return ZonedDateTime.of(now.year, now.monthValue, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        }
    }
}
