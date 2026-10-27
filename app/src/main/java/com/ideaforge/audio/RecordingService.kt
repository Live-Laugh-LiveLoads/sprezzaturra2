package com.ideaforge.audio

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ideaforge.R
import com.ideaforge.core.pipeline.PipelineState
import com.ideaforge.core.pipeline.SessionMode
import com.ideaforge.data.SessionActions
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.data.db.ChunkEntity
import com.ideaforge.data.db.SessionEventTypes
import com.ideaforge.notify.Notifier
import com.ideaforge.settings.SettingsRepository
import com.ideaforge.ui.Routes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Foreground (microphone) service that owns one recording from start to finish.
 *
 * It drives a [ChunkedAacRecorder], keeps Room and the shared [RecordingController] state in sync,
 * reacts to interruptions through [InterruptionMonitor], and shows the ongoing notification.
 * Returns START_NOT_STICKY: Android 14 forbids restarting a microphone service from the background,
 * so a killed recording is repaired by [RecordingRecovery] on the next launch instead.
 *
 * Threading: service state is touched on the main thread only. The recorder's audio-thread
 * callbacks just enqueue work onto [dbQueue], a single consumer, so database writes for one
 * session stay in order.
 */
@AndroidEntryPoint
class RecordingService : LifecycleService(), InterruptionMonitor.Host {
    @Inject lateinit var controller: RecordingController
    @Inject lateinit var db: AppDatabase
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var actions: SessionActions
    @Inject lateinit var notifier: Notifier

    private var sessionId = NO_SESSION
    private var mode: SessionMode = SessionMode.MEMO
    private var recorder: ChunkedAacRecorder? = null
    private var monitor: InterruptionMonitor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var paused = false
    private var pauseReason: String? = null
    private var stopping = false
    private var elapsedMs = 0L
    private val warnings = LinkedHashMap<String, String>()

    /** Completes once the recorder is running (or startup failed), so a quick Stop waits for it. */
    private val ready = CompletableDeferred<Unit>()

    private val dbQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** chunkIndex -> row id; only touched by the [dbQueue] consumer. */
    private val chunkIds = HashMap<Int, Long>()

