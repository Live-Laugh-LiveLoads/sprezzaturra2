package com.ideaforge.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ideaforge.MainActivity
import com.ideaforge.R
import com.ideaforge.core.pipeline.PipelineEvents
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.settings.SettingsRepository
import com.ideaforge.ui.Routes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All user-facing notifications. Implements [PipelineEvents] so the orchestrator's milestones
 * (transcribed, plans ready, executed, failed, budget) become notifications that deep-link into
 * the right screen.
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
) : PipelineEvents {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a memo or meeting is being recorded"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_PROGRESS, "Pipeline progress", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while agents are working in the background"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_RESULTS, "Results", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Transcripts, plans ready for approval, finished deliverables, failures"
                },
            ),
        )
    }

    /** Opens the app at [route]; distinct request codes keep each notification's extras. */
    fun openRoute(route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun progressNotification(title: String, text: String) =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()

    override suspend fun transcriptionFinished(sessionId: Long) {
        if (!settings.current.notifications.transcriptionFinished) return
        post(sessionId * 10 + 1, "Transcript ready", "${title(sessionId)} has been transcribed. Agents are working on it.", Routes.session(sessionId))
    }

    override suspend fun reviewNeeded(sessionId: Long) {
        post(sessionId * 10 + 2, "Review the transcript", "${title(sessionId)} is paused for your review before the agents run.", Routes.session(sessionId))
    }

    override suspend fun plansReady(sessionId: Long, count: Int) {
        if (!settings.current.notifications.plansReady) return
        post(sessionId * 10 + 3, "$count plans ready", "Pick which plans from ${title(sessionId)} to execute.", Routes.approval(sessionId))
    }

    override suspend fun executionFinished(sessionId: Long, planId: Long, deliverables: Int) {
        if (!settings.current.notifications.executionFinished) return
        val plan = db.plans().get(planId)?.title ?: "Plan"
        post(planId * 10 + 4 + 1_000_000, "Deliverables ready", "$plan: $deliverables deliverables produced.", Routes.session(sessionId, tab = Routes.TAB_DELIVERABLES))
    }

    override suspend fun budgetPaused(sessionId: Long, message: String) {
        post(sessionId * 10 + 5, "Paused: spending cap reached", "$message. Raise the cap in Settings to continue.", Routes.session(sessionId))
    }

    override suspend fun failed(sessionId: Long, stage: String, message: String) {
        if (!settings.current.notifications.failures) return
        post(sessionId * 10 + 6, "$stage failed", "${title(sessionId)}: $message", Routes.session(sessionId))
    }

    /** Recording ended on its own (battery, storage, error). */
    fun recordingStopped(sessionId: Long, reason: String) {
        post(sessionId * 10 + 7, "Recording stopped", reason, Routes.session(sessionId))
    }

    private suspend fun title(sessionId: Long) = db.sessions().get(sessionId)?.title ?: "Session"

    private fun post(id: Long, title: String, text: String, route: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openRoute(route, id.toInt()))
            .build()
        NotificationManagerCompat.from(context).notify(id.toInt(), n)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_RESULTS = "results"
        const val ID_RECORDING = 1
        const val ID_PIPELINE_PROGRESS = 2
    }
}
