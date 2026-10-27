package com.ideaforge.export


import com.ideaforge.core.transcript.Transcript
import com.ideaforge.core.transcript.formatTimestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders a session as one readable Markdown report. Pure functions over [ExportData] so the
 * layout is covered by JVM unit tests; SessionExporter only gathers the data.
 */
object MarkdownBuilder {

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun formatDate(epochMs: Long, zone: ZoneId): String =
        dateFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun build(d: ExportData): String = buildString {
        line("# ${d.title.ifBlank { "Untitled session" }}")
        blank()
        line("- **Date:** ${formatDate(d.createdAtMs, d.zone)}")
        line("- **Mode:** ${d.mode.lowercase().replaceFirstChar { it.uppercase() }}")
        line("- **Duration:** ${formatDuration(d.durationMs)}")
        line("- **Pipeline state:** ${d.pipelineState}")
        if (d.mode.equals("MEETING", ignoreCase = true)) {
            val consent = d.consentConfirmedAtMs?.let { "All-party recording consent confirmed at ${formatDate(it, d.zone)}" }
                ?: "No consent confirmation was recorded"
            line("- **Consent:** $consent")
        }
        if (d.speakers.isNotEmpty()) {
            line("- **Speakers:** " + d.speakers.entries.joinToString(", ") { (label, name) ->
                if (name == label || name.isBlank()) label else "$name ($label)"
            })
        }
        if (d.notes.isNotBlank()) {
            blank()
            line("## Notes")
            blank()
            line(d.notes.trim())
        }

        blank()
        line(if (d.transcriptIsRaw) "## Transcript (raw, not verified)" else "## Transcript (cleaned)")
        blank()
        if (d.transcript == null || d.transcript.segments.isEmpty()) {
            line("_No transcript available._")
        } else {
            line(transcriptMarkdown(d.transcript, d.speakers))
        }

        if (d.flags.isNotEmpty()) {
            blank()
            line("## Flagged passages")
            blank()
            d.flags.forEach { f ->
                line("- [${formatTimestamp(f.startMs)}-${formatTimestamp(f.endMs)}] \"${inline(f.text)}\" - ${inline(f.reason)}")
            }
        }
        if (d.changes.isNotEmpty()) {
            blank()
            line("## Verifier change log")
            blank()
            d.changes.forEach { c ->
                line("- [${formatTimestamp(c.startMs)}] \"${inline(c.original)}\" -> \"${inline(c.corrected)}\" (${inline(c.reason)})")
            }
        }

        if (d.shortlist.isNotEmpty()) {
            blank()
            line("## Final shortlist")
            blank()
            d.shortlist.forEach { s ->
                val score = s.overall?.let { " - score ${"%.1f".format(Locale.US, it)}" } ?: ""
                line("${s.rank}. **${inline(s.title)}** (${s.ideaId}, effort ${s.effort})$score")
                s.rationale?.takeIf { it.isNotBlank() }?.let { line("   - ${inline(it)}") }
            }
        }

        if (d.rounds.isNotEmpty()) {
            blank()
            line("## Debate history")
            d.rounds.sortedBy { it.round }.forEach { r -> roundSection(r) }
        }

        if (d.plans.isNotEmpty()) {
            blank()
            line("## Plans")
            d.plans.sortedBy { it.rank }.forEach { p ->
                blank()
                append(planMarkdown(p, headingLevel = 3))
            }
        }

        blank()
        line("## Usage and cost")
        blank()
        append(usageSection(d.usage))
    }.trimEnd() + "\n"

    /** `[mm:ss] Speaker: text` lines, one paragraph each, using display names. */
    fun transcriptMarkdown(t: Transcript, speakers: Map<String, String>): String =
        t.segments.joinToString("\n\n") { s ->
            val who = s.speaker?.let { (speakers[it]?.takeIf { n -> n.isNotBlank() } ?: it) + ": " } ?: ""
            "[${formatTimestamp(s.startMs)}] $who${s.text.trim()}"
        }

