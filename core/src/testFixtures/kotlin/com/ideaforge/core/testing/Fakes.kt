package com.ideaforge.core.testing

import com.ideaforge.core.agents.AgentId
import com.ideaforge.core.agents.AgentPrompt
import com.ideaforge.core.agents.PromptFile
import com.ideaforge.core.agents.PromptStore
import com.ideaforge.core.llm.LlmProvider
import com.ideaforge.core.llm.LlmRequest
import com.ideaforge.core.llm.LlmResponse
import com.ideaforge.core.llm.UsageLedger
import com.ideaforge.core.llm.UsageRecord
import com.ideaforge.core.transcription.TranscriptionOptions
import com.ideaforge.core.transcription.TranscriptionProvider
import com.ideaforge.core.transcript.Segment
import com.ideaforge.core.transcript.Transcript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An LLM that answers from a script. [responder] gets the request and the 0-based count of calls so
 * far and returns the reply text. Requests are kept in [requests] for assertions.
 */
class FakeLlmProvider(
    override val profileId: String = "fake",
    private val responder: (LlmRequest, Int) -> String = CannedAgentResponses::respond,
) : LlmProvider {
    val requests = CopyOnWriteArrayList<LlmRequest>()

    /** Set to make the next N calls throw, to simulate airplane mode. */
    @Volatile var failNext: Int = 0
    @Volatile var failure: () -> Exception = { java.io.IOException("offline") }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        if (failNext > 0) {
            failNext--
            throw failure()
        }
        val index = requests.size
        requests += request
        val text = responder(request, index)
        return LlmResponse(text, request.model, promptTokens = 1000, completionTokens = 500)
    }

    override suspend fun listModels(): List<String> = listOf("fake-model")
}

/**
 * Produces schema-valid replies for each agent by recognising the agent from its system prompt.
 * The verifier echoes every segment id it is given, so any transcript round-trips.
 */
object CannedAgentResponses {
    private val json = Json { ignoreUnknownKeys = true }

    fun respond(request: LlmRequest, @Suppress("UNUSED_PARAMETER") index: Int): String {
        val system = request.messages.first().content
        val user = request.messages[1].content
        return when {
            "Transcript Verifier" in system -> verifier(user)
            "Brainstormer on a small team" in system -> BRAINSTORM
            "You are the Evaluator" in system -> EVALUATION
            "You are the Planner" in system -> plan(user)
            "You are the Executor" in system -> executor(user)
            else -> error("FakeLlmProvider: unrecognised agent prompt")
        }
    }

    private fun verifier(user: String): String {
        val segments = json.parseToJsonElement(user).jsonObject["segments"] as JsonArray
        val items = segments.joinToString(",") { s ->
            val o = s.jsonObject
            val id = o["id"]!!.jsonPrimitive.int
            val text = o["text"]!!.jsonPrimitive.content.replace("cubernetes", "Kubernetes")
            """{"id":$id,"text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}"""
        }
        return """{"segments":[$items],"changes":[],"flags":[]}"""
    }

    private fun plan(user: String): String {
        val ideaId = Regex("\"id\": \"(I\\d+)\"").find(user)?.groupValues?.get(1) ?: "I1"
        return """
        {"idea_id":"$ideaId","title":"Plan for $ideaId","summary":"Do the thing","objective":"Ship it",
         "deliverables":[{"id":"D1","title":"Launch checklist","type":"checklist","description":"Steps"},
                         {"id":"D2","title":"Announcement email","type":"email_draft","description":"Email"}],
         "steps":[{"order":1,"description":"Start","estimated_time":"1h"}],
         "tools":["Phone"],"estimated_time":"1 day","dependencies":[],
         "risks":[{"risk":"Too slow","mitigation":"Timebox"}],"definition_of_done":"It is shipped"}
        """.trimIndent()
    }

