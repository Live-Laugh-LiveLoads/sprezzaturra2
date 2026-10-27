package com.ideaforge.ui.session

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideaforge.core.agents.BrainstormFeedback
import com.ideaforge.core.agents.BrainstormOutput
import com.ideaforge.core.agents.EvaluationOutput
import com.ideaforge.core.agents.FlaggedPassage
import com.ideaforge.core.agents.IdeaEvaluation
import com.ideaforge.core.agents.TranscriptChange
import com.ideaforge.core.agents.VerifierOutput
import com.ideaforge.core.json.AppJson
import com.ideaforge.core.pipeline.PipelineState
import com.ideaforge.core.pipeline.Stage
import com.ideaforge.core.pipeline.StageStatus
import com.ideaforge.core.transcript.Segment
import com.ideaforge.core.transcript.Transcript
import com.ideaforge.data.SessionActions
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.data.db.ChunkEntity
import com.ideaforge.data.db.DeliverableEntity
import com.ideaforge.data.db.IdeaEntity
import com.ideaforge.data.db.PlanEntity
import com.ideaforge.data.db.SessionEntity
import com.ideaforge.data.db.SessionEventEntity
import com.ideaforge.data.db.StageRunEntity
import com.ideaforge.data.db.TranscriptEntity
import com.ideaforge.export.SessionExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot things the screen must do: show a message or open the share sheet. */
sealed interface SessionEvent {
    data class Message(val text: String) : SessionEvent
    data class Share(val intent: Intent) : SessionEvent
}

/** What the Transcript tab shows. */
sealed interface TranscriptUi {
    data object Loading : TranscriptUi
    data class Empty(val cleaned: Boolean) : TranscriptUi
    data class Ready(
        val cleaned: Boolean,
        /** Word-level timings are dropped after decoding: the list never shows them and they dominate memory. */
        val segments: List<Segment>,
        val language: String?,
        /** Verifier flags keyed by the index of the segment that contains their start. */
        val flagsBySegment: Map<Int, List<FlaggedPassage>>,
        val changes: List<TranscriptChange>,
    ) : TranscriptUi
}

/** An idea's evaluation joined with the idea's title. */
data class EvalItem(val ideaId: String, val title: String, val evaluation: IdeaEvaluation)

/** Everything the Evaluation tab shows about one brainstorm/evaluate round. */
data class EvalRound(
    val round: Int,
    val brainstormStatus: StageStatus?,
    val brainstormNote: String?,
    val ideaCount: Int?,
    val evaluateStatus: StageStatus?,
    val evaluateNote: String?,
    val evaluations: List<EvalItem>,
    val feedback: BrainstormFeedback?,
)

