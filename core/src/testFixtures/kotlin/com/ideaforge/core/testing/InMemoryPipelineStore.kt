package com.ideaforge.core.testing

import com.ideaforge.core.agents.ExecutedDeliverable
import com.ideaforge.core.pipeline.ChunkInfo
import com.ideaforge.core.pipeline.NewPlan
import com.ideaforge.core.pipeline.PipelineState
import com.ideaforge.core.pipeline.PipelineStore
import com.ideaforge.core.pipeline.PlanStatus
import com.ideaforge.core.pipeline.SessionInfo
import com.ideaforge.core.pipeline.SessionMode
import com.ideaforge.core.pipeline.Stage
import com.ideaforge.core.pipeline.StageRecord
import com.ideaforge.core.pipeline.StoredPlan
import com.ideaforge.core.transcript.Transcript

/** A [PipelineStore] backed by plain collections, for orchestrator tests. Not thread-safe. */
class InMemoryPipelineStore : PipelineStore {
    val sessions = mutableMapOf<Long, SessionInfo>()
    val chunks = mutableMapOf<Long, MutableList<ChunkInfo>>()
    val rawTranscripts = mutableMapOf<Long, MutableList<Transcript>>()
    val cleanedTranscripts = mutableMapOf<Long, MutableList<Transcript>>()
    val stageRecords = mutableMapOf<Long, MutableList<StageRecord>>()
    val states = mutableMapOf<Long, MutableList<Pair<PipelineState, String?>>>()
    val ideas = mutableMapOf<Pair<Long, Int>, Triple<String, String?, String?>>()
    val storedPlans = mutableListOf<StoredPlan>()
    val savedDeliverables = mutableMapOf<Long, MutableList<ExecutedDeliverable>>()
    private var nextPlanId = 1L

    fun addSession(id: Long, chunkCount: Int, recording: Boolean = false, chunkMs: Long = 300_000, overlapMs: Long = 5_000) {
        sessions[id] = SessionInfo(id, SessionMode.MEETING, recording, "", emptyMap())
        chunks[id] = (0 until chunkCount).map { i ->
            ChunkInfo(id * 100 + i, i, i * (chunkMs - overlapMs), chunkMs, null)
        }.toMutableList()
    }

    fun stopRecording(id: Long) {
        sessions[id] = sessions.getValue(id).copy(isRecording = false)
    }

    fun lastState(id: Long): PipelineState? = states[id]?.lastOrNull()?.first

    override suspend fun session(sessionId: Long) = sessions.getValue(sessionId)
    override suspend fun chunks(sessionId: Long): List<ChunkInfo> = chunks[sessionId].orEmpty().toList()

    override suspend fun saveChunkTranscript(chunkId: Long, transcript: Transcript) {
        chunks.values.forEach { list ->
            val i = list.indexOfFirst { it.id == chunkId }
            if (i >= 0) list[i] = list[i].copy(transcript = transcript)
        }
    }

    override suspend fun clearChunkTranscripts(sessionId: Long) {
        chunks[sessionId]?.replaceAll { it.copy(transcript = null) }
    }

    override suspend fun latestRawTranscript(sessionId: Long) = rawTranscripts[sessionId]?.lastOrNull()
    override suspend fun insertRawTranscript(sessionId: Long, transcript: Transcript) {
        rawTranscripts.getOrPut(sessionId) { mutableListOf() } += transcript
    }

    override suspend fun latestCleanedTranscript(sessionId: Long) = cleanedTranscripts[sessionId]?.lastOrNull()
    override suspend fun insertCleanedTranscript(sessionId: Long, transcript: Transcript) {
        cleanedTranscripts.getOrPut(sessionId) { mutableListOf() } += transcript
    }

    override suspend fun stages(sessionId: Long): List<StageRecord> = stageRecords[sessionId].orEmpty().toList()

    override suspend fun upsertStage(sessionId: Long, record: StageRecord) {
        val list = stageRecords.getOrPut(sessionId) { mutableListOf() }
        list.removeAll { it.stage == record.stage && it.round == record.round }
        list += record
    }

    override suspend fun deleteStage(sessionId: Long, stage: Stage, round: Int) {
        stageRecords[sessionId]?.removeAll { it.stage == stage && it.round == round }
    }

    override suspend fun publishIdeas(sessionId: Long, round: Int, ideasJson: String, evaluationJson: String?, shortlistJson: String?) {
        ideas[sessionId to round] = Triple(ideasJson, evaluationJson, shortlistJson)
    }

    override suspend fun replacePendingPlans(sessionId: Long, plans: List<NewPlan>) {
        storedPlans.removeAll { it.sessionId == sessionId && it.status == PlanStatus.PENDING }
        plans.forEach { p ->
            storedPlans += StoredPlan(nextPlanId++, sessionId, p.plan, null, p.score, p.effort, PlanStatus.PENDING, null)
        }
    }

    override suspend fun plans(sessionId: Long) = storedPlans.filter { it.sessionId == sessionId }
    override suspend fun plan(planId: Long) = storedPlans.firstOrNull { it.id == planId }

    override suspend fun updatePlanStatus(planId: Long, status: PlanStatus, error: String?) {
        val i = storedPlans.indexOfFirst { it.id == planId }
        if (i >= 0) storedPlans[i] = storedPlans[i].copy(status = status)
    }

    override suspend fun deliverables(planId: Long) = savedDeliverables[planId].orEmpty().toList()
    override suspend fun saveDeliverable(planId: Long, sessionId: Long, deliverable: ExecutedDeliverable) {
        savedDeliverables.getOrPut(planId) { mutableListOf() } += deliverable
    }

    override suspend fun setPipelineState(sessionId: Long, state: PipelineState, message: String?) {
        states.getOrPut(sessionId) { mutableListOf() } += state to message
    }
}
