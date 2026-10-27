package com.ideaforge.core.llm

import com.ideaforge.core.json.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Chat completions against any OpenAI-compatible endpoint: xAI Grok (`https://api.x.ai/v1`),
 * OpenAI, OpenRouter, Anthropic's OpenAI-compatible endpoint, Groq, or Ollama on the LAN
 * (`http://<host>:11434/v1`).
 */
class OpenAiCompatibleLlmProvider(
    override val profileId: String,
    baseUrl: String,
    private val apiKey: () -> String?,
    private val client: OkHttpClient,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : LlmProvider {

    private val base = baseUrl.trimEnd('/')

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val body = buildJsonObject {
            put("model", request.model)
            put("temperature", request.temperature)
            request.maxTokens?.let { put("max_tokens", it) }
            if (request.jsonMode) putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                request.messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role.wire)
                        put("content", m.content)
                    })
                }
            }
        }
        val http = requestBuilder("$base/chat/completions")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val text = execute(http)
        val root = try {
            AppJson.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ProviderException("Endpoint returned a non-JSON body", retryable = true, cause = e)
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw ProviderException("Response had no choices", retryable = true)
        val content = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw ProviderException("Response had no message content", retryable = true)
        val usage = root["usage"] as? JsonObject
        return LlmResponse(
            content = content,
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: request.model,
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: estimateTokens(request.messages.sumOf { it.content.length }),
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.int ?: estimateTokens(content.length),
        )
    }

    override suspend fun listModels(): List<String> {
        val text = execute(requestBuilder("$base/models").get().build())
        val root = AppJson.parseToJsonElement(text).jsonObject
        // OpenAI style is {"data":[{"id":...}]}; Ollama's /v1/models follows it too.
        val data = root["data"]?.jsonArray ?: root["models"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.sorted()
    }

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        apiKey()?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        return builder
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw ProviderException("Network error: ${e.javaClass.simpleName}", retryable = true, cause = e)
        }
        response.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw httpError(r.code, body)
            body
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** ~4 characters per token for English; used only when the endpoint omits `usage`. */
        fun estimateTokens(chars: Int): Int = (chars + 3) / 4

        internal fun httpError(code: Int, body: String): ProviderException {
            // Error bodies never contain our key, but keep them short in case they echo the request.
            val detail = runCatching {
                AppJson.parseToJsonElement(body).jsonObject["error"]?.let { e ->
                    (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.jsonPrimitive.contentOrNull
                }
            }.getOrNull() ?: body.take(200)
            val retryable = code == 408 || code == 429 || code >= 500
            val hint = when (code) {
                401, 403 -> " (check the API key in Settings)"
                404 -> " (check the base URL and model name)"
                else -> ""
            }
            return ProviderException("HTTP $code: $detail$hint", retryable, code)
        }
    }
}

/** Suspends on an OkHttp call and cancels it if the coroutine is cancelled. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