/**
 * State and actions for the session detail screen. It also owns the audio player so playback
 * survives tab switches and rotation, and is released in [onCleared].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val db: AppDatabase,
    private val actions: SessionActions,
    private val exporter: SessionExporter,
) : ViewModel() {

    val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: error("sessionId argument missing")
    val initialTab: Int = (savedStateHandle.get<Int>("tab") ?: 1).coerceIn(0, 5)

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val session: StateFlow<SessionEntity?> = db.sessions().observe(sessionId).state(null)
    val stages: StateFlow<List<StageRunEntity>> = db.stages().observeForSession(sessionId).state(emptyList())
    val chunks: StateFlow<List<ChunkEntity>> = db.chunks().observeForSession(sessionId).state(emptyList())
    val events: StateFlow<List<SessionEventEntity>> = db.events().observeForSession(sessionId).state(emptyList())
    val ideas: StateFlow<List<IdeaEntity>> = db.ideas().observeForSession(sessionId).state(emptyList())
    val plans: StateFlow<List<PlanEntity>> = db.plans().observeForSession(sessionId).state(emptyList())
    val deliverables: StateFlow<List<DeliverableEntity>> = db.deliverables().observeForSession(sessionId).state(emptyList())

    /** Speaker label → display name chosen by the user. */
    val speakerNames: StateFlow<Map<String, String>> = db.speakers().observeForSession(sessionId)
        .map { list -> list.associate { it.label to it.displayName } }
        .state(emptyMap())

    private val _events = Channel<SessionEvent>(Channel.BUFFERED)
    val uiEvents: Flow<SessionEvent> = _events.receiveAsFlow()

    private val _busy = MutableStateFlow(false)
    /** True while an export is being built. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // ---- Audio -----------------------------------------------------------------------------------

    private val playback = SessionPlayback(context, viewModelScope)
    val isPlaying: StateFlow<Boolean> = playback.isPlaying
    val positionMs: StateFlow<Long> = playback.positionMs
    val playableEndMs: StateFlow<Long> = playback.playableEndMs
    val playbackError: StateFlow<String?> = playback.error

    init {
        viewModelScope.launch { db.chunks().observeForSession(sessionId).collect { playback.setChunks(it) } }
    }

    /** Seeks to session time [ms] and plays; used by every timestamp chip in the other tabs. */
    fun seekTo(ms: Long) {
        if (session.value?.audioDeleted == true) {
            post("Audio deleted (transcripts kept)")
        } else if (!playback.seekAndPlay(ms)) {
            post("No finished audio to play yet")
        }
    }

    fun scrubTo(ms: Long) = playback.seekTo(ms)
    fun seekBy(deltaMs: Long) = playback.seekBy(deltaMs)
    fun togglePlay() = playback.togglePlay()

    // ---- Transcript tab ----------------------------------------------------------------------------

    private val showCleanedFlow = savedStateHandle.getStateFlow("showCleaned", true)
    val showCleaned: StateFlow<Boolean> = showCleanedFlow
    private val handle = savedStateHandle

    fun setShowCleaned(cleaned: Boolean) {
        handle["showCleaned"] = cleaned
    }

    private val verifier: Flow<VerifierOutput?> = stages
        .map { list -> list.firstOrNull { it.stage == Stage.VERIFY.name && it.status == StageStatus.DONE.name }?.let { it.editedJson ?: it.outputJson } }
        .distinctUntilChanged()
        .map { json -> json?.let { runCatching { AppJson.decodeFromString(VerifierOutput.serializer(), it) }.getOrNull() } }
        .flowOn(Dispatchers.Default)

    private val decodedTranscript: Flow<Pair<Boolean, Transcript?>> = showCleanedFlow
        .flatMapLatest { cleaned ->
            db.transcripts().observeLatest(sessionId, if (cleaned) TranscriptEntity.CLEANED else TranscriptEntity.RAW)
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .map { entity -> cleaned to entity?.let { decode(it.json) } }
        }
        .flowOn(Dispatchers.Default)

    val transcriptUi: StateFlow<TranscriptUi> = combine(decodedTranscript, verifier) { (cleaned, transcript), v ->
        if (transcript == null || transcript.segments.isEmpty()) {
            TranscriptUi.Empty(cleaned)
        } else {
            TranscriptUi.Ready(cleaned, transcript.segments, transcript.language, flagsBySegment(transcript.segments, v?.flags.orEmpty()), v?.changes.orEmpty())
        }
    }.flowOn(Dispatchers.Default).state(TranscriptUi.Loading)

    private fun decode(json: String): Transcript? =
        runCatching { AppJson.decodeFromString(Transcript.serializer(), json) }.getOrNull()
            ?.let { t -> t.copy(segments = t.segments.map { if (it.words.isEmpty()) it else it.copy(words = emptyList()) }) }

    /** Attaches every flag to the last segment starting at or before it (binary search). */
    private fun flagsBySegment(segments: List<Segment>, flags: List<FlaggedPassage>): Map<Int, List<FlaggedPassage>> {
        if (flags.isEmpty()) return emptyMap()
        val out = HashMap<Int, MutableList<FlaggedPassage>>()
        for (f in flags) {
            var lo = 0
            var hi = segments.size - 1
            var found = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (segments[mid].startMs <= f.startMs) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            out.getOrPut(found) { mutableListOf() }.add(f)
        }
        return out
    }

    /**
     * Saves a hand-edited segment of the cleaned transcript. Stages after Review are cleared by the
     * orchestrator; the pipeline is re-queued unless it is parked at the review gate or recording.
     */
    fun editSegment(index: Int, newText: String) {
        val ui = transcriptUi.value as? TranscriptUi.Ready ?: return
        if (!ui.cleaned || index !in ui.segments.indices) return
        val edited = ui.segments.mapIndexed { i, s -> if (i == index) s.copy(text = newText.trim()) else s }
        viewModelScope.launch {
            try {
                actions.editCleanedTranscript(sessionId, Transcript(edited, ui.language))
                val state = session.value?.pipelineState
                if (state != PipelineState.AWAITING_REVIEW.name && state != PipelineState.RECORDING.name) {
                    actions.runPipeline(sessionId)
                    post("Saved. Brainstorm, Evaluate and Plan will run again.")
                } else {
                    post("Saved. Press Continue when you are happy with the transcript.")
                }
            } catch (e: Exception) {
                post("Could not save: ${e.message}")
            }
        }
    }

    // ---- Evaluation tab ------------------------------------------------------------------------------

    val evaluationUi: StateFlow<List<EvalRound>> = combine(stages, ideas) { stageList, ideaList ->
        val titles = ideaList.associate { (it.round to it.ideaId) to it.title }
        fun find(stage: Stage, round: Int) = stageList.firstOrNull { it.stage == stage.name && it.round == round }
        val rounds = stageList.filter { it.stage == Stage.BRAINSTORM.name || it.stage == Stage.EVALUATE.name }
            .map { it.round }.distinct().sorted()
        rounds.map { r ->
            val b = find(Stage.BRAINSTORM, r)
            val e = find(Stage.EVALUATE, r)
            val brainstorm = b?.let { s -> (s.editedJson ?: s.outputJson)?.let { runCatching { AppJson.decodeFromString(BrainstormOutput.serializer(), it) }.getOrNull() } }
            val evaluation = e?.let { s -> (s.editedJson ?: s.outputJson)?.let { runCatching { AppJson.decodeFromString(EvaluationOutput.serializer(), it) }.getOrNull() } }
            EvalRound(
                round = r,
                brainstormStatus = b?.let { runCatching { StageStatus.valueOf(it.status) }.getOrNull() },
                brainstormNote = b?.note,
                ideaCount = brainstorm?.ideas?.size,
                evaluateStatus = e?.let { runCatching { StageStatus.valueOf(it.status) }.getOrNull() },
                evaluateNote = e?.note,
                evaluations = evaluation?.evaluations.orEmpty().map { EvalItem(it.ideaId, titles[r to it.ideaId] ?: it.ideaId, it) },
                feedback = evaluation?.feedback,
            )
        }
    }.flowOn(Dispatchers.Default).state(emptyList())

    // ---- Actions ---------------------------------------------------------------------------------------

    private fun post(text: String) {
        _events.trySend(SessionEvent.Message(text))
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                post(e.message ?: "Something went wrong")
            }
        }
    }

    fun rename(title: String) = launchAction { actions.renameSession(sessionId, title) }
    fun setNotes(notes: String) = launchAction { actions.setNotes(sessionId, notes) }
    fun renameSpeaker(label: String, name: String) = launchAction { actions.renameSpeaker(sessionId, label, name) }
    fun continueAfterReview() = launchAction { actions.continueAfterReview(sessionId) }
    fun resume() = launchAction { actions.runPipeline(sessionId) }
    fun rerun(stage: Stage, round: Int) = launchAction {
        actions.rerunStage(sessionId, stage, round)
        post("${stage.label} and later stages will run again")
    }
    fun retryPlan(planId: Long) = launchAction { actions.retryExecution(planId) }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                playback.pause()
                actions.deleteSession(sessionId)
                onDone()
            } catch (e: Exception) {
                post("Could not delete: ${e.message}")
            }
        }
    }

    fun export(zip: Boolean) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val intent = if (zip) {
                    exporter.shareIntent(exporter.exportZip(sessionId), "application/zip")
                } else {
                    exporter.shareIntent(exporter.exportMarkdown(sessionId), "text/markdown")
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                _events.trySend(SessionEvent.Share(intent))
            } catch (e: Exception) {
                post("Export failed: ${e.message}")
            } finally {
                _busy.value = false
            }
        }
    }

    override fun onCleared() {
        playback.release()
        super.onCleared()
    }
}
