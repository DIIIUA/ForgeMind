package com.forgemind.app.agent

import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.McpTransport
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.llm.LlmToolSpec
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.web.WebScraperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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
    private val onOpenBrowserUrl: (String) -> Unit = {}
) {
    private val dynamicMcpLookup = mutableMapOf<String, Pair<String, String>>() // exposedName -> (serverId, realToolName)

    suspend fun describeConnectedEnvironments(
        internalProject: String = "default",
        defaultEnv: WorkspaceEnvType = WorkspaceEnvType.INTERNAL
    ): String {
        val internalDir = workspaceManager.getInternalProjectDir(internalProject).absolutePath
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
        val mcpDesc = if (mcpAll.isEmpty()) {
            "внешние MCP-серверы не добавлены (настраиваются в Настройки -> MCP)"
        } else {
            val toolsMap = mcpManager.getEnabledToolsMap()
            val enabledLines = mcpEnabled.joinToString("; ") { s ->
                val list = toolsMap[s].orEmpty()
                val insight = mcpManager.analyzeServerCatalog(s, list)
                "${s.name} [${s.transport}, ${insight.summaryLine}]"
            }
            val disabledNote = if (mcpDisabled.isNotEmpty()) {
                " | отключены вручную (${mcpDisabled.size}): ${mcpDisabled.joinToString(", ") { it.name }}"
            } else ""
            (enabledLines.ifBlank { "активных MCP-серверов сейчас нет" }) + disabledNote
        }
        return buildString {
            appendLine("=== ГДЕ ТЫ НАХОДИШЬСЯ И ЧТО ГДЕ ЛЕЖИТ ===")
            appendLine("• Среда по умолчанию в этом чате: '${defaultEnv.id}' (${defaultEnv.label}).")
            appendLine("• 'internal' (Домашняя ИИ): $internalDir")
            appendLine("  Твоя приватная папка внутри Android-приложения. Сюда складывай создаваемые скрипты/файлы/заметки; пользователь видит её во вкладке «Файлы ИИ -> Домашняя ИИ» и через «+» в чате. Чтобы отдать файл в чат — используй send_file_to_chat.")
            appendLine("  run_command с env='internal' запускает базовый Android sh в этой папке (без apt/pkg; для Linux/Python/Git нужен Termux или SSH).")
            appendLine("• 'saf' (Папка Android): $safDesc.")
            appendLine("• 'termux_ssh' (Linux/SSH, $termuxDesc; указывай параметр server='<id>'):")
            appendLine("  $sshDesc")
            append("• MCP-серверы: $mcpDesc.")
        }.trimEnd()
    }

    suspend fun buildToolSpecs(agentSettings: AgentSettings): List<LlmToolSpec> {
        if (!agentSettings.enableTools) return emptyList()

        val specs = mutableListOf<LlmToolSpec>()
        dynamicMcpLookup.clear()

        specs += builtInSpecs()

        // Inject all tools from currently enabled MCP servers directly into the model schema
        val enabledMap = mcpManager.getEnabledToolsMap()
        val usedNames = specs.map { it.name }.toMutableSet()
        for ((srv, tools) in enabledMap) {
            for (t in tools) {
                var exposed = McpClientManager.buildExposedToolName(srv.name, t.name)
                if (exposed in usedNames) exposed = "${exposed}_${srv.id.take(4)}"
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

    fun isDestructiveOrCommandTool(toolName: String, argsJson: String): Boolean {
        if (toolName in setOf("file_delete", "kill_command")) return true
        if (toolName in setOf("run_command", "run_command_async")) {
            val cmd = runCatching {
                LlmHttpFactory.json.parseToJsonElement(argsJson).jsonObject["command"]?.jsonPrimitive?.contentOrNull
            }.getOrNull().orEmpty().lowercase()
            return listOf("rm ", "rm -", "mkfs", "dd ", "git reset --hard", "chmod 777", "kill -9", ":(){", "shutdown", "reboot")
                .any { cmd.contains(it) }
        }
        return false
    }

    fun requiresApproval(toolName: String, argsJson: String, settings: AgentSettings): Boolean {
        if (settings.autoApproveAll) return false
        if (isDestructiveOrCommandTool(toolName, argsJson)) return true
        if (!settings.autoApproveSafe) {
            return toolName in setOf("file_write", "file_edit", "run_command", "run_command_async", "transfer_file")
        }
        return false
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
        val args = runCatching {
            LlmHttpFactory.json.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject
        }.getOrElse { emptyObjSchema() }

        fun str(key: String): String? = args[key]?.jsonPrimitive?.contentOrNull
        fun int(key: String, def: Int): Int = args[key]?.jsonPrimitive?.intOrNull ?: def
        fun bool(key: String, def: Boolean): Boolean = args[key]?.jsonPrimitive?.booleanOrNull ?: def
        fun resolveEnv(): WorkspaceEnvType = str("target_env")?.let { WorkspaceEnvType.fromId(it) } ?: defaultEnv

        // Dynamic MCP tool call
        dynamicMcpLookup[toolName]?.let { (serverId, realToolName) ->
            val res = mcpManager.callTool(serverId, realToolName, argsJson)
            val imgs = res.savedFilePaths.mapNotNull { path ->
                val f = File(path)
                if (f.exists()) ChatAttachment(
                    id = "mcp_${System.nanoTime()}",
                    name = f.name,
                    mimeType = WorkspaceManager.guessMime(f.name),
                    localPath = f.absolutePath,
                    sizeBytes = f.length(),
                    kind = com.forgemind.app.data.model.AttachmentKind.IMAGE,
                    isFromAssistant = true
                ) else null
            }
            return ToolRunOutput(
                text = res.text,
                isError = res.isError,
                outgoingAttachments = imgs,
                mcpServerName = mcpManager.getAllServers().firstOrNull { it.id == serverId }?.name
            )
        }

        return when (toolName) {
            "check_environment_status" -> {
                val mcpList = mcpManager.getAllServers()
                val sshList = termuxBridge.getAllProfiles()
                val text = buildString {
                    appendLine("Единое рабочее пространство (все доступные среды активны сразу):")
                    appendLine("- Домашняя папка ИИ (`internal`): ${workspaceManager.getInternalProjectDir(internalProject).absolutePath} [Готова]")
                    appendLine("- Termux установлен: ${termuxBridge.isTermuxInstalled()}, RUN_COMMAND: ${termuxBridge.hasTermuxPermission()}")
                    appendLine("- SSH-серверы (${sshList.size}, включено: ${sshList.count { it.enabled }}):")
                    sshList.forEach { p ->
                        val def = if (p.isDefault) " [основной]" else ""
                        val cfTag = if (p.useCloudflareAccess) ", tunnel=CloudflareAccess" else ""
                        appendLine("  * ${p.name} (id=${p.id}): ${p.username.ifBlank { "?" }}@${p.host.ifBlank { "?" }}:${p.port}$cfTag, enabled=${p.enabled}, connected=${termuxBridge.isSshConnected(p.id)}$def")
                    }
                    appendLine("- SAF папка Android: ${workspaceManager.safRootUri?.let { WorkspaceManager.formatSafTreeLabel(it.toString()) } ?: "не выбрана"}")
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

            "transfer_file" -> {
                val srcEnv = WorkspaceEnvType.fromId(str("source_env"))
                val srcPath = str("source_path") ?: return ToolRunOutput("Отсутствует source_path", isError = true)
                val dstEnv = WorkspaceEnvType.fromId(str("target_env"))
                val dstPath = str("target_path") ?: return ToolRunOutput("Отсутствует target_path", isError = true)
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
                if (env == WorkspaceEnvType.INTERNAL && server.isNullOrBlank()) {
                    val dir = workspaceManager.getInternalProjectDir(internalProject)
                    val res = runLocalShellCommand(cmd, dir, timeout)
                    ToolRunOutput(
                        text = buildString {
                            appendLine("Exit Code: ${res.first}")
                            if (res.second.isNotBlank()) appendLine("--- stdout/stderr ---\n${res.second.take(16_000)}")
                        },
                        isError = res.first != 0
                    )
                } else {
                    val wd = str("workdir")
                    val res = termuxBridge.runCommand(cmd, wd, timeout, serverHint = server)
                    val outText = buildString {
                        appendLine("Exit Code: ${res.exitCode}" + if (res.timedOut) " (TIMEOUT)" else "")
                        if (!res.error.isNullOrBlank()) appendLine("Error: ${res.error}")
                        if (res.stdout.isNotBlank()) appendLine("--- stdout ---\n${res.stdout.take(16_000)}")
                        if (res.stderr.isNotBlank()) appendLine("--- stderr ---\n${res.stderr.take(8_000)}")
                    }
                    ToolRunOutput(outText, isError = res.error != null || res.exitCode != 0)
                }
            }

            "run_command_async" -> {
                val cmd = str("command") ?: return ToolRunOutput("Отсутствует command", isError = true)
                val wd = str("workdir")
                val server = str("server")
                termuxBridge.runCommandAsync(cmd, wd, serverHint = server).fold(
                    onSuccess = { started ->
                        val srvArg = server?.let { ", server='$it'" }.orEmpty()
                        ToolRunOutput(
                            "Команда запущена в фоне.\nPID: ${started.pid}\nLog file: ${started.logFile}\n" +
                                "Используйте check_command_status(pid='${started.pid}', log_file='${started.logFile}'$srvArg) для проверки вывода."
                        )
                    },
                    onFailure = { ToolRunOutput("Ошибка run_command_async: ${it.message}", isError = true) }
                )
            }

            "check_command_status" -> {
                val pid = str("pid") ?: return ToolRunOutput("Отсутствует pid", isError = true)
                val logFile = str("log_file") ?: return ToolRunOutput("Отсутствует log_file", isError = true)
                val lines = int("lines", 60)
                val server = str("server")
                ToolRunOutput(termuxBridge.checkCommandStatus(pid, logFile, lines, serverHint = server))
            }

            "kill_command" -> {
                val pid = str("pid") ?: return ToolRunOutput("Отсутствует pid", isError = true)
                val server = str("server")
                val res = termuxBridge.killCommand(pid, serverHint = server)
                ToolRunOutput(res.stdout.ifBlank { res.stderr.ifBlank { res.error ?: "Завершено" } }, isError = res.error != null)
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

            "browser_open" -> {
                val raw = str("url")?.trim() ?: return ToolRunOutput("Отсутствует url", isError = true)
                val clean = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                onOpenBrowserUrl(clean)
                ToolRunOutput("Страница открыта во вкладке «Браузер»: $clean")
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
            runCatching {
                workDir.mkdirs()
                val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return@withContext -1 to "Команда превысила таймаут ${timeoutSec}s"
                }
                val out = proc.inputStream.bufferedReader().readText()
                proc.exitValue() to out
            }.getOrElse { e -> -1 to "Ошибка запуска shell: ${e.message}" }
        }

    private fun builtInSpecs(): List<LlmToolSpec> = listOf(
        spec(
            "check_environment_status",
            "Проверить доступные среды: Домашнюю папку ИИ ('internal'), Termux/SSH, SAF папку Android и подключённые MCP-серверы.",
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
            "transfer_file",
            "Перенести или скопировать файл между средами (например из 'internal' в 'termux_ssh' или 'saf' и обратно).",
            objSchema(
                mapOf(
                    "source_env" to strProp("internal | termux_ssh | saf"),
                    "source_path" to strProp("Путь исходного файла"),
                    "source_server" to strProp("Исходный SSH-сервер (если source_env='termux_ssh')"),
                    "target_env" to strProp("internal | termux_ssh | saf"),
                    "target_path" to strProp("Путь назначения"),
                    "target_server" to strProp("Целевой SSH-сервер (если target_env='termux_ssh')")
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
            "Выполнить shell-команду на SSH-сервере (Termux или внешний SSH) либо во встроенной домашней папке.",
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
            "Запустить долгую команду в фоне SSH/Termux и получить PID + log_file.",
            objSchema(
                mapOf(
                    "command" to strProp("Команда для фонового запуска"),
                    "workdir" to strProp("Рабочая директория"),
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
                    "server" to strProp("Имя или ID SSH-сервера (опционально)")
                ),
                listOf("pid", "log_file")
            )
        ),
        spec(
            "kill_command",
            "Принудительно завершить фоновый процесс в Termux/SSH по PID.",
            objSchema(
                mapOf(
                    "pid" to strProp("PID процесса"),
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
            "Загрузить и распарсить веб-страницу: в Markdown, чистый текст, ссылки, таблицы или по CSS-селектору. Также открывает страницу во вкладке «Браузер».",
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
            "browser_open",
            "Открыть указанный URL во встроенной вкладке «Браузер» для пользователя и агента.",
            objSchema(
                mapOf("url" to strProp("URL веб-страницы")),
                listOf("url")
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

    private fun envProp() = strProp("Среда выполнения: 'internal' (Домашняя ИИ), 'termux_ssh' (Termux/SSH) или 'saf' (Папка Android). По умолчанию используется активная среда чата.")
}
