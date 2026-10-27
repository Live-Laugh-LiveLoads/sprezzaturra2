package com.ideaforge.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideaforge.core.pipeline.StageStatus
import com.ideaforge.core.transcript.formatTimestamp
import java.text.DateFormat
import java.util.Date

/*
 * Small pieces shared by the session tabs. They are private to this package on purpose so the
 * session UI does not depend on files that other workers may still be changing.
 */

internal fun formatDateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

internal fun formatClock(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(epochMs))

/** Green is not part of the Material scheme, so "done" gets a fixed colour that reads on dark surfaces. */
private val DoneGreen = Color(0xFF66BB6A)
private val RunningAmber = Color(0xFFFFB74D)

@Composable
internal fun statusColor(status: StageStatus?): Color = when (status) {
    StageStatus.DONE -> DoneGreen
    StageStatus.RUNNING -> RunningAmber
    StageStatus.FAILED -> MaterialTheme.colorScheme.error
    StageStatus.SKIPPED -> MaterialTheme.colorScheme.outline
    StageStatus.PENDING, null -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** A tappable timestamp that seeks the audio; the caller passes the session time in milliseconds. */
@Composable
internal fun SeekChip(ms: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = { onSeek(ms) },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.minimumInteractiveComponentSize(),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play from", modifier = Modifier.size(14.dp))
            Text(formatTimestamp(ms), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}

/** Non-interactive label chip. */
@Composable
internal fun InfoChip(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceVariant, content: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(shape = RoundedCornerShape(50), color = color, contentColor = content, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = color, modifier = modifier.size(10.dp)) {}
}

/** Horizontal chip row to pick a brainstorm round. */
@Composable
internal fun RoundSelector(rounds: List<Int>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Round", style = MaterialTheme.typography.labelLarge)
        rounds.forEach { r ->
            FilterChip(selected = r == selected, onClick = { onSelect(r) }, label = { Text("$r") })
        }
    }
}

internal fun effortLabel(effort: String): String = effort.replace('_', ' ').replaceFirstChar { it.uppercase() }

internal fun typeLabel(type: String): String = type.replace('_', ' ').replaceFirstChar { it.uppercase() }
