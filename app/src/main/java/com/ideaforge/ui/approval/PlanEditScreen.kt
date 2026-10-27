package com.ideaforge.ui.approval

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.core.agents.DeliverableTypes
import com.ideaforge.ui.session.typeLabel

/** Form for hand-editing a plan, then approving it in one step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditScreen(onDone: () -> Unit, viewModel: PlanEditViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit plan") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back without saving") } },
            )
        },
    ) { padding ->
        when {
            ui.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.loadError != null -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(ui.loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val form = viewModel.form
                Column(
                    Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Field("Title", form.title, singleLine = true) { v -> viewModel.update { it.copy(title = v) } }
                    Field("Summary", form.summary) { v -> viewModel.update { it.copy(summary = v) } }
                    Field("Objective", form.objective) { v -> viewModel.update { it.copy(objective = v) } }
                    Field("Definition of done", form.definitionOfDone) { v -> viewModel.update { it.copy(definitionOfDone = v) } }
                    Field("Estimated time", form.estimatedTime, singleLine = true) { v -> viewModel.update { it.copy(estimatedTime = v) } }
                    Field("Steps (one per line; numbered when saved)", form.steps, minLines = 4) { v -> viewModel.update { it.copy(steps = v) } }
                    Field("Tools (comma separated)", form.tools) { v -> viewModel.update { it.copy(tools = v) } }
                    Field("Dependencies (one per line)", form.dependencies) { v -> viewModel.update { it.copy(dependencies = v) } }

                    Text("Deliverables", style = MaterialTheme.typography.titleMedium)
                    form.deliverables.forEach { d ->
                        key(d.id) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(d.id, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { viewModel.removeDeliverable(d.id) }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Remove deliverable ${d.id}")
                                        }
                                    }
                                    Field("Title", d.title, singleLine = true) { v -> viewModel.updateDeliverable(d.id) { it.copy(title = v) } }
                                    TypeDropdown(d.type) { t -> viewModel.updateDeliverable(d.id) { it.copy(type = t) } }
                                    Field("Description", d.description) { v -> viewModel.updateDeliverable(d.id) { it.copy(description = v) } }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = viewModel::addDeliverable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add deliverable") }

                    Text("Risks", style = MaterialTheme.typography.titleMedium)
                    form.risks.forEachIndexed { i, r ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Risk ${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { viewModel.removeRisk(i) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove risk ${i + 1}") }
                                }
                                Field("Risk", r.risk) { v -> viewModel.updateRisk(i) { it.copy(risk = v) } }
                                Field("Mitigation", r.mitigation) { v -> viewModel.updateRisk(i) { it.copy(mitigation = v) } }
                            }
                        }
                    }
                    OutlinedButton(onClick = viewModel::addRisk, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add risk") }

                    val problem = form.problem ?: ui.saveError
                    if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { viewModel.saveAndApprove(onDone) },
                        enabled = form.problem == null && !ui.saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (ui.saving) "Saving..." else "Save and approve") }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, singleLine: Boolean = false, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A menu button listing the allowed deliverable types (avoids the experimental exposed-dropdown API). */
@Composable
private fun TypeDropdown(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Type: ${typeLabel(selected)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DeliverableTypes.ALL.forEach { t ->
                DropdownMenuItem(text = { Text(typeLabel(t)) }, onClick = { open = false; onSelect(t) })
            }
        }
    }
}
