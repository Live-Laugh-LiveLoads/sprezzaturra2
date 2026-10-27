@file:OptIn(ExperimentalMaterial3Api::class)

package com.ideaforge.ui.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.core.agents.FlaggedPassage
import com.ideaforge.core.agents.TranscriptChange
import com.ideaforge.core.transcript.Segment
import com.ideaforge.core.transcript.formatTimestamp

/**
 * Raw / Cleaned transcript. Decoding happens in the ViewModel on a background dispatcher; this list
 * uses stable index keys and takes no per-frame state, so audio position updates never recompose it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TranscriptTab(vm: SessionViewModel, onRenameSpeaker: (String) -> Unit) {
    val ui by vm.transcriptUi.collectAsStateWithLifecycle()
    val names by vm.speakerNames.collectAsStateWithLifecycle()
    val showCleaned by vm.showCleaned.collectAsStateWithLifecycle()
    var editIndex by rememberSaveable { mutableIntStateOf(-1) }
    var changesOpen by rememberSaveable { mutableStateOf(false) }
    val onSeek = remember(vm) { { ms: Long -> vm.seekTo(ms) } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showCleaned, onClick = { vm.setShowCleaned(false) }, label = { Text("Raw") })
            FilterChip(selected = showCleaned, onClick = { vm.setShowCleaned(true) }, label = { Text("Cleaned") })
        }
        when (val state = ui) {
            TranscriptUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is TranscriptUi.Empty -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (state.cleaned) "No cleaned transcript yet. It appears after the Verify stage finishes (or the stage is turned off in Settings)."
                    else "No transcript yet. It appears once the audio has been transcribed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is TranscriptUi.Ready -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (state.cleaned && state.changes.isNotEmpty()) {
                        item(key = "changes") {
                            ChangeLog(state.changes, expanded = changesOpen, onToggle = { changesOpen = !changesOpen })
                        }
                    }
                    itemsIndexed(state.segments, key = { i, _ -> i }) { i, seg ->
                        SegmentRow(
                            index = i,
                            segment = seg,
                            speakerName = seg.speaker?.let { names[it] ?: it },
                            flags = state.flagsBySegment[i].orEmpty(),
                            editable = state.cleaned,
                            onSeek = onSeek,
                            onRenameSpeaker = onRenameSpeaker,
                            onEdit = { editIndex = it },
                        )
                    }
                }
                if (editIndex in state.segments.indices) {
                    TextEditDialog(
                        title = "Edit segment",
                        initial = state.segments[editIndex].text.trim(),
                        label = "Text",
                        confirmText = "Save",
                        singleLine = false,
                        requireNonBlank = true,
                        message = "Saving creates a new version of the cleaned transcript. Brainstorm, Evaluate and Plan " +
                            "run again from it, and plans still awaiting approval are replaced.",
                        onDismiss = { editIndex = -1 },
                        onConfirm = { vm.editSegment(editIndex, it); editIndex = -1 },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangeLog(changes: List<TranscriptChange>, expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Change log (${changes.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    changes.forEach { c ->
                        Column {
                            Text(c.original, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            Text("-> ${c.corrected}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(c.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentRow(
    index: Int,
    segment: Segment,
    speakerName: String?,
    flags: List<FlaggedPassage>,
    editable: Boolean,
    onSeek: (Long) -> Unit,
    onRenameSpeaker: (String) -> Unit,
    onEdit: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = if (editable) ({ onEdit(index) }) else null)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SeekChip(segment.startMs, onSeek)
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            val speakerLabel = segment.speaker
            if (speakerName != null && speakerLabel != null) {
                Text(
                    speakerName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onRenameSpeaker(speakerLabel) }.heightIn(min = 32.dp),
                )
            }
            Text(segment.text.trim(), style = MaterialTheme.typography.bodyLarge)
            flags.forEach { f ->
                Surface(
                    onClick = { onSeek(f.startMs) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Warning, contentDescription = "Flagged by the verifier")
                        Column {
                            Text("Check this at ${formatTimestamp(f.startMs)}: \"${f.text.trim()}\"", style = MaterialTheme.typography.bodyMedium)
                            Text(f.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
