package com.forgemind.app

import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.voice.TtsManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSpeedAndChunkTests {

    @Test
    fun `payload builders include clamped tts speed for xtts and openai`() {
        val emb = JsonArray(listOf(JsonPrimitive(0.25f)))
        val lat = JsonArray(listOf(JsonArray(listOf(JsonPrimitive(-0.5f)))))

        val xttsPayload = TtsManager.buildXttsStreamPayload(
            text = "Testing speech tempo",
            language = "en",
            speakerEmbedding = emb,
            gptCondLatent = lat,
            pitch = 1.5f,
            keepTempo = true,
            speed = 1.65f
        )
        assertEquals(1.65f, xttsPayload["speed"]?.jsonPrimitive?.float ?: 0f, 0.001f)
        assertEquals(1.5f, xttsPayload["pitch"]?.jsonPrimitive?.float ?: 0f, 0.001f)

        val clampedHigh = TtsManager.buildXttsStreamPayload(
            text = "Fast",
            language = "en",
            speakerEmbedding = emb,
            gptCondLatent = lat,
            pitch = 0f,
            keepTempo = true,
            speed = 9.0f
        )
        assertEquals(2.5f, clampedHigh["speed"]?.jsonPrimitive?.float ?: 0f, 0.001f)

        val openaiPayload = TtsManager.buildOpenAiSpeechPayload(
            model = "gpt-4o-mini-tts",
            input = "Test",
            voice = "nova",
            speed = 1.4f
        )
        assertEquals(1.4f, openaiPayload["speed"]?.jsonPrimitive?.float ?: 0f, 0.001f)
    }

    @Test
    fun `computeTrackPlaybackSpeed avoids double speedup and supports live adjustment`() {
        assertEquals(
            1.0f,
            TtsManager.computeTrackPlaybackSpeed(
                liveSpeed = 1.5f,
                synthesizedSpeed = 1.5f,
                serverAppliedSpeed = true
            ),
            0.001f
        )
        assertEquals(
            1.5f,
            TtsManager.computeTrackPlaybackSpeed(
                liveSpeed = 1.8f,
                synthesizedSpeed = 1.2f,
                serverAppliedSpeed = true
            ),
            0.001f
        )
        assertEquals(
            1.4f,
            TtsManager.computeTrackPlaybackSpeed(
                liveSpeed = 1.4f,
                synthesizedSpeed = 1.0f,
                serverAppliedSpeed = false
            ),
            0.001f
        )
    }

    @Test
    fun `splitIntoChunks keeps long prose within XTTS limit without breaking words`() {
        val text = "First short sentence. " +
            "Second very long sentence in English containing commas, technical terms like Cloudflared and OpenAI, plus an extra clause — ensuring splits happen strictly on phrase or word boundaries without breaking the middle of a word when enforcing the synthesizer segment limit! " +
            "End of text."
        val chunks = TtsManager.splitIntoChunks(text, maxLen = 155)
        assertTrue(chunks.size >= 3)
        chunks.forEach { chunk ->
            assertTrue("Chunk exceeds 155 chars (${chunk.length}): $chunk", chunk.length <= 155)
            assertTrue(chunk.isNotBlank())
        }
        val expectedWords = text.replace(Regex("\\s+"), " ").trim().split(' ')
        val actualWords = chunks.joinToString(" ").split(' ')
        assertEquals(expectedWords, actualWords)
    }

    @Test
    fun `requestXttsChunk retries after premature body disconnect and reads speed header`() {
        val server = MockWebServer()
        // Response 1 fails mid-body (simulates EOFException / IncompleteRead), Response 2 succeeds
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(ByteArray(32_768) { 7 }))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val expectedPcm = byteArrayOf(10, 20, 30, 40, 50, 60)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-XTTS-Speed-Applied", "1")
                .setBody(Buffer().write(expectedPcm))
        )
        // Response 3 without X-XTTS-Speed-Applied and odd byte count gets aligned cleanly
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(byteArrayOf(1, 2, 3, 4, 5)))
        )
        server.start()
        try {
            val httpClient = OkHttpClient.Builder().build()
            val url = server.url("/tts_stream").toString()
            val settings = VoiceSettings(ttsSpeed = 1.5f)
            val payload = TtsManager.buildXttsStreamPayload(
                text = "Testing retry.",
                language = "en",
                speakerEmbedding = JsonArray(emptyList()),
                gptCondLatent = JsonArray(emptyList()),
                pitch = 0f,
                keepTempo = true,
                speed = 1.5f
            )

            val chunk1 = TtsManager.requestXttsChunk(
                httpClient = httpClient,
                url = url,
                reqJson = payload,
                settings = settings,
                synthesizedSpeed = 1.5f
            )
            assertTrue(chunk1.serverAppliedSpeed)
            assertEquals(1.5f, chunk1.synthesizedSpeed, 0.001f)
            assertArrayEquals(expectedPcm, chunk1.pcm)

            val req1 = server.takeRequest()
            val req2 = server.takeRequest()
            assertFalse("close".equals(req1.getHeader("Connection"), ignoreCase = true))
            assertEquals("close", req2.getHeader("Connection"))

            val chunk2 = TtsManager.requestXttsChunk(
                httpClient = httpClient,
                url = url,
                reqJson = payload,
                settings = settings,
                synthesizedSpeed = 1.5f
            )
            assertFalse(chunk2.serverAppliedSpeed)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), chunk2.pcm)
        } finally {
            server.shutdown()
        }
    }
}
