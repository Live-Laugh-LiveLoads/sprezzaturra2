package com.ideaforge.settings

import com.ideaforge.core.agents.AgentId
import com.ideaforge.core.llm.ModelPrice
import com.ideaforge.core.llm.PriceTable
import com.ideaforge.core.pipeline.PipelineConfig
import com.ideaforge.core.transcription.TranscriptionOptions
import kotlinx.serialization.Serializable

/*
 * Every user-configurable default lives here, with its default value. The README's
 * "Configurable defaults" table mirrors this file. API keys are NOT here; see SecretStore.
 */

/** An OpenAI-compatible chat endpoint. The key is stored in SecretStore under [id]. */
@Serializable
data class LlmProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** Send `response_format: json_object`. Off for endpoints that reject it. */
    val jsonMode: Boolean = true,
)

@Serializable
data class AgentModelSetting(
    val profileId: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int? = null,
)

@Serializable
enum class TranscriptionEngine { CLOUD, ON_DEVICE }

@Serializable
data class TranscriptionSettings(
    val engine: TranscriptionEngine = TranscriptionEngine.CLOUD,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "whisper-1",
    /** "auto", "verbose_json", "json" or "diarized_json". */
    val responseFormat: String = "auto",
    val language: String = "en",
    val diarize: Boolean = true,
    /** "tiny.en" or "base.en" (whisper.cpp ggml models). */
    val onDeviceModel: String = "base.en",
    val onDeviceThreads: Int = 2,
)

@Serializable
enum class MicSource { MIC, VOICE_RECOGNITION, CAMCORDER, UNPROCESSED }

@Serializable
data class RecordingSettings(
    val chunkMinutes: Int = 5,
    val overlapSeconds: Int = 5,
    val sampleRate: Int = 16_000,
    val bitrateKbps: Int = 32,
    val micSource: MicSource = MicSource.MIC,
    /** Stop gracefully when battery falls to this level and the phone is not charging. */
    val stopAtBatteryPercent: Int = 5,
    val warnAtBatteryPercent: Int = 15,
    /** Stop gracefully when free storage drops below this. */
    val stopAtFreeMb: Int = 50,
    /** Pause during phone calls and audio-focus loss, then resume automatically. */
    val autoResumeAfterInterruption: Boolean = true,
    /** Keep the CPU awake with a partial wake lock while recording. */
    val useWakeLock: Boolean = true,
)

@Serializable
data class PipelineSettings(
    val verifyEnabled: Boolean = true,
    val brainstormEnabled: Boolean = true,
    val evaluateEnabled: Boolean = true,
    val planEnabled: Boolean = true,
    val pauseAfterVerify: Boolean = false,
    val rounds: Int = 2,
    val shortlistSize: Int = 5,
    val optionsPerIdea: Int = 10,
    /** Names and jargon you use, comma separated. Sent to transcription and the Verifier. */
    val glossary: String = "",
    /** Start the pipeline automatically when a recording stops. */
    val autoStart: Boolean = true,
)

@Serializable
data class CostSettings(
    /** USD per calendar month; 0 disables the cap. */
    val monthlyCapUsd: Double = 10.0,
    /** User overrides merged over [PriceTable.DEFAULTS]. */
    val priceOverrides: Map<String, ModelPrice> = emptyMap(),
)

@Serializable
data class DataSettings(
    /** Delete audio (keeping transcripts and outputs) this many days after recording; 0 = never. */
    val deleteAudioAfterDays: Int = 0,
)

@Serializable
data class ApprovalSettings(
    /** Default threshold for "Approve all above score X". */
    val approveAboveScore: Double = 7.0,
)

@Serializable
data class NotificationSettings(
    val transcriptionFinished: Boolean = true,
    val plansReady: Boolean = true,
    val executionFinished: Boolean = true,
    val failures: Boolean = true,
)

@Serializable
data class AppSettings(
    val llmProfiles: List<LlmProfile> = DEFAULT_PROFILES,
    val agentModels: Map<AgentId, AgentModelSetting> = DEFAULT_AGENT_MODELS,
    val transcription: TranscriptionSettings = TranscriptionSettings(),
    val recording: RecordingSettings = RecordingSettings(),
    val pipeline: PipelineSettings = PipelineSettings(),
    val cost: CostSettings = CostSettings(),
    val data: DataSettings = DataSettings(),
    val approval: ApprovalSettings = ApprovalSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    /** Set once the first-run setup screen has been completed or skipped. */
    val onboardingDone: Boolean = false,
) {
    fun profile(id: String): LlmProfile? = llmProfiles.firstOrNull { it.id == id }

    fun agentModel(agent: AgentId): AgentModelSetting = agentModels[agent] ?: DEFAULT_AGENT_MODELS.getValue(agent)

    fun priceTable(): PriceTable = PriceTable(PriceTable.DEFAULTS + cost.priceOverrides)

    fun pipelineConfig(): PipelineConfig = PipelineConfig(
        verifyEnabled = pipeline.verifyEnabled,
        brainstormEnabled = pipeline.brainstormEnabled,
        evaluateEnabled = pipeline.evaluateEnabled,
        planEnabled = pipeline.planEnabled,
        pauseAfterVerify = pipeline.pauseAfterVerify,
        rounds = pipeline.rounds.coerceIn(1, PipelineConfig.MAX_ROUNDS),
        shortlistSize = pipeline.shortlistSize.coerceIn(1, 20),
        optionsPerIdea = pipeline.optionsPerIdea.coerceIn(3, 25),
        glossary = pipeline.glossary,
        transcription = TranscriptionOptions(
            language = transcription.language.ifBlank { null },
            vocabularyHint = pipeline.glossary.ifBlank { null },
            diarize = transcription.diarize,
        ),
    )

    companion object {
        const val XAI = "xai"
        const val OPENAI = "openai"
        const val OPENROUTER = "openrouter"
        const val ANTHROPIC = "anthropic"
        const val OLLAMA = "ollama"

        /** Key slot for the cloud transcription endpoint in SecretStore. */
        const val TRANSCRIPTION_KEY = "transcription"

        val DEFAULT_PROFILES = listOf(
            LlmProfile(XAI, "xAI Grok", "https://api.x.ai/v1"),
            LlmProfile(OPENAI, "OpenAI", "https://api.openai.com/v1"),
            LlmProfile(OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1"),
            // Anthropic's OpenAI-compatible endpoint ignores response_format, so JSON mode is off.
            LlmProfile(ANTHROPIC, "Anthropic (OpenAI-compatible)", "https://api.anthropic.com/v1", jsonMode = false),
            LlmProfile(OLLAMA, "Ollama on LAN", "http://192.168.1.10:11434/v1"),
        )

        const val DEFAULT_MODEL = "grok-4-fast-reasoning"

        val DEFAULT_AGENT_MODELS: Map<AgentId, AgentModelSetting> = mapOf(
            AgentId.VERIFIER to AgentModelSetting(XAI, DEFAULT_MODEL, 0.1),
            AgentId.BRAINSTORMER to AgentModelSetting(XAI, DEFAULT_MODEL, 0.9),
            AgentId.EVALUATOR to AgentModelSetting(XAI, DEFAULT_MODEL, 0.3),
            AgentId.PLANNER to AgentModelSetting(XAI, DEFAULT_MODEL, 0.4),
            AgentId.EXECUTOR to AgentModelSetting(XAI, DEFAULT_MODEL, 0.5),
        )
    }
}
