package com.ideaforge.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.core.agents.AgentId
import com.ideaforge.settings.AppSettings
import com.ideaforge.settings.LlmProfile
import java.util.Locale

// ---- providers -------------------------------------------------------------------------------

@Composable
internal fun ProvidersSection(vm: SettingsViewModel, settings: AppSettings) {
    val hints by vm.keyHints.collectAsStateWithLifecycle()
    val tests by vm.tests.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }

    SettingsCard(title = "Where to get keys") {
        SettingsPresets.KEY_HELP.forEach { HelpText(it.text) }
        HelpText("Keys are stored encrypted on this phone, are never shown in full, and are not included in backups.")
    }

    settings.llmProfiles.forEach { profile ->
        key(profile.id) {
            ProfileCard(
                profile = profile,
                custom = !vm.isBuiltIn(profile.id),
                keyHint = hints[profile.id],
                test = tests[profile.id],
                vm = vm,
            )
        }
    }

    Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Add custom endpoint")
    }

    if (showAdd) {
        AddEndpointDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, url ->
                vm.addCustomProfile(name, url)
                showAdd = false
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: LlmProfile,
    custom: Boolean,
    keyHint: String?,
    test: TestState?,
    vm: SettingsViewModel,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    SettingsCard(title = profile.name) {
        if (custom) {
            SaveableField(
                value = profile.name,
                label = "Name",
                onValue = { n -> vm.updateProfile(profile.id) { it.copy(name = n) } },
            )
        }
        SaveableField(
            value = profile.baseUrl,
            label = "Base URL",
            onValue = { u -> vm.updateProfile(profile.id) { it.copy(baseUrl = u.trim()) } },
            keyboardType = KeyboardType.Uri,
            supportingText = "OpenAI-compatible, ends in /v1",
        )
        SwitchRow(
            label = "JSON mode",
            description = "Ask the endpoint for guaranteed JSON. Turn off if it rejects response_format.",
            checked = profile.jsonMode,
            onChange = { on -> vm.updateProfile(profile.id) { it.copy(jsonMode = on) } },
        )
        ApiKeyField(
            label = "API key",
            savedHint = keyHint,
            onSave = { vm.saveKey(profile.id, it) },
            onClear = { vm.clearKey(profile.id) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { vm.testProfile(profile) },
                enabled = test !is TestState.Running,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Test") }
            when (test) {
                TestState.Running -> CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                is TestState.Ok -> Text(
                    "Works: ${test.modelCount} models available",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                is TestState.Failed -> Text(
                    test.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                null -> {}
            }
        }
        if (custom) {
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Delete endpoint", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${profile.name}?") },
            text = { Text("Its saved key is removed, and agents using it switch back to xAI.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteProfile(profile.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddEndpointDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("https://") }
    val valid = name.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://")) && url.length > 8
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom endpoint") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("For example https://api.groq.com/openai/v1") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, url) }, enabled = valid) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- agents ----------------------------------------------------------------------------------

@Composable
internal fun AgentsSection(vm: SettingsViewModel, settings: AppSettings) {
    val picker by vm.picker.collectAsStateWithLifecycle()
    // Bumped when the picker rewrites a model so the text fields drop their local copy.
    var pickCount by rememberSaveable { mutableIntStateOf(0) }

    HelpText("Each agent can use a different provider and model. Cheaper, faster models suit the Verifier; stronger ones suit the Planner and Executor.")
    AgentId.entries.forEach { agent ->
        key(agent) { AgentCard(agent, settings, vm, pickCount) }
    }

    picker?.let { p ->
        ModelPickerDialog(
            picker = p,
            profileName = settings.profile(settings.agentModel(p.agent).profileId)?.name ?: "provider",
            onPick = { model ->
                vm.update { s -> s.copy(agentModels = s.agentModels + (p.agent to s.agentModel(p.agent).copy(model = model))) }
                pickCount++
                vm.closeModelPicker()
            },
            onDismiss = vm::closeModelPicker,
        )
    }
}

@Composable
private fun AgentCard(agent: AgentId, settings: AppSettings, vm: SettingsViewModel, modelResetKey: Int) {
    val m = settings.agentModel(agent)
    var temp by remember(m.temperature) { mutableFloatStateOf(m.temperature.toFloat()) }
    fun change(transform: (com.ideaforge.settings.AgentModelSetting) -> com.ideaforge.settings.AgentModelSetting) {
        vm.update { s -> s.copy(agentModels = s.agentModels + (agent to transform(s.agentModel(agent)))) }
    }

    SettingsCard(title = agent.displayName) {
        DropdownField(
            label = "Provider",
            selectedText = settings.profile(m.profileId)?.name ?: "${m.profileId} (missing)",
            options = settings.llmProfiles.map { it.id to it.name },
            onSelect = { id -> change { it.copy(profileId = id) } },
        )
        SaveableField(
            value = m.model,
            label = "Model",
            onValue = { v -> change { it.copy(model = v.trim()) } },
            resetKey = modelResetKey,
            supportingText = "Exact model id, e.g. ${AppSettings.DEFAULT_MODEL}",
        )
        OutlinedButton(
            onClick = { vm.openModelPicker(agent) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Pick from list") }
        Text("Temperature: ${"%.2f".format(Locale.US, temp)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = temp,
            onValueChange = { temp = it },
            valueRange = 0f..1.5f,
            steps = 29,
            onValueChangeFinished = {
                val rounded = Math.round(temp * 20) / 20.0
                change { it.copy(temperature = rounded) }
            },
        )
        SaveableField(
            value = m.maxTokens?.toString().orEmpty(),
            label = "Max output tokens (optional)",
            onValue = { v ->
                val n = v.trim().toIntOrNull()
                when {
                    v.isBlank() -> change { it.copy(maxTokens = null) }
                    n != null && n > 0 -> change { it.copy(maxTokens = n) }
                    else -> Unit
                }
            },
            keyboardType = KeyboardType.Number,
            supportingText = "Leave empty to let the model decide",
        )
    }
}

@Composable
private fun ModelPickerDialog(
    picker: ModelPicker,
    profileName: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Models at $profileName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    picker.loading -> CircularProgressIndicator()
                    picker.error != null -> Text(picker.error, color = MaterialTheme.colorScheme.error)
                    else -> {
                        OutlinedTextField(
                            filter, { filter = it }, label = { Text("Filter") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val shown = picker.models.filter { it.contains(filter.trim(), ignoreCase = true) }
                        if (shown.isEmpty()) HelpText("No models match.")
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(shown, key = { it }) { model ->
                                Text(
                                    model,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable { onPick(model) }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---- prompts ---------------------------------------------------------------------------------

@Composable
internal fun PromptsSection(vm: SettingsViewModel, onEditPrompt: (String) -> Unit) {
    val rows by vm.promptRows.collectAsStateWithLifecycle()
    // Runs on first show and each time we come back from the editor.
    LifecycleResumeEffect(Unit) {
        vm.refreshPrompts()
        onPauseOrDispose {}
    }
    HelpText("Prompts ship with the app. Your edits are saved on this phone and replace the shipped text until you reset them.")
    rows.forEach { row ->
        SettingsCard(modifier = Modifier.clickable { onEditPrompt(row.agent.name) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(row.agent.displayName, style = MaterialTheme.typography.titleMedium)
                    HelpText("Version ${row.version}")
                }
                if (row.customized) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            "customized",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
    }
}

// ---- pipeline --------------------------------------------------------------------------------

@Composable
internal fun PipelineSection(vm: SettingsViewModel, settings: AppSettings) {
    val p = settings.pipeline
    fun change(t: (com.ideaforge.settings.PipelineSettings) -> com.ideaforge.settings.PipelineSettings) =
        vm.update { it.copy(pipeline = t(it.pipeline)) }

    SettingsCard(title = "Stages") {
        SwitchRow("Verify transcript", p.verifyEnabled, { v -> change { it.copy(verifyEnabled = v) } }, "Fix mis-heard words and flag unclear passages")
        SwitchRow("Brainstorm", p.brainstormEnabled, { v -> change { it.copy(brainstormEnabled = v) } }, "Turn what was said into ideas and options")
        SwitchRow("Evaluate", p.evaluateEnabled, { v -> change { it.copy(evaluateEnabled = v) } }, "Score the ideas and build the shortlist")
        SwitchRow("Plan", p.planEnabled, { v -> change { it.copy(planEnabled = v) } }, "Write a plan for each shortlisted idea")
        SwitchRow("Pause after verify", p.pauseAfterVerify, { v -> change { it.copy(pauseAfterVerify = v) } }, "Stop so you can read and fix the transcript first")
        SwitchRow("Start automatically", p.autoStart, { v -> change { it.copy(autoStart = v) } }, "Run the pipeline as soon as a recording stops")
    }
    SettingsCard(title = "Depth") {
        StepperRow("Brainstorm rounds", p.rounds, 1..4, { v -> change { it.copy(rounds = v) } },
            description = "Each extra round feeds the Evaluator's feedback back to the Brainstormer")
        StepperRow("Shortlist size", p.shortlistSize, 1..10, { v -> change { it.copy(shortlistSize = v) } })
        StepperRow("Options per idea", p.optionsPerIdea, 3..25, { v -> change { it.copy(optionsPerIdea = v) } })
    }
    SettingsCard(title = "Glossary") {
        SaveableField(
            value = p.glossary,
            label = "Names and jargon, comma separated",
            onValue = { v -> change { it.copy(glossary = v) } },
            singleLine = false,
            supportingText = "Sent to transcription and the Verifier so these are spelled correctly",
        )
    }
}
