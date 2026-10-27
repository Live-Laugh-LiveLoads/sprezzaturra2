package com.ideaforge.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Background and foreground for one status chip. */
@Immutable
data class StatusColor(val background: Color, val content: Color)

/**
 * Colours for pipeline stage chips and state badges. All pairs keep a contrast ratio above 4.5:1
 * on the dark surfaces. Look a stage status up by its enum name with [of].
 */
object StatusColors {
    val pending = StatusColor(Color(0xFF3A342D), Color(0xFFD1C4B4))
    val running = StatusColor(Color(0xFF5B3B00), Color(0xFFFFCB80))
    val done = StatusColor(Color(0xFF2F4A22), Color(0xFFBFE3A0))
    val failed = StatusColor(Color(0xFF8C1D18), Color(0xFFFFDAD6))
    val skipped = StatusColor(Color(0xFF24211D), Color(0xFF8F857A))
    val paused = StatusColor(Color(0xFF1F3F5E), Color(0xFFB9DAFF))

    /** Maps a [com.ideaforge.core.pipeline.StageStatus] name (case-insensitive) to its colours; unknown values read as pending. */
    fun of(status: String): StatusColor = when (status.uppercase()) {
        "RUNNING" -> running
        "DONE" -> done
        "FAILED" -> failed
        "SKIPPED" -> skipped
        "PAUSED" -> paused
        else -> pending
    }
}
