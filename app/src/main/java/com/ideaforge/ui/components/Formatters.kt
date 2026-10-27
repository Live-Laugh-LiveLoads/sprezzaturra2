package com.ideaforge.ui.components

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats a duration as `m:ss`, or `h:mm:ss` once it reaches an hour. With [alwaysHours] the
 * hour part is always present (`0:05:09`), which keeps a live timer from changing width.
 * Negative values are treated as zero.
 */
fun formatDuration(ms: Long, alwaysHours: Boolean = false): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0 || alwaysHours) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Formats an epoch-millisecond timestamp as a short localized date and time, e.g. "27 Oct 2026, 14:05". */
fun formatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
