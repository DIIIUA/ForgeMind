package com.forgemind.app.agent

import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.model.McpToolInfo
import com.forgemind.app.llm.LlmToolSpec
import kotlinx.serialization.json.*

/**
 * The application's own toolset in one call.
 *
 * Only ForgeMind's built-in tools are described here. Tools of MCP servers are described by
 * the servers themselves through MCP (they reach the model as tools from the MCP client); the
 * catalog only names the connected servers so the model knows where to look.
 *
 * - no arguments: every built-in tool, grouped by area, with a one-line purpose;
 * - area: one area with parameter signatures;
 * - name: the full description and JSON schema of one built-in tool;
 * - query: matching built-in tools.
 * Every answer is bounded and goes through the normal result limits.
 */
object ToolCatalog {
    const val TOOL_NAME = "capabilities_catalog"
    const val OVERVIEW_BUDGET = 12_000
    const val DETAIL_BUDGET = 12_000
    private const val MCP_NOTE = "Tools of MCP servers are described by those servers through MCP, not by this catalog: " +
        "use the tools the server provides, mcp_call_tool(server=server_id, tool_name=..., arguments_json=...) " +
        "and mcp_connect to refresh a server."

    data class Entry(val id: String, val name: String, val description: String, val area: String, val schema: JsonElement? = null)

    fun entries(builtIns: List<LlmToolSpec>): List<Entry> = builtIns.distinctBy { it.name }.map {
        Entry("local:${it.name}", it.name, it.description, AgentToolRegistry.toolArea(it.name), it.parametersSchema)
    }

    /** First sentence, or the text cut to [max] characters. */
    fun summary(text: String, max: Int): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        val end = t.indexOf(". ").takeIf { it in 1 until max }
        return when {
            end != null -> t.take(end + 1)
            t.length <= max -> t
            else -> t.take(max - 1).trimEnd() + "…"
        }
    }

    /** "path, offset_line?, limit_lines?": names, optional ones marked with '?'. */
    fun signature(schema: JsonElement?): String {
        val obj = schema as? JsonObject ?: return ""
        val props = obj["properties"] as? JsonObject ?: return ""
        val required = (obj["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        return props.keys.joinToString(", ") { if (it in required) it else "$it?" }
    }

    fun render(
        builtIns: List<LlmToolSpec>,
        servers: Map<McpServerEntity, List<McpToolInfo>>,
        area: String = "",
        query: String = "",
        name: String = ""
    ): String {
        val all = entries(builtIns)
        return when {
            name.isNotBlank() -> tool(all, name.trim())
            query.isNotBlank() -> search(all, area.trim(), query.trim())
            area.isNotBlank() -> area(all, servers, area.trim())
            else -> overview(all, servers)
        }
    }

    /** Connected MCP servers by id and name only; their tools are theirs to describe. */
    private fun serverList(servers: Map<McpServerEntity, List<McpToolInfo>>) = buildJsonArray {
        servers.forEach { (server, tools) ->
            add(buildJsonObject {
                put("server_id", server.id)
                put("name", server.name)
                put("status", if (tools.isEmpty()) "tools_not_discovered" else "connected")
            })
        }
    }

    private fun overview(all: List<Entry>, servers: Map<McpServerEntity, List<McpToolInfo>>): String {
        val areas = buildJsonArray {
            val grouped = all.groupBy { it.area }
            (AgentToolRegistry.AREA_TITLES.keys + grouped.keys.filter { it !in AgentToolRegistry.AREA_TITLES }).forEach { key ->
                val tools = grouped[key] ?: return@forEach
                add(buildJsonObject {
                    put("area", key)
                    put("title", AgentToolRegistry.AREA_TITLES[key] ?: "other")
                    put("tools", buildJsonArray { tools.forEach { add("${it.name}: ${summary(it.description, 90)}") } })
                })
            }
        }
        return buildJsonObject {
            put("kind", "overview")
            put("built_in_count", all.size)
            put("usage", "Every ForgeMind tool, by area. Details of one area: area=<area>. Full JSON schema of one tool: " +
                "name=<tool>. Search: query=<words>.")
            put("areas", areas)
            put("mcp_servers", serverList(servers))
            put("mcp_note", MCP_NOTE)
        }.toString()
    }

    private fun area(all: List<Entry>, servers: Map<McpServerEntity, List<McpToolInfo>>, key: String): String {
        val server = servers.keys.firstOrNull { it.id == key || it.name.equals(key, true) }
        if (server != null) {
            return buildJsonObject {
                put("kind", "area")
                put("server_id", server.id)
                put("name", server.name)
                put("mcp_note", MCP_NOTE)
            }.toString()
        }
        val selected = all.filter { it.area.equals(key, true) }
        if (selected.isEmpty()) {
            return buildJsonObject {
                put("kind", "area")
                put("error", "Unknown area '$key'")
                put("areas", buildJsonArray { AgentToolRegistry.AREA_TITLES.keys.forEach { add(it) } })
            }.toString()
        }
        return detailList("area", selected) {
            put("area", key.lowercase()); put("title", AgentToolRegistry.AREA_TITLES[key.lowercase()] ?: "other")
        }
    }

    private fun search(all: List<Entry>, area: String, query: String): String {
        val scoped = if (area.isBlank()) all else all.filter { it.area.equals(area, true) }
        val hits = scoped.filter { it.name.contains(query, true) || it.description.contains(query, true) }
        return detailList("search", hits) { put("query", query); put("total_matches", hits.size) }
    }

    /** Ranked results from an external ranker (local embeddings), same bounded shape. */
    fun ranked(entries: List<Pair<Entry, Float>>, total: Int): String =
        detailList("semantic_search", entries.map { it.first }, entries.associate { it.first.id to it.second }) {
            put("total_candidates", total)
        }

    private fun detailList(
        kind: String,
        entries: List<Entry>,
        scores: Map<String, Float> = emptyMap(),
        extra: JsonObjectBuilder.() -> Unit
    ): String {
        var budget = DETAIL_BUDGET - 800
        val names = mutableListOf<String>()
        val tools = buildJsonArray {
            for (entry in entries) {
                val item = buildJsonObject {
                    put("name", entry.name)
                    put("area", entry.area)
                    put("description", summary(entry.description, 300))
                    put("parameters", signature(entry.schema))
                    scores[entry.id]?.let { put("similarity", it) }
                }
                val size = item.toString().length + 1
                if (budget - size < 0) { names += entry.name; continue }
                budget -= size
                add(item)
            }
        }
        return buildJsonObject {
            put("kind", kind)
            extra()
            put("tools", tools)
            if (names.isNotEmpty()) {
                put("names_only", buildJsonArray { names.forEach { add(it) } })
                put("note", "Listed by name only to stay within the context budget; ask with name=<tool> or a narrower query.")
            }
            put("schema_hint", "Full JSON schema: capabilities_catalog(name=<tool>).")
        }.toString()
    }

    private fun tool(all: List<Entry>, name: String): String {
        val entry = all.firstOrNull { it.name == name } ?: all.firstOrNull { it.name.equals(name, true) }
            ?: return buildJsonObject {
                put("kind", "tool")
                put("error", "No ForgeMind tool named '$name'")
                put("hint", "capabilities_catalog() lists every ForgeMind tool. MCP tools are described by their servers.")
            }.toString()
        return buildJsonObject {
            put("kind", "tool")
            put("name", entry.name)
            put("area", entry.area)
            put("description", entry.description)
            entry.schema?.let { put("parameters", it) }
        }.toString()
    }
}
