package com.ideaforge.core.agents

import com.ideaforge.core.json.PrettyJson
import com.ideaforge.core.transcript.Segment
import com.ideaforge.core.transcript.Transcript
import com.ideaforge.core.transcript.formatTimestamp
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Agent 1. Cleans the raw transcript without adding content.
 *
 * Long meetings are verified in windows of roughly [windowWords] words so the model never has to
 * reproduce a two-hour transcript in one reply. The model returns corrected text per segment id;
 * anything it drops keeps the raw text, and any "correction" that grows a segment well beyond the
 * original is rejected and flagged instead, which enforces "never add content that was not said".
 */
class VerifierAgent(
    private val runner: AgentRunner,
    private val windowWords: Int = 1200,
    private val lowConfidenceThreshold: Double = 0.45,
) {
    data class Result(
        val cleaned: Transcript,
        val output: VerifierOutput,
        val promptVersion: String,
        val model: String,
    )

    suspend fun verify(
        raw: Transcript,
        sessionId: Long?,
        glossary: String,
        speakerNames: Map<String, String>,
    ): Result {
        val cleanedSegments = raw.segments.toMutableList()
        val changes = mutableListOf<TranscriptChange>()
        val flags = mutableListOf<FlaggedPassage>()
        var version = ""
        var model = ""

        val windows = windows(raw.segments)
        windows.forEachIndexed { w, range ->
            val user = buildJsonObject {
                put("glossary", glossary)
                put("window", "${w + 1} of ${windows.size}")
                putJsonArray("segments") {
                    for (i in range) {
                        val s = raw.segments[i]
                        add(buildJsonObject {
                            put("id", i)
                            put("start_ms", s.startMs)
                            put("end_ms", s.endMs)
                            s.speaker?.let { put("speaker", speakerNames[it] ?: it) }
                            s.confidence?.let { put("confidence", "%.2f".format(it).toDouble()) }
                            put("text", s.text)
                        })
                    }
                }
            }.toString()
            val result = runner.call(AgentId.VERIFIER, sessionId, "VERIFY", user, VerifierOutput.serializer())
            version = result.promptVersion
            model = result.model

            val byId = result.value.segments.associateBy { it.id }
            for (i in range) {
                val original = raw.segments[i]
                val corrected = byId[i]?.text?.trim() ?: continue
                if (corrected.isEmpty() || corrected == original.text.trim()) continue
                if (addsContent(original.text, corrected)) {
                    flags += FlaggedPassage(original.startMs, original.endMs, original.text,
                        "Verifier proposed a longer rewrite; kept the original wording: \"$corrected\"")
                    continue
                }
                // Word timings no longer line up with corrected text, so drop them for this segment.
                cleanedSegments[i] = original.copy(text = corrected, words = emptyList())
            }
            changes += result.value.changes
                .filter { it.segmentId in range }
                .map { it.copy(startMs = raw.segments[it.segmentId].startMs) }
            flags += result.value.flags
        }

        // Always surface passages the transcription engine itself was unsure about.
        raw.segments.filter { (it.confidence ?: 1.0) < lowConfidenceThreshold }.forEach { s ->
            if (flags.none { it.startMs <= s.startMs && it.endMs > s.startMs }) {
                flags += FlaggedPassage(s.startMs, s.endMs, s.text, "Low transcription confidence")
            }
        }

        val output = VerifierOutput(
            segments = cleanedSegments.mapIndexed { i, s -> VerifiedSegment(i, s.text) },
            changes = changes,
            flags = flags.sortedBy { it.startMs },
        )
        return Result(raw.copy(segments = cleanedSegments), output, version, model)
    }

    private fun windows(segments: List<Segment>): List<IntRange> {
        if (segments.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var start = 0
        var words = 0
        segments.forEachIndexed { i, s ->
            words += s.text.split(' ').size
            if (words >= windowWords) {
                out += start..i
                start = i + 1
                words = 0
            }
        }
        if (start < segments.size) out += start until segments.size
        return out
    }

    companion object {
        /** A correction may fix words and punctuation; it may not grow the passage materially. */
        fun addsContent(original: String, corrected: String): Boolean {
            val a = original.trim().split(Regex("\\s+")).size
            val b = corrected.trim().split(Regex("\\s+")).size
            return b > a * 1.25 + 3
        }
    }
}

/** Agent 2. Extracts core ideas and generates options; revises from Evaluator feedback in later rounds. */
class BrainstormerAgent(private val runner: AgentRunner) {

    suspend fun brainstorm(
        transcript: Transcript,
        speakerNames: Map<String, String>,
        notes: String,
        optionsPerIdea: Int,
        round: Int,
        previous: BrainstormOutput?,
        feedback: EvaluationOutput?,
        sessionId: Long?,
    ): AgentResult<BrainstormOutput> {
        val user = buildString {
            appendLine("ROUND: $round")
            appendLine()
            appendLine("TRANSCRIPT (timestamps are [mm:ss] from the start of the recording):")
            appendLine(transcript.toTimestampedText(speakerNames))
            if (notes.isNotBlank()) {
                appendLine()
                appendLine("MY NOTES:")
                appendLine(notes)
            }
            if (previous != null && feedback != null) {
                appendLine()
                appendLine("YOUR PREVIOUS IDEAS:")
                appendLine(PrettyJson.encodeToString(BrainstormOutput.serializer(), previous))
                appendLine()
                appendLine("EVALUATOR SCORES AND FEEDBACK ON THEM:")
                appendLine(PrettyJson.encodeToString(EvaluationOutput.serializer(), feedback))
                appendLine()
                appendLine("Revise the full idea list using this feedback. Keep the id of any idea you keep or refine; give new ideas new ids.")
            }
        }
        val result = runner.call(
            AgentId.BRAINSTORMER, sessionId, "BRAINSTORM r$round", user, BrainstormOutput.serializer(),
            vars = mapOf("options_per_idea" to optionsPerIdea, "options_min" to maxOf(1, optionsPerIdea - 4), "options_max" to optionsPerIdea + 3),
        )
        return result.copy(value = withUniqueIds(result.value))
    }

    companion object {
        /** Models occasionally reuse an id; suffix duplicates so evaluations map one-to-one. */
        fun withUniqueIds(out: BrainstormOutput): BrainstormOutput {
            val seen = mutableSetOf<String>()
            return out.copy(ideas = out.ideas.map { idea ->
                var id = idea.id.ifBlank { "I" }
                var n = 2
                while (!seen.add(id)) id = "${idea.id}-${n++}"
                idea.copy(id = id)
            })
        }
    }
}

/** Agent 3. Scores ideas on explicit criteria and writes feedback for the Brainstormer. */
class EvaluatorAgent(private val runner: AgentRunner) {

    suspend fun evaluate(
        ideas: BrainstormOutput,
        transcript: Transcript,
        speakerNames: Map<String, String>,
        notes: String,
        round: Int,
        sessionId: Long?,
    ): AgentResult<EvaluationOutput> {
        val user = buildString {
            appendLine("TRANSCRIPT:")
            appendLine(transcript.toTimestampedText(speakerNames))
            if (notes.isNotBlank()) {
                appendLine()
                appendLine("NOTES FROM THE SPEAKER:")
                appendLine(notes)
            }
            appendLine()
            appendLine("IDEAS TO EVALUATE (round $round):")
            appendLine(PrettyJson.encodeToString(BrainstormOutput.serializer(), ideas))
        }
        return runner.call(AgentId.EVALUATOR, sessionId, "EVALUATE r$round", user, EvaluationOutput.serializer())
    }

    companion object {
        /**
         * Ranks ideas by overall score (ties broken by fit, then impact) and keeps the top [size].
         * Done in code so ranking is deterministic and survives hand edits to scores.
         */
        fun shortlist(ideas: BrainstormOutput, evaluation: EvaluationOutput?, size: Int): Shortlist {
            val evals = evaluation?.evaluations?.associateBy { it.ideaId }.orEmpty()
            val ranked = ideas.ideas
                .map { it to evals[it.id] }
                .sortedWith(
                    compareByDescending<Pair<Idea, IdeaEvaluation?>> { it.second?.overall ?: Double.NEGATIVE_INFINITY }
                        .thenByDescending { it.second?.scores?.fit ?: 0 }
                        .thenByDescending { it.second?.scores?.impact ?: 0 },
                )
                .take(size)
            return Shortlist(ranked.mapIndexed { i, (idea, e) -> ShortlistItem(idea, e, i + 1) })
        }

        /** Identity of a shortlist for early stopping: ids are unstable across rounds, titles are not. */
        fun shortlistKey(list: Shortlist): Set<String> =
            list.items.map { it.idea.title.lowercase().filter(Char::isLetterOrDigit) }.toSet()
    }
}

/** Agent 4. Turns one shortlisted idea into an actionable plan. */
class PlannerAgent(private val runner: AgentRunner) {

    suspend fun plan(
        item: ShortlistItem,
        transcriptSummary: String,
        notes: String,
        sessionId: Long?,
    ): AgentResult<Plan> {
        val user = buildString {
            appendLine("IDEA (rank ${item.rank}):")
            appendLine(PrettyJson.encodeToString(Idea.serializer(), item.idea))
            item.evaluation?.let {
                appendLine()
                appendLine("EVALUATION:")
                appendLine(PrettyJson.encodeToString(IdeaEvaluation.serializer(), it))
            }
            appendLine()
            appendLine("CONTEXT FROM THE RECORDING:")
            appendLine(transcriptSummary)
            if (notes.isNotBlank()) {
                appendLine()
                appendLine("NOTES:")
                appendLine(notes)
            }
        }
        val result = runner.call(
            AgentId.PLANNER, sessionId, "PLAN ${item.idea.id}", user, Plan.serializer(),
            vars = mapOf("deliverable_types" to DeliverableTypes.ALL.joinToString(", ")),
        )
        // Pin the plan to the idea it was asked about, whatever id the model echoed.
        return result.copy(value = result.value.copy(ideaId = item.idea.id))
    }
}

/** Agent 5. Produces one deliverable of an approved plan. */
class ExecutorAgent(private val runner: AgentRunner) {

    suspend fun execute(
        plan: Plan,
        deliverable: PlannedDeliverable,
        alreadyProduced: List<ExecutedDeliverable>,
        sessionId: Long?,
    ): AgentResult<ExecutedDeliverable> {
        val user = buildString {
            appendLine("APPROVED PLAN:")
            appendLine(PrettyJson.encodeToString(Plan.serializer(), plan))
            appendLine()
            appendLine("PRODUCE THIS DELIVERABLE NOW:")
            appendLine(PrettyJson.encodeToString(PlannedDeliverable.serializer(), deliverable))
            if (alreadyProduced.isNotEmpty()) {
                appendLine()
                appendLine("DELIVERABLES ALREADY PRODUCED FOR THIS PLAN (titles only, stay consistent with them):")
                alreadyProduced.forEach { appendLine("- ${it.title} (${it.filename})") }
            }
        }
        val result = runner.call(AgentId.EXECUTOR, sessionId, "EXECUTE ${deliverable.id}", user, ExecutedDeliverable.serializer())
        val v = result.value
        return result.copy(
            value = v.copy(
                deliverableId = deliverable.id,
                type = deliverable.type,
                filename = safeFileName(v.filename.ifBlank { deliverable.title }, deliverable.type),
            ),
        )
    }

    companion object {
        /** Keeps only the last path element and safe characters; non-code deliverables are .md. */
        fun safeFileName(name: String, type: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            val hasExt = base.contains('.') && !base.startsWith('.')
            val ext = if (type == DeliverableTypes.CODE && hasExt) base.substringAfterLast('.').take(8) else "md"
            val stem = (if (hasExt) base.substringBeforeLast('.') else base)
                .replace(Regex("[^A-Za-z0-9_ -]"), "").trim().replace(Regex("\\s+"), "-").take(60)
                .ifBlank { "deliverable" }
            return "$stem.$ext"
        }
    }
}

/** A short, timestamp-free digest of the transcript that keeps Planner prompts small. */
fun transcriptDigest(transcript: Transcript, maxChars: Int = 6000): String {
    val text = transcript.segments.joinToString("\n") { "[${formatTimestamp(it.startMs)}] ${it.text}" }
    return if (text.length <= maxChars) text else text.take(maxChars / 2) + "\n…\n" + text.takeLast(maxChars / 2)
}
