package com.ideaforge.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.ideaforge.audio.AdtsRemuxer
import com.ideaforge.core.agents.AgentModelResolver
import com.ideaforge.core.agents.AgentRunner
import com.ideaforge.core.agents.BrainstormerAgent
import com.ideaforge.core.agents.EvaluatorAgent
import com.ideaforge.core.agents.ExecutorAgent
import com.ideaforge.core.agents.PlannerAgent
import com.ideaforge.core.agents.PromptStore
import com.ideaforge.core.agents.VerifierAgent
import com.ideaforge.core.llm.MeteredLlm
import com.ideaforge.core.llm.UsageLedger
import com.ideaforge.core.pipeline.ChunkAudioSource
import com.ideaforge.core.pipeline.PipelineEvents
import com.ideaforge.core.pipeline.PipelineOrchestrator
import com.ideaforge.core.pipeline.PipelineStore
import com.ideaforge.core.transcription.TranscriptionProvider
import com.ideaforge.data.RoomPipelineStore
import com.ideaforge.data.RoomUsageLedger
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.notify.Notifier
import com.ideaforge.prompts.AssetPromptStore
import com.ideaforge.providers.ProviderFactory
import com.ideaforge.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    /** Long read timeout: a brainstorm reply can take a couple of minutes on a slow model. */
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Named("sessionsDir")
    fun sessionsDir(@ApplicationContext context: Context): File = File(context.filesDir, "sessions").apply { mkdirs() }

    @Provides @Named("deliverablesDir")
    fun deliverablesDir(@ApplicationContext context: Context): File = File(context.filesDir, "deliverables").apply { mkdirs() }

    @Provides @Singleton
    fun metered(ledger: UsageLedger, settings: SettingsRepository): MeteredLlm =
        MeteredLlm(ledger, { settings.current.cost.monthlyCapUsd }, { settings.current.priceTable() })

    @Provides @Singleton
    fun runner(metered: MeteredLlm, models: AgentModelResolver, prompts: PromptStore): AgentRunner =
        AgentRunner(metered, models, prompts)

    /** Hands the transcriber an M4A for each chunk, remuxing a leftover ADTS file if needed. */
    @Provides @Singleton
    fun chunkAudio(db: AppDatabase, @ApplicationContext context: Context): ChunkAudioSource = ChunkAudioSource { chunk ->
        val entity = db.chunks().get(chunk.id) ?: error("Chunk ${chunk.id} no longer exists")
        val file = File(entity.path)
        if (file.extension.equals("aac", ignoreCase = true)) {
            val m4a = AdtsRemuxer.remux(file, File(file.parentFile, file.nameWithoutExtension + ".m4a"))
            db.chunks().finalize(entity.id, m4a.path, entity.durationMs.takeIf { it > 0 } ?: AdtsRemuxer.durationMs(m4a))
            file.delete()
            m4a
        } else file
    }

    @Provides @Singleton
    fun orchestrator(
        store: PipelineStore,
        transcriber: TranscriberSource,
        audio: ChunkAudioSource,
        metered: MeteredLlm,
        prompts: PromptStore,
        runner: AgentRunner,
        events: PipelineEvents,
    ): PipelineOrchestrator = PipelineOrchestrator(
        store = store,
        transcriber = { transcriber.get() },
        audio = audio,
        metered = metered,
        prompts = prompts,
        verifier = VerifierAgent(runner),
        brainstormer = BrainstormerAgent(runner),
        evaluator = EvaluatorAgent(runner),
        planner = PlannerAgent(runner),
        executor = ExecutorAgent(runner),
        events = events,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun store(impl: RoomPipelineStore): PipelineStore
    @Binds abstract fun ledger(impl: RoomUsageLedger): UsageLedger
    @Binds abstract fun prompts(impl: AssetPromptStore): PromptStore
    @Binds abstract fun events(impl: Notifier): PipelineEvents
}

/** Supplies the transcription provider for each run, as configured in Settings. */
fun interface TranscriberSource {
    fun get(): TranscriptionProvider
}

/**
 * The only bindings that talk to the outside world. The instrumented test replaces this module
 * with fakes (see FakeProvidersModule in androidTest), so the rest of the app runs unchanged.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {
    @Provides
    fun models(factory: ProviderFactory): AgentModelResolver = factory

    @Provides
    fun transcriber(factory: ProviderFactory): TranscriberSource = TranscriberSource { factory.transcription() }
}
