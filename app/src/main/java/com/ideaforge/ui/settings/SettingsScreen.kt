package com.ideaforge.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ideaforge.BuildConfig

/** The nine settings sections, in display order. [id] is the navigation argument. */
internal enum class SettingsSection(val id: String, val title: String, val description: String) {
    PROVIDERS("providers", "AI providers", "Endpoints, API keys and connection tests"),
    AGENTS("agents", "Agents", "Which model each of the five agents uses"),
    PROMPTS("prompts", "Prompts", "View and edit the instructions each agent receives"),
    PIPELINE("pipeline", "Pipeline", "Stages, rounds, shortlist size and glossary"),
    RECORDING("recording", "Recording", "Chunk length, bitrate, microphone and battery limits"),
    TRANSCRIPTION("transcription", "Transcription", "Cloud or on-device speech to text"),
    COSTS("costs", "Costs", "Monthly cap, month-to-date spend and price table"),
    DATA("data", "Data", "Audio retention, backup, restore and audio import"),
    NOTIFICATIONS("notifications", "Notifications", "Which events notify you");

    companion object {
        fun fromId(id: String?): SettingsSection? = entries.firstOrNull { it.id == id }
    }
}

/** Top-level settings list; each row opens one section through [onOpenSection]. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenSection: (String) -> Unit) {
    SettingsScaffold(title = "Settings", onBack = onBack) {
        SettingsSection.entries.forEach { section ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpenSection(section.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium)
                        HelpText(section.description)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
        HelpText(
            "Idea Forge ${BuildConfig.VERSION_NAME}",
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
