package com.ideaforge.ui.recording

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.core.pipeline.SessionMode
import com.ideaforge.ui.components.ConfirmDialog
import com.ideaforge.ui.components.EmptyState
import com.ideaforge.ui.components.LevelMeter
import com.ideaforge.ui.components.formatDuration

/** Meetings shorter than this stop immediately; longer ones ask first because the data is hard to redo. */
private const val STOP_CONFIRM_AFTER_MS = 60_000L

/**
 * Live recording screen: timer, level meter, pause/resume and stop. [onFinished] receives the id
 * of the session once the recording has ended (also when it was stopped from the notification).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onBack: () -> Unit,
    onFinished: (Long) -> Unit,
    viewModel: RecordingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    KeepScreenOn()

    LaunchedEffect(Unit) {
        viewModel.finishedSessions.collect { onFinished(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recording") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (!state.isActive) {
            EmptyState(
                icon = Icons.Default.Mic,
                title = "No recording in progress",
                body = "Start a memo or meeting from the home screen.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = if (state.mode == SessionMode.MEETING) "Meeting" else "Memo",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = formatDuration(state.elapsedMs, alwaysHours = true),
                // Tabular digits stop the timer from jittering as the numbers change.
                style = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
                color = if (state.paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            LevelMeter(level = if (state.paused) 0f else state.level)
            Text(
                text = "Chunks saved: ${state.chunkCount}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.paused) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Paused", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        state.pauseReason?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
            state.warning?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Start,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FilledTonalButton(
                    onClick = { if (state.paused) viewModel.resume() else viewModel.pause() },
                    modifier = Modifier.weight(1f).heightIn(min = 80.dp),
                ) {
                    Icon(
                        if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(if (state.paused) "Resume" else "Pause", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = {
                        if (state.mode == SessionMode.MEETING && state.elapsedMs > STOP_CONFIRM_AFTER_MS) confirmStop = true
                        else viewModel.stop()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 80.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(28.dp))
                    Text("Stop", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(
                "Recording continues if you turn the screen off or leave the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (confirmStop) {
        ConfirmDialog(
            title = "Stop recording?",
            text = "The meeting will be saved and processing will start.",
            confirmLabel = "Stop",
            destructive = true,
            onConfirm = { confirmStop = false; viewModel.stop() },
            onDismiss = { confirmStop = false },
        )
    }
}

/**
 * Keeps the display on while this screen is visible so the timer and meter can be watched.
 * This is purely a convenience: the recording itself runs in a foreground service and works
 * with the screen off, so the flag is cleared as soon as the screen leaves composition.
 */
@Composable
private fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
