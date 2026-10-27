package com.ideaforge.core.pipeline

import com.google.common.truth.Truth.assertThat
import com.ideaforge.core.agents.AgentOutputException
import com.ideaforge.core.llm.LlmRequest
import com.ideaforge.core.llm.ProviderException
import com.ideaforge.core.testing.CannedAgentResponses
import com.ideaforge.core.testing.FakeLlmProvider
import com.ideaforge.core.testing.InMemoryPipelineStore
import com.ideaforge.core.testing.TestPipeline
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PipelineOrchestratorTest {

    private val store = InMemoryPipelineStore()

    private fun stagesOf(id: Long) = store.stageRecords[id].orEmpty().associateBy { it.stage to it.round }

    private fun agentOf(request: LlmRequest): String = request.messages.first().content.let {
        when {
            "Transcript Verifier" in it -> "verify"
            "You are the Evaluator" in it -> "evaluate"
            "Brainstormer on a small team" in it -> "brainstorm"
            "You are the Planner" in it -> "plan"
            else -> "execute"
        }
    }

    @Test fun `full run reaches approval with plans for the shortlist`() = runTest {
        val p = TestPipeline(store)
        store.addSession(1, chunkCount = 3)

        val outcome = p.orchestrator.run(1, PipelineConfig(rounds = 2, shortlistSize = 2))

        assertThat(outcome).isEqualTo(PipelineOutcome.AwaitingApproval(2))
        assertThat(store.lastState(1)).isEqualTo(PipelineState.AWAITING_APPROVAL)
        assertThat(p.transcriber.calls).hasSize(3)
        // Raw keeps the misspelling, cleaned has the verifier's fix; raw was never overwritten.
        assertThat(store.rawTranscripts[1]!!.single().text).contains("cubernetes")
        assertThat(store.cleanedTranscripts[1]!!.single().text).contains("Kubernetes")
        // The canned evaluator ranks the same two ideas both rounds, so round 2 stops early.
        val s = stagesOf(1)
        assertThat(s[Stage.BRAINSTORM to 2]!!.status).isEqualTo(StageStatus.DONE)
        assertThat(s[Stage.EVALUATE to 2]!!.status).isEqualTo(StageStatus.DONE)
        assertThat(store.plans(1).map { it.plan.ideaId }).containsExactly("I1", "I2").inOrder()
        assertThat(store.plans(1).first().score).isEqualTo(8.1)
        // Usage was metered: 3 audio chunks plus 1 verify + 2×(brainstorm, evaluate) + 2 plans.
        assertThat(p.ledger.records.count { it.stage == "TRANSCRIBE" }).isEqualTo(3)
        assertThat(p.llm.requests).hasSize(1 + 4 + 2)
    }

    @Test fun `early stop skips remaining rounds when the shortlist is unchanged`() = runTest {
        val p = TestPipeline(store)
        store.addSession(1, chunkCount = 1)

        p.orchestrator.run(1, PipelineConfig(rounds = 4, shortlistSize = 2))

        val s = stagesOf(1)
        assertThat(s[Stage.BRAINSTORM to 3]!!.status).isEqualTo(StageStatus.SKIPPED)
        assertThat(s[Stage.EVALUATE to 4]!!.status).isEqualTo(StageStatus.SKIPPED)
        assertThat(p.llm.requests.count { agentOf(it) == "brainstorm" }).isEqualTo(2)
    }

    @Test fun `revision round sends previous ideas and feedback to the brainstormer`() = runTest {
        val p = TestPipeline(store)
        store.addSession(1, chunkCount = 1)
        p.orchestrator.run(1, PipelineConfig(rounds = 2))
        val second = p.llm.requests.filter { agentOf(it) == "brainstorm" }[1].messages[1].content
        assertThat(second).contains("EVALUATOR SCORES AND FEEDBACK")
        assertThat(second).contains("partnerships")
    }

    @Test fun `while recording only finished chunks are transcribed`() = runTest {
        val p = TestPipeline(store)
        store.addSession(1, chunkCount = 3, recording = true)

        assertThat(p.orchestrator.run(1, PipelineConfig())).isEqualTo(PipelineOutcome.WaitingForRecording)
        assertThat(p.transcriber.calls.map { it.name }).containsExactly("chunk-0.m4a", "chunk-1.m4a")

        store.stopRecording(1)
        p.orchestrator.run(1, PipelineConfig())
        // Only the last chunk is new work.
        assertThat(p.transcriber.calls.map { it.name }).containsExactly("chunk-0.m4a", "chunk-1.m4a", "chunk-2.m4a")
    }

    @Test fun `network failure is rethrown and the next run resumes from the failed stage`() = runTest {
        store.addSession(1, chunkCount = 1)
        // Fail the first brainstorm call: verify (call 0) succeeds, brainstorm (call 1) fails.
        var calls = 0
        val flaky = FakeLlmProvider { req, i ->
            calls++
            if (calls == 2) throw ProviderException("offline", retryable = true)
            CannedAgentResponses.respond(req, i)
        }
        val p2 = TestPipeline(store, llm = flaky)

        val error = runCatching { p2.orchestrator.run(1, PipelineConfig(rounds = 1)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(ProviderException::class.java)
        assertThat(store.lastState(1)).isEqualTo(PipelineState.WAITING_NETWORK)
        assertThat(stagesOf(1)[Stage.VERIFY to 0]!!.status).isEqualTo(StageStatus.DONE)
        assertThat(stagesOf(1)[Stage.BRAINSTORM to 1]!!.status).isEqualTo(StageStatus.PENDING)

        val outcome = p2.orchestrator.run(1, PipelineConfig(rounds = 1))
        assertThat(outcome).isInstanceOf(PipelineOutcome.AwaitingApproval::class.java)
        // Transcription and verification were not repeated.
        assertThat(p2.transcriber.calls).hasSize(1)
        assertThat(flaky.requests.count { agentOf(it) == "verify" }).isEqualTo(1)
    }

    @Test fun `invalid JSON is retried once with the parse error`() = runTest {
        var badOnce = true
        val llm = FakeLlmProvider { req, i ->
            if (agentOf(req) == "evaluate" && badOnce) {
                badOnce = false
                """{"evaluations": "oops"}"""
            } else CannedAgentResponses.respond(req, i)
        }
        val p = TestPipeline(store, llm = llm)
        store.addSession(1, chunkCount = 1)

        assertThat(p.orchestrator.run(1, PipelineConfig(rounds = 1))).isInstanceOf(PipelineOutcome.AwaitingApproval::class.java)
        val retry = llm.requests.filter { agentOf(it) == "evaluate" }.last()
        assertThat(retry.messages.last().content).contains("/evaluations: expected array, got string")
    }

    @Test fun `invalid JSON twice fails the stage visibly`() = runTest {
        val llm = FakeLlmProvider { req, i -> if (agentOf(req) == "plan") "not json at all" else CannedAgentResponses.respond(req, i) }
        val p = TestPipeline(store, llm = llm)
        store.addSession(1, chunkCount = 1)

        val outcome = p.orchestrator.run(1, PipelineConfig(rounds = 1))

        assertThat(outcome).isInstanceOf(PipelineOutcome.Failed::class.java)
        assertThat((outcome as PipelineOutcome.Failed).stage).isEqualTo("Plan")
        assertThat(outcome.message).contains("no JSON object found")
        assertThat(stagesOf(1)[Stage.PLAN to 0]!!.status).isEqualTo(StageStatus.FAILED)
        assertThat(store.lastState(1)).isEqualTo(PipelineState.FAILED)
        assertThat(llm.requests.count { agentOf(it) == "plan" }).isEqualTo(2)
    }

    @Test fun `spending cap pauses before the next call and resumes when raised`() = runTest {
        val p = TestPipeline(store)
        store.addSession(1, chunkCount = 1)
        p.ledger.baselineUsd = 5.0
        p.capUsd = 5.0

        val outcome = p.orchestrator.run(1, PipelineConfig(rounds = 1))
        assertThat(outcome).isInstanceOf(PipelineOutcome.PausedForBudget::class.java)
        assertThat(store.lastState(1)).isEqualTo(PipelineState.PAUSED_BUDGET)
        assertThat(p.transcriber.calls).isEmpty()

        p.capUsd = 10.0
        assertThat(p.orchestrator.run(1, PipelineConfig(rounds = 1))).isInstanceOf(PipelineOutcome.AwaitingApproval::class.java)
    }

    @Test fun `pause for review stops after verify until approved`() = runTest {
        val p = TestPipeline(store)
        store.addSession(1, chunkCount = 1)
        val config = PipelineConfig(rounds = 1, pauseAfterVerify = true)

        assertThat(p.orchestrator.run(1, config)).isEqualTo(PipelineOutcome.AwaitingReview)
        assertThat(p.llm.requests.map(::agentOf)).containsExactly("verify")

        p.orchestrator.approveReview(1)
        assertThat(p.orchestrator.run(1, config)).isInstanceOf(PipelineOutcome.AwaitingApproval::class.java)
    }

    @Test fun `disabled stages are skipped`() = runTest {
        val p = TestPipeline(store)
        store.addSession(1, chunkCount = 1)

        val outcome = p.orchestrator.run(1, PipelineConfig(verifyEnabled = false, evaluateEnabled = false, planEnabled = false))

        assertThat(outcome).isEqualTo(PipelineOutcome.Complete)
        assertThat(stagesOf(1)[Stage.VERIFY to 0]!!.status).isEqualTo(StageStatus.SKIPPED)
        assertThat(p.llm.requests.map(::agentOf)).containsExactly("brainstorm")
        // Cleaned transcript falls back to the raw one.
        assertThat(store.cleanedTranscripts[1]!!.single().text).contains("cubernetes")
    }

    @Test fun `editing an output validates it and clears downstream stages`() = runTest {
        val p = TestPipeline(store)
        store.addSession(1, chunkCount = 1)
        p.orchestrator.run(1, PipelineConfig(rounds = 1))

        val bad = p.orchestrator.editStageOutput(1, Stage.BRAINSTORM, 1, """{"core_ideas":[],"ideas":[]}""")
        assertThat(bad).isNotEmpty()

        val edited = CannedAgentResponses.BRAINSTORM.replace("Pop-up stall", "Farmers market stall")
        assertThat(p.orchestrator.editStageOutput(1, Stage.BRAINSTORM, 1, edited)).isEmpty()
        assertThat(stagesOf(1)[Stage.EVALUATE to 1]).isNull()
        assertThat(stagesOf(1)[Stage.PLAN to 0]).isNull()

        p.orchestrator.run(1, PipelineConfig(rounds = 1))
        val evalInput = p.llm.requests.filter { agentOf(it) == "evaluate" }.last().messages[1].content
        assertThat(evalInput).contains("Farmers market stall")
        // Brainstorm itself was not re-run.
        assertThat(p.llm.requests.count { agentOf(it) == "brainstorm" }).isEqualTo(1)
    }

    @Test fun `re-running a stage recomputes it and everything after it`() = runTest {
        val p = TestPipeline(store)
        store.addSession(1, chunkCount = 1)
        p.orchestrator.run(1, PipelineConfig(rounds = 1))

        p.orchestrator.resetStage(1, Stage.VERIFY)
        p.orchestrator.run(1, PipelineConfig(rounds = 1))

        assertThat(p.transcriber.calls).hasSize(1)
        assertThat(p.llm.requests.count { agentOf(it) == "verify" }).isEqualTo(2)
        assertThat(store.cleanedTranscripts[1]).hasSize(2)
        // Re-planning replaced the three pending plans instead of duplicating them.
        assertThat(store.plans(1)).hasSize(3)
    }

    @Test fun `approved plan executes every deliverable and resumes after failure`() = runTest {
        var failOnce = true
        val llm = FakeLlmProvider { req, i ->
            val target = req.messages[1].content.substringAfter("PRODUCE THIS DELIVERABLE NOW")
            if (agentOf(req) == "execute" && "\"D2\"" in target && failOnce) {
                failOnce = false
                throw ProviderException("timeout", retryable = true)
            }
            CannedAgentResponses.respond(req, i)
        }
        val p = TestPipeline(store, llm = llm)
        store.addSession(1, chunkCount = 1)
        p.orchestrator.run(1, PipelineConfig(rounds = 1))
        val planId = store.plans(1).first().id

        assertThat(p.orchestrator.executePlan(planId)).isInstanceOf(PipelineOrchestrator.ExecutionOutcome.Failed::class.java)

        store.updatePlanStatus(planId, PlanStatus.APPROVED)
        val error = runCatching { p.orchestrator.executePlan(planId) }.exceptionOrNull()
        assertThat(error).isInstanceOf(ProviderException::class.java)
        assertThat(store.savedDeliverables[planId]!!.map { it.deliverableId }).containsExactly("D1")

        assertThat(p.orchestrator.executePlan(planId)).isEqualTo(PipelineOrchestrator.ExecutionOutcome.Done(2))
        assertThat(store.plan(planId)!!.status).isEqualTo(PlanStatus.EXECUTED)
        assertThat(store.savedDeliverables[planId]!!.map { it.deliverableId }).containsExactly("D1", "D2").inOrder()
        // D1 once, D2 failed once and then succeeded; D1 was not produced twice.
        assertThat(llm.requests.count { agentOf(it) == "execute" }).isEqualTo(3)
    }

    @Test fun `agent output exception carries the problems`() {
        val e = AgentOutputException(com.ideaforge.core.agents.AgentId.PLANNER, listOf("a", "b"), "raw")
        assertThat(e.message).contains("Planner returned invalid JSON after a retry: a; b")
    }
}
