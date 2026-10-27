package com.ideaforge.core.agents

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire formats of the five agents. Field names match the JSON schemas in
 * app/src/main/assets/schemas, which is what the model is validated against; these classes are
 * what the app decodes after validation passes.
 */

enum class AgentId(val fileName: String, val displayName: String) {
    VERIFIER("verifier", "Transcript Verifier"),
    BRAINSTORMER("brainstormer", "Brainstormer"),
    EVALUATOR("evaluator", "Evaluator"),
    PLANNER("planner", "Planner"),
    EXECUTOR("executor", "Executor"),
}

// ---- Agent 1: Verifier -------------------------------------------------------------------------

@Serializable
data class VerifiedSegment(
    val id: Int,
    val text: String,
)

@Serializable
data class TranscriptChange(
    @SerialName("segment_id") val segmentId: Int,
    @SerialName("start_ms") val startMs: Long = 0,
    val original: String,
    val corrected: String,
    val reason: String,
)

@Serializable
data class FlaggedPassage(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
    val reason: String,
)

@Serializable
data class VerifierOutput(
    val segments: List<VerifiedSegment>,
    val changes: List<TranscriptChange> = emptyList(),
    val flags: List<FlaggedPassage> = emptyList(),
)

// ---- Agent 2: Brainstormer ---------------------------------------------------------------------

@Serializable
data class CoreIdea(
    val id: String,
    val title: String,
    val summary: String,
    @SerialName("timestamp_ms") val timestampMs: Long? = null,
    val kind: String = "idea",
)

@Serializable
data class Idea(
    val id: String,
    @SerialName("core_idea_id") val coreIdeaId: String,
    val title: String,
    val description: String,
    @SerialName("source_timestamp_ms") val sourceTimestampMs: Long? = null,
    val effort: String,
    val style: String = "conventional",
)

@Serializable
data class BrainstormOutput(
    @SerialName("core_ideas") val coreIdeas: List<CoreIdea>,
    val ideas: List<Idea>,
)

// ---- Agent 3: Evaluator ------------------------------------------------------------------------

@Serializable
data class CriteriaScores(
    val impact: Int,
    val feasibility: Int,
    val effort: Int,
    val cost: Int,
    val risk: Int,
    @SerialName("time_to_first_result") val timeToFirstResult: Int,
    val fit: Int,
)

@Serializable
data class IdeaEvaluation(
    @SerialName("idea_id") val ideaId: String,
    val scores: CriteriaScores,
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    val overall: Double,
    val rationale: String,
)

@Serializable
data class BrainstormFeedback(
    val gaps: List<String> = emptyList(),
    @SerialName("weak_spots") val weakSpots: List<String> = emptyList(),
    val directions: List<String> = emptyList(),
)

@Serializable
data class EvaluationOutput(
    val evaluations: List<IdeaEvaluation>,
    val feedback: BrainstormFeedback,
)

/** A shortlisted idea joined with its evaluation; computed in code, not by the model. */
@Serializable
data class ShortlistItem(
    val idea: Idea,
    val evaluation: IdeaEvaluation?,
    val rank: Int,
)

@Serializable
data class Shortlist(val items: List<ShortlistItem>)

// ---- Agent 4: Planner --------------------------------------------------------------------------

object DeliverableTypes {
    const val DOCUMENT = "document"
    const val CHECKLIST = "checklist"
    const val EMAIL_DRAFT = "email_draft"
    const val CODE = "code"
    const val SPREADSHEET_OUTLINE = "spreadsheet_outline"
    const val RESEARCH_BRIEF = "research_brief"
    const val PRESENTATION_OUTLINE = "presentation_outline"
    val ALL = listOf(DOCUMENT, CHECKLIST, EMAIL_DRAFT, CODE, SPREADSHEET_OUTLINE, RESEARCH_BRIEF, PRESENTATION_OUTLINE)
}

@Serializable
data class PlannedDeliverable(
    val id: String,
    val title: String,
    val type: String,
    val description: String,
)

@Serializable
data class PlanStep(
    val order: Int,
    val description: String,
    @SerialName("estimated_time") val estimatedTime: String = "",
)

@Serializable
data class PlanRisk(
    val risk: String,
    val mitigation: String,
)

@Serializable
data class Plan(
    @SerialName("idea_id") val ideaId: String,
    val title: String,
    val summary: String,
    val objective: String,
    val deliverables: List<PlannedDeliverable>,
    val steps: List<PlanStep>,
    val tools: List<String> = emptyList(),
    @SerialName("estimated_time") val estimatedTime: String,
    val dependencies: List<String> = emptyList(),
    val risks: List<PlanRisk> = emptyList(),
    @SerialName("definition_of_done") val definitionOfDone: String,
)

@Serializable
data class PlannerOutput(val plans: List<Plan>)

// ---- Agent 5: Executor -------------------------------------------------------------------------

@Serializable
data class ExecutedDeliverable(
    @SerialName("deliverable_id") val deliverableId: String,
    val title: String,
    val type: String,
    val filename: String,
    @SerialName("content_markdown") val contentMarkdown: String,
    val complete: Boolean,
    @SerialName("human_actions") val humanActions: List<String> = emptyList(),
)
