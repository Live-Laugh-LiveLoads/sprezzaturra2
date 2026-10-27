package com.ideaforge.core.llm

import com.google.common.truth.Truth.assertThat
import com.ideaforge.core.transcription.TranscriptionOptions
import com.ideaforge.core.transcription.WhisperApiTranscriptionProvider
import com.ideaforge.core.transcription.WhisperApiTranscriptionProvider.ResponseFormat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import java.io.File

class ProvidersTest {
    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient()
    private val base = server.url("/v1").toString()

    @After fun tearDown() = server.shutdown()

    @Test fun `chat completion sends key, json mode and parses usage`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"model":"grok-x","choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"}}],
               "usage":{"prompt_tokens":12,"completion_tokens":3}}""",
        ))
        val provider = OpenAiCompatibleLlmProvider("xai", base, { "secret-key" }, client)

        val r = provider.complete(LlmRequest("grok-x", listOf(LlmMessage.system("s"), LlmMessage.user("u"))))

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/chat/completions")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer secret-key")
        assertThat(recorded.body.readUtf8()).contains("\"response_format\":{\"type\":\"json_object\"}")
        assertThat(r.content).isEqualTo("{\"ok\":true}")
        assertThat(r.promptTokens).isEqualTo(12)
        assertThat(r.completionTokens).isEqualTo(3)
    }

    @Test fun `errors are classified as retryable or not`() = runTest {
        val provider = OpenAiCompatibleLlmProvider("p", base, { "k" }, client)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))
        val rateLimited = runCatching { provider.complete(LlmRequest("m", listOf(LlmMessage.user("x")))) }.exceptionOrNull() as ProviderException
        assertThat(rateLimited.retryable).isTrue()
        assertThat(rateLimited.message).contains("slow down")

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val unauthorized = runCatching { provider.complete(LlmRequest("m", listOf(LlmMessage.user("x")))) }.exceptionOrNull() as ProviderException
        assertThat(unauthorized.retryable).isFalse()
        assertThat(unauthorized.message).contains("check the API key")
    }

    @Test fun `connection failure is retryable`() = runTest {
        val dead = OpenAiCompatibleLlmProvider("p", "http://127.0.0.1:1/v1", { null }, client)
        val e = runCatching { dead.complete(LlmRequest("m", listOf(LlmMessage.user("x")))) }.exceptionOrNull()
        assertThat((e as ProviderException).retryable).isTrue()
    }

    @Test fun `models are listed`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"b"},{"id":"a"}]}"""))
        assertThat(OpenAiCompatibleLlmProvider("p", base, { null }, client).listModels()).containsExactly("a", "b").inOrder()
    }

    @Test fun `whisper request asks for word timestamps and parses verbose json`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"language":"english","segments":[{"start":0.0,"end":2.5,"text":" Hello world.","avg_logprob":-0.1},
                {"start":2.5,"end":4.0,"text":" Bye.","avg_logprob":-2.0}],
               "words":[{"word":"Hello","start":0.1,"end":0.5},{"word":"world","start":0.6,"end":1.0},{"word":"Bye","start":2.6,"end":3.0}]}""",
        ))
        val audio = File.createTempFile("chunk", ".m4a").apply { writeBytes(ByteArray(10)) }
        val provider = WhisperApiTranscriptionProvider(base, { "k" }, "whisper-1", client)

        val t = provider.transcribe(audio, TranscriptionOptions(language = "en", vocabularyHint = "Kubernetes"))

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("verbose_json")
        assertThat(body).contains("timestamp_granularities[]")
        assertThat(body).contains("Kubernetes")
        assertThat(t.segments.map { it.text }).containsExactly("Hello world.", "Bye.").inOrder()
        assertThat(t.segments[0].words.map { it.text }).containsExactly("Hello", "world")
        assertThat(t.segments[1].endMs).isEqualTo(4000)
        assertThat(t.segments[1].confidence!!).isLessThan(0.2)
    }

    @Test fun `diarized json maps speakers to Speaker N in order of appearance`() {
        val t = WhisperApiTranscriptionProvider.parse(
            """{"segments":[{"speaker":"B","start":0,"end":1,"text":"Hi"},{"speaker":"A","start":1,"end":2,"text":"Hey"},
                {"speaker":"B","start":2,"end":3,"text":"So"}]}""",
            ResponseFormat.DIARIZED_JSON,
        )
        assertThat(t.segments.map { it.speaker }).containsExactly("Speaker 1", "Speaker 2", "Speaker 1").inOrder()
    }

    @Test fun `plain json becomes a single segment`() {
        val t = WhisperApiTranscriptionProvider.parse("""{"text":"just text"}""", ResponseFormat.JSON)
        assertThat(t.segments.single().text).isEqualTo("just text")
    }

    @Test fun `price table matches by prefix and meters cost`() = runTest {
        val table = PriceTable(PriceTable.DEFAULTS)
        assertThat(table.chatCost("gpt-4o-mini-2024-07-18", 1_000_000, 1_000_000)).isWithin(1e-9).of(0.75)
        assertThat(table.audioCost("whisper-1", 600_000)).isWithin(1e-9).of(0.06)
        assertThat(table.priceFor("unknown-model")).isNull()
    }
}
