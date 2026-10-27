package com.ideaforge.core.llm

import java.io.IOException

data class LlmMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }

    companion object {
        fun system(text: String) = LlmMessage(Role.SYSTEM, text)
        fun user(text: String) = LlmMessage(Role.USER, text)
        fun assistant(text: String) = LlmMessage(Role.ASSISTANT, text)
    }
}

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val temperature: Double = 0.4,
    /** Ask for `response_format: json_object`. Turn off for endpoints that reject it. */
    val jsonMode: Boolean = true,
    val maxTokens: Int? = null,
)

data class LlmResponse(
    val content: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
)

/**
 * A chat-completion backend. Implementations must never log the API key or full request headers.
 */
interface LlmProvider {
    /** Stable id of the provider profile this instance was built from (used for usage records). */
    val profileId: String

    suspend fun complete(request: LlmRequest): LlmResponse

    /** Model ids the endpoint advertises, for the model picker in Settings. */
    suspend fun listModels(): List<String>
}

/**
 * A failure talking to an LLM or transcription endpoint.
 * [retryable] is true for network errors, timeouts, 408/429 and 5xx: WorkManager retries those
 * with backoff. Everything else (bad key, unknown model, 4xx) fails the stage visibly.
 */
open class ProviderException(
    message: String,
    val retryable: Boolean,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

class BudgetExceededException(val spentUsd: Double, val capUsd: Double) :
    Exception("Monthly spending cap reached (\$${"%.2f".format(spentUsd)} of \$${"%.2f".format(capUsd)})")