    override val isPaused: Boolean get() = paused

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        lifecycleScope.launch(Dispatchers.IO) {
            for (job in dbQueue) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "recording bookkeeping failed", t)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Whatever started us, we must be in the foreground within seconds.
        try {
            enterForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "could not enter foreground", t)
            failStartup("Recording could not start: ${t.message}")
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> begin(intent.getLongExtra(EXTRA_SESSION_ID, NO_SESSION))
            ACTION_PAUSE -> pauseByUser()
            ACTION_RESUME -> resumeByUser()
            ACTION_STOP -> stopRecording(null)
            else -> if (sessionId == NO_SESSION) shutDown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Normal exits already ran shutDown(); this covers a system teardown mid-recording. Stopping
        // the recorder lets its thread close the files; the session is repaired by RecordingRecovery.
        monitor?.stop()
        recorder?.stop()
        releaseWakeLock()
        dbQueue.close()
        running.set(false)
        super.onDestroy()
    }

    // ---- Start --------------------------------------------------------------------------------

    private fun begin(id: Long) {
        if (id == NO_SESSION || sessionId != NO_SESSION) {
            if (sessionId == NO_SESSION) shutDown()
            return
        }
        sessionId = id
        lifecycleScope.launch {
            try {
                val session = db.sessions().get(id) ?: throw IOException("Session $id no longer exists")
                mode = runCatching { SessionMode.valueOf(session.mode) }.getOrDefault(SessionMode.MEMO)
                val dir = File(session.audioDir).also { it.mkdirs() }
                acquireWakeLock()

                val rec = ChunkedAacRecorder(settingsRepo.current.recording, dir, recorderListener(id))
                recorder = rec
                monitor = InterruptionMonitor(
                    context = this@RecordingService,
                    scope = lifecycleScope,
                    settings = { settingsRepo.current.recording },
                    audioDir = dir,
                    micSessionId = { rec.audioSessionId },
                    host = this@RecordingService,
                ).also { it.start() }
                lifecycleScope.launch {
                    combine(rec.elapsedMs, rec.level) { e, l -> e to l }.collect { (e, l) ->
                        elapsedMs = e
                        controller.update(id) { it.copy(elapsedMs = e, level = l) }
                    }
                }
                rec.start()
                refreshNotification()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "startup failed", t)
                failStartup("Recording could not start: ${t.message}")
            } finally {
                ready.complete(Unit)
            }
        }
    }

    /** Startup failed before any audio was captured: close the session so it is not stuck "recording". */
    private fun failStartup(message: String) {
        val id = sessionId
        lifecycleScope.launch(NonCancellable) {
            if (id != NO_SESSION) {
                runCatching {
                    db.sessions().setRecording(id, false, 0)
                    db.sessions().setState(id, PipelineState.FAILED.name, message)
                    actions.logEvent(id, SessionEventTypes.STOPPED, message)
                    notifier.recordingStopped(id, message)
                }
            }
            shutDown()
        }
    }

    // ---- Recorder callbacks (audio thread) ------------------------------------------------------

    private fun recorderListener(id: Long) = object : ChunkedAacRecorder.Listener {
        override fun onChunkStarted(index: Int, file: File, startOffsetMs: Long) {
            controller.update(id) { it.copy(chunkCount = index + 1) }
            dbQueue.trySend {
                val rowId = db.chunks().insert(
                    ChunkEntity(sessionId = id, chunkIndex = index, path = file.absolutePath, startOffsetMs = startOffsetMs),
                )
                chunkIds[index] = rowId
            }
        }

        override fun onChunkFinished(index: Int, file: File, durationMs: Long) {
            dbQueue.trySend { finalizeChunk(id, index, file, durationMs) }
        }

        override fun onChunkDiscarded(index: Int, file: File) {
            dbQueue.trySend { chunkIds.remove(index)?.let { db.chunks().delete(it) } }
        }

        override fun onFailure(error: Throwable) {
            lifecycleScope.launch {
                stopRecording("Recording stopped because of an error: ${error.message ?: error.javaClass.simpleName}. What was captured is saved.")
            }
        }

        override fun onMicUnavailable() {
            lifecycleScope.launch {
                if (stopping) return@launch
                paused = true
                pauseReason = "Microphone unavailable"
                controller.update(id) { it.copy(paused = true, pauseReason = pauseReason) }
                logEvent(SessionEventTypes.WARNING, "Could not resume: the microphone is in use by another app")
                refreshNotification()
            }
        }
    }

    /** Remuxes a closed chunk to .m4a, records it in Room and lets the pipeline transcribe it. */
    private suspend fun finalizeChunk(id: Long, index: Int, aac: File, durationMs: Long) {
        val rowId = chunkIds[index] ?: return
        if (AdtsRemuxer.durationMs(aac) == 0L) {
            // Nothing decodable in the file; drop the row instead of feeding transcription an empty file.
            aac.delete()
            chunkIds.remove(index)
            db.chunks().delete(rowId)
            return
        }
        val m4a = File(aac.parentFile, aac.nameWithoutExtension + ".m4a")
        var finalFile = aac
        try {
            AdtsRemuxer.remux(aac, m4a)
            aac.delete()
            finalFile = m4a
        } catch (e: IOException) {
            // Keep the raw ADTS audio rather than lose it; it is still playable and decodable.
            Log.w(TAG, "remux failed for ${aac.name}", e)
            m4a.delete()
            actions.logEvent(id, SessionEventTypes.WARNING, "Could not convert ${aac.name}: ${e.message}")
        }
        db.chunks().finalize(rowId, finalFile.absolutePath, durationMs)
        actions.runPipeline(id)
    }

    // ---- Pause / resume -----------------------------------------------------------------------

    private fun pauseByUser() {
        monitor?.onManualChange()
        pause("Paused", auto = false)
    }

    private fun resumeByUser() {
        monitor?.onManualChange()
        resume(auto = false)
    }

    private fun pause(reason: String, auto: Boolean) {
        val id = sessionId
        if (id == NO_SESSION || stopping) return
        if (paused) {
            if (auto && pauseReason != reason) {
                pauseReason = reason
                controller.update(id) { it.copy(pauseReason = reason) }
                refreshNotification()
            }
            return
        }
        paused = true
        pauseReason = reason
        recorder?.pause()
        controller.update(id) { it.copy(paused = true, pauseReason = reason, level = 0f) }
        logEvent(SessionEventTypes.PAUSED, reason)
        refreshNotification()
    }

    private fun resume(auto: Boolean) {
        val id = sessionId
        if (id == NO_SESSION || stopping || !paused) return
        paused = false
        pauseReason = null
        recorder?.resume()
        controller.update(id) { it.copy(paused = false, pauseReason = null) }
        logEvent(SessionEventTypes.RESUMED, if (auto) "Resumed automatically" else "Resumed")
        refreshNotification()
    }

    // ---- InterruptionMonitor.Host --------------------------------------------------------------

    override fun onAutoPause(reason: String) = pause(reason, auto = true)

    override fun onAutoResume() = resume(auto = true)

    override fun onWarning(kind: String, message: String?) {
        val id = sessionId
        if (id == NO_SESSION || stopping) return
        if (message == null) warnings.remove(kind) else warnings[kind] = message
        val shown = if (warnings.isEmpty()) null else warnings.values.joinToString("\n")
        controller.update(id) { it.copy(warning = shown) }
        if (message != null) logEvent(SessionEventTypes.WARNING, message)
        refreshNotification()
    }

    override fun onStopRequested(reason: String) = stopRecording(reason)

    // ---- Stop ---------------------------------------------------------------------------------

    /**
     * Ends the recording: finishes encoders, finalizes chunks, closes the session row, optionally
     * starts the pipeline. [reason] is non-null when the stop was not the user's (battery, storage,
     * error), in which case the user is notified.
     */
    private fun stopRecording(reason: String?) {
        val id = sessionId
        if (id == NO_SESSION || stopping) {
            if (id == NO_SESSION) shutDown()
            return
        }
        stopping = true
        refreshNotification()
        lifecycleScope.launch {
            withContext(NonCancellable) {
                try {
                    ready.await()
                    monitor?.stop()
                    val rec = recorder
                    var totalMs = elapsedMs
                    if (rec != null) {
                        rec.stop()
                        totalMs = rec.awaitStopped()
                    }
                    // Wait for every chunk to be remuxed and finalized before closing the session.
                    val drained = CompletableDeferred<Unit>()
                    dbQueue.trySend { drained.complete(Unit) }
                    drained.await()

                    val chunks = db.chunks().forSession(id).size
                    db.sessions().setRecording(id, false, totalMs)
                    actions.logEvent(id, SessionEventTypes.STOPPED, reason ?: "Recording stopped")
                    if (reason != null) notifier.recordingStopped(id, reason)
                    when {
                        chunks == 0 -> db.sessions().setState(id, PipelineState.FAILED.name, "No audio was captured")
                        settingsRepo.current.pipeline.autoStart -> actions.runPipeline(id)
                        else -> db.sessions().setState(id, PipelineState.AWAITING_REVIEW.name, "Recording saved. Continue when you are ready.")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "stop failed", t)
                    runCatching { db.sessions().setRecording(id, false, elapsedMs) }
                } finally {
                    shutDown()
                }
            }
        }
    }

    /** Releases everything and ends the service. Safe to call more than once. */
    private fun shutDown() {
        monitor?.stop()
        monitor = null
        recorder = null
        releaseWakeLock()
        controller.reset()
        sessionId = NO_SESSION
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Wake lock ----------------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (!settingsRepo.current.recording.useWakeLock || wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IdeaForge:recording").apply {
            setReferenceCounted(false)
            // A timeout guards against a leak if every release path were somehow skipped.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "wake lock release failed", e)
        }
    }

    // ---- Notification -------------------------------------------------------------------------

    private fun enterForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, Notifier.ID_RECORDING, buildNotification(), type)
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(Notifier.ID_RECORDING, buildNotification())
    }

    private fun buildNotification(): Notification {
        val title = when {
            stopping -> "Saving recording"
            paused -> "Recording paused"
            mode == SessionMode.MEETING -> "Recording meeting"
            else -> "Recording memo"
        }
        val text = when {
            stopping -> "Finishing up. Do not close the app."
            paused -> "${pauseReason ?: "Paused"} at ${formatElapsed(elapsedMs)}"
            warnings.isNotEmpty() -> warnings.values.last()
            else -> "Tap to open the recorder"
        }
        val builder = NotificationCompat.Builder(this, Notifier.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(notifier.openRoute(Routes.RECORDING, REQUEST_OPEN))
        if (!paused && !stopping) {
            // The chronometer counts wall time, so anchor it at "now minus audio time": paused
            // periods are excluded because the anchor is recomputed on every refresh (resume included).
            builder.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - elapsedMs)
        } else {
            builder.setUsesChronometer(false).setShowWhen(false)
        }
        if (!stopping) {
            if (paused) {
                builder.addAction(android.R.drawable.ic_media_play, "Resume", servicePendingIntent(ACTION_RESUME, REQUEST_RESUME))
            } else {
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", servicePendingIntent(ACTION_PAUSE, REQUEST_PAUSE))
            }
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", servicePendingIntent(ACTION_STOP, REQUEST_STOP))
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun logEvent(type: String, message: String) {
        val id = sessionId
        if (id == NO_SESSION) return
        lifecycleScope.launch { actions.logEvent(id, type, message) }
    }

    companion object {
        const val ACTION_START = "com.ideaforge.audio.START"
        const val ACTION_PAUSE = "com.ideaforge.audio.PAUSE"
        const val ACTION_RESUME = "com.ideaforge.audio.RESUME"
        const val ACTION_STOP = "com.ideaforge.audio.STOP"
        const val EXTRA_SESSION_ID = "sessionId"

        private const val TAG = "RecordingService"
        private const val NO_SESSION = -1L
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000
        private const val REQUEST_OPEN = 9001
        private const val REQUEST_PAUSE = 9002
        private const val REQUEST_RESUME = 9003
        private const val REQUEST_STOP = 9004

        private val running = AtomicBoolean(false)

        /** True while a service instance exists; [RecordingRecovery] uses it to tell live from dead recordings. */
        val isRunning: Boolean get() = running.get()

        /** m:ss or h:mm:ss. */
        fun formatElapsed(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = s % 3600 / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}
