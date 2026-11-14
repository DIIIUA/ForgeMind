package com.forgemind.app.agent

import com.forgemind.app.bridge.TerminalMode
import com.forgemind.app.bridge.TerminalSessionManager
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.archive.ChatArchiveManager
import com.forgemind.app.data.db.ChatFolderEntity
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.db.ForgeMindDatabase
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.llm.LlmToolSpec
import com.forgemind.app.voice.TtsManager
import com.forgemind.app.web.AgentBrowserController
import com.forgemind.app.web.BrowserBatchStep
import com.forgemind.app.web.LocalPreviewServer
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class EditorTabSnapshot(
    val id: String,
    val title: String,
    val path: String,
    val env: WorkspaceEnvType,
    val isDirty: Boolean,
    val isActive: Boolean,
    val content: String
)

internal class AppLeverTools(
    private val workspaceManager: WorkspaceManager,
    private val browserController: AgentBrowserController?,
    private val localPreviewServer: LocalPreviewServer?,
    private val terminalManager: TerminalSessionManager?,
    private val archiveManager: ChatArchiveManager?,
    private val db: ForgeMindDatabase?,
    private val settingsRepo: SettingsRepository?,
    private val ttsManager: TtsManager?,
    private val termuxBridge: com.forgemind.app.bridge.TermuxBridge,
    private val onNavigateSection: (String) -> Boolean,
    private val onOpenEditorTab: (WorkspaceEnvType, String, String, String?) -> String,
    private val onListEditorTabs: () -> List<EditorTabSnapshot>,
    private val onPreviewPortChanged: (Int) -> Unit,
    private val onCompactContext: suspend (String) -> Result<String>
) {

    fun safeApprovalTools(): Set<String> = setOf("file_move", "snapshot_restore", "terminal_send", "browser_agent_eval_js")

    fun specs(): List<LlmToolSpec> = listOf(
        spec(
            "file_info",
            "Get detailed metadata for a file or folder (type, size, line count, last modified time).",
            objSchema(
                mapOf(
                    "path" to strProp("Path to the file or folder"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (for termux_ssh)")
                ),
                listOf("path")
            )
        ),
        spec(
            "file_move",
            "Rename or move a file or folder inside the workspace environment.",
            objSchema(
                mapOf(
                    "source_path" to strProp("Source path"),
                    "target_path" to strProp("Destination path"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (for termux_ssh)")
                ),
                listOf("source_path", "target_path")
            )
        ),
        spec(
            "snapshot_list",
            "List saved file rollback snapshots in the current session.",
            objSchema(mapOf("limit" to intProp("Maximum entries (default 20)")))
        ),
        spec(
            "snapshot_restore",
            "Restore a file from a saved snapshot by snapshot_id.",
            objSchema(mapOf("snapshot_id" to strProp("Snapshot ID from snapshot_list")), listOf("snapshot_id"))
        ),
        spec(
            "editor_open_file",
            "Open a file in the built-in code editor background buffer (does not switch the user's screen or disturb their active tab).",
            objSchema(
                mapOf(
                    "path" to strProp("Path to the text file"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (for termux_ssh)")
                ),
                listOf("path")
            )
        ),
        spec(
            "editor_list_tabs",
            "List currently open code editor tabs and (optionally) their unsaved buffer text.",
            objSchema(mapOf("include_content" to boolProp("Include open tab text content (default false)")))
        ),
        spec(
            "preview_server_control",
            "Start, check, or stop the built-in local HTTP preview server for the internal project directory ('internal').",
            objSchema(
                mapOf(
                    "action" to strProp("start | stop | status"),
                    "open_in_browser" to boolProp("Immediately open the local URL in the background Agent Browser (default true on start)")
                ),
                listOf("action")
            )
        ),
        spec(
            "browser_agent_tabs",
            "AGENT BROWSER (MULTI-TAB PARALLEL BACKGROUND MODE): open multiple websites concurrently in background WebViews (up to 8 live windows), list all open AI windows, dump unified inspect/Markdown across all windows at once, switch the active window, or close unused windows. Open windows persist across app restarts and updates (use action='close_all' to clear all windows).",
            objSchema(
                mapOf(
                    "action" to strProp("open_parallel | list | new | switch | inspect_all | markdown_all | close | close_others | close_all"),
                    "urls" to strArrayProp("Array of URLs to open simultaneously in the background when action='open_parallel' (up to 8 sites at once)"),
                    "tab_ids" to strArrayProp("Optional readable window IDs for open_parallel (e.g. ['docs', 'pricing', 'github'])"),
                    "tab_id" to strProp("Window ID (e.g. 'tab_1' or 'docs') for new / switch / close / close_others"),
                    "url" to strProp("Optional URL to open when action='new'"),
                    "turbo" to boolProp("Optionally toggle shared ⚡ Turbo mode (true = ON with visible badge for user, false = OFF; omit to keep current choice)"),
                    "return_inspect" to boolProp("For open_parallel: immediately return indexed interactive elements [0..N] for each opened site (default true)"),
                    "return_markdown" to boolProp("For open_parallel: immediately return GFM Markdown layout for each opened site (default false)"),
                    "max_chars_per_tab" to intProp("Total character budget per window (min 120; caps both text/Markdown and interactive elements list, default 3500)"),
                    "wait_seconds" to intProp("Load wait timeout in seconds (1..30, default 15)")
                ),
                listOf("action")
            )
        ),
        spec(
            "browser_agent_navigate",
            "AGENT BROWSER (dedicated background AI window pool, never touches the user's Regular Browser): open a URL in the target or new background window tab_id, wait for load, and optionally return the interactive element map [0..N] or structured Markdown in one shot.",
            objSchema(
                mapOf(
                    "url" to strProp("Website URL or search query"),
                    "tab_id" to strProp("Background AI window ID (defaults to active window; creates a new window if an unused ID is given)"),
                    "new_tab" to boolProp("Open URL in a new background AI window without closing existing pages (default false)"),
                    "wait_seconds" to intProp("Load wait timeout in seconds (1..30, default 15)"),
                    "turbo" to boolProp("Optionally toggle shared ⚡ Turbo mode (true = enable, false = disable; omit to keep current mode)"),
                    "return_inspect" to boolProp("Immediately return indexed interactive elements [0..N] after navigation (default false)"),
                    "return_markdown" to boolProp("Immediately return clean GFM Markdown of the loaded page (default false)"),
                    "max_chars" to intProp("Character limit when return_inspect / return_markdown is true (default 6000)")
                ),
                listOf("url")
            )
        ),
        spec(
            "browser_agent_inspect",
            "AGENT BROWSER: read the live page state in window tab_id (URL, title, scroll, indexed interactive elements [0..N], visible text or Markdown, and JS console errors).",
            objSchema(
                mapOf(
                    "tab_id" to strProp("Background AI window ID (defaults to active window)"),
                    "max_chars" to intProp("Maximum text/Markdown characters (default 5500)"),
                    "format" to strProp("default (elements + visible text) | markdown (rich GFM Markdown layout + elements)")
                )
            )
        ),
        spec(
            "browser_agent_markdown",
            "AGENT BROWSER: export the currently rendered live page of window tab_id (including authenticated SPAs and dynamic tabs) into structured GFM Markdown with headings, tables, links, code blocks, and interactive elements [0..N].",
            objSchema(
                mapOf(
                    "tab_id" to strProp("Background AI window ID (defaults to active window)"),
                    "max_chars" to intProp("Maximum Markdown characters (default 18000)"),
                    "selector" to strProp("Optional CSS selector of root container (defaults to main/article/body)"),
                    "include_interactive" to boolProp("Append indexed interactive elements [0..N] at the end (default true)"),
                    "save_to_path" to strProp("Optional workspace file path to save the .md output directly to disk"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (when saving save_to_path into termux_ssh)")
                )
            )
        ),
        spec(
            "browser_agent_act",
            "AGENT BROWSER: perform an interactive action on the live page of window tab_id (click, type, scroll, Enter, select, back/forward/reload/wait). Automatically re-indexes fresh [0..N] elements on click/submit.",
            objSchema(
                mapOf(
                    "action" to strProp("click | type | scroll | press_enter | select | back | forward | reload | wait"),
                    "tab_id" to strProp("Background AI window ID (defaults to active window)"),
                    "element_index" to intProp("Element index [N] from the latest inspect/act of that window"),
                    "selector" to strProp("CSS selector (when element_index is omitted)"),
                    "text" to strProp("Find element by visible text, placeholder, or aria-label"),
                    "value" to strProp("Text for type/select, scroll direction (down | up | top | bottom | pixels), or wait milliseconds"),
                    "auto_inspect" to boolProp("Re-index and return fresh [0..N] elements right after the action (default true for click/press_enter/select)")
                ),
                listOf("action")
            )
        ),
        spec(
            "browser_agent_batch",
            "AGENT BROWSER (HIGH SPEED + MULTI-WINDOW): execute up to 30 sequential actions in one fast burst with zero LLM round-trip delay between steps (including cross-window steps via tab_id / switch_tab) and return the final DOM/Markdown in a single response.",
            objSchema(
                mapOf(
                    "steps" to buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put(
                            "description",
                            JsonPrimitive("Array of steps: [{action: 'navigate'|'switch_tab'|'click'|'type'|'fill_form'|'select'|'press_enter'|'scroll'|'wait_selector'|'wait_text'|'wait'|'eval_js'|'extract_markdown'|'screenshot', tab_id?, new_tab?, url?, index?/element_index?, selector?, text?, value?, fields?, submit?, timeout_ms?, filename?}]")
                        )
                        put("items", buildJsonObject { put("type", JsonPrimitive("object")) })
                    },
                    "tab_id" to strProp("Initial background AI window ID (defaults to active window; each step can also pass its own tab_id or new_tab=true)"),
                    "turbo" to boolProp("Optionally toggle shared ⚡ Turbo mode (true = enable, false = disable; omit to keep current AI/user choice)"),
                    "stop_on_error" to boolProp("Stop batch on first step error (default true)"),
                    "return_inspect" to boolProp("Return fresh interactive elements [0..N] at the end (default true)"),
                    "return_markdown" to boolProp("Return full GFM Markdown of the final page at the end (default false)"),
                    "max_chars" to intProp("Character limit for the final snapshot/Markdown (default 8000)")
                ),
                listOf("steps")
            )
        ),
        spec(
            "browser_agent_eval_js",
            "AGENT BROWSER: execute custom JavaScript on the currently open live page of window tab_id and return the result.",
            objSchema(
                mapOf(
                    "script" to strProp("JavaScript code (expression or IIFE)"),
                    "tab_id" to strProp("Background AI window ID (defaults to active window)")
                ),
                listOf("script")
            )
        ),
        spec(
            "browser_agent_screenshot",
            "AGENT BROWSER: capture a full PNG screenshot of window tab_id (automatically loads images even when ⚡ Turbo is ON) and attach it to chat / save it in the project.",
            objSchema(
                mapOf(
                    "tab_id" to strProp("Background AI window ID (defaults to active window)"),
                    "filename" to strProp("File name (default browser_shot_<ts>.png)"),
                    "attach_to_chat" to boolProp("Attach screenshot directly to the chat message (default true)")
                )
            )
        ),
        spec(
            "browser_agent_mode",
            "AGENT BROWSER (background): check all open windows & ⚡ Turbo status, toggle shared high-speed mode (turbo_on / turbo_off — synchronously updates the visible ⚡ Turbo badge for the user), read its action log, or reset a window.",
            objSchema(
                mapOf(
                    "action" to strProp("status | logs | home | turbo_on | turbo_off"),
                    "tab_id" to strProp("Optional window ID when action='home'")
                ),
                listOf("action")
            )
        ),
        spec(
            "terminal_list_tabs",
            "List the embedded physical AI shell state and open terminal tabs (Local Shell / SSH) along with recent output.",
            objSchema(mapOf("tail_chars" to intProp("Number of trailing output characters to include (default 3500)")))
        ),
        spec(
            "terminal_open_tab",
            "Open a new background terminal tab (local shell or configured SSH profile) without switching the user's active screen.",
            objSchema(
                mapOf(
                    "mode" to strProp("local | ssh (defaults to local for internal or ssh for termux_ssh)"),
                    "server" to strProp("Existing SSH profile name or ID (when mode='ssh')"),
                    "title" to strProp("Optional tab title")
                )
            )
        ),
        spec(
            "terminal_send",
            "Send a command or control signal (Ctrl+C, Tab, Esc, clear) to an open terminal tab.",
            objSchema(
                mapOf(
                    "command" to strProp("Command line (without trailing \\n)"),
                    "signal" to strProp("ctrl_c | tab | esc | clear (when sending a control signal instead of a command)"),
                    "tab_id" to strProp("Tab ID (defaults to active tab)"),
                    "wait_ms" to intProp("Delay before reading output in ms (default 650)")
                )
            )
        ),
        spec(
            "chat_list_sessions",
            "List saved chat sessions and folders in the app.",
            objSchema(
                mapOf(
                    "include_archived" to boolProp("Include archived sessions (default false)"),
                    "folder_id" to strProp("Optional folder ID filter")
                )
            )
        ),
        spec(
            "chat_search_history",
            "Search across titles and message contents in all past chat sessions.",
            objSchema(
                mapOf(
                    "query" to strProp("Search phrase"),
                    "limit" to intProp("Maximum matches (default 15)")
                ),
                listOf("query")
            )
        ),
        spec(
            "chat_read_session",
            "Read recent messages from a saved chat session by session_id.",
            objSchema(
                mapOf(
                    "session_id" to strProp("Session ID from chat_list_sessions or chat_search_history"),
                    "limit_messages" to intProp("Number of recent messages to read (default 20)")
                ),
                listOf("session_id")
            )
        ),
        spec(
            "chat_manage",
            "Organize chat sessions and folders (create session/folder, rename, pin/unpin, assign folder; non-destructive).",
            objSchema(
                mapOf(
                    "action" to strProp("create_session | rename_session | create_folder | assign_folder | pin | unpin"),
                    "session_id" to strProp("Session ID (defaults to current session)"),
                    "title" to strProp("New session or folder title"),
                    "folder_id" to strProp("Folder ID (for assign_folder / create_session)")
                ),
                listOf("action")
            )
        ),
        spec(
            "chat_export_markdown",
            "Export a chat session as Markdown (.md) into the internal project folder and optionally attach it to chat.",
            objSchema(
                mapOf(
                    "session_id" to strProp("Session ID (defaults to current session)"),
                    "filename" to strProp("Optional .md file name"),
                    "attach_to_chat" to boolProp("Attach exported file to chat (default true)")
                )
            )
        ),
        spec(
            "context_compact",
            "Compact the current conversation context (summarize long history to save tokens).",
            emptyObjSchema()
        ),
        spec(
            "app_navigate",
            "Switch the active app tab (call ONLY when the user explicitly asked to switch their screen).",
            objSchema(
                mapOf("section" to strProp("chat | files | terminal | preview | settings")),
                listOf("section")
            )
        ),
        spec(
            "voice_speak",
            "Speak text aloud via the configured TTS engine, stop speech playback, or inspect current voice status.",
            objSchema(
                mapOf(
                    "action" to strProp("speak | stop | status"),
                    "text" to strProp("Text to speak aloud (when action='speak')")
                ),
                listOf("action")
            )
        )
    )

    suspend fun executeIfHandled(
        toolName: String,
        args: JsonObject,
        defaultEnv: WorkspaceEnvType,
        internalProject: String,
        sessionId: String
    ): ToolRunOutput? {
        fun str(key: String): String? = when (val v = args[key]) {
            is JsonPrimitive -> v.contentOrNull
            is JsonObject, is JsonArray -> v.toString()
            else -> null
        }
        fun int(key: String, def: Int): Int = (args[key] as? JsonPrimitive)?.intOrNull ?: def
        fun bool(key: String, def: Boolean): Boolean = (args[key] as? JsonPrimitive)?.booleanOrNull ?: def
        fun resolveEnv(): WorkspaceEnvType = str("target_env")?.let { WorkspaceEnvType.fromId(it) } ?: defaultEnv

        return when (toolName) {
            "file_info" -> {
                val path = str("path") ?: return ToolRunOutput("Missing required parameter: path", isError = true)
                workspaceManager.getFileInfo(resolveEnv(), path, internalProject, str("server")).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("file_info error: ${it.message}", isError = true) }
                )
            }

            "file_move" -> {
                val src = str("source_path") ?: return ToolRunOutput("Missing required parameter: source_path", isError = true)
                val dst = str("target_path") ?: return ToolRunOutput("Missing required parameter: target_path", isError = true)
                workspaceManager.moveOrRenameFile(resolveEnv(), src, dst, internalProject, str("server")).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("file_move error: ${it.message}", isError = true) }
                )
            }

            "snapshot_list" -> {
                val limit = int("limit", 20).coerceIn(1, 50)
                val list = workspaceManager.listSessionSnapshots(sessionId, limit)
                if (list.isEmpty()) {
                    ToolRunOutput("No rollback snapshots have been created in this session yet.")
                } else {
                    val fmt = SimpleDateFormat("HH:mm:ss dd.MM", Locale.getDefault())
                    val text = list.joinToString("\n") { s ->
                        val state = if (s.restored) "[restored]" else "[ready]"
                        "- id=${s.id} | ${s.envType}:${s.filePath} | existedBefore=${s.fileExistedBefore} | ${fmt.format(Date(s.createdAt))} $state"
                    }
                    ToolRunOutput(text)
                }
            }

            "snapshot_restore" -> {
                val snapId = str("snapshot_id") ?: return ToolRunOutput("Missing required parameter: snapshot_id", isError = true)
                workspaceManager.restoreSnapshot(snapId, internalProject).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("snapshot_restore error: ${it.message}", isError = true) }
                )
            }

            "editor_open_file" -> {
                val path = str("path") ?: return ToolRunOutput("Missing required parameter: path", isError = true)
                val env = resolveEnv()
                val server = str("server")
                workspaceManager.readRawBytes(env, path, internalProject, server).fold(
                    onSuccess = { bytes ->
                        if (WorkspaceManager.looksBinaryBytes(bytes)) {
                            return ToolRunOutput("File '$path' is binary (${bytes.size} B) and cannot be opened as text.", isError = true)
                        }
                        val title = onOpenEditorTab(env, path, bytes.toString(Charsets.UTF_8), server)
                        ToolRunOutput("File '$title' (${env.id}:$path) opened in the background editor buffer (without switching the user's screen).")
                    },
                    onFailure = { ToolRunOutput("editor_open_file error: ${it.message}", isError = true) }
                )
            }

            "editor_list_tabs" -> {
                val includeContent = bool("include_content", false)
                val tabs = onListEditorTabs()
                if (tabs.isEmpty()) {
                    ToolRunOutput("No editor tabs are currently open.")
                } else {
                    val out = buildString {
                        tabs.forEach { t ->
                            val flags = listOfNotNull(
                                if (t.isActive) "ACTIVE" else null,
                                if (t.isDirty) "UNSAVED" else null
                            ).joinToString(", ")
                            appendLine("- [${t.id}] ${t.title} (${t.env.id}:${t.path}) ${if (flags.isNotBlank()) "[$flags]" else ""}")
                            if (includeContent) {
                                appendLine("```text\n${t.content.take(4000)}\n```")
                            }
                        }
                    }
                    ToolRunOutput(out.trimEnd())
                }
            }

            "preview_server_control" -> {
                val srv = localPreviewServer ?: return ToolRunOutput("Local preview server is unavailable", isError = true)
                val act = str("action")?.lowercase() ?: "status"
                when (act) {
                    "start" -> {
                        val dir = workspaceManager.getInternalProjectDir(internalProject)
                        val port = srv.start(dir)
                        onPreviewPortChanged(port)
                        val url = "http://127.0.0.1:$port/"
                        if (bool("open_in_browser", true)) {
                            browserController?.navigate(url, enableAgentMode = false, waitFinishMs = 6000L)
                        }
                        ToolRunOutput("Local HTTP server started over ${dir.name}: $url")
                    }
                    "stop" -> {
                        srv.stop()
                        onPreviewPortChanged(0)
                        ToolRunOutput("Local HTTP preview server stopped.")
                    }
                    else -> {
                        val port = srv.port
                        ToolRunOutput(
                            if (port > 0) "Local HTTP server is active: http://127.0.0.1:$port/"
                            else "Local HTTP server is stopped."
                        )
                    }
                }
            }

            "browser_agent_tabs" -> {
                val ctrl = browserController ?: return ToolRunOutput("Browser controller is unavailable", isError = true)
                val act = str("action")?.trim()?.lowercase()
                    ?: return ToolRunOutput("Missing required parameter: action for browser_agent_tabs", isError = true)
                val turboOpt = (args["turbo"] as? JsonPrimitive)?.booleanOrNull
                if (turboOpt != null) {
                    ctrl.setTurboMode(turboOpt)
                }
                when (act) {
                    "list", "status" -> ToolRunOutput(ctrl.listTabsSummary())

                    "open_parallel" -> {
                        val urlsList = parseStringListArg(args["urls"])
                            .ifEmpty { str("url")?.let { listOf(it) }.orEmpty() }
                        if (urlsList.isEmpty()) {
                            return ToolRunOutput("Pass a non-empty 'urls' array for action='open_parallel'", isError = true)
                        }
                        val tabIdsList = parseStringListArg(args["tab_ids"]).takeIf { it.isNotEmpty() }
                        val waitSec = int("wait_seconds", 15).coerceIn(1, 30)
                        val retInspect = bool("return_inspect", true)
                        val retMd = bool("return_markdown", false)
                        val maxPerTab = int("max_chars_per_tab", 3500)
                        ctrl.openParallelTabs(
                            urls = urlsList,
                            tabIds = tabIdsList,
                            waitFinishMs = waitSec * 1000L,
                            turbo = null,
                            includeInspect = retInspect,
                            includeMarkdown = retMd,
                            maxCharsPerTab = maxPerTab
                        ).fold(
                            onSuccess = { ToolRunOutput(it) },
                            onFailure = { ToolRunOutput("open_parallel error: ${it.message}", isError = true) }
                        )
                    }

                    "new", "open" -> {
                        val reqId = str("tab_id")
                        val initUrl = str("url")?.trim().orEmpty()
                        val createdId = ctrl.createOrSelectTab(requestedTabId = reqId, initialUrl = "", makeActive = true)
                        if (initUrl.isNotEmpty()) {
                            val waitSec = int("wait_seconds", 15).coerceIn(1, 30)
                            ctrl.navigate(
                                rawUrl = initUrl,
                                enableAgentMode = false,
                                waitFinishMs = waitSec * 1000L,
                                turbo = null,
                                includeInspect = bool("return_inspect", true),
                                includeMarkdown = bool("return_markdown", false),
                                maxChars = int("max_chars_per_tab", 5000),
                                tabId = createdId,
                                newTab = false
                            ).fold(
                                onSuccess = { ToolRunOutput(it) },
                                onFailure = { ToolRunOutput("Failed to open window [$createdId]: ${it.message}", isError = true) }
                            )
                        } else {
                            ToolRunOutput("Opened new background window [$createdId].\n${ctrl.listTabsSummary()}")
                        }
                    }

                    "switch", "select" -> {
                        val tid = str("tab_id") ?: return ToolRunOutput("Specify tab_id to switch windows", isError = true)
                        if (ctrl.switchAgentTab(tid)) {
                            ToolRunOutput("Switched active AI window to [$tid].\n${ctrl.listTabsSummary()}")
                        } else {
                            ToolRunOutput("Window '$tid' not found.\n${ctrl.listTabsSummary()}", isError = true)
                        }
                    }

                    "inspect_all" -> {
                        val maxPerTab = int("max_chars_per_tab", 3500)
                        ctrl.inspectAllTabs(format = "default", maxCharsPerTab = maxPerTab).fold(
                            onSuccess = { ToolRunOutput(it) },
                            onFailure = { ToolRunOutput("inspect_all error: ${it.message}", isError = true) }
                        )
                    }

                    "markdown_all" -> {
                        val maxPerTab = int("max_chars_per_tab", 4500)
                        ctrl.inspectAllTabs(format = "markdown", maxCharsPerTab = maxPerTab).fold(
                            onSuccess = { ToolRunOutput(it) },
                            onFailure = { ToolRunOutput("markdown_all error: ${it.message}", isError = true) }
                        )
                    }

                    "close" -> {
                        val tid = str("tab_id") ?: ctrl.activeAgentTabId.value
                        ctrl.closeAgentTab(tid).fold(
                            onSuccess = { ToolRunOutput("$it\n${ctrl.listTabsSummary()}") },
                            onFailure = { ToolRunOutput("Failed to close window: ${it.message}", isError = true) }
                        )
                    }

                    "close_others" -> {
                        val msg = ctrl.closeOtherAgentTabs(str("tab_id"))
                        ToolRunOutput("$msg\n${ctrl.listTabsSummary()}")
                    }

                    "close_all" -> {
                        val msg = ctrl.closeAllAgentTabs()
                        ToolRunOutput("$msg\n${ctrl.listTabsSummary()}")
                    }

                    else -> ToolRunOutput(
                        "Unknown browser_agent_tabs action: '$act'. Valid actions: open_parallel, list, new, switch, inspect_all, markdown_all, close, close_others, close_all.",
                        isError = true
                    )
                }
            }

            "browser_agent_navigate" -> {
                val ctrl = browserController ?: return ToolRunOutput("Browser controller is unavailable", isError = true)
                val rawUrl = str("url") ?: return ToolRunOutput("Missing required parameter: url", isError = true)
                val waitSec = int("wait_seconds", 15).coerceIn(1, 30)
                val turboOpt = (args["turbo"] as? JsonPrimitive)?.booleanOrNull
                val retInspect = bool("return_inspect", false)
                val retMd = bool("return_markdown", false)
                val maxChars = int("max_chars", 6000)
                val tabId = str("tab_id")
                val newTab = bool("new_tab", false)
                ctrl.navigate(
                    rawUrl = rawUrl,
                    enableAgentMode = false,
                    waitFinishMs = waitSec * 1000L,
                    turbo = turboOpt,
                    includeInspect = retInspect,
                    includeMarkdown = retMd,
                    maxChars = maxChars,
                    tabId = tabId,
                    newTab = newTab
                ).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("browser_agent_navigate error: ${it.message}", isError = true) }
                )
            }

            "browser_agent_inspect" -> {
                val ctrl = browserController ?: return ToolRunOutput("Browser controller is unavailable", isError = true)
                val maxChars = int("max_chars", 5500)
                val fmt = str("format") ?: "default"
                val tabId = str("tab_id")
                ctrl.inspect(maxChars, fmt, tabId = tabId).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("browser_agent_inspect error: ${it.message}", isError = true) }
                )
            }

            "browser_agent_markdown" -> {
                val ctrl = browserController ?: return ToolRunOutput("Browser controller is unavailable", isError = true)
                val maxChars = int("max_chars", 18_000)
                val sel = str("selector")
                val withInteractive = bool("include_interactive", true)
                val savePath = str("save_to_path")?.trim()?.takeIf { it.isNotEmpty() }
                val tabId = str("tab_id")
                ctrl.extractMarkdown(maxChars, sel, withInteractive, tabId = tabId).fold(
                    onSuccess = { md ->
                        val savedNote = if (savePath != null) {
                            workspaceManager.writeFile(
                                env = resolveEnv(),
                                path = savePath,
                                content = md,
                                sessionId = sessionId,
                                messageId = "browser_md",
                                saveSnapshot = false,
                                internalProject = internalProject,
                                sshServer = str("server")
                            ).fold(
                                onSuccess = { "\n\n[Saved to file: $savePath]" },
                                onFailure = { "\n\n[Failed to save to $savePath: ${it.message}]" }
                            )
                        } else ""
                        ToolRunOutput(md + savedNote)
                    },
                    onFailure = { ToolRunOutput("browser_agent_markdown error: ${it.message}", isError = true) }
                )
            }

            "browser_agent_act" -> {
                val ctrl = browserController ?: return ToolRunOutput("Browser controller is unavailable", isError = true)
                val actName = str("action") ?: return ToolRunOutput("Missing required parameter: action", isError = true)
                val idx = (args["element_index"] as? JsonPrimitive)?.intOrNull
                val defaultAutoInspect = actName.trim().lowercase() in setOf("click", "press_enter", "select")
                val autoInspect = bool("auto_inspect", defaultAutoInspect)
                val tabId = str("tab_id")
                ctrl.act(
                    action = actName,
                    selector = str("selector"),
                    elementIndex = idx,
                    textMatch = str("text"),
                    value = str("value"),
                    autoInspect = autoInspect,
                    tabId = tabId
                ).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("browser_agent_act error: ${it.message}", isError = true) }
                )
            }

            "browser_agent_batch" -> {
                val ctrl = browserController ?: return ToolRunOutput("Browser controller is unavailable", isError = true)
                val stepsArr = args["steps"] as? JsonArray
                    ?: return ToolRunOutput("Missing required parameter: steps array", isError = true)
                val parsedSteps = parseBrowserBatchSteps(stepsArr)
                if (parsedSteps.isEmpty()) {
                    return ToolRunOutput("Parameter 'steps' is empty or contains no valid actions", isError = true)
                }
                val turboOpt = (args["turbo"] as? JsonPrimitive)?.booleanOrNull
                val stopOnErr = bool("stop_on_error", true)
                val retInspect = bool("return_inspect", true)
                val retMd = bool("return_markdown", false)
                val maxChars = int("max_chars", 8000)
                val tabId = str("tab_id")

                ctrl.executeBatch(
                    steps = parsedSteps,
                    turbo = turboOpt,
                    stopOnError = stopOnErr,
                    returnInspect = retInspect,
                    returnMarkdown = retMd,
                    maxChars = maxChars,
                    tabId = tabId,
                    screenshotFileResolver = { rawName ->
                        val clean = (rawName?.trim()?.ifBlank { null } ?: "browser_${System.currentTimeMillis()}.png")
                            .substringAfterLast('/').substringAfterLast('\\').replace("..", "_")
                            .ifBlank { "browser_${System.currentTimeMillis()}.png" }
                        File(workspaceManager.chatFilesDir, clean)
                    }
                ).fold(
                    onSuccess = { outcome ->
                        val atts = outcome.capturedFiles
                            .filter { (file, attach) -> attach && file.exists() }
                            .map { (file, _) -> workspaceManager.buildAttachmentFromLocalFile(file, isFromAssistant = true) }
                        ToolRunOutput(
                            text = outcome.summaryText,
                            isError = outcome.hadError && stopOnErr,
                            outgoingAttachments = atts
                        )
                    },
                    onFailure = { ToolRunOutput("browser_agent_batch error: ${it.message}", isError = true) }
                )
            }

            "browser_agent_eval_js" -> {
                val ctrl = browserController ?: return ToolRunOutput("Browser controller is unavailable", isError = true)
                val script = str("script") ?: return ToolRunOutput("Missing required parameter: script", isError = true)
                val tabId = str("tab_id")
                ctrl.evalJs(script, tabId = tabId).fold(
                    onSuccess = { ToolRunOutput(it.take(16_000)) },
                    onFailure = { ToolRunOutput("browser_agent_eval_js error: ${it.message}", isError = true) }
                )
            }

            "browser_agent_screenshot" -> {
                val ctrl = browserController ?: return ToolRunOutput("Browser controller is unavailable", isError = true)
                val rawName = (str("filename")?.trim()?.ifBlank { null }
                    ?: "browser_${System.currentTimeMillis()}.png")
                val safeName = rawName.substringAfterLast('/').substringAfterLast('\\').replace("..", "_")
                    .ifBlank { "browser_${System.currentTimeMillis()}.png" }
                val outFile = File(workspaceManager.chatFilesDir, safeName)
                val tabId = str("tab_id")
                ctrl.captureScreenshot(outFile, tabId = tabId).fold(
                    onSuccess = { saved ->
                        val attach = bool("attach_to_chat", true)
                        val atts = if (attach) listOf(workspaceManager.buildAttachmentFromLocalFile(saved, isFromAssistant = true)) else emptyList()
                        ToolRunOutput(
                            text = "Agent Browser [${tabId ?: ctrl.activeAgentTabId.value}] screenshot saved: ${saved.name} (${saved.length()} bytes)" +
                                if (attach) " and attached to chat." else ".",
                            outgoingAttachments = atts
                        )
                    },
                    onFailure = { ToolRunOutput("browser_agent_screenshot error: ${it.message}", isError = true) }
                )
            }

            "browser_agent_mode" -> {
                val ctrl = browserController ?: return ToolRunOutput("Browser controller is unavailable", isError = true)
                when (str("action")?.lowercase() ?: "status") {
                    "home", "reset" -> {
                        val tid = str("tab_id")
                        ctrl.resetAgentBrowser(tid)
                        ToolRunOutput("Background Agent Browser window [${tid ?: ctrl.activeAgentTabId.value}] reset to start page (user's Regular Browser was not touched).")
                    }
                    "turbo_on" -> {
                        ctrl.setTurboMode(true)
                        ToolRunOutput("⚡ Agent Browser shared Turbo mode ENABLED (visible ⚡ Turbo badge is now shown to the user: ad/tracker/font/heavy-media blocking, 0ms CSS transitions, and early DOM-ready return).")
                    }
                    "turbo_off" -> {
                        ctrl.setTurboMode(false)
                        ToolRunOutput("Agent Browser shared Turbo mode DISABLED (⚡ Turbo badge hidden; full fonts, images, and media loading restored).")
                    }
                    "logs" -> {
                        val logs = ctrl.actionLog.value.takeLast(35)
                        ToolRunOutput(
                            if (logs.isEmpty()) "Agent Browser action log is empty."
                            else logs.joinToString("\n")
                        )
                    }
                    else -> {
                        ToolRunOutput(
                            "User view mode: ${if (ctrl.agentModeEnabled.value) "Watching Agent (Live)" else "In Regular Browser"}\n" +
                                "Shared high-speed mode (⚡ Turbo): ${if (ctrl.turboModeEnabled.value) "ON (⚡ Turbo badge visible to user)" else "off"}\n" +
                                "${ctrl.listTabsSummary()}\n" +
                                "Last agent action: ${ctrl.lastActionBanner.value ?: "none"}"
                        )
                    }
                }
            }

            "terminal_list_tabs" -> {
                val tm = terminalManager ?: return ToolRunOutput("Terminal manager is unavailable", isError = true)
                val tabs = tm.tabs.value
                val aiState = tm.aiTerminalState.value
                val tailChars = int("tail_chars", 3500).coerceIn(200, 12_000)
                val activeId = tm.activeTabId.value
                val text = buildString {
                    appendLine("=== [AI EMBEDDED PHYSICAL SHELL] (alive=${aiState.isAlive}, pid=${aiState.pid ?: "?"}, cwd=${aiState.workDir}, running=${aiState.isRunningCommand}) ===")
                    if (aiState.outputLog.isNotBlank()) {
                        appendLine(aiState.outputLog.takeLast(minOf(tailChars, 2500)))
                    }
                    if (tabs.isEmpty()) {
                        appendLine("No manual terminal tabs are open yet (use terminal_open_tab to open one).")
                    } else {
                        tabs.forEach { t ->
                            val marker = if (t.id == activeId) " [ACTIVE]" else ""
                            appendLine("=== ${t.title} (id=${t.id}, mode=${t.mode.name}, alive=${t.isAlive}, dir=${t.workDir})$marker ===")
                            if (t.id == activeId || tabs.size == 1) {
                                appendLine(t.output.takeLast(tailChars))
                            }
                        }
                    }
                }
                ToolRunOutput(text.trimEnd())
            }

            "terminal_open_tab" -> {
                val tm = terminalManager ?: return ToolRunOutput("Terminal manager is unavailable", isError = true)
                val defaultMode = if (defaultEnv == WorkspaceEnvType.TERMUX_SSH) "ssh" else "local"
                val modeStr = str("mode")?.lowercase() ?: defaultMode
                val title = str("title")
                val tabId = if (modeStr == "ssh") {
                    val hint = str("server")
                    val profiles = termuxBridge.getAllProfiles()
                    val profile = if (!hint.isNullOrBlank()) {
                        profiles.firstOrNull {
                            it.id.equals(hint, ignoreCase = true) || it.name.equals(hint, ignoreCase = true)
                        } ?: return ToolRunOutput("SSH profile '$hint' was not found among configured profiles.", isError = true)
                    } else {
                        termuxBridge.activeProfile
                    }
                    tm.openSshTerminal(profile, title ?: profile.name)
                } else {
                    val dir = workspaceManager.getInternalProjectDir(internalProject)
                    tm.openLocalShell(dir, title ?: "Local Shell")
                }
                delay(350)
                ToolRunOutput("Opened background terminal tab id=$tabId (mode=$modeStr).")
            }

            "terminal_send" -> {
                val tm = terminalManager ?: return ToolRunOutput("Terminal manager is unavailable", isError = true)
                val tabId = str("tab_id") ?: tm.activeTabId.value ?: tm.tabs.value.lastOrNull()?.id
                    ?: return ToolRunOutput("No terminal tab is open — call terminal_open_tab first.", isError = true)
                val signal = str("signal")?.lowercase()
                val cmd = str("command")
                val waitMs = int("wait_ms", 650).coerceIn(100, 10_000)

                when (signal) {
                    "ctrl_c" -> tm.sendCtrlC(tabId)
                    "tab" -> tm.sendTab(tabId)
                    "esc" -> tm.sendEsc(tabId)
                    "clear" -> tm.clearOutput(tabId)
                    else -> {
                        val c = cmd ?: return ToolRunOutput("Specify either command or signal", isError = true)
                        tm.sendLine(tabId, c)
                    }
                }
                delay(waitMs.toLong())
                val updated = tm.tabs.value.firstOrNull { it.id == tabId }
                val closedErr = updated != null && !updated.isAlive && signal == null
                ToolRunOutput(
                    text = updated?.output?.takeLast(6000) ?: "Sent to $tabId",
                    isError = closedErr
                )
            }

            "chat_list_sessions" -> {
                val database = db ?: return ToolRunOutput("Database is unavailable", isError = true)
                val includeArchived = bool("include_archived", false)
                val folderFilter = str("folder_id")
                val folders = database.folderDao().getAll().associateBy { it.id }
                val allSessions = database.sessionDao().getAll()
                    .filter { includeArchived || !it.isArchived }
                    .filter { folderFilter == null || it.folderId == folderFilter }
                val out = buildString {
                    if (folders.isNotEmpty()) {
                        appendLine("Folders:")
                        folders.values.forEach { appendLine("  - ${it.name} (id=${it.id})") }
                    }
                    appendLine("Sessions (${allSessions.size}):")
                    val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                    allSessions.take(40).forEach { s ->
                        val curTag = if (s.id == sessionId) " [CURRENT]" else ""
                        val pinTag = if (s.isPinned) " [PINNED]" else ""
                        val fName = s.folderId?.let { folders[it]?.name }?.let { " | folder=$it" }.orEmpty()
                        appendLine("- id=${s.id} | \"${s.title}\" | env=${s.workspaceEnv}$fName | upd=${fmt.format(Date(s.updatedAt))}$curTag$pinTag")
                    }
                }
                ToolRunOutput(out.trimEnd())
            }

            "chat_search_history" -> {
                val database = db ?: return ToolRunOutput("Database is unavailable", isError = true)
                val query = str("query")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return ToolRunOutput("Missing required parameter: query", isError = true)
                val limit = int("limit", 15).coerceIn(1, 40)
                val sessionsById = database.sessionDao().getAll().associateBy { it.id }
                val msgs = database.messageDao().searchMessages(query, limit)
                if (msgs.isEmpty()) {
                    ToolRunOutput("No matches found in chat history for \"$query\".")
                } else {
                    val out = msgs.joinToString("\n\n") { m ->
                        val sTitle = sessionsById[m.sessionId]?.title ?: m.sessionId
                        val snippet = m.content.replace('\n', ' ').trim().take(240)
                        "[session: \"$sTitle\", session_id=${m.sessionId}] (${m.role}): $snippet"
                    }
                    ToolRunOutput(out)
                }
            }

            "chat_read_session" -> {
                val database = db ?: return ToolRunOutput("Database is unavailable", isError = true)
                val sid = str("session_id") ?: return ToolRunOutput("Missing required parameter: session_id", isError = true)
                val limit = int("limit_messages", 20).coerceIn(1, 60)
                val s = database.sessionDao().getById(sid)
                    ?: return ToolRunOutput("Chat session '$sid' not found", isError = true)
                val msgs = database.messageDao().getForSession(sid)
                    .filter { it.role != LlmRole.SYSTEM.name }
                    .takeLast(limit)
                val out = buildString {
                    appendLine("Session \"${s.title}\" (env=${s.workspaceEnv}, messages=${msgs.size}):")
                    if (s.compactedSummary.isNotBlank()) {
                        appendLine("[Summary]: ${s.compactedSummary.take(800)}\n")
                    }
                    msgs.forEach { m ->
                        val body = if (m.role == LlmRole.TOOL.name) m.content.take(400) else m.content.take(1500)
                        appendLine("${m.role}: $body")
                    }
                }
                ToolRunOutput(out.trimEnd())
            }

            "chat_manage" -> {
                val database = db ?: return ToolRunOutput("Database is unavailable", isError = true)
                val act = str("action")?.lowercase() ?: return ToolRunOutput("Missing required parameter: action", isError = true)
                val targetSid = str("session_id") ?: sessionId
                when (act) {
                    "create_folder" -> {
                        val name = str("title")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return ToolRunOutput("Specify title for the folder", isError = true)
                        val folder = ChatFolderEntity(id = UUID.randomUUID().toString(), name = name)
                        database.folderDao().upsert(folder)
                        ToolRunOutput("Folder \"$name\" created (folder_id=${folder.id}).")
                    }
                    "create_session" -> {
                        val title = str("title")?.trim()?.ifBlank { "New Chat" } ?: "New Chat"
                        val folderId = str("folder_id")
                        val cur = database.sessionDao().getById(sessionId)
                        val newSess = ChatSessionEntity(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            folderId = folderId,
                            providerId = cur?.providerId ?: settingsRepo?.activeProviderId?.value ?: "openrouter",
                            modelId = cur?.modelId ?: ""
                        )
                        database.sessionDao().upsert(newSess)
                        ToolRunOutput("Created session \"$title\" (session_id=${newSess.id}).")
                    }
                    "rename_session" -> {
                        val title = str("title")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return ToolRunOutput("Specify title", isError = true)
                        database.sessionDao().updateTitle(targetSid, title)
                        ToolRunOutput("Session $targetSid renamed to \"$title\".")
                    }
                    "assign_folder" -> {
                        val s = database.sessionDao().getById(targetSid)
                            ?: return ToolRunOutput("Session '$targetSid' not found", isError = true)
                        val fid = str("folder_id")?.takeIf { it.isNotBlank() }
                        database.sessionDao().upsert(s.copy(folderId = fid, updatedAt = System.currentTimeMillis()))
                        ToolRunOutput("Session \"${s.title}\" moved to folder ${fid ?: "unassigned"}.")
                    }
                    "pin", "unpin" -> {
                        database.sessionDao().setPinned(targetSid, act == "pin")
                        ToolRunOutput(if (act == "pin") "Session $targetSid pinned." else "Session $targetSid unpinned.")
                    }
                    else -> ToolRunOutput("Unknown chat_manage action: $act", isError = true)
                }
            }

            "chat_export_markdown" -> {
                val database = db ?: return ToolRunOutput("Database is unavailable", isError = true)
                val am = archiveManager ?: return ToolRunOutput("ArchiveManager is unavailable", isError = true)
                val sid = str("session_id") ?: sessionId
                val s = database.sessionDao().getById(sid)
                    ?: return ToolRunOutput("Session '$sid' not found", isError = true)
                val msgs = database.messageDao().getForSession(sid)
                val md = am.exportSessionToMarkdown(s, msgs)
                val safeName = (str("filename")?.trim()?.ifBlank { null } ?: "chat_${s.id.take(8)}.md")
                    .replace("..", "_")
                workspaceManager.writeFile(
                    env = WorkspaceEnvType.INTERNAL,
                    path = safeName,
                    content = md,
                    saveSnapshot = false,
                    internalProject = internalProject
                ).fold(
                    onSuccess = {
                        val attach = bool("attach_to_chat", true)
                        val atts = if (attach) {
                            listOfNotNull(
                                workspaceManager.prepareAssistantOutgoingFile(
                                    WorkspaceEnvType.INTERNAL, safeName, internalProject
                                ).getOrNull()
                            )
                        } else emptyList()
                        ToolRunOutput(
                            text = "Session \"${s.title}\" exported to $safeName (${md.length} chars).",
                            outgoingAttachments = atts
                        )
                    },
                    onFailure = { ToolRunOutput("Export error: ${it.message}", isError = true) }
                )
            }

            "context_compact" -> {
                onCompactContext(sessionId).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Failed to compact context: ${it.message}", isError = true) }
                )
            }

            "app_navigate" -> {
                val sec = str("section") ?: return ToolRunOutput("Missing required parameter: section", isError = true)
                if (onNavigateSection(sec)) {
                    ToolRunOutput("Switched active screen to tab '$sec'.")
                } else {
                    ToolRunOutput("Unknown tab '$sec'. Valid sections: chat, files, terminal, preview, settings.", isError = true)
                }
            }

            "voice_speak" -> {
                val tts = ttsManager ?: return ToolRunOutput("TTS is unavailable", isError = true)
                val v = settingsRepo?.voiceSettings?.value
                    ?: return ToolRunOutput("Voice settings are unavailable", isError = true)
                when (str("action")?.lowercase() ?: "status") {
                    "speak" -> {
                        val txt = str("text")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return ToolRunOutput("Specify text to speak", isError = true)
                        tts.speak(null, txt, v)
                        ToolRunOutput("Started TTS playback (${txt.length} chars, engine: ${v.ttsProvider.label}).")
                    }
                    "stop" -> {
                        tts.stop()
                        ToolRunOutput("Stopped TTS playback.")
                    }
                    else -> {
                        ToolRunOutput(
                            "TTS status: speaking=${tts.isSpeaking.value}, engine=${v.ttsProvider.label}, " +
                                "speed=${v.ttsSpeed}x, autoSpeak=${v.autoSpeakReplies}, " +
                                "STT=${v.sttProvider.label} (autoFallback=${v.sttAutoFallback})"
                        )
                    }
                }
            }

            else -> null
        }
    }

    private fun spec(name: String, description: String, schema: JsonObject) =
        LlmToolSpec(name = name, description = description, parametersSchema = schema)

    private fun emptyObjSchema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {})
    }

    private fun objSchema(props: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                props.forEach { (k, v) -> put(k, v) }
            })
            if (required.isNotEmpty()) {
                put("required", buildJsonArray {
                    required.forEach { add(JsonPrimitive(it)) }
                })
            }
        }

    private fun strProp(desc: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(desc))
    }

    private fun intProp(desc: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(desc))
    }

    private fun boolProp(desc: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(desc))
    }

    private fun strArrayProp(desc: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("description", JsonPrimitive(desc))
        put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
    }

    private fun envProp(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", buildJsonArray {
            add(JsonPrimitive("internal"))
            add(JsonPrimitive("device_storage"))
            add(JsonPrimitive("termux_ssh"))
            add(JsonPrimitive("saf"))
        })
        put(
            "description",
            JsonPrimitive("Target environment: 'internal' (AI Home sandbox), 'device_storage' (Phone Storage), 'termux_ssh' (Linux/SSH), or 'saf' (Android SAF folder). Defaults to the session's active environment.")
        )
    }

    companion object {
        internal fun parseStringListArg(raw: kotlinx.serialization.json.JsonElement?): List<String> = when (raw) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            is JsonPrimitive -> {
                val txt = raw.contentOrNull?.trim().orEmpty()
                if (txt.startsWith("[")) {
                    runCatching {
                        (com.forgemind.app.llm.LlmHttpFactory.json.parseToJsonElement(txt) as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    }.getOrNull() ?: emptyList()
                } else if (txt.isNotEmpty()) {
                    txt.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                } else emptyList()
            }
            else -> emptyList()
        }

        internal fun parseBrowserBatchSteps(stepsArr: JsonArray): List<BrowserBatchStep> {
            val out = mutableListOf<BrowserBatchStep>()
            for (item in stepsArr) {
                val o = item as? JsonObject ?: continue
                val act = (o["action"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (act.isEmpty()) continue
                val idx = (o["index"] as? JsonPrimitive)?.intOrNull
                    ?: (o["element_index"] as? JsonPrimitive)?.intOrNull
                val fieldsObj = o["fields"] as? JsonObject
                val fieldsMap = fieldsObj?.entries?.associate { (k, v) ->
                    k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString())
                }
                val timeout = (o["timeout_ms"] as? JsonPrimitive)?.longOrNull
                    ?: (o["wait_ms"] as? JsonPrimitive)?.longOrNull
                out += BrowserBatchStep(
                    action = act,
                    url = (o["url"] as? JsonPrimitive)?.contentOrNull,
                    index = idx,
                    selector = (o["selector"] as? JsonPrimitive)?.contentOrNull,
                    text = (o["text"] as? JsonPrimitive)?.contentOrNull
                        ?: (o["label"] as? JsonPrimitive)?.contentOrNull,
                    value = (o["value"] as? JsonPrimitive)?.contentOrNull,
                    script = (o["script"] as? JsonPrimitive)?.contentOrNull,
                    fields = fieldsMap,
                    submit = (o["submit"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    timeoutMs = timeout,
                    filename = (o["filename"] as? JsonPrimitive)?.contentOrNull,
                    attachToChat = (o["attach_to_chat"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    tabId = (o["tab_id"] as? JsonPrimitive)?.contentOrNull,
                    newTab = (o["new_tab"] as? JsonPrimitive)?.booleanOrNull ?: false
                )
            }
            return out
        }
    }
}
