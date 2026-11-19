package com.forgemind.app

import com.forgemind.app.data.model.AutoSpeakMode
import com.forgemind.app.data.model.TtsProviderType
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.voice.TtsManager
import com.forgemind.app.voice.cleanSpeechMarkdown
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBargeInAndCleanupTests {

    @Test
    fun `autoSpeakMode maps and cycles cleanly between OFF, STREAMING, and AFTER_REPLY without trapping autoSpeak true`() {
        val base = VoiceSettings()
        assertEquals(AutoSpeakMode.OFF, base.autoSpeakMode())
        assertFalse(base.autoSpeakReplies)
        assertFalse(base.streamSpeakReplies)

        val streaming = base.cycleAutoSpeakMode()
        assertEquals(AutoSpeakMode.STREAMING, streaming.autoSpeakMode())
        assertTrue(streaming.autoSpeakReplies)
        assertTrue(streaming.streamSpeakReplies)

        val afterReply = streaming.cycleAutoSpeakMode()
        assertEquals(AutoSpeakMode.AFTER_REPLY, afterReply.autoSpeakMode())
        assertTrue(afterReply.autoSpeakReplies)
        assertFalse(afterReply.streamSpeakReplies)

        val offAgain = afterReply.cycleAutoSpeakMode()
        assertEquals(AutoSpeakMode.OFF, offAgain.autoSpeakMode())
        assertFalse(offAgain.autoSpeakReplies)
        assertFalse(offAgain.streamSpeakReplies)

        // Direct OFF from STREAMING must turn off BOTH flags so AI stops speaking altogether
        val directOff = streaming.withAutoSpeakMode(AutoSpeakMode.OFF)
        assertFalse(directOff.autoSpeakReplies)
        assertFalse(directOff.streamSpeakReplies)
        assertEquals(AutoSpeakMode.OFF, directOff.autoSpeakMode())
    }

    @Test
    fun `legacy serialized VoiceSettings with removed handsFreeMode field deserializes cleanly`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """{"handsFreeMode":true,"autoSpeakReplies":true,"streamSpeakReplies":true,"xttsLanguage":"en"}"""
        val decoded = json.decodeFromString<VoiceSettings>(legacy)
        assertEquals(AutoSpeakMode.STREAMING, decoded.autoSpeakMode())
        assertEquals("en", decoded.xttsLanguage)
    }

    @Test
    fun `TTS chunk budgets allow full Russian sentences and paragraphs without mid-sentence chopping`() {
        assertTrue(TtsManager.XTTS_CHUNK_MAX_LEN >= 220)
        assertTrue(TtsManager.EDGE_CHUNK_MAX_LEN >= 1000)
        assertTrue(TtsManager.ANDROID_TTS_CHUNK_MAX_LEN >= 1000)
        assertEquals(TtsManager.EDGE_CHUNK_MAX_LEN, TtsManager.streamingBatchLimitFor(TtsProviderType.MICROSOFT_EDGE))
        assertEquals(TtsManager.XTTS_CHUNK_MAX_LEN, TtsManager.streamingBatchLimitFor(TtsProviderType.XTTS_V2))

        val normalRussianSentence =
            "Это полноценное длинное русское предложение, в котором около двухсот символов с запятыми и уточнениями, и раньше оно разрывалось пополам на полуслове прямо во время озвучки."
        val xttsChunks = TtsManager.splitIntoChunks(normalRussianSentence, TtsManager.XTTS_CHUNK_MAX_LEN)
        assertEquals(1, xttsChunks.size)

        val multiParagraph = List(4) { normalRussianSentence }.joinToString(" ")
        val edgeChunks = TtsManager.splitIntoChunks(multiParagraph, TtsManager.EDGE_CHUNK_MAX_LEN)
        assertEquals(1, edgeChunks.size)
    }

    @Test
    fun `splitIntoProgressiveChunks starts fast with compact first chunk and batches large follow-up chunks without losing words`() {
        val sentence = "Это длинное полноценное русское предложение для проверки прогрессивной нарезки текста на абзацы без обрыва смысла и потери слов."
        val longText = List(18) { "Пункт ${it + 1}. $sentence" }.joinToString(" ")
        val chunks = TtsManager.splitIntoProgressiveChunks(
            text = longText,
            firstMaxLen = TtsManager.EDGE_FIRST_CHUNK_MAX_LEN,
            nextMaxLen = TtsManager.EDGE_CHUNK_MAX_LEN
        )
        assertTrue("Expected at least 3 progressive chunks, got ${chunks.size}", chunks.size >= 3)
        assertTrue("First chunk (${chunks.first().length}) must fit fast-start budget", chunks.first().length <= TtsManager.EDGE_FIRST_CHUNK_MAX_LEN)
        assertTrue("Second chunk (${chunks[1].length}) should load much more text than first chunk", chunks[1].length > TtsManager.EDGE_FIRST_CHUNK_MAX_LEN)
        chunks.drop(1).forEach { chunk ->
            assertTrue("Follow-up chunk (${chunk.length}) exceeds EDGE_CHUNK_MAX_LEN", chunk.length <= TtsManager.EDGE_CHUNK_MAX_LEN)
        }
        val expectedWords = longText.replace(Regex("\\s+"), " ").trim().split(' ')
        val actualWords = chunks.joinToString(" ").split(' ')
        assertEquals(expectedWords, actualWords)
    }

    @Test
    fun `cleanSpeechMarkdown removes stray spaces before punctuation, invisible spaces, and repeated pauses`() {
        val raw = "**Привет**, это\u00A0`тест`\u200B...  \n\n  Смотри (https://example.com) — готово! . ."
        val clean = cleanSpeechMarkdown(raw)
        assertEquals("Привет, это тест. Смотри — готово!", clean)
    }

    @Test
    fun `trimPcm16Silence cuts excess leading and trailing silence while preserving active speech`() {
        val sr = 1000 // 1 sample per ms for simple exact math
        val leadSilenceSamples = 400
        val speechSamples = 200
        val tailSilenceSamples = 600
        val total = leadSilenceSamples + speechSamples + tailSilenceSamples
        val pcm = ByteArray(total * 2)
        for (i in leadSilenceSamples until (leadSilenceSamples + speechSamples)) {
            val v = 4000
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        val trimmed = TtsManager.trimPcm16Silence(
            pcm = pcm,
            sampleRate = sr,
            keepLeadMs = 20,
            keepTailMs = 80,
            threshold = 320
        )
        assertEquals((20 + speechSamples + 80) * 2, trimmed.size)

        // All-silence chunk should collapse to empty
        assertEquals(0, TtsManager.trimPcm16Silence(ByteArray(1000)).size)
    }
}
