package com.ideaforge.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideaforge.core.agents.CriteriaScores
import com.ideaforge.core.pipeline.StageStatus
import com.ideaforge.data.db.IdeaEntity

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Ideas of one round, grouped by the core idea they grew from. */
@Composable
internal fun IdeasTab(vm: SessionViewModel) {
    val ideas by vm.ideas.collectAsStateWithLifecycle()
    // -1 means "latest round", so a new round showing up while the tab is open is followed.
    var picked by rememberSaveable { mutableIntStateOf(-1) }
    val rounds = remember(ideas) { ideas.map { it.round }.distinct().sorted() }
    if (rounds.isEmpty()) {
        CenteredHint("No ideas yet. They appear after the Brainstorm stage.")
        return
    }
    val round = if (picked in rounds) picked else rounds.last()
    val groups = remember(ideas, round) { ideas.filter { it.round == round }.groupBy { it.coreIdeaTitle } }
    val onSeek = remember(vm) { { ms: Long -> vm.seekTo(ms) } }

    Column(Modifier.fillMaxSize()) {
        if (rounds.size > 1) RoundSelector(rounds, round, onSelect = { picked = it })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { (core, list) ->
                item(key = "core-$round-$core") {
                    Text(core, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                items(list, key = { "idea-${it.id}" }) { idea -> IdeaCard(idea, onSeek) }
            }
        }
    }
}

@Composable
private fun IdeaCard(idea: IdeaEntity, onSeek: (Long) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                idea.shortlistRank?.let {
                    InfoChip("#$it", color = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                }
                Text(idea.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                idea.overall?.let { Text("%.1f".format(it), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            }
            Text(idea.description, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoChip("Effort: ${effortLabel(idea.effort)}")
                InfoChip(effortLabel(idea.style))
                idea.sourceTimestampMs?.let { SeekChip(it, onSeek) }
            }
        }
    }
}

/** Per-round evaluations, then the debate history between Brainstormer and Evaluator. */
@Composable
internal fun EvaluationTab(vm: SessionViewModel) {
    val rounds by vm.evaluationUi.collectAsStateWithLifecycle()
    var picked by rememberSaveable { mutableIntStateOf(-1) }
    if (rounds.isEmpty()) {
        CenteredHint("No evaluations yet. They appear after the Evaluate stage.")
        return
    }
    val roundNumbers = remember(rounds) { rounds.map { it.round } }
    val selected = if (picked in roundNumbers) picked else roundNumbers.last()
    val current = rounds.first { it.round == selected }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (roundNumbers.size > 1) {
            item(key = "selector") { RoundSelector(roundNumbers, selected, onSelect = { picked = it }) }
        }
        if (current.evaluations.isEmpty()) {
            item(key = "none") {
                Text(
                    current.evaluateNote ?: "Round $selected has no evaluations (${current.evaluateStatus?.name ?: "not run"}).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(current.evaluations, key = { "eval-$selected-${it.ideaId}" }) { item ->
            EvaluationCard(item, Modifier.padding(horizontal = 16.dp))
        }
        item(key = "history") {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Text("Debate history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        items(rounds, key = { "round-${it.round}" }) { r -> DebateRound(r, Modifier.padding(horizontal = 16.dp)) }
    }
}

private val CRITERIA_LABELS: List<Pair<String, (CriteriaScores) -> Int>> = listOf(
    "Impact" to { s: CriteriaScores -> s.impact },
    "Feasibility" to { s: CriteriaScores -> s.feasibility },
    "Effort" to { s: CriteriaScores -> s.effort },
    "Cost" to { s: CriteriaScores -> s.cost },
    "Risk" to { s: CriteriaScores -> s.risk },
    "Time to first result" to { s: CriteriaScores -> s.timeToFirstResult },
    "Fit" to { s: CriteriaScores -> s.fit },
)

@Composable
private fun EvaluationCard(item: EvalItem, modifier: Modifier = Modifier) {
    val e = item.evaluation
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("%.1f".format(e.overall), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
            CRITERIA_LABELS.forEach { (label, pick) ->
                val v = pick(e.scores)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(130.dp))
                    LinearProgressIndicator(progress = { (v / 10f).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
                    Text("$v", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(24.dp))
                }
            }
            BulletBlock("Pros", e.pros)
            BulletBlock("Cons", e.cons)
            Text(e.rationale, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BulletBlock(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    lines.forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun DebateRound(r: EvalRound, modifier: Modifier = Modifier) {
    val skipped = r.brainstormStatus == StageStatus.SKIPPED || r.evaluateStatus == StageStatus.SKIPPED
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Round ${r.round}", style = MaterialTheme.typography.titleSmall)
            if (skipped) {
                val note = listOfNotNull(r.brainstormNote.takeIf { r.brainstormStatus == StageStatus.SKIPPED }, r.evaluateNote.takeIf { r.evaluateStatus == StageStatus.SKIPPED })
                    .firstOrNull()
                Text("Skipped" + (note?.let { ": $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "Brainstormer: " + (r.ideaCount?.let { "$it ideas" } ?: r.brainstormStatus?.name?.lowercase() ?: "not run"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val f = r.feedback
                if (f != null) {
                    Text("Evaluator feedback", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    BulletBlock("Gaps", f.gaps)
                    BulletBlock("Weak spots", f.weakSpots)
                    BulletBlock("Directions to explore", f.directions)
                    if (f.gaps.isEmpty() && f.weakSpots.isEmpty() && f.directions.isEmpty()) {
                        Text("No further feedback.", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text("Evaluator: " + (r.evaluateStatus?.name?.lowercase() ?: "not run"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
