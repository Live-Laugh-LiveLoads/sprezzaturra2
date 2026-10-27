package com.ideaforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideaforge.core.pipeline.Stage
import com.ideaforge.data.db.StageRunEntity
import com.ideaforge.ui.theme.StatusColors

/** The single letter shown on each stage's chip: T V R B E P. */
private fun Stage.letter(): String = when (this) {
    Stage.TRANSCRIBE -> "T"
    Stage.VERIFY -> "V"
    Stage.REVIEW -> "R"
    Stage.BRAINSTORM -> "B"
    Stage.EVALUATE -> "E"
    Stage.PLAN -> "P"
}

/**
 * Compact progress strip for a session: one chip per pipeline stage (T V R B E P), coloured by
 * status via [StatusColors]. [stages] is every [StageRunEntity] of the session in any order.
 * BRAINSTORM and EVALUATE repeat per round, so their chip shows the highest round's status and,
 * when that round is above 1, the round number (e.g. "B2"). A stage with no row yet reads as pending.
 *
 * @param compact small 22dp chips for list cards; otherwise 32dp chips with more spacing.
 */
@Composable
fun StageChipsRow(
    stages: List<StageRunEntity>,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val byStage = stages.groupBy { it.stage }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stage.entries.forEach { stage ->
            val run = byStage[stage.name]?.maxByOrNull { it.round }
            val status = run?.status ?: "PENDING"
            val colors = StatusColors.of(status)
            val label = if (run != null && run.round > 1) "${stage.letter()}${run.round}" else stage.letter()
            val size = if (compact) 22.dp else 32.dp
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = size, minHeight = size)
                    .background(colors.background, RoundedCornerShape(if (compact) 6.dp else 8.dp))
                    .padding(horizontal = if (compact) 4.dp else 6.dp)
                    .semantics { contentDescription = "${stage.label}: ${status.lowercase()}" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = colors.content,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
