package com.forgemind.app

import com.forgemind.app.agent.ToolArgumentValidator
import com.forgemind.app.data.model.ConversationActions
import com.forgemind.app.llm.ModelCapabilities
import com.forgemind.app.voice.StreamingSpeechText
import com.forgemind.app.voice.cleanSpeechMarkdown
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*

class ConversationFeatureTests {
    @Test(timeout=10000) fun stoppingParentCancelsBlockedNetworkBeforeReaderJobCompletes() = runBlocking {
        val server=okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        server.start()
        val call=okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(server.url("/held")).build())
        val reader=launch(Dispatchers.IO) {
            val hook=com.forgemind.app.llm.cancelCallWithCoroutine(call)
            try {call.execute().close()}catch(_:java.io.IOException){ }finally{hook.dispose()}
        }
        try {
            assertNotNull(server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(reader.isActive)
            withTimeout(3000){reader.cancelAndJoin()}
            assertTrue(call.isCanceled())
        } finally {call.cancel();reader.cancel();server.shutdown()}
    }
    @Test fun dailyScheduleUsesZoneAndRollsPastLocalTimeToTomorrow() {
        val now=java.time.Instant.parse("2026-11-18T08:00:00Z").toEpochMilli()
        val task=com.forgemind.app.scheduling.ScheduledPrompt("id","chat","Daily","Prompt",kind="daily",dateTime="09:00",timeZone="Europe/Berlin")
        assertEquals(java.time.Instant.parse("2026-11-19T08:00:00Z").toEpochMilli(),com.forgemind.app.scheduling.SchedulePolicy.next(task,now))
    }
    @Test fun dailyScheduleResolvesSpringDstGapWithoutImmediateLoop() {
        val now=java.time.Instant.parse("2026-03-29T00:00:00Z").toEpochMilli()
        val task=com.forgemind.app.scheduling.ScheduledPrompt("id","chat","Daily","Prompt",kind="daily",dateTime="02:30",timeZone="Europe/Berlin")
        assertEquals(java.time.Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(),com.forgemind.app.scheduling.SchedulePolicy.next(task,now))
    }
    @Test fun pastOnceTaskCanBeImportedDisabledButCannotBeEnabledInPast() {
        val task=com.forgemind.app.scheduling.ScheduledPrompt("id","chat","Once","Prompt",dateTime="2020-01-01T12:00:00Z",timeZone="UTC",enabled=false)
        com.forgemind.app.scheduling.SchedulePolicy.validate(task)
        assertTrue(runCatching { com.forgemind.app.scheduling.SchedulePolicy.next(task,java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()) }.isFailure)
        assertNotEquals(com.forgemind.app.scheduling.SchedulePolicy.occurrence(task),com.forgemind.app.scheduling.SchedulePolicy.occurrence(task.copy(revision=2)))
    }
    @Test fun profileOutputLimitWinsOverReasoningBudgetWhileBlankPresetInherits() {
        val config=com.forgemind.app.data.model.ProviderConfig("custom",temperature=0.7f,maxTokens=8192)
        assertEquals(config,com.forgemind.app.data.model.GenerationPreset().apply(config))
        val applied=com.forgemind.app.data.model.GenerationPreset(0.2f,512,"high").apply(config)
        assertEquals(512,applied.maxTokens);assertEquals(0.2f,applied.temperature)
        assertTrue(applied.enableThinking && applied.thinkingBudget<512)
    }
    @Test fun invalidProfilePresetIsRejectedBeforeStorage() {
        for(raw in listOf("""{"temperature":3}""","""{"maxTokens":0}""","""{"reasoning":"arbitrary"}"""))
            assertTrue(runCatching { com.forgemind.app.data.model.GenerationPreset.decode(raw) }.isFailure)
    }
    @Test fun streamingToolMarkupIsSilentEvenWhenOpeningTagAndJsonAreSplit() {
        val stream=StreamingSpeechText()
        val values=listOf("До. <tool_","call>{\"name\":\"run_command\",\"arguments\":{\"command\":\"hidden. ","danger\"}}</tool_","call> После.").flatMap { stream.append(it) }
        assertEquals(listOf("До.","После."),values)
    }
    @Test fun streamingLongCodeDoesNotLeakAndCanResumeAfterFence() {
        val stream=StreamingSpeechText()
        assertTrue(stream.append("```sh\n" + "secret. ".repeat(10000)).isEmpty())
        assertEquals(listOf("Готово."),stream.append("\n```\nГотово."))
    }
    @Test fun archiveComparisonShowsReplacementAndPreservesExistingProfile() {
        val chat=com.forgemind.app.data.db.ChatSessionEntity("chat","Before")
        val profile=com.forgemind.app.data.db.AssistantProfileEntity("profile","Current")
        val current=com.forgemind.app.data.archive.ChatArchiveBundle(sessions=listOf(chat),assistants=listOf(profile))
        val changes=com.forgemind.app.data.archive.ArchiveComparison.compare(current,current.copy(sessions=listOf(chat.copy(title="After")),assistants=listOf(profile.copy(name="Other")) ))
        assertEquals(listOf("Заменить","Сохранить текущую запись"),changes.map { it.action })
        assertEquals("Before",changes.first().before);assertEquals("After",changes.first().after)
    }
    private fun zip(vararg entries:Pair<String,String>):ByteArray {
        val out=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip -> entries.forEach { (path,body) -> zip.putNextEntry(java.util.zip.ZipEntry(path));zip.write(body.toByteArray());zip.closeEntry() } }
        return out.toByteArray()
    }
    @Test fun archiveRejectsTraversalAndOrphanMessagesBeforeAnyExtraction() {
        assertTrue(runCatching { com.forgemind.app.data.archive.ArchivePreviewReader.inspect(zip("../escape" to "bad","manifest.json" to "{}").inputStream()) }.isFailure)
        val manifest="""{"sessions":[],"messages":[{"id":"m","sessionId":"missing","orderIndex":0,"role":"USER"}]}"""
        assertTrue(runCatching { com.forgemind.app.data.archive.ArchivePreviewReader.inspect(zip("manifest.json" to manifest).inputStream()) }.isFailure)
    }
    @Test fun oldArchiveRemainsReadableAndDuplicateChatIdsAreRejected() {
        assertEquals(1,com.forgemind.app.data.archive.ArchivePreviewReader.inspect(zip("manifest.json" to """{"formatVersion":1}""").inputStream()).first.formatVersion)
        val invalid="""{"sessions":[{"id":"a","title":"One"},{"id":"a","title":"Two"}]}"""
        assertTrue(runCatching { com.forgemind.app.data.archive.ArchivePreviewReader.inspect(zip("manifest.json" to invalid).inputStream()) }.isFailure)
    }
    @Test fun copiedConversationContainsOnlyVisibleRolesAndDoesNotExposeToolArguments() {
        val values=listOf(com.forgemind.app.data.db.ChatMessageEntity("u","c",0,"USER","Question"),
            com.forgemind.app.data.db.ChatMessageEntity("t","c",1,"TOOL","PRIVATE_TOOL"),
            com.forgemind.app.data.db.ChatMessageEntity("a","c",2,"ASSISTANT","Answer",thinking="PRIVATE_THINKING",modelId="Model"))
        assertEquals("Пользователь:\nQuestion\n\nModel:\nAnswer",ConversationActions.copyConversation(values))
    }
    @Test fun unknownModelNameDoesNotInventVision() {
        assertEquals(ModelCapabilities(), ModelCapabilities.fromCatalog(Json.parseToJsonElement("""{"id":"vision-thinking-tools-model"}""").jsonObject))
    }
    @Test fun capabilitiesComeFromProviderMetadata() {
        val value = ModelCapabilities.fromCatalog(Json.parseToJsonElement("""{"architecture":{"input_modalities":["text","image"]},"supported_parameters":["tools","reasoning_effort"]}""").jsonObject)
        assertEquals(ModelCapabilities(true,true,true),value)
    }
    @Test fun catalogWithoutVisionReportsFalseOnlyWhenModalitiesAreKnown() {
        assertEquals(false,ModelCapabilities.fromCatalog(Json.parseToJsonElement("""{"input_modalities":["text"]}""").jsonObject).vision)
    }
    @Test fun approvalRejectsWrongRootTypeMissingAndWrongTypedArguments() {
        val schema=Json.parseToJsonElement("""{"type":"object","properties":{"lines":{"type":"integer","minimum":1}},"required":["lines"],"additionalProperties":false}""").jsonObject
        for (raw in listOf("[]","{}","""{"lines":"2"}""","""{"lines":0}""","""{"lines":2,"other":1}"""))
            assertNotNull(raw,ToolArgumentValidator.validate(raw,schema))
        assertNull(ToolArgumentValidator.validate("""{"lines":2}""",schema))
    }
    @Test fun branchNumberUsesOriginalRootAndDoesNotOverwriteAnExistingTitle() {
        assertEquals("Topic (3)",ConversationActions.nextBranchTitle("Topic (1)", listOf("Topic (1)","Topic (2)")))
    }
    @Test fun sentenceBoundaryAcrossDeltasIsSpokenOnce() {
        val stream=StreamingSpeechText()
        assertTrue(stream.append("Первая фра").isEmpty())
        assertEquals(listOf("Первая фраза."),stream.append("за. Вторая"))
        assertEquals(listOf("Вторая"),stream.append("",finish=true))
        assertTrue(stream.append("",finish=true).isEmpty())
    }
    @Test fun streamingCodeStaysSilent() {
        val stream=StreamingSpeechText()
        val spoken=listOf("До.\n```py\n","print('секрет.')\n","```\nПосле.").flatMap { stream.append(it) }
        assertEquals(listOf("До.","После."),spoken)
    }
    @Test fun speechMarkdownKeepsLinkTextAndDropsCodeImagesAndHtml() {
        val clean=cleanSpeechMarkdown("# Заголовок\n[ссылка](https://example.org) ![изображение](a.png)\n```sh\nrm -rf x\n```\n<b>текст</b>")
        assertEquals("Заголовок ссылка текст",clean)
    }
}
