package com.ideaforge.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.core.transcript.formatTimestamp
import kotlin.math.max

/** Seek step used by the ± buttons. */
internal const val SEEK_STEP_MS = 15_000L

/**
 * Scrubber + elapsed/total labels. This is the only place (with [MiniPlayerBar]) that collects the
 * playback position, so the 4 Hz position updates recompose just this row, never the tab content.
 */
@Composable
internal fun Scrubber(vm: SessionViewModel, totalMs: Long, modifier: Modifier = Modifier) {
    val position by vm.positionMs.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val total = max(totalMs, 1L).toFloat()
    Column(modifier) {
        Slider(
            value = (dragging ?: position.toFloat()).coerceIn(0f, total),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { vm.scrubTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..total,
            enabled = totalMs > 0,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTimestamp((dragging ?: position.toFloat()).toLong()), style = MaterialTheme.typography.labelMedium)
            Text(formatTimestamp(totalMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Play/pause and ±15 s. */
@Composable
internal fun TransportButtons(vm: SessionViewModel, enabled: Boolean, big: Boolean, modifier: Modifier = Modifier) {
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val iconSize = if (big) 40.dp else 28.dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = { vm.seekBy(-SEEK_STEP_MS) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Text("-15s", style = MaterialTheme.typography.labelLarge)
        }
        IconButton(onClick = vm::togglePlay, enabled = enabled, modifier = Modifier.size(if (big) 72.dp else 48.dp)) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(iconSize),
            )
        }
        IconButton(onClick = { vm.seekBy(SEEK_STEP_MS) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Text("+15s", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The persistent player at the bottom of the session screen, shared by all tabs. */
@Composable
internal fun MiniPlayerBar(vm: SessionViewModel, audioDeleted: Boolean, sessionDurationMs: Long) {
    val playableEnd by vm.playableEndMs.collectAsStateWithLifecycle()
    val error by vm.playbackError.collectAsStateWithLifecycle()
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            when {
                audioDeleted -> Text(
                    "Audio deleted (transcripts kept)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
                playableEnd <= 0 -> Text(
                    "No finished audio to play yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
                else -> {
                    error?.let {
                        Text("Playback error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TransportButtons(vm, enabled = true, big = false)
                        Scrubber(vm, totalMs = max(sessionDurationMs, playableEnd), modifier = Modifier.weight(1f).padding(start = 4.dp))
                    }
                }
            }
        }
    }
}
