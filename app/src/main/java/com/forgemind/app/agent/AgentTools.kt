package com.forgemind.app.agent

import com.forgemind.app.bridge.TerminalSessionManager
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.archive.ChatArchiveManager
import com.forgemind.app.data.db.ForgeMindDatabase
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.McpTransport
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.llm.LlmToolSpec
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.voice.TtsManager
import com.forgemind.app.web.AgentBrowserController
import com.forgemind.app.web.LocalPreviewServer
import com.forgemind.app.web.WebScraperEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ToolRunOutput(
    val text: String,
    val isError: Boolean = false,
    val diffText: String? = null,
    val snapshotId: String? = null,
    val outgoingAttachments: List<ChatAttachment> = emptyList(),
    val mcpServerName: String? = null,
    val refreshToolSchemas: Boolean = false
)

class AgentToolRegistry(
    private val workspaceManager: WorkspaceManager,
    private val termuxBridge: TermuxBridge,
    private val mcpManager: McpClientManager,
    private val webScraper: WebScraperEngine,
    private val browserController: AgentBrowserController? = null,
    private val localPreviewServer: LocalPreviewServer? = null,
    private val terminalManager: TerminalSessionManager? = null,
    private val archiveManager: ChatArchiveManager? = null,
    private val db: ForgeMindDatabase? = null,
    private val settingsRepo: SettingsRepository? = null,
    private val ttsManager: TtsManager? = null,
    private val onOpenBrowserUrl: (String) -> Unit = {},
    private val onNavigateSection: (String) -> Boolean = { false },
    private val onOpenEditorTab: (WorkspaceEnvType, String, String, String?) -> String = { _, path, _, _ -> path },
    private val onListEditorTabs: () -> List<EditorTabSnapshot> = { emptyList() },
    private val onPreviewPortChanged: (Int) -> Unit = {},
    private val onCompactContext: suspend (String) -> Result<String> = { Result.failure(IllegalStateException("Unavailable")) }
) {
    private val dynamicMcpLookup = ConcurrentHashMap<String, Pair<String, String>>() // exposedName -> (serverId, realToolName)

    private val appLeverTools = AppLeverTools(
        workspaceManager = workspaceManager,
        browserController = browserController,
        localPreviewServer = localPreviewServer,
        terminalManager = terminalManager,
        archiveManager = archiveManager,
        db = db,
        settingsRepo = settingsRepo,
        ttsManager = ttsManager,
        termuxBridge = termuxBridge,
        onNavigateSection = onNavigateSection,
        onOpenEditorTab = onOpenEditorTab,
        onListEditorTabs = onListEditorTabs,
        onPreviewPortChanged = onPreviewPortChanged,
        onCompactContext = onCompactContext
    )

    suspend fun describeConnectedEnvironments(
        internalProject: String = "default",
        defaultEnv: WorkspaceEnvType = WorkspaceEnvType.INTERNAL
    ): String {
        val internalDir = workspaceManager.getInternalProjectDir(internalProject).absolutePath
        val safUriStr = workspaceManager.safRootUri?.toString()
        val safDesc = if (safUriStr.isNullOrBlank()) {
            "not selected (user can pick a folder in 'AI Files -> Android Folder (SAF)')"
        } else {
            "connected -> ${WorkspaceManager.formatSafTreeLabel(safUriStr)}"
        }
        val profiles = termuxBridge.getAllProfiles()
        val sshDesc = if (profiles.isEmpty()) {
            "no SSH profiles configured"
        } else {
            profiles.joinToString("\n  ") { p ->
                val state = when {
                    !p.enabled -> "disabled in Settings"
                    p.host.isBlank() || p.username.isBlank() -> "enabled, but host/username not filled"
                    termuxBridge.isSshConnected(p.id) -> "enabled, currently connected"
                    else -> "enabled, ready to connect"
                }
                val defTag = if (p.isDefault) " [default]" else ""
                val wd = p.defaultWorkDir.ifBlank { "~" }
                "- server='${p.id}' (${p.name}): ${p.username.ifBlank { "?" }}@${p.host.ifBlank { "?" }}:${p.port}, workDir=$wd [$state]$defTag"
            }
        }
        val termuxDesc = when {
            termuxBridge.isTermuxInstalled() && termuxBridge.hasTermuxPermission() -> "Termux installed, RUN_COMMAND granted"
            termuxBridge.isTermuxInstalled() -> "Termux installed (operating via SSH)"
            else -> "Termux not detected"
        }
        val mcpAll = mcpManager.getAllServers()
        val mcpEnabled = mcpAll.filter { it.enabled }
        val mcpDisabled = mcpAll.filter { !it.enabled }
        val toolsMap = if (mcpEnabled.isNotEmpty()) mcpManager.getEnabledToolsMap() else emptyMap()
        val mcpInstructionBlocks = mutableListOf<Pair<String, String>>()
        val mcpDesc = if (mcpAll.isEmpty()) {
            "no external MCP servers added (configured in Settings -> MCP)"
        } else {
            val enabledLines = mcpEnabled.joinToString("; ") { s ->
                val list = toolsMap[s].orEmpty()
                val insight = mcpManager.analyzeServerCatalog(s, list)
                val instr = mcpManager.resolveServerInstructions(s)
                if (instr.isNotBlank()) {
                    mcpInstructionBlocks += s.name to instr
                }
                val endpointPart = s.endpoint.trim().takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
                val instrTag = if (instr.isNotBlank()) ", instructions=${instr.length} chars" else ""
                "${s.name} [${s.transport}$endpointPart, ${insight.summaryLine}$instrTag]"
            }
            val disabledNote = if (mcpDisabled.isNotEmpty()) {
                val names = mcpDisabled.joinToString(", ") { s ->
                    val ep = s.endpoint.trim()
                    if (ep.isNotBlank()) "${s.name} ($ep)" else s.name
                }
                " | manually disabled (${mcpDisabled.size}): $names (do not treat as broken or enable uninvited)"
            } else ""
            (enabledLines.ifBlank { "no active MCP servers right now" }) + disabledNote
        }
        val aiShellState = terminalManager?.embeddedAiShell?.ensureStarted()
        val aiShellDesc = if (aiShellState != null) {
            "active (PID=${aiShellState.pid ?: "?"}, cwd=${aiShellState.workDir}, commands=${aiShellState.commandCount})"
        } else {
            "local /system/bin/sh in $internalDir"
        }
        return buildString {
            appendLine("=== CONNECTED ENVIRONMENTS & WORKSPACE MAP ===")
            appendLine("• Default environment in this session: '${defaultEnv.id}' (${defaultEnv.label}).")
            appendLine("• 'internal' (AI Home): $internalDir")
            appendLine("  Your private sandbox folder inside the Android app. Store generated scripts/files/notes here; the user sees it in 'AI Files -> AI Home' and via '+' in chat. Use send_file_to_chat to share a file directly in chat.")
            appendLine("  • Embedded physical AI shell (persistent /system/bin/sh inside the app): $aiShellDesc. Preserves cd/export state and ALWAYS works — even if SSH/Termux is disconnected or drops (live output is visible in 'Terminal -> Watch Agent (Live)').")
            appendLine("• 'saf' (Android Folder): $safDesc.")
            appendLine("• 'termux_ssh' (Linux/SSH, $termuxDesc; pass server='<id>'):")
            appendLine("  $sshDesc")
            appendLine("• MCP servers: $mcpDesc.")
            if (mcpInstructionBlocks.isNotEmpty()) {
                appendLine("• Active MCP server instructions (from initialize.instructions):")
                var remainingBudget = 12_000
                mcpInstructionBlocks.forEach { (serverName, rawInstructions) ->
                    if (remainingBudget <= 0) return@forEach
                    val perServerCap = minOf(6_000, remainingBudget)
                    val trimmed = rawInstructions.trim()
                    val body = if (trimmed.length > perServerCap) {
                        trimmed.take(perServerCap) + "\n... [truncated to $perServerCap of ${trimmed.length} chars]"
                    } else {
                        trimmed
                    }
                    remainingBudget -= body.length.coerceAtMost(remainingBudget)
                    appendLine("  --- [$serverName] ---")
                    body.lineSequence().forEach { line ->
                        appendLine("  $line")
                    }
                }
            }
            val browserUrl = browserController?.currentUrl?.value.orEmpty()
            val previewPort = localPreviewServer?.port ?: 0
            append("• Built-in levers (passive background, never disturb user UI): background Agent Browser (${if (browserUrl.isBlank()) "ready" else browserUrl}), HTTP preview (${if (previewPort > 0) "http://127.0.0.1:$previewPort/" else "off"}), code editor buffer, background terminal, snapshots, chat history, context compaction, and TTS.")
        }.trimEnd()
    }

    suspend fun buildToolSpecs(agentSettings: AgentSettings): List<LlmToolSpec> {
        if (!agentSettings.enableTools) return emptyList()

        val specs = mutableListOf<LlmToolSpec>()
        dynamicMcpLookup.clear()

        specs += builtInSpecs()
        specs += appLeverTools.specs()

        // Inject all tools from currently enabled MCP servers directly into the model schema
        val enabledMap = mcpManager.getEnabledToolsMap()
        val usedNames = specs.map { it.name }.toMutableSet()
        for ((srv, tools) in enabledMap) {
            for (t in tools) {
                val baseExposed = McpClientManager.buildExposedToolName(srv.name, t.name)
                var exposed = baseExposed
                if (exposed in usedNames) {
                    val serverScoped = "${baseExposed.take(58)}_${srv.id.take(4)}"
                    exposed = serverScoped
                    var counter = 2
                    while (exposed in usedNames) {
                        val suffix = "_$counter"
                        exposed = "${baseExposed.take(64 - suffix.length)}$suffix"
                        counter++
                    }
                }
                usedNames += exposed
                dynamicMcpLookup[exposed] = srv.id to t.name

                val schemaObj = runCatching {
                    LlmHttpFactory.json.parseToJsonElement(t.inputSchemaJson).jsonObject
                }.getOrElse { emptyObjSchema() }

                specs += LlmToolSpec(
                    name = exposed,
                    description = "[MCP: ${srv.name}] ${t.description.ifBlank { t.name }}",
                    parametersSchema = ensureObjectSchema(schemaObj)
                )
            }
        }
        return specs
    }

    fun isDangerousCommand(toolName: String, argsJson: String): Boolean {
        if (toolName in setOf("run_command", "run_command_async", "terminal_send")) {
            val parsedArgs = runCatching {
                LlmHttpFactory.json.parseToJsonElement(argsJson).jsonObject
            }.getOrNull()
            val cmd = (parsedArgs?.get("command") as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
            if (cmd.isNotBlank() && DANGEROUS_CMD_REGEX.containsMatchIn(cmd)) {
                return true
            }
        }
        return false
    }

    fun isDestructiveOrCommandTool(toolName: String, argsJson: String): Boolean {
        if (toolName in setOf("file_delete", "kill_command")) return true
        val parsedArgs = runCatching {
            LlmHttpFactory.json.parseToJsonElement(argsJson).jsonObject
        }.getOrNull()
        if (toolName == "mcp_connect") {
            val transport = (parsedArgs?.get("transport") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (transport.equals("SSH_STDIO", ignoreCase = true)) return true
        }
        return isDangerousCommand(toolName, argsJson)
    }

    fun requiresApproval(toolName: String, argsJson: String, settings: AgentSettings): Boolean {
        val normalized = toolName.trim()

        // 1. User's blocklist ALWAYS requires manual approval (even in full autopilot)
        if (settings.blockedTools.any { it.equals(normalized, ignoreCase = true) }) {
            return true
        }

        // 2. Dangerous shell commands (rm -rf, mkfs, forkbomb, etc.) require confirmation
        // unless the user explicitly enabled full autopilot (autoApproveAll).
        val hasDangerousRegex = isDangerousCommand(normalized, argsJson)
        if (hasDangerousRegex && !settings.autoApproveAll) {
            return true
        }

        // 3. Full autopilot: everything is allowed unless the tool is blocklisted
        if (settings.autoApproveAll) {
            return false
        }

        // 4. User's allowlist permits the tool (except dangerous shell regex above)
        if (settings.allowedTools.any { it.equals(normalized, ignoreCase = true) }) {
            return false
        }

        // 5. Granular evaluation by functional category
        return when {
            normalized == "file_delete" -> {
                !settings.autoApproveFileDelete
            }

            normalized in setOf("run_command", "run_command_async", "terminal_send", "kill_command") -> {
                !settings.autoApproveShell
            }

            normalized in setOf(
                "file_write", "file_edit", "file_move", "create_folder",
                "transfer_file", "archive_create", "archive_extract", "snapshot_restore"
            ) -> {
                !(settings.autoApproveSafe && settings.autoApproveFileWrite)
            }

            normalized == "browser_agent_eval_js" -> {
                !settings.autoApproveBrowserJs
            }

            normalized.startsWith("browser_agent_") || normalized == "browser_open" -> {
                !(settings.autoApproveSafe && settings.autoApproveBrowser)
            }

            normalized in setOf("mcp_connect", "mcp_call_tool") || dynamicMcpLookup.containsKey(normalized) -> {
                val parsedArgs = runCatching {
                    LlmHttpFactory.json.parseToJsonElement(argsJson).jsonObject
                }.getOrNull()
                val transport = (parsedArgs?.get("transport") as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (transport.equals("SSH_STDIO", ignoreCase = true)) {
                    true
                } else {
                    !settings.autoApproveMcp
                }
            }

            normalized in setOf(
                "chat_manage", "chat_export_markdown", "voice_speak",
                "preview_server_control", "ui_navigate",
                "terminal_open_tab", "terminal_list_tabs", "terminal_close_tab",
                "editor_open_file", "editor_list_tabs"
            ) -> {
                !(settings.autoApproveSafe && settings.autoApproveChatManage)
            }

            else -> {
                !(settings.autoApproveRead && settings.autoApproveSafe)
            }
        }
    }

    suspend fun execute(
        toolName: String,
        argsJson: String,
        defaultEnv: WorkspaceEnvType,
        internalProject: String,
        sessionId: String,
        messageId: String,
        agentSettings: AgentSettings,
        searchSettings: SearchSettings
    ): ToolRunOutput {
        val rawArgsText = argsJson.ifBlank { "{}" }
        val args = runCatching {
            val el = LlmHttpFactory.json.parseToJsonElement(rawArgsText)
            el as? JsonObject ?: error("Arguments must be a JSON object")
        }.getOrElse { err ->
            return ToolRunOutput(
                "Failed to parse JSON arguments for '$toolName': ${err.message}. Pass a valid JSON object.",
                isError = true
            )
        }

        fun str(key: String): String? = when (val v = args[key]) {
            is JsonPrimitive -> v.contentOrNull
            is JsonObject, is JsonArray -> v.toString()
            else -> null
        }
        fun int(key: String, def: Int): Int = (args[key] as? JsonPrimitive)?.intOrNull ?: def
        fun bool(key: String, def: Boolean): Boolean = (args[key] as? JsonPrimitive)?.booleanOrNull ?: def
        fun resolveEnv(): WorkspaceEnvType = str("target_env")?.let { WorkspaceEnvType.fromId(it) } ?: defaultEnv

        return try {
            // Dynamic MCP tool call
            dynamicMcpLookup[toolName]?.let { (serverId, realToolName) ->
                val res = mcpManager.callTool(serverId, realToolName, argsJson)
                val atts = res.savedFilePaths.mapNotNull { path ->
                    val f = File(path)
                    if (f.exists()) workspaceManager.buildAttachmentFromLocalFile(f, isFromAssistant = true) else null
                }
                return ToolRunOutput(
                    text = res.text,
                    isError = res.isError,
                    outgoingAttachments = atts,
                    mcpServerName = mcpManager.getAllServers().firstOrNull { it.id == serverId }?.name
                )
            }

            appLeverTools.executeIfHandled(toolName, args, defaultEnv, internalProject, sessionId)?.let {
                return it
            }

            executeBuiltIn(
                toolName = toolName,
                args = args,
                str = ::str,
                int = ::int,
                bool = ::bool,
                resolveEnv = ::resolveEnv,
                defaultEnv = defaultEnv,
                internalProject = internalProject,
                sessionId = sessionId,
                messageId = messageId,
                agentSettings = agentSettings,
                searchSettings = searchSettings
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ToolRunOutput(
                "Internal error in tool '$toolName': ${t.message ?: t::class.java.simpleName}",
                isError = true
            )
        }
    }

    private suspend fun executeBuiltIn(
        toolName: String,
        @Suppress("UNUSED_PARAMETER") args: JsonObject,
        str: (String) -> String?,
        int: (String, Int) -> Int,
        bool: (String, Boolean) -> Boolean,
        resolveEnv: () -> WorkspaceEnvType,
        @Suppress("UNUSED_PARAMETER") defaultEnv: WorkspaceEnvType,
        internalProject: String,
        sessionId: String,
        messageId: String,
        agentSettings: AgentSettings,
        searchSettings: SearchSettings
    ): ToolRunOutput {

        return when (toolName) {
            "check_environment_status" -> {
                val mcpList = mcpManager.getAllServers()
                val sshList = termuxBridge.getAllProfiles()
                val text = buildString {
                    appendLine("Unified workspace (all configured environments are ready immediately):")
                    appendLine("- AI Home sandbox (`internal`): ${workspaceManager.getInternalProjectDir(internalProject).absolutePath} [Ready]")
                    appendLine("- Termux installed: ${termuxBridge.isTermuxInstalled()}, RUN_COMMAND: ${termuxBridge.hasTermuxPermission()}")
                    appendLine("- SSH servers (${sshList.size}, enabled: ${sshList.count { it.enabled }}):")
                    sshList.forEach { p ->
                        val def = if (p.isDefault) " [default]" else ""
                        val cfTag = if (p.useCloudflareAccess) ", tunnel=CloudflareAccess" else ""
                        appendLine("  * ${p.name} (id=${p.id}): ${p.username.ifBlank { "?" }}@${p.host.ifBlank { "?" }}:${p.port}$cfTag, enabled=${p.enabled}, connected=${termuxBridge.isSshConnected(p.id)}$def")
                    }
                    appendLine("- Android SAF folder: ${workspaceManager.safRootUri?.let { WorkspaceManager.formatSafTreeLabel(it.toString()) } ?: "not selected"}")
                    appendLine("- MCP servers: ${mcpList.size} (enabled: ${mcpList.count { it.enabled }})")
                    mcpList.forEach {
                        appendLine("  * ${it.name} [${it.transport}] -> ${it.endpoint} (enabled=${it.enabled})")
                    }
                }
                ToolRunOutput(text)
            }

            "list_files" -> {
                val env = resolveEnv()
                val path = str("path").orEmpty()
                val server = str("server")
                workspaceManager.listFiles(env, path, internalProject, sshServer = server).fold(
                    onSuccess = { items ->
                        if (items.isEmpty()) ToolRunOutput("Directory is empty (${env.id}:$path).")
                        else {
                            val formatted = items.joinToString("\n") {
                                val icon = if (it.isDirectory) "[DIR]" else "[${it.sizeBytes}B]"
                                "$icon ${it.path}"
                            }
                            ToolRunOutput(formatted)
                        }
                    },
                    onFailure = { ToolRunOutput("list_files error: ${it.message}", isError = true) }
                )
            }

            "search_files" -> {
                val env = resolveEnv()
                val q = str("query").orEmpty()
                val server = str("server")
                workspaceManager.searchFiles(env, q, internalProject, sshServer = server).fold(
                    onSuccess = { list ->
                        ToolRunOutput(if (list.isEmpty()) "No matching files found." else list.joinToString("\n"))
                    },
                    onFailure = { ToolRunOutput("search_files error: ${it.message}", isError = true) }
                )
            }

            "search_codebase" -> {
                val env = resolveEnv()
                val q = str("query") ?: return ToolRunOutput("Missing required parameter: query", isError = true)
                val server = str("server")
                workspaceManager.searchCodebase(env, q, internalProject, sshServer = server).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("search_codebase error: ${it.message}", isError = true) }
                )
            }

            "file_read" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Missing required parameter: path", isError = true)
                val off = int("offset_line", 1)
                val lim = int("limit_lines", 300)
                val server = str("server")
                workspaceManager.readFile(env, path, off, lim, internalProject, sshServer = server).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("file_read error: ${it.message}", isError = true) }
                )
            }

            "file_write" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Missing required parameter: path", isError = true)
                val content = str("content") ?: return ToolRunOutput("Missing required parameter: content", isError = true)
                val server = str("server")
                workspaceManager.writeFile(
                    env = env,
                    path = path,
                    content = content,
                    sessionId = sessionId,
                    messageId = messageId,
                    saveSnapshot = agentSettings.autoSnapshotBeforeEdit,
                    internalProject = internalProject,
                    sshServer = server
                ).fold(
                    onSuccess = { res ->
                        ToolRunOutput(res.message, diffText = res.diffText, snapshotId = res.snapshotId)
                    },
                    onFailure = { ToolRunOutput("file_write error: ${it.message}", isError = true) }
                )
            }

            "file_edit" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Missing required parameter: path", isError = true)
                val oldS = str("old_string") ?: return ToolRunOutput("Missing required parameter: old_string", isError = true)
                val newS = str("new_string") ?: return ToolRunOutput("Missing required parameter: new_string", isError = true)
                val server = str("server")
                workspaceManager.editFile(
                    env = env,
                    path = path,
                    oldString = oldS,
                    newString = newS,
                    sessionId = sessionId,
                    messageId = messageId,
                    saveSnapshot = agentSettings.autoSnapshotBeforeEdit,
                    internalProject = internalProject,
                    sshServer = server
                ).fold(
                    onSuccess = { res ->
                        ToolRunOutput(res.message, diffText = res.diffText, snapshotId = res.snapshotId)
                    },
                    onFailure = { ToolRunOutput("file_edit error: ${it.message}", isError = true) }
                )
            }

            "file_delete" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Missing required parameter: path", isError = true)
                val server = str("server")
                workspaceManager.deleteFile(env, path, internalProject, sshServer = server).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("file_delete error: ${it.message}", isError = true) }
                )
            }

            "create_folder" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Missing required parameter: path", isError = true)
                val server = str("server")
                workspaceManager.createFolder(env, path, internalProject, sshServer = server).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("create_folder error: ${it.message}", isError = true) }
                )
            }

            "archive_create" -> {
                val source = str("source_path") ?: return ToolRunOutput("Missing required parameter: source_path", isError = true)
                workspaceManager.createZipArchive(source, str("archive_path").orEmpty(), internalProject).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("archive_create error: ${it.message}", isError = true) }
                )
            }

            "archive_extract" -> {
                val archive = str("archive_path") ?: return ToolRunOutput("Missing required parameter: archive_path", isError = true)
                workspaceManager.extractZipArchive(archive, str("target_dir").orEmpty(), internalProject).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("archive_extract error: ${it.message}", isError = true) }
                )
            }

            "transfer_file" -> {
                val srcEnv = WorkspaceEnvType.fromId(str("source_env"))
                val srcPath = str("source_path") ?: return ToolRunOutput("Missing required parameter: source_path", isError = true)
                val dstEnv = WorkspaceEnvType.fromId(str("target_env"))
                val dstPath = str("target_path") ?: return ToolRunOutput("Missing required parameter: target_path", isError = true)
                val srcServer = str("source_server") ?: str("server")
                val dstServer = str("target_server") ?: str("server")
                workspaceManager.transferFile(
                    sourceEnv = srcEnv,
                    sourcePath = srcPath,
                    targetEnv = dstEnv,
                    targetPath = dstPath,
                    internalProject = internalProject,
                    sourceSshServer = srcServer,
                    targetSshServer = dstServer
                ).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("transfer_file error: ${it.message}", isError = true) }
                )
            }

            "send_file_to_chat" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Missing required parameter: path", isError = true)
                val caption = str("caption").orEmpty()
                val server = str("server")
                workspaceManager.prepareAssistantOutgoingFile(env, path, internalProject, sshServer = server).fold(
                    onSuccess = { att ->
                        ToolRunOutput(
                            text = "File '${att.name}' (${att.sizeBytes} bytes) attached to chat. $caption".trim(),
                            outgoingAttachments = listOf(att)
                        )
                    },
                    onFailure = { ToolRunOutput("send_file_to_chat error: ${it.message}", isError = true) }
                )
            }

            "run_command" -> {
                val cmd = str("command") ?: return ToolRunOutput("Missing required parameter: command", isError = true)
                val env = resolveEnv()
                val timeout = int("timeout_seconds", 35).coerceIn(5, 180)
                val server = str("server")
                val wdRaw = str("workdir")?.trim()?.takeIf { it.isNotEmpty() }
                val defaultInternalDir = workspaceManager.getInternalProjectDir(internalProject)
                val customLocalDir = wdRaw?.let { File(it) }
                if (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank()) {
                    val shell = terminalManager?.embeddedAiShell
                    if (shell != null) {
                        val res = shell.execute(
                            command = cmd,
                            workDir = customLocalDir,
                            timeoutSeconds = timeout
                        )
                        ToolRunOutput(
                            text = buildString {
                                appendLine("Env: Embedded Physical Shell (/system/bin/sh)")
                                if (res.workDir.isNotBlank()) appendLine("Workdir: ${res.workDir}")
                                appendLine("Exit Code: ${res.exitCode}" + if (res.timedOut) " (TIMEOUT)" else "")
                                if (res.stdout.isNotBlank()) appendLine("--- stdout/stderr ---\n${res.stdout.take(16_000)}")
                            }.trimEnd(),
                            isError = res.exitCode != 0
                        )
                    } else {
                        val res = runLocalShellCommand(cmd, customLocalDir ?: defaultInternalDir, timeout)
                        ToolRunOutput(
                            text = buildString {
                                appendLine("Exit Code: ${res.first}")
                                if (res.second.isNotBlank()) appendLine("--- stdout/stderr ---\n${res.second.take(16_000)}")
                            },
                            isError = res.first != 0
                        )
                    }
                } else {
                    val res = termuxBridge.runCommand(cmd, wdRaw, timeout, serverHint = server)
                    val allowFallback = bool("fallback_to_embedded", true)
                    if (res.error != null && allowFallback && termuxBridge.isLocalSshTarget(server)) {
                        val shell = terminalManager?.embeddedAiShell
                        if (shell != null) {
                            val fb = shell.execute(
                                command = cmd,
                                workDir = customLocalDir ?: defaultInternalDir,
                                timeoutSeconds = timeout,
                                sourceTag = "SSH Fallback -> Embedded Shell",
                                usedSshFallback = true
                            )
                            val outText = buildString {
                                appendLine("[SSH unavailable (${res.error}) -> executed via embedded physical app shell]")
                                if (fb.workDir.isNotBlank()) appendLine("Workdir: ${fb.workDir}")
                                appendLine("Exit Code: ${fb.exitCode}" + if (fb.timedOut) " (TIMEOUT)" else "")
                                if (fb.stdout.isNotBlank()) appendLine("--- stdout/stderr ---\n${fb.stdout.take(16_000)}")
                            }.trimEnd()
                            return ToolRunOutput(outText, isError = fb.exitCode != 0)
                        }
                    }
                    val combinedOut = buildString {
                        if (res.stdout.isNotBlank()) append(res.stdout)
                        if (res.stderr.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(res.stderr)
                        }
                        if (!res.error.isNullOrBlank()) {
                            if (isNotEmpty()) append('\n')
                            append("Error: ${res.error}")
                        }
                    }
                    terminalManager?.embeddedAiShell?.recordExternalExecution(
                        sourceLabel = "SSH: ${server ?: termuxBridge.activeProfile.name}",
                        workDir = wdRaw,
                        command = cmd,
                        output = combinedOut.take(12_000),
                        exitCode = res.exitCode
                    )
                    val outText = buildString {
                        appendLine("Exit Code: ${res.exitCode}" + if (res.timedOut) " (TIMEOUT)" else "")
                        if (!res.error.isNullOrBlank()) {
                            appendLine("Error: ${res.error}")
                            appendLine("Tip: the embedded physical app shell (`target_env='internal'`) is active and works independently of SSH.")
                        }
                        if (res.stdout.isNotBlank()) appendLine("--- stdout ---\n${res.stdout.take(16_000)}")
                        if (res.stderr.isNotBlank()) appendLine("--- stderr ---\n${res.stderr.take(8_000)}")
                    }
                    ToolRunOutput(outText, isError = res.error != null || res.exitCode != 0)
                }
            }

            "run_command_async" -> {
                val cmd = str("command") ?: return ToolRunOutput("Missing required parameter: command", isError = true)
                val env = resolveEnv()
                val wd = str("workdir")
                val server = str("server")
                val shell = terminalManager?.embeddedAiShell
                val defaultInternalDir = workspaceManager.getInternalProjectDir(internalProject)
                if (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank() && shell != null) {
                    shell.startAsync(cmd, wd?.let { File(it) } ?: defaultInternalDir).fold(
                        onSuccess = { started ->
                            ToolRunOutput(
                                "Command started in background of the embedded physical shell.\n" +
                                    "PID: ${started.id}\nOS PID: ${started.pid}\nLog file: ${started.logFile.absolutePath}\n" +
                                    "Call check_command_status(pid='${started.id}', log_file='${started.logFile.absolutePath}', target_env='internal') to inspect output."
                            )
                        },
                        onFailure = { ToolRunOutput("run_command_async (internal) error: ${it.message}", isError = true) }
                    )
                } else {
                    termuxBridge.runCommandAsync(cmd, wd, serverHint = server).fold(
                        onSuccess = { started ->
                            val srvArg = server?.let { ", server='$it'" }.orEmpty()
                            ToolRunOutput(
                                "Command started in background.\nPID: ${started.pid}\nLog file: ${started.logFile}\n" +
                                    "Call check_command_status(pid='${started.pid}', log_file='${started.logFile}'$srvArg) to inspect output."
                            )
                        },
                        onFailure = { err ->
                            if (bool("fallback_to_embedded", true) && termuxBridge.isLocalSshTarget(server) && shell != null) {
                                shell.startAsync(cmd, wd?.let { File(it) } ?: defaultInternalDir).fold(
                                    onSuccess = { started ->
                                        ToolRunOutput(
                                            "[SSH unavailable (${err.message}) -> started in background of embedded physical shell]\n" +
                                                "PID: ${started.id}\nLog file: ${started.logFile.absolutePath}\n" +
                                                "Call check_command_status(pid='${started.id}', log_file='${started.logFile.absolutePath}', target_env='internal')."
                                        )
                                    },
                                    onFailure = { fbErr ->
                                        ToolRunOutput("run_command_async error: ${err.message}; fallback error: ${fbErr.message}", isError = true)
                                    }
                                )
                            } else {
                                ToolRunOutput("run_command_async error: ${err.message}", isError = true)
                            }
                        }
                    )
                }
            }

            "check_command_status" -> {
                val pid = str("pid") ?: return ToolRunOutput("Missing required parameter: pid", isError = true)
                val logFile = str("log_file") ?: return ToolRunOutput("Missing required parameter: log_file", isError = true)
                val lines = int("lines", 60)
                val server = str("server")
                val env = resolveEnv()
                val shell = terminalManager?.embeddedAiShell
                if ((pid.startsWith("local_") || (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank())) && shell != null) {
                    ToolRunOutput(shell.checkAsyncStatus(pid, logFile, lines))
                } else {
                    val statusText = termuxBridge.checkCommandStatus(pid, logFile, lines, serverHint = server)
                    ToolRunOutput(statusText, isError = statusText.startsWith("Error", ignoreCase = true))
                }
            }

            "kill_command" -> {
                val pid = str("pid") ?: return ToolRunOutput("Missing required parameter: pid", isError = true)
                val server = str("server")
                val env = resolveEnv()
                val shell = terminalManager?.embeddedAiShell
                if ((pid.startsWith("local_") || (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank())) && shell != null) {
                    shell.killAsync(pid).fold(
                        onSuccess = { ToolRunOutput(it) },
                        onFailure = { ToolRunOutput("kill_command error: ${it.message}", isError = true) }
                    )
                } else {
                    val res = termuxBridge.killCommand(pid, serverHint = server)
                    ToolRunOutput(res.stdout.ifBlank { res.stderr.ifBlank { res.error ?: "Terminated" } }, isError = res.error != null)
                }
            }

            "web_search" -> {
                val query = str("query") ?: return ToolRunOutput("Missing required parameter: query", isError = true)
                val count = int("count", 8).coerceIn(1, 20)
                webScraper.search(query, count, searchSettings).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("web_search error: ${it.message}", isError = true) }
                )
            }

            "web_scrape" -> {
                val url = str("url") ?: return ToolRunOutput("Missing required parameter: url", isError = true)
                val mode = str("mode") ?: "markdown"
                val sel = str("css_selector")
                val renderJs = bool("render_js", false)
                val headers = str("headers_json") ?: "{}"
                val maxChars = int("max_chars", 24_000).coerceIn(1000, 80_000)
                webScraper.scrape(url, mode, sel, renderJs, headers, maxChars, searchSettings).fold(
                    onSuccess = { out ->
                        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                        onOpenBrowserUrl(normalized)
                        ToolRunOutput(out)
                    },
                    onFailure = { ToolRunOutput("web_scrape error: ${it.message}", isError = true) }
                )
            }

            "browser_open" -> {
                val raw = str("url")?.trim() ?: return ToolRunOutput("Missing required parameter: url", isError = true)
                val clean = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                onOpenBrowserUrl(clean)
                ToolRunOutput("Page opened in the background Agent Browser: $clean")
            }

            "mcp_list_servers" -> {
                val all = mcpManager.getAllServers()
                if (all.isEmpty()) {
                    ToolRunOutput("No MCP servers have been added yet. You can connect an external MCP server via mcp_connect.")
                } else {
                    val out = buildString {
                        for (srv in all) {
                            val tools = mcpManager.decodeTools(srv.cachedToolsJson)
                            appendLine("- ${srv.name} (id=${srv.id}, enabled=${srv.enabled}, transport=${srv.transport})")
                            appendLine("  endpoint: ${srv.endpoint}")
                            if (!srv.lastError.isNullOrBlank()) appendLine("  lastError: ${srv.lastError}")
                            appendLine("  tools (${tools.size}): ${tools.joinToString { it.name }}")
                        }
                    }
                    ToolRunOutput(out)
                }
            }

            "mcp_connect" -> {
                val name = str("name") ?: "remote_mcp"
                val url = str("url") ?: return ToolRunOutput("Missing required parameter: url for mcp_connect", isError = true)
                val tr = runCatching { McpTransport.valueOf((str("transport") ?: "STREAMABLE_HTTP").uppercase()) }
                    .getOrDefault(McpTransport.STREAMABLE_HTTP)
                val hdrs = str("headers_json") ?: "{}"
                mcpManager.connectDynamicServer(name, url, tr, hdrs).fold(
                    onSuccess = { (srv, tools) ->
                        ToolRunOutput(
                            text = buildString {
                                appendLine("Successfully connected MCP server '${srv.name}' (${srv.endpoint})!")
                                appendLine("Discovered tools: ${tools.size}")
                                tools.forEach { t ->
                                    val exposed = McpClientManager.buildExposedToolName(srv.name, t.name)
                                    appendLine("  - $exposed (original: ${t.name}): ${t.description.take(140)}")
                                }
                            },
                            mcpServerName = srv.name,
                            refreshToolSchemas = true
                        )
                    },
                    onFailure = { ToolRunOutput("Failed to connect to MCP: ${it.message}", isError = true) }
                )
            }

            "mcp_call_tool" -> {
                val srvQuery = str("server") ?: return ToolRunOutput("Missing required parameter: server", isError = true)
                val tName = str("tool_name") ?: return ToolRunOutput("Missing required parameter: tool_name", isError = true)
                val aJson = str("arguments_json") ?: "{}"
                val target = mcpManager.getAllServers().firstOrNull {
                    it.id == srvQuery || it.name.equals(srvQuery, ignoreCase = true)
                } ?: return ToolRunOutput("MCP server '$srvQuery' not found", isError = true)
                val res = mcpManager.callTool(target.id, tName, aJson)
                val imgs = res.savedFilePaths.mapNotNull { path ->
                    val f = File(path)
                    if (f.exists()) workspaceManager.buildAttachmentFromLocalFile(f, isFromAssistant = true) else null
                }
                val note = if (imgs.isNotEmpty()) "\n[Attachments added to chat: ${imgs.size}]" else ""
                ToolRunOutput(
                    res.text + note,
                    isError = res.isError,
                    mcpServerName = target.name,
                    outgoingAttachments = imgs
                )
            }

            "mcp_read_resource" -> {
                val srvQuery = str("server") ?: return ToolRunOutput("Missing required parameter: server", isError = true)
                val uri = str("uri") ?: return ToolRunOutput("Missing required parameter: uri", isError = true)
                val target = mcpManager.getAllServers().firstOrNull {
                    it.id == srvQuery || it.name.equals(srvQuery, ignoreCase = true)
                } ?: return ToolRunOutput("MCP server '$srvQuery' not found", isError = true)
                mcpManager.readResource(target.id, uri).fold(
                    onSuccess = { ToolRunOutput(it, mcpServerName = target.name) },
                    onFailure = { ToolRunOutput("MCP resource read error: ${it.message}", isError = true) }
                )
            }

            else -> ToolRunOutput("Unknown tool: $toolName", isError = true)
        }
    }

    private suspend fun runLocalShellCommand(command: String, workDir: File, timeoutSec: Int): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            workDir.mkdirs()
            val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            try {
                val result = withTimeoutOrNull(timeoutSec.coerceAtLeast(1) * 1000L) {
                    coroutineScope {
                        val readJob = async(Dispatchers.IO) {
                            val maxBytes = 512 * 1024
                            val outBuf = ByteArrayOutputStream()
                            val chunk = ByteArray(4096)
                            var truncated = false
                            proc.inputStream.use { input ->
                                while (true) {
                                    val n = input.read(chunk)
                                    if (n < 0) break
                                    val room = maxBytes - outBuf.size()
                                    if (room > 0) {
                                        outBuf.write(chunk, 0, minOf(n, room))
                                    } else {
                                        truncated = true
                                    }
                                }
                            }
                            val text = outBuf.toString(Charsets.UTF_8.name())
                            if (truncated) "$text\n...[output truncated at 512 KB]" else text
                        }
                        val exitCode = proc.waitFor()
                        exitCode to readJob.await()
                    }
                }
                if (result == null) {
                    proc.destroyForcibly()
                    -1 to "Command timed out after ${timeoutSec}s"
                } else {
                    result
                }
            } catch (ce: CancellationException) {
                proc.destroyForcibly()
                throw ce
            } catch (t: Throwable) {
                proc.destroyForcibly()
                -1 to "Failed to launch local shell: ${t.message}"
            }
        }

    private fun builtInSpecs(): List<LlmToolSpec> = listOf(
        spec(
            "check_environment_status",
            "Check connected environments: AI Home sandbox ('internal'), Termux/SSH profiles, Android SAF folder, and MCP servers.",
            emptyObjSchema()
        ),
        spec(
            "list_files",
            "List files and subdirectories in a folder.",
            objSchema(
                mapOf(
                    "path" to strProp("Relative or absolute directory path. Leave empty for root."),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (optional for target_env='termux_ssh')")
                )
            )
        ),
        spec(
            "search_files",
            "Find files by name or path substring.",
            objSchema(
                mapOf(
                    "query" to strProp("File name or path substring"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (for termux_ssh)")
                ),
                listOf("query")
            )
        ),
        spec(
            "search_codebase",
            "Full-text grep search across project file contents.",
            objSchema(
                mapOf(
                    "query" to strProp("Text or symbol to search across code"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (for termux_ssh)")
                ),
                listOf("query")
            )
        ),
        spec(
            "file_read",
            "Read a text, code, or PDF file by line range.",
            objSchema(
                mapOf(
                    "path" to strProp("Path to the file"),
                    "offset_line" to intProp("1-based starting line"),
                    "limit_lines" to intProp("Number of lines to read (default 300)"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (for termux_ssh)")
                ),
                listOf("path")
            )
        ),
        spec(
            "file_write",
            "Create or overwrite a file completely. Automatically saves a rollback snapshot.",
            objSchema(
                mapOf(
                    "path" to strProp("Path to the file"),
                    "content" to strProp("Full file contents"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (for termux_ssh)")
                ),
                listOf("path", "content")
            )
        ),
        spec(
            "file_edit",
            "Replace an exact old_string snippet with new_string in an existing file (generates unified Diff and rollback snapshot).",
            objSchema(
                mapOf(
                    "path" to strProp("Path to the file"),
                    "old_string" to strProp("Exact original text snippet"),
                    "new_string" to strProp("Replacement text snippet"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (for termux_ssh)")
                ),
                listOf("path", "old_string", "new_string")
            )
        ),
        spec(
            "file_delete",
            "Delete a file or directory.",
            objSchema(
                mapOf(
                    "path" to strProp("Path to the file or directory"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (for termux_ssh)")
                ),
                listOf("path")
            )
        ),
        spec(
            "create_folder",
            "Create a directory (including parent directories).",
            objSchema(
                mapOf(
                    "path" to strProp("Directory path to create"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (for termux_ssh)")
                ),
                listOf("path")
            )
        ),
        spec(
            "archive_create",
            "Create a ZIP archive from a file or folder inside the internal sandbox ('internal'). Use run_command with zip for SSH/Termux.",
            objSchema(
                mapOf(
                    "source_path" to strProp("Source file or folder path (inside 'internal')"),
                    "archive_path" to strProp("Destination .zip path (defaults to <source_name>.zip)")
                ),
                listOf("source_path")
            )
        ),
        spec(
            "archive_extract",
            "Extract a ZIP archive inside the internal sandbox ('internal'). Use run_command with unzip for SSH/Termux.",
            objSchema(
                mapOf(
                    "archive_path" to strProp("Path to the .zip archive (inside 'internal')"),
                    "target_dir" to strProp("Destination folder (defaults to archive name without .zip)")
                ),
                listOf("archive_path")
            )
        ),
        spec(
            "transfer_file",
            "Transfer or copy a file across environments (e.g. between 'internal', 'termux_ssh', and 'saf').",
            objSchema(
                mapOf(
                    "source_env" to strProp("internal | termux_ssh | saf"),
                    "source_path" to strProp("Source file path"),
                    "source_server" to strProp("Source SSH server (when source_env='termux_ssh')"),
                    "target_env" to strProp("internal | termux_ssh | saf"),
                    "target_path" to strProp("Destination file path"),
                    "target_server" to strProp("Target SSH server (when target_env='termux_ssh')")
                ),
                listOf("source_env", "source_path", "target_env", "target_path")
            )
        ),
        spec(
            "send_file_to_chat",
            "Attach a file or image directly to the chat message for inline preview, download, or sharing.",
            objSchema(
                mapOf(
                    "path" to strProp("File path in the target environment"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (for termux_ssh)"),
                    "caption" to strProp("Optional short caption")
                ),
                listOf("path")
            )
        ),
        spec(
            "run_command",
            "Execute a shell command on an SSH server (Termux/SSH) or inside the app's embedded physical shell ('internal' always works even without SSH).",
            objSchema(
                mapOf(
                    "command" to strProp("Shell command, e.g. 'ls -la', 'python3 app.py', 'git status'"),
                    "workdir" to strProp("Optional working directory"),
                    "target_env" to envProp(),
                    "server" to strProp("SSH server name or ID (e.g. 'Termux (local)' or 'Remote SSH')"),
                    "timeout_seconds" to intProp("Timeout in seconds (default 35)")
                ),
                listOf("command")
            )
        ),
        spec(
            "run_command_async",
            "Start a long-running command in the background (via SSH/Termux or the embedded physical 'internal' shell) and return PID + log_file.",
            objSchema(
                mapOf(
                    "command" to strProp("Command to launch in background"),
                    "workdir" to strProp("Optional working directory"),
                    "target_env" to envProp(),
                    "server" to strProp("Optional SSH server name or ID")
                ),
                listOf("command")
            )
        ),
        spec(
            "check_command_status",
            "Check whether a background process is still running by PID and read the trailing lines of its log file.",
            objSchema(
                mapOf(
                    "pid" to strProp("Process PID"),
                    "log_file" to strProp("Path to the log file"),
                    "lines" to intProp("Number of trailing lines to read (default 60)"),
                    "target_env" to envProp(),
                    "server" to strProp("Optional SSH server name or ID")
                ),
                listOf("pid", "log_file")
            )
        ),
        spec(
            "kill_command",
            "Terminate a background process in Termux/SSH or the embedded shell by PID.",
            objSchema(
                mapOf(
                    "pid" to strProp("Process PID"),
                    "target_env" to envProp(),
                    "server" to strProp("Optional SSH server name or ID")
                ),
                listOf("pid")
            )
        ),
        spec(
            "web_search",
            "Search the web for up-to-date information.",
            objSchema(
                mapOf(
                    "query" to strProp("Search query"),
                    "count" to intProp("Number of results (1..20)")
                ),
                listOf("query")
            )
        ),
        spec(
            "web_scrape",
            "Fetch and parse a web page into Markdown, plain text, links, tables, or by CSS selector. Also opens the URL in the background Agent Browser when available.",
            objSchema(
                mapOf(
                    "url" to strProp("Full https://... URL"),
                    "mode" to strProp("markdown | text | selector | links | tables | html"),
                    "css_selector" to strProp("CSS selector for targeted scraping (e.g. 'article p', 'table.price')"),
                    "render_js" to boolProp("Use a real WebView to render JavaScript (for SPAs)"),
                    "headers_json" to strProp("JSON object of extra HTTP headers"),
                    "max_chars" to intProp("Maximum characters in response")
                ),
                listOf("url")
            )
        ),
        spec(
            "browser_open",
            "Open a URL in the AI's background Agent Browser (isolated WebView, never touches the user's Regular Browser).",
            objSchema(
                mapOf("url" to strProp("Web page URL")),
                listOf("url")
            )
        ),
        spec(
            "mcp_list_servers",
            "List configured external MCP (Model Context Protocol) servers and their tools.",
            emptyObjSchema()
        ),
        spec(
            "mcp_connect",
            "Connect to an external MCP server (Streamable HTTP, SSE, or SSH Stdio), discover its tools, and expose them for invocation.",
            objSchema(
                mapOf(
                    "name" to strProp("Short server name"),
                    "url" to strProp("HTTP/SSE endpoint URL or SSH_STDIO command"),
                    "transport" to strProp("STREAMABLE_HTTP | HTTP_SSE | SSH_STDIO"),
                    "headers_json" to strProp("JSON auth headers, e.g. {\"Authorization\":\"Bearer ...\"}")
                ),
                listOf("name", "url")
            )
        ),
        spec(
            "mcp_call_tool",
            "Invoke a tool directly on a connected MCP server.",
            objSchema(
                mapOf(
                    "server" to strProp("MCP server name or ID"),
                    "tool_name" to strProp("Tool name on the MCP server"),
                    "arguments_json" to strProp("JSON string of tool arguments")
                ),
                listOf("server", "tool_name")
            )
        ),
        spec(
            "mcp_read_resource",
            "Read an MCP server resource by URI.",
            objSchema(
                mapOf(
                    "server" to strProp("MCP server name or ID"),
                    "uri" to strProp("MCP resource URI")
                ),
                listOf("server", "uri")
            )
        )
    )

    private fun spec(name: String, description: String, schema: JsonObject) =
        LlmToolSpec(name = name, description = description, parametersSchema = schema)

    private fun emptyObjSchema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {})
    }

    private fun ensureObjectSchema(src: JsonObject): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", src["properties"] ?: buildJsonObject {})
        src["required"]?.let { put("required", it) }
        src["\$defs"]?.let { put("\$defs", it) }
        src["definitions"]?.let { put("definitions", it) }
        src["anyOf"]?.let { put("anyOf", it) }
        src["oneOf"]?.let { put("oneOf", it) }
    }

    private fun objSchema(props: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                props.forEach { (k, v) -> put(k, v) }
            })
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
        }

    private fun strProp(desc: String) = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(desc))
    }

    private fun intProp(desc: String) = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(desc))
    }

    private fun boolProp(desc: String) = buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(desc))
    }

    private fun envProp() = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", buildJsonArray {
            add(JsonPrimitive("internal"))
            add(JsonPrimitive("termux_ssh"))
            add(JsonPrimitive("saf"))
        })
        put("description", JsonPrimitive("Target environment: 'internal' (AI Home sandbox), 'termux_ssh' (Termux/SSH), or 'saf' (Android SAF folder). Defaults to the session's active environment."))
    }

    companion object {
        private val DANGEROUS_CMD_REGEX = Regex(
            """(?:^|[;&|\s/])rm(?:\s|$)|\bmkfs\b|(?:^|[;&|\s/])dd\s|git\s+reset\s+--hard|chmod\s+(?:-[a-zA-Z]*\s+)?777\b|kill\s+-9\b|:\(\)\s*\{|\b(?:shutdown|reboot)\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
