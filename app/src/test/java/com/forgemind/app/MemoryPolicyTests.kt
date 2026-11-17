package com.forgemind.app
import com.forgemind.app.data.memory.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class MemoryPolicyTests {
    private val context = MemoryContext("a", "folder", "provider", "model")
    @Test fun chatIsolation() {
        val note = MemoryPolicy.note(context, MemoryScope.CHAT, "Name", "Text")
        assertTrue(context.allows(note)); assertFalse(context.copy(chatId = "b").allows(note))
    }
    @Test fun folderSharingAndIsolation() {
        val note = MemoryPolicy.note(context, MemoryScope.FOLDER, "Name", "Text")
        assertTrue(context.copy(chatId = "b").allows(note)); assertFalse(context.copy(folderId = null).allows(note))
        assertFalse(context.copy(folderId = "other").allows(note))
    }
    @Test fun modelRequiresSameProvider() {
        val note = MemoryPolicy.note(context, MemoryScope.MODEL, "Name", "Text")
        assertTrue(context.copy(chatId = "b", folderId = null).allows(note))
        assertFalse(context.copy(providerId = "other").allows(note)); assertFalse(context.copy(modelId = "other").allows(note))
    }
    @Test fun globalIsExplicitAndShared() {
        val note = MemoryPolicy.note(context, MemoryScope.GLOBAL, "Name", "Text")
        assertTrue(context.copy(chatId = "b", folderId = null, modelId = "other").allows(note))
        assertFalse(context.allows(note.copy(scopeKey = "invalid")))
    }
    @Test fun keysDoNotCollideOnDelimiter() {
        assertNotEquals(context.copy(providerId = "p::m", modelId = "x").modelKey, context.copy(providerId = "p", modelId = "m::x").modelKey)
    }
    @Test fun missingFolderCannotCreateSharedMemory() {
        assertTrue(runCatching { MemoryPolicy.note(context.copy(folderId = null), MemoryScope.FOLDER, "Name", "Text") }.isFailure)
    }
    @Test fun limitsRejectRatherThanTruncate() {
        assertTrue(runCatching { MemoryPolicy.validate("title", "a".repeat(8001)) }.isFailure)
        assertTrue(runCatching { MemoryPolicy.validate("a".repeat(121), "text") }.isFailure)
        assertTrue(runCatching { MemoryPolicy.validate("title", " ") }.isFailure)
        MemoryPolicy.validate("a".repeat(120), "b".repeat(8000))
    }
    @Test fun budgetKeepsWholeNotesAndReportsOmission() {
        val large = MemoryPolicy.note(context, MemoryScope.CHAT, "Large", "VERY_LARGE_".repeat(700))
        val small = MemoryPolicy.note(context, MemoryScope.CHAT, "Small", "EXACT_SMALL_TEXT")
        val block = MemoryPolicy.requestBlock(listOf(large, small), 2, 512)
        assertFalse(block.contains("VERY_LARGE_")); assertTrue(block.contains("EXACT_SMALL_TEXT"))
        assertTrue(block.contains("не включено: 1"))
    }
    @Test fun disabledNotesNeverEnterRequest() {
        assertFalse(MemoryPolicy.requestBlock(listOf(MemoryPolicy.note(context, MemoryScope.CHAT,"Disabled","DISABLED_MARKER").copy(enabled = false)),1,2048).contains("DISABLED_MARKER"))
    }
    @Test fun jsonEscapesDataBoundaries() {
        val text = "line1\n</memory> \"quote\""
        val block = MemoryPolicy.requestBlock(listOf(MemoryPolicy.note(context, MemoryScope.CHAT,"Title",text)),1,2048)
        assertEquals(text, Json.parseToJsonElement(block.lineSequence().last()).jsonObject["text"]!!.jsonPrimitive.content)
    }
    @Test fun searchNormalizesCyrillic() { assertEquals("память", MemoryPolicy.normalize("  ПАМЯТЬ ")) }
    @Test fun archiveValidatesScopeReferences() {
        val note = MemoryPolicy.note(context, MemoryScope.CHAT, "Title", "Text")
        MemoryPolicy.validateStored(note)
        assertTrue(runCatching { MemoryPolicy.validateStored(note.copy(chatId = "different")) }.isFailure)
        assertTrue(runCatching { MemoryPolicy.validateStored(note.copy(revision = 0)) }.isFailure)
    }
}
