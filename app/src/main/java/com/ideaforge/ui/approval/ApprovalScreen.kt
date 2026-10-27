package com.ideaforge.ui.approval

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.core.pipeline.PlanStatus
import com.ideaforge.data.db.PlanEntity
import com.ideaforge.ui.session.InfoChip
import com.ideaforge.ui.session.PlanDetails
import com.ideaforge.ui.session.PlanStatusChip
import com.ideaforge.ui.session.TextEditDialog
import com.ideaforge.ui.session.decodePlan
import com.ideaforge.ui.session.effortLabel
import com.ideaforge.ui.session.planStatusDetail
import kotlin.math.roundToInt

/**
 * "Plans ready" landing screen: approve, reject or edit each pending plan, approve in bulk by
 * selection or by score threshold, and browse approved and archived plans.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalScreen(
    onBack: () -> Unit,
    onEditPlan: (Long) -> Unit,
    viewModel: ApprovalViewModel = hiltViewModel(),
) {
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle(initialValue = emptySet())
    val threshold by viewModel.threshold.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var rejectingId by rememberSaveable { mutableLongStateOf(-1L) }
    var doneOpen by rememberSaveable { mutableStateOf(false) }
    var archiveOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showSnackbar(it) } }

    val all = plans
    val pending = all?.filter { it.status == ApprovalViewModel.PENDING }.orEmpty()
    val selectedPending = pending.filter { it.id in selected }
    val done = all?.filter { it.status != ApprovalViewModel.PENDING && it.status != ApprovalViewModel.REJECTED }.orEmpty()
    val archived = all?.filter { it.status == ApprovalViewModel.REJECTED }.orEmpty()
    val matching = pending.count { (it.score ?: 0.0) >= threshold }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plans ready") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selectedPending.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { viewModel.approve(selectedPending.map { it.id }) },
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).heightIn(min = 48.dp),
                    ) { Text("Approve selected (${selectedPending.size})") }
                }
            }
        },
    ) { padding ->
        if (all == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (pending.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nothing is waiting for your approval", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "New plans appear here after the Planner runs. Approved and archived plans are listed below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item(key = "threshold") {
                    ThresholdCard(threshold, matching, onChange = viewModel::setThreshold, onApprove = { viewModel.approveAbove(threshold.toDouble()) })
                }
                items(pending, key = { "pending-${it.id}" }) { plan ->
                    PendingPlanCard(
                        plan = plan,
                        checked = plan.id in selected,
                        onCheck = { viewModel.toggleSelected(plan.id) },
                        onApprove = { viewModel.approve(listOf(plan.id)) },
                        onReject = { rejectingId = plan.id },
                        onEdit = { onEditPlan(plan.id) },
                    )
                }
            }
            if (done.isNotEmpty()) {
                item(key = "done-header") { SectionHeader("Approved, executing and executed (${done.size})", doneOpen) { doneOpen = !doneOpen } }
                if (doneOpen) {
                    items(done, key = { "done-${it.id}" }) { plan -> DonePlanCard(plan, onRetry = { viewModel.retry(plan.id) }) }
                }
            }
            if (archived.isNotEmpty()) {
                item(key = "archive-header") { SectionHeader("Archived (rejected) (${archived.size})", archiveOpen) { archiveOpen = !archiveOpen } }
                if (archiveOpen) {
                    items(archived, key = { "archived-${it.id}" }) { plan -> ArchivedPlanCard(plan, onRestore = { viewModel.restore(plan.id) }) }
                }
            }
        }
    }

    if (rejectingId >= 0) {
        val id = rejectingId
        TextEditDialog(
            title = "Reject this plan?",
            initial = "",
            label = "Reason (required)",
            confirmText = "Reject",
            singleLine = false,
            requireNonBlank = true,
            message = "The plan moves to Archived. Your reason helps you remember why, and you can restore it later.",
            onDismiss = { rejectingId = -1L },
            onConfirm = { viewModel.reject(id, it); rejectingId = -1L },
        )
    }
}

@Composable
private fun SectionHeader(title: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ThresholdCard(threshold: Float, matching: Int, onChange: (Float) -> Unit, onApprove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Approve all scoring at least %.1f".format(threshold), style = MaterialTheme.typography.titleSmall)
            Slider(
                value = threshold,
                onValueChange = { onChange((it * 2).roundToInt() / 2f) },
                valueRange = 1f..10f,
                steps = 17, // 1.0, 1.5 ... 10.0
            )
            Button(onClick = onApprove, enabled = matching > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (matching == 0) "No plan reaches this score" else "Approve $matching plan${if (matching == 1) "" else "s"}")
            }
        }
    }
}

@Composable
private fun PendingPlanCard(
    plan: PlanEntity,
    checked: Boolean,
    onCheck: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit,
) {
    var expanded by rememberSaveable(plan.id) { mutableStateOf(false) }
    val decoded = remember(plan.id, plan.planJson, plan.editedPlanJson, expanded) { if (expanded) decodePlan(plan) else null }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 4.dp, end = 14.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { onCheck() })
                Text(plan.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                plan.score?.let { Text("%.1f".format(it), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            }
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoChip("Effort: ${effortLabel(plan.effort)}")
                Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
                if (expanded) {
                    if (decoded != null) PlanDetails(decoded) else Text("The plan details could not be read.", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Hide details" else "Show details") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApprove, modifier = Modifier.heightIn(min = 48.dp)) { Text("Approve") }
                    OutlinedButton(onClick = onReject, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reject") }
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Edit, then approve") }
            }
        }
    }
}

@Composable
private fun DonePlanCard(plan: PlanEntity, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(plan.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                plan.score?.let { Text("%.1f".format(it), style = MaterialTheme.typography.titleMedium) }
            }
            PlanStatusChip(plan)
            planStatusDetail(plan)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (plan.status == PlanStatus.FAILED.name) {
                Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun ArchivedPlanCard(plan: PlanEntity, onRestore: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(plan.title, style = MaterialTheme.typography.titleSmall)
            Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
            planStatusDetail(plan)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedButton(onClick = onRestore, modifier = Modifier.heightIn(min = 48.dp)) { Text("Restore") }
        }
    }
}
