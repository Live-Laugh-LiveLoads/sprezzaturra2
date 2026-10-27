package com.ideaforge.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ideaforge.core.agents.AgentId
import com.ideaforge.core.agents.PromptFile
import com.ideaforge.prompts.AssetPromptStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal data class PromptEditState(
    val loading: Boolean = true,
    val text: String = "",
    /** The text as last saved or loaded, to detect unsaved edits. */
    val baseline: String = "",
    val customized: Boolean = false,
    val schema: String = "",
    val message: String? = null,
    val error: String? = null,
) {
    val dirty: Boolean get() = text != baseline
    val version: String get() = PromptFile.parse(text).version
}

/** Loads, edits, saves and resets one agent's prompt. The agent comes from the `agent` nav argument. */
@HiltViewModel
class PromptEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val store: AssetPromptStore,
) : ViewModel() {

    internal val agent: AgentId? = savedState.get<String>("agent")?.let { name -> AgentId.entries.firstOrNull { it.name == name } }

    private val _state = MutableStateFlow(PromptEditState())
    internal val state: StateFlow<PromptEditState> = _state

    init {
        val a = agent
        if (a == null) {
            _state.value = PromptEditState(loading = false, error = "Unknown agent")
        } else {
            viewModelScope.launch {
                try {
                    val text = store.text(a)
                    val schema = withContext(Dispatchers.IO) { store.schemaText(a) }
                    _state.value = PromptEditState(
                        loading = false, text = text, baseline = text, customized = store.isCustomized(a), schema = schema,
                    )
                } catch (e: Exception) {
                    _state.value = PromptEditState(loading = false, error = "Could not load the prompt: ${e.message}")
                }
            }
        }
    }

    internal fun onTextChange(text: String) = _state.update { it.copy(text = text, message = null) }

    fun save() {
        val a = agent ?: return
        val text = _state.value.text
        viewModelScope.launch {
            try {
                store.save(a, text)
                _state.update { it.copy(baseline = text, customized = true, message = "Saved. New runs use this prompt.", error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not save: ${e.message}") }
            }
        }
    }

    fun resetToDefault() {
        val a = agent ?: return
        viewModelScope.launch {
            try {
                store.reset(a)
                val default = withContext(Dispatchers.IO) { store.defaultText(a) }
                _state.update { it.copy(text = default, baseline = default, customized = false, message = "Restored the shipped prompt.", error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not reset: ${e.message}") }
            }
        }
    }
}

/** Monospace prompt editor with save, reset-to-default and a read-only view of the output schema. */
@Composable
fun PromptEditScreen(onBack: () -> Unit, viewModel: PromptEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var schemaOpen by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    SettingsScaffold(
        title = viewModel.agent?.displayName ?: "Prompt",
        onBack = { if (state.dirty) confirmDiscard = true else onBack() },
    ) {
        if (state.loading) {
            CircularProgressIndicator()
            return@SettingsScaffold
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (viewModel.agent == null) return@SettingsScaffold

        Text(
            "Version ${state.version}" + if (state.customized) " (customized)" else " (shipped)",
            style = MaterialTheme.typography.titleMedium,
        )
        HelpText(
            "Bump the version: line in the header when you edit, so every result records which prompt produced it. " +
                placeholderHint(viewModel.agent),
        )

        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::onTextChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
            minLines = 12,
            label = { Text("Prompt") },
        )
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::save, enabled = state.dirty, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
            OutlinedButton(
                onClick = { confirmReset = true },
                enabled = state.customized || state.dirty,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Reset to default") }
        }

        SettingsCard {
            TextButton(onClick = { schemaOpen = !schemaOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (schemaOpen) "Hide output schema" else "Show output schema (read-only)")
            }
            if (schemaOpen) {
                HelpText("The app validates every reply against this schema, so it cannot be edited here.")
                SelectionContainer {
                    Text(
                        state.schema,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    )
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset to default?") },
            text = { Text("Your customized prompt is deleted and the version shipped with the app is used again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetToDefault()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved edits to this prompt.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

private fun placeholderHint(agent: AgentId?): String = when (agent) {
    AgentId.BRAINSTORMER ->
        "Placeholders filled in by the app: {{options_per_idea}}, {{options_min}}, {{options_max}}. Keep them as written."
    AgentId.PLANNER ->
        "Placeholder filled in by the app: {{deliverable_types}}. Keep it as written."
    else -> "This prompt has no {{placeholders}}."
}
