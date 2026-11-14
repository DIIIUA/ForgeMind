package com.forgemind.app.agent

import com.forgemind.app.accessibility.ForgeAccessibilityService
import com.forgemind.app.bridge.TerminalSessionManager
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.archive.ChatArchiveManager
import com.forgemind.app.data.db.ForgeMindDatabase
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.McpTransport
import com.forgemind.app.data.model.ProviderCategory
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
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
        val phoneDir = workspaceManager.getDeviceStorageRoot().absolutePath
        val phoneAccess = if (workspaceManager.hasFullDeviceStorageAccess()) "полный доступ разрешён" else "требуется разрешение All Files"
        val safUriStr = workspaceManager.safRootUri?.toString()
        val safDesc = if (safUriStr.isNullOrBlank()) {
            "не выбрана (пользователь может выбрать папку в «Файлы ИИ» -> «Папка Android (SAF)»)"
        } else {
            "подключена -> ${WorkspaceManager.formatSafTreeLabel(safUriStr)}"
        }
        val profiles = termuxBridge.getAllProfiles()
        val sshDesc = if (profiles.isEmpty()) {
            "нет настроенных SSH-профилей"
        } else {
            profiles.joinToString("\n  ") { p ->
                val state = when {
                    !p.enabled -> "выключен в Настройках"
                    p.host.isBlank() || p.username.isBlank() -> "включён, но не заполнены host/username"
                    termuxBridge.isSshConnected(p.id) -> "включён, подключён сейчас"
                    else -> "включён, готов к подключению"
                }
                val defTag = if (p.isDefault) " [основной]" else ""
                val wd = p.defaultWorkDir.ifBlank { "~" }
                "- server='${p.id}' (${p.name}): ${p.username.ifBlank { "?" }}@${p.host.ifBlank { "?" }}:${p.port}, workDir=$wd [$state]$defTag"
            }
        }
        val termuxDesc = when {
            termuxBridge.isTermuxInstalled() && termuxBridge.hasTermuxPermission() -> "Termux установлен, RUN_COMMAND разрешён"
            termuxBridge.isTermuxInstalled() -> "Termux установлен (работа по SSH)"
            else -> "Termux не обнаружен"
        }
        val mcpAll = mcpManager.getAllServers()
        val mcpEnabled = mcpAll.filter { it.enabled }
        val mcpDisabled = mcpAll.filter { !it.enabled }
        val toolsMap = if (mcpEnabled.isNotEmpty()) mcpManager.getEnabledToolsMap() else emptyMap()
        val mcpInstructionBlocks = mutableListOf<Pair<String, String>>()
        val mcpDesc = if (mcpAll.isEmpty()) {
            "внешние MCP-серверы не добавлены (настраиваются в Настройки -> MCP)"
        } else {
            val enabledLines = mcpEnabled.joinToString("; ") { s ->
                val list = toolsMap[s].orEmpty()
                val insight = mcpManager.analyzeServerCatalog(s, list)
                val instr = mcpManager.resolveServerInstructions(s)
                if (instr.isNotBlank()) {
                    mcpInstructionBlocks += s.name to instr
                }
                val endpointPart = s.endpoint.trim().takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
                val instrTag = if (instr.isNotBlank()) ", instructions=${instr.length} симв." else ""
                "${s.name} [${s.transport}$endpointPart, ${insight.summaryLine}$instrTag]"
            }
            val disabledNote = if (mcpDisabled.isNotEmpty()) {
                val names = mcpDisabled.joinToString(", ") { s ->
                    val ep = s.endpoint.trim()
                    if (ep.isNotBlank()) "${s.name} ($ep)" else s.name
                }
                " | отключены вручную (${mcpDisabled.size}): $names (не считать сломанными и не включать без запроса)"
            } else ""
            (enabledLines.ifBlank { "активных MCP-серверов сейчас нет" }) + disabledNote
        }
        val aiShellState = terminalManager?.embeddedAiShell?.ensureStarted()
        val aiShellDesc = if (aiShellState != null) {
            "активен (PID=${aiShellState.pid ?: "?"}, cwd=${aiShellState.workDir}, команд=${aiShellState.commandCount})"
        } else {
            "локальный /system/bin/sh в $internalDir"
        }
        return buildString {
            appendLine("=== ГДЕ ТЫ НАХОДИШЬСЯ И ЧТО ГДЕ ЛЕЖИТ ===")
            appendLine("• Среда по умолчанию в этом чате: '${defaultEnv.id}' (${defaultEnv.label}).")
            appendLine("• 'internal' (Домашняя ИИ): $internalDir")
            appendLine("  Твоя приватная папка внутри Android-приложения. Сюда складывай создаваемые скрипты/файлы/заметки; пользователь видит её во вкладке «Файлы ИИ -> Домашняя ИИ» и через «+» в чате. Чтобы отдать файл в чат — используй send_file_to_chat.")
            appendLine("  • Встроенный физический терминал ИИ (постоянный /system/bin/sh внутри приложения): $aiShellDesc. Сохраняет cd/export и работает ВСЕГДА — даже если SSH/Termux отключён или оборвался (живой вывод виден пользователю во вкладке «Терминал -> Агент (Live)»).")
            appendLine("• 'device_storage' (Память телефона): $phoneDir [$phoneAccess].")
            appendLine("• 'saf' (Папка Android): $safDesc.")
            appendLine("• 'termux_ssh' (Linux/SSH, $termuxDesc; указывай параметр server='<id>'):")
            appendLine("  $sshDesc")
            appendLine("• MCP-серверы: $mcpDesc.")
            if (mcpInstructionBlocks.isNotEmpty()) {
                appendLine("• Инструкции активных MCP-серверов (из initialize.instructions):")
                var remainingBudget = 12_000
                mcpInstructionBlocks.forEach { (serverName, rawInstructions) ->
                    if (remainingBudget <= 0) return@forEach
                    val perServerCap = minOf(6_000, remainingBudget)
                    val trimmed = rawInstructions.trim()
                    val body = if (trimmed.length > perServerCap) {
                        trimmed.take(perServerCap) + "\n... [обрезано до $perServerCap из ${trimmed.length} симв.]"
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
            val browserTurbo = browserController?.turboModeEnabled?.value == true
            val browserTabs = browserController?.agentTabs?.value.orEmpty()
            val browserTabsSummary = if (browserTabs.isEmpty()) {
                if (browserUrl.isBlank()) "готов" else browserUrl
            } else {
                val openNonBlank = browserTabs.filter { it.url.isNotBlank() }
                if (openNonBlank.isEmpty()) {
                    "окон: ${browserTabs.size}, ⚡ Turbo=${if (browserTurbo) "ВКЛ" else "выкл"}"
                } else {
                    "окон: ${browserTabs.size} (⚡ Turbo=${if (browserTurbo) "ВКЛ" else "выкл"}): " +
                        openNonBlank.joinToString(", ") { "[${it.id}${if (it.isActive) "*" else ""}] ${it.url}" }
                }
            }
            val previewPort = localPreviewServer?.port ?: 0
            val a11yReady = ForgeAccessibilityService.ensureConnectedOrSelfHeal() != null
            val mcpHostCfg = settingsRepo?.mcpHostSettings?.value
            val mcpHostStatus = if (mcpHostCfg?.enabled == true) {
                "АКТИВЕН на http://127.0.0.1:${mcpHostCfg.port}/mcp (доступен из встроенного Shell через fm-mcp и из Termux)"
            } else {
                "выключен (включается в Настройки -> MCP)"
            }
            appendLine("• Window-Tag DOM и управление экраном Android (screen_read_windows / screen_act_tag / screen_act_batch / screen_capture): ${if (a11yReady) "АКТИВЕН — читает окна [W1..Wn] и теги [#1..#k] за миллисекунды, выполняет пакеты шагов без пауз и делает быстрый снимок по запросу" else "выключен в Спецвозможностях Android (пользователь может включить в Настройки -> Агент -> Window-Tag DOM)"}.")
            appendLine("• Встроенный MCP-сервер ForgeMind (локальная петля 127.0.0.1): $mcpHostStatus.")
            append("• Встроенные рычаги (пассивный фон, не мешают UI пользователя): мультиоконный Агентный браузер ($browserTabsSummary), HTTP preview (${if (previewPort > 0) "http://127.0.0.1:$previewPort/" else "выкл"}), буфер редактора кода, фоновый терминал, снэпшоты, история чатов, сжатие контекста и TTS.")
        }.trimEnd()
    }

    fun buildHostServerToolSpecs(exposeShellAndWrite: Boolean = true): List<LlmToolSpec> {
        val all = builtInSpecs() + appLeverTools.specs()
        if (exposeShellAndWrite) return all
        return all.filterNot { it.name in SHELL_AND_WRITE_HOST_TOOLS }
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

    /**
     * Tailors outgoing tool schemas to the active model's context budget & parameter scale while keeping
     * ALL built-in and MCP tools registered in `dynamicMcpLookup` so direct or `mcp_call_tool` execution works 100%.
     */
    fun selectToolsForProvider(
        allSpecs: List<LlmToolSpec>,
        providerCfg: ProviderConfig
    ): List<LlmToolSpec> {
        if (allSpecs.isEmpty()) return emptyList()
        if (shouldUseCompactModelMode(providerCfg)) {
            val coreSet = COMPACT_CORE_TOOL_NAMES
            val filtered = allSpecs.filter { it.name in coreSet }
                .ifEmpty { allSpecs.take(24) }
            return filtered.map { compactToolSpec(it) }
        }
        // OpenAI `/v1/chat/completions` enforces a hard maximum of 128 tools per request
        val isStrictOpenAiLimit = ProviderType.fromId(providerCfg.providerId) == ProviderType.OPENAI &&
            providerCfg.isBuiltInProvider()
        if (isStrictOpenAiLimit && allSpecs.size > 128) {
            val nonNumberedTasker = allSpecs.filterNot { it.name.contains("tasker_ag_", ignoreCase = true) }
            return (if (nonNumberedTasker.size >= 60) nonNumberedTasker else allSpecs).take(128)
        }
        return allSpecs
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

        // 1. Черный список пользователя ВСЕГДА требует одобрения (даже при полном автопилоте)
        if (settings.blockedTools.any { it.equals(normalized, ignoreCase = true) }) {
            return true
        }

        // 2. Опасные команды shell (rm -rf, mkfs, forkbomb, etc.) требуют подтверждения,
        // пока пользователь явно не включил полный автопилот (autoApproveAll).
        val hasDangerousRegex = isDangerousCommand(normalized, argsJson)
        if (hasDangerousRegex && !settings.autoApproveAll) {
            return true
        }

        // 3. Полный автопилот: всё разрешено, если инструмент не в черном списке
        if (settings.autoApproveAll) {
            return false
        }

        // 4. Белый список пользователя разрешает инструмент (кроме опасных shell regex выше)
        if (settings.allowedTools.any { it.equals(normalized, ignoreCase = true) }) {
            return false
        }

        // 5. Гранулярная оценка по функциональным категориям
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

            normalized == "browser_agent_eval_js" ||
                (normalized == "browser_agent_batch" && hasBrowserEvalJsStep(argsJson)) -> {
                !settings.autoApproveBrowserJs
            }

            normalized == "browser_agent_markdown" && hasNonBlankSavePath(argsJson) -> {
                !(settings.autoApproveSafe && settings.autoApproveBrowser && settings.autoApproveFileWrite)
            }

            normalized == "web_scrape_site" && hasNonBlankSavePath(argsJson) -> {
                !(settings.autoApproveSafe && settings.autoApproveRead && settings.autoApproveFileWrite)
            }

            normalized.startsWith("browser_agent_") ||
                normalized in setOf("browser_open", "screen_act_tag", "screen_act_batch", "screen_capture") -> {
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
            el as? JsonObject ?: error("Аргументы должны быть JSON-объектом")
        }.getOrElse { err ->
            return ToolRunOutput(
                "Ошибка разбора JSON-аргументов для '$toolName': ${err.message}. Передайте корректный JSON-объект.",
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
                "Внутренняя ошибка инструмента '$toolName': ${t.message ?: t::class.java.simpleName}",
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
                    appendLine("Единое рабочее пространство (все доступные среды активны сразу):")
                    appendLine("- Домашняя папка ИИ (`internal`): ${workspaceManager.getInternalProjectDir(internalProject).absolutePath} [Готова]")
                    appendLine("- Память телефона (`device_storage`): ${workspaceManager.getDeviceStorageRoot().absolutePath} [${if (workspaceManager.hasFullDeviceStorageAccess()) "Полный доступ" else "Ограниченный доступ"}]")
                    appendLine("- Termux установлен: ${termuxBridge.isTermuxInstalled()}, RUN_COMMAND: ${termuxBridge.hasTermuxPermission()}")
                    appendLine("- SSH-серверы (${sshList.size}, включено: ${sshList.count { it.enabled }}):")
                    sshList.forEach { p ->
                        val def = if (p.isDefault) " [основной]" else ""
                        val cfTag = if (p.useCloudflareAccess) ", tunnel=CloudflareAccess" else ""
                        appendLine("  * ${p.name} (id=${p.id}): ${p.username.ifBlank { "?" }}@${p.host.ifBlank { "?" }}:${p.port}$cfTag, enabled=${p.enabled}, connected=${termuxBridge.isSshConnected(p.id)}$def")
                    }
                    appendLine("- SAF папка Android: ${workspaceManager.safRootUri?.let { WorkspaceManager.formatSafTreeLabel(it.toString()) } ?: "не выбрана"}")
                    browserController?.let { ctrl ->
                        appendLine("- Мультиоконный Агентный браузер: ⚡ Turbo=${if (ctrl.turboModeEnabled.value) "ВКЛ" else "выкл"}, окон=${ctrl.agentTabs.value.size} (активное=[${ctrl.activeAgentTabId.value}])")
                    }
                    appendLine("- MCP-серверов: ${mcpList.size} (включено: ${mcpList.count { it.enabled }})")
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
                        if (items.isEmpty()) ToolRunOutput("Папка пуста (${env.id}:$path).")
                        else {
                            val formatted = items.joinToString("\n") {
                                val icon = if (it.isDirectory) "[DIR]" else "[${it.sizeBytes}B]"
                                "$icon ${it.path}"
                            }
                            ToolRunOutput(formatted)
                        }
                    },
                    onFailure = { ToolRunOutput("Ошибка list_files: ${it.message}", isError = true) }
                )
            }

            "search_files" -> {
                val env = resolveEnv()
                val q = str("query").orEmpty()
                val server = str("server")
                workspaceManager.searchFiles(env, q, internalProject, sshServer = server).fold(
                    onSuccess = { list ->
                        ToolRunOutput(if (list.isEmpty()) "Файлы не найдены." else list.joinToString("\n"))
                    },
                    onFailure = { ToolRunOutput("Ошибка search_files: ${it.message}", isError = true) }
                )
            }

            "search_codebase" -> {
                val env = resolveEnv()
                val q = str("query") ?: return ToolRunOutput("Отсутствует query", isError = true)
                val server = str("server")
                workspaceManager.searchCodebase(env, q, internalProject, sshServer = server).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка search_codebase: ${it.message}", isError = true) }
                )
            }

            "file_read" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Отсутствует path", isError = true)
                val off = int("offset_line", 1)
                val lim = int("limit_lines", 300)
                val server = str("server")
                workspaceManager.readFile(env, path, off, lim, internalProject, sshServer = server).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка file_read: ${it.message}", isError = true) }
                )
            }

            "file_write" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Отсутствует path", isError = true)
                val content = str("content") ?: return ToolRunOutput("Отсутствует content", isError = true)
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
                    onFailure = { ToolRunOutput("Ошибка file_write: ${it.message}", isError = true) }
                )
            }

            "file_edit" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Отсутствует path", isError = true)
                val oldS = str("old_string") ?: return ToolRunOutput("Отсутствует old_string", isError = true)
                val newS = str("new_string") ?: return ToolRunOutput("Отсутствует new_string", isError = true)
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
                    onFailure = { ToolRunOutput("Ошибка file_edit: ${it.message}", isError = true) }
                )
            }

            "file_delete" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Отсутствует path", isError = true)
                val server = str("server")
                workspaceManager.deleteFile(env, path, internalProject, sshServer = server).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка file_delete: ${it.message}", isError = true) }
                )
            }

            "create_folder" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Отсутствует path", isError = true)
                val server = str("server")
                workspaceManager.createFolder(env, path, internalProject, sshServer = server).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка create_folder: ${it.message}", isError = true) }
                )
            }

            "archive_create" -> {
                val source = str("source_path") ?: return ToolRunOutput("Отсутствует source_path", isError = true)
                workspaceManager.createZipArchive(source, str("archive_path").orEmpty(), internalProject).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка archive_create: ${it.message}", isError = true) }
                )
            }

            "archive_extract" -> {
                val archive = str("archive_path") ?: return ToolRunOutput("Отсутствует archive_path", isError = true)
                workspaceManager.extractZipArchive(archive, str("target_dir").orEmpty(), internalProject).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка archive_extract: ${it.message}", isError = true) }
                )
            }

            "transfer_file" -> {
                val srcEnv = WorkspaceEnvType.fromId(str("source_env"))
                val srcPath = str("source_path") ?: return ToolRunOutput("Отсутствует source_path", isError = true)
                val dstEnv = WorkspaceEnvType.fromId(str("target_env"))
                val dstPath = str("target_path") ?: return ToolRunOutput("Отсутствует target_path", isError = true)
                val srcServer = str("source_server") ?: str("server")
                val dstServer = str("target_server") ?: str("server")
                val moveFlag = bool("move", false)
                workspaceManager.transferFile(
                    sourceEnv = srcEnv,
                    sourcePath = srcPath,
                    targetEnv = dstEnv,
                    targetPath = dstPath,
                    internalProject = internalProject,
                    sourceSshServer = srcServer,
                    targetSshServer = dstServer,
                    move = moveFlag
                ).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка transfer_file: ${it.message}", isError = true) }
                )
            }

            "send_file_to_chat" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Отсутствует path", isError = true)
                val caption = str("caption").orEmpty()
                val server = str("server")
                workspaceManager.prepareAssistantOutgoingFile(env, path, internalProject, sshServer = server).fold(
                    onSuccess = { att ->
                        ToolRunOutput(
                            text = "Файл '${att.name}' (${att.sizeBytes} байт) прикреплён в чат. $caption".trim(),
                            outgoingAttachments = listOf(att)
                        )
                    },
                    onFailure = { ToolRunOutput("Ошибка send_file_to_chat: ${it.message}", isError = true) }
                )
            }

            "run_command" -> {
                val cmd = str("command") ?: return ToolRunOutput("Отсутствует command", isError = true)
                val env = resolveEnv()
                val timeout = int("timeout_seconds", 35).coerceIn(5, 180)
                val server = str("server")
                val wdRaw = str("workdir")?.trim()?.takeIf { it.isNotEmpty() }
                val isDeviceStorageReady = env == WorkspaceEnvType.DEVICE_STORAGE && workspaceManager.hasFullDeviceStorageAccess()
                val defaultInternalDir = if (isDeviceStorageReady) {
                    workspaceManager.getDeviceStorageRoot()
                } else {
                    workspaceManager.getInternalProjectDir(internalProject)
                }
                val customLocalDir = wdRaw?.let { File(it) }
                val effectiveShellWorkDir = customLocalDir ?: defaultInternalDir.takeIf { isDeviceStorageReady }
                if (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank()) {
                    val shell = terminalManager?.embeddedAiShell
                    if (shell != null) {
                        val res = shell.execute(
                            command = cmd,
                            workDir = effectiveShellWorkDir,
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
                                appendLine("[SSH недоступен (${res.error}) -> выполнено через встроенный физический терминал приложения]")
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
                            appendLine("Подсказка: встроенный физический терминал приложения (`target_env='internal'`) активен и работает независимо от SSH.")
                        }
                        if (res.stdout.isNotBlank()) appendLine("--- stdout ---\n${res.stdout.take(16_000)}")
                        if (res.stderr.isNotBlank()) appendLine("--- stderr ---\n${res.stderr.take(8_000)}")
                    }
                    ToolRunOutput(outText, isError = res.error != null || res.exitCode != 0)
                }
            }

            "run_command_async" -> {
                val cmd = str("command") ?: return ToolRunOutput("Отсутствует command", isError = true)
                val env = resolveEnv()
                val wd = str("workdir")?.trim()?.takeIf { it.isNotEmpty() }
                val server = str("server")
                val shell = terminalManager?.embeddedAiShell
                val defaultInternalDir = if (env == WorkspaceEnvType.DEVICE_STORAGE && workspaceManager.hasFullDeviceStorageAccess()) {
                    workspaceManager.getDeviceStorageRoot()
                } else {
                    workspaceManager.getInternalProjectDir(internalProject)
                }
                if (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank() && shell != null) {
                    shell.startAsync(cmd, wd?.let { File(it) } ?: defaultInternalDir).fold(
                        onSuccess = { started ->
                            ToolRunOutput(
                                "Команда запущена в фоне встроенного физического Shell.\n" +
                                    "PID: ${started.id}\nOS PID: ${started.pid}\nLog file: ${started.logFile.absolutePath}\n" +
                                    "Используйте check_command_status(pid='${started.id}', log_file='${started.logFile.absolutePath}', target_env='internal') для проверки вывода."
                            )
                        },
                        onFailure = { ToolRunOutput("Ошибка run_command_async (internal): ${it.message}", isError = true) }
                    )
                } else {
                    termuxBridge.runCommandAsync(cmd, wd, serverHint = server).fold(
                        onSuccess = { started ->
                            val srvArg = server?.let { ", server='$it'" }.orEmpty()
                            ToolRunOutput(
                                "Команда запущена в фоне.\nPID: ${started.pid}\nLog file: ${started.logFile}\n" +
                                    "Используйте check_command_status(pid='${started.pid}', log_file='${started.logFile}'$srvArg) для проверки вывода."
                            )
                        },
                        onFailure = { err ->
                            if (bool("fallback_to_embedded", true) && termuxBridge.isLocalSshTarget(server) && shell != null) {
                                shell.startAsync(cmd, wd?.let { File(it) } ?: defaultInternalDir).fold(
                                    onSuccess = { started ->
                                        ToolRunOutput(
                                            "[SSH недоступен (${err.message}) -> запущено в фоне встроенного физического Shell]\n" +
                                                "PID: ${started.id}\nLog file: ${started.logFile.absolutePath}\n" +
                                                "Используйте check_command_status(pid='${started.id}', log_file='${started.logFile.absolutePath}', target_env='internal')."
                                        )
                                    },
                                    onFailure = { fbErr ->
                                        ToolRunOutput("Ошибка run_command_async: ${err.message}; fallback error: ${fbErr.message}", isError = true)
                                    }
                                )
                            } else {
                                ToolRunOutput("Ошибка run_command_async: ${err.message}", isError = true)
                            }
                        }
                    )
                }
            }

            "check_command_status" -> {
                val pid = str("pid") ?: return ToolRunOutput("Отсутствует pid", isError = true)
                val logFile = str("log_file") ?: return ToolRunOutput("Отсутствует log_file", isError = true)
                val lines = int("lines", 60)
                val server = str("server")
                val env = resolveEnv()
                val shell = terminalManager?.embeddedAiShell
                if ((pid.startsWith("local_") || (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank())) && shell != null) {
                    ToolRunOutput(shell.checkAsyncStatus(pid, logFile, lines))
                } else {
                    val statusText = termuxBridge.checkCommandStatus(pid, logFile, lines, serverHint = server)
                    ToolRunOutput(statusText, isError = statusText.startsWith("Ошибка"))
                }
            }

            "kill_command" -> {
                val pid = str("pid") ?: return ToolRunOutput("Отсутствует pid", isError = true)
                val server = str("server")
                val env = resolveEnv()
                val shell = terminalManager?.embeddedAiShell
                if ((pid.startsWith("local_") || (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank())) && shell != null) {
                    shell.killAsync(pid).fold(
                        onSuccess = { ToolRunOutput(it) },
                        onFailure = { ToolRunOutput("Ошибка kill_command: ${it.message}", isError = true) }
                    )
                } else {
                    val res = termuxBridge.killCommand(pid, serverHint = server)
                    ToolRunOutput(res.stdout.ifBlank { res.stderr.ifBlank { res.error ?: "Завершено" } }, isError = res.error != null)
                }
            }

            "web_search" -> {
                val query = str("query") ?: return ToolRunOutput("Отсутствует query", isError = true)
                val count = int("count", 8).coerceIn(1, 20)
                webScraper.search(query, count, searchSettings).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка web_search: ${it.message}", isError = true) }
                )
            }

            "web_scrape" -> {
                val url = str("url") ?: return ToolRunOutput("Отсутствует url", isError = true)
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
                    onFailure = { ToolRunOutput("Ошибка web_scrape: ${it.message}", isError = true) }
                )
            }

            "web_scrape_site" -> {
                val startUrl = str("start_url") ?: str("url")
                    ?: return ToolRunOutput("Отсутствует start_url", isError = true)
                val maxPages = int("max_pages", 12).coerceIn(1, 50)
                val maxDepth = int("max_depth", 2).coerceIn(0, 5)
                val concurrency = int("concurrency", 6).coerceIn(1, 8)
                val samePrefix = bool("same_path_prefix", false)
                val urlFilter = str("url_filter")
                val includeSitemap = bool("include_sitemap", true)
                val renderJsFirst = bool("render_js_first_page", false)
                val headers = str("headers_json") ?: "{}"
                val maxCharsPerPage = int("max_chars_per_page", 14_000).coerceIn(1500, 50_000)
                val maxCharsTotal = int("max_chars", 40_000).coerceIn(2000, 120_000)
                val saveDirRaw = str("save_to_path")?.trim()?.trimEnd('/', '\\')?.takeIf { it.isNotEmpty() }

                webScraper.scrapeSite(
                    startUrl = startUrl,
                    maxPages = maxPages,
                    maxDepth = maxDepth,
                    concurrency = concurrency,
                    samePathPrefix = samePrefix,
                    urlFilter = urlFilter,
                    includeSitemap = includeSitemap,
                    renderJsFirstPage = renderJsFirst,
                    headersJson = headers,
                    maxCharsPerPage = maxCharsPerPage,
                    settings = searchSettings
                ).fold(
                    onSuccess = { site ->
                        onOpenBrowserUrl(site.startUrl)
                        val savedNote = if (saveDirRaw != null) {
                            val env = resolveEnv()
                            val server = str("server")
                            val effectiveDir = if (env == WorkspaceEnvType.SAF) {
                                runCatching {
                                    WorkspaceManager.normalizeSafRelativePath(saveDirRaw, workspaceManager.safRootUri?.toString())
                                }.getOrElse { err ->
                                    return ToolRunOutput("Ошибка save_to_path в SAF ('$saveDirRaw'): ${err.message}", isError = true)
                                }
                            } else {
                                saveDirRaw
                            }
                            val folderErr = workspaceManager.createFolder(env, effectiveDir, internalProject, sshServer = server).exceptionOrNull()
                            if (folderErr != null) {
                                return ToolRunOutput("Не удалось создать папку '$saveDirRaw' в ${env.id}: ${folderErr.message}", isError = true)
                            }
                            val savedFiles = mutableListOf<String>()
                            val writeErrors = mutableListOf<String>()
                            suspend fun saveMd(relName: String, text: String) {
                                val targetPath = if (effectiveDir.isBlank()) relName else "$effectiveDir/$relName"
                                workspaceManager.writeFile(
                                    env = env,
                                    path = targetPath,
                                    content = text,
                                    sessionId = sessionId,
                                    messageId = messageId,
                                    saveSnapshot = false,
                                    internalProject = internalProject,
                                    sshServer = server
                                ).fold(
                                    onSuccess = { savedFiles += targetPath },
                                    onFailure = { writeErrors += "$relName: ${it.message}" }
                                )
                            }
                            saveMd("INDEX.md", site.indexMarkdown)
                            saveMd("FULL_SITE.md", site.combinedMarkdown)
                            site.llmsTxtContent?.takeIf { it.isNotBlank() }?.let { saveMd("llms.txt", it) }
                            for (page in site.pages) {
                                val pageHeader = buildString {
                                    appendLine("# ${page.title}")
                                    appendLine("- URL: ${page.url}")
                                    appendLine("- Depth: ${page.depth}")
                                    appendLine()
                                    append(page.markdown)
                                }
                                saveMd(page.slugFileName, pageHeader)
                            }
                            buildString {
                                appendLine("Сохранено файлов MD-вёрстки сайта в '${effectiveDir.ifBlank { "/" }}' (${savedFiles.size}):")
                                savedFiles.take(15).forEach { appendLine("- $it") }
                                if (savedFiles.size > 15) appendLine("- ... и ещё ${savedFiles.size - 15}")
                                if (writeErrors.isNotEmpty()) {
                                    appendLine("Ошибки записи: ${writeErrors.joinToString("; ")}")
                                }
                                appendLine()
                            }
                        } else ""

                        val body = if (site.combinedMarkdown.length > maxCharsTotal) {
                            site.combinedMarkdown.take(maxCharsTotal) +
                                "\n\n... [Вывод обрезан на $maxCharsTotal из ${site.combinedMarkdown.length} симв. Используйте save_to_path или читайте отдельные страницы.]"
                        } else {
                            site.combinedMarkdown
                        }
                        val wrapped = buildString {
                            appendLine("Скрапер сайта завершил обход ${site.host}: ${site.pages.size} стр. за ${site.elapsedMs} мс.")
                            if (savedNote.isNotBlank()) append(savedNote)
                            appendLine("<external_content untrusted=\"true\" source=\"web_scrape_site\">")
                            appendLine(body)
                            append("</external_content>")
                        }
                        ToolRunOutput(wrapped)
                    },
                    onFailure = { ToolRunOutput("Ошибка web_scrape_site: ${it.message}", isError = true) }
                )
            }

            "browser_open" -> {
                val raw = str("url")?.trim() ?: return ToolRunOutput("Отсутствует url", isError = true)
                val clean = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                onOpenBrowserUrl(clean)
                ToolRunOutput("Страница открыта в фоновом Агентном браузере: $clean")
            }

            "screen_read_windows" -> {
                val svc = ForgeAccessibilityService.ensureConnectedOrSelfHeal()
                    ?: return ToolRunOutput(
                        ForgeAccessibilityService.describeUnavailableMessage(english = false),
                        isError = true
                    )
                val includeSelf = bool("include_self", false)
                val onlyInteractive = bool("only_interactive", false)
                val windowFilter = str("window_filter")
                val maxNodes = int("max_nodes", 260)
                val dom = svc.captureWindowsDom(
                    includeSelf = includeSelf,
                    onlyInteractive = onlyInteractive,
                    windowFilter = windowFilter,
                    maxNodes = maxNodes
                )
                ToolRunOutput(dom)
            }

            "screen_act_tag" -> {
                val svc = ForgeAccessibilityService.ensureConnectedOrSelfHeal()
                    ?: return ToolRunOutput(
                        ForgeAccessibilityService.describeUnavailableMessage(english = false),
                        isError = true
                    )
                val action = str("action") ?: return ToolRunOutput("Отсутствует action", isError = true)
                val tag = (args["tag"] as? JsonPrimitive)?.intOrNull
                val text = str("text")
                val submit = bool("submit", false)
                svc.actOnTag(tagId = tag, action = action, text = text, submit = submit).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка screen_act_tag: ${it.message}", isError = true) }
                )
            }

            "screen_act_batch" -> {
                val svc = ForgeAccessibilityService.ensureConnectedOrSelfHeal()
                    ?: return ToolRunOutput(
                        ForgeAccessibilityService.describeUnavailableMessage(english = false),
                        isError = true
                    )
                val stepsArray: JsonArray = when (val rawSteps = args["steps"]) {
                    is JsonArray -> rawSteps
                    is JsonPrimitive -> {
                        val txt = rawSteps.contentOrNull?.trim().orEmpty()
                        runCatching { LlmHttpFactory.json.parseToJsonElement(txt) as? JsonArray }.getOrNull()
                            ?: return ToolRunOutput("Параметр steps должен быть JSON-массивом объектов шагов.", isError = true)
                    }
                    else -> {
                        val fallbackJson = str("steps_json")?.trim().orEmpty()
                        if (fallbackJson.isEmpty()) {
                            return ToolRunOutput("Отсутствует массив steps для screen_act_batch.", isError = true)
                        }
                        runCatching { LlmHttpFactory.json.parseToJsonElement(fallbackJson) as? JsonArray }.getOrNull()
                            ?: return ToolRunOutput("Некорректный JSON-массив в steps_json.", isError = true)
                    }
                }
                val (ok, report) = svc.executeBatch(
                    steps = stepsArray,
                    stopOnError = bool("stop_on_error", true),
                    returnDom = bool("return_dom", true),
                    includeSelf = bool("include_self", false),
                    onlyInteractive = bool("only_interactive", false),
                    windowFilter = str("window_filter"),
                    maxNodes = int("max_nodes", 220)
                )
                ToolRunOutput(report, isError = !ok)
            }

            "screen_capture" -> {
                val svc = ForgeAccessibilityService.ensureConnectedOrSelfHeal()
                    ?: return ToolRunOutput(
                        ForgeAccessibilityService.describeUnavailableMessage(english = false),
                        isError = true
                    )
                val fmt = str("format") ?: "webp"
                val maxEdge = int("max_edge", 1280)
                val quality = int("quality", 75)
                val includeDom = bool("include_dom", false)
                val attachToChat = bool("attach_to_chat", true)
                svc.captureScreenFast(
                    outDir = workspaceManager.chatFilesDir,
                    format = fmt,
                    maxEdge = maxEdge,
                    quality = quality
                ).fold(
                    onSuccess = { shot ->
                        val att = workspaceManager.buildAttachmentFromLocalFile(shot.file, isFromAssistant = true)
                        val domSuffix = if (includeDom) {
                            "\n\n" + svc.captureWindowsDom(
                                includeSelf = bool("include_self", false),
                                onlyInteractive = bool("only_interactive", false),
                                windowFilter = str("window_filter"),
                                maxNodes = int("max_nodes", 220)
                            )
                        } else ""
                        ToolRunOutput(
                            text = "Снимок экрана получен за ${shot.elapsedMs}ms: ${shot.file.name} (${shot.width}x${shot.height} из ${shot.originalWidth}x${shot.originalHeight}, ${shot.file.length()} байт, ${shot.mimeType}).$domSuffix",
                            outgoingAttachments = if (attachToChat) listOf(att) else emptyList()
                        )
                    },
                    onFailure = { err ->
                        ToolRunOutput("Ошибка screen_capture: ${err.message}", isError = true)
                    }
                )
            }

            "mcp_list_servers" -> {
                val all = mcpManager.getAllServers()
                if (all.isEmpty()) {
                    ToolRunOutput("MCP-серверы ещё не добавлены. Вы можете подключить внешний MCP-сервер через mcp_connect.")
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
                val url = str("url") ?: return ToolRunOutput("Отсутствует url/command для mcp_connect", isError = true)
                val tr = runCatching { McpTransport.valueOf((str("transport") ?: "STREAMABLE_HTTP").uppercase()) }
                    .getOrDefault(McpTransport.STREAMABLE_HTTP)
                val hdrs = str("headers_json") ?: "{}"
                mcpManager.connectDynamicServer(name, url, tr, hdrs).fold(
                    onSuccess = { (srv, tools) ->
                        ToolRunOutput(
                            text = buildString {
                                appendLine("Успешно подключён MCP-сервер '${srv.name}' (${srv.endpoint})!")
                                appendLine("Найдено инструментов: ${tools.size}")
                                tools.forEach { t ->
                                    val exposed = McpClientManager.buildExposedToolName(srv.name, t.name)
                                    appendLine("  - $exposed (оригинал: ${t.name}): ${t.description.take(140)}")
                                }
                            },
                            mcpServerName = srv.name,
                            refreshToolSchemas = true
                        )
                    },
                    onFailure = { ToolRunOutput("Не удалось подключиться к MCP: ${it.message}", isError = true) }
                )
            }

            "mcp_call_tool" -> {
                val srvQuery = str("server") ?: return ToolRunOutput("Отсутствует server", isError = true)
                val tName = str("tool_name") ?: return ToolRunOutput("Отсутствует tool_name", isError = true)
                val aJson = str("arguments_json") ?: "{}"
                val target = mcpManager.getAllServers().firstOrNull {
                    it.id == srvQuery || it.name.equals(srvQuery, ignoreCase = true)
                } ?: return ToolRunOutput("MCP-сервер '$srvQuery' не найден", isError = true)
                val res = mcpManager.callTool(target.id, tName, aJson)
                val imgs = res.savedFilePaths.mapNotNull { path ->
                    val f = File(path)
                    if (f.exists()) workspaceManager.buildAttachmentFromLocalFile(f, isFromAssistant = true) else null
                }
                val note = if (imgs.isNotEmpty()) "\n[Вложения добавлены в чат: ${imgs.size}]" else ""
                ToolRunOutput(
                    res.text + note,
                    isError = res.isError,
                    mcpServerName = target.name,
                    outgoingAttachments = imgs
                )
            }

            "mcp_read_resource" -> {
                val srvQuery = str("server") ?: return ToolRunOutput("Отсутствует server", isError = true)
                val uri = str("uri") ?: return ToolRunOutput("Отсутствует uri", isError = true)
                val target = mcpManager.getAllServers().firstOrNull {
                    it.id == srvQuery || it.name.equals(srvQuery, ignoreCase = true)
                } ?: return ToolRunOutput("MCP-сервер '$srvQuery' не найден", isError = true)
                mcpManager.readResource(target.id, uri).fold(
                    onSuccess = { ToolRunOutput(it, mcpServerName = target.name) },
                    onFailure = { ToolRunOutput("Ошибка чтения ресурса MCP: ${it.message}", isError = true) }
                )
            }

            else -> ToolRunOutput("Неизвестный инструмент: $toolName", isError = true)
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
                            if (truncated) "$text\n...[вывод обрезан на 512 КБ]" else text
                        }
                        val exitCode = proc.waitFor()
                        exitCode to readJob.await()
                    }
                }
                if (result == null) {
                    proc.destroyForcibly()
                    -1 to "Команда превысила таймаут ${timeoutSec}s"
                } else {
                    result
                }
            } catch (ce: CancellationException) {
                proc.destroyForcibly()
                throw ce
            } catch (t: Throwable) {
                proc.destroyForcibly()
                -1 to "Ошибка запуска shell: ${t.message}"
            }
        }

    private fun builtInSpecs(): List<LlmToolSpec> = listOf(
        spec(
            "check_environment_status",
            "Проверить доступные среды: Домашнюю папку ИИ ('internal'), Память телефона ('device_storage'), Termux/SSH, SAF папку Android и подключённые MCP-серверы.",
            emptyObjSchema()
        ),
        spec(
            "list_files",
            "Показать список файлов и папок в директории.",
            objSchema(
                mapOf(
                    "path" to strProp("Относительный или абсолютный путь. Оставьте пустым для корня."),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (опционально для target_env='termux_ssh')")
                )
            )
        ),
        spec(
            "search_files",
            "Найти файлы по подстроке имени.",
            objSchema(
                mapOf(
                    "query" to strProp("Часть имени файла или пути"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("query")
            )
        ),
        spec(
            "search_codebase",
            "Полнотекстовый поиск (grep) внутри содержимого файлов проекта.",
            objSchema(
                mapOf(
                    "query" to strProp("Строка или символ для поиска по коду"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("query")
            )
        ),
        spec(
            "file_read",
            "Прочитать текстовый файл, код или PDF по строкам.",
            objSchema(
                mapOf(
                    "path" to strProp("Путь к файлу"),
                    "offset_line" to intProp("Стартовая строка (с 1)"),
                    "limit_lines" to intProp("Сколько строк прочитать (по умолчанию 300)"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("path")
            )
        ),
        spec(
            "file_write",
            "Создать или полностью перезаписать файл. Автоматически сохраняет снэпшот для отката.",
            objSchema(
                mapOf(
                    "path" to strProp("Путь к файлу"),
                    "content" to strProp("Полное содержимое файла"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("path", "content")
            )
        ),
        spec(
            "file_edit",
            "Точечная замена фрагмента old_string на new_string в существующем файле (с генерацией Diff и снэпшотом).",
            objSchema(
                mapOf(
                    "path" to strProp("Путь к файлу"),
                    "old_string" to strProp("Точный исходный фрагмент текста"),
                    "new_string" to strProp("Новый фрагмент текста"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("path", "old_string", "new_string")
            )
        ),
        spec(
            "file_delete",
            "Удалить файл или папку.",
            objSchema(
                mapOf(
                    "path" to strProp("Путь к файлу или папке"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("path")
            )
        ),
        spec(
            "create_folder",
            "Создать директорию (включая вложенные).",
            objSchema(
                mapOf(
                    "path" to strProp("Путь создаваемой папки"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("path")
            )
        ),
        spec(
            "archive_create",
            "Создать ZIP-архив из файла или папки во внутренней рабочей среде ('internal'). Для SSH/Termux используйте run_command с zip.",
            objSchema(
                mapOf(
                    "source_path" to strProp("Путь к исходному файлу или папке (внутри 'internal')"),
                    "archive_path" to strProp("Путь создаваемого .zip (по умолчанию рядом с источником: <имя>.zip)")
                ),
                listOf("source_path")
            )
        ),
        spec(
            "archive_extract",
            "Распаковать ZIP-архив во внутреннюю рабочую среду ('internal'). Для SSH/Termux используйте run_command с unzip.",
            objSchema(
                mapOf(
                    "archive_path" to strProp("Путь к .zip архиву (внутри 'internal')"),
                    "target_dir" to strProp("Папка назначения (по умолчанию: папка с именем архива без .zip)")
                ),
                listOf("archive_path")
            )
        ),
        spec(
            "transfer_file",
            "Перенести или скопировать файл/папку между средами (например между 'internal', 'device_storage', 'termux_ssh' и 'saf').",
            objSchema(
                mapOf(
                    "source_env" to envProp("Исходная среда: 'internal', 'device_storage', 'termux_ssh' или 'saf'"),
                    "source_path" to strProp("Путь исходного файла или папки"),
                    "source_server" to strProp("Исходный SSH-сервер (если source_env='termux_ssh')"),
                    "target_env" to envProp("Целевая среда: 'internal', 'device_storage', 'termux_ssh' или 'saf'"),
                    "target_path" to strProp("Путь назначения"),
                    "target_server" to strProp("Целевой SSH-сервер (если target_env='termux_ssh')"),
                    "move" to boolProp("Переместить с удалением исходника (true = move, false = copy; по умолчанию false)")
                ),
                listOf("source_env", "source_path", "target_env", "target_path")
            )
        ),
        spec(
            "send_file_to_chat",
            "Отправить файл или изображение пользователю прямо в сообщение чата (для просмотра, скачивания или шаринга).",
            objSchema(
                mapOf(
                    "path" to strProp("Путь к файлу в рабочей среде"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)"),
                    "caption" to strProp("Короткое описание файла")
                ),
                listOf("path")
            )
        ),
        spec(
            "run_command",
            "Выполнить shell-команду на SSH-сервере (Termux/SSH) или во встроенном физическом терминале приложения ('internal' работает всегда, даже без SSH).",
            objSchema(
                mapOf(
                    "command" to strProp("Команда bash/sh, например 'ls -la', 'python3 app.py', 'git status'"),
                    "workdir" to strProp("Рабочая директория (опционально)"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (например 'Termux (локальный)' или 'SSH внешний')"),
                    "timeout_seconds" to intProp("Таймаут в секундах (по умолчанию 35)")
                ),
                listOf("command")
            )
        ),
        spec(
            "run_command_async",
            "Запустить долгую команду в фоне (в SSH/Termux или во встроенном физическом shell 'internal') и получить PID + log_file.",
            objSchema(
                mapOf(
                    "command" to strProp("Команда для фонового запуска"),
                    "workdir" to strProp("Рабочая директория"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (опционально)")
                ),
                listOf("command")
            )
        ),
        spec(
            "check_command_status",
            "Проверить живой ли фоновый процесс по PID и прочитать последние строки его лога.",
            objSchema(
                mapOf(
                    "pid" to strProp("PID процесса"),
                    "log_file" to strProp("Путь к лог-файлу"),
                    "lines" to intProp("Количество последних строк (по умолчанию 60)"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (опционально)")
                ),
                listOf("pid", "log_file")
            )
        ),
        spec(
            "kill_command",
            "Принудительно завершить фоновый процесс в Termux/SSH или встроенном shell по PID.",
            objSchema(
                mapOf(
                    "pid" to strProp("PID процесса"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (опционально)")
                ),
                listOf("pid")
            )
        ),
        spec(
            "web_search",
            "Поиск информации в интернете.",
            objSchema(
                mapOf(
                    "query" to strProp("Поисковый запрос"),
                    "count" to intProp("Количество результатов (1..20)")
                ),
                listOf("query")
            )
        ),
        spec(
            "web_scrape",
            "Загрузить и распарсить одну веб-страницу в богатую GFM Markdown-вёрстку (с меню, заголовками, таблицами, ссылками и кодом), чистый текст, ссылки, таблицы или по CSS-селектору.",
            objSchema(
                mapOf(
                    "url" to strProp("Полный URL https://..."),
                    "mode" to strProp("markdown | text | selector | links | tables | html"),
                    "css_selector" to strProp("CSS-селектор для скрапинга (например 'article p', 'table.price')"),
                    "render_js" to boolProp("Использовать реальный WebView для выполнения JavaScript (для SPA)"),
                    "headers_json" to strProp("JSON объект дополнительных заголовков"),
                    "max_chars" to intProp("Максимум символов в ответе")
                ),
                listOf("url")
            )
        ),
        spec(
            "web_scrape_site",
            "СКРАПЕР САЙТОВ В MARKDOWN: параллельно обойти целый сайт/документацию (включая /sitemap.xml и /llms.txt) и выгрузить единую структурную MD-вёрстку всего сайта с картой навигации (INDEX.md), оглавлением заголовков и полным контентом страниц (опционально сохраняя пакет .md файлов на диск).",
            objSchema(
                mapOf(
                    "start_url" to strProp("Стартовый URL сайта или раздела документации (https://...)"),
                    "max_pages" to intProp("Максимум страниц для обхода (1..50, по умолчанию 12)"),
                    "max_depth" to intProp("Глубина переходов по внутренним ссылкам (0..5, по умолчанию 2)"),
                    "concurrency" to intProp("Параллельных потоков загрузки (1..8, по умолчанию 6)"),
                    "same_path_prefix" to boolProp("Оставаться только внутри текущего префикса пути, например /docs/ (по умолчанию false)"),
                    "url_filter" to strProp("Опциональный фильтр/regex для URL страниц"),
                    "include_sitemap" to boolProp("Автоматически подхватывать /sitemap.xml и /llms.txt (по умолчанию true)"),
                    "render_js_first_page" to boolProp("Отрендерить стартовую SPA-страницу через WebView JS (по умолчанию false)"),
                    "headers_json" to strProp("JSON объект дополнительных HTTP-заголовков"),
                    "max_chars_per_page" to intProp("Лимит символов Markdown на одну страницу (по умолчанию 14000)"),
                    "max_chars" to intProp("Общий лимит символов в ответе инструмента (по умолчанию 40000)"),
                    "save_to_path" to strProp("Опциональная папка в рабочей среде для сохранения INDEX.md, FULL_SITE.md и 01_*.md по страницам"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (если save_to_path сохраняется в termux_ssh)")
                ),
                listOf("start_url")
            )
        ),
        spec(
            "browser_open",
            "Открыть указанный URL в фоновом агентном браузере ИИ (отдельный WebView, не мешает обычному браузеру пользователя).",
            objSchema(
                mapOf("url" to strProp("URL веб-страницы")),
                listOf("url")
            )
        ),
        spec(
            "screen_read_windows",
            "Прочитать все открытые на экране Android окна ([W1..Wn]: основное приложение, split-screen, плавающие окна, клавиатура) и превратить их интерфейс и текст в точные теги [#1..#k] без угадывания по скриншоту. Своё плавающее окно ForgeMind автоматически скрыто (если include_self=false).",
            objSchema(
                mapOf(
                    "window_filter" to strProp("Фильтр по пакету или заголовку окна (опционально)"),
                    "only_interactive" to boolProp("Показывать только кликабельные/редактируемые/скроллируемые элементы (по умолчанию false — включает весь видимый текст)"),
                    "include_self" to boolProp("Включать ли окна самого ForgeMind (по умолчанию false)"),
                    "max_nodes" to intProp("Максимум тегов в снимке (по умолчанию 260)")
                )
            )
        ),
        spec(
            "screen_act_tag",
            "Выполнить точное действие по тегу [#N] из последнего вызова screen_read_windows (нажатие, долгое нажатие, ввод/дописывание текста, скролл) или системную кнопку (back, home, recents, notifications).",
            objSchema(
                mapOf(
                    "action" to strProp("click | long_click | set_text | append_text | focus | scroll_forward | scroll_backward | back | home | recents | notifications | quick_settings"),
                    "tag" to intProp("Номер тега N из [#N] (обязателен для всех действий, кроме back/home/recents/notifications/quick_settings)"),
                    "text" to strProp("Текст для ввода при action='set_text' или 'append_text'"),
                    "submit" to boolProp("Отправить ли Enter/IME action после ввода текста (по умолчанию false)")
                ),
                listOf("action")
            )
        ),
        spec(
            "screen_act_batch",
            "Выполнить цепочку действий на экране Android в реальном времени за один запрос без сетевых пауз между шагами (клики по [#tag], по тексту label или координатам x,y, мгновенная вставка Unicode/русского текста без клавиатуры, свайпы, ожидание появления надписи wait_text, запуск приложения open_app и возврат свежего Window-Tag DOM).",
            objSchema(
                mapOf(
                    "steps" to buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put(
                            "description",
                            JsonPrimitive("Массив шагов: каждый объект содержит action ('click'|'long_click'|'set_text'|'append_text'|'tap_xy'|'swipe'|'scroll_forward'|'scroll_backward'|'wait_ms'|'wait_text'|'open_app'|'read_windows'|'back'|'home'|'recents'|'notifications'|'quick_settings') и опциональные поля: tag (int), label (string), text (string), submit (bool), x/y или x1/y1/x2/y2 (number), ms/timeout_ms/duration_ms (int), click_when_found (bool), package_name (string).")
                        )
                        put(
                            "items",
                            objSchema(
                                mapOf(
                                    "action" to strProp("Тип действия шага"),
                                    "tag" to intProp("Номер тега [#N]"),
                                    "label" to strProp("Подстрока видимой надписи/описания элемента для поиска на лету"),
                                    "text" to strProp("Текст для set_text / append_text / wait_text"),
                                    "submit" to boolProp("Отправить Enter после set_text"),
                                    "x" to intProp("Координата X для tap_xy / click"),
                                    "y" to intProp("Координата Y для tap_xy / click"),
                                    "x1" to intProp("Начальный X для swipe"),
                                    "y1" to intProp("Начальный Y для swipe"),
                                    "x2" to intProp("Конечный X для swipe"),
                                    "y2" to intProp("Конечный Y для swipe"),
                                    "ms" to intProp("Пауза в мс для wait_ms"),
                                    "timeout_ms" to intProp("Таймаут ожидания текста в мс для wait_text"),
                                    "click_when_found" to boolProp("Сразу нажать найденный элемент при wait_text"),
                                    "package_name" to strProp("Имя пакета Android-приложения для open_app")
                                ),
                                listOf("action")
                            )
                        )
                    },
                    "stop_on_error" to boolProp("Остановить выполнение пакета при первой ошибке шага (по умолчанию true)"),
                    "return_dom" to boolProp("Сразу вернуть обновлённый снимок Window-Tag DOM после завершения шагов (по умолчанию true)"),
                    "include_self" to boolProp("Включать ли окна самого ForgeMind в итоговый DOM (по умолчанию false)"),
                    "only_interactive" to boolProp("В итоговом DOM оставить только интерактивные элементы (по умолчанию false)"),
                    "window_filter" to strProp("Фильтр пакета/заголовка для итогового DOM (опционально)"),
                    "max_nodes" to intProp("Максимум тегов в итоговом DOM (по умолчанию 220)")
                ),
                listOf("steps")
            )
        ),
        spec(
            "screen_capture",
            "Сделать мгновенный аппаратный снимок экрана Android по запросу (Android 11+, без фоновой записи кадров) и вернуть изображение (webp/jpeg/png) + при необходимости свежий Window-Tag DOM.",
            objSchema(
                mapOf(
                    "format" to buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("webp"))
                            add(JsonPrimitive("jpeg"))
                            add(JsonPrimitive("png"))
                        })
                        put("description", JsonPrimitive("Формат кадра: webp (по умолчанию, компактный и чёткий), jpeg или png"))
                    },
                    "max_edge" to intProp("Максимальная длинная сторона в пикселях (128..4096, по умолчанию 1280)"),
                    "quality" to intProp("Качество сжатия 10..95 (по умолчанию 75)"),
                    "include_dom" to boolProp("Приложить ли одновременно текстовое дерево окон Window-Tag DOM (по умолчанию false)"),
                    "attach_to_chat" to boolProp("Прикрепить ли изображение в сообщение чата / ответ MCP image (по умолчанию true)")
                )
            )
        ),
        spec(
            "mcp_list_servers",
            "Показать список настроенных внешних MCP-серверов (Model Context Protocol) и их инструментов.",
            emptyObjSchema()
        ),
        spec(
            "mcp_connect",
            "Подключиться к внешнему MCP-серверу (Streamable HTTP, SSE или SSH Stdio), получить список его инструментов и сделать их доступными для вызова.",
            objSchema(
                mapOf(
                    "name" to strProp("Короткое имя сервера"),
                    "url" to strProp("HTTP/SSE URL сервера или команда для SSH_STDIO"),
                    "transport" to strProp("STREAMABLE_HTTP | HTTP_SSE | SSH_STDIO"),
                    "headers_json" to strProp("JSON заголовки авторизации, например {\"Authorization\":\"Bearer ...\"}")
                ),
                listOf("name", "url")
            )
        ),
        spec(
            "mcp_call_tool",
            "Вызвать инструмент на подключённом MCP-сервере напрямую.",
            objSchema(
                mapOf(
                    "server" to strProp("Имя или ID MCP-сервера"),
                    "tool_name" to strProp("Имя инструмента на MCP-сервере"),
                    "arguments_json" to strProp("JSON-строка с аргументами инструмента")
                ),
                listOf("server", "tool_name")
            )
        ),
        spec(
            "mcp_read_resource",
            "Прочитать ресурс MCP-сервера по URI.",
            objSchema(
                mapOf(
                    "server" to strProp("Имя или ID MCP-сервера"),
                    "uri" to strProp("URI ресурса MCP")
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

    private fun envProp(
        desc: String = "Среда выполнения: 'internal' (Домашняя ИИ), 'device_storage' (Память телефона), 'termux_ssh' (Termux/SSH) или 'saf' (Папка Android). По умолчанию используется активная среда чата."
    ) = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", buildJsonArray {
            add(JsonPrimitive("internal"))
            add(JsonPrimitive("device_storage"))
            add(JsonPrimitive("termux_ssh"))
            add(JsonPrimitive("saf"))
        })
        put("description", JsonPrimitive(desc))
    }

    companion object {
        val SHELL_AND_WRITE_HOST_TOOLS = setOf(
            "run_command",
            "run_command_async",
            "kill_command",
            "terminal_send",
            "terminal_open_tab",
            "file_write",
            "file_edit",
            "file_move",
            "file_delete",
            "create_folder",
            "archive_create",
            "archive_extract",
            "transfer_file",
            "snapshot_restore",
            "browser_agent_eval_js",
            "mcp_connect"
        )

        private val DANGEROUS_CMD_REGEX = Regex(
            """(?:^|[;&|\s/])rm(?:\s|$)|\bmkfs\b|(?:^|[;&|\s/])dd\s|git\s+reset\s+--hard|chmod\s+(?:-[a-zA-Z]*\s+)?777\b|kill\s+-9\b|:\(\)\s*\{|\b(?:shutdown|reboot)\b""",
            RegexOption.IGNORE_CASE
        )

        fun hasBrowserEvalJsStep(argsJson: String): Boolean {
            if (argsJson.isBlank()) return false
            val obj = runCatching { LlmHttpFactory.json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
                ?: return argsJson.contains("eval_js", ignoreCase = true)
            val steps = obj["steps"] as? JsonArray ?: return false
            return steps.any { item ->
                val act = ((item as? JsonObject)?.get("action") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
                act == "eval_js" || act == "js"
            }
        }

        fun hasNonBlankSavePath(argsJson: String): Boolean {
            if (argsJson.isBlank()) return false
            val obj = runCatching { LlmHttpFactory.json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
                ?: return argsJson.contains("save_to_path", ignoreCase = true)
            val savePath = (obj["save_to_path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            return savePath.isNotEmpty()
        }

        private val SMALL_OR_LOCAL_MODEL_REGEX = Regex(
            """(?:^|[-_:/\s])(0\.5|1|1\.5|1\.7|2|3|3\.8|4|6|7|8|9|11|12|13|14|15|16|20|22|24|27|30|32)b(?:[-_:/\s.]|$)""",
            RegexOption.IGNORE_CASE
        )

        val COMPACT_CORE_TOOL_NAMES: Set<String> = linkedSetOf(
            "run_command",
            "run_command_async",
            "check_command_status",
            "kill_command",
            "list_files",
            "search_files",
            "search_codebase",
            "file_read",
            "file_write",
            "file_edit",
            "file_move",
            "file_delete",
            "create_folder",
            "transfer_file",
            "send_file_to_chat",
            "web_search",
            "web_scrape",
            "browser_agent_navigate",
            "browser_agent_inspect",
            "browser_agent_act",
            "browser_agent_markdown",
            "screen_read_windows",
            "screen_act_tag",
            "screen_capture",
            "mcp_list_servers",
            "mcp_call_tool"
        )

        fun shouldUseCompactModelMode(config: ProviderConfig): Boolean {
            if (config.contextWindow <= 49_152) return true
            if (config.effectiveCategory() == ProviderCategory.LOCAL_LAN) return true
            val m = config.modelId.lowercase()
            if (SMALL_OR_LOCAL_MODEL_REGEX.containsMatchIn(m)) return true
            if (m.contains("heretic") || m.contains("uncensored") || m.contains("abliterated") ||
                m.contains("gguf") || m.contains("kobold") || m.contains("awq") || m.contains("exl2")
            ) {
                return true
            }
            return false
        }

        fun compactToolSpec(spec: LlmToolSpec): LlmToolSpec {
            val shortDesc = spec.description
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .trim()
                .let { firstLine ->
                    val dot = firstLine.indexOf(". ")
                    if (dot in 15..110) firstLine.substring(0, dot + 1) else firstLine.take(120)
                }
            val props = spec.parametersSchema["properties"] as? JsonObject
            val compactedSchema = if (props == null) {
                spec.parametersSchema
            } else {
                buildJsonObject {
                    for ((k, v) in spec.parametersSchema) {
                        if (k == "properties") {
                            put("properties", buildJsonObject {
                                for ((propName, propVal) in props) {
                                    val pObj = propVal as? JsonObject
                                    if (pObj == null) {
                                        put(propName, propVal)
                                    } else {
                                        put(propName, buildJsonObject {
                                            for ((pk, pv) in pObj) {
                                                if (pk == "description") {
                                                    val pd = (pv as? JsonPrimitive)?.contentOrNull.orEmpty()
                                                    put("description", JsonPrimitive(pd.take(72)))
                                                } else {
                                                    put(pk, pv)
                                                }
                                            }
                                        })
                                    }
                                }
                            })
                        } else {
                            put(k, v)
                        }
                    }
                }
            }
            return LlmToolSpec(
                name = spec.name,
                description = shortDesc,
                parametersSchema = compactedSchema
            )
        }
    }
}
