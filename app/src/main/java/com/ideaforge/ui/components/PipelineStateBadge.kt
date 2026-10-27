package com.ideaforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ideaforge.ui.theme.StatusColor
import com.ideaforge.ui.theme.StatusColors

/** Human label and colours for a [com.ideaforge.core.pipeline.PipelineState] name. */
private fun describeState(state: String): Pair<String, StatusColor> = when (state) {
    "RECORDING" -> "Recording" to StatusColors.failed
    "QUEUED" -> "Queued" to StatusColors.pending
    "RUNNING" -> "Processing" to StatusColors.running
    "WAITING_NETWORK" -> "Waiting for network" to StatusColors.paused
    "AWAITING_REVIEW" -> "Needs review" to StatusColors.paused
    "AWAITING_APPROVAL" -> "Needs approval" to StatusColors.paused
    "PAUSED_BUDGET" -> "Paused: budget" to StatusColors.paused
    "FAILED" -> "Failed" to StatusColors.failed
    "COMPLETE" -> "Complete" to StatusColors.done
    else -> state.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } to StatusColors.pending
}

/**
 * Pill showing a session's overall pipeline state ([state] is a `PipelineState` name). When
 * [message] (the session's `stateMessage`) is not blank it appears under the pill, up to two lines.
 */
@Composable
fun PipelineStateBadge(
    state: String,
    message: String?,
    modifier: Modifier = Modifier,
) {
    val (label, colors) = describeState(state)
    Column(modifier = modifier) {
        Text(
            text = label,
            color = colors.content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(colors.background, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
