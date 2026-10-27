package com.ideaforge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.settings.AppSettings
import com.ideaforge.settings.SecretStore
import com.ideaforge.settings.SettingsRepository
import com.ideaforge.settings.TranscriptionEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

internal data class OnboardingState(
    val providerId: String = AppSettings.XAI,
    val model: String = AppSettings.DEFAULT_MODEL,
    val llmKey: String = "",
    /** Index into [SettingsPresets.TRANSCRIPTION], or [ON_DEVICE]. */
    val transcriptionChoice: Int = 0,
    val transcriptionKey: String = "",
    val capText: String = "10",
) {
    companion object {
        const val ON_DEVICE = -1
    }
}

/**
 * Holds what the user typed during first-run setup (surviving rotation) and writes it to
 * [SettingsRepository] / [SecretStore]. Keys are saved when the user moves to the next step, so
 * skipping later never loses them.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: SettingsRepository,
    private val secrets: SecretStore,
) : ViewModel() {

    private val stepState = savedState.getStateFlow("step", 0)
    internal val step: StateFlow<Int> = stepState
    private val handle = savedState

    private val _state = MutableStateFlow(OnboardingState())
    internal val state: StateFlow<OnboardingState> = _state

    internal val profiles get() = repo.current.llmProfiles

    internal fun edit(f: (OnboardingState) -> OnboardingState) = _state.update(f)

    fun back() {
        handle["step"] = (stepState.value - 1).coerceAtLeast(0)
    }

    /** Saves the current step's input and moves on. */
    fun next() {
        save(stepState.value)
        handle["step"] = (stepState.value + 1).coerceAtMost(LAST_STEP)
    }

    fun finish() {
        save(LAST_STEP)
        repo.update { it.copy(onboardingDone = true) }
    }

    fun skip() {
        repo.update { it.copy(onboardingDone = true) }
    }

    private fun save(step: Int) {
        val s = _state.value
        when (step) {
            0 -> {
                if (s.llmKey.isNotBlank()) secrets.set(s.providerId, s.llmKey)
                // Agents default to xAI; another provider needs its own profile and a model id it knows.
                if (s.providerId != AppSettings.XAI && s.model.isNotBlank()) {
                    repo.update { cur ->
                        cur.copy(agentModels = cur.agentModels.mapValues { (_, m) -> m.copy(profileId = s.providerId, model = s.model.trim()) })
                    }
                }
            }
            1 -> {
                if (s.transcriptionChoice == OnboardingState.ON_DEVICE) {
                    repo.update { it.copy(transcription = it.transcription.copy(engine = TranscriptionEngine.ON_DEVICE)) }
                } else {
                    val preset = SettingsPresets.TRANSCRIPTION.getOrNull(s.transcriptionChoice) ?: return
                    repo.update { it.copy(transcription = preset.applyTo(it.transcription).copy(engine = TranscriptionEngine.CLOUD)) }
                    if (s.transcriptionKey.isNotBlank()) secrets.set(AppSettings.TRANSCRIPTION_KEY, s.transcriptionKey)
                }
            }
            else -> {
                s.capText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { cap ->
                    repo.update { it.copy(cost = it.cost.copy(monthlyCapUsd = cap)) }
                }
            }
        }
    }

    private companion object {
        const val LAST_STEP = 2
    }
}

