package com.ideaforge.core.agents

import com.ideaforge.core.json.AppJson
import kotlinx.serialization.json.jsonObject

/**
 * Prompt files are Markdown with a small front-matter header:
 *
 * ```
 * ---
 * agent: verifier
 * version: 1.0.0
 * ---
 * <system prompt>
 * ```
 *
 * The version is stored with every stage output so results can be traced to the prompt that made them.
 */
object PromptFile {

    data class Parsed(val version: String, val body: String)

    fun parse(text: String): Parsed {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return Parsed("unversioned", normalized.trim())
        val end = normalized.indexOf("\n---", startIndex = 4)
        if (end < 0) return Parsed("unversioned", normalized.trim())
        val header = normalized.substring(4, end)
        val body = normalized.substring(end + 4).trimStart('-').trim()
        val version = header.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("version:") }
            ?.substringAfter(':')?.trim()
            ?.ifEmpty { null } ?: "unversioned"
        return Parsed(version, body)
    }

    fun toPrompt(agent: AgentId, promptText: String, schemaText: String): AgentPrompt {
        val parsed = parse(promptText)
        return AgentPrompt(agent, parsed.version, parsed.body, AppJson.parseToJsonElement(schemaText).jsonObject)
    }
}
