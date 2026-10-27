package com.ideaforge.ui.settings

import com.ideaforge.settings.AppSettings
import com.ideaforge.settings.TranscriptionSettings

/** A ready-made cloud transcription endpoint. */
internal data class TranscriptionPreset(
    val label: String,
    val baseUrl: String,
    val model: String,
    val responseFormat: String,
    val diarize: Boolean,
    val keyUrl: String,
) {
    fun applyTo(t: TranscriptionSettings) = t.copy(
        baseUrl = baseUrl,
        model = model,
        responseFormat = responseFormat,
        diarize = diarize,
    )
}

/** Where to get keys, shown next to the key fields. */
internal data class KeyHelp(val profileId: String, val text: String)

internal object SettingsPresets {
    val TRANSCRIPTION = listOf(
        TranscriptionPreset("OpenAI whisper-1", "https://api.openai.com/v1", "whisper-1", "auto", true, "platform.openai.com/api-keys"),
        TranscriptionPreset("OpenAI diarized", "https://api.openai.com/v1", "gpt-4o-transcribe-diarize", "diarized_json", true, "platform.openai.com/api-keys"),
        TranscriptionPreset("Groq turbo", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", "auto", false, "console.groq.com/keys"),
    )

    val RESPONSE_FORMATS = listOf("auto", "verbose_json", "json", "diarized_json")

    /** Key locations for the built-in providers. */
    val KEY_HELP = listOf(
        KeyHelp(AppSettings.XAI, "xAI: console.x.ai"),
        KeyHelp(AppSettings.OPENAI, "OpenAI: platform.openai.com/api-keys"),
        KeyHelp("groq", "Groq: console.groq.com/keys (add it as a custom endpoint, base URL https://api.groq.com/openai/v1)"),
        KeyHelp(AppSettings.OPENROUTER, "OpenRouter: openrouter.ai/keys"),
        KeyHelp(AppSettings.ANTHROPIC, "Anthropic: console.anthropic.com"),
        KeyHelp(AppSettings.OLLAMA, "Ollama: no key needed. Use http://<lan-ip>:11434/v1 with your computer's address."),
    )

    /** Link shown on the onboarding screen for each provider id (without scheme). */
    fun keyUrl(profileId: String): String? = when (profileId) {
        AppSettings.XAI -> "console.x.ai"
        AppSettings.OPENAI -> "platform.openai.com/api-keys"
        AppSettings.OPENROUTER -> "openrouter.ai/keys"
        AppSettings.ANTHROPIC -> "console.anthropic.com"
        else -> null
    }
}
