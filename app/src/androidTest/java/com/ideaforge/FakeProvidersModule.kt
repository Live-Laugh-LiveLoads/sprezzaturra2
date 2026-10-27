package com.ideaforge

import com.ideaforge.core.agents.AgentModel
import com.ideaforge.core.agents.AgentModelResolver
import com.ideaforge.core.testing.FakeLlmProvider
import com.ideaforge.core.testing.FakeTranscriptionProvider
import com.ideaforge.di.ProviderModule
import com.ideaforge.di.TranscriberSource
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Swaps the network-facing providers for offline fakes; everything else in the app is real. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ProviderModule::class])
object FakeProvidersModule {

    @Provides @Singleton
    fun llm(): FakeLlmProvider = FakeLlmProvider()

    @Provides
    fun models(llm: FakeLlmProvider): AgentModelResolver =
        AgentModelResolver { AgentModel(llm, "fake-model", 0.2, jsonMode = true) }

    @Provides
    fun transcriber(): TranscriberSource = TranscriberSource { FakeTranscriptionProvider() }
}
