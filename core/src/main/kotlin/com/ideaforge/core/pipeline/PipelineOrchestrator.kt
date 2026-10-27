package com.ideaforge.core.pipeline

import com.ideaforge.core.agents.AgentId
import com.ideaforge.core.agents.BrainstormOutput
import com.ideaforge.core.agents.BrainstormerAgent
import com.ideaforge.core.agents.EvaluationOutput
import com.ideaforge.core.agents.EvaluatorAgent
import com.ideaforge.core.agents.ExecutorAgent
import com.ideaforge.core.agents.Plan
import com.ideaforge.core.agents.PlannerAgent
import com.ideaforge.core.agents.PromptStore
import com.ideaforge.core.agents.Shortlist
import com.ideaforge.core.agents.VerifierAgent
import com.ideaforge.core.agents.VerifierOutput
import com.ideaforge.core.agents.transcriptDigest
import com.ideaforge.core.json.AppJson
import com.ideaforge.core.json.JsonExtract
import com.ideaforge.core.json.JsonSchemaValidator
import com.ideaforge.core.llm.BudgetExceededException
import com.ideaforge.core.llm.MeteredLlm
import com.ideaforge.core.llm.ProviderException
import com.ideaforge.core.transcription.TranscriptionProvider
import com.ideaforge.core.transcript.ChunkTranscript
import com.ideaforge.core.transcript.Transcript
import com.ideaforge.core.transcript.TranscriptStitcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives a session through Transcribe → Verify → [Review] → (Brainstorm ⇄ Evaluate) × N → Plan,
 * and runs approved plans through the Executor.
 *
 * Every stage writes its output to [PipelineStore] before the next one starts, and [run] always
 * begins by skipping stages already DONE, so a crash, a process kill or a WorkManager retry resumes
 * from the last completed step. Errors are classified three ways:
 *  - retryable network/server errors are rethrown as [ProviderException] for WorkManager to retry,
 *  - the monthly spending cap pauses the pipeline and returns [PipelineOutcome.PausedForBudget],
 *  - everything else (bad JSON twice, bad key, 4xx) marks the stage FAILED and is shown in the app.
 */
