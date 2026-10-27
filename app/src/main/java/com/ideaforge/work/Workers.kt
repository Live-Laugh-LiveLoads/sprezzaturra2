package com.ideaforge.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ideaforge.core.llm.ProviderException
import com.ideaforge.core.pipeline.PipelineOrchestrator
import com.ideaforge.core.pipeline.PipelineState
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.notify.Notifier
import com.ideaforge.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues background work. One unique chain per session ("pipeline-<id>") serializes chunk
 * transcription during a meeting and the full pipeline afterwards, so two runs never race.
 * All network work waits for connectivity and retries with exponential backoff, which is what
 * makes airplane mode safe: work queues up and resumes when the phone is back online.
 */
@Singleton
class WorkScheduler @Inject constructor(private val workManager: WorkManager) {

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueuePipeline(sessionId: Long, requireNetwork: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<PipelineWorker>()
            .setInputData(workDataOf(KEY_SESSION to sessionId))
            .setConstraints(if (requireNetwork) network else Constraints.NONE)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_PIPELINE)
            .addTag("session-$sessionId")
            .build()
        workManager.enqueueUniqueWork("pipeline-$sessionId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun enqueueExecution(planId: Long) {
        val request = OneTimeWorkRequestBuilder<ExecutePlanWorker>()
            .setInputData(workDataOf(KEY_PLAN to planId))
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_EXECUTE)
            .build()
        workManager.enqueueUniqueWork("execute-$planId", ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodicMaintenance() {
        val request = PeriodicWorkRequestBuilder<AudioCleanupWorker>(1, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork("audio-cleanup", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelSession(sessionId: Long) {
        workManager.cancelUniqueWork("pipeline-$sessionId")
    }

    companion object {
        const val KEY_SESSION = "sessionId"
        const val KEY_PLAN = "planId"
        const val TAG_PIPELINE = "pipeline"
        const val TAG_EXECUTE = "execute"
    }
}

/**
 * Asks WorkManager to run as a foreground service so long stages (verifying a two-hour meeting)
 * are not cut off at the 10-minute worker limit. Android 12+ refuses this while the app is in the
 * background; the worker then continues normally and relies on stage-level resume if stopped.
 */
private suspend fun CoroutineWorker.tryForeground(notifier: Notifier, title: String, text: String) {
    val n = notifier.progressNotification(title, text)
    val info = if (Build.VERSION.SDK_INT >= 29) {
        ForegroundInfo(Notifier.ID_PIPELINE_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(Notifier.ID_PIPELINE_PROGRESS, n)
    }
    try {
        setForeground(info)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i("Work", "Running without foreground: ${e.javaClass.simpleName}")
    }
}

@HiltWorker
class PipelineWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: PipelineOrchestrator,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val db: AppDatabase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getLong(WorkScheduler.KEY_SESSION, -1)
        val session = db.sessions().get(sessionId) ?: return Result.success()
        if (!session.isRecording) tryForeground(notifier, "Working on ${session.title}", "Agents are processing your recording")
        return try {
            orchestrator.run(sessionId, settings.current.pipelineConfig())
            Result.success()
        } catch (e: ProviderException) {
            // Retryable by construction: non-retryable provider errors become a FAILED stage inside run().
            Log.i(TAG, "Pipeline for $sessionId will retry: ${e.message}")
            if (runAttemptCount >= MAX_ATTEMPTS) {
                db.sessions().setState(sessionId, PipelineState.FAILED.name, "Gave up after $MAX_ATTEMPTS attempts: ${e.message}")
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    private companion object {
        const val TAG = "PipelineWorker"
        const val MAX_ATTEMPTS = 20
    }
}

@HiltWorker
class ExecutePlanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: PipelineOrchestrator,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val planId = inputData.getLong(WorkScheduler.KEY_PLAN, -1)
        tryForeground(notifier, "Executing plan", "The Executor is producing deliverables")
        return try {
            orchestrator.executePlan(planId)
            Result.success()
        } catch (e: ProviderException) {
            if (runAttemptCount >= 20) Result.failure() else Result.retry()
        }
    }
}

/** Deletes audio older than the configured age, keeping transcripts and all agent outputs. */
@HiltWorker
class AudioCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val days = settings.current.data.deleteAudioAfterDays
        if (days <= 0) return Result.success()
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        for (session in db.sessions().withAudioOlderThan(cutoff)) {
            // Only delete once transcription is done, so audio is never lost before it was used.
            val chunks = db.chunks().forSession(session.id)
            if (chunks.any { it.transcriptJson == null }) continue
            File(session.audioDir).deleteRecursively()
            db.sessions().markAudioDeleted(session.id)
        }
        return Result.success()
    }
}
