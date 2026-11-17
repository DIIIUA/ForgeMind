package com.forgemind.app

import com.forgemind.app.agent.*
import com.forgemind.app.data.db.*
import com.forgemind.app.data.model.*
import com.forgemind.app.llm.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolCatalogTests {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"items":{"type":"array","items":{"type":"string","enum":["a","b"]}}},"required":["items"]}""").jsonObject
    private val server = McpServerEntity("one", "Same display name", endpoint = "https://not-exported.invalid", headersJson = "SECRET_HEADER")
    private fun catalog() = ToolCatalog.render(
        listOf(LlmToolSpec("file_read", "Read file", schema)),
        linkedMapOf(server to (1..175).map { McpToolInfo("remote_$it", "Description $it", schema.toString()) },
            server.copy(id = "two") to listOf(McpToolInfo("LAST_SERVER_TOOL", "Second server", schema.toString())))
    )

    @Test fun fullCatalogContainsEveryToolAndNestedSchema() {
        val value = Json.parseToJsonElement(catalog()).jsonObject
        assertEquals(176, value.getValue("mcp_tool_count").jsonPrimitive.int)
        val servers = value.getValue("mcp_servers").jsonArray
        assertEquals(2, servers.size)
        assertEquals(175, servers[0].jsonObject.getValue("tools").jsonArray.size)
        assertEquals(schema, servers[1].jsonObject.getValue("tools").jsonArray[0].jsonObject["parameters"])
        assertFalse(catalog().contains("SECRET_HEADER") || catalog().contains("not-exported.invalid"))
    }

    @Test fun optionalFiltersHaveNoFortyOrHundredFiftyToolLimit() {
        val all = (1..175).map { McpToolInfo("match_$it", "matching", schema.toString()) }
        val value = Json.parseToJsonElement(ToolCatalog.render(emptyList(), mapOf(server to all), "one", "MATCH")).jsonObject
        assertEquals(175, value.getValue("mcp_tool_count").jsonPrimitive.int)
    }

    @Test fun emptyAndInvalidServerSchemasAreExplicit() {
        val value = ToolCatalog.render(emptyList(), mapOf(server to emptyList(), server.copy(id = "bad") to listOf(McpToolInfo("broken", inputSchemaJson = "not JSON"))))
        assertTrue(value.contains("empty_or_not_discovered"))
        assertTrue(value.contains("schema_error"))
    }

    @Test fun compactModelsCanRequestTheFullCatalog() {
        assertTrue(ToolCatalog.TOOL_NAME in AgentToolRegistry.COMPACT_CORE_TOOL_NAMES)
    }

    @Test fun fullCatalogSurvivesToolResultFormatting() {
        val text = catalog()
        assertTrue(text.length > ToolResultFormatter.MAX_TOOL_RESULT_CHARS)
        assertEquals(text, ToolResultFormatter.forModel(ToolCatalog.TOOL_NAME, ToolRunOutput(text)))
        assertTrue(ToolResultFormatter.forModel("file_read", ToolRunOutput(text)).length < text.length)
    }

    private fun history(fallback: Boolean): List<ChatMessageEntity> {
        val call = ToolCallInfo("catalog", ToolCatalog.TOOL_NAME, "{}", viaTextFallback = fallback)
        return listOf(
            ChatMessageEntity("user", "s", 0, "USER", "List tools"),
            ChatMessageEntity("assistant", "s", 1, "ASSISTANT", toolCallsJson = LlmHttpFactory.json.encodeToString(listOf(call))),
            ChatMessageEntity("tool", "s", 2, "TOOL", catalog(), toolCallId = call.id, toolName = call.name)
        )
    }

    @Test fun fullCatalogReachesNativeToolHistory() {
        assertEquals(catalog(), AgentEngine.buildWireHistory("sys", "", history(false)).single { it.role == LlmRole.TOOL }.content)
    }

    @Test fun fullCatalogReachesTextFallbackHistory() {
        assertTrue(AgentEngine.buildWireHistory("sys", "", history(true)).any { it.content.contains(catalog()) })
    }

    @Test fun fullCatalogReachesReflectionAndClientDowngrade() {
        assertTrue(AgentEngine.buildReflectionWireHistory("sys", "", history(false), 5, 5).any { it.content.contains(catalog()) })
        val wire = AgentEngine.buildWireHistory("sys", "", history(false))
        assertTrue(LlmHttpFactory.downgradeWireMessagesForTextTools(wire, emptyList()).any { it.content.contains(catalog()) })
    }
}
