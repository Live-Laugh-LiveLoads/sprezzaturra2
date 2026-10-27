package com.ideaforge.ui.approval

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideaforge.core.pipeline.PlanStatus
import com.ideaforge.data.SessionActions
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.data.db.PlanEntity
import com.ideaforge.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the "Plans ready" screen: the session's plans, the multi-selection and the
 * "approve all above X" threshold. Selection and threshold survive process death.
 */
@HiltViewModel
class ApprovalViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    db: AppDatabase,
    settings: SettingsRepository,
    private val actions: SessionActions,
) : ViewModel() {

    val sessionId: Long = handle.get<Long>("sessionId") ?: error("sessionId argument missing")

    val plans: StateFlow<List<PlanEntity>?> = db.plans().observeForSession(sessionId)
        .map<List<PlanEntity>, List<PlanEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val defaultThreshold = settings.current.approval.approveAboveScore.toFloat().coerceIn(1f, 10f)

    val threshold: StateFlow<Float> = handle.getStateFlow("threshold", defaultThreshold)
    private val selectedIds: StateFlow<LongArray> = handle.getStateFlow("selected", LongArray(0))
    val selected: Flow<Set<Long>> = selectedIds.map { it.toSet() }

    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow: Flow<String> = messages.receiveAsFlow()

    fun setThreshold(value: Float) {
        handle["threshold"] = value
    }

    fun toggleSelected(planId: Long) {
        val current = selectedIds.value.toMutableSet()
        if (!current.add(planId)) current.remove(planId)
        handle["selected"] = current.toLongArray()
    }

    private fun clearSelection(ids: Collection<Long>) {
        handle["selected"] = selectedIds.value.filter { it !in ids }.toLongArray()
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                messages.trySend(e.message ?: "Something went wrong")
            }
        }
    }

    fun approve(ids: Collection<Long>) = run {
        if (ids.isEmpty()) return@run
        actions.approve(ids)
        clearSelection(ids)
        messages.trySend(if (ids.size == 1) "Approved. Working on it in the background." else "Approved ${ids.size} plans. Working on them in the background.")
    }

    fun approveAbove(threshold: Double) = run {
        val n = actions.approveAbove(sessionId, threshold)
        messages.trySend(if (n == 0) "No pending plan scores that high" else "Approved $n plan${if (n == 1) "" else "s"}")
    }

    fun reject(planId: Long, reason: String) = run {
        actions.reject(planId, reason)
        clearSelection(listOf(planId))
        messages.trySend("Rejected. You can restore it from Archived.")
    }

    fun restore(planId: Long) = run {
        actions.unreject(planId)
        messages.trySend("Restored to pending")
    }

    fun retry(planId: Long) = run {
        actions.retryExecution(planId)
        messages.trySend("Retrying")
    }

    companion object {
        val PENDING = PlanStatus.PENDING.name
        val REJECTED = PlanStatus.REJECTED.name
    }
}
