package com.ideaforge.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ideaforge.core.agents.BrainstormOutput
import com.ideaforge.core.agents.EvaluationOutput
import com.ideaforge.core.agents.Plan
import com.ideaforge.core.agents.VerifierOutput
import com.ideaforge.core.json.AppJson
import com.ideaforge.core.json.PrettyJson
import com.ideaforge.core.pipeline.Stage
import com.ideaforge.core.transcript.Transcript
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.data.db.DeliverableEntity
import com.ideaforge.data.db.PlanEntity
import com.ideaforge.data.db.SessionEntity
import com.ideaforge.data.db.StageRunEntity
import com.ideaforge.data.db.TranscriptEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports one session as a single Markdown report or as a ZIP with everything (audio, every
 * transcript version, stage outputs, plans, deliverables, usage, events). Files go to
 * `cacheDir/exports/`, which is the FileProvider's `exports` path; files older than a day are
 * deleted on every export so the cache cannot grow without bound.
 */
@Singleton
class SessionExporter @Inject constructor(
    private val db: AppDatabase,
    @ApplicationContext private val context: Context,
) {

    private val exportDir: File get() = File(context.cacheDir, "exports")

    /** Writes the human-readable report and returns the file. */
    suspend fun exportMarkdown(sessionId: Long): File = withContext(Dispatchers.IO) {
        cleanOldExports()
        val loaded = load(sessionId)
        val file = File(exportDir.apply { mkdirs() }, "idea-forge-$sessionId-${ZipPaths.slug(loaded.session.title)}.md")
        file.writeText(MarkdownBuilder.build(loaded.exportData))
        file
    }

    /** Writes the full ZIP archive and returns the file. Audio is streamed, never loaded whole. */
    suspend fun exportZip(sessionId: Long): File = withContext(Dispatchers.IO) {
        cleanOldExports()
        val loaded = load(sessionId)
        val file = File(exportDir.apply { mkdirs() }, "idea-forge-$sessionId-${ZipPaths.slug(loaded.session.title)}.zip")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(file), BUFFER)).use { zip -> writeZip(zip, loaded) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        file
    }

    /**
     * An ACTION_SEND chooser for [file] through the app's FileProvider. The read grant is on the
     * intent and on the ClipData so the chosen app can open the content URI.
     */
    fun shareIntent(file: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun cleanOldExports() {
        val cutoff = System.currentTimeMillis() - ONE_DAY_MS
        exportDir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }

    // ---- loading -----------------------------------------------------------------------------

    /** Rows read from Room, kept together because both export formats need several of them. */
    private class Loaded(
        val session: SessionEntity,
        val transcripts: List<TranscriptEntity>,
        val stages: List<StageRunEntity>,
        val plans: List<PlanEntity>,
        val deliverables: List<DeliverableEntity>,
        val exportData: ExportData,
    )

    private suspend fun load(sessionId: Long): Loaded {
        val session = db.sessions().get(sessionId) ?: error("Session $sessionId no longer exists")
        val speakers = db.speakers().forSession(sessionId).associate { it.label to it.displayName }
        val transcripts = db.transcripts().allForSession(sessionId)
        val stages = db.stages().forSession(sessionId)
        val plans = db.plans().forSession(sessionId)
        val deliverables = db.deliverables().forSession(sessionId)
        val ideas = db.ideas().observeForSession(sessionId).first()
        val usage = db.usage().forSession(sessionId)

        val cleaned = transcripts.lastOrNull { it.kind == TranscriptEntity.CLEANED }
        val raw = transcripts.lastOrNull { it.kind == TranscriptEntity.RAW }
        val shown = cleaned ?: raw
        val transcript = shown?.let { runCatching { AppJson.decodeFromString(Transcript.serializer(), it.json) }.getOrNull() }

        val verifier = stages.firstOrNull { it.stage == Stage.VERIFY.name }
            ?.effective()?.let { runCatching { AppJson.decodeFromString(VerifierOutput.serializer(), it) }.getOrNull() }

        val rounds = stages
            .filter { it.stage == Stage.BRAINSTORM.name || it.stage == Stage.EVALUATE.name }
            .groupBy { it.round }
            .toSortedMap()
            .map { (round, rows) ->
                RoundData(
                    round = round,
                    brainstorm = rows.firstOrNull { it.stage == Stage.BRAINSTORM.name }?.effective()
                        ?.let { runCatching { AppJson.decodeFromString(BrainstormOutput.serializer(), it) }.getOrNull() },
                    evaluation = rows.firstOrNull { it.stage == Stage.EVALUATE.name }?.effective()
                        ?.let { runCatching { AppJson.decodeFromString(EvaluationOutput.serializer(), it) }.getOrNull() },
                )
            }

        // The shortlist is flagged on the idea rows of the last round that produced one.
        val finalRound = ideas.filter { it.shortlistRank != null }.maxOfOrNull { it.round }
        val shortlist = ideas
            .filter { it.shortlistRank != null && it.round == finalRound }
            .sortedBy { it.shortlistRank }
            .map { ShortlistRow(it.shortlistRank!!, it.ideaId, it.title, it.overall, it.effort, it.rationale) }

        val planData = plans.mapNotNull { p ->
            val plan = p.effectivePlan() ?: return@mapNotNull null
            PlanData(
                rank = p.rank,
                status = p.status,
                rejectReason = p.rejectReason,
                score = p.score,
                effort = p.effort,
                edited = p.editedPlanJson != null,
                plan = plan,
                deliverables = deliverables.filter { it.planId == p.id }.map { d ->
                    val f = File(d.path)
                    DeliverableData(
                        title = d.title,
                        type = d.type,
                        fileName = d.fileName,
                        complete = d.complete,
                        humanActions = parseStrings(d.humanActionsJson),
                        content = if (f.isFile) runCatching { f.readText() }.getOrNull() else null,
                    )
                },
            )
        }

        val data = ExportData(
            title = session.title,
            mode = session.mode,
            createdAtMs = session.createdAt,
            durationMs = session.durationMs,
            pipelineState = session.pipelineState,
            consentConfirmedAtMs = session.consentConfirmedAt,
            notes = session.userNotes,
            speakers = speakers,
            transcript = transcript,
            transcriptIsRaw = cleaned == null,
            flags = verifier?.flags.orEmpty(),
            changes = verifier?.changes.orEmpty(),
            shortlist = shortlist,
            rounds = rounds,
            plans = planData,
            usage = usage.map { UsageRow(it.stage, it.model, it.promptTokens, it.completionTokens, it.audioMs, it.costUsd) },
        )
        return Loaded(session, transcripts, stages, plans, deliverables, data)
    }

    private fun StageRunEntity.effective(): String? = editedJson ?: outputJson

    private fun PlanEntity.effectivePlan(): Plan? =
        runCatching { AppJson.decodeFromString(Plan.serializer(), editedPlanJson ?: planJson) }.getOrNull()

    private fun parseStrings(json: String): List<String> =
        runCatching { AppJson.decodeFromString(ListSerializer(String.serializer()), json) }
            .getOrDefault(emptyList())

    // ---- ZIP ---------------------------------------------------------------------------------

    private suspend fun writeZip(zip: ZipOutputStream, l: Loaded) {
        val sessionId = l.session.id
        zip.putText("session.md", MarkdownBuilder.build(l.exportData))

        // Audio is already compressed AAC, so store it without deflating and copy in 64 KB steps.
        val chunks = db.chunks().forSession(sessionId)
        zip.setLevel(Deflater.NO_COMPRESSION)
        val usedAudio = mutableSetOf<String>()
        chunks.forEach { c ->
            val f = File(c.path)
            if (f.isFile) {
                val name = uniqueName(usedAudio, "audio/${"%03d".format(c.chunkIndex)}-${ZipPaths.safeFileName(f.name)}")
                zip.putFile(name, f)
            }
        }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)

        // Transcripts: the latest of each kind under a stable name, every version under raw_vN.
        val names = l.exportData.speakers
        for (kind in listOf(TranscriptEntity.RAW, TranscriptEntity.CLEANED)) {
            val versions = l.transcripts.filter { it.kind == kind }
            val base = kind.lowercase()
            versions.forEach { t ->
                zip.putText("transcripts/${base}_v${t.version}.json", pretty(t.json))
                zip.putText("transcripts/${base}_v${t.version}.md", transcriptFile(t.json, names))
            }
            versions.maxByOrNull { it.version }?.let { t ->
                zip.putText("transcripts/$base.json", pretty(t.json))
                zip.putText("transcripts/$base.md", transcriptFile(t.json, names))
            }
        }

        l.stages.sortedWith(compareBy({ Stage.valueOf(it.stage).position(it.round) }, { it.round })).forEach { s ->
            val base = "stages/${s.stage}_r${s.round}"
            val effective = s.effective()
            if (effective != null) zip.putText("$base.json", pretty(effective))
            if (s.editedJson != null && s.outputJson != null) zip.putText("$base.original.json", pretty(s.outputJson))
        }

        val planDirs = HashMap<Long, String>()
        l.plans.sortedBy { it.rank }.forEach { p ->
            val dir = "${p.rank}-${ZipPaths.slug(p.title)}"
            planDirs[p.id] = dir
            zip.putText("plans/$dir.json", pretty(p.editedPlanJson ?: p.planJson))
            if (p.editedPlanJson != null) zip.putText("plans/$dir.original.json", pretty(p.planJson))
            l.exportData.plans.firstOrNull { it.rank == p.rank }?.let { zip.putText("plans/$dir.md", MarkdownBuilder.planMarkdown(it)) }
        }

        val usedDeliverables = mutableSetOf<String>()
        l.deliverables.forEach { d ->
            val f = File(d.path)
            if (!f.isFile) return@forEach
            val dir = planDirs[d.planId] ?: "plan-${d.planId}"
            zip.putFile(uniqueName(usedDeliverables, "deliverables/$dir/${ZipPaths.safeFileName(d.fileName, "deliverable-${d.id}")}"), f)
        }

        val usage = db.usage().forSession(sessionId).map {
            UsageExport(it.sessionId, it.stage, it.model, it.promptTokens, it.completionTokens, it.audioMs, it.costUsd, it.atEpochMs)
        }
        zip.putText("usage.json", PrettyJson.encodeToString(ListSerializer(UsageExport.serializer()), usage))
        val events = db.events().forSession(sessionId).map { EventExport(it.atEpochMs, it.type, it.message) }
        zip.putText("events.json", PrettyJson.encodeToString(ListSerializer(EventExport.serializer()), events))
    }

    private fun transcriptFile(json: String, speakers: Map<String, String>): String =
        runCatching { AppJson.decodeFromString(Transcript.serializer(), json) }
            .map { MarkdownBuilder.transcriptMarkdown(it, speakers) + "\n" }
            .getOrElse { "(transcript could not be read)\n" }

    /** Re-indents stored JSON for humans; falls back to the stored text if it does not parse. */
    private fun pretty(json: String): String =
        runCatching { PrettyJson.encodeToString(JsonElement.serializer(), AppJson.parseToJsonElement(json)) }
            .getOrDefault(json)

    private fun uniqueName(used: MutableSet<String>, wanted: String): String {
        if (used.add(wanted)) return wanted
        val dot = wanted.lastIndexOf('.').takeIf { it > wanted.lastIndexOf('/') } ?: wanted.length
        var n = 2
        while (true) {
            val candidate = wanted.substring(0, dot) + "-$n" + wanted.substring(dot)
            if (used.add(candidate)) return candidate
            n++
        }
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this, BUFFER) }
        closeEntry()
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val ONE_DAY_MS = 24L * 60 * 60 * 1000
    }
}
