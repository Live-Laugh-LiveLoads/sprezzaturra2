package com.ideaforge.ui.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.selection.toggleable
import com.ideaforge.core.pipeline.SessionMode
import com.ideaforge.ui.components.EmptyState
import com.ideaforge.ui.components.PipelineStateBadge
import com.ideaforge.ui.components.StageChipsRow
import com.ideaforge.ui.components.formatDate
import com.ideaforge.ui.components.formatDuration
import java.util.Locale

/**
 * Home: record buttons (or a banner when a recording is running), monthly spend and recent sessions.
 *
 * Starting a recording is a small state machine because it needs several user decisions in order:
 * meeting consent, runtime permissions, a one-time battery-exemption offer. The pending mode and
 * consent time are saved so a rotation in the middle of it does not lose them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenRecording: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val recorder by viewModel.recorder.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val spend by viewModel.spend.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var pendingMode by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConsentAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var showConsent by rememberSaveable { mutableStateOf(false) }
    var showMicRationale by rememberSaveable { mutableStateOf(false) }
    var showBatteryOffer by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    fun startNow() {
        val mode = pendingMode?.let { SessionMode.valueOf(it) } ?: return
        val consentAt = pendingConsentAt
        pendingMode = null
        pendingConsentAt = null
        viewModel.startRecording(mode, consentAt)
    }

    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Whatever the user chose in system settings, carry on with the recording.
        startNow()
    }

    /** Last step before starting: offer the battery exemption once, if not already exempt. */
    fun maybeOfferBattery() {
        if (viewModel.batteryOfferPending() && !isIgnoringBatteryOptimizations(context)) {
            showBatteryOffer = true
        } else {
            startNow()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Notification denial is tolerated: recording still works, only the status notification is hidden.
        if (result[Manifest.permission.RECORD_AUDIO] == true || hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            maybeOfferBattery()
        } else {
            showMicRationale = true
        }
    }

    fun requestPermissionsThenStart() {
        val missing = buildList {
            if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) maybeOfferBattery() else permissionLauncher.launch(missing.toTypedArray())
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importAudio(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                HomeEvent.OpenRecording -> onOpenRecording()
                is HomeEvent.OpenSession -> onOpenSession(event.sessionId)
                is HomeEvent.Error -> snackbar.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Idea Forge") },
                actions = {
                    IconButton(onClick = onOpenSearch) { Icon(Icons.Default.Search, contentDescription = "Search") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Import audio file") },
                                leadingIcon = { Icon(Icons.Default.AudioFile, contentDescription = null) },
                                enabled = !busy,
                                onClick = {
                                    menuOpen = false
                                    importLauncher.launch(arrayOf("audio/*"))
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                item(key = "busy") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            item(key = "record") {
                if (recorder.isActive) {
                    ActiveRecordingBanner(
                        isMeeting = recorder.mode == SessionMode.MEETING,
                        elapsedMs = recorder.elapsedMs,
                        paused = recorder.paused,
                        onClick = onOpenRecording,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RecordButton(
                            label = "Record memo",
                            icon = Icons.Default.Mic,
                            enabled = !busy,
                            onClick = {
                                pendingMode = SessionMode.MEMO.name
                                pendingConsentAt = null
                                requestPermissionsThenStart()
                            },
                        )
                        RecordButton(
                            label = "Record meeting",
                            icon = Icons.Default.Groups,
                            enabled = !busy,
                            onClick = { showConsent = true },
                        )
                    }
                }
            }
            item(key = "spend") { SpendLine(spend) }
            item(key = "recent-header") {
                Text("Recent sessions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            }
            val rows = sessions
            when {
                rows == null -> Unit
                rows.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.Default.Mic,
                        title = "No sessions yet",
                        body = "Record a memo or a meeting, or import an audio file. Ideas and plans will show up here.",
                    )
                }
                else -> items(rows, key = { it.session.id }) { row ->
                    SessionCard(row, onClick = { onOpenSession(row.session.id) })
                }
            }
        }
    }

    if (showConsent) {
        MeetingConsentDialog(
            onConfirm = {
                showConsent = false
                // The moment of confirmation is what gets stored as the consent time.
                pendingMode = SessionMode.MEETING.name
                pendingConsentAt = System.currentTimeMillis()
                requestPermissionsThenStart()
            },
            onDismiss = { showConsent = false },
        )
    }

    if (showMicRationale) {
        val activity = context as? Activity
        val canAskAgain = activity != null &&
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        AlertDialog(
            onDismissRequest = { showMicRationale = false; pendingMode = null; pendingConsentAt = null },
            title = { Text("Microphone access needed") },
            text = {
                Text(
                    "Idea Forge cannot record without the microphone permission. Audio is recorded on this phone " +
                        "and only leaves it when you transcribe or process a session with the services you configured.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMicRationale = false
                    if (canAskAgain) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    } else {
                        // Denied permanently: the system dialog will not appear, so send the user to app settings.
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }
                }) { Text(if (canAskAgain) "Grant access" else "Open app settings") }
            },
            dismissButton = {
                TextButton(onClick = { showMicRationale = false; pendingMode = null; pendingConsentAt = null }) { Text("Cancel") }
            },
        )
    }

    if (showBatteryOffer) {
        AlertDialog(
            onDismissRequest = { /* must choose explicitly */ },
            title = { Text("Keep long recordings alive") },
            text = {
                Text(
                    "Long meetings need Idea Forge to be exempt from battery optimisation, otherwise the phone may " +
                        "stop the recording after a while with the screen off. You will only be asked once.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryOffer = false
                    viewModel.markBatteryOffered()
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    try {
                        batteryLauncher.launch(intent)
                    } catch (e: Exception) {
                        // Some devices lack this screen; carry on without the exemption.
                        startNow()
                    }
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBatteryOffer = false
                    viewModel.markBatteryOffered()
                    startNow()
                }) { Text("Not now") }
            },
        )
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
private fun RecordButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ActiveRecordingBanner(isMeeting: Boolean, elapsedMs: Long, paused: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (paused) "Recording paused" else if (isMeeting) "Recording meeting" else "Recording memo",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    formatDuration(elapsedMs, alwaysHours = true) + "  •  tap to open",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun SpendLine(spend: SpendInfo) {
    val over = spend.capUsd > 0 && spend.spentUsd >= spend.capUsd
    val text = if (spend.capUsd > 0) {
        String.format(Locale.US, "Monthly spend $%.2f of $%.2f", spend.spentUsd, spend.capUsd)
    } else {
        String.format(Locale.US, "Monthly spend $%.2f (no cap)", spend.spentUsd)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SessionCard(row: SessionRow, onClick: () -> Unit) {
    val session = row.session
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = if (session.mode == SessionMode.MEETING.name) Icons.Default.Groups else Icons.Default.Mic,
                    contentDescription = if (session.mode == SessionMode.MEETING.name) "Meeting" else "Memo",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        session.title.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatDate(session.createdAt) + "  •  " + formatDuration(session.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PipelineStateBadge(state = session.pipelineState, message = session.stateMessage)
            StageChipsRow(stages = row.stages, compact = true)
        }
    }
}

/** Meeting recording needs an explicit, affirmative consent step; the Start button stays disabled until the box is ticked. */
@Composable
private fun MeetingConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var agreed by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record a meeting") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Recording people without their knowledge may be illegal where you are. Confirm before you start.")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(value = agreed, role = Role.Checkbox, onValueChange = { agreed = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = agreed, onCheckedChange = null)
                    Text(
                        "Everyone present has agreed to be recorded",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = agreed, colors = ButtonDefaults.buttonColors()) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
