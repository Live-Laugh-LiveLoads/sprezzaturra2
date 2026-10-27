package com.ideaforge.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.ui.Routes
import com.ideaforge.ui.components.EmptyState

/**
 * Full-text search over transcripts and ideas. [onOpenSession] gets the session id and the tab
 * ([Routes.TAB_TRANSCRIPT] or [Routes.TAB_IDEAS]) to open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenSession: (sessionId: Long, tab: Int) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Local copy of the text so typing never waits on the (asynchronous) state flow.
    var text by rememberSaveable { mutableStateOf(viewModel.initialQuery) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    TextField(
                        value = text,
                        onValueChange = { text = it; viewModel.setQuery(it) },
                        placeholder = { Text("Search transcripts and ideas") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
                actions = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = ""; viewModel.setQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                SearchUiState.Idle -> EmptyState(
                    icon = Icons.Default.Search,
                    title = "Search your sessions",
                    body = "Type at least ${SearchViewModel.MIN_CHARS} characters to search transcripts and ideas.",
                    modifier = Modifier.align(Alignment.Center),
                )
                SearchUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is SearchUiState.Error -> EmptyState(
                    icon = Icons.Default.Search,
                    title = "Search failed",
                    body = s.message,
                    modifier = Modifier.align(Alignment.Center),
                )
                is SearchUiState.Results -> if (s.transcripts.isEmpty() && s.ideas.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "No matches",
                        body = "Nothing found for \"${text.trim()}\".",
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    ResultsList(s, onOpenSession)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultsList(results: SearchUiState.Results, onOpenSession: (Long, Int) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (results.transcripts.isNotEmpty()) {
            item(key = "h-transcripts") { SectionHeader("Transcripts") }
            items(results.transcripts, key = { "t${it.sessionId}" }) { hit ->
                Card(onClick = { onOpenSession(hit.sessionId, Routes.TAB_TRANSCRIPT) }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(hit.sessionTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            hit.snippet.toAnnotated(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (results.ideas.isNotEmpty()) {
            item(key = "h-ideas") { SectionHeader("Ideas") }
            items(results.ideas, key = { "i${it.ideaRowId}" }) { hit ->
                Card(onClick = { onOpenSession(hit.sessionId, Routes.TAB_IDEAS) }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            hit.sessionTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

private fun Snippet.toAnnotated(): AnnotatedString = buildAnnotatedString {
    append(before)
    if (match.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.Unspecified)) { append(match) }
    append(after)
}
