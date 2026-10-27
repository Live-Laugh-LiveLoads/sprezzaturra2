package com.ideaforge.core.transcription

import com.ideaforge.core.json.AppJson
import com.ideaforge.core.llm.OpenAiCompatibleLlmProvider
import com.ideaforge.core.llm.ProviderException
import com.ideaforge.core.llm.await
import com.ideaforge.core.transcript.Segment
import com.ideaforge.core.transcript.Transcript
import com.ideaforge.core.transcript.Word
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * `/audio/transcriptions` on any Whisper-compatible endpoint: OpenAI (`https://api.openai.com/v1`,
 * models `whisper-1`, `gpt-4o-transcribe-diarize`), Groq (`https://api.groq.com/openai/v1`, model
 * `whisper-large-v3-turbo`) or a self-hosted server.
 *
 * Response format is picked from the model name unless [formatOverride] is set:
 *  - names containing "diarize" → `diarized_json` (speaker labels),
 *  - `gpt-4o-*transcribe` → `json` (those models return no timestamps; the file becomes one segment),
 *  - anything else → `verbose_json` with segment and word timestamps.
 */
class WhisperApiTranscriptionProvider(
    baseUrl: String,
    private val apiKey: () -> String?,
    override val modelId: String,
    private val client: OkHttpClient,
    private val formatOverride: ResponseFormat? = null,
) : TranscriptionProvider {

    enum class ResponseFormat(val wire: String) { VERBOSE_JSON("verbose_json"), JSON("json"), DIARIZED_JSON("diarized_json") }

    private val base = baseUrl.trimEnd('/')

    override val requiresNetwork = true

    override val supportsDiarization: Boolean get() = format() == ResponseFormat.DIARIZED_JSON

    private fun format(): ResponseFormat = formatOverride ?: when {
        modelId.contains("diarize", ignoreCase = true) -> ResponseFormat.DIARIZED_JSON
        modelId.startsWith("gpt-4o", ignoreCase = true) -> ResponseFormat.JSON
        else -> ResponseFormat.VERBOSE_JSON
    }

    override suspend fun transcribe(audio: File, options: TranscriptionOptions): Transcript {
        val format = format()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody(mimeFor(audio).toMediaType()))
            .addFormDataPart("model", modelId)
            .addFormDataPart("response_format", format.wire)
            .apply {
                options.language?.let { addFormDataPart("language", it) }
                when (format) {
                    ResponseFormat.VERBOSE_JSON -> {
                        addFormDataPart("timestamp_granularities[]", "segment")
                        addFormDataPart("timestamp_granularities[]", "word")
                        options.vocabularyHint?.takeIf { it.isNotBlank() }?.let { addFormDataPart("prompt", it) }
                    }
                    // Diarizing models need server-side chunking for audio longer than 30 s.
                    ResponseFormat.DIARIZED_JSON -> addFormDataPart("chunking_strategy", "auto")
                    ResponseFormat.JSON -> options.vocabularyHint?.takeIf { it.isNotBlank() }?.let { addFormDataPart("prompt", it) }
                }
            }
            .build()

        val request = Request.Builder().url("$base/audio/transcriptions").post(body).apply {
            apiKey()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }.build()

        val text = withContext(Dispatchers.IO) {
            val response = try {
                client.newCall(request).await()
            } catch (e: IOException) {
                throw ProviderException("Network error: ${e.javaClass.simpleName}", retryable = true, cause = e)
            }
            response.use { r ->
                val b = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw OpenAiCompatibleLlmProvider.httpError(r.code, b)
                b
            }
        }
        return parse(text, format)
    }

    companion object {
        fun mimeFor(file: File): String = when (file.extension.lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "webm" -> "audio/webm"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }

        /** Parses any of the three response shapes into a chunk-relative [Transcript]. */
        fun parse(text: String, format: ResponseFormat): Transcript {
            val root = try {
                AppJson.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw ProviderException("Transcription endpoint returned non-JSON", retryable = true, cause = e)
            }
            val language = root["language"]?.jsonPrimitive?.contentOrNull
            val segmentsJson = root["segments"] as? JsonArray
            if (format == ResponseFormat.JSON || segmentsJson == null) {
                val whole = root["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val duration = root["duration"]?.jsonPrimitive?.doubleOrNull?.let(::secToMs) ?: 0L
                return Transcript(if (whole.isEmpty()) emptyList() else listOf(Segment(0, duration, whole)), language)
            }

            val words = (root["words"] as? JsonArray)?.mapNotNull { w ->
                val o = w.jsonObject
                val t = o["word"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Word(t.trim(), secToMs(o.num("start")), secToMs(o.num("end")))
            }.orEmpty()

            val speakerNames = LinkedHashMap<String, String>()
            val segments = segmentsJson.mapNotNull { s ->
                val o = s.jsonObject
                val segText = o["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (segText.isEmpty()) return@mapNotNull null
                val start = secToMs(o.num("start"))
                val end = secToMs(o.num("end"))
                val speaker = o["speaker"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                    // Providers label speakers "A", "B" or "SPEAKER_00"; show them as Speaker 1, 2...
                    speakerNames.getOrPut(raw) { "Speaker ${speakerNames.size + 1}" }
                }
                val confidence = (o["avg_logprob"]?.jsonPrimitive?.doubleOrNull)?.let { exp(it).coerceIn(0.0, 1.0) }
                Segment(
                    startMs = start,
                    endMs = end,
                    text = segText,
                    speaker = speaker,
                    words = words.filter { it.startMs >= start && it.startMs < end },
                    confidence = confidence,
                )
            }
            return Transcript(segments, language)
        }

        private fun JsonObject.num(key: String): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
        private fun secToMs(sec: Double): Long = (sec * 1000).roundToLong()
    }
}
