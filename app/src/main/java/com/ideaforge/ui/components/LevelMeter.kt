package com.ideaforge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/**
 * Horizontal audio level bar. [level] is 0..1 (clamped); changes are animated over 90 ms so the
 * bar moves smoothly even though the recorder only publishes a few levels per second.
 */
@Composable
fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 90),
        label = "levelMeter",
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val hot = MaterialTheme.colorScheme.error
    Canvas(modifier = modifier.fillMaxWidth().height(20.dp)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, size = size, cornerRadius = radius)
        val w = size.width * animated
        if (w > 0f) {
            // Turn red near clipping so a too-loud source is visible at a glance.
            drawRoundRect(
                color = if (animated > 0.92f) hot else fill,
                topLeft = Offset.Zero,
                size = Size(maxOf(w, size.height), size.height),
                cornerRadius = radius,
            )
        }
    }
}
