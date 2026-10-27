package com.ideaforge.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Tappable chip showing a position in the recording (`m:ss` / `h:mm:ss`), typically used to seek the audio player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimestampChip(ms: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = { Text(formatDuration(ms)) },
        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = "Play from here", modifier = Modifier.size(18.dp)) },
    )
}
