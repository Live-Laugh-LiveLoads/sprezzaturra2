package com.ideaforge.audio

import android.util.Log
import com.ideaforge.core.pipeline.PipelineState
import com.ideaforge.data.SessionActions
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.data.db.ChunkEntity
import com.ideaforge.data.db.SessionEntity
import com.ideaforge.data.db.SessionEventTypes
import com.ideaforge.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repairs recordings that were cut short because the process died or the phone rebooted.
 *
 * On Android 14+ a killed microphone service cannot be restarted from the background, so the
 * best we can do on the next launch is keep everything that reached disk: ADTS chunks stay
 * decodable when truncated, so they are remuxed, measured and finalized like normal chunks.
 */
@Singleton
class RecordingRecovery @Inject constructor(
    private val db: AppDatabase,
    private val actions: SessionActions,
    private val notifier: Notifier,
    private val controller: RecordingController,
) {
    /** Finalizes every session still marked as recording whose service is no longer running. */
    suspend fun recoverInterruptedRecordings() = withContext(Dispatchers.IO) {
        val live = controller.state.value.sessionId
        for (session in db.sessions().recording()) {
            // A recording started a moment ago (before this coroutine ran) is not an orphan.
            if (session.id == live || (RecordingService.isRunning && live == null)) continue
            try {
                recover(session)
            } catch (t: Throwable) {
                Log.e(TAG, "recovery of session ${session.id} failed", t)
            }
        }
    }

    private suspend fun recover(session: SessionEntity) {
        val id = session.id
        for (chunk in db.chunks().forSession(id)) {
            if (!chunk.finalized) recoverChunk(chunk)
        }
        val finalized = db.chunks().forSession(id).filter { it.finalized }
        val last = finalized.maxByOrNull { it.chunkIndex }
        val durationMs = if (last == null) 0L else last.startOffsetMs + last.durationMs
        db.sessions().setRecording(id, false, durationMs)

        val minutes = ((durationMs + 30_000) / 60_000).toInt()
        val saved = when {
            last == null -> "no audio could be recovered"
            minutes < 1 -> "less than a minute was saved"
            minutes == 1 -> "1 minute was saved"
            else -> "$minutes minutes were saved"
        }
        val message = "Recording ended unexpectedly; $saved."
        actions.logEvent(id, SessionEventTypes.RECOVERED, message)
        notifier.recordingStopped(id, message)
        if (last == null) {
            db.sessions().setState(id, PipelineState.FAILED.name, "No audio was recovered")
        } else {
            actions.runPipeline(id)
        }
    }

    /** Remuxes one leftover chunk, or removes it if nothing usable is on disk. */
    private suspend fun recoverChunk(chunk: ChunkEntity) {
        val source = File(chunk.path)
        val m4a = File(source.parentFile, source.nameWithoutExtension + ".m4a")
        try {
            if (source.isFile && source.length() > 0 && source.extension.equals("aac", ignoreCase = true)) {
                AdtsRemuxer.remux(source, m4a)
                val duration = AdtsRemuxer.durationMs(m4a)
                if (duration > 0) {
                    source.delete()
                    db.chunks().finalize(chunk.id, m4a.absolutePath, duration)
                    return
                }
            } else if (!source.exists() && m4a.isFile) {
                // Died between "delete .aac" and "update Room": the remuxed file is already there.
                val duration = AdtsRemuxer.durationMs(m4a)
                if (duration > 0) {
                    db.chunks().finalize(chunk.id, m4a.absolutePath, duration)
                    return
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "chunk ${chunk.id} is not recoverable", e)
        }
        source.delete()
        m4a.delete()
        db.chunks().delete(chunk.id)
    }

    private companion object {
        const val TAG = "RecordingRecovery"
    }
}
