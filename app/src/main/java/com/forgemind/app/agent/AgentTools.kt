package com.forgemind.app.agent

import okhttp3.Request
import okhttp3.OkHttpClient
import com.forgemind.app.accessibility.ForgeAccessibilityService
import com.forgemind.app.bridge.TerminalSessionManager
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.bridge.AsyncCommandLaunchUncertain
import com.forgemind.app.bridge.AsyncCommandStart
import com.forgemind.app.bridge.LocalAsyncJob
import com.forgemind.app.bridge.CommandTaskRecord
import com.forgemind.app.bridge.CommandTaskService
import com.forgemind.app.bridge.CommandTaskState
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.encodeToString
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
    private val semanticSearch: com.forgemind.app.data.memory.LocalSemanticSearch? = null,
    private val db: ForgeMindDatabase? = null,
    private val settingsRepo: SettingsRepository? = null,
    private val ttsManager: TtsManager? = null,
    private val onOpenBrowserUrl: (String) -> Unit = {},
    private val onNavigateSection: (String) -> Boolean = { false },
    private val onOpenEditorTab: (WorkspaceEnvType, String, String, String?) -> String = { _, path, _, _ -> path },
    private val onListEditorTabs: () -> List<EditorTabSnapshot> = { emptyList() },
    private val onPreviewPortChanged: (Int) -> Unit = {},
    private val onCompactContext: suspend (String) -> Result<String> = { Result.failure(IllegalStateException("Unavailable")) },
    private val onManageSchedule: suspend (String, JsonObject) -> String = { _, _ -> error("Schedules unavailable") }
) {
    private val dynamicMcpLookup = ConcurrentHashMap<String, Pair<String, String>>() // exposedName -> (serverId, realToolName)
    private val dynamicSchemas = ConcurrentHashMap<String, JsonObject>()
    private val dynamicServerIdentities = ConcurrentHashMap<String, String>()
    private val commandTasks by lazy { CommandTaskService(termuxBridge) }

    private fun startedTask(start: AsyncCommandStart): ToolRunOutput = startedTask(
        start.pid, start.logFile, start.workDir) { commandTasks.track(start) }

    private fun startedTask(start: LocalAsyncJob): ToolRunOutput = startedTask(
        start.id, start.logFile.absolutePath, start.workDir) { commandTasks.track(start) }

    private fun startedTask(pid: String, logFile: String, workDir: String, save: () -> CommandTaskRecord): ToolRunOutput {
        val saved = runCatching(save)
        return ToolRunOutput(buildString {
            appendLine("Command launched; completion has not been checked.")
            saved.onSuccess { task ->
                appendLine("task_id: ${task.id}")
                appendLine("backend: ${task.backend}; server_id: ${task.serverId ?: "local"}")
                appendLine("Use check_command_status(task_id='${task.id}') or kill_command(task_id='${task.id}').")
            }.onFailure {
                appendLine("Task metadata could not be saved: ${it.message}. Do NOT launch the command again; use the legacy PID/log below.")
            }
            appendLine("Workdir: $workDir")
            appendLine("PID: $pid")
            append("Log file: $logFile")
        })
    }

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /** "path (обязательный), offset_line?, target_env? = internal|device_storage|termux_ssh|saf" — for error hints. */
    fun paramsHint(toolName: String): String? {
        val spec = (builtInSpecs() + appLeverTools.specs()).firstOrNull { it.name == toolName } ?: return null
        val props = spec.parametersSchema["properties"] as? JsonObject ?: return null
        if (props.isEmpty()) return "без параметров"
        val required = (spec.parametersSchema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()
        return props.entries.joinToString(", ") { (name, schema) ->
            val enumVals = ((schema as? JsonObject)?.get("enum") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            buildString {
                append(name)
                if (name in required) append(" (обязательный)") else append('?')
                if (!enumVals.isNullOrEmpty()) append(" = ").append(enumVals.joinToString("|"))
            }
        }
    }

    private fun withDiffForModel(message: String, diff: String?, dryRun: Boolean): String =
        if (dryRun && !diff.isNullOrBlank()) "$message\n\n$diff" else message

    private fun knownToolNames(): List<String> =
        (builtInSpecs() + appLeverTools.specs()).map { it.name } + dynamicMcpLookup.keys

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
        onCompactContext = onCompactContext,
        onManageSchedule = onManageSchedule
    )

    /** Bounded catalog from current built-in specs only; MCP servers are queried strictly on demand. */
    internal suspend fun capabilitiesCatalog(area: String = "", query: String = "", name: String = ""): String =
        ToolCatalog.render(builtInSpecs() + appLeverTools.specs(), emptyMap(), area, query, name)

    internal suspend fun searchCapabilities(area: String, query: String, mode: String, limit: Int, name: String = ""): String {
        require(mode in setOf("auto", "semantic", "text")) { "mode: auto, semantic или text" }
        if (name.isNotBlank() || query.isBlank()) return capabilitiesCatalog(area, "", name) // Overview, area or one tool.
        val engine = semanticSearch
        if (mode == "text" || mode == "auto" && engine?.ready?.value != true) {
            val result = LlmHttpFactory.json.parseToJsonElement(capabilitiesCatalog(area,query)).jsonObject
            return JsonObject(result + mapOf("search_mode" to JsonPrimitive("text"), "search_note" to JsonPrimitive(if(mode == "text") "По словам" else "Модель локального поиска не загружена; использован поиск по словам"))).toString()
        }
        check(engine != null && engine.ready.value) { "Скачайте модель: меню чата → Память → Скачать модель поиска" }
        // Only ForgeMind's own tools: MCP servers describe their tools through MCP.
        val entries = ToolCatalog.entries(builtInSpecs() + appLeverTools.specs()).filter { entry ->
            area.isBlank() || entry.area.equals(area, true)
        }
        val byId = entries.associateBy { it.id }
        val ranked = engine.rank(query, entries.map { com.forgemind.app.data.memory.SemanticDocument(it.id, it.name + "\n" + it.description) })
            .take(limit.coerceIn(1, 100))
        val result = LlmHttpFactory.json.parseToJsonElement(ToolCatalog.ranked(ranked.map { byId.getValue(it.id) to it.score }, entries.size)).jsonObject
        return JsonObject(result + mapOf(
            "search_mode" to JsonPrimitive("semantic_local"),
            "usage" to JsonPrimitive("Результаты ранжированы по локальным эмбеддингам. Все инструменты: capabilities_catalog({}); полная схема: name=<tool>.")
        )).toString()
    }

    @Volatile private var probeCache: Pair<Long, String>? = null

    /** One read-only pass over both shells: which commands exist in PATH. Cached for 10 minutes. */
    private suspend fun probeCommands(refresh: Boolean): String {
        val now = System.currentTimeMillis()
        val cached = probeCache
        if (!refresh && cached != null && now - cached.first < 10 * 60_000L) {
            return cached.second + "\n(кэш ${(now - cached.first) / 1000} с назад; refresh=true — проверить заново)"
        }
        if (termuxBridge.hasUnifiedTermux()) {
            val probe = termuxBridge.runCommand(CAPABILITY_PROBE, timeoutSeconds = 20, serverHint = "default_termux")
            val text = "internal / Termux: общая среда, без SSH.\n" +
                if (probe.error != null) "Ошибка проверки: ${probe.error}" else formatCapabilityProbe(probe.stdout)
            probeCache = now to text
            return text
        }
        val internal = terminalManager?.embeddedAiShell?.let { shell ->
            runCatching { shell.execute(CAPABILITY_PROBE, timeoutSeconds = 15, sourceTag = "check_environment_status") }.getOrNull()
        }
        val profile = termuxBridge.getAllProfiles().filter { it.enabled }.let { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
        val termux = profile?.let { p ->
            runCatching { termuxBridge.runCommand(CAPABILITY_PROBE, null, 20, serverHint = p.id) }.getOrNull()
        }
        val text = buildString {
            appendLine("Команды в средах (проверено сейчас, только поиск в PATH):")
            appendLine("- internal: " + when {
                internal == null -> "встроенный shell недоступен"
                internal.timedOut -> "проверка не уложилась в 15 с"
                else -> formatCapabilityProbe(internal.stdout)
            })
            appendLine("- termux_ssh" + (profile?.let { " (server='${it.id}')" } ?: "") + ": " + when {
                profile == null -> "нет включённого SSH-профиля"
                termux == null -> "не удалось выполнить проверку"
                termux.stdout.isBlank() && termux.error != null -> "ошибка: ${termux.error.take(200)}"
                else -> formatCapabilityProbe(termux.stdout)
            })
            append("Есть команда — ещё не значит, что действие сработает: например, am broadcast возвращает 0 и без получателя. Подтверждай результат по эффекту.")
        }
        probeCache = now to text
        return text
    }

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
        val bridgeReady = termuxBridge.isTermuxInstalled() && termuxBridge.remote.installed() && termuxBridge.remote.available(forCommands = true)
        val sshDesc = if (profiles.isEmpty()) {
            "нет настроенных SSH-профилей"
        } else {
            profiles.joinToString("\n  ") { p ->
                val state = when {
                    !p.enabled -> "выключен в Настройках"
                    bridgeReady && termuxBridge.usesRemoteBridge(p.id) -> "прямой мост Termux подключён; SSH для этой среды не требуется"
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
            bridgeReady -> "прямой мост Termux подключён: PTY, tmux, команды и файлы; данные приложения находятся в отдельном UID"
            termuxBridge.isTermuxInstalled() && termuxBridge.hasTermuxPermission() -> "Termux установлен, RUN_COMMAND разрешён"
            termuxBridge.isTermuxInstalled() -> "Termux установлен (работа по SSH)"
            else -> "Termux не обнаружен"
        }
        val aiShellState = if (termuxBridge.hasUnifiedTermux()) null else terminalManager?.embeddedAiShell?.ensureStarted()
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
            if (termuxBridge.hasUnifiedTermux()) {
                appendLine("  • Команды internal выполняются в Bash Termux, без SSH, в общей живой сессии forge. Рабочая папка — Домашняя ИИ; cd/export сохраняются между командами проекта. Python, пакеты и HOME общие с Termux. Каждая команда имеет отдельное окно; Стоп завершает только её. Для фонового процесса используй run_command_async. /system/bin/sh доступен отдельно как резервная ручная вкладка.")
            } else {
                appendLine("  • Встроенный физический терминал ИИ (постоянный /system/bin/sh внутри приложения): $aiShellDesc. Сохраняет cd/export, живой вывод — во вкладке «Терминал -> Агент (Live)».")
            }
            appendLine("• 'device_storage' (Память телефона): $phoneDir [$phoneAccess].")
            appendLine("• 'saf' (Папка Android): $safDesc.")
            appendLine("• 'termux_ssh' (Linux/SSH, $termuxDesc; указывай параметр server='<id>'):")
            appendLine("  $sshDesc")
            if (bridgeReady) appendLine("  Для локального Termux используй target_env='termux_ssh' и id локального профиля из списка. " +
                "Команды идут через мост без SSH. Среда internal остаётся приватной средой приложения. " +
                "Удаление файлов Termux требует подтверждения владельца. При потере ответа не запускай команду повторно: проверь сохранённую задачу.")
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
            appendLine("• Window-Tag DOM и управление экраном Android (screen_read_windows / screen_act_tag / screen_act_batch / screen_capture): ${if (a11yReady) "АКТИВЕН — читает окна [W1..Wn] и теги [#1..#k] за миллисекунды, выполняет пакеты шагов без пауз и делает быстрый снимок по запросу" else "выключен в Спецвозможностях Android (пользователь может включить в Настройки -> Агент -> Window-Tag DOM)"}.")
            append("• Встроенные рычаги (пассивный фон, не мешают UI пользователя): мультиоконный Агентный браузер ($browserTabsSummary), HTTP preview (${if (previewPort > 0) "http://127.0.0.1:$previewPort/" else "выкл"}), буфер редактора кода, фоновый терминал, снэпшоты, история чатов, сжатие контекста и TTS.")
        }.trimEnd()
    }

    fun buildHostServerToolSpecs(exposeShellAndWrite: Boolean = true): List<LlmToolSpec> {
        val all = builtInSpecs() + appLeverTools.specs()
        if (exposeShellAndWrite) return all
        return all.filterNot { it.name in SHELL_AND_WRITE_HOST_TOOLS }
    }

    suspend fun assistantProfile(id: String?): com.forgemind.app.data.db.AssistantProfileEntity? =
        id?.let { db?.assistantDao()?.get(it) }

    suspend fun historyLimit(session: com.forgemind.app.data.db.ChatSessionEntity, providerLimit: Int): Int =
        com.forgemind.app.data.memory.AssistantPolicy.historyLimit(session.historyMessageLimit, assistantProfile(session.assistantId)?.historyMessageLimit, providerLimit)

    suspend fun memoryRequestContext(session: com.forgemind.app.data.db.ChatSessionEntity, provider: com.forgemind.app.data.model.ProviderConfig): String {
        val settings = settingsRepo?.agentSettings?.value ?: return ""
        if (!settings.memoryEnabled) return ""
        val database = db ?: return ""
        return com.forgemind.app.data.memory.MemoryRepository(database).requestBlock(
            com.forgemind.app.data.memory.MemoryContext(session.id, session.folderId, provider.providerId, provider.modelId, session.assistantId), settings.memoryBudgetTokens)
    }

    suspend fun buildToolSpecs(agentSettings: AgentSettings): List<LlmToolSpec> {
        if (!agentSettings.enableTools) return emptyList()

        val specs = mutableListOf<LlmToolSpec>()
        dynamicMcpLookup.clear()
        dynamicSchemas.clear()
        dynamicServerIdentities.clear()

        // Strictly on-demand MCP: do NOT touch, probe, or preload MCP server configs or schemas here.
        // Models discover MCP servers and their tools only when calling mcp_list_servers / mcp_call_tool.
        specs += builtInSpecs()
        specs += appLeverTools.specs()
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

    fun isDestructive(name: String, arguments: String): Boolean =
        isDestructiveOrCommandTool(name, arguments) || name in setOf("snapshot_restore", "file_move")

    suspend fun requiresUnrememberedApproval(name: String, arguments: String, settings: AgentSettings, env: WorkspaceEnvType): Boolean {
        val args = runCatching { LlmHttpFactory.json.parseToJsonElement(arguments).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        if (settings.blockedTools.any { it.equals(name, true) } || isDangerousCommand(name, arguments) ||
            TermuxDeleteApproval.required(name, args, env)) return true
        if (name == "snapshot_restore") {
            val id = (args["snapshot_id"] as? JsonPrimitive)?.contentOrNull ?: return true
            if (workspaceManager.snapshotDeletesTermux(id)) return true
        }
        if (name == "schedule_manage" && (args["action"] as? JsonPrimitive)?.contentOrNull != "list") return true
        // Generic server names are resolved dynamically; remember the explicit exposed MCP tool instead.
        if (name == "mcp_call_tool") return true
        return name == "mcp_connect" && (args["transport"] as? JsonPrimitive)?.contentOrNull == "SSH_STDIO"
    }

    fun approvalFingerprint(name: String, arguments: String = "{}"): String {
        val schema = dynamicSchemas[name] ?: (builtInSpecs() + appLeverTools.specs()).firstOrNull { it.name == name }?.parametersSchema
        val lookup = dynamicMcpLookup[name]
        val args = runCatching { LlmHttpFactory.json.parseToJsonElement(arguments).jsonObject }.getOrNull()
        val target = if (name == "mcp_call_tool") listOf(args?.get("server"), args?.get("tool_name")).joinToString() else lookup.toString()
        val executionTarget=listOf("target_env","source_env","server","ssh_profile_id","session_id","tab_id").map { args?.get(it) }.joinToString()
        val nestedSchema = if(name=="mcp_call_tool") dynamicMcpLookup.entries.firstOrNull { (_,value) ->
            value.first == (args?.get("server") as? JsonPrimitive)?.contentOrNull && value.second == (args?.get("tool_name") as? JsonPrimitive)?.contentOrNull
        }?.key?.let { dynamicSchemas[it] } else null
        val identity=lookup?.first?.let {dynamicServerIdentities[it]}.orEmpty()
        val bytes = (name + target + executionTarget + identity + schema.toString() + nestedSchema.toString() + com.forgemind.app.BuildConfig.VERSION_CODE).toByteArray()
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun validateArguments(name: String, arguments: String): String? {
        val schema = dynamicSchemas[name] ?: (builtInSpecs() + appLeverTools.specs()).firstOrNull { it.name == name }?.parametersSchema
        return ToolArgumentValidator.validate(arguments, schema)
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

    suspend fun requiresExecutionApproval(toolName: String, argsJson: String, settings: AgentSettings, defaultEnv: WorkspaceEnvType): Boolean {
        if (requiresApproval(toolName, argsJson, settings, defaultEnv)) return true
        if (toolName.trim() == "snapshot_restore") {
            val args = runCatching { LlmHttpFactory.json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
            val id = (args?.get("snapshot_id") as? JsonPrimitive)?.contentOrNull ?: return false
            return workspaceManager.snapshotDeletesTermux(id)
        }
        return false
    }

    fun requiresApproval(toolName: String, argsJson: String, settings: AgentSettings,
                         defaultEnv: WorkspaceEnvType = WorkspaceEnvType.INTERNAL): Boolean {
        val normalized = toolName.trim()
        val arguments = runCatching { LlmHttpFactory.json.parseToJsonElement(argsJson).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        if (TermuxDeleteApproval.required(normalized, arguments, defaultEnv)) return true
        if (normalized == "schedule_manage") return (arguments["action"] as? JsonPrimitive)?.contentOrNull != "list"

        // 1. Черный список пользователя ВСЕГДА требует одобрения (даже при полном автопилоте)
        if (settings.blockedTools.any { it.equals(normalized, ignoreCase = true) }) {
            return true
        }

        // Proposals only stage a draft. The memory screen always requires the owner's final decision.
        if (normalized == "memory_search" || normalized == "memory_propose") return false

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
                "transfer_file", "archive_create", "archive_extract", "snapshot_restore",
                "web_download", "file_patch", "file_append"
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
                "preview_server_control", "app_navigate",
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
        searchSettings: SearchSettings,
        userConfirmed: Boolean = false
    ): ToolRunOutput {
        val rawArgsText = argsJson.ifBlank { "{}" }
        val args = runCatching {
            val el = LlmHttpFactory.json.parseToJsonElement(rawArgsText)
            el as? JsonObject ?: error("Аргументы должны быть JSON-объектом")
        }.getOrElse { err ->
            return ToolRunOutput(
                "Ошибка разбора JSON-аргументов для '$toolName': ${err.message}. Передайте корректный JSON-объект." +
                    (paramsHint(toolName)?.let { "\nПараметры $toolName: $it" } ?: ""),
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

            appLeverTools.executeIfHandled(toolName, args, defaultEnv, internalProject, sessionId, userConfirmed)?.let {
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
                searchSettings = searchSettings,
                userConfirmed = userConfirmed
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
        searchSettings: SearchSettings,
        userConfirmed: Boolean
    ): ToolRunOutput {

        return when (toolName) {
            "memory_search", "memory_propose" -> {
                check((settingsRepo?.agentSettings?.value ?: agentSettings).memoryEnabled) { "Память выключена пользователем" }
                val database = db ?: error("Хранилище памяти недоступно")
                val repository = com.forgemind.app.data.memory.MemoryRepository(database)
                val chat = database.sessionDao().getById(sessionId) ?: error("Нужен существующий чат")
                // A user can switch the picker while a response is in flight. Attribute the tool to its actual model.
                val origin = database.messageDao().getMessage(sessionId, messageId)?.takeIf { it.role == "ASSISTANT" }
                val providerId = origin?.providerId ?: chat.providerId
                val modelId = (origin?.modelId ?: chat.modelId).ifBlank { settingsRepo?.getProviderConfig(providerId)?.modelId.orEmpty() }
                val context = repository.context(sessionId, provider = providerId, model = modelId).let { if (origin != null) it.copy(assistantId = origin.assistantId) else it }
                if (toolName == "memory_search") {
                    val query = str("query").orEmpty()
                    val mode = str("mode") ?: "auto"
                    require(mode in setOf("auto","semantic","text")) { "mode: auto, semantic или text" }
                    if (str("note_id").isNullOrBlank() && query.isNotBlank() && (mode == "semantic" || mode == "auto" && semanticSearch?.ready?.value == true)) {
                        val search = semanticSearch ?: error("Локальный поиск недоступен")
                        check(search.ready.value) { "Скачайте модель: меню чата → Память → Скачать модель поиска" }
                        val (total, notes) = repository.semanticSearch(context,query,search,int("limit",20),int("offset",0))
                        ToolRunOutput(buildJsonObject {
                            put("search_mode","semantic_local");put("total_candidates",total)
                            put("next_offset", (int("offset",0) + notes.size).takeIf { it < total }?.let(::JsonPrimitive) ?: JsonNull)
                            put("notes",buildJsonArray { notes.forEach { (note, score) -> add(buildJsonObject {
                                put("id",note.id);put("scope",note.scopeType);put("title",note.title);put("content",note.content);put("similarity",score)
                            }) } })
                        }.toString())
                    } else {
                        val note = if (mode == "auto" && query.isNotBlank()) "search_mode=text; локальная модель не загружена\n" else "search_mode=text\n"
                        ToolRunOutput(note + repository.search(context,query,str("note_id"),int("limit",20),int("offset",0)))
                    }
                } else {
                    val proposal = repository.propose(context, str("action") ?: "create", str("scope"), str("title"), str("content"), str("note_id"))
                    ToolRunOutput("Предложение памяти ${proposal.id} ожидает решения пользователя в меню чата → Память. Область: ${proposal.scopeType}. Это ещё не сохранённая память. Не повторяй предложение и не утверждай, что запись уже принята.")
                }
            }

            "capabilities_catalog" -> {
                ToolRunOutput(searchCapabilities(str("area")?.trim().orEmpty(), str("query")?.trim().orEmpty(), str("mode") ?: "auto", int("limit",12), str("name")?.trim().orEmpty()))
            }

            "check_environment_status" -> {
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
                    val screenOn = ForgeAccessibilityService.ensureConnectedOrSelfHeal() != null
                    appendLine("- Управление экраном (Window-Tag DOM): ${if (screenOn) "включено" else "выключено в Спецвозможностях Android; пользователь включает кнопкой «Включить» в листе «Подключения»"}")
                    appendLine("- MCP-серверы: запрашиваются по требованию через `mcp_list_servers` (без предзагрузки в контекст).")
                }
                val probed = if (bool("probe_commands", false)) "\n\n" + probeCommands(bool("refresh", false))
                    else "\n\nКоманды в средах не проверялись: probe_commands=true одним вызовом проверит python3, pip, curl, git, tmux, setsid, termux-api, rish и am."
                ToolRunOutput(text.trimEnd() + probed)
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
                val ctx = int("context_lines", 0).coerceIn(0, 20)
                workspaceManager.searchCodebase(env, q, internalProject, sshServer = server, contextLines = ctx).fold(
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
                    sshServer = server,
                    dryRun = bool("dry_run", false)
                ).fold(
                    onSuccess = { res ->
                        ToolRunOutput(withDiffForModel(res.message, res.diffText, bool("dry_run", false)), diffText = res.diffText, snapshotId = res.snapshotId)
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
                    sshServer = server,
                    dryRun = bool("dry_run", false)
                ).fold(
                    onSuccess = { res ->
                        ToolRunOutput(withDiffForModel(res.message, res.diffText, bool("dry_run", false)), diffText = res.diffText, snapshotId = res.snapshotId)
                    },
                    onFailure = { ToolRunOutput("Ошибка file_edit: ${it.message}", isError = true) }
                )
            }

            "file_patch" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Отсутствует path", isError = true)
                val diff = str("diff") ?: return ToolRunOutput("Отсутствует diff (unified diff с заголовками @@ -a,b +c,d @@)", isError = true)
                val dry = bool("dry_run", false)
                workspaceManager.patchFile(
                    env = env,
                    path = path,
                    unifiedDiff = diff,
                    expectedSha256 = str("expected_sha256"),
                    dryRun = dry,
                    sessionId = sessionId,
                    messageId = messageId,
                    saveSnapshot = agentSettings.autoSnapshotBeforeEdit,
                    internalProject = internalProject,
                    sshServer = str("server")
                ).fold(
                    onSuccess = { res ->
                        ToolRunOutput(withDiffForModel(res.message, res.diffText, dry), diffText = res.diffText, snapshotId = res.snapshotId)
                    },
                    onFailure = { ToolRunOutput("Ошибка file_patch: ${it.message}", isError = true) }
                )
            }

            "file_append" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Отсутствует path", isError = true)
                val text = str("text") ?: return ToolRunOutput("Отсутствует text", isError = true)
                workspaceManager.appendFile(
                    env = env,
                    path = path,
                    text = text,
                    ensureNewline = bool("ensure_newline", true),
                    internalProject = internalProject,
                    sshServer = str("server")
                ).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка file_append: ${it.message}", isError = true) }
                )
            }

            "file_delete" -> {
                val env = resolveEnv()
                val path = str("path") ?: return ToolRunOutput("Отсутствует path", isError = true)
                val server = str("server")
                workspaceManager.deleteFile(env, path, internalProject, sshServer = server, userConfirmed = userConfirmed).fold(
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
                    move = moveFlag,
                    userConfirmed = userConfirmed
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
                        val sha = runCatching { workspaceManager.sha256Hex(File(att.localPath).readBytes()) }.getOrNull()
                        ToolRunOutput(
                            text = ("Файл '${att.name}' прикреплён в чат: ${att.sizeBytes} байт, ${att.mimeType}" +
                                (sha?.let { ", sha256 $it" } ?: "") + ". $caption").trim(),
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
                if (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank() && termuxBridge.hasUnifiedTermux()) {
                    terminalManager?.watchSharedTermux(true)
                    val res = termuxBridge.runWorkspaceCommand(cmd, defaultInternalDir,
                        effectiveShellWorkDir?.absolutePath, timeout,
                        terminalManager?.sharedCommandEnvironment(defaultInternalDir).orEmpty())
                    return ToolRunOutput(buildString {
                        appendLine("Env: Termux · forge (internal workspace, no SSH)")
                        appendLine("Exit Code: ${res.exitCode}" + if (res.timedOut) " (TIMEOUT)" else "")
                        res.error?.let { appendLine("Error: $it") }
                        if (res.stdout.isNotBlank()) appendLine("--- stdout ---\n${res.stdout.take(16_000)}")
                        if (res.stderr.isNotBlank()) appendLine("--- stderr ---\n${res.stderr.take(8_000)}")
                    }.trimEnd(), isError = res.error != null || res.exitCode != 0)
                }
                terminalManager?.watchSharedTermux(
                    (env == WorkspaceEnvType.TERMUX_SSH || !server.isNullOrBlank()) && termuxBridge.usesSharedTerminal(server))
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
                    if (res.error != null && res.fallbackAllowed && allowFallback && !res.timedOut && !termuxBridge.usesSharedTerminal(server) && termuxBridge.isLocalSshTarget(server)) {
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
                        sourceLabel = res.route ?: if (termuxBridge.usesSharedTerminal(server)) "Termux Live" else "SSH: ${server ?: termuxBridge.activeProfile.name}",
                        workDir = wdRaw,
                        command = cmd,
                        output = combinedOut.take(12_000),
                        exitCode = res.exitCode
                    )
                    val outText = buildString {
                        res.route?.let { appendLine("Route: $it") }
                        if (res.outputTruncated) appendLine("[Output truncated]")
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
                if (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank() && termuxBridge.hasUnifiedTermux()) {
                    terminalManager?.watchSharedTermux(true)
                    return termuxBridge.runWorkspaceCommandAsync(cmd, defaultInternalDir, wd,
                        terminalManager?.sharedCommandEnvironment(defaultInternalDir).orEmpty()).fold(
                        onSuccess = { startedTask(it) },
                        onFailure = { ToolRunOutput("Ошибка run_command_async: ${it.message}", isError = true) })
                }
                if (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank() && shell != null) {
                    shell.startAsync(cmd, wd?.let { File(it) } ?: defaultInternalDir).fold(
                        onSuccess = { startedTask(it) },
                        onFailure = { ToolRunOutput("Ошибка run_command_async (internal): ${it.message}", isError = true) }
                    )
                } else {
                    termuxBridge.runCommandAsync(cmd, wd, serverHint = server).fold(
                        onSuccess = { startedTask(it) },
                        onFailure = { err ->
                            if (err is CancellationException) throw err
                            if (err !is AsyncCommandLaunchUncertain && bool("fallback_to_embedded", true) && !termuxBridge.usesSharedTerminal(server) && termuxBridge.isLocalSshTarget(server) && shell != null) {
                                shell.startAsync(cmd, wd?.let { File(it) } ?: defaultInternalDir).fold(
                                    onSuccess = { startedTask(it).let { output ->
                                        output.copy(text = "[SSH unavailable -> embedded shell]\n" + output.text)
                                    } },
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
                str("task_id")?.let { id ->
                    val status = commandTasks.controller.status(id, int("lines", 60))
                    return ToolRunOutput(LlmHttpFactory.json.encodeToString(status),
                        isError = status.state in setOf(CommandTaskState.UNKNOWN, CommandTaskState.LOST))
                }
                val pid = str("pid") ?: return ToolRunOutput("Укажи task_id из run_command_async/list_command_tasks либо старые pid и log_file.", isError = true)
                val logFile = str("log_file") ?: return ToolRunOutput("Для старого pid нужен log_file. Для новой задачи достаточно task_id.", isError = true)
                val lines = int("lines", 60)
                val server = str("server")
                val env = resolveEnv()
                val shell = terminalManager?.embeddedAiShell
                if (!pid.startsWith("tmux:") && (pid.startsWith("local_") || (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank())) && shell != null) {
                    ToolRunOutput(shell.checkAsyncStatus(pid, logFile, lines))
                } else {
                    val statusText = termuxBridge.checkCommandStatus(pid, logFile, lines, serverHint = server)
                    ToolRunOutput(statusText, isError = statusText.startsWith("Ошибка") || statusText.startsWith("Status check failed") || statusText.startsWith("__LOST__"))
                }
            }

            "kill_command" -> {
                str("task_id")?.let { id ->
                    val status = commandTasks.controller.cancel(id)
                    return ToolRunOutput(LlmHttpFactory.json.encodeToString(status),
                        isError = status.state in setOf(CommandTaskState.UNKNOWN, CommandTaskState.LOST, CommandTaskState.RUNNING))
                }
                val pid = str("pid") ?: return ToolRunOutput("Укажи task_id из run_command_async/list_command_tasks либо старый pid с исходной средой.", isError = true)
                val server = str("server")
                val env = resolveEnv()
                val shell = terminalManager?.embeddedAiShell
                if (!pid.startsWith("tmux:") && (pid.startsWith("local_") || (env != WorkspaceEnvType.TERMUX_SSH && server.isNullOrBlank())) && shell != null) {
                    shell.killAsync(pid).fold(
                        onSuccess = { ToolRunOutput(it) },
                        onFailure = { ToolRunOutput("Ошибка kill_command: ${it.message}", isError = true) }
                    )
                } else {
                    val res = termuxBridge.killCommand(pid, serverHint = server)
                    ToolRunOutput(res.stdout.ifBlank { res.stderr.ifBlank { res.error ?: "Завершено" } }, isError = res.error != null || res.exitCode != 0)
                }
            }

            "list_command_tasks" -> {
                val limit = int("limit", 20).coerceIn(1, 100)
                val offset = int("offset", 0).coerceAtLeast(0)
                val listing = withContext(Dispatchers.IO) { commandTasks.store.list(limit, offset) }
                val result = buildJsonObject {
                    put("has_more", JsonPrimitive(listing.hasMore))
                    if (listing.hasMore) put("next_offset", JsonPrimitive(offset.toLong() + listing.tasks.size))
                    put("unreadable_records", JsonPrimitive(listing.unreadableRecords))
                    put("tasks", buildJsonArray {
                        listing.tasks.forEach { task -> add(buildJsonObject {
                            put("task_id", JsonPrimitive(task.id))
                            put("backend", JsonPrimitive(task.backend.name))
                            put("workdir", JsonPrimitive(task.workDir))
                            put("created_at_ms", JsonPrimitive(task.createdAt))
                            task.serverId?.let { put("server_id", JsonPrimitive(it)) }
                            put("cancelled", JsonPrimitive(task.cancelled))
                        }) }
                    })
                    put("note", JsonPrimitive("Saved tasks, including completed ones. Use check_command_status or wait_command_tasks for current state. Existing jobs launched before task IDs are not imported."))
                }
                ToolRunOutput(result.toString())
            }

            "wait_command_tasks" -> {
                val ids = (args["task_ids"] as? JsonArray)?.map {
                    (it as? JsonPrimitive)?.contentOrNull ?: error("task_ids must contain strings")
                } ?: return ToolRunOutput("Provide task_ids: an array of 1 to 8 task IDs", isError = true)
                val mode = str("mode") ?: "all"
                require(mode == "all" || mode == "any") { "mode must be all or any" }
                val result = commandTasks.controller.waitFor(ids,
                    int("timeout_seconds", 10).coerceIn(0, 30) * 1000L,
                    any = mode == "any", lines = int("lines", 30))
                ToolRunOutput(LlmHttpFactory.json.encodeToString(result), isError = result.reason == "unavailable")
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
                    val srvFilter = str("server")?.trim().orEmpty()
                    val query = str("query")?.trim().orEmpty()
                    val exactTool = str("tool_name")?.trim().orEmpty()
                    if (srvFilter.isBlank() && query.isBlank() && exactTool.isBlank()) {
                        val out = buildString {
                            appendLine("Настроенные MCP-серверы (${all.size}):")
                            for (srv in all) {
                                val cachedCount = mcpManager.decodeTools(srv.cachedToolsJson).size
                                val state = if (srv.enabled) "enabled" else "disabled"
                                appendLine("- ${srv.name} (id=${srv.id}, $state, transport=${srv.transport}, tools=$cachedCount)")
                                appendLine("  endpoint: ${srv.endpoint}")
                                if (!srv.lastError.isNullOrBlank()) appendLine("  lastError: ${srv.lastError}")
                            }
                            append("Чтобы получить инструкции и инструменты нужного сервера, вызови mcp_list_servers(server='<имя>', query?, tool_name?), а для запуска — mcp_call_tool(server, tool_name, arguments_json).")
                        }
                        ToolRunOutput(out)
                    } else {
                        val targets = if (srvFilter.isNotBlank()) {
                            val match = all.firstOrNull {
                                it.id == srvFilter || it.name.equals(srvFilter, ignoreCase = true)
                            } ?: all.firstOrNull {
                                it.name.contains(srvFilter, ignoreCase = true)
                            }
                            if (match == null) {
                                return ToolRunOutput("MCP-сервер '$srvFilter' не найден. Доступны: ${all.joinToString { it.name }}", isError = true)
                            }
                            listOf(match)
                        } else {
                            all.filter { it.enabled }.ifEmpty { all }
                        }
                        val out = buildString {
                            for (srv in targets) {
                                var currentSrv = srv
                                var tools = mcpManager.decodeTools(currentSrv.cachedToolsJson)
                                if (tools.isEmpty() && currentSrv.enabled && srvFilter.isNotBlank()) {
                                    val discovered = mcpManager.connectAndDiscover(currentSrv.id).getOrNull()
                                    if (discovered != null) {
                                        tools = discovered
                                        currentSrv = mcpManager.getAllServers().firstOrNull { it.id == currentSrv.id } ?: currentSrv
                                    }
                                }
                                appendLine("=== MCP: ${currentSrv.name} (id=${currentSrv.id}, enabled=${currentSrv.enabled}, tools=${tools.size}) ===")
                                if (!currentSrv.lastError.isNullOrBlank()) appendLine("lastError: ${currentSrv.lastError}")
                                if (exactTool.isNotBlank()) {
                                    val found = tools.firstOrNull { it.name.equals(exactTool, ignoreCase = true) }
                                        ?: tools.firstOrNull { it.name.contains(exactTool, ignoreCase = true) }
                                    if (found == null) {
                                        appendLine("Инструмент '$exactTool' не найден на сервере ${currentSrv.name}.")
                                    } else {
                                        appendLine("tool: ${found.name}")
                                        if (found.description.isNotBlank()) appendLine("description: ${found.description}")
                                        appendLine("inputSchema: ${found.inputSchemaJson}")
                                        appendLine("Вызов: mcp_call_tool(server=\"${currentSrv.name}\", tool_name=\"${found.name}\", arguments_json=\"{...}\")")
                                    }
                                    continue
                                }
                                if (query.isBlank()) {
                                    val instr = mcpManager.resolveServerInstructions(currentSrv).trim()
                                    if (instr.isNotBlank()) {
                                        val clipped = if (instr.length > 6000) instr.take(6000) + "\n... [обрезано до 6000 из ${instr.length} симв.]" else instr
                                        appendLine("instructions:")
                                        appendLine(clipped)
                                    }
                                    val insight = mcpManager.analyzeServerCatalog(currentSrv, tools)
                                    appendLine("catalog: ${insight.summaryLine}")
                                }
                                val filtered = if (query.isNotBlank()) {
                                    val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                                    tools.filter { t ->
                                        val hay = "${t.name} ${t.description}".lowercase()
                                        terms.all { hay.contains(it) }
                                    }
                                } else if (tools.size > 60) {
                                    val nonTasker = tools.filterNot { it.name.contains("tasker_ag_", ignoreCase = true) }
                                    if (nonTasker.isNotEmpty()) nonTasker else tools
                                } else {
                                    tools
                                }
                                val hiddenTasker = if (query.isBlank() && tools.size > 60) tools.size - filtered.size else 0
                                val maxShow = if (query.isNotBlank()) 40 else 55
                                appendLine("tools (показано ${minOf(filtered.size, maxShow)} из ${filtered.size}):")
                                filtered.take(maxShow).forEach { t ->
                                    val schemaEl = runCatching { LlmHttpFactory.json.parseToJsonElement(t.inputSchemaJson) }.getOrNull()
                                    val sig = ToolCatalog.signature(schemaEl)
                                    val desc = ToolCatalog.summary(t.description, 95)
                                    appendLine("- ${t.name}($sig)${if (desc.isNotBlank()) ": $desc" else ""}")
                                }
                                if (filtered.size > maxShow) {
                                    appendLine("... ещё ${filtered.size - maxShow} инструментов (уточни через query='...')")
                                }
                                if (hiddenTasker > 0) {
                                    appendLine("... плюс $hiddenTasker однотипных tasker_AG_* задач (используй query='tasker' или конкретное слово)")
                                }
                            }
                            append("Для полной JSON-схемы одного инструмента вызови mcp_list_servers(server='...', tool_name='...'). Для вызова — mcp_call_tool(server, tool_name, arguments_json).")
                        }
                        ToolRunOutput(out, mcpServerName = targets.singleOrNull()?.name)
                    }
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

            "web_download" -> {
                val url = str("url")?.trim().orEmpty()
                if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                    return ToolRunOutput("web_download: нужен url, начинающийся с http:// или https://", isError = true)
                }
                val env = resolveEnv()
                val server = str("server")
                val maxMb = int("max_mb", 50).coerceIn(1, 100)
                val limitBytes = maxMb * 1024L * 1024L
                val nameFromUrl = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "download_${System.currentTimeMillis()}" }
                val rawPath = str("path")?.trim().orEmpty()
                val path = when {
                    rawPath.isBlank() -> "downloads/$nameFromUrl"
                    rawPath.endsWith("/") -> rawPath + nameFromUrl
                    else -> rawPath
                }
                val t0 = System.currentTimeMillis()
                runCatching {
                    val tmp = withContext(Dispatchers.IO) {
                        val req = Request.Builder().url(url).header("User-Agent", "ForgeMind-Agent/0.2 (Android)").build()
                        downloadClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) error("HTTP ${resp.code} для $url")
                            val body = resp.body ?: error("пустое тело ответа")
                            if (body.contentLength() > limitBytes) {
                                error("файл ${body.contentLength() / 1024 / 1024} МБ больше лимита max_mb=$maxMb")
                            }
                            val out = File.createTempFile("fm_dl_", ".part", workspaceManager.chatFilesDir)
                            body.byteStream().use { input ->
                                out.outputStream().use { o ->
                                    val buf = ByteArray(64 * 1024)
                                    var total = 0L
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        total += n
                                        if (total > limitBytes) {
                                            o.close()
                                            out.delete()
                                            error("загрузка больше лимита max_mb=$maxMb, прервана")
                                        }
                                        o.write(buf, 0, n)
                                    }
                                }
                            }
                            out
                        }
                    }
                    try {
                        val size = tmp.length()
                        workspaceManager.writeRawBytes(env, path, tmp.readBytes(), internalProject, server).getOrThrow()
                        size
                    } finally {
                        tmp.delete()
                    }
                }.fold(
                    onSuccess = { size ->
                        ToolRunOutput("Скачано: $url → ${env.id}:$path ($size байт, ${System.currentTimeMillis() - t0} мс). Дальше: file_read, archive_extract или run_command в той же среде.")
                    },
                    onFailure = { ToolRunOutput("Ошибка web_download: ${it.message}", isError = true) }
                )
            }

            "notify_user" -> {
                val text = str("text")?.trim().orEmpty()
                if (text.isBlank()) return ToolRunOutput("notify_user: нужен text", isError = true)
                val level = str("level")?.lowercase()?.takeIf { it in setOf("info", "success", "warning", "error") } ?: "info"
                val shown = workspaceManager.postAgentNotification(str("title")?.trim().orEmpty(), text, level)
                if (shown) {
                    ToolRunOutput("Уведомление показано пользователю ($level).")
                } else {
                    ToolRunOutput("Уведомление не показано: нет разрешения на уведомления. Сообщи это пользователю текстом в ответе.", isError = true)
                }
            }

            "report_tool_issue" -> {
                val tool = str("tool")?.trim().orEmpty()
                val problem = str("problem")?.trim().orEmpty()
                if (tool.isBlank() || problem.isBlank()) {
                    return ToolRunOutput("report_tool_issue: нужны tool и problem", isError = true)
                }
                val expected = str("expected")?.trim().orEmpty()
                val file = File(workspaceManager.getInternalProjectDir(internalProject), "agent_feedback.md")
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
                withContext(Dispatchers.IO) {
                    val isNew = !file.exists()
                    file.appendText(buildString {
                        if (isNew) append("# Отзывы агента об инструментах ForgeMind\n\n")
                        append("## $stamp · $tool\n")
                        append("- Проблема: $problem\n")
                        if (expected.isNotBlank()) append("- Ожидалось: $expected\n")
                        append("\n")
                    })
                }
                ToolRunOutput("Записано в agent_feedback.md (Домашняя ИИ): $tool. Разработчик увидит это в «Файлах ИИ». Продолжай задачу обходным путём, если он есть.")
            }

            else -> {
                val similar = ToolResultFormatter.closestNames(toolName, knownToolNames())
                ToolRunOutput(
                    buildString {
                        append("Неизвестный инструмент: $toolName. Такого инструмента нет.\n\n")
                        append(ToolResultFormatter.FIX_MARKER).append(' ')
                        if (similar.isNotEmpty()) append("Похожие существующие инструменты: ${similar.joinToString(", ")}. ")
                        append("Вызывай только инструменты из списка доступных.")
                    },
                    isError = true
                )
            }
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
        spec("memory_search", "Читать подтверждённую включённую память: только этот чат, его ассистент и папка, выбранная модель у этого провайдера и общие записи. query — поиск по смыслу локальными эмбеддингами, если модель загружена; mode=text — подстрока без учёта регистра; note_id — полный текст одной доступной записи. limit 1–100, offset — страницы. Предложения и чужие области не возвращаются.",
            objSchema(mapOf("query" to strProp("Что нужно вспомнить; можно другими словами"), "mode" to strProp("auto, semantic (локальная модель) или text (подстрока)"), "note_id" to strProp("ID записи"), "limit" to intProp("Размер страницы, по умолчанию 20"), "offset" to intProp("Смещение, по умолчанию 0")), emptyList())),
        spec("memory_propose", "Предложить create/update/delete записи памяти. Приложение НЕ применяет её до явного подтверждения пользователем на экране памяти, даже в автопилоте. create: scope CHAT (по умолчанию), ASSISTANT, FOLDER, MODEL или GLOBAL; ключ области определяется текущим чатом автоматически. update/delete: note_id, область не меняется. title/content обязательны для create/update. Не отправляй одно предложение повторно.",
            objSchema(mapOf("action" to strProp("create, update или delete"), "scope" to strProp("CHAT, ASSISTANT, FOLDER, MODEL или GLOBAL"), "title" to strProp("Название до 120 символов"), "content" to strProp("Полный текст до 8000 символов"), "note_id" to strProp("ID изменяемой или удаляемой записи")), emptyList())),
        spec(
            "web_download",
            "Скачать файл по прямой ссылке (http/https) в рабочую среду: архив, скрипт, картинку, PDF. Встроенной оболочке не нужен curl. Лимит max_mb (по умолчанию 50, максимум 100). После скачивания: file_read, archive_extract или run_command в той же среде.",
            objSchema(
                mapOf(
                    "url" to strProp("Прямая ссылка на файл, http:// или https://"),
                    "path" to strProp("Куда сохранить: путь к файлу или папке с '/' на конце. По умолчанию downloads/<имя из ссылки>"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя SSH-профиля, если target_env='termux_ssh'"),
                    "max_mb" to intProp("Предел размера в мегабайтах (1–100, по умолчанию 50)")
                ),
                listOf("url")
            )
        ),
        spec(
            "notify_user",
            "Показать пользователю системное уведомление Android, когда он, возможно, не смотрит в чат: долгая задача закончилась, нужна его помощь, что-то сломалось. Не дублируй им обычный ответ.",
            objSchema(
                mapOf(
                    "text" to strProp("Текст уведомления, коротко"),
                    "title" to strProp("Заголовок (необязательно)"),
                    "level" to buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("info")); add(JsonPrimitive("success"))
                            add(JsonPrimitive("warning")); add(JsonPrimitive("error"))
                        })
                        put("description", JsonPrimitive("Важность: info, success, warning, error"))
                    }
                ),
                listOf("text")
            )
        ),
        spec(
            "report_tool_issue",
            "Сообщить разработчику ForgeMind, что инструмент мешает работать: падает, отдаёт пустое или непонятное, не хватает параметра. Запись попадает в agent_feedback.md в «Домашней ИИ». Используй, когда наткнулся на проблему инструмента, и продолжай задачу обходным путём.",
            objSchema(
                mapOf(
                    "tool" to strProp("Имя инструмента"),
                    "problem" to strProp("Что пошло не так, с конкретикой"),
                    "expected" to strProp("Как должно было работать (необязательно)")
                ),
                listOf("tool", "problem")
            )
        ),
        spec(
            "capabilities_catalog",
            "Весь функционал приложения ForgeMind одним вызовом: capabilities_catalog({}) перечисляет все встроенные инструменты по областям с назначением и называет подключённые MCP-серверы. Инструменты MCP-серверов описывают сами серверы через MCP. area — подробно одна область с параметрами; name — полное описание и JSON-схема одного встроенного инструмента; query — поиск (mode=semantic по смыслу, нужна загруженная локальная модель). Ответ всегда компактный. Состояние сред — check_environment_status.",
            objSchema(
                mapOf(
                    "area" to strProp("Область: files, terminal, browser, screen, chat, editor, mcp, app, environment"),
                    "name" to strProp("Точное имя встроенного инструмента: полное описание и JSON-схема"),
                    "query" to strProp("Задача или смысл искомого инструмента"),
                    "mode" to strProp("auto (локальная модель, если загружена, иначе слова), semantic или text"),
                    "limit" to intProp("Число результатов поиска по смыслу, 1–100, по умолчанию 12")
                ),
                emptyList()
            )
        ),
        spec(
            "check_environment_status",
            "Карта сред одним вызовом: Домашняя папка ИИ ('internal'), Память телефона ('device_storage'), Termux/SSH, SAF, MCP-серверы, управление экраном, MCP-сервер ForgeMind. С probe_commands=true ещё проверяет, какие команды есть во встроенной среде и в Termux (python3, pip, curl, git, tmux, setsid, termux-api, rish, am). Вызывай так один раз перед задачей с командами вместо десятка пробных вызовов. Проверка кэшируется на 10 минут.",
            objSchema(
                mapOf(
                    "probe_commands" to boolProp("Проверить наличие команд во встроенной среде и в Termux"),
                    "refresh" to boolProp("Проверить заново, не брать кэш")
                ),
                emptyList()
            )
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
            "Полнотекстовый поиск (grep) внутри содержимого файлов проекта. С context_lines сразу показывает строки вокруг совпадения — не нужен отдельный file_read.",
            objSchema(
                mapOf(
                    "query" to strProp("Строка или символ для поиска по коду"),
                    "context_lines" to intProp("Сколько строк контекста до и после совпадения (0–20, по умолчанию 0)"),
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
            "Создать или полностью перезаписать файл. Автоматически сохраняет снэпшот для отката. Для правок существующего файла лучше file_edit или file_patch; для дописывания — file_append.",
            objSchema(
                mapOf(
                    "path" to strProp("Путь к файлу"),
                    "content" to strProp("Полное содержимое файла"),
                    "dry_run" to boolProp("true — ничего не записывать, вернуть unified diff будущего изменения"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("path", "content")
            )
        ),
        spec(
            "file_edit",
            "Точечная замена фрагмента old_string на new_string в существующем файле (с Diff и снэпшотом). Прощает разницу в переводах строк и отступах; если фрагмент не найден, покажет ближайший похожий с номерами строк. Для правок из нескольких мест сразу — file_patch.",
            objSchema(
                mapOf(
                    "path" to strProp("Путь к файлу"),
                    "old_string" to strProp("Исходный фрагмент текста (лучше скопировать из file_read)"),
                    "new_string" to strProp("Новый фрагмент текста"),
                    "dry_run" to boolProp("true — ничего не записывать, вернуть unified diff"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("path", "old_string", "new_string")
            )
        ),
        spec(
            "file_patch",
            "Применить unified diff к одному текстовому файлу: несколько правок за один вызов без перезаписи всего файла. Хунки ищутся рядом с указанной строкой, затем по всему файлу, с допуском по отступам. expected_sha256 (из file_info) защищает от правки устаревшей версии. dry_run показывает итоговый diff без записи. Снэпшот для отката сохраняется.",
            objSchema(
                mapOf(
                    "path" to strProp("Путь к файлу"),
                    "diff" to strProp("Unified diff для этого файла: строки '@@ -a,b +c,d @@', затем ' ' контекст, '-' удалить, '+' добавить. Заголовки ---/+++ необязательны"),
                    "expected_sha256" to strProp("SHA-256 текущего содержимого (из file_info); если файл изменился — ошибка вместо порчи"),
                    "dry_run" to boolProp("true — только проверить и вернуть итоговый diff"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("path", "diff")
            )
        ),
        spec(
            "file_append",
            "Дописать текст в конец файла (логи, заметки, JSONL) без чтения и перезаписи всего файла. Создаёт файл и папки, если их нет.",
            objSchema(
                mapOf(
                    "path" to strProp("Путь к файлу"),
                    "text" to strProp("Что дописать"),
                    "ensure_newline" to boolProp("Вставить перевод строки, если файл не кончается им (по умолчанию true)"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("path", "text")
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
            "Выполнить команду в рабочей среде. В плагине internal использует общий Bash Termux без SSH и сохраняет cd/export. В обычной версии internal использует Android shell. Для удалённого SSH укажи server.",
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
            "Запустить команду в фоне и получить сохраняемый task_id для статуса, ожидания и остановки. Среда запуска закрепляется за задачей. Возврат ID ещё не означает завершения команды.",
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
            "Статус и вывод фоновой задачи по task_id; среда и путь лога восстановятся автоматически. Старые вызовы pid + log_file + target_env/server также поддерживаются.",
            objSchema(
                mapOf(
                    "task_id" to strProp("ID из run_command_async или list_command_tasks; достаточно одного этого ID"),
                    "pid" to strProp("PID процесса"),
                    "log_file" to strProp("Путь к лог-файлу"),
                    "lines" to intProp("Количество последних строк (по умолчанию 60)"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (опционально)")
                ),
                emptyList()
            )
        ),
        spec(
            "kill_command",
            "Остановить сохранённую задачу по task_id, проверив владельца процесса. Завершённые задачи не останавливаются повторно. Старый вариант pid + target_env/server поддерживается отдельно.",
            objSchema(
                mapOf(
                    "task_id" to strProp("ID задачи; путь лога и текущую среду указывать не нужно"),
                    "pid" to strProp("PID процесса"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (опционально)")
                ),
                emptyList()
            )
        ),
        spec(
            "list_command_tasks",
            "Восстановить список сохранённых фоновых задач после перезапуска приложения. Список включает завершённые задачи; текущий статус читается отдельно по task_id.",
            objSchema(mapOf(
                "limit" to intProp("Число задач на странице: по умолчанию 20, максимум 100"),
                "offset" to intProp("Смещение страницы: по умолчанию 0; для продолжения используй next_offset")
            ))
        ),
        spec(
            "wait_command_tasks",
            "Подождать завершения группы из 1–8 задач (mode=all) или первой из них (mode=any). Ожидание до 30 секунд; timeout_seconds=0 только читает статус. Истечение ожидания НЕ останавливает команды. UNKNOWN не считается успешным завершением.",
            objSchema(mapOf(
                "task_ids" to buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("items", strProp("ID сохранённой задачи"))
                    put("minItems", JsonPrimitive(1)); put("maxItems", JsonPrimitive(8))
                },
                "mode" to strProp("all (по умолчанию) или any"),
                "timeout_seconds" to intProp("0–30, по умолчанию 10"),
                "lines" to intProp("Строк вывода на задачу: по умолчанию 30, максимум 100")
            ), listOf("task_ids"))
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
            "Показать подключённые MCP-серверы; при указании server — загрузить по запросу его инструкции и список инструментов, с query — найти нужные инструменты, с tool_name — получить полную JSON-схему конкретного инструмента.",
            objSchema(
                mapOf(
                    "server" to strProp("Имя или ID конкретного MCP-сервера для загрузки его инструкций и инструментов (необязательно)"),
                    "query" to strProp("Фильтр/поиск по имени или описанию инструментов MCP (необязательно)"),
                    "tool_name" to strProp("Точное имя инструмента на сервере для получения его полной JSON-схемы (необязательно)")
                )
            )
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
        /** Areas of built-in tools, in display order. */
        val AREA_TITLES: Map<String, String> = linkedMapOf(
            "files" to "файлы во всех средах: чтение, правка, перенос, архивы, снимки",
            "terminal" to "команды в internal и Termux, фоновые задачи, вкладки терминала",
            "browser" to "агентный браузер, поиск и скачивание из интернета",
            "screen" to "управление экраном Android через метки окон",
            "chat" to "чаты, история, сжатие контекста",
            "memory" to "подтверждённая память чата, ассистента, папки, модели и всех чатов",
            "editor" to "редактор кода и локальный preview",
            "mcp" to "внешние MCP-серверы: подключение и вызов их инструментов",
            "app" to "уведомления, голос, навигация по приложению, отзывы разработчику",
            "environment" to "карта сред и этот каталог"
        )

        fun toolArea(name: String): String = when {
            name.startsWith("memory_") -> "memory"
            name == "check_environment_status" || name == "capabilities_catalog" -> "environment"
            name.startsWith("browser_") || name.startsWith("web_") -> "browser"
            name.startsWith("screen_") -> "screen"
            name.startsWith("terminal_") ||
                name in setOf("run_command", "run_command_async", "check_command_status", "kill_command", "list_command_tasks", "wait_command_tasks") -> "terminal"
            name.startsWith("file_") || name.startsWith("archive_") || name.startsWith("snapshot_") ||
                name in setOf("list_files", "search_files", "search_codebase", "create_folder", "transfer_file", "send_file_to_chat") -> "files"
            name.startsWith("chat_") || name == "context_compact" -> "chat"
            name.startsWith("editor_") || name == "preview_server_control" -> "editor"
            name.startsWith("mcp_") -> "mcp"
            name in setOf("notify_user", "report_tool_issue", "voice_speak", "app_navigate") -> "app"
            else -> "other"
        }

        /** Read-only: prints name=yes/no for each command, then uid and the Python version. */
        const val CAPABILITY_PROBE = "for c in python3 pip curl wget git ssh tmux setsid termux-battery-status rish am; do " +
            "if command -v \"\$c\" >/dev/null 2>&1; then echo \"\$c=yes\"; else echo \"\$c=no\"; fi; done; " +
            "echo \"uid=\$(id -u)\"; if command -v python3 >/dev/null 2>&1; then echo \"python=\$(python3 --version 2>&1)\"; fi"

        /** "name=yes/no" lines from [CAPABILITY_PROBE] → one readable line. */
        fun formatCapabilityProbe(stdout: String): String {
            val pairs = stdout.lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toList()
            if (pairs.isEmpty()) return "нет вывода"
            val flags = pairs.filter { it.second == "yes" || it.second == "no" }
            val info = pairs.filter { it.second != "yes" && it.second != "no" }
            val have = flags.filter { it.second == "yes" }.joinToString(", ") { it.first }.ifBlank { "—" }
            val missing = flags.filter { it.second == "no" }.joinToString(", ") { it.first }.ifBlank { "—" }
            val extra = info.joinToString("; ") { "${it.first}=${it.second}" }
            return "есть: $have | нет: $missing" + if (extra.isNotBlank()) " | $extra" else ""
        }

        val SHELL_AND_WRITE_HOST_TOOLS = setOf(
            "memory_propose",
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
            "mcp_connect",
            "web_download",
            "file_patch",
            "file_append"
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
            "memory_search", "memory_propose",
            "capabilities_catalog",
            "run_command",
            "run_command_async",
            "check_command_status",
            "kill_command",
            "list_command_tasks",
            "wait_command_tasks",
            "list_files",
            "search_files",
            "search_codebase",
            "file_read",
            "file_write",
            "file_edit",
            "file_patch",
            "file_append",
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
            if (config.contextWindow >= 64_000) return false
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
