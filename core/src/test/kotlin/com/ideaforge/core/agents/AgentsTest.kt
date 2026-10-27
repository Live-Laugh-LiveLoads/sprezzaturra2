package com.ideaforge.core.agents

import com.google.common.truth.Truth.assertThat
import com.ideaforge.core.testing.FakeLlmProvider
import com.ideaforge.core.testing.TestPipeline
import com.ideaforge.core.transcript.Segment
import com.ideaforge.core.transcript.Transcript
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AgentsTest {

    @Test fun `prompt front matter yields version and body`() {
        val p = PromptFile.parse("---\nagent: x\nversion: 2.1\n---\nHello {{name}}")
        assertThat(p.version).isEqualTo("2.1")
        assertThat(p.body).isEqualTo("Hello {{name}}")
        assertThat(PromptFile.parse("No header").version).isEqualTo("unversioned")
    }

    @Test fun `verifier rejects corrections that add content and keeps unanswered segments`() = runTest {
        val llm = FakeLlmProvider { _, _ ->
            """{"segments":[{"id":0,"text":"We use Kubernetes."},
                {"id":1,"text":"I think we should also buy a boat, hire ten people and move to Spain next year."}],
               "changes":[{"segment_id":0,"original":"cubernetes","corrected":"Kubernetes","reason":"tech term"}],"flags":[]}"""
        }
        val p = TestPipeline(llm = llm)
        val raw = Transcript(listOf(
            Segment(0, 1000, "We use cubernetes."),
            Segment(1000, 2000, "I think so."),
            Segment(2000, 3000, "mumble", confidence = 0.2),
        ))

        val r = VerifierAgent(p.runner).verify(raw, 1, "", emptyMap())

        assertThat(r.cleaned.segments.map { it.text }).containsExactly("We use Kubernetes.", "I think so.", "mumble").inOrder()
        assertThat(r.output.changes.single().startMs).isEqualTo(0)
        assertThat(r.output.flags.map { it.reason }).containsExactly(
            "Verifier proposed a longer rewrite; kept the original wording: \"I think we should also buy a boat, hire ten people and move to Spain next year.\"",
            "Low transcription confidence",
        ).inOrder()
    }

    @Test fun `long transcripts are verified in windows`() = runTest {
        val p = TestPipeline()
        val raw = Transcript((0 until 30).map { Segment(it * 1000L, it * 1000L + 900, (1..100).joinToString(" ") { "w" }) })
        VerifierAgent(p.runner, windowWords = 1200).verify(raw, 1, "", emptyMap())
        assertThat(p.llm.requests).hasSize(3)
    }

    @Test fun `duplicate idea ids are made unique`() {
        val out = BrainstormerAgent.withUniqueIds(BrainstormOutput(
            listOf(CoreIdea("C1", "t", "s")),
            listOf(Idea("I1", "C1", "a", "d", effort = "low"), Idea("I1", "C1", "b", "d", effort = "low")),
        ))
        assertThat(out.ideas.map { it.id }).containsExactly("I1", "I1-2")
    }

    @Test fun `shortlist ranks by overall then fit`() {
        val ideas = BrainstormOutput(listOf(CoreIdea("C1", "t", "s")), listOf("I1", "I2", "I3").map { Idea(it, "C1", it, "d", effort = "low") })
        fun e(id: String, overall: Double, fit: Int) = IdeaEvaluation(id, CriteriaScores(5, 5, 5, 5, 5, 5, fit), overall = overall, rationale = "r")
        val eval = EvaluationOutput(listOf(e("I1", 6.0, 5), e("I2", 8.0, 5), e("I3", 8.0, 9)), BrainstormFeedback())
        val list = EvaluatorAgent.shortlist(ideas, eval, 2)
        assertThat(list.items.map { it.idea.id }).containsExactly("I3", "I2").inOrder()
        assertThat(list.items.map { it.rank }).containsExactly(1, 2).inOrder()
    }

    @Test fun `executor file names are made safe`() {
        assertThat(ExecutorAgent.safeFileName("My Plan: v2?.md", "document")).isEqualTo("My-Plan-v2.md")
        assertThat(ExecutorAgent.safeFileName("scrape.py", "code")).isEqualTo("scrape.py")
        assertThat(ExecutorAgent.safeFileName("../../etc/passwd", "document")).isEqualTo("passwd.md")
    }
}
