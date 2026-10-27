package com.ideaforge.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * One settings section, chosen by the `section` navigation argument. Every control writes to
 * [SettingsViewModel] immediately; there is no Save button except for API keys and prompts.
 */
@Composable
fun SettingsSectionScreen(
    onBack: () -> Unit,
    onEditPrompt: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val section = SettingsSection.fromId(viewModel.section)

    SettingsScaffold(title = section?.title ?: "Settings", onBack = onBack) {
        when (section) {
            SettingsSection.PROVIDERS -> ProvidersSection(viewModel, settings)
            SettingsSection.AGENTS -> AgentsSection(viewModel, settings)
            SettingsSection.PROMPTS -> PromptsSection(viewModel, onEditPrompt)
            SettingsSection.PIPELINE -> PipelineSection(viewModel, settings)
            SettingsSection.RECORDING -> RecordingSection(viewModel, settings)
            SettingsSection.TRANSCRIPTION -> TranscriptionSection(viewModel, settings)
            SettingsSection.COSTS -> CostsSection(viewModel, settings)
            SettingsSection.DATA -> DataSection(viewModel, settings)
            SettingsSection.NOTIFICATIONS -> NotificationsSection(viewModel, settings)
            null -> HelpText("Unknown settings section \"${viewModel.section}\".")
        }
    }
}
