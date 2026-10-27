package com.ideaforge.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.core.pipeline.SessionMode
import com.ideaforge.core.transcript.formatTimestamp
import com.ideaforge.data.db.SessionEntity
import com.ideaforge.data.db.SessionEventTypes
import kotlin.math.max

/** Big scrubber, chunk list and the event timeline (including the meeting consent confirmation). */
@Composable
internal fun AudioTab(vm: SessionViewModel, session: SessionEntity) {
    val chunks by vm.chunks.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val playableEnd by vm.playableEndMs.collectAsStateWithLifecycle()
    val consentAt = session.consentConfirmedAt
        ?: events.firstOrNull { it.type == SessionEventTypes.CONSENT_CONFIRMED }?.atEpochMs

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (session.mode == SessionMode.MEETING.name) {
            item(key = "consent") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Recording consent", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (consentAt != null) "Everyone present was confirmed as agreeing to be recorded on ${formatDateTime(consentAt)}."
                            else "No consent confirmation was logged for this meeting.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item(key = "player") {
            Card {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (session.audioDeleted) {
                        Text("Audio deleted (transcripts kept)", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Scrubber(vm, totalMs = max(session.durationMs, playableEnd), modifier = Modifier.fillMaxWidth())
                        TransportButtons(vm, enabled = playableEnd > 0, big = true)
                    }
                }
            }
        }
        item(key = "chunks-header") { Text("Chunks", style = MaterialTheme.typography.titleMedium) }
        if (chunks.isEmpty()) {
            item(key = "chunks-empty") { Text("No audio chunks.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(chunks, key = { "chunk-${it.id}" }) { chunk ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("#${chunk.chunkIndex + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(0.15f))
                Column(Modifier.weight(0.55f)) {
                    Text("Starts ${formatTimestamp(chunk.startOffsetMs)}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (chunk.finalized) "Length ${formatTimestamp(chunk.durationMs)}" else "Still being written",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (chunk.transcriptJson != null) "Transcribed" else "Not transcribed",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chunk.transcriptJson != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.3f),
                )
            }
        }
        item(key = "events-header") {
            HorizontalDivider()
            Text("Timeline", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        }
        if (events.isEmpty()) {
            item(key = "events-empty") { Text("No events logged.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(events, key = { "event-${it.id}" }) { e ->
            val isConsent = e.type == SessionEventTypes.CONSENT_CONFIRMED
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(formatClock(e.atEpochMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.25f))
                Text(
                    e.message,
                    style = if (isConsent) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (e.type == SessionEventTypes.WARNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.75f),
                )
            }
        }
    }
}
