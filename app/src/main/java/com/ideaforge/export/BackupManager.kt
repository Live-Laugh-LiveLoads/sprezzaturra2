package com.ideaforge.export

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.sqlite.db.SimpleSQLiteQuery
import com.ideaforge.BuildConfig
import com.ideaforge.MainActivity
import com.ideaforge.core.json.AppJson
import com.ideaforge.core.json.PrettyJson
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** First entry of every backup; restore refuses archives without a recognised one. */
@Serializable
data class BackupManifest(
    val format: Int,
    val appVersion: String,
    val createdAt: Long,
    val dbSchemaVersion: Int,
)

/**
 * Whole-app backup and restore as one ZIP.
 *
 * A backup holds the Room database (with its WAL), `files/sessions` (audio), `files/deliverables`,
 * `files/prompts` (customised prompts) and the settings JSON. **API keys are not included**: they
 * live in the Android Keystore of this phone and could not be decrypted elsewhere, so after a
 * restore the keys must be entered again.
 *
 * **Restore replaces everything**: the database, audio, deliverables, custom prompts and settings
 * on this device are overwritten, and the app restarts afterwards because Room and the singletons
 * hold the old state in memory.
 */
@Singleton
class BackupManager @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) {

    private val dbFile: File get() = context.getDatabasePath(AppDatabase.NAME)

    /** Streams the backup ZIP to [out] and closes nothing: the caller owns the stream. */
    suspend fun backup(out: OutputStream) = withContext(Dispatchers.IO) {
        // Fold the write-ahead log into the main file so the copy is self-consistent.
        db.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).use { it.moveToFirst() }

        val zip = ZipOutputStream(BufferedOutputStream(out, BUFFER))
        val manifest = BackupManifest(FORMAT, BuildConfig.VERSION_NAME, System.currentTimeMillis(), DB_SCHEMA)
        zip.putText(MANIFEST, PrettyJson.encodeToString(BackupManifest.serializer(), manifest))

        for (suffix in listOf("", "-wal", "-shm")) {
            val f = File(dbFile.path + suffix)
            if (f.isFile) zip.putFile("database/${f.name}", f)
        }
        for (tree in TREES) {
            val root = File(context.filesDir, tree)
            if (!root.isDirectory) continue
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putFile("files/$tree/" + f.relativeTo(root).invariantSeparatorsPath, f)
            }
        }
        zip.putText(SETTINGS, settings.exportJson())
        // finish() writes the central directory without closing the caller's stream.
        zip.finish()
        zip.flush()
    }

    /**
     * Validates, stages and installs the backup in [input], then restarts the process. Does not
     * return normally on success. Any failure before the swap leaves the current data untouched.
     */
    suspend fun restore(input: InputStream) = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore-staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            extract(input, staging)
            val settingsJson = File(staging, SETTINGS).takeIf { it.isFile }?.readText()
                ?: throw IOException("This backup has no settings.json")
            if (!File(staging, "database/${AppDatabase.NAME}").isFile) throw IOException("This backup has no database")

            // Point of no return: nothing may use the database from here on.
            db.close()
            for (suffix in listOf("", "-wal", "-shm")) File(dbFile.path + suffix).delete()
            dbFile.parentFile?.mkdirs()
            File(staging, "database").listFiles()?.forEach { f ->
                f.copyTo(File(dbFile.parentFile, f.name), overwrite = true)
            }
            for (tree in TREES) {
                val target = File(context.filesDir, tree)
                target.deleteRecursively()
                val staged = File(staging, "files/$tree")
                if (staged.isDirectory) {
                    // Rename is instant on the same volume; copy only if the volumes differ.
                    if (!staged.renameTo(target)) staged.copyRecursively(target, overwrite = true)
                } else {
                    target.mkdirs()
                }
            }
            settings.importJson(settingsJson)
            // importJson saves with apply() (asynchronous). A commit() writes the whole map and
            // waits, so the restored settings are on disk before the process is killed below.
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putLong("restored_at", System.currentTimeMillis()).commit()
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
        staging.deleteRecursively()
        restartApp()
    }

    /**
     * Reads the archive into [staging]. The manifest must be the first entry; unknown entries are
     * skipped and every path is checked against zip-slip before anything is written.
     */
    private fun extract(input: InputStream, staging: File) {
        val zip = ZipInputStream(input.buffered(BUFFER))
        val first = zip.nextEntry ?: throw IOException("The file is empty or not a ZIP archive")
        if (first.name != MANIFEST) throw IOException("Not an Idea Forge backup (no manifest)")
        val manifest = try {
            AppJson.decodeFromString(BackupManifest.serializer(), String(zip.readNBytesLimited(MAX_MANIFEST), Charsets.UTF_8))
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("The backup manifest is unreadable")
        }
        if (manifest.format != FORMAT) throw IOException("Unsupported backup format ${manifest.format}")
        if (manifest.dbSchemaVersion != DB_SCHEMA) {
            throw IOException("This backup uses database schema ${manifest.dbSchemaVersion}; this app understands $DB_SCHEMA")
        }
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory || !isWanted(entry)) continue
            val dest = ZipPaths.resolveSafe(staging, entry.name)
            dest.parentFile?.mkdirs()
            dest.outputStream().buffered(BUFFER).use { zip.copyTo(it, BUFFER) }
        }
    }

    private fun isWanted(entry: ZipEntry): Boolean =
        entry.name == SETTINGS || entry.name.startsWith("database/") ||
            TREES.any { entry.name.startsWith("files/$it/") }

    private fun ZipInputStream.readNBytesLimited(limit: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > limit) throw IOException("The backup manifest is too large")
        }
        return buf.toByteArray()
    }

    /**
     * Schedules a fresh launch and ends the process. The alarm survives our death; Android may
     * refuse a background activity start, in which case the user simply reopens the app.
     */
    private fun restartApp() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY_MS, pending)
        Runtime.getRuntime().exit(0)
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

    companion object {
        const val FORMAT = 1
        const val DB_SCHEMA = 1
        private const val MANIFEST = "manifest.json"
        private const val SETTINGS = "settings.json"
        private val TREES = listOf("sessions", "deliverables", "prompts")
        private const val BUFFER = 64 * 1024
        private const val MAX_MANIFEST = 64 * 1024
        private const val RESTART_DELAY_MS = 500L
    }
}
