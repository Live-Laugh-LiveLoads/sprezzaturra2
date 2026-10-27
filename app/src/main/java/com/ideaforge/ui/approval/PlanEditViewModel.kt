package com.ideaforge.ui.approval

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideaforge.core.agents.DeliverableTypes
import com.ideaforge.core.agents.Plan
import com.ideaforge.core.agents.PlanRisk
import com.ideaforge.core.agents.PlanStep
import com.ideaforge.core.agents.PlannedDeliverable
import com.ideaforge.core.json.AppJson
import com.ideaforge.data.SessionActions
import com.ideaforge.data.db.AppDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** An editable deliverable row. [id] is "D1".."Dn" and stays stable for rows that already existed. */
data class DeliverableForm(val id: String, val title: String, val type: String, val description: String)

data class RiskForm(val risk: String, val mitigation: String)

/** Text-field friendly mirror of [Plan]. Lists that are one-per-line in the UI are held as a single string. */
data class PlanForm(
    val title: String = "",
    val summary: String = "",
    val objective: String = "",
    val definitionOfDone: String = "",
    val estimatedTime: String = "",
    val steps: String = "",
    val tools: String = "",
    val dependencies: String = "",
    val deliverables: List<DeliverableForm> = emptyList(),
    val risks: List<RiskForm> = emptyList(),
) {
    /** Null when valid, otherwise what is missing. */
    val problem: String?
        get() = when {
            title.isBlank() -> "Give the plan a title"
            deliverables.none { it.title.isNotBlank() } -> "Add at least one deliverable with a title"
            else -> null
        }
}

data class PlanEditUi(
    val loading: Boolean = true,
    val loadError: String? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
)

/** Loads a plan (the user's earlier edit if any), holds the form, and saves via "edit and approve". */
@HiltViewModel
class PlanEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val db: AppDatabase,
    private val actions: SessionActions,
) : ViewModel() {

    private val planId: Long = savedStateHandle.get<Long>("planId") ?: error("planId argument missing")
    private var original: Plan? = null

    private val _ui = MutableStateFlow(PlanEditUi())
    val ui: StateFlow<PlanEditUi> = _ui.asStateFlow()

    /**
     * The form is Compose snapshot state (not a Flow) so a keystroke is applied synchronously.
     * With a Flow the text field briefly shows stale text and the caret can jump on slow phones.
     */
    var form: PlanForm by mutableStateOf(PlanForm())
        private set

    init {
        viewModelScope.launch {
            try {
                val entity = db.plans().get(planId) ?: error("This plan no longer exists")
                val plan = AppJson.decodeFromString(Plan.serializer(), entity.editedPlanJson ?: entity.planJson)
                original = plan
                form = plan.toForm()
                _ui.update { it.copy(loading = false) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, loadError = e.message ?: "Could not read the plan") }
            }
        }
    }

    fun update(transform: (PlanForm) -> PlanForm) {
        form = transform(form)
        if (_ui.value.saveError != null) _ui.update { it.copy(saveError = null) }
    }

    fun addDeliverable() = update { f ->
        // Next free number: ids of existing rows never change, so a new row must not reuse one.
        val next = (f.deliverables.mapNotNull { it.id.removePrefix("D").toIntOrNull() }.maxOrNull() ?: 0) + 1
        f.copy(deliverables = f.deliverables + DeliverableForm("D$next", "", DeliverableTypes.DOCUMENT, ""))
    }

    fun removeDeliverable(id: String) = update { f -> f.copy(deliverables = f.deliverables.filterNot { it.id == id }) }

    fun updateDeliverable(id: String, transform: (DeliverableForm) -> DeliverableForm) =
        update { f -> f.copy(deliverables = f.deliverables.map { if (it.id == id) transform(it) else it }) }

    fun addRisk() = update { it.copy(risks = it.risks + RiskForm("", "")) }
    fun removeRisk(index: Int) = update { f -> f.copy(risks = f.risks.filterIndexed { i, _ -> i != index }) }
    fun updateRisk(index: Int, transform: (RiskForm) -> RiskForm) =
        update { f -> f.copy(risks = f.risks.mapIndexed { i, r -> if (i == index) transform(r) else r }) }

    fun saveAndApprove(onDone: () -> Unit) {
        val snapshot = form
        if (_ui.value.saving || snapshot.problem != null) return
        _ui.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                actions.editAndApprove(planId, snapshot.toPlan(original))
                _ui.update { it.copy(saving = false) }
                onDone()
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, saveError = e.message ?: "Could not save") }
            }
        }
    }
}

private fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

private fun Plan.toForm() = PlanForm(
    title = title,
    summary = summary,
    objective = objective,
    definitionOfDone = definitionOfDone,
    estimatedTime = estimatedTime,
    steps = steps.sortedBy { it.order }.joinToString("\n") { it.description },
    tools = tools.joinToString(", "),
    dependencies = dependencies.joinToString("\n"),
    deliverables = deliverables.map { DeliverableForm(it.id, it.title, it.type, it.description) },
    risks = risks.map { RiskForm(it.risk, it.mitigation) },
)

/** Builds the plan to save. Steps are re-numbered; a step keeps its time estimate if its text is unchanged. */
private fun PlanForm.toPlan(original: Plan?): Plan {
    val oldSteps = original?.steps?.sortedBy { it.order }.orEmpty()
    return Plan(
        ideaId = original?.ideaId.orEmpty(),
        title = title.trim(),
        summary = summary.trim(),
        objective = objective.trim(),
        deliverables = deliverables.filter { it.title.isNotBlank() }
            .map { PlannedDeliverable(it.id, it.title.trim(), it.type, it.description.trim()) },
        steps = lines(steps).mapIndexed { i, text ->
            val old = oldSteps.getOrNull(i)?.takeIf { it.description.trim() == text }
            PlanStep(order = i + 1, description = text, estimatedTime = old?.estimatedTime.orEmpty())
        },
        tools = tools.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        estimatedTime = estimatedTime.trim(),
        dependencies = lines(dependencies),
        risks = risks.filter { it.risk.isNotBlank() }.map { PlanRisk(it.risk.trim(), it.mitigation.trim()) },
        definitionOfDone = definitionOfDone.trim(),
    )
}
