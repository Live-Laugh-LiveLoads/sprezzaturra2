package com.ideaforge.prompts

import android.content.Context
import com.ideaforge.core.agents.AgentId
import com.ideaforge.core.agents.AgentPrompt
import com.ideaforge.core.agents.PromptFile
import com.ideaforge.core.agents.PromptStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent prompts: the defaults ship in assets/prompts; an edit made in Settings is saved to
 * filesDir/prompts/<agent>.md and takes precedence, so prompts are tunable without recompiling.
 * Schemas always come from assets/schemas because the app's code depends on their shape.
 */
@Singleton
class AssetPromptStore @Inject constructor(@ApplicationContext private val context: Context) : PromptStore {

    private val overrideDir = File(context.filesDir, "prompts")

    override suspend fun load(agent: AgentId): AgentPrompt = withContext(Dispatchers.IO) {
        PromptFile.toPrompt(agent, text(agent), schemaText(agent))
    }

    /** The prompt text currently in effect (override or default). */
    suspend fun text(agent: AgentId): String = withContext(Dispatchers.IO) {
        overrideFile(agent).takeIf { it.exists() }?.readText() ?: defaultText(agent)
    }

    fun defaultText(agent: AgentId): String =
        context.assets.open("prompts/${agent.fileName}.md").bufferedReader().use { it.readText() }

    fun schemaText(agent: AgentId): String =
        context.assets.open("schemas/${agent.fileName}.schema.json").bufferedReader().use { it.readText() }

    fun isCustomized(agent: AgentId): Boolean = overrideFile(agent).exists()

    suspend fun save(agent: AgentId, text: String) = withContext(Dispatchers.IO) {
        overrideDir.mkdirs()
        overrideFile(agent).writeText(text)
    }

    suspend fun reset(agent: AgentId) = withContext(Dispatchers.IO) {
        overrideFile(agent).delete()
        Unit
    }

    /** Directory of customized prompts, included in backups. */
    val customizedDir: File get() = overrideDir

    private fun overrideFile(agent: AgentId) = File(overrideDir, "${agent.fileName}.md")
}
