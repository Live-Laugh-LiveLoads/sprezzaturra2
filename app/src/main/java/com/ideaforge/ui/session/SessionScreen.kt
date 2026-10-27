package com.ideaforge.ui.session

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.core.pipeline.PipelineState
import com.ideaforge.core.pipeline.Stage
import com.ideaforge.core.pipeline.StageStatus
import com.ideaforge.data.db.StageRunEntity
import kotlinx.coroutines.launch

private val TAB_TITLES = listOf("Audio", "Transcript", "Ideas", "Evaluation", "Plans", "Deliverables")

/** Stages whose output can be hand-edited (Transcribe and Review have no JSON output to edit). */
private val EDITABLE_STAGES = setOf(Stage.VERIFY, Stage.BRAINSTORM, Stage.EVALUATE, Stage.PLAN)

/**
 * Session detail: title, pipeline strip, state banner, six tabs and a persistent audio player.
 * The player lives in [SessionViewModel], so it keeps playing across tab switches and rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    onBack: () -> Unit,
    onOpenApproval: (Long) -> Unit,
    onEditStage: (Long, String, Int) -> Unit,
    onOpenDeliverable: (Long) -> Unit,
    onEditPlan: (Long) -> Unit,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val vm = viewModel
    val session by vm.session.collectAsStateWithLifecycle()
    val stages by vm.stages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableIntStateOf(vm.initialTab) }
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    // "STAGE:round" of the stage whose sheet is open; saved so rotation keeps it.
    var sheetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var speakerDialogLabel by rememberSaveable { mutableStateOf<String?>(null) }
    val tabState = rememberSaveableStateHolder()

    LaunchedEffect(vm) {
        vm.uiEvents.collect { event ->
            when (event) {
                is SessionEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
                is SessionEvent.Share -> try {
                    context.startActivity(event.intent)
                } catch (e: ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar("No app can handle this file") }
                }
            }
        }
    }

    val current = session
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        current?.title ?: "Session",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = current != null) { showRename = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(24.dp), strokeWidth = 2.dp)
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; showRename = true })
                            DropdownMenuItem(text = { Text("Notes") }, onClick = { menuOpen = false; showNotes = true })
                            DropdownMenuItem(text = { Text("Export Markdown") }, onClick = { menuOpen = false; vm.export(zip = false) })
                            DropdownMenuItem(text = { Text("Export ZIP") }, onClick = { menuOpen = false; vm.export(zip = true) })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; showDelete = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (current != null) MiniPlayerBar(vm, current.audioDeleted, current.durationMs)
        },
    ) { padding ->
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            StateBanner(
                state = current.pipelineState,
                message = current.stateMessage,
                onContinue = vm::continueAfterReview,
                onResume = vm::resume,
                onOpenApproval = { onOpenApproval(vm.sessionId) },
            )
            PipelineStrip(stages) { sheetKey = "${it.stage}:${it.round}" }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                TAB_TITLES.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Each tab keeps its own scroll position while it is off screen.
                tabState.SaveableStateProvider(tab) {
                    when (tab) {
                        0 -> AudioTab(vm, current)
                        1 -> TranscriptTab(vm, onRenameSpeaker = { speakerDialogLabel = it })
                        2 -> IdeasTab(vm)
                        3 -> EvaluationTab(vm)
                        4 -> PlansTab(vm, onOpenApproval = { onOpenApproval(vm.sessionId) }, onEditPlan = onEditPlan)
                        else -> DeliverablesTab(vm, onOpenDeliverable)
                    }
                }
            }
        }
    }

    if (showRename) {
        TextEditDialog(
            title = "Rename session",
            initial = current?.title.orEmpty(),
            label = "Title",
            confirmText = "Rename",
            requireNonBlank = true,
            onDismiss = { showRename = false },
            onConfirm = { vm.rename(it); showRename = false },
        )
    }
    if (showNotes) {
        TextEditDialog(
            title = "Notes",
            initial = current?.userNotes.orEmpty(),
            label = "Your notes",
            confirmText = "Save",
            singleLine = false,
            message = "Your notes are given to the Brainstormer and the Evaluator as extra context " +
                "(goals, constraints, what you care about). They apply the next time those stages run.",
            onDismiss = { showNotes = false },
            onConfirm = { vm.setNotes(it); showNotes = false },
        )
    }
    if (showDelete) {
        ConfirmDialog(
            title = "Delete this session?",
            message = "The recording, transcripts, ideas, plans and deliverables are removed from this phone. This cannot be undone.",
            confirmText = "Delete",
            onDismiss = { showDelete = false },
            onConfirm = { showDelete = false; vm.delete(onDone = onBack) },
        )
    }
    speakerDialogLabel?.let { label ->
        val names by vm.speakerNames.collectAsStateWithLifecycle()
        TextEditDialog(
            title = "Rename speaker",
            initial = names[label] ?: label,
            label = "Name for $label",
            confirmText = "Save",
            requireNonBlank = true,
            onDismiss = { speakerDialogLabel = null },
            onConfirm = { vm.renameSpeaker(label, it); speakerDialogLabel = null },
        )
    }
    sheetKey?.let { key ->
        val record = stages.firstOrNull { "${it.stage}:${it.round}" == key }
        if (record == null) {
            // The stage run vanished (a re-run cleared it): close the sheet outside composition.
            LaunchedEffect(key) { sheetKey = null }
        } else {
            StageSheet(
                record = record,
                onDismiss = { sheetKey = null },
                onRerun = { stage, round -> vm.rerun(stage, round); sheetKey = null },
                onEdit = { stage, round -> sheetKey = null; onEditStage(vm.sessionId, stage.name, round) },
            )
        }
    }
}

/** Banner above the tabs that tells the user what the pipeline needs from them. */
@Composable
private fun StateBanner(
    state: String,
    message: String?,
    onContinue: () -> Unit,
    onResume: () -> Unit,
    onOpenApproval: () -> Unit,
) {
    val parsed = runCatching { PipelineState.valueOf(state) }.getOrNull() ?: return
    when (parsed) {
        PipelineState.RECORDING -> BannerRow("Recording in progress", null, null, {})
        PipelineState.AWAITING_REVIEW -> BannerRow("Review the cleaned transcript, then Continue", message, "Continue", onContinue)
        PipelineState.PAUSED_BUDGET -> BannerRow("Paused: monthly budget reached", message, "Resume", onResume, isProblem = true)
        PipelineState.FAILED -> BannerRow("Something failed", message, "Resume", onResume, isProblem = true)
        PipelineState.WAITING_NETWORK -> BannerRow("Waiting for a network connection", message, "Resume", onResume)
        PipelineState.AWAITING_APPROVAL -> BannerRow("Plans are ready", message, "Review plans", onOpenApproval)
        PipelineState.QUEUED, PipelineState.RUNNING -> Column {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                message ?: if (parsed == PipelineState.QUEUED) "Queued" else "Working",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        PipelineState.COMPLETE -> Unit
    }
}

@Composable
private fun BannerRow(title: String, detail: String?, actionLabel: String?, onAction: () -> Unit, isProblem: Boolean = false) {
    Surface(
        color = if (isProblem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isProblem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (actionLabel != null) {
                Button(onClick = onAction, modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp)) { Text(actionLabel) }
            }
        }
    }
}

