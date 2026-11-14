package com.forgemind.app

import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.ui.components.MdRenderBlock
import com.forgemind.app.ui.components.buildRichMarkdownBlocks
import com.forgemind.app.voice.MicrosoftEdgeTtsClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class MicrosoftEdgeTtsAndChatImageTest {

    @Test
    fun secMsGecV2MatchesReferenceVectors() {
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            MicrosoftEdgeTtsClient.generateSecMsGecV2(1700000000.0)
        )
        assertEquals(
            "9A08CA9330247016-0AB8F4694DA3129BB36A42DB8968B3C509926F5BCD80173DDD99636D6A21D5AC",
            MicrosoftEdgeTtsClient.generateSecMsGecV2(1731550000.0)
        )
        assertEquals(
            "3A87DFBAF4BA09C0-A09063D16EA4B9567FDB3C273506B409B367595179F58950F757A7D33069A755",
            MicrosoftEdgeTtsClient.generateSecMsGecV2(1763091600.0)
        )
    }

    @Test
    fun autoVoiceSelectionAndSsmlFormattingWork() {
        assertEquals(
            "ru-RU-SvetlanaNeural",
            MicrosoftEdgeTtsClient.resolveVoiceForText("auto", "Привет! Проверка русской озвучки.")
        )
        assertEquals(
            "uk-UA-PolinaNeural",
            MicrosoftEdgeTtsClient.resolveVoiceForText("auto", "Привіт! Як справи сьогодні?")
        )
        assertEquals(
            "en-US-AvaMultilingualNeural",
            MicrosoftEdgeTtsClient.resolveVoiceForText("auto", "Hello world, testing neural voice synthesis.")
        )
        assertEquals(
            "ru-RU-DmitryNeural",
            MicrosoftEdgeTtsClient.resolveVoiceForText("ru-RU-DmitryNeural", "Hello world")
        )
        assertEquals("+20%", MicrosoftEdgeTtsClient.formatRatePercent(1.2f))
        assertEquals("-15%", MicrosoftEdgeTtsClient.formatRatePercent(0.85f))
        assertEquals("+5Hz", MicrosoftEdgeTtsClient.formatPitchHz(5))
        assertEquals("-10Hz", MicrosoftEdgeTtsClient.formatPitchHz(-10))

        val escaped = MicrosoftEdgeTtsClient.sanitizeAndEscapeForSsml("A & B <tag> \"q\" 's'\u000B")
        assertEquals("A &amp; B &lt;tag&gt; &quot;q&quot; &apos;s&apos; ", escaped)
    }

    @Test
    fun liveMicrosoftEdgeVoicesAndMp3SynthesisSucceed() = runBlocking {
        val client = LlmHttpFactory.baseClientBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val voices = MicrosoftEdgeTtsClient.fetchVoiceList(client, forceRefresh = true)
        assertTrue("Expected > 250 Microsoft voices, got ${voices.size}", voices.size > 250)
        assertTrue(voices.any { it.shortName == "ru-RU-SvetlanaNeural" })
        assertTrue(voices.any { it.shortName == "ru-RU-DmitryNeural" })

        val mp3 = MicrosoftEdgeTtsClient.synthesizeMp3(
            httpClient = client,
            text = "Привет! Бесплатная озвучка Microsoft работает отлично.",
            voice = "ru-RU-SvetlanaNeural",
            rateMultiplier = 1.05f,
            pitchHz = 0
        )
        assertTrue("Expected real MP3 bytes > 4000, got ${mp3.size}", mp3.size > 4000)
        val hasMp3SyncOrId3 =
            (mp3[0] == 'I'.code.toByte() && mp3[1] == 'D'.code.toByte() && mp3[2] == '3'.code.toByte()) ||
                (mp3[0] == 0xFF.toByte() && (mp3[1].toInt() and 0xE0) == 0xE0)
        assertTrue("Expected valid MP3 frame sync or ID3 header", hasMp3SyncOrId3)
    }

    @Test
    fun markdownImageBlocksExtractCleanly() {
        val md = """
            # Отчёт
            ![Архитектура](https://example.com/arch.png "Diagram Title")
            Вот скриншот: ![Экран](/storage/emulated/0/Pictures/shot.webp) — готово!
            [![Badge](https://img.shields.io/badge/ok-green.svg)](https://example.com)
            ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=)
        """.trimIndent()

        val blocks = buildRichMarkdownBlocks(md)
        val images = blocks.filterIsInstance<MdRenderBlock.Image>()
        assertEquals(4, images.size)
        assertEquals("Архитектура", images[0].alt)
        assertEquals("https://example.com/arch.png", images[0].source)
        assertEquals("Экран", images[1].alt)
        assertEquals("/storage/emulated/0/Pictures/shot.webp", images[1].source)
        assertEquals("Badge", images[2].alt)
        assertEquals("https://img.shields.io/badge/ok-green.svg", images[2].source)
        assertTrue(images[3].source.startsWith("data:image/png;base64,iVBOR"))
    }
}
