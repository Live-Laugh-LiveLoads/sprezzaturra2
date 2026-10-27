package com.ideaforge.ui.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideaforge.audio.RecorderUiState
import com.ideaforge.audio.RecordingController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exposes the controller's state and reports (once) the id of the session whose recording just
 * ended, so the screen can move on to it. Opening the screen with no recording running does not navigate.
 */
@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val controller: RecordingController,
) : ViewModel() {

    val state: StateFlow<RecorderUiState> = controller.state

    private val finished = Channel<Long>(Channel.BUFFERED)

    /** Emits the session id when a recording that was active becomes inactive. */
    val finishedSessions: Flow<Long> = finished.receiveAsFlow()

    init {
        viewModelScope.launch {
            var activeSession: Long? = null
            controller.state.collect { s ->
                val id = s.sessionId
                if (id != null) {
                    activeSession = id
                } else {
                    activeSession?.let { finished.send(it) }
                    activeSession = null
                }
            }
        }
    }

    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun stop() = controller.stop()
}
