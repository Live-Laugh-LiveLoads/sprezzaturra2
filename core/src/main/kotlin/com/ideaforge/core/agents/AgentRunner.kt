package com.ideaforge.core.agents

import com.ideaforge.core.json.AppJson
import com.ideaforge.core.json.JsonExtract
import com.ideaforge.core.json.JsonSchemaValidator
import com.ideaforge.core.llm.LlmMessage
import com.ideaforge.core.llm.LlmProvider
import com.ideaforge.core.llm.LlmRequest
import com.ideaforge.core.llm.MeteredLlm
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/** A system prompt plus the schema its output must satisfy. */
data class AgentPrompt(
    val agent: AgentId,
    val version: String,
    val systemTemplate: String,
    val schema: JsonObject,
) {
    /** Fills `{{name}}` placeholders, leaving unknown ones intact so typos are visible. */
    fun render(vars: Map<String, Any>): String =
        vars.entries.fold(systemTemplate) { acc, (k, v) -> acc.replace("{{$k}}", v.toString()) }
}

/** Loads the current (possibly user-edited) prompt for an agent. */
interface PromptStore {
    suspend fun load(agent: AgentId): AgentPrompt
}

/** The endpoint and model an agent should use, from Settings. */
data class AgentModel(
    val provider: LlmProvider,
    val model: String,
    val temperature: Double,
    val jsonMode: Boolean,
    val maxTokens: Int? = null,
)

fun interface AgentModelResolver {
    fun resolve(agent: AgentId): AgentModel
}

data class AgentResult<T>(
    val value: T,
    val rawJson: String,
    val promptVersion: String,
    val model: String,
)

/** The model's output failed parsing or schema validation twice. Shown to the user on the stage. */
class AgentOutputException(val agent: AgentId, val problems: List<String>, val lastRaw: String) :
    Exception("${agent.displayName} returned invalid JSON after a retry: ${problems.take(5).joinToString("; ")}")

/**
 * Runs one structured agent call: render prompt → call model → extract JSON → validate against the
 * schema → decode. On a parse or validation failure it retries exactly once, sending the model its
 * own reply and the list of errors; a second failure throws [AgentOutputException].
 */
class AgentRunner(
    private val metered: MeteredLlm,
    private val models: AgentModelResolver,
    private val prompts: PromptStore,
) {
    suspend fun <T> call(
        agent: AgentId,
        sessionId: Long?,
        stageLabel: String,
        userContent: String,
        serializer: KSerializer<T>,
        vars: Map<String, Any> = emptyMap(),
    ): AgentResult<T> {
        val prompt = prompts.load(agent)
        val model = models.resolve(agent)
        val messages = mutableListOf(
            LlmMessage.system(prompt.render(vars)),
            LlmMessage.user(userContent),
        )

        var lastRaw = ""
        var problems: List<String> = emptyList()
        repeat(2) { attempt ->
            val response = metered.complete(
                model.provider,
                LlmRequest(model.model, messages, model.temperature, model.jsonMode, model.maxTokens),
                sessionId,
                if (attempt == 0) stageLabel else "$stageLabel (retry)",
            )
            lastRaw = response.content
            problems = try {
                val element = JsonExtract.parseObject(response.content)
                val schemaErrors = JsonSchemaValidator.validate(element, prompt.schema)
                if (schemaErrors.isEmpty()) {
                    val value = AppJson.decodeFromJsonElement(serializer, element)
                    return AgentResult(value, element.toString(), prompt.version, response.model)
                }
                schemaErrors.map { it.toString() }
            } catch (e: JsonExtract.ParseException) {
                listOf(e.message ?: "unparseable JSON")
            } catch (e: kotlinx.serialization.SerializationException) {
                listOf(e.message?.lineSequence()?.firstOrNull() ?: "JSON did not match the expected shape")
            } catch (e: IllegalArgumentException) {
                listOf(e.message?.lineSequence()?.firstOrNull() ?: "JSON did not match the expected shape")
            }
            messages += LlmMessage.assistant(response.content)
            messages += LlmMessage.user(
                "Your reply could not be used. Problems:\n" +
                    problems.take(20).joinToString("\n") { "- $it" } +
                    "\nReply again with only the corrected JSON object that satisfies the schema.",
            )
        }
        throw AgentOutputException(agent, problems, lastRaw)
    }
}
