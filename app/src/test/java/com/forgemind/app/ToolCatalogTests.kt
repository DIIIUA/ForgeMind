package com.forgemind.app

import com.forgemind.app.agent.*
import com.forgemind.app.data.db.*
import com.forgemind.app.data.model.*
import com.forgemind.app.llm.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The catalog describes ForgeMind itself in one bounded call; MCP servers describe their own tools. */
class ToolCatalogTests {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"items":{"type":"array","items":{"type":"string","enum":["a","b"]}},"mode":{"type":"string"}},"required":["items"]}""").jsonObject
    private val server = McpServerEntity("one", "Owner server", endpoint = "https://not-exported.invalid", headersJson = "SECRET_HEADER")
    private val builtIns = listOf(
        LlmToolSpec("file_read", "Read a file from any workspace. Long second sentence.", schema),
        LlmToolSpec("run_command", "Run a shell command.", schema)
    )
    private val servers = linkedMapOf(
        server to (1..300).map { McpToolInfo("owner_private_tool_$it", "Private description $it", schema.toString()) },
        server.copy(id = "two", name = "Not discovered") to emptyList()
    )
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun overviewDescribesOnlyTheApplication() {
        val text = ToolCatalog.render(builtIns, servers)
        val value = json(text)
        assertEquals("overview", value.getValue("kind").jsonPrimitive.content)
        assertEquals(2, value.getValue("built_in_count").jsonPrimitive.int)
        assertTrue(text.contains("file_read: Read a file from any workspace."))
        assertFalse("MCP tool names belong to their server", text.contains("owner_private_tool"))
        assertFalse(text.contains("Private description"))
        assertFalse(text.contains("SECRET_HEADER") || text.contains("not-exported.invalid"))
        val listed = value.getValue("mcp_servers").jsonArray.map { it.jsonObject }
        assertEquals(listOf("one", "two"), listed.map { it.getValue("server_id").jsonPrimitive.content })
        assertEquals("tools_not_discovered", listed[1].getValue("status").jsonPrimitive.content)
        assertTrue(value.getValue("mcp_note").jsonPrimitive.content.contains("described by those servers"))
        assertTrue(text.length <= ToolCatalog.OVERVIEW_BUDGET)
    }

    @Test fun manyBuiltInsStayWithinBudget() {
        val many = (1..120).map { LlmToolSpec("tool_$it", "Purpose $it. " + "x".repeat(500), schema) }
        val text = ToolCatalog.render(many, emptyMap())
        assertTrue("overview is ${text.length} chars", text.length <= ToolCatalog.OVERVIEW_BUDGET)
        assertFalse(text.contains("xxxxxxxxxx"))
    }

    @Test fun areaGivesSignatures() {
        val tool = json(ToolCatalog.render(builtIns, servers, area = "files")).getValue("tools").jsonArray.single().jsonObject
        assertEquals("file_read", tool.getValue("name").jsonPrimitive.content)
        assertEquals("items, mode?", tool.getValue("parameters").jsonPrimitive.content)
        assertTrue(json(ToolCatalog.render(builtIns, servers, area = "nope")).containsKey("error"))
        val serverArea = ToolCatalog.render(builtIns, servers, area = "one")
        assertFalse(serverArea.contains("owner_private_tool"))
        assertTrue(json(serverArea).containsKey("mcp_note"))
    }

    @Test fun nameReturnsTheFullSchemaOfABuiltIn() {
        val value = json(ToolCatalog.render(builtIns, servers, name = "run_command"))
        assertEquals(schema, value["parameters"])
        assertTrue(json(ToolCatalog.render(builtIns, servers, name = "owner_private_tool_1")).containsKey("error"))
    }

    @Test fun searchCoversOnlyBuiltIns() {
        val value = json(ToolCatalog.render(builtIns, servers, query = "private"))
        assertEquals(0, value.getValue("total_matches").jsonPrimitive.int)
        val shell = json(ToolCatalog.render(builtIns, servers, query = "shell"))
        assertEquals("run_command", shell.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test fun compactModelsCanRequestTheCatalog() {
        assertTrue(ToolCatalog.TOOL_NAME in AgentToolRegistry.COMPACT_CORE_TOOL_NAMES)
    }

    @Test fun catalogResultsFollowTheNormalLimits() {
        val long = "x".repeat(ToolResultFormatter.MAX_TOOL_RESULT_CHARS + 500)
        assertTrue(ToolResultFormatter.forModel(ToolCatalog.TOOL_NAME, ToolRunOutput(long)).contains("ОБРЕЗАНО"))
        val call = ToolCallInfo("catalog", ToolCatalog.TOOL_NAME, "{}", viaTextFallback = true)
        val history = listOf(
            ChatMessageEntity("user", "s", 0, "USER", "List tools"),
            ChatMessageEntity("assistant", "s", 1, "ASSISTANT", toolCallsJson = LlmHttpFactory.json.encodeToString(listOf(call))),
            ChatMessageEntity("tool", "s", 2, "TOOL", long, toolCallId = call.id, toolName = call.name)
        )
        assertTrue(AgentEngine.buildWireHistory("sys", "", history).none { it.content.contains(long) })
    }
}
