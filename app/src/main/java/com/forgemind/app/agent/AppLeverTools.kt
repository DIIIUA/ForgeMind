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
            "Получить подробные метаданные файла или папки (тип, размер, строки, дата изменения).",
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
            "file_move",
            "Переименовать или переместить файл/папку внутри рабочей среды.",
            objSchema(
                mapOf(
                    "source_path" to strProp("Исходный путь"),
                    "target_path" to strProp("Новый путь"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("source_path", "target_path")
            )
        ),
        spec(
            "snapshot_list",
            "Показать сохранённые снэпшоты (точки отката файлов) в текущей сессии.",
            objSchema(mapOf("limit" to intProp("Максимум записей (по умолчанию 20)")))
        ),
        spec(
            "snapshot_restore",
            "Откатить файл к сохранённому снэпшоту по snapshot_id.",
            objSchema(mapOf("snapshot_id" to strProp("ID снэпшота из snapshot_list")), listOf("snapshot_id"))
        ),
        spec(
            "editor_open_file",
            "Открыть файл в фоновом буфере встроенного редактора (не переключает экран пользователя и не мешает его текущей вкладке).",
            objSchema(
                mapOf(
                    "path" to strProp("Путь к текстовому файлу"),
                    "target_env" to envProp(),
                    "server" to strProp("Имя или ID SSH-сервера (для termux_ssh)")
                ),
                listOf("path")
            )
        ),
        spec(
            "editor_list_tabs",
            "Узнать список открытых сейчас вкладок редактора кода и (опционально) несохранённое содержимое.",
            objSchema(mapOf("include_content" to boolProp("Включить текст открытых вкладок (по умолчанию false)")))
        ),
        spec(
            "preview_server_control",
            "Запустить, проверить или остановить встроенный локальный HTTP-сервер предпросмотра для домашней папки проекта ('internal').",
            objSchema(
                mapOf(
                    "action" to strProp("start | stop | status"),
                    "open_in_browser" to boolProp("Сразу открыть локальный URL в фоновом агентном браузере (по умолчанию true при start)")
                ),
                listOf("action")
            )
        ),
        spec(
            "browser_agent_navigate",
            "АГЕНТНЫЙ БРАУЗЕР (отдельный фоновый WebView, не трогает обычный браузер пользователя): открыть URL и дождаться загрузки.",
            objSchema(
                mapOf(
                    "url" to strProp("URL сайта или поисковый запрос"),
                    "wait_seconds" to intProp("Таймаут ожидания загрузки в секундах (1..30, по умолчанию 15)")
                ),
                listOf("url")
            )
        ),
        spec(
            "browser_agent_inspect",
            "АГЕНТНЫЙ БРАУЗЕР: прочитать состояние живой страницы (URL, title, scroll, нумерованные кликабельные элементы [0], [1]..., видимый текст и ошибки JS-консоли).",
            objSchema(mapOf("max_chars" to intProp("Максимум символов видимого текста (по умолчанию 5500)")))
        ),
        spec(
            "browser_agent_act",
            "АГЕНТНЫЙ БРАУЗЕР: выполнить действие в живом браузере в реальном времени (клик, ввод текста, скролл, Enter, назад/вперёд/обновить).",
            objSchema(
                mapOf(
                    "action" to strProp("click | type | scroll | press_enter | select | back | forward | reload | wait"),
                    "element_index" to intProp("Индекс элемента [N] из последнего browser_agent_inspect"),
                    "selector" to strProp("CSS-селектор элемента (если не указан element_index)"),
                    "text" to strProp("Поиск элемента по видимому тексту/placeholder/aria-label"),
                    "value" to strProp("Текст для type/select, либо для scroll: down | up | top | bottom | пиксели, либо мс для wait")
                ),
                listOf("action")
            )
        ),
        spec(
            "browser_agent_eval_js",
            "АГЕНТНЫЙ БРАУЗЕР: выполнить произвольный JavaScript на открытой странице живого браузера и вернуть результат.",
            objSchema(mapOf("script" to strProp("JS-код (можно IIFE или выражение)")), listOf("script"))
        ),
        spec(
            "browser_agent_screenshot",
            "АГЕНТНЫЙ БРАУЗЕР: сделать PNG-снимок текущей страницы живого браузера и отправить в чат/сохранить в проекте.",
            objSchema(
                mapOf(
                    "filename" to strProp("Имя файла (по умолчанию browser_shot_<ts>.png)"),
                    "attach_to_chat" to boolProp("Прикрепить скриншот прямо в сообщение чата (по умолчанию true)")
                )
            )
        ),
        spec(
            "browser_agent_mode",
            "АГЕНТНЫЙ БРАУЗЕР (фоновый): проверить состояние отдельного браузера агента, прочитать ленту действий или сбросить его на стартовую.",
            objSchema(
                mapOf(
                    "action" to strProp("status | logs | home")
                ),
                listOf("action")
            )
        ),
        spec(
            "terminal_list_tabs",
            "Показать открытые вкладки видимого терминала (Local Shell / SSH) и последний вывод.",
            objSchema(mapOf("tail_chars" to intProp("Сколько символов конца вывода показать (по умолчанию 3500)")))
        ),
        spec(
            "terminal_open_tab",
            "Открыть новую фоновую вкладку терминала (локальный shell или уже настроенный SSH-профиль), не переключая текущий экран пользователя.",
            objSchema(
                mapOf(
                    "mode" to strProp("local | ssh (по умолчанию local для internal или ssh)"),
                    "server" to strProp("Имя или ID существующего SSH-профиля (если mode='ssh')"),
                    "title" to strProp("Заголовок вкладки (опционально)")
                )
            )
        ),
        spec(
            "terminal_send",
            "Отправить команду или управляющий сигнал (Ctrl+C, Tab, Esc, clear) в видимую вкладку терминала.",
            objSchema(
                mapOf(
                    "command" to strProp("Команда (без завершающего \\n)"),
                    "signal" to strProp("ctrl_c | tab | esc | clear (если нужен сигнал вместо команды)"),
                    "tab_id" to strProp("ID вкладки (по умолчанию активная)"),
                    "wait_ms" to intProp("Пауза перед чтением вывода в мс (по умолчанию 650)")
                )
            )
        ),
        spec(
            "chat_list_sessions",
            "Показать список сохранённых чатов и папок приложения.",
            objSchema(
                mapOf(
                    "include_archived" to boolProp("Включить архивные чаты (по умолчанию false)"),
                    "folder_id" to strProp("Фильтр по ID папки (опционально)")
                )
            )
        ),
        spec(
            "chat_search_history",
            "Поиск по заголовкам и текстам сообщений во всех прошлых диалогах.",
            objSchema(
                mapOf(
                    "query" to strProp("Поисковая фраза"),
                    "limit" to intProp("Максимум совпадений (по умолчанию 15)")
                ),
                listOf("query")
            )
        ),
        spec(
            "chat_read_session",
            "Прочитать последние сообщения из указанного сохранённого чата по session_id.",
            objSchema(
                mapOf(
                    "session_id" to strProp("ID чата из chat_list_sessions или chat_search_history"),
                    "limit_messages" to intProp("Сколько последних сообщений прочитать (по умолчанию 20)")
                ),
                listOf("session_id")
            )
        ),
        spec(
            "chat_manage",
            "Организовать чаты и папки (создать чат/папку, переименовать, закрепить, поместить в папку; без удаления).",
            objSchema(
                mapOf(
                    "action" to strProp("create_session | rename_session | create_folder | assign_folder | pin | unpin"),
                    "session_id" to strProp("ID чата (по умолчанию текущий)"),
                    "title" to strProp("Новое название чата или папки"),
                    "folder_id" to strProp("ID папки (для assign_folder / create_session)")
                ),
                listOf("action")
            )
        ),
        spec(
            "chat_export_markdown",
            "Экспортировать чат в Markdown (.md) во внутреннюю папку проекта и опционально прикрепить в чат.",
            objSchema(
                mapOf(
                    "session_id" to strProp("ID чата (по умолчанию текущий)"),
                    "filename" to strProp("Имя .md файла (опционально)"),
                    "attach_to_chat" to boolProp("Прикрепить файл в чат (по умолчанию true)")
                )
            )
        ),
        spec(
            "context_compact",
            "Сжать контекст текущего диалога (суммaризация длинной истории для экономии токенов).",
            emptyObjSchema()
        ),
        spec(
            "app_navigate",
            "Переключить активную вкладку экрана приложения (вызывать ТОЛЬКО если пользователь сам попросил переключить его экран).",
            objSchema(
                mapOf("section" to strProp("chat | files | preview | settings")),
                listOf("section")
            )
        ),
        spec(
            "voice_speak",
            "Озвучить текст через настроенный TTS-движок, остановить озвучку или узнать текущий статус речи.",
            objSchema(
                mapOf(
                    "action" to strProp("speak | stop | status"),
                    "text" to strProp("Текст для озвучивания (если action='speak')")
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
                val path = str("path") ?: return ToolRunOutput("Отсутствует path", isError = true)
                workspaceManager.getFileInfo(resolveEnv(), path, internalProject, str("server")).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка file_info: ${it.message}", isError = true) }
                )
            }

            "file_move" -> {
                val src = str("source_path") ?: return ToolRunOutput("Отсутствует source_path", isError = true)
                val dst = str("target_path") ?: return ToolRunOutput("Отсутствует target_path", isError = true)
                workspaceManager.moveOrRenameFile(resolveEnv(), src, dst, internalProject, str("server")).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка file_move: ${it.message}", isError = true) }
                )
            }

            "snapshot_list" -> {
                val limit = int("limit", 20).coerceIn(1, 50)
                val list = workspaceManager.listSessionSnapshots(sessionId, limit)
                if (list.isEmpty()) {
                    ToolRunOutput("Снэпшоты отката в этом чате ещё не созданы.")
                } else {
                    val fmt = SimpleDateFormat("HH:mm:ss dd.MM", Locale.getDefault())
                    val text = list.joinToString("\n") { s ->
                        val state = if (s.restored) "[откат уже выполнен]" else "[готов]"
                        "- id=${s.id} | ${s.envType}:${s.filePath} | existedBefore=${s.fileExistedBefore} | ${fmt.format(Date(s.createdAt))} $state"
                    }
                    ToolRunOutput(text)
                }
            }

            "snapshot_restore" -> {
                val snapId = str("snapshot_id") ?: return ToolRunOutput("Отсутствует snapshot_id", isError = true)
                workspaceManager.restoreSnapshot(snapId, internalProject).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка snapshot_restore: ${it.message}", isError = true) }
                )
            }

            "editor_open_file" -> {
                val path = str("path") ?: return ToolRunOutput("Отсутствует path", isError = true)
                val env = resolveEnv()
                val server = str("server")
                workspaceManager.readRawBytes(env, path, internalProject, server).fold(
                    onSuccess = { bytes ->
                        if (WorkspaceManager.looksBinaryBytes(bytes)) {
                            return ToolRunOutput("Файл '$path' бинарный (${bytes.size} Б) и не может быть открыт как текст.", isError = true)
                        }
                        val title = onOpenEditorTab(env, path, bytes.toString(Charsets.UTF_8), server)
                        ToolRunOutput("Файл '$title' (${env.id}:$path) открыт в фоновом буфере редактора (без переключения экрана пользователя).")
                    },
                    onFailure = { ToolRunOutput("Ошибка editor_open_file: ${it.message}", isError = true) }
                )
            }

            "editor_list_tabs" -> {
                val includeContent = bool("include_content", false)
                val tabs = onListEditorTabs()
                if (tabs.isEmpty()) {
                    ToolRunOutput("Открытых вкладок редактора сейчас нет.")
                } else {
                    val out = buildString {
                        tabs.forEach { t ->
                            val flags = listOfNotNull(
                                if (t.isActive) "АКТИВНА" else null,
                                if (t.isDirty) "НЕ СОХРАНЕНО" else null
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
                val srv = localPreviewServer ?: return ToolRunOutput("Локальный сервер недоступен", isError = true)
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
                        ToolRunOutput("Локальный HTTP-сервер запущен над ${dir.name}: $url")
                    }
                    "stop" -> {
                        srv.stop()
                        onPreviewPortChanged(0)
                        ToolRunOutput("Локальный HTTP-сервер предпросмотра остановлен.")
                    }
                    else -> {
                        val port = srv.port
                        ToolRunOutput(
                            if (port > 0) "Локальный HTTP-сервер активен: http://127.0.0.1:$port/"
                            else "Локальный HTTP-сервер не запущен."
                        )
                    }
                }
            }

            "browser_agent_navigate" -> {
                val ctrl = browserController ?: return ToolRunOutput("Контроллер браузера недоступен", isError = true)
                val rawUrl = str("url") ?: return ToolRunOutput("Отсутствует url", isError = true)
                val waitSec = int("wait_seconds", 15).coerceIn(1, 30)
                ctrl.navigate(rawUrl, enableAgentMode = false, waitFinishMs = waitSec * 1000L).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка browser_agent_navigate: ${it.message}", isError = true) }
                )
            }

            "browser_agent_inspect" -> {
                val ctrl = browserController ?: return ToolRunOutput("Контроллер браузера недоступен", isError = true)
                val maxChars = int("max_chars", 5500)
                ctrl.inspect(maxChars).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка browser_agent_inspect: ${it.message}", isError = true) }
                )
            }

            "browser_agent_act" -> {
                val ctrl = browserController ?: return ToolRunOutput("Контроллер браузера недоступен", isError = true)
                val actName = str("action") ?: return ToolRunOutput("Отсутствует action", isError = true)
                val idx = (args["element_index"] as? JsonPrimitive)?.intOrNull
                ctrl.act(actName, str("selector"), idx, str("text"), str("value")).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Ошибка browser_agent_act: ${it.message}", isError = true) }
                )
            }

            "browser_agent_eval_js" -> {
                val ctrl = browserController ?: return ToolRunOutput("Контроллер браузера недоступен", isError = true)
                val script = str("script") ?: return ToolRunOutput("Отсутствует script", isError = true)
                ctrl.evalJs(script).fold(
                    onSuccess = { ToolRunOutput(it.take(16_000)) },
                    onFailure = { ToolRunOutput("Ошибка browser_agent_eval_js: ${it.message}", isError = true) }
                )
            }

            "browser_agent_screenshot" -> {
                val ctrl = browserController ?: return ToolRunOutput("Контроллер браузера недоступен", isError = true)
                val rawName = (str("filename")?.trim()?.ifBlank { null }
                    ?: "browser_${System.currentTimeMillis()}.png")
                val safeName = rawName.substringAfterLast('/').substringAfterLast('\\').replace("..", "_")
                    .ifBlank { "browser_${System.currentTimeMillis()}.png" }
                val outFile = File(workspaceManager.chatFilesDir, safeName)
                ctrl.captureScreenshot(outFile).fold(
                    onSuccess = { saved ->
                        val attach = bool("attach_to_chat", true)
                        val atts = if (attach) listOf(workspaceManager.buildAttachmentFromLocalFile(saved, isFromAssistant = true)) else emptyList()
                        ToolRunOutput(
                            text = "Скриншот агентного браузера сохранён: ${saved.name} (${saved.length()} байт)" +
                                if (attach) " и прикреплён к чату." else ".",
                            outgoingAttachments = atts
                        )
                    },
                    onFailure = { ToolRunOutput("Ошибка browser_agent_screenshot: ${it.message}", isError = true) }
                )
            }

            "browser_agent_mode" -> {
                val ctrl = browserController ?: return ToolRunOutput("Контроллер браузера недоступен", isError = true)
                when (str("action")?.lowercase() ?: "status") {
                    "home", "reset" -> {
                        ctrl.resetAgentBrowser()
                        ToolRunOutput("Фоновый агентный браузер сброшен на стартовую страницу (обычный браузер пользователя не затронут).")
                    }
                    "logs" -> {
                        val logs = ctrl.actionLog.value.takeLast(30)
                        ToolRunOutput(
                            if (logs.isEmpty()) "Лента действий агентного браузера пуста."
                            else logs.joinToString("\n")
                        )
                    }
                    else -> {
                        ToolRunOutput(
                            "Режим просмотра пользователем: ${if (ctrl.agentModeEnabled.value) "Наблюдает Агентный (Live)" else "В своём Обычном браузере"}\n" +
                                "Агентный URL: ${ctrl.currentUrl.value.ifBlank { "(не открыт)" }}\n" +
                                "Title: ${ctrl.pageTitle.value}\n" +
                                "Загрузка: ${ctrl.isLoading.value}\n" +
                                "Последнее действие агента: ${ctrl.lastActionBanner.value ?: "нет"}"
                        )
                    }
                }
            }

            "terminal_list_tabs" -> {
                val tm = terminalManager ?: return ToolRunOutput("Терминал недоступен", isError = true)
                val tabs = tm.tabs.value
                if (tabs.isEmpty()) {
                    ToolRunOutput("Открытых вкладок терминала нет. Можно открыть через terminal_open_tab.")
                } else {
                    val tailChars = int("tail_chars", 3500).coerceIn(200, 12_000)
                    val activeId = tm.activeTabId.value
                    val text = buildString {
                        tabs.forEach { t ->
                            val marker = if (t.id == activeId) " [ACTIVE]" else ""
                            appendLine("=== ${t.title} (id=${t.id}, mode=${t.mode.name}, alive=${t.isAlive}, dir=${t.workDir})$marker ===")
                            if (t.id == activeId || tabs.size == 1) {
                                appendLine(t.output.takeLast(tailChars))
                            }
                        }
                    }
                    ToolRunOutput(text.trimEnd())
                }
            }

            "terminal_open_tab" -> {
                val tm = terminalManager ?: return ToolRunOutput("Терминал недоступен", isError = true)
                val defaultMode = if (defaultEnv == WorkspaceEnvType.TERMUX_SSH) "ssh" else "local"
                val modeStr = str("mode")?.lowercase() ?: defaultMode
                val title = str("title")
                val tabId = if (modeStr == "ssh") {
                    val hint = str("server")
                    val profiles = termuxBridge.getAllProfiles()
                    val profile = if (!hint.isNullOrBlank()) {
                        profiles.firstOrNull {
                            it.id.equals(hint, ignoreCase = true) || it.name.equals(hint, ignoreCase = true)
                        } ?: return ToolRunOutput("SSH-профиль '$hint' не найден среди настроенных.", isError = true)
                    } else {
                        termuxBridge.activeProfile
                    }
                    tm.openSshTerminal(profile, title ?: profile.name)
                } else {
                    val dir = workspaceManager.getInternalProjectDir(internalProject)
                    tm.openLocalShell(dir, title ?: "Локальный Shell")
                }
                delay(350)
                ToolRunOutput("Открыта фоновая вкладка терминала id=$tabId (mode=$modeStr).")
            }

            "terminal_send" -> {
                val tm = terminalManager ?: return ToolRunOutput("Терминал недоступен", isError = true)
                val tabId = str("tab_id") ?: tm.activeTabId.value ?: tm.tabs.value.lastOrNull()?.id
                    ?: return ToolRunOutput("Нет открытой вкладки терминала — сначала вызовите terminal_open_tab.", isError = true)
                val signal = str("signal")?.lowercase()
                val cmd = str("command")
                val waitMs = int("wait_ms", 650).coerceIn(100, 10_000)

                when (signal) {
                    "ctrl_c" -> tm.sendCtrlC(tabId)
                    "tab" -> tm.sendTab(tabId)
                    "esc" -> tm.sendEsc(tabId)
                    "clear" -> tm.clearOutput(tabId)
                    else -> {
                        val c = cmd ?: return ToolRunOutput("Нужно указать command или signal", isError = true)
                        tm.sendLine(tabId, c)
                    }
                }
                delay(waitMs.toLong())
                val updated = tm.tabs.value.firstOrNull { it.id == tabId }
                val closedErr = updated != null && !updated.isAlive && signal == null
                ToolRunOutput(
                    text = updated?.output?.takeLast(6000) ?: "Отправлено в $tabId",
                    isError = closedErr
                )
            }

            "chat_list_sessions" -> {
                val database = db ?: return ToolRunOutput("БД недоступна", isError = true)
                val includeArchived = bool("include_archived", false)
                val folderFilter = str("folder_id")
                val folders = database.folderDao().getAll().associateBy { it.id }
                val allSessions = database.sessionDao().getAll()
                    .filter { includeArchived || !it.isArchived }
                    .filter { folderFilter == null || it.folderId == folderFilter }
                val out = buildString {
                    if (folders.isNotEmpty()) {
                        appendLine("Папки:")
                        folders.values.forEach { appendLine("  - ${it.name} (id=${it.id})") }
                    }
                    appendLine("Чаты (${allSessions.size}):")
                    val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                    allSessions.take(40).forEach { s ->
                        val curTag = if (s.id == sessionId) " [ТЕКУЩИЙ]" else ""
                        val pinTag = if (s.isPinned) " [ЗАКРЕПЛЁН]" else ""
                        val fName = s.folderId?.let { folders[it]?.name }?.let { " | папка=$it" }.orEmpty()
                        appendLine("- id=${s.id} | «${s.title}» | env=${s.workspaceEnv}$fName | upd=${fmt.format(Date(s.updatedAt))}$curTag$pinTag")
                    }
                }
                ToolRunOutput(out.trimEnd())
            }

            "chat_search_history" -> {
                val database = db ?: return ToolRunOutput("БД недоступна", isError = true)
                val query = str("query")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return ToolRunOutput("Отсутствует query", isError = true)
                val limit = int("limit", 15).coerceIn(1, 40)
                val sessionsById = database.sessionDao().getAll().associateBy { it.id }
                val msgs = database.messageDao().searchMessages(query, limit)
                if (msgs.isEmpty()) {
                    ToolRunOutput("По запросу «$query» в истории чатов совпадений не найдено.")
                } else {
                    val out = msgs.joinToString("\n\n") { m ->
                        val sTitle = sessionsById[m.sessionId]?.title ?: m.sessionId
                        val snippet = m.content.replace('\n', ' ').trim().take(240)
                        "[чат: «$sTitle», session_id=${m.sessionId}] (${m.role}): $snippet"
                    }
                    ToolRunOutput(out)
                }
            }

            "chat_read_session" -> {
                val database = db ?: return ToolRunOutput("БД недоступна", isError = true)
                val sid = str("session_id") ?: return ToolRunOutput("Отсутствует session_id", isError = true)
                val limit = int("limit_messages", 20).coerceIn(1, 60)
                val s = database.sessionDao().getById(sid)
                    ?: return ToolRunOutput("Чат '$sid' не найден", isError = true)
                val msgs = database.messageDao().getForSession(sid)
                    .filter { it.role != LlmRole.SYSTEM.name }
                    .takeLast(limit)
                val out = buildString {
                    appendLine("Чат «${s.title}» (env=${s.workspaceEnv}, сообщений=${msgs.size}):")
                    if (s.compactedSummary.isNotBlank()) {
                        appendLine("[Сводка]: ${s.compactedSummary.take(800)}\n")
                    }
                    msgs.forEach { m ->
                        val body = if (m.role == LlmRole.TOOL.name) m.content.take(400) else m.content.take(1500)
                        appendLine("${m.role}: $body")
                    }
                }
                ToolRunOutput(out.trimEnd())
            }

            "chat_manage" -> {
                val database = db ?: return ToolRunOutput("БД недоступна", isError = true)
                val act = str("action")?.lowercase() ?: return ToolRunOutput("Отсутствует action", isError = true)
                val targetSid = str("session_id") ?: sessionId
                when (act) {
                    "create_folder" -> {
                        val name = str("title")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return ToolRunOutput("Укажите title для папки", isError = true)
                        val folder = ChatFolderEntity(id = UUID.randomUUID().toString(), name = name)
                        database.folderDao().upsert(folder)
                        ToolRunOutput("Папка «$name» создана (folder_id=${folder.id}).")
                    }
                    "create_session" -> {
                        val title = str("title")?.trim()?.ifBlank { "Новый чат" } ?: "Новый чат"
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
                        ToolRunOutput("Создан чат «$title» (session_id=${newSess.id}).")
                    }
                    "rename_session" -> {
                        val title = str("title")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return ToolRunOutput("Укажите title", isError = true)
                        database.sessionDao().updateTitle(targetSid, title)
                        ToolRunOutput("Чат $targetSid переименован в «$title».")
                    }
                    "assign_folder" -> {
                        val s = database.sessionDao().getById(targetSid)
                            ?: return ToolRunOutput("Чат '$targetSid' не найден", isError = true)
                        val fid = str("folder_id")?.takeIf { it.isNotBlank() }
                        database.sessionDao().upsert(s.copy(folderId = fid, updatedAt = System.currentTimeMillis()))
                        ToolRunOutput("Чат «${s.title}» перенесён в папку ${fid ?: "без папки"}.")
                    }
                    "pin", "unpin" -> {
                        database.sessionDao().setPinned(targetSid, act == "pin")
                        ToolRunOutput(if (act == "pin") "Чат $targetSid закреплён." else "Чат $targetSid откреплён.")
                    }
                    else -> ToolRunOutput("Неизвестное действие chat_manage: $act", isError = true)
                }
            }

            "chat_export_markdown" -> {
                val database = db ?: return ToolRunOutput("БД недоступна", isError = true)
                val am = archiveManager ?: return ToolRunOutput("ArchiveManager недоступен", isError = true)
                val sid = str("session_id") ?: sessionId
                val s = database.sessionDao().getById(sid)
                    ?: return ToolRunOutput("Чат '$sid' не найден", isError = true)
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
                            text = "Чат «${s.title}» экспортирован в $safeName (${md.length} симв.).",
                            outgoingAttachments = atts
                        )
                    },
                    onFailure = { ToolRunOutput("Ошибка экспорта: ${it.message}", isError = true) }
                )
            }

            "context_compact" -> {
                onCompactContext(sessionId).fold(
                    onSuccess = { ToolRunOutput(it) },
                    onFailure = { ToolRunOutput("Не удалось сжать контекст: ${it.message}", isError = true) }
                )
            }

            "app_navigate" -> {
                val sec = str("section") ?: return ToolRunOutput("Отсутствует section", isError = true)
                if (onNavigateSection(sec)) {
                    ToolRunOutput("Экран переключён на вкладку '$sec'.")
                } else {
                    ToolRunOutput("Неизвестная вкладка '$sec'. Допустимы: chat, files, preview, settings.", isError = true)
                }
            }

            "voice_speak" -> {
                val tts = ttsManager ?: return ToolRunOutput("TTS недоступен", isError = true)
                val v = settingsRepo?.voiceSettings?.value
                    ?: return ToolRunOutput("Голосовые настройки недоступны", isError = true)
                when (str("action")?.lowercase() ?: "status") {
                    "speak" -> {
                        val txt = str("text")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return ToolRunOutput("Укажите text для озвучивания", isError = true)
                        tts.speak(null, txt, v)
                        ToolRunOutput("Запущена озвучка (${txt.length} симв., движок: ${v.ttsProvider.label}).")
                    }
                    "stop" -> {
                        tts.stop()
                        ToolRunOutput("Озвучка остановлена.")
                    }
                    else -> {
                        ToolRunOutput(
                            "TTS статус: speaking=${tts.isSpeaking.value}, движок=${v.ttsProvider.label}, " +
                                "скорость=${v.ttsSpeed}x, autoSpeak=${v.autoSpeakReplies}, " +
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

    private fun envProp(): JsonObject =
        strProp("Среда: 'internal' (домашняя ИИ), 'termux_ssh' (Linux/SSH) или 'saf' (папка Android). По умолчанию активная среда чата.")
}
