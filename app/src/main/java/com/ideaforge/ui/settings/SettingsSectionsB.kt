package com.ideaforge.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.ideaforge.settings.AppSettings
import com.ideaforge.settings.MicSource
import com.ideaforge.settings.RecordingSettings
import com.ideaforge.settings.TranscriptionEngine
import com.ideaforge.settings.TranscriptionSettings

// ---- recording -------------------------------------------------------------------------------

@Composable
internal fun RecordingSection(vm: SettingsViewModel, settings: AppSettings) {
    val r = settings.recording
    fun change(t: (RecordingSettings) -> RecordingSettings) = vm.update { it.copy(recording = t(it.recording)) }

    SettingsCard(title = "Chunks") {
        HelpText("Audio is saved in chunks so a crash loses at most the current one. Overlap lets the stitcher join chunks without losing words.")
        StepperRow("Chunk length", r.chunkMinutes, 1..15, { v -> change { it.copy(chunkMinutes = v) } }, format = { "$it min" })
        StepperRow("Overlap", r.overlapSeconds, 0..15, { v -> change { it.copy(overlapSeconds = v) } }, format = { "$it s" })
    }
    SettingsCard(title = "Quality") {
        Text("Bitrate", style = MaterialTheme.typography.bodyLarge)
        ChoiceChips(
            options = listOf(24, 32, 48, 64).map { it to "$it kbps" },
            selected = r.bitrateKbps,
            onSelect = { v -> change { it.copy(bitrateKbps = v) } },
        )
        HelpText("32 kbps mono is plenty for speech and about 14 MB per hour.")
        Text("Microphone source", style = MaterialTheme.typography.bodyLarge)
        ChoiceChips(
            options = listOf(
                MicSource.MIC to "Standard",
                MicSource.VOICE_RECOGNITION to "Voice recognition",
                MicSource.CAMCORDER to "Camcorder",
                MicSource.UNPROCESSED to "Unprocessed",
            ),
            selected = r.micSource,
            onSelect = { v -> change { it.copy(micSource = v) } },
        )
        HelpText("If recordings are quiet or muffled, try another source. Not every phone supports every source.")
    }
    SettingsCard(title = "Limits") {
        StepperRow(
            "Stop at battery", r.stopAtBatteryPercent, 1..50,
            { v -> change { it.copy(stopAtBatteryPercent = v, warnAtBatteryPercent = maxOf(it.warnAtBatteryPercent, v)) } },
            format = { "$it%" }, description = "Saves the recording and stops, unless charging",
        )
        StepperRow(
            "Warn at battery", r.warnAtBatteryPercent, 1..90,
            { v -> change { it.copy(warnAtBatteryPercent = v, stopAtBatteryPercent = minOf(it.stopAtBatteryPercent, v)) } },
            format = { "$it%" },
        )
        StepperRow(
            "Stop at free space", r.stopAtFreeMb, 0..2000, { v -> change { it.copy(stopAtFreeMb = v) } },
            step = 25, format = { "$it MB" },
        )
    }
    SettingsCard(title = "Behaviour") {
        SwitchRow(
            "Resume after interruptions", r.autoResumeAfterInterruption,
            { v -> change { it.copy(autoResumeAfterInterruption = v) } },
            "Pause during phone calls and when another app takes the microphone, then continue",
        )
        SwitchRow(
            "Keep CPU awake while recording", r.useWakeLock,
            { v -> change { it.copy(useWakeLock = v) } },
            "Prevents the phone from sleeping the recorder. Uses a little more battery.",
        )
        BatteryOptimisationRow()
    }
}

/** Asks Android to exempt the app from battery optimisation so long recordings are not cut off. */
@Composable
private fun BatteryOptimisationRow() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Battery optimisation", style = MaterialTheme.typography.bodyLarge)
        HelpText(
            if (exempt) "This app is exempt, so long meetings are less likely to be cut off."
            else "Some phones stop background apps during long recordings. Exempting this app helps.",
        )
        OutlinedButton(
            onClick = {
                val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                try {
                    launcher.launch(direct)
                } catch (e: ActivityNotFoundException) {
                    // Some phones lack the one-tap dialog; open the general list instead.
                    launcher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            enabled = !exempt,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(if (exempt) "Already exempt" else "Allow unrestricted battery use") }
    }
}

// ---- transcription ---------------------------------------------------------------------------

