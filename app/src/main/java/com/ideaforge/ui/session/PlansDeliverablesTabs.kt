package com.ideaforge.ui.session

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.core.agents.Plan
import com.ideaforge.core.json.AppJson
import com.ideaforge.core.pipeline.PlanStatus
import com.ideaforge.data.db.DeliverableEntity
import com.ideaforge.data.db.PlanEntity

@Composable
internal fun PlanStatusChip(plan: PlanEntity, modifier: Modifier = Modifier) {
    val status = runCatching { PlanStatus.valueOf(plan.status) }.getOrNull()
    val (color, content) = when (status) {
        PlanStatus.APPROVED, PlanStatus.EXECUTING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        PlanStatus.EXECUTED -> Color(0xFF2E7D32) to Color.White
        PlanStatus.FAILED, PlanStatus.REJECTED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    InfoChip(status?.name ?: plan.status, modifier, color, content)
}

/** Free-text detail that goes with a status: the rejection reason or the execution message. */
internal fun planStatusDetail(plan: PlanEntity): String? = when (plan.status) {
    PlanStatus.REJECTED.name -> plan.rejectReason?.takeIf { it.isNotBlank() }?.let { "Reason: $it" }
    else -> plan.statusMessage?.takeIf { it.isNotBlank() }
}

/** The plan's effective definition: the user's edit if there is one, else the Planner's original. */
internal fun decodePlan(entity: PlanEntity): Plan? =
    runCatching { AppJson.decodeFromString(Plan.serializer(), entity.editedPlanJson ?: entity.planJson) }.getOrNull()

/** Expandable plan details, shared by the session Plans tab and the approval screen. */
@Composable
internal fun PlanDetails(plan: Plan, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabeledText("Objective", plan.objective)
        LabeledText("Estimated time", plan.estimatedTime)
        if (plan.deliverables.isNotEmpty()) {
            SectionLabel("Deliverables")
            plan.deliverables.forEach { Text("- ${it.title} (${typeLabel(it.type)})", style = MaterialTheme.typography.bodyMedium) }
        }
        if (plan.steps.isNotEmpty()) {
            SectionLabel("Steps")
            plan.steps.sortedBy { it.order }.forEach {
                Text(
                    "${it.order}. ${it.description}" + if (it.estimatedTime.isNotBlank()) " (${it.estimatedTime})" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (plan.tools.isNotEmpty()) LabeledText("Tools", plan.tools.joinToString(", "))
        if (plan.dependencies.isNotEmpty()) {
            SectionLabel("Dependencies")
            plan.dependencies.forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) }
        }
        if (plan.risks.isNotEmpty()) {
            SectionLabel("Risks")
            plan.risks.forEach { Text("- ${it.risk}\n   Mitigation: ${it.mitigation}", style = MaterialTheme.typography.bodyMedium) }
        }
        LabeledText("Definition of done", plan.definitionOfDone)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun LabeledText(label: String, value: String) {
    if (value.isBlank()) return
    SectionLabel(label)
    Text(value, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun PlansTab(vm: SessionViewModel, onOpenApproval: () -> Unit, onEditPlan: (Long) -> Unit) {
    val plans by vm.plans.collectAsStateWithLifecycle()
    if (plans.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No plans yet. They appear after the Plan stage.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val pending = plans.count { it.status == PlanStatus.PENDING.name }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (pending > 0) {
            item(key = "review") {
                Button(onClick = onOpenApproval, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review plans ($pending waiting)") }
            }
        }
        items(plans, key = { "plan-${it.id}" }) { plan ->
            PlanCard(plan, onEdit = { onEditPlan(plan.id) }, onRetry = { vm.retryPlan(plan.id) })
        }
    }
}

@Composable
private fun PlanCard(plan: PlanEntity, onEdit: () -> Unit, onRetry: () -> Unit) {
    var expanded by rememberSaveable(plan.id) { mutableStateOf(false) }
    val decoded = remember(plan.id, plan.planJson, plan.editedPlanJson, expanded) { if (expanded) decodePlan(plan) else null }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(plan.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                plan.score?.let { Text("%.1f".format(it), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlanStatusChip(plan)
                InfoChip("Effort: ${effortLabel(plan.effort)}")
            }
            planStatusDetail(plan)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                if (decoded != null) PlanDetails(decoded) else Text("The plan details could not be read.", color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Hide details" else "Show details") }
                if (plan.status == PlanStatus.PENDING.name) {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit") }
                }
                if (plan.status == PlanStatus.FAILED.name) {
                    Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
internal fun DeliverablesTab(vm: SessionViewModel, onOpen: (Long) -> Unit) {
    val deliverables by vm.deliverables.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (deliverables.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No deliverables yet. They appear after you approve a plan and it finishes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val titles = remember(plans) { plans.associate { it.id to it.title } }
    val grouped = remember(deliverables) { deliverables.groupBy { it.planId } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        grouped.forEach { (planId, list) ->
            item(key = "plan-header-$planId") {
                Text(titles[planId] ?: "Plan", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(list, key = { "deliverable-${it.id}" }) { d ->
                DeliverableRow(d, onOpen = { onOpen(d.id) }, onShare = { shareDeliverableFile(context, d.path, d.type, d.title) })
            }
        }
    }
}

@Composable
private fun DeliverableRow(d: DeliverableEntity, onOpen: () -> Unit, onShare: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 14.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(d.title, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip(typeLabel(d.type))
                    if (d.complete) {
                        InfoChip("Complete", color = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                    } else {
                        InfoChip("Needs you", color = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share ${d.title}") }
        }
    }
}
