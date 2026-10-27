package com.ideaforge.core.testing

import com.ideaforge.core.agents.AgentModel
import com.ideaforge.core.agents.AgentRunner
import com.ideaforge.core.agents.BrainstormerAgent
import com.ideaforge.core.agents.EvaluatorAgent
import com.ideaforge.core.agents.ExecutorAgent
import com.ideaforge.core.agents.PlannerAgent
import com.ideaforge.core.agents.PromptStore
import com.ideaforge.core.agents.VerifierAgent
import com.ideaforge.core.llm.MeteredLlm
import com.ideaforge.core.llm.PriceTable
import com.ideaforge.core.llm.ModelPrice
import com.ideaforge.core.pipeline.ChunkAudioSource
import com.ideaforge.core.pipeline.PipelineEvents
import com.ideaforge.core.pipeline.PipelineOrchestrator
import com.ideaforge.core.pipeline.PipelineStore
import java.io.File

/** Wires a full orchestrator around fakes. Every fake is exposed for assertions. */
class TestPipeline(
    val store: PipelineStore = InMemoryPipelineStore(),
    val llm: FakeLlmProvider = FakeLlmProvider(),
    val transcriber: FakeTranscriptionProvider = FakeTranscriptionProvider(),
    val ledger: InMemoryUsageLedger = InMemoryUsageLedger(),
    prompts: PromptStore = DirectoryPromptStore.fromSystemProperty(),
    var capUsd: Double = 0.0,
    events: PipelineEvents = PipelineEvents.None,
    audioDir: File = File(System.getProperty("java.io.tmpdir")),
) {
    /** $1 per million tokens both ways, so each fake call (1000 in + 500 out) costs $0.0015. */
    val prices = PriceTable(mapOf("fake-model" to ModelPrice(1.0, 1.0), "fake-whisper" to ModelPrice(perAudioMinute = 0.01)))
    val metered = MeteredLlm(ledger, { capUsd }, { prices })
    val runner = AgentRunner(metered, { AgentModel(llm, "fake-model", 0.2, jsonMode = true) }, prompts)

    val orchestrator = PipelineOrchestrator(
        store = store,
        transcriber = { transcriber },
        audio = ChunkAudioSource { chunk -> File(audioDir, "chunk-${chunk.index}.m4a") },
        metered = metered,
        prompts = prompts,
        verifier = VerifierAgent(runner),
        brainstormer = BrainstormerAgent(runner),
        evaluator = EvaluatorAgent(runner),
        planner = PlannerAgent(runner),
        executor = ExecutorAgent(runner),
        events = events,
    )
}
