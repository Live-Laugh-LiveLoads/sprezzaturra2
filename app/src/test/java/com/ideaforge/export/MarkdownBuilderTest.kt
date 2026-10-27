package com.ideaforge.export

import com.google.common.truth.Truth.assertThat
import com.ideaforge.core.agents.BrainstormFeedback
import com.ideaforge.core.agents.BrainstormOutput
import com.ideaforge.core.agents.CoreIdea
import com.ideaforge.core.agents.CriteriaScores
import com.ideaforge.core.agents.EvaluationOutput
import com.ideaforge.core.agents.FlaggedPassage
import com.ideaforge.core.agents.Idea
import com.ideaforge.core.agents.IdeaEvaluation
import com.ideaforge.core.agents.Plan
import com.ideaforge.core.agents.PlanRisk
import com.ideaforge.core.agents.PlanStep
import com.ideaforge.core.agents.PlannedDeliverable
import com.ideaforge.core.agents.TranscriptChange
import com.ideaforge.core.transcript.Segment
import com.ideaforge.core.transcript.Transcript
import org.junit.Test
import java.time.ZoneOffset

class MarkdownBuilderTest {

    private val plan = Plan(
        ideaId = "I1", title = "Ship the beta", summary = "Summary text", objective = "Get users",
        deliverables = listOf(PlannedDeliverable("D1", "Launch checklist", "checklist", "What to do")),
        steps = listOf(PlanStep(1, "Write copy", "2h")),
        tools = listOf("Notion"), estimatedTime = "1 week", dependencies = listOf("Domain"),
        risks = listOf(PlanRisk("Delay", "Cut scope")), definitionOfDone = "Ten users signed up",
    )

    private fun data(mode: String = "MEETING", consent: Long? = 1_700_000_000_000) = ExportData(
        title = "Roadmap chat", mode = mode, createdAtMs = 1_700_000_000_000, durationMs = 3_725_000,
        pipelineState = "COMPLETE", consentConfirmedAtMs = consent, notes = "",
        speakers = mapOf("Speaker 1" to "Alice", "Speaker 2" to "Speaker 2"),
        transcript = Transcript(
            listOf(
                Segment(0, 4_000, " Hello there ", speaker = "Speaker 1"),
                Segment(65_000, 70_000, "Hi", speaker = "Speaker 2"),
                Segment(72_000, 75_000, "No speaker"),
            ),
        ),
        transcriptIsRaw = false,
        flags = listOf(FlaggedPassage(1_000, 2_000, "mumble", "inaudible")),
        changes = listOf(TranscriptChange(0, 0, "helo", "hello", "typo")),
        shortlist = listOf(ShortlistRow(1, "I1", "Beta launch", 8.456, "S", "Fast and cheap")),
        rounds = listOf(
            RoundData(
                1,
                BrainstormOutput(
                    listOf(CoreIdea("C1", "Launch", "Launch it")),
                    listOf(Idea("I1", "C1", "Beta launch", "Do a beta", effort = "S")),
                ),
                EvaluationOutput(
                    listOf(IdeaEvaluation("I1", CriteriaScores(8, 8, 8, 8, 8, 8, 8), listOf("cheap"), listOf("small"), 8.4, "Good")),
                    BrainstormFeedback(gaps = listOf("No pricing idea")),
                ),
            ),
        ),
        plans = listOf(
            PlanData(
                1, "REJECTED", "too big", 8.4, "S", true, plan,
                listOf(DeliverableData("Checklist", "checklist", "check.md", true, listOf("Send it"), "- [ ] one\n```kotlin\nx\n```")),
            ),
        ),
        usage = listOf(
            UsageRow("VERIFY", "grok", 1000, 200, 0, 0.01),
            UsageRow("VERIFY", "grok", 500, 100, 0, 0.005),
            UsageRow("TRANSCRIBE", "whisper-1", 0, 0, 120_000, 0.012),
        ),
        zone = ZoneOffset.UTC,
    )

    @Test
    fun headerShowsDateModeDurationAndConsent() {
        val md = MarkdownBuilder.build(data())
        assertThat(md).startsWith("# Roadmap chat")
        assertThat(md).contains("**Date:** 2023-11-14 22:13:20")
        assertThat(md).contains("**Mode:** Meeting")
        assertThat(md).contains("**Duration:** 1h 2m 5s")
        assertThat(md).contains("All-party recording consent confirmed at 2023-11-14 22:13:20")
        assertThat(md).contains("Alice (Speaker 1)")
    }

    @Test
    fun memoHasNoConsentLine() {
        assertThat(MarkdownBuilder.build(data(mode = "MEMO", consent = null))).doesNotContain("Consent")
    }

    @Test
    fun meetingWithoutConfirmationSaysSo() {
        assertThat(MarkdownBuilder.build(data(consent = null))).contains("No consent confirmation was recorded")
    }

    @Test
    fun transcriptUsesTimestampsAndDisplayNames() {
        val md = MarkdownBuilder.build(data())
        assertThat(md).contains("[00:00] Alice: Hello there")
        assertThat(md).contains("[01:05] Speaker 2: Hi")
        assertThat(md).contains("[01:12] No speaker")
    }

    @Test
    fun includesFlagsChangesShortlistAndDebate() {
        val md = MarkdownBuilder.build(data())
        assertThat(md).contains("## Flagged passages")
        assertThat(md).contains("inaudible")
        assertThat(md).contains("\"helo\" -> \"hello\"")
        assertThat(md).contains("1. **Beta launch** (I1, effort S) - score 8.5")
        assertThat(md).contains("### Round 1")
        assertThat(md).contains("No pricing idea")
        assertThat(md).contains("pro: cheap")
    }

    @Test
    fun planShowsStatusRejectReasonAndDeliverableContent() {
        val md = MarkdownBuilder.build(data())
        assertThat(md).contains("**Status:** REJECTED (edited by you before approval)")
        assertThat(md).contains("**Reject reason:** too big")
        assertThat(md).contains("Ten users signed up")
        assertThat(md).contains("1. Write copy _(2h)_")
        assertThat(md).contains("- [ ] one")
        assertThat(md).contains("````")
    }

    @Test
    fun usageSummaryAggregatesPerStageAndModel() {
        val md = MarkdownBuilder.build(data())
        assertThat(md).contains("**Total cost:** $0.0270")
        assertThat(md).contains("| VERIFY | grok | 2 | 1500 | 300 | 0.0 | $0.0150 |")
        assertThat(md).contains("| TRANSCRIBE | whisper-1 | 1 | 0 | 0 | 2.0 | $0.0120 |")
    }

    @Test
    fun rawTranscriptIsLabelled() {
        val md = MarkdownBuilder.build(data().copy(transcriptIsRaw = true))
        assertThat(md).contains("## Transcript (raw, not verified)")
    }

    @Test
    fun emptySessionStillRenders() {
        val md = MarkdownBuilder.build(
            data().copy(transcript = null, flags = emptyList(), changes = emptyList(), shortlist = emptyList(), rounds = emptyList(), plans = emptyList(), usage = emptyList()),
        )
        assertThat(md).contains("_No transcript available._")
        assertThat(md).contains("_No billed calls recorded._")
        assertThat(md).doesNotContain("## Plans")
    }
}
