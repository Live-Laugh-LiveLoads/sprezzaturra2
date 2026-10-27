package com.ideaforge.core.transcription

import com.ideaforge.core.transcript.Transcript
import java.io.File

data class TranscriptionOptions(
    /** ISO-639-1 code such as "en", or null to auto-detect. */
    val language: String? = "en",
    /** Names and jargon that help the model spell things right (Whisper's `prompt`). */
    val vocabularyHint: String? = null,
    /** Ask for speaker labels when the model supports them. */
    val diarize: Boolean = true,
)

/**
 * Turns one audio file into a timed transcript with times relative to the file start.
 * Implementations: [WhisperApiTranscriptionProvider] (cloud) and the whisper.cpp provider in the
 * `whisper` module (on-device).
 */
interface TranscriptionProvider {
    /** The model id used for pricing and display, e.g. "whisper-1" or "ggml-base.en". */
    val modelId: String

    /** True for providers that need the network; the worker then waits for connectivity. */
    val requiresNetwork: Boolean

    /** True if speaker labels can come back for this model. */
    val supportsDiarization: Boolean

    suspend fun transcribe(audio: File, options: TranscriptionOptions): Transcript
}
