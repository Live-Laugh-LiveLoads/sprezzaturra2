package com.ideaforge.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ideaforge.core.pipeline.SessionMode
import com.ideaforge.data.SessionActions
import com.ideaforge.data.db.SessionEventTypes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** What the recording screen and the notification show. [elapsedMs] is audio time, so pauses do not count. */
data class RecorderUiState(
    val sessionId: Long? = null,
    val mode: SessionMode? = null,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val paused: Boolean = false,
    val pauseReason: String? = null,
    val warning: String? = null,
    val chunkCount: Int = 0,
) {
    val isActive: Boolean get() = sessionId != null
}

/**
 * The UI's handle on recording. The actual work happens in [RecordingService]; this singleton owns
 * the shared [state] (the service updates it) and turns user actions into service intents.
 */
@Singleton
class RecordingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actions: SessionActions,
) {
    private val _state = MutableStateFlow(RecorderUiState())
    val state: StateFlow<RecorderUiState> = _state.asStateFlow()

    /** Guards against two taps racing between "state is idle" and "state is claimed". */
    private val starting = AtomicBoolean(false)

    /**
     * Creates the session, logs STARTED and launches the foreground service. Must be called while
     * the app is visible (Android 14 refuses to start a microphone service from the background).
     * Returns the new session id.
     *
     * @throws IllegalStateException if a recording is already running.
     */
    suspend fun start(mode: SessionMode, consentAt: Long?): Long {
        if (_state.value.isActive || RecordingService.isRunning || !starting.compareAndSet(false, true)) {
            throw IllegalStateException("A recording is already in progress")
        }
        var sessionId: Long? = null
        try {
            val session = actions.createSession(mode, consentAt)
            sessionId = session.id
            _state.value = RecorderUiState(sessionId = session.id, mode = mode)
            actions.logEvent(session.id, SessionEventTypes.STARTED, "Recording started (${mode.name.lowercase()})")
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_SESSION_ID, session.id),
            )
            return session.id
        } catch (t: Throwable) {
            // Do not leave a session stuck in "recording" when the service could not be started.
            _state.value = RecorderUiState()
            sessionId?.let { runCatching { actions.deleteSession(it) } }
            throw t
        } finally {
            starting.set(false)
        }
    }

    fun pause() = send(RecordingService.ACTION_PAUSE)

    fun resume() = send(RecordingService.ACTION_RESUME)

    fun stop() = send(RecordingService.ACTION_STOP)

    private fun send(action: String) {
        if (!_state.value.isActive) return
        // The service is already in the foreground, so a plain startService is allowed and does not
        // owe a startForeground() call the way startForegroundService() would.
        context.startService(Intent(context, RecordingService::class.java).setAction(action))
    }

    /** Called by the service to publish progress; ignored if [sessionId] is no longer the active one. */
    internal fun update(sessionId: Long, transform: (RecorderUiState) -> RecorderUiState) {
        _state.update { if (it.sessionId == sessionId) transform(it) else it }
    }

    /** Called by the service when recording has fully ended. */
    internal fun reset() {
        _state.value = RecorderUiState()
    }
}