private fun stageOrNull(name: String): Stage? = runCatching { Stage.valueOf(name) }.getOrNull()
private fun statusOrNull(name: String): StageStatus? = runCatching { StageStatus.valueOf(name) }.getOrNull()

private fun chipLabel(record: StageRunEntity): String {
    val stage = stageOrNull(record.stage)?.label ?: record.stage
    return if (stageOrNull(record.stage).let { it == Stage.BRAINSTORM || it == Stage.EVALUATE }) "$stage ${record.round}" else stage
}

/** One chip per stage run, coloured by status; tapping opens details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PipelineStrip(stages: List<StageRunEntity>, onClick: (StageRunEntity) -> Unit) {
    if (stages.isEmpty()) return
    val ordered = remember(stages) {
        stages.mapNotNull { r -> stageOrNull(r.stage)?.let { it.position(r.round) to r } }.sortedBy { it.first }.map { it.second }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ordered, key = { "${it.stage}:${it.round}" }) { record ->
            val status = statusOrNull(record.status)
            Surface(
                onClick = { onClick(record) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(statusColor(status))
                    Text(chipLabel(record), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Details and actions for one stage run. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StageSheet(
    record: StageRunEntity,
    onDismiss: () -> Unit,
    onRerun: (Stage, Int) -> Unit,
    onEdit: (Stage, Int) -> Unit,
) {
    val stage = stageOrNull(record.stage)
    val status = statusOrNull(record.status)
    var confirmRerun by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(chipLabel(record), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(statusColor(status))
                Text(status?.name ?: record.status, style = MaterialTheme.typography.titleSmall)
            }
            DetailLine("Model", record.model)
            DetailLine("Prompt version", record.promptVersion)
            DetailLine("Note", record.note)
            DetailLine("Updated", formatDateTime(record.updatedAt))
            if (!record.error.isNullOrBlank()) {
                Text("Error", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                SelectionContainer {
                    Text(
                        record.error,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (stage != null) {
                Button(onClick = { confirmRerun = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Re-run from here") }
                if (stage in EDITABLE_STAGES && status == StageStatus.DONE) {
                    OutlinedButton(onClick = { onEdit(stage, record.round) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Edit output") }
                }
            }
        }
    }
    if (confirmRerun && stage != null) {
        ConfirmDialog(
            title = "Re-run from ${stage.label}?",
            message = "This clears ${stage.label} and every stage after it, then runs them again. Cloud stages may use API credits.",
            confirmText = "Re-run",
            onDismiss = { confirmRerun = false },
            onConfirm = { confirmRerun = false; onRerun(stage, record.round) },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal, modifier = Modifier.weight(0.65f))
    }
}
