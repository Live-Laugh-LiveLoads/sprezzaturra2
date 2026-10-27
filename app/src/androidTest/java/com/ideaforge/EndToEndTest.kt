package com.ideaforge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.ideaforge.audio.RecordingController
import com.ideaforge.core.pipeline.PipelineOrchestrator
import com.ideaforge.core.pipeline.PipelineOutcome
import com.ideaforge.core.pipeline.SessionMode
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.notify.Notifier
import com.ideaforge.settings.SettingsRepository
import com.ideaforge.ui.Routes
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject
import javax.inject.Provider

/**
 * Records a short memo through the real foreground service and recorder, runs the real pipeline
 * (Room store, prompts, schemas, agents) against the offline fakes from FakeProvidersModule, and
 * checks that the plan approval screen shows the resulting plans.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    }

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule()

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var db: AppDatabase

    // Providers defer creation until WorkManager is initialised below (these depend on it).
    @Inject lateinit var recorder: Provider<RecordingController>
    @Inject lateinit var orchestrator: Provider<PipelineOrchestrator>
    @Inject lateinit var notifier: Provider<Notifier>

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hilt.inject()
        // HiltTestApplication replaces IdeaForgeApp, so do its startup work here.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(workerFactory).setExecutor(SynchronousExecutor()).build(),
        )
        notifier.get().createChannels()
        settings.update { it.copy(onboardingDone = true) }
    }

    @Test
    fun recordMemoRunPipelineAndReachApproval() = runBlocking<Unit> {
        val controller = recorder.get()
        val sessionId = controller.start(SessionMode.MEMO, consentAt = null)

        waitFor("2 s of audio") { controller.state.value.elapsedMs >= 2_000 }
        controller.stop()
        waitFor("recording to finalize") { db.sessions().get(sessionId)?.isRecording == false }

        val chunks = db.chunks().forSession(sessionId)
        assertThat(chunks).isNotEmpty()
        assertThat(chunks.all { it.finalized }).isTrue()
        assertThat(File(chunks.first().path).length()).isGreaterThan(0L)

        // Background work may already have started; the orchestrator serialises runs per session
        // and skips finished stages, so a direct run converges on the same result.
        val outcome = orchestrator.get().run(sessionId, settings.current.pipelineConfig())
        assertThat(outcome).isInstanceOf(PipelineOutcome.AwaitingApproval::class.java)
        assertThat(db.plans().forSession(sessionId)).isNotEmpty()

        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Routes.approval(sessionId))
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Plan for I1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Plan for I1").assertIsDisplayed()
        }
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 20_000, condition: suspend () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(100) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }
}
