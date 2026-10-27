package com.ideaforge.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** USD per million tokens. Transcription models use [perAudioMinute] instead. */
@Serializable
data class ModelPrice(
    @SerialName("input_per_m") val inputPerMillion: Double = 0.0,
    @SerialName("output_per_m") val outputPerMillion: Double = 0.0,
    @SerialName("per_audio_minute") val perAudioMinute: Double = 0.0,
)

/**
 * Price table keyed by model id. Lookup is exact first, then the longest key that prefixes the
 * model id (so "gpt-4o-mini-2024-07-18" uses "gpt-4o-mini"). Unknown models cost 0 and the UI
 * marks the estimate as incomplete. Defaults are list prices at the time of writing and are
 * editable in Settings, because providers change them.
 */
class PriceTable(private val prices: Map<String, ModelPrice>) {

    fun priceFor(model: String): ModelPrice? {
        val key = model.lowercase()
        prices[key]?.let { return it }
        return prices.entries.filter { key.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
    }

    fun chatCost(model: String, promptTokens: Int, completionTokens: Int): Double {
        val p = priceFor(model) ?: return 0.0
        return promptTokens / 1_000_000.0 * p.inputPerMillion + completionTokens / 1_000_000.0 * p.outputPerMillion
    }

    fun audioCost(model: String, durationMs: Long): Double {
        val p = priceFor(model) ?: return 0.0
        return durationMs / 60_000.0 * p.perAudioMinute
    }

    fun asMap(): Map<String, ModelPrice> = prices

    companion object {
        val DEFAULTS: Map<String, ModelPrice> = mapOf(
            "grok-4-fast-reasoning" to ModelPrice(0.20, 0.50),
            "grok-4-fast-non-reasoning" to ModelPrice(0.20, 0.50),
            "grok-4-fast" to ModelPrice(0.20, 0.50),
            "grok-4" to ModelPrice(3.00, 15.00),
            "grok-3-mini" to ModelPrice(0.30, 0.50),
            "grok-3" to ModelPrice(3.00, 15.00),
            "gpt-4o-mini" to ModelPrice(0.15, 0.60),
            "gpt-4o" to ModelPrice(2.50, 10.00),
            "gpt-4.1-mini" to ModelPrice(0.40, 1.60),
            "gpt-4.1" to ModelPrice(2.00, 8.00),
            "whisper-1" to ModelPrice(perAudioMinute = 0.006),
            "gpt-4o-transcribe" to ModelPrice(perAudioMinute = 0.006),
            "gpt-4o-mini-transcribe" to ModelPrice(perAudioMinute = 0.003),
            "whisper-large-v3-turbo" to ModelPrice(perAudioMinute = 0.04 / 60),
            "whisper-large-v3" to ModelPrice(perAudioMinute = 0.111 / 60),
        )
    }
}

/** One billed call, persisted so the app can show per-run and month-to-date totals. */
data class UsageRecord(
    val sessionId: Long?,
    val stage: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val audioMs: Long,
    val costUsd: Double,
    val atEpochMs: Long,
)

/** Where usage goes, and where the month-to-date spend comes from. Implemented over Room. */
interface UsageLedger {
    suspend fun record(usage: UsageRecord)
    suspend fun monthToDateUsd(nowEpochMs: Long): Double
}

/** Spending policy: [capUsd] <= 0 means no cap. */
fun interface BudgetPolicy {
    fun monthlyCapUsd(): Double
}

/**
 * Wraps calls with the budget check and usage recording. Every LLM call in the pipeline goes
 * through here, which is what makes the spending cap actually pause the pipeline.
 */
class MeteredLlm(
    private val ledger: UsageLedger,
    private val budget: BudgetPolicy,
    private val prices: () -> PriceTable,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun ensureBudget() {
        val cap = budget.monthlyCapUsd()
        if (cap <= 0) return
        val spent = ledger.monthToDateUsd(clock())
        if (spent >= cap) throw BudgetExceededException(spent, cap)
    }

    suspend fun complete(provider: LlmProvider, request: LlmRequest, sessionId: Long?, stage: String): LlmResponse {
        ensureBudget()
        val response = provider.complete(request)
        ledger.record(
            UsageRecord(
                sessionId = sessionId,
                stage = stage,
                model = response.model.ifBlank { request.model },
                promptTokens = response.promptTokens,
                completionTokens = response.completionTokens,
                audioMs = 0,
                costUsd = prices().chatCost(request.model, response.promptTokens, response.completionTokens),
                atEpochMs = clock(),
            ),
        )
        return response
    }

    suspend fun recordAudio(sessionId: Long?, model: String, durationMs: Long) {
        ledger.record(
            UsageRecord(sessionId, "TRANSCRIBE", model, 0, 0, durationMs, prices().audioCost(model, durationMs), clock()),
        )
    }
}