/** Three short first-run steps: LLM provider, transcription, monthly cap plus consent note. */
@Composable
fun OnboardingScreen(onDone: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Set up Idea Forge", style = MaterialTheme.typography.headlineSmall)
            HelpText("Step ${step + 1} of 3. You can change everything later in Settings.")

            when (step) {
                0 -> LlmStep(viewModel, state)
                1 -> TranscriptionStep(viewModel, state)
                else -> CapStep(viewModel, state)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 0) {
                    OutlinedButton(onClick = viewModel::back, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                }
                if (step < 2) {
                    Button(onClick = viewModel::next, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next") }
                } else {
                    Button(
                        onClick = {
                            viewModel.finish()
                            onDone()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Finish") }
                }
            }
            TextButton(
                onClick = {
                    viewModel.skip()
                    onDone()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Skip setup") }
        }
    }
}

@Composable
private fun LlmStep(vm: OnboardingViewModel, state: OnboardingState) {
    val profiles = vm.profiles
    val selected = profiles.firstOrNull { it.id == state.providerId }
    SettingsCard(title = "1. AI provider") {
        HelpText("Idea Forge sends your transcript to a language model to clean it up, brainstorm and plan. xAI Grok is the default.")
        ChoiceChips(
            options = profiles.map { it.id to it.name },
            selected = state.providerId,
            onSelect = { id -> vm.edit { it.copy(providerId = id) } },
        )
        if (state.providerId != AppSettings.XAI) {
            OutlinedTextField(
                value = state.model,
                onValueChange = { v -> vm.edit { it.copy(model = v) } },
                label = { Text("Model id for ${selected?.name ?: "this provider"}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("All five agents will use it; refine per agent in Settings") },
            )
        }
        val keyUrl = SettingsPresets.keyUrl(state.providerId)
        if (keyUrl != null) LinkText("Get a key at $keyUrl", "https://$keyUrl")
        else HelpText("Local endpoints such as Ollama need no key.")
        SecretField("API key", state.llmKey) { v -> vm.edit { it.copy(llmKey = v) } }
    }
}

@Composable
private fun TranscriptionStep(vm: OnboardingViewModel, state: OnboardingState) {
    SettingsCard(title = "2. Transcription") {
        HelpText("Recordings are turned into text by a speech model. Cloud is fast and accurate; on-device keeps audio on the phone but is slow.")
        ChoiceChips(
            options = SettingsPresets.TRANSCRIPTION.mapIndexed { i, p -> i to p.label } + (OnboardingState.ON_DEVICE to "On-device"),
            selected = state.transcriptionChoice,
            onSelect = { c -> vm.edit { it.copy(transcriptionChoice = c) } },
        )
        if (state.transcriptionChoice == OnboardingState.ON_DEVICE) {
            HelpText("Slow on older phones: roughly real-time for tiny.en, 2-3x slower for base.en. Download a model in Settings > Transcription.")
        } else {
            val url = SettingsPresets.TRANSCRIPTION.getOrNull(state.transcriptionChoice)?.keyUrl
            if (url != null) LinkText("Get a key at $url", "https://$url")
            SecretField("Transcription API key", state.transcriptionKey) { v -> vm.edit { it.copy(transcriptionKey = v) } }
        }
    }
}

@Composable
private fun CapStep(vm: OnboardingViewModel, state: OnboardingState) {
    SettingsCard(title = "3. Monthly spending cap") {
        HelpText("The pipeline pauses when this month's estimated cost reaches the cap. Enter 0 for no cap.")
        OutlinedTextField(
            value = state.capText,
            onValueChange = { v -> vm.edit { it.copy(capText = v) } },
            label = { Text("Cap in USD") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = state.capText.trim().replace(',', '.').toDoubleOrNull()?.let { it < 0 } ?: true,
        )
    }
    SettingsCard(title = "Recording other people") {
        Text(
            "Illinois and several other states require every party's consent to record a conversation. " +
                "In Meeting mode the app asks you to confirm that everyone has agreed, and saves when you confirmed. " +
                "You are responsible for following the law where you are.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SecretField(label: String, value: String, onValue: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide key" else "Show key",
                )
            }
        },
        supportingText = { Text("Stored encrypted on this phone. Leave empty to add it later.") },
    )
}

@Composable
private fun LinkText(text: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        text,
        modifier = Modifier.heightIn(min = 48.dp).clickable { runCatching { uriHandler.openUri(url) } },
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        style = MaterialTheme.typography.bodyMedium,
    )
}
