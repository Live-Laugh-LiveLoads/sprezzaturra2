package com.ideaforge.ui.home

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideaforge.audio.AudioImporter
import com.ideaforge.audio.RecorderUiState
import com.ideaforge.audio.RecordingController
import com.ideaforge.core.pipeline.SessionMode
import com.ideaforge.data.RoomUsageLedger
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.data.db.SessionEntity
import com.ideaforge.data.db.StageRunEntity
import com.ideaforge.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A session with its pipeline stage rows, as shown on a Home card. */
data class SessionRow(val session: SessionEntity, val stages: List<StageRunEntity>)

/** Month-to-date spend against the configured cap (a cap of 0 or less means unlimited). */
data class SpendInfo(val spentUsd: Double, val capUsd: Double)

/** One-shot results the screen reacts to. */
sealed interface HomeEvent {
    data object OpenRecording : HomeEvent
    data class OpenSession(val sessionId: Long) : HomeEvent
    data class Error(val message: String) : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val recordingController: RecordingController,
    private val audioImporter: AudioImporter,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    val recorder: StateFlow<RecorderUiState> = recordingController.state

    val sessions: StateFlow<List<SessionRow>?> = combine(
        db.sessions().observeRecent(),
        db.stages().observeAll(),
    ) { sessions, stages ->
        val bySession = stages.groupBy { it.sessionId }
        sessions.map { SessionRow(it, bySession[it.id].orEmpty()) }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Month start is fixed when the screen is first shown; a rollover mid-session is corrected on next launch.
    val spend: StateFlow<SpendInfo> = combine(
        db.usage().observeCostSince(RoomUsageLedger.monthStart(System.currentTimeMillis())),
        settings.settings,
    ) { spent, s -> SpendInfo(spent, s.monthlyCapUsd) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpendInfo(0.0, settings.current.monthlyCapUsd))

    private val _busy = MutableStateFlow(false)

    /** True while a recording is starting or a file is being imported; disables the buttons. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val eventChannel = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = eventChannel.receiveAsFlow()

    /** Whether the battery-optimisation exemption has already been offered (it is offered only once). */
    fun batteryOfferPending(): Boolean = !prefs.getBoolean(KEY_BATTERY_OFFERED, false)

    fun markBatteryOffered() = prefs.edit { putBoolean(KEY_BATTERY_OFFERED, true) }

    /** Starts recording. [consentAt] is the confirmation time for meetings and null for memos. */
    fun startRecording(mode: SessionMode, consentAt: Long?) {
        if (!_busy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                recordingController.start(mode, consentAt)
                eventChannel.send(HomeEvent.OpenRecording)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                eventChannel.send(HomeEvent.Error("Could not start recording: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                _busy.value = false
            }
        }
    }

    /** Imports an existing audio file as a new session and opens it. */
    fun importAudio(uri: Uri) {
        if (!_busy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                eventChannel.send(HomeEvent.OpenSession(audioImporter.import(uri)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                eventChannel.send(HomeEvent.Error("Could not import audio: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        const val KEY_BATTERY_OFFERED = "battery_exemption_offered"
    }
}