class PipelineOrchestrator(
    private val store: PipelineStore,
    private val transcriber: () -> TranscriptionProvider,
    private val audio: ChunkAudioSource,
    private val metered: MeteredLlm,
    private val prompts: PromptStore,
    private val verifier: VerifierAgent,
    private val brainstormer: BrainstormerAgent,
    private val evaluator: EvaluatorAgent,
    private val planner: PlannerAgent,
    private val executor: ExecutorAgent,
    private val events: PipelineEvents = PipelineEvents.None,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ------------------------------------------------------------------------------------------
    // Main pipeline
    // ------------------------------------------------------------------------------------------

    suspend fun run(sessionId: Long, config: PipelineConfig): PipelineOutcome = lockFor(sessionId).withLock {
        val session = store.session(sessionId)
        store.setPipelineState(sessionId, if (session.isRecording) PipelineState.RECORDING else PipelineState.RUNNING)
        var current = Stage.TRANSCRIBE to 0
        try {
            if (!transcribe(session, config)) return PipelineOutcome.WaitingForRecording
            store.setPipelineState(sessionId, PipelineState.RUNNING)

            current = Stage.VERIFY to 0
            verify(session, config)

            current = Stage.REVIEW to 0
            if (needsReview(sessionId, config)) {
                store.setPipelineState(sessionId, PipelineState.AWAITING_REVIEW)
                events.reviewNeeded(sessionId)
                return PipelineOutcome.AwaitingReview
            }

            if (!config.brainstormEnabled) {
                markSkipped(sessionId, Stage.BRAINSTORM, 1, "Brainstorm is turned off in Settings")
                return complete(sessionId)
            }
            val shortlist = brainstormRounds(session, config) { stage, round -> current = stage to round }

            current = Stage.PLAN to 0
            if (!config.planEnabled) {
                markSkipped(sessionId, Stage.PLAN, 0, "Planner is turned off in Settings")
                return complete(sessionId)
            }
            val planCount = plan(session, shortlist)
            store.setPipelineState(sessionId, PipelineState.AWAITING_APPROVAL, "$planCount plans ready")
            events.plansReady(sessionId, planCount)
            return PipelineOutcome.AwaitingApproval(planCount)
        } catch (e: BudgetExceededException) {
            resetRunning(sessionId, current.first, current.second, "Paused: ${e.message}")
            store.setPipelineState(sessionId, PipelineState.PAUSED_BUDGET, e.message)
            events.budgetPaused(sessionId, e.message.orEmpty())
            return PipelineOutcome.PausedForBudget(e.message.orEmpty())
        } catch (e: ProviderException) {
            if (e.retryable) {
                resetRunning(sessionId, current.first, current.second, "Will retry: ${e.message}")
                store.setPipelineState(sessionId, PipelineState.WAITING_NETWORK, e.message)
                throw e
            }
            return fail(sessionId, current.first, current.second, e.message ?: "Provider error")
        } catch (e: kotlinx.coroutines.CancellationException) {
            resetRunning(sessionId, current.first, current.second, "Interrupted; will resume")
            throw e
        } catch (e: Exception) {
            return fail(sessionId, current.first, current.second, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Transcribes every finished chunk; returns false while the recording is still running. */
    private suspend fun transcribe(session: SessionInfo, config: PipelineConfig): Boolean {
        if (stage(session.id, Stage.TRANSCRIBE, 0)?.status == StageStatus.DONE) return true
        val provider = transcriber()
        val options = config.transcription.copy(
            vocabularyHint = config.transcription.vocabularyHint ?: config.glossary.ifBlank { null },
        )
        val chunks = store.chunks(session.id).sortedBy { it.index }
        // While recording, the last chunk is still being written; only earlier ones are complete.
        val ready = if (session.isRecording) chunks.dropLast(1) else chunks
        for (chunk in ready.filter { it.transcript == null }) {
            if (provider.requiresNetwork) metered.ensureBudget()
            val transcript = provider.transcribe(audio.fileFor(chunk), options)
            store.saveChunkTranscript(chunk.id, transcript)
            if (provider.requiresNetwork) metered.recordAudio(session.id, provider.modelId, chunk.durationMs)
        }
        if (session.isRecording) return false

        val transcribed = store.chunks(session.id)
        val stitched = TranscriptStitcher.stitch(
            transcribed.map { ChunkTranscript(it.index, it.startOffsetMs, it.durationMs, it.transcript ?: Transcript.EMPTY) },
        )
        store.insertRawTranscript(session.id, stitched)
        val summary = buildJsonObject {
            put("chunks", transcribed.size)
            put("segments", stitched.segments.size)
            put("words", stitched.text.split(Regex("\\s+")).count { it.isNotEmpty() })
            put("speakers", stitched.segments.mapNotNull { it.speaker }.distinct().size)
        }
        done(session.id, Stage.TRANSCRIBE, 0, summary.toString(), model = provider.modelId)
        events.transcriptionFinished(session.id)
        return true
    }

    private suspend fun verify(session: SessionInfo, config: PipelineConfig) {
        val existing = stage(session.id, Stage.VERIFY, 0)
        if (existing?.status == StageStatus.DONE || existing?.status == StageStatus.SKIPPED) return
        val raw = store.latestRawTranscript(session.id) ?: error("No raw transcript to verify")
        if (!config.verifyEnabled) {
            store.insertCleanedTranscript(session.id, raw)
            markSkipped(session.id, Stage.VERIFY, 0, "Verifier is turned off; using the raw transcript")
            return
        }
        running(session.id, Stage.VERIFY, 0)
        val result = verifier.verify(raw, session.id, config.glossary, session.speakerNames)
        store.insertCleanedTranscript(session.id, result.cleaned)
        done(
            session.id, Stage.VERIFY, 0,
            AppJson.encodeToString(VerifierOutput.serializer(), result.output),
            result.model, result.promptVersion,
        )
    }

    private suspend fun needsReview(sessionId: Long, config: PipelineConfig): Boolean {
        if (!config.pauseAfterVerify) return false
        val review = stage(sessionId, Stage.REVIEW, 0)
        if (review?.status == StageStatus.DONE) return false
        store.upsertStage(sessionId, StageRecord(Stage.REVIEW, 0, StageStatus.PENDING, note = "Waiting for you to review the transcript", updatedAt = clock()))
        return true
    }

    /** Marks the review gate passed. Call [run] afterwards to continue. */
    suspend fun approveReview(sessionId: Long) {
        done(sessionId, Stage.REVIEW, 0, null, note = "Reviewed")
    }

    private suspend fun brainstormRounds(
        session: SessionInfo,
        config: PipelineConfig,
        onStage: (Stage, Int) -> Unit,
    ): Shortlist {
        val transcript = store.latestCleanedTranscript(session.id) ?: store.latestRawTranscript(session.id)
            ?: error("No transcript available")
        val rounds = if (config.evaluateEnabled) config.rounds else 1
        var previousIdeas: BrainstormOutput? = null
        var previousEval: EvaluationOutput? = null
        var previousKey: Set<String>? = null
        var shortlist: Shortlist? = null

        for (round in 1..rounds) {
            onStage(Stage.BRAINSTORM, round)
            val ideas = cached(session.id, Stage.BRAINSTORM, round, BrainstormOutput.serializer()) ?: run {
                running(session.id, Stage.BRAINSTORM, round)
                val r = brainstormer.brainstorm(
                    transcript, session.speakerNames, session.userNotes, config.optionsPerIdea,
                    round, previousIdeas, previousEval, session.id,
                )
                done(session.id, Stage.BRAINSTORM, round, AppJson.encodeToString(BrainstormOutput.serializer(), r.value), r.model, r.promptVersion)
                r.value
            }

            if (!config.evaluateEnabled) {
                markSkipped(session.id, Stage.EVALUATE, round, "Evaluator is turned off; shortlist is the first ideas in order")
                shortlist = EvaluatorAgent.shortlist(ideas, null, config.shortlistSize)
                publish(session.id, round, ideas, null, shortlist)
                break
            }

            onStage(Stage.EVALUATE, round)
            val evaluation = cached(session.id, Stage.EVALUATE, round, EvaluationOutput.serializer()) ?: run {
                running(session.id, Stage.EVALUATE, round)
                val r = evaluator.evaluate(ideas, transcript, session.speakerNames, session.userNotes, round, session.id)
                done(session.id, Stage.EVALUATE, round, AppJson.encodeToString(EvaluationOutput.serializer(), r.value), r.model, r.promptVersion)
                r.value
            }

            shortlist = EvaluatorAgent.shortlist(ideas, evaluation, config.shortlistSize)
            publish(session.id, round, ideas, evaluation, shortlist)

            val key = EvaluatorAgent.shortlistKey(shortlist)
            if (previousKey != null && key == previousKey) {
                for (skipped in round + 1..rounds) {
                    markSkipped(session.id, Stage.BRAINSTORM, skipped, "Stopped early: round $round shortlist matched round ${round - 1}")
                    markSkipped(session.id, Stage.EVALUATE, skipped, "Stopped early")
                }
                break
            }
            previousKey = key
            previousIdeas = ideas
            previousEval = evaluation
        }
        return shortlist ?: error("Brainstorm produced no shortlist")
    }

    private suspend fun publish(sessionId: Long, round: Int, ideas: BrainstormOutput, eval: EvaluationOutput?, shortlist: Shortlist) {
        store.publishIdeas(
            sessionId, round,
            AppJson.encodeToString(BrainstormOutput.serializer(), ideas),
            eval?.let { AppJson.encodeToString(EvaluationOutput.serializer(), it) },
            AppJson.encodeToString(Shortlist.serializer(), shortlist),
        )
    }

    /** Plans each shortlisted idea, saving after each one so a retry does not redo finished plans. */
    private suspend fun plan(session: SessionInfo, shortlist: Shortlist): Int {
        val existing = stage(session.id, Stage.PLAN, 0)
        val planSerializer = ListSerializer(Plan.serializer())
        if (existing?.status == StageStatus.DONE) {
            return AppJson.decodeFromString(planSerializer, existing.effectiveJson ?: "[]").size
        }
        val partial = existing?.outputJson?.let { runCatching { AppJson.decodeFromString(planSerializer, it) }.getOrNull() }
            .orEmpty().toMutableList()
        val transcript = store.latestCleanedTranscript(session.id) ?: Transcript.EMPTY
        val digest = transcriptDigest(transcript)
        var model: String? = existing?.model
        var version: String? = existing?.promptVersion

        for (item in shortlist.items) {
            if (partial.any { it.ideaId == item.idea.id }) continue
            store.upsertStage(session.id, StageRecord(Stage.PLAN, 0, StageStatus.RUNNING,
                outputJson = AppJson.encodeToString(planSerializer, partial),
                note = "Planning ${partial.size + 1} of ${shortlist.items.size}", updatedAt = clock()))
            val r = planner.plan(item, digest, session.userNotes, session.id)
            partial += r.value
            model = r.model
            version = r.promptVersion
        }
        done(session.id, Stage.PLAN, 0, AppJson.encodeToString(planSerializer, partial), model, version)

        val byIdea = shortlist.items.associateBy { it.idea.id }
        store.replacePendingPlans(session.id, partial.map { p ->
            val item = byIdea[p.ideaId]
            NewPlan(p, item?.evaluation?.overall, item?.idea?.effort ?: "unknown", item?.rank ?: Int.MAX_VALUE)
        })
        return partial.size
    }

    // ------------------------------------------------------------------------------------------
    // Execution of approved plans
    // ------------------------------------------------------------------------------------------

    sealed interface ExecutionOutcome {
        data class Done(val deliverables: Int) : ExecutionOutcome
        data class PausedForBudget(val message: String) : ExecutionOutcome
        data class Failed(val message: String) : ExecutionOutcome
    }

    /** Produces every deliverable of an approved plan; deliverables already saved are skipped. */
    suspend fun executePlan(planId: Long): ExecutionOutcome {
        val stored = store.plan(planId) ?: return ExecutionOutcome.Failed("Plan $planId no longer exists")
        return lockFor(stored.sessionId).withLock {
            if (stored.status !in setOf(PlanStatus.APPROVED, PlanStatus.EXECUTING, PlanStatus.FAILED)) {
                return@withLock ExecutionOutcome.Failed("Plan is ${stored.status.name.lowercase()}, not approved")
            }
            store.updatePlanStatus(planId, PlanStatus.EXECUTING)
            val plan = stored.effectivePlan
            try {
                val produced = store.deliverables(planId).toMutableList()
                for (d in plan.deliverables) {
                    if (produced.any { it.deliverableId == d.id }) continue
                    val r = executor.execute(plan, d, produced, stored.sessionId)
                    store.saveDeliverable(planId, stored.sessionId, r.value)
                    produced += r.value
                }
                store.updatePlanStatus(planId, PlanStatus.EXECUTED)
                events.executionFinished(stored.sessionId, planId, produced.size)
                ExecutionOutcome.Done(produced.size)
            } catch (e: BudgetExceededException) {
                store.updatePlanStatus(planId, PlanStatus.APPROVED, e.message)
                events.budgetPaused(stored.sessionId, e.message.orEmpty())
                ExecutionOutcome.PausedForBudget(e.message.orEmpty())
            } catch (e: ProviderException) {
                if (e.retryable) {
                    store.updatePlanStatus(planId, PlanStatus.APPROVED, "Will retry: ${e.message}")
                    throw e
                }
                store.updatePlanStatus(planId, PlanStatus.FAILED, e.message)
                events.failed(stored.sessionId, "Execute", e.message.orEmpty())
                ExecutionOutcome.Failed(e.message.orEmpty())
            } catch (e: kotlinx.coroutines.CancellationException) {
                store.updatePlanStatus(planId, PlanStatus.APPROVED, "Interrupted; will resume")
                throw e
            } catch (e: Exception) {
                store.updatePlanStatus(planId, PlanStatus.FAILED, e.message)
                events.failed(stored.sessionId, "Execute", e.message.orEmpty())
                ExecutionOutcome.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // User controls: re-run a stage, edit an intermediate output
    // ------------------------------------------------------------------------------------------

    /**
     * Clears [stage] (at [round]) and everything downstream so the next [run] recomputes them.
     * Re-running TRANSCRIBE also discards chunk transcripts; earlier raw transcript versions stay.
     */
    suspend fun resetStage(sessionId: Long, stage: Stage, round: Int = 0) = lockFor(sessionId).withLock {
        val from = stage.position(round)
        store.stages(sessionId).filter { it.stage.position(it.round) >= from }
            .forEach { store.deleteStage(sessionId, it.stage, it.round) }
        if (stage == Stage.TRANSCRIBE) store.clearChunkTranscripts(sessionId)
        store.setPipelineState(sessionId, PipelineState.QUEUED, "Re-running ${stage.label}")
    }

    /**
     * Saves a hand edit of a stage's JSON output after validating it against the agent's schema,
     * then clears everything downstream so later stages consume the edit.
     * Returns the validation problems; an empty list means the edit was saved.
     */
    suspend fun editStageOutput(sessionId: Long, stage: Stage, round: Int, json: String): List<String> = lockFor(sessionId).withLock {
        val agent = when (stage) {
            Stage.VERIFY -> AgentId.VERIFIER
            Stage.BRAINSTORM -> AgentId.BRAINSTORMER
            Stage.EVALUATE -> AgentId.EVALUATOR
            Stage.PLAN -> null
            Stage.TRANSCRIBE, Stage.REVIEW -> return@withLock listOf("${stage.label} output is not editable here")
        }
        val record = stage(sessionId, stage, round) ?: return@withLock listOf("Stage has not run yet")
        val problems = try {
            if (agent != null) {
                val element = JsonExtract.parseObject(json)
                JsonSchemaValidator.validate(element, prompts.load(agent).schema).map { it.toString() }
            } else {
                AppJson.decodeFromString(ListSerializer(Plan.serializer()), json)
                emptyList()
            }
        } catch (e: Exception) {
            listOf(e.message ?: "invalid JSON")
        }
        if (problems.isNotEmpty()) return@withLock problems

        store.upsertStage(sessionId, record.copy(editedJson = json, updatedAt = clock(), note = "Edited by you"))
        val from = stage.position(round)
        store.stages(sessionId)
            .filter { it.stage.position(it.round) > from && it.stage != Stage.REVIEW }
            .forEach { store.deleteStage(sessionId, it.stage, it.round) }
        emptyList()
    }

    /** Saves a hand-edited cleaned transcript (a new version) and invalidates the agent stages. */
    suspend fun editCleanedTranscript(sessionId: Long, transcript: Transcript) = lockFor(sessionId).withLock {
        store.insertCleanedTranscript(sessionId, transcript)
        val from = Stage.REVIEW.position(0)
        store.stages(sessionId).filter { it.stage.position(it.round) > from }
            .forEach { store.deleteStage(sessionId, it.stage, it.round) }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private suspend fun stage(sessionId: Long, stage: Stage, round: Int): StageRecord? =
        store.stages(sessionId).firstOrNull { it.stage == stage && it.round == round }

    private suspend fun <T> cached(sessionId: Long, stage: Stage, round: Int, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val record = stage(sessionId, stage, round) ?: return null
        if (record.status != StageStatus.DONE) return null
        return AppJson.decodeFromString(serializer, record.effectiveJson ?: return null)
    }

    private suspend fun running(sessionId: Long, stage: Stage, round: Int) {
        store.upsertStage(sessionId, StageRecord(stage, round, StageStatus.RUNNING, updatedAt = clock()))
    }

    private suspend fun done(sessionId: Long, stage: Stage, round: Int, json: String?, model: String? = null, version: String? = null, note: String? = null) {
        store.upsertStage(sessionId, StageRecord(stage, round, StageStatus.DONE, outputJson = json, model = model, promptVersion = version, note = note, updatedAt = clock()))
    }

    private suspend fun markSkipped(sessionId: Long, stage: Stage, round: Int, note: String) {
        store.upsertStage(sessionId, StageRecord(stage, round, StageStatus.SKIPPED, note = note, updatedAt = clock()))
    }

    /** Puts an interrupted stage back to PENDING, keeping any partial output (used by PLAN). */
    private suspend fun resetRunning(sessionId: Long, stage: Stage, round: Int, note: String) {
        val record = stage(sessionId, stage, round)
        if (record == null || record.status == StageStatus.RUNNING) {
            store.upsertStage(sessionId, (record ?: StageRecord(stage, round, StageStatus.PENDING))
                .copy(status = StageStatus.PENDING, note = note, updatedAt = clock()))
        }
    }

    private suspend fun fail(sessionId: Long, stage: Stage, round: Int, message: String): PipelineOutcome {
        val record = stage(sessionId, stage, round) ?: StageRecord(stage, round, StageStatus.FAILED)
        store.upsertStage(sessionId, record.copy(status = StageStatus.FAILED, error = message, updatedAt = clock()))
        val label = if (round > 0) "${stage.label} (round $round)" else stage.label
        store.setPipelineState(sessionId, PipelineState.FAILED, "$label failed: $message")
        events.failed(sessionId, label, message)
        return PipelineOutcome.Failed(label, message)
    }

    private suspend fun complete(sessionId: Long): PipelineOutcome {
        store.setPipelineState(sessionId, PipelineState.COMPLETE)
        return PipelineOutcome.Complete
    }

    companion object {
        private val locks = ConcurrentHashMap<Long, Mutex>()
        private fun lockFor(sessionId: Long): Mutex = locks.getOrPut(sessionId) { Mutex() }
    }
}
