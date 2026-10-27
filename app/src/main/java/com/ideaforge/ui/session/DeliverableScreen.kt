package com.ideaforge.ui.session

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ideaforge.BuildConfig
import com.ideaforge.core.agents.DeliverableTypes
import com.ideaforge.core.json.AppJson
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.data.db.DeliverableEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import javax.inject.Inject

/** What the deliverable viewer shows. */
sealed interface DeliverableUi {
    data object Loading : DeliverableUi
    data class Failed(val message: String) : DeliverableUi
    data class Ready(
        val deliverable: DeliverableEntity,
        val text: String,
        /** True when the file is source code rather than Markdown, so it is shown verbatim. */
        val isCode: Boolean,
        val blocks: List<MdBlock>,
        val humanActions: List<String>,
    ) : DeliverableUi
}

/** Loads a deliverable's file and parses it off the main thread. */
@HiltViewModel
class DeliverableViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val db: AppDatabase,
) : ViewModel() {

    private val deliverableId: Long = savedStateHandle.get<Long>("deliverableId") ?: error("deliverableId argument missing")
    private val _ui = MutableStateFlow<DeliverableUi>(DeliverableUi.Loading)
    val ui: StateFlow<DeliverableUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            _ui.value = withContext(Dispatchers.IO) {
                try {
                    val d = db.deliverables().get(deliverableId) ?: return@withContext DeliverableUi.Failed("This deliverable no longer exists.")
                    val file = File(d.path)
                    if (!file.exists()) return@withContext DeliverableUi.Failed("The file for \"${d.title}\" is missing from this phone.")
                    val text = file.readText()
                    val isCode = d.type == DeliverableTypes.CODE && !file.extension.equals("md", ignoreCase = true)
                    val actions = runCatching { AppJson.decodeFromString(ListSerializer(String.serializer()), d.humanActionsJson) }.getOrDefault(emptyList())
                    DeliverableUi.Ready(d, text, isCode, if (isCode) emptyList() else parseMarkdown(text), actions)
                } catch (e: Exception) {
                    DeliverableUi.Failed("Could not open the file: ${e.message}")
                }
            }
        }
    }
}

/** Opens the share sheet for a deliverable file via the app's FileProvider. */
internal fun shareDeliverableFile(context: Context, path: String, type: String, title: String) {
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "The file is missing from this phone", Toast.LENGTH_LONG).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = if (type == DeliverableTypes.CODE && !file.extension.equals("md", ignoreCase = true)) "text/plain" else "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share $title"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can handle this file", Toast.LENGTH_LONG).show()
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, "This file cannot be shared", Toast.LENGTH_LONG).show()
    }
}

/** Viewer for one deliverable: what you still need to do, then the content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliverableScreen(onBack: () -> Unit, viewModel: DeliverableViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ready = ui as? DeliverableUi.Ready

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ready?.deliverable?.title ?: "Deliverable", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (ready != null) {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(ready.text))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
                        IconButton(onClick = { shareDeliverableFile(context, ready.deliverable.path, ready.deliverable.type, ready.deliverable.title) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val state = ui) {
            DeliverableUi.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is DeliverableUi.Failed -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
            is DeliverableUi.Ready -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip(typeLabel(state.deliverable.type))
                    if (state.deliverable.complete) {
                        InfoChip("Complete", color = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                    } else {
                        InfoChip("Needs you", color = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
                if (!state.deliverable.complete) {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("What you need to do", style = MaterialTheme.typography.titleMedium)
                            if (state.humanActions.isEmpty()) {
                                Text("This draft is not finished, but the assistant did not list specific steps for you.", style = MaterialTheme.typography.bodyMedium)
                            }
                            state.humanActions.forEachIndexed { i, a -> Text("${i + 1}. $a", style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
                SelectionContainer {
                    if (state.isCode) CodeBlock(state.text) else MarkdownContent(state.blocks)
                }
            }
        }
    }
}