    /** A plan with every field, its status and its deliverables' full content. */
    fun planMarkdown(p: PlanData, headingLevel: Int = 1): String = buildString {
        val h = "#".repeat(headingLevel.coerceIn(1, 5))
        val h2 = "#".repeat((headingLevel + 1).coerceIn(2, 6))
        val plan = p.plan
        line("$h ${p.rank}. ${plan.title}")
        blank()
        line("- **Status:** ${p.status}${if (p.edited) " (edited by you before approval)" else ""}")
        p.rejectReason?.takeIf { it.isNotBlank() }?.let { line("- **Reject reason:** ${inline(it)}") }
        p.score?.let { line("- **Score:** ${"%.1f".format(Locale.US, it)}") }
        line("- **Effort:** ${p.effort}")
        line("- **Idea:** ${plan.ideaId}")
        line("- **Estimated time:** ${plan.estimatedTime}")
        blank()
        line("**Summary.** ${plan.summary.trim()}")
        blank()
        line("**Objective.** ${plan.objective.trim()}")
        blank()
        line("$h2 Deliverables")
        blank()
        if (plan.deliverables.isEmpty()) line("_None._")
        plan.deliverables.forEach { line("- **${inline(it.title)}** (${it.type}, ${it.id}): ${inline(it.description)}") }
        blank()
        line("$h2 Steps")
        blank()
        plan.steps.sortedBy { it.order }.forEach {
            val time = it.estimatedTime.takeIf { t -> t.isNotBlank() }?.let { t -> " _(${inline(t)})_" } ?: ""
            line("${it.order}. ${inline(it.description)}$time")
        }
        if (plan.tools.isNotEmpty()) {
            blank()
            line("**Tools.** ${plan.tools.joinToString(", ")}")
        }
        if (plan.dependencies.isNotEmpty()) {
            blank()
            line("$h2 Dependencies")
            blank()
            plan.dependencies.forEach { line("- ${inline(it)}") }
        }
        if (plan.risks.isNotEmpty()) {
            blank()
            line("$h2 Risks")
            blank()
            plan.risks.forEach { line("- ${inline(it.risk)} - mitigation: ${inline(it.mitigation)}") }
        }
        blank()
        line("**Definition of done.** ${plan.definitionOfDone.trim()}")
        if (p.deliverables.isNotEmpty()) {
            blank()
            line("$h2 Produced deliverables")
            p.deliverables.forEach { dv ->
                blank()
                line("**${inline(dv.title)}** (${dv.type}, `${dv.fileName}`, ${if (dv.complete) "complete" else "incomplete"})")
                if (dv.humanActions.isNotEmpty()) {
                    blank()
                    line("You still need to:")
                    dv.humanActions.forEach { line("- ${inline(it)}") }
                }
                blank()
                // Four backticks so deliverables that contain fenced code do not break out.
                line("````")
                line(dv.content?.trimEnd() ?: "(file no longer on disk)")
                line("````")
            }
        }
    }

    fun usageSection(rows: List<UsageRow>): String = buildString {
        if (rows.isEmpty()) {
            line("_No billed calls recorded._")
            return@buildString
        }
        val total = rows.sumOf { it.costUsd }
        val tokensIn = rows.sumOf { it.promptTokens.toLong() }
        val tokensOut = rows.sumOf { it.completionTokens.toLong() }
        val audioMin = rows.sumOf { it.audioMs } / 60_000.0
        line("- **Total cost:** ${money(total)} (estimate from the price table in Settings)")
        line("- **Tokens:** $tokensIn in, $tokensOut out")
        line("- **Audio transcribed:** ${"%.1f".format(Locale.US, audioMin)} min")
        blank()
        line("| Stage | Model | Calls | Tokens in | Tokens out | Audio min | Cost |")
        line("|---|---|---|---|---|---|---|")
        rows.groupBy { it.stage to it.model }.forEach { (key, group) ->
            line(
                "| ${cell(key.first)} | ${cell(key.second)} | ${group.size} | ${group.sumOf { it.promptTokens.toLong() }} | " +
                    "${group.sumOf { it.completionTokens.toLong() }} | " +
                    "${"%.1f".format(Locale.US, group.sumOf { it.audioMs } / 60_000.0)} | ${money(group.sumOf { it.costUsd })} |",
            )
        }
    }

    private fun StringBuilder.roundSection(r: RoundData) {
        blank()
        line("### Round ${r.round}")
        r.brainstorm?.let { b ->
            blank()
            line("**Core ideas**")
            blank()
            b.coreIdeas.forEach { line("- **${inline(it.title)}** (${it.id}): ${inline(it.summary)}") }
            blank()
            line("**Ideas**")
            blank()
            val evals = r.evaluation?.evaluations?.associateBy { it.ideaId }.orEmpty()
            b.ideas.forEach { idea ->
                val score = evals[idea.id]?.let { " - score ${"%.1f".format(Locale.US, it.overall)}" } ?: ""
                line("- **${inline(idea.title)}** (${idea.id}, effort ${idea.effort}, ${idea.style})$score: ${inline(idea.description)}")
            }
        }
        r.evaluation?.let { e ->
            blank()
            line("**Evaluator feedback**")
            blank()
            e.evaluations.forEach { ev ->
                line("- ${ev.ideaId}: ${"%.1f".format(Locale.US, ev.overall)} - ${inline(ev.rationale)}")
                ev.pros.forEach { line("  - pro: ${inline(it)}") }
                ev.cons.forEach { line("  - con: ${inline(it)}") }
            }
            val fb = e.feedback
            if (fb.gaps.isNotEmpty()) {
                blank()
                line("Gaps:")
                fb.gaps.forEach { line("- ${inline(it)}") }
            }
            if (fb.weakSpots.isNotEmpty()) {
                blank()
                line("Weak spots:")
                fb.weakSpots.forEach { line("- ${inline(it)}") }
            }
            if (fb.directions.isNotEmpty()) {
                blank()
                line("Directions to explore next:")
                fb.directions.forEach { line("- ${inline(it)}") }
            }
        }
    }

    private fun money(v: Double) = "$" + "%.4f".format(Locale.US, v)

    /** Collapses newlines so a model-written string stays on its list line. */
    private fun inline(s: String) = s.trim().replace(Regex("\\s*\\n\\s*"), " ")

    private fun cell(s: String) = inline(s).replace("|", "\\|")

    private fun StringBuilder.line(s: String) {
        append(s).append('\n')
    }

    private fun StringBuilder.blank() {
        if (isNotEmpty() && !endsWith("\n\n")) append('\n')
    }

}
