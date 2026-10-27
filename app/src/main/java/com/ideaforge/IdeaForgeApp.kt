package com.ideaforge

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ideaforge.audio.RecordingRecovery
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.notify.Notifier
import com.ideaforge.settings.AppSettings
import com.ideaforge.settings.SecretStore
import com.ideaforge.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class IdeaForgeApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var scheduler: WorkScheduler
    @Inject lateinit var recovery: RecordingRecovery
    @Inject lateinit var db: AppDatabase
    @Inject lateinit var secrets: SecretStore

    /** Process-wide scope for startup housekeeping. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        scheduler.schedulePeriodicMaintenance()
        appScope.launch {
            // Opening the encrypted store touches the Keystore, which is slow on old phones; do it here
            // so a later key read on the main thread (Settings, onboarding) does not stall the UI.
            runCatching { secrets.has(AppSettings.TRANSCRIPTION_KEY) }
            // A process that died mid-stage leaves it RUNNING; show it as pending (it resumes on the next run).
            db.stages().resetInterrupted()
            // Finalize any recording cut short by a crash or the system killing the app.
            recovery.recoverInterruptedRecordings()
        }
    }
}
