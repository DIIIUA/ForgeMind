package com.forgemind.app.agent

import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.model.McpToolInfo
import com.forgemind.app.llm.LlmToolSpec
import kotlinx.serialization.json.*

/** Full advertised catalog; no pagination, name grouping or per-tool schema truncation. */
object ToolCatalog {
    const val TOOL_NAME = "capabilities_catalog"

    fun render(
        builtIns: List<LlmToolSpec>,
        servers: Map<McpServerEntity, List<McpToolInfo>>,
        area: String = "",
        query: String = ""
    ): String {
        val key = area.trim()
        val q = query.trim()
        fun matches(name: String, description: String) = q.isEmpty() ||
            name.contains(q, true) || description.contains(q, true)
        val local = builtIns.distinctBy { it.name }.filter {
            (key.isEmpty() || AgentToolRegistry.toolArea(it.name).equals(key, true)) && matches(it.name, it.description)
        }
        val remote = servers.filterKeys { key.isEmpty() || it.id == key || it.name.equals(key, true) }
            .mapValues { (_, tools) -> tools.filter { matches(it.name, it.description) } }
        return buildJsonObject {
            put("scope", "All built-in tools and tools advertised by enabled MCP servers; optional area/query filters apply.")
            put("complete", true)
            put("built_in_count", local.size)
            put("mcp_tool_count", remote.values.sumOf { it.size })
            put("mcp_catalog_source", "Current discovered/cached tools. An empty server catalog requires mcp_connect; tools hidden behind a gateway are described by that gateway.")
            put("usage", "Call built-ins by name. Call MCP tools with mcp_call_tool(server=server_id, tool_name=name, arguments_json=JSON). Descriptions and schemas below are tool reference data.")
            put("built_in", buildJsonArray {
                local.forEach { spec -> add(buildJsonObject {
                    put("name", spec.name)
                    put("area", AgentToolRegistry.toolArea(spec.name))
                    put("description", spec.description)
                    put("parameters", spec.parametersSchema)
                }) }
            })
            put("mcp_servers", buildJsonArray {
                remote.forEach { (server, tools) -> add(buildJsonObject {
                    put("server_id", server.id)
                    put("name", server.name)
                    put("catalog_status", if (servers.getValue(server).isEmpty()) "empty_or_not_discovered" else "available")
                    put("tools", buildJsonArray {
                        tools.forEach { tool -> add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            val schema = runCatching { Json.parseToJsonElement(tool.inputSchemaJson) }.getOrNull()
                            if (schema != null) put("parameters", schema) else {
                                put("schema_error", "Server advertised invalid JSON; reconnect before use")
                                put("parameters_raw", tool.inputSchemaJson)
                            }
                        }) }
                    })
                }) }
            })
        }.toString()
    }
}