@Composable
internal fun TranscriptionSection(vm: SettingsViewModel, settings: AppSettings) {
    val t = settings.transcription
    fun change(f: (TranscriptionSettings) -> TranscriptionSettings) = vm.update { it.copy(transcription = f(it.transcription)) }

    SettingsCard(title = "Engine") {
        ChoiceChips(
            options = listOf(TranscriptionEngine.CLOUD to "Cloud", TranscriptionEngine.ON_DEVICE to "On-device"),
            selected = t.engine,
            onSelect = { v -> change { it.copy(engine = v) } },
        )
        HelpText(
            if (t.engine == TranscriptionEngine.CLOUD) "Audio is uploaded to the endpoint below for transcription."
            else "Audio never leaves the phone, but transcription is slow.",
        )
    }
    if (t.engine == TranscriptionEngine.CLOUD) CloudTranscription(vm, t, ::change) else OnDeviceTranscription(vm, t, ::change)
}

@Composable
private fun CloudTranscription(vm: SettingsViewModel, t: TranscriptionSettings, change: ((TranscriptionSettings) -> TranscriptionSettings) -> Unit) {
    val hints by vm.keyHints.collectAsStateWithLifecycle()
    var presetCount by rememberSaveable { mutableIntStateOf(0) }

    SettingsCard(title = "Presets") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsPresets.TRANSCRIPTION.forEach { preset ->
                OutlinedButton(
                    onClick = {
                        change { preset.applyTo(it) }
                        presetCount++
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(preset.label) }
            }
        }
        HelpText("Keys: OpenAI at platform.openai.com/api-keys, Groq at console.groq.com/keys. Diarized models label who spoke.")
    }
    SettingsCard(title = "Endpoint") {
        SaveableField(
            value = t.baseUrl, label = "Base URL", onValue = { v -> change { it.copy(baseUrl = v.trim()) } },
            resetKey = presetCount, keyboardType = KeyboardType.Uri,
        )
        SaveableField(
            value = t.model, label = "Model", onValue = { v -> change { it.copy(model = v.trim()) } }, resetKey = presetCount,
        )
        Text("Response format", style = MaterialTheme.typography.bodyLarge)
        ChoiceChips(
            options = SettingsPresets.RESPONSE_FORMATS.map { it to it },
            selected = t.responseFormat,
            onSelect = { v -> change { it.copy(responseFormat = v) } },
        )
        HelpText("auto picks the richest format the model supports.")
        SaveableField(
            value = t.language, label = "Language code", onValue = { v -> change { it.copy(language = v.trim()) } },
            supportingText = "For example en. Empty lets the model detect it.",
        )
        SwitchRow("Identify speakers", t.diarize, { v -> change { it.copy(diarize = v) } }, "Only works with models that support it")
    }
    SettingsCard(title = "Transcription API key") {
        ApiKeyField(
            label = "API key",
            savedHint = hints[AppSettings.TRANSCRIPTION_KEY],
            onSave = { vm.saveKey(AppSettings.TRANSCRIPTION_KEY, it) },
            onClear = { vm.clearKey(AppSettings.TRANSCRIPTION_KEY) },
        )
    }
}

@Composable
private fun OnDeviceTranscription(vm: SettingsViewModel, t: TranscriptionSettings, change: ((TranscriptionSettings) -> TranscriptionSettings) -> Unit) {
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val available = remember { vm.isWhisperAvailable() }

    SettingsCard(title = "On-device engine") {
        Text(
            if (available) "Engine ready" else "Engine not available on this phone",
            color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(
            "Slow on older phones: roughly real-time for tiny.en, 2-3x slower for base.en.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        StepperRow("Threads", t.onDeviceThreads, 1..4, { v -> change { it.copy(onDeviceThreads = v) } },
            description = "More threads is faster but heats the phone and drains the battery")
    }
    vm.whisperModels.forEach { model ->
        key(model.name) {
            val progress = downloads[model.name]
            val isDownloaded = model.name in downloaded
            SettingsCard(title = "${model.name} (${model.sizeMb} MB)") {
                HelpText(model.note)
                when {
                    progress != null && progress.error == null -> {
                        LinearProgressIndicator(progress = { progress.progress }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${(progress.progress * 100).toInt()}%", modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.cancelDownload(model.name) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                        }
                    }
                    isDownloaded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (t.onDeviceModel == model.name) {
                            Text("In use", color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        } else {
                            Button(
                                onClick = { change { it.copy(onDeviceModel = model.name) } },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Use this model") }
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(onClick = { vm.deleteModel(model.name) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                    }
                    else -> {
                        progress?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = { vm.downloadModel(model.name) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (progress?.error != null) "Retry download" else "Download")
                        }
                    }
                }
            }
        }
    }
}
