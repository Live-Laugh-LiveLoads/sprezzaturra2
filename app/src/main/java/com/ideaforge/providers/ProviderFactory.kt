package com.ideaforge.providers

import com.ideaforge.core.agents.AgentId
import com.ideaforge.core.agents.AgentModel
import com.ideaforge.core.agents.AgentModelResolver
import com.ideaforge.core.llm.LlmProvider
import com.ideaforge.core.llm.OpenAiCompatibleLlmProvider
import com.ideaforge.core.transcription.TranscriptionProvider
import com.ideaforge.core.transcription.WhisperApiTranscriptionProvider
import com.ideaforge.settings.AppSettings
import com.ideaforge.settings.LlmProfile
import com.ideaforge.settings.SecretStore
import com.ideaforge.settings.SettingsRepository
import com.ideaforge.settings.TranscriptionEngine
import com.ideaforge.whisper.WhisperCppTranscriptionProvider
import com.ideaforge.whisper.WhisperModelManager
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds providers from the current Settings on every call, so a key or model change takes
 * effect on the next pipeline step without restarting the app.
 */
@Singleton
class ProviderFactory @Inject constructor(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val client: OkHttpClient,
    private val whisperModels: WhisperModelManager,
) : AgentModelResolver {

    fun llm(profile: LlmProfile): LlmProvider = OpenAiCompatibleLlmProvider(
        profileId = profile.id,
        baseUrl = profile.baseUrl,
        apiKey = { secrets.get(profile.id) },
        client = client,
        extraHeaders = if (profile.id == AppSettings.OPENROUTER) mapOf("X-Title" to "Idea Forge") else emptyMap(),
    )

    override fun resolve(agent: AgentId): AgentModel {
        val s = settings.current
        val choice = s.agentModel(agent)
        val profile = s.profile(choice.profileId) ?: s.llmProfiles.first()
        return AgentModel(
            provider = llm(profile),
            model = choice.model,
            temperature = choice.temperature,
            jsonMode = profile.jsonMode,
            maxTokens = choice.maxTokens,
        )
    }

    fun transcription(): TranscriptionProvider {
        val t = settings.current.transcription
        return when (t.engine) {
            TranscriptionEngine.CLOUD -> WhisperApiTranscriptionProvider(
                baseUrl = t.baseUrl,
                apiKey = { secrets.get(AppSettings.TRANSCRIPTION_KEY) },
                modelId = t.model,
                client = client,
                formatOverride = WhisperApiTranscriptionProvider.ResponseFormat.entries
                    .firstOrNull { it.wire == t.responseFormat },
            )
            TranscriptionEngine.ON_DEVICE -> WhisperCppTranscriptionProvider(
                modelFile = whisperModels.modelFile(t.onDeviceModel),
                modelName = t.onDeviceModel,
                threads = t.onDeviceThreads,
            )
        }
    }
}
