package com.forgemind.app

import com.forgemind.app.agent.AgentToolRegistry
import com.forgemind.app.data.archive.PortableSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent learns its toolset and environments in one call; settings transfer keeps every type. */
class CapabilityCatalogTests {

    private val builtInNames = """
        web_download notify_user report_tool_issue check_environment_status capabilities_catalog list_files
        search_files search_codebase file_read file_write file_edit file_patch file_append file_delete
        create_folder archive_create archive_extract transfer_file send_file_to_chat run_command
        run_command_async check_command_status kill_command list_command_tasks wait_command_tasks web_search web_scrape web_scrape_site browser_open
        screen_read_windows screen_act_tag screen_act_batch screen_capture mcp_list_servers mcp_connect
        mcp_call_tool mcp_read_resource file_info file_move snapshot_list snapshot_restore editor_open_file
        editor_list_tabs preview_server_control browser_agent_tabs browser_agent_navigate browser_agent_inspect
        browser_agent_markdown browser_agent_act browser_agent_batch browser_agent_eval_js browser_agent_screenshot
        browser_agent_mode terminal_list_tabs terminal_open_tab terminal_send chat_list_sessions
        chat_search_history chat_read_session chat_manage chat_export_markdown context_compact app_navigate voice_speak
    """.trim().split(Regex("\\s+"))

    @Test
    fun `every built-in tool belongs to a named area`() {
        assertEquals(64, builtInNames.size)
        val unassigned = builtInNames.filter { AgentToolRegistry.toolArea(it) !in AgentToolRegistry.AREA_TITLES }
        assertTrue("unassigned: $unassigned", unassigned.isEmpty())
        assertEquals("terminal", AgentToolRegistry.toolArea("run_command_async"))
        assertEquals("files", AgentToolRegistry.toolArea("send_file_to_chat"))
        assertEquals("environment", AgentToolRegistry.toolArea("capabilities_catalog"))
        assertEquals("other", AgentToolRegistry.toolArea("some_future_tool"))
    }

    @Test
    fun `command probe output becomes one readable line`() {
        val line = AgentToolRegistry.formatCapabilityProbe(
            "python3=yes\npip=yes\ncurl=no\nrish=no\nuid=10327\npython=Python 3.14.10\n"
        )
        assertEquals("есть: python3, pip | нет: curl, rish | uid=10327; python=Python 3.14.10", line)
        assertEquals("нет вывода", AgentToolRegistry.formatCapabilityProbe(""))
    }

    @Test
    fun `old settings backups without numeric types still load`() {
        val old = """{"strings":{"a":"x"},"booleans":{"b":true},"secrets":{"k":"v"}}"""
        val parsed = Json.decodeFromString(PortableSettings.serializer(), old)
        assertEquals(mapOf("a" to "x"), parsed.strings)
        assertTrue(parsed.ints.isEmpty() && parsed.stringSets.isEmpty())
        val withNumbers = PortableSettings(ints = mapOf("n" to 3), stringSets = mapOf("s" to setOf("p", "q")))
        val round = Json.decodeFromString(PortableSettings.serializer(), Json.encodeToString(PortableSettings.serializer(), withNumbers))
        assertEquals(withNumbers, round)
    }
}
