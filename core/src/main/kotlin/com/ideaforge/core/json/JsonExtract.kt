package com.ideaforge.core.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Shared JSON configuration: lenient on input (models add extra fields), stable on output. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
    prettyPrint = false
}

val PrettyJson: Json = Json(AppJson) { prettyPrint = true }

/**
 * Pulls the JSON object out of an LLM reply. Models sometimes wrap JSON in Markdown fences or add a
 * sentence before it even in JSON mode, so this strips fences and takes the outermost `{...}` span.
 */
object JsonExtract {
    class ParseException(message: String) : Exception(message)

    fun parseObject(raw: String): JsonElement {
        val text = stripFences(raw.trim())
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw ParseException("no JSON object found in the response")
        return try {
            AppJson.parseToJsonElement(text.substring(start, end + 1))
        } catch (e: Exception) {
            throw ParseException("invalid JSON: ${e.message?.lineSequence()?.firstOrNull() ?: "parse error"}")
        }
    }

    private fun stripFences(text: String): String {
        if (!text.startsWith("```")) return text
        val firstNewline = text.indexOf('\n')
        val body = if (firstNewline >= 0) text.substring(firstNewline + 1) else text
        return body.removeSuffix("```").trimEnd().removeSuffix("```")
    }
}
