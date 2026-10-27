package com.ideaforge.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ideaforge.core.json.AppJson
import com.ideaforge.core.json.PrettyJson
import com.ideaforge.core.pipeline.Stage
import com.ideaforge.data.SessionActions
import com.ideaforge.data.db.AppDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/** UI state of the stage-output editor. */
data class StageEditUi(
    val loading: Boolean = true,
    val loadError: String? = null,
    val saving: Boolean = false,
    /** Validation problems from the last save attempt; non-empty means nothing was saved. */
    val problems: List<String> = emptyList(),
)

/**
 * Edits the effective JSON of one stage run. The text lives here (not in the composable) so a
 * rotation does not lose a long edit.
 */
@HiltViewModel
class StageEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val db: AppDatabase,
    private val actions: SessionActions,
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: error("sessionId argument missing")
    val stage: Stage = Stage.valueOf(savedStateHandle.get<String>("stage") ?: error("stage argument missing"))
    private val round: Int = savedStateHandle.get<Int>("round") ?: 0

    private val _ui = MutableStateFlow(StageEditUi())
    val ui: StateFlow<StageEditUi> = _ui.asStateFlow()

    /** Snapshot state, not a Flow: keystrokes in a large text field must apply synchronously. */
    var text: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val run = db.stages().forSession(sessionId).firstOrNull { it.stage == stage.name && it.round == round }
                        ?: error("This stage has not run yet")
                    val raw = run.editedJson ?: run.outputJson ?: error("This stage has no output to edit")
                    // Pretty-print so a phone-sized edit is feasible; the validator accepts any whitespace.
                    PrettyJson.encodeToString(JsonElement.serializer(), AppJson.parseToJsonElement(raw))
                }
            }
            result.getOrNull()?.let { text = it }
            _ui.update { s ->
                result.fold(
                    onSuccess = { s.copy(loading = false) },
                    onFailure = { s.copy(loading = false, loadError = it.message ?: "Could not load the output") },
                )
            }
        }
    }

    fun onTextChange(value: String) {
        text = value
        if (_ui.value.problems.isNotEmpty()) _ui.update { it.copy(problems = emptyList()) }
    }

    /** Validates and saves; on success re-queues the pipeline (downstream stages were cleared) and calls [onDone]. */
    fun save(onDone: () -> Unit) {
        if (_ui.value.saving) return
        _ui.update { it.copy(saving = true, problems = emptyList()) }
        viewModelScope.launch {
            try {
                val problems = actions.editStageOutput(sessionId, stage, round, text)
                if (problems.isEmpty()) {
                    actions.runPipeline(sessionId)
                    _ui.update { it.copy(saving = false) }
                    onDone()
                } else {
                    _ui.update { it.copy(saving = false, problems = problems) }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, problems = listOf(e.message ?: "Could not save")) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageEditScreen(onDone: () -> Unit, viewModel: StageEditViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit ${viewModel.stage.label} output") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back without saving") } },
            )
        },
    ) { padding ->
        when {
            ui.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.loadError != null -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(ui.loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Saving clears everything that came after this stage and runs it again from your edited version. " +
                        "Cloud stages may use API credits. The JSON must stay valid for this stage's schema.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.problems.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                        Column(
                            Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Not saved. Fix these problems:", style = MaterialTheme.typography.titleSmall)
                            ui.problems.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                OutlinedTextField(
                    value = viewModel.text,
                    onValueChange = viewModel::onTextChange,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = { Text("JSON") },
                )
                Button(
                    onClick = { viewModel.save(onDone) },
                    enabled = !ui.saving && viewModel.text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (ui.saving) "Saving..." else "Save and re-run later stages") }
            }
        }
    }
}
