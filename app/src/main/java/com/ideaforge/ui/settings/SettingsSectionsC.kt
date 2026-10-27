package com.ideaforge.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.core.llm.ModelPrice
import com.ideaforge.core.llm.PriceTable
import com.ideaforge.settings.AppSettings
import com.ideaforge.settings.CostSettings
import com.ideaforge.settings.NotificationSettings
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

// ---- costs -----------------------------------------------------------------------------------

/** Shortest plain-decimal text for a price, e.g. 0.2 instead of 0.20000000000000001. */
internal fun formatPrice(v: Double): String = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

@Composable
internal fun CostsSection(vm: SettingsViewModel, settings: AppSettings) {
    val spent by vm.monthToDate.collectAsStateWithLifecycle()
    val cap = settings.cost.monthlyCapUsd
    var resetCount by rememberSaveable { mutableIntStateOf(0) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    fun change(f: (CostSettings) -> CostSettings) = vm.update { it.copy(cost = f(it.cost)) }

    SettingsCard(title = "This month") {
        Text("Spent so far: $${"%.4f".format(Locale.US, spent)}", style = MaterialTheme.typography.titleMedium)
        if (cap > 0) {
            LinearProgressIndicator(progress = { (spent / cap).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            HelpText("of the $${"%.2f".format(Locale.US, cap)} cap")
        } else {
            HelpText("No cap is set.")
        }
        NumberField(
            value = formatPrice(cap),
            label = "Monthly cap (USD, 0 = no cap)",
            onNumber = { v -> change { it.copy(monthlyCapUsd = v) } },
            modifier = Modifier.fillMaxWidth(),
            supportingText = "The pipeline pauses when the cap is reached; raise it to continue",
        )
    }

    SettingsCard(title = "Price table") {
        HelpText("USD per million tokens (chat models) or per audio minute (transcription). Costs are estimates from these numbers; models not listed count as free, so add them here.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add model") }
            TextButton(
                onClick = {
                    change { it.copy(priceOverrides = emptyMap()) }
                    resetCount++
                },
                enabled = settings.cost.priceOverrides.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Reset overrides") }
        }
    }
    val merged = (PriceTable.DEFAULTS + settings.cost.priceOverrides).toSortedMap()
    merged.forEach { (model, price) ->
        key(model) {
            PriceCard(model, price, edited = model in settings.cost.priceOverrides, resetKey = resetCount) { newPrice ->
                change { it.copy(priceOverrides = it.priceOverrides + (model to newPrice)) }
            }
        }
    }

    if (showAdd) {
        var id by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add model price") },
            text = {
                OutlinedTextField(
                    id, { id = it }, label = { Text("Model id") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Matched by prefix, case-insensitive") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = id.isNotBlank(),
                    onClick = {
                        val model = id.trim().lowercase()
                        change { it.copy(priceOverrides = it.priceOverrides + (model to (merged[model] ?: ModelPrice()))) }
                        id = ""
                        showAdd = false
                    },
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PriceCard(model: String, price: ModelPrice, edited: Boolean, resetKey: Int, onChange: (ModelPrice) -> Unit) {
    SettingsCard(title = if (edited) "$model (edited)" else model) {
        // The three fields share one ModelPrice, so each edit starts from the latest saved value.
        var current by remember(resetKey) { mutableStateOf(price) }
        fun commit(next: ModelPrice) {
            current = next
            onChange(next)
        }
        NumberField(formatPrice(price.inputPerMillion), "Input $ per 1M tokens", { v -> commit(current.copy(inputPerMillion = v)) },
            Modifier.fillMaxWidth(), resetKey)
        NumberField(formatPrice(price.outputPerMillion), "Output $ per 1M tokens", { v -> commit(current.copy(outputPerMillion = v)) },
            Modifier.fillMaxWidth(), resetKey)
        NumberField(formatPrice(price.perAudioMinute), "$ per audio minute", { v -> commit(current.copy(perAudioMinute = v)) },
            Modifier.fillMaxWidth(), resetKey)
    }
}

// ---- data ------------------------------------------------------------------------------------

@Composable
internal fun DataSection(vm: SettingsViewModel, settings: AppSettings) {
    val op by vm.op.collectAsStateWithLifecycle()
    var pendingRestore by rememberSaveable { mutableStateOf<String?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.backupTo(uri)
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri.toString()
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri)
    }
    val busy = op is DataOp.Working

    SettingsCard(title = "Audio retention") {
        SaveableField(
            value = settings.data.deleteAudioAfterDays.toString(),
            label = "Delete audio after (days, 0 = never)",
            onValue = { v ->
                v.trim().toIntOrNull()?.takeIf { it in 0..3650 }?.let { days ->
                    vm.update { it.copy(data = it.data.copy(deleteAudioAfterDays = days)) }
                }
            },
            keyboardType = KeyboardType.Number,
            supportingText = "Transcripts, ideas, plans and deliverables are kept; only the recording is removed",
        )
    }

    SettingsCard(title = "Backup and restore") {
        HelpText("A backup contains all sessions, audio, deliverables, custom prompts and settings. API keys are NOT included because they are tied to this phone's secure storage; enter them again after restoring.")
        Button(
            onClick = { backupLauncher.launch("idea-forge-backup-${LocalDate.now()}.zip") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Back up to file") }
        OutlinedButton(
            onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Restore from file") }
        HelpText("Restore replaces everything on this phone and restarts the app.")
    }

    SettingsCard(title = "Import audio") {
        HelpText("Transcribe a recording made elsewhere. It becomes a new session.")
        OutlinedButton(
            onClick = { audioPicker.launch(arrayOf("audio/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Import audio file") }
    }

    when (val o = op) {
        DataOp.Idle -> {}
        is DataOp.Working -> SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Text(o.label)
            }
        }
        is DataOp.Done -> SettingsCard {
            Text(o.message)
            TextButton(onClick = vm::dismissOp, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
        }
        is DataOp.Failed -> SettingsCard {
            Text(o.message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::dismissOp, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
        }
    }

    pendingRestore?.let { uriText ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Replace everything?") },
            text = {
                Text("Restoring overwrites all sessions, audio, deliverables, prompts and settings on this phone with the contents of the backup. This cannot be undone. API keys must be entered again. The app restarts afterwards.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    vm.restoreFrom(Uri.parse(uriText))
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
}

// ---- notifications ---------------------------------------------------------------------------

@Composable
internal fun NotificationsSection(vm: SettingsViewModel, settings: AppSettings) {
    val n = settings.notifications
    val context = LocalContext.current
    fun change(f: (NotificationSettings) -> NotificationSettings) = vm.update { it.copy(notifications = f(it.notifications)) }

    SettingsCard(title = "Notify me when") {
        SwitchRow("Transcription finished", n.transcriptionFinished, { v -> change { it.copy(transcriptionFinished = v) } })
        SwitchRow("Plans ready for approval", n.plansReady, { v -> change { it.copy(plansReady = v) } })
        SwitchRow("Execution finished", n.executionFinished, { v -> change { it.copy(executionFinished = v) } })
        SwitchRow("Something failed or the cap was reached", n.failures, { v -> change { it.copy(failures = v) } })
    }
    SettingsCard(title = "System settings") {
        HelpText("Android can also block notifications for the whole app, or per channel.")
        OutlinedButton(
            onClick = { openSystemNotificationSettings(context) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Open system notification settings") }
    }
}

private fun openSystemNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