    private fun executor(user: String): String {
        val id = Regex("\"id\": \"(D\\d+)\"").findAll(user).lastOrNull()?.groupValues?.get(1) ?: "D1"
        return """{"deliverable_id":"$id","title":"Deliverable $id","type":"checklist","filename":"deliverable-$id.md",
                  "content_markdown":"# Deliverable $id\n- [ ] step","complete":false,"human_actions":["Call the venue"]}"""
    }

    const val BRAINSTORM = """
    {"core_ideas":[{"id":"C1","title":"Weekend market stall","summary":"Sell prints","kind":"idea","timestamp_ms":0}],
     "ideas":[
       {"id":"I1","core_idea_id":"C1","title":"Pop-up stall","description":"Rent a stall","source_timestamp_ms":0,"effort":"low","style":"conventional"},
       {"id":"I2","core_idea_id":"C1","title":"Online shop","description":"Open a shop","source_timestamp_ms":1000,"effort":"medium","style":"conventional"},
       {"id":"I3","core_idea_id":"C1","title":"Print vending machine","description":"Odd but fun","source_timestamp_ms":2000,"effort":"high","style":"unconventional"}
     ]}
    """

    const val EVALUATION = """
    {"evaluations":[
       {"idea_id":"I1","scores":{"impact":7,"feasibility":9,"effort":8,"cost":8,"risk":8,"time_to_first_result":9,"fit":8},"pros":["fast"],"cons":["weather"],"overall":8.1,"rationale":"Quick test"},
       {"idea_id":"I2","scores":{"impact":8,"feasibility":7,"effort":6,"cost":7,"risk":7,"time_to_first_result":6,"fit":7},"pros":["scale"],"cons":["marketing"],"overall":7.2,"rationale":"Solid"},
       {"idea_id":"I3","scores":{"impact":5,"feasibility":3,"effort":2,"cost":2,"risk":3,"time_to_first_result":2,"fit":4},"pros":["novel"],"cons":["costly"],"overall":3.5,"rationale":"Too costly"}
     ],
     "feedback":{"gaps":["pricing"],"weak_spots":["I3 cost"],"directions":["partnerships"]}}
    """
}

/** Returns a fixed transcript per call (or per file via [byFile]); counts calls. */
class FakeTranscriptionProvider(
    override val modelId: String = "fake-whisper",
    override val requiresNetwork: Boolean = true,
    override val supportsDiarization: Boolean = false,
    private val byFile: (File) -> Transcript = { f ->
        Transcript(listOf(Segment(0, 4000, "We could run a cubernetes cluster for ${f.nameWithoutExtension}.")))
    },
) : TranscriptionProvider {
    val calls = CopyOnWriteArrayList<File>()
    @Volatile var failNext: Int = 0

    override suspend fun transcribe(audio: File, options: TranscriptionOptions): Transcript {
        if (failNext > 0) {
            failNext--
            throw com.ideaforge.core.llm.ProviderException("offline", retryable = true)
        }
        calls += audio
        return byFile(audio)
    }
}

class InMemoryUsageLedger : UsageLedger {
    val records = CopyOnWriteArrayList<UsageRecord>()
    var baselineUsd = 0.0

    override suspend fun record(usage: UsageRecord) {
        records += usage
    }

    override suspend fun monthToDateUsd(nowEpochMs: Long): Double = baselineUsd + records.sumOf { it.costUsd }
}

/** Loads prompts and schemas from an assets directory laid out like app/src/main/assets. */
class DirectoryPromptStore(private val assets: File) : PromptStore {
    override suspend fun load(agent: AgentId): AgentPrompt = PromptFile.toPrompt(
        agent,
        File(assets, "prompts/${agent.fileName}.md").readText(),
        File(assets, "schemas/${agent.fileName}.schema.json").readText(),
    )

    companion object {
        /** The app's assets directory, passed to JVM tests by the core build script. */
        fun fromSystemProperty(): DirectoryPromptStore =
            DirectoryPromptStore(File(System.getProperty("ideaforge.assets") ?: "../app/src/main/assets"))
    }
}
