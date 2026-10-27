package com.ideaforge.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.ideaforge.core.pipeline.SessionMode
import com.ideaforge.data.SessionActions
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.data.db.ChunkEntity
import com.ideaforge.data.db.SessionEventTypes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Turns a user-picked audio file into a ready-to-transcribe MEMO session. */
@Singleton
class AudioImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val actions: SessionActions,
) {
    /**
     * Copies [uri] into a new session as its only chunk, queues the pipeline and returns the new
     * session id. Nothing is left behind if the file cannot be copied or has no readable audio.
     */
    suspend fun import(uri: Uri): Long = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(uri)
        val extension = extensionFor(name, resolver.getType(uri))

        val session = actions.createSession(SessionMode.MEMO, null)
        val id = session.id
        try {
            val dir = File(session.audioDir).also { it.mkdirs() }
            val dest = File(dir, "chunk_000.$extension")
            val input = resolver.openInputStream(uri) ?: throw IOException("Could not open the selected file")
            input.use { src -> dest.outputStream().use { src.copyTo(it, COPY_BUFFER_BYTES) } }

            val durationMs = AdtsRemuxer.durationMs(dest)
            if (durationMs <= 0) throw IOException("The selected file has no audio that can be read")

            db.chunks().insert(
                ChunkEntity(
                    sessionId = id,
                    chunkIndex = 0,
                    path = dest.absolutePath,
                    startOffsetMs = 0,
                    durationMs = durationMs,
                    finalized = true,
                ),
            )
            db.sessions().setRecording(id, false, durationMs)
            name?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { actions.renameSession(id, it.take(80)) }
            actions.logEvent(id, SessionEventTypes.STARTED, "Imported audio file${name?.let { ": $it" } ?: ""}")
            actions.runPipeline(id)
            id
        } catch (t: Throwable) {
            // Do not leave an empty "recording" session behind.
            runCatching { actions.deleteSession(id) }
            throw t
        }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    companion object {
        /** Formats the transcription API accepts. */
        val SUPPORTED_EXTENSIONS = setOf("m4a", "mp3", "wav", "ogg", "webm", "flac", "mp4", "mpeg", "mpga", "oga")

        private const val COPY_BUFFER_BYTES = 64 * 1024

        /**
         * Picks the stored file extension. A recognised extension is kept. Otherwise the MIME type
         * decides: audio/mp4 means an m4a container, anything else keeps the original extension,
         * falling back to the MIME type's usual extension and finally m4a.
         */
        fun extensionFor(displayName: String?, mimeType: String?): String {
            val original = displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
                .filter { it.isLetterOrDigit() }.take(8)
            val mime = mimeType?.lowercase(Locale.ROOT)
            return when {
                original in SUPPORTED_EXTENSIONS -> original
                mime == "audio/mp4" -> "m4a"
                original.isNotEmpty() -> original
                else -> mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "m4a"
            }
        }
    }
}
