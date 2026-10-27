package com.ideaforge.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideaforge.data.db.AppDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Text around a match, split so the UI can bold [match]. [match] is empty when no exact position was found. */
data class Snippet(val before: String, val match: String, val after: String)

data class TranscriptHit(val sessionId: Long, val sessionTitle: String, val snippet: Snippet)

data class IdeaHit(val ideaRowId: Long, val sessionId: Long, val title: String, val sessionTitle: String)

/** What the results area should show. */
sealed interface SearchUiState {
    /** Fewer than [SearchViewModel.MIN_CHARS] characters typed. */
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val transcripts: List<TranscriptHit>, val ideas: List<IdeaHit>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val db: AppDatabase,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** The query as last saved, used to pre-fill the text field after process death. */
    val initialQuery: String = savedState[KEY_QUERY] ?: ""

    private val query = savedState.getStateFlow(KEY_QUERY, "")

    fun setQuery(q: String) {
        savedState[KEY_QUERY] = q
    }

    val state: StateFlow<SearchUiState> = query
        .debounce(DEBOUNCE_MS)
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { q -> search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Idle)

    private fun search(q: String): Flow<SearchUiState> = flow {
        if (q.length < MIN_CHARS) {
            emit(SearchUiState.Idle)
            return@flow
        }
        emit(SearchUiState.Loading)
        try {
            val result = withContext(Dispatchers.Default) {
                val transcripts = db.transcripts().search(q)
                val ideas = db.ideas().search(q)
                val titles = HashMap<Long, String>()
                suspend fun titleOf(id: Long): String =
                    titles.getOrPut(id) { db.sessions().get(id)?.title?.ifBlank { "Untitled" } ?: "Deleted session" }
                SearchUiState.Results(
                    transcripts = transcripts.map { TranscriptHit(it.sessionId, titleOf(it.sessionId), snippetAround(it.text, q)) },
                    ideas = ideas.map { IdeaHit(it.id, it.sessionId, it.title, titleOf(it.sessionId)) },
                )
            }
            emit(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(SearchUiState.Error(e.message ?: "Search failed"))
        }
    }

    companion object {
        const val MIN_CHARS = 2
        private const val DEBOUNCE_MS = 300L
        private const val KEY_QUERY = "q"
        private const val CONTEXT_CHARS = 60

        /** Builds a ~120 character excerpt centred on the first case-insensitive occurrence of [q]. */
        internal fun snippetAround(text: String, q: String): Snippet {
            val at = text.indexOf(q, ignoreCase = true)
            if (at < 0) return Snippet(text.take(CONTEXT_CHARS * 2).trim(), "", "")
            val start = (at - CONTEXT_CHARS).coerceAtLeast(0)
            val end = (at + q.length + CONTEXT_CHARS).coerceAtMost(text.length)
            val flat = { s: String -> s.replace(Regex("\\s+"), " ") }
            return Snippet(
                before = (if (start > 0) "…" else "") + flat(text.substring(start, at)),
                match = text.substring(at, at + q.length),
                after = flat(text.substring(at + q.length, end)) + (if (end < text.length) "…" else ""),
            )
        }
    }
}
