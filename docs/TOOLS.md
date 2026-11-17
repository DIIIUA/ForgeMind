# Agent tools / Инструменты агента — v0.2.5-alpha

**66 built-ins: 39 core tools + 27 app controls.** The names below are extracted from `AgentTools.kt` and `AppLeverTools.kt`. `capabilities_catalog({})` lists every built-in tool by area in one compact call; `area` gives one area with parameters, `name` the full schema of one tool, `query` searches. Tools of MCP servers are described by those servers through MCP; the catalog only names the connected servers.

**66 встроенных инструментов: 39 основных + 27 управления приложением.** `capabilities_catalog({})` одним компактным вызовом перечисляет все встроенные инструменты по областям; `area` — одна область с параметрами, `name` — полная схема одного инструмента, `query` — поиск. Инструменты MCP-серверов описывают сами серверы через MCP; каталог только называет подключённые серверы.

## Native and XML/JSON calls

Choose Automatic, Native or XML/JSON in the provider's tool-calling mode. For the text protocol the assistant emits a standalone envelope outside Markdown code fences:

```xml
<tool_call>{"name":"capabilities_catalog","arguments":{}}</tool_call>
```

This fenced example is documentation and is not executed. Actual envelopes require strict JSON, a known tool name and an object in `arguments`; optional `id` is accepted. Unknown fields, invalid JSON and unfinished XML stay text. Inline backticks and fenced examples are protected. Provider-specific legacy markup is supported in the appropriate fallback mode; text calls follow the same tool dispatch/approval rules. Model compliance is still required.

В этом документе пример заключён в блок кода и не исполняется. Исполняемый блок должен находиться вне Markdown-кода, содержать строгий JSON, известное имя и объект `arguments`; допустим необязательный `id`. Невалидный JSON, неизвестные поля и незавершённый XML остаются текстом. Обычный код и примеры в обратных кавычках защищены от исполнения. Действуют те же правила подтверждения, что у нативных вызовов.

## Human terminal commands

`/term pwd` or the explicit terminal mode opens a confirmation showing the command and target. Only confirmed submission sends it to the selected/created terminal session. This is separate from a model tool call; ordinary source code remains chat text.

`/term pwd` или режим терминала открывает подтверждение с командой и назначением. Отправка выполняется после подтверждения. Это отдельный путь от вызовов модели; обычный код остаётся текстом чата.

## Complete built-in names

| Tool | Definition |
| --- | --- |
| `memory_search` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `memory_propose` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `web_download` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `notify_user` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `report_tool_issue` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `capabilities_catalog` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `check_environment_status` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `list_files` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `search_files` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `search_codebase` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `file_read` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `file_write` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `file_edit` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `file_patch` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `file_append` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `file_delete` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `create_folder` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `archive_create` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `archive_extract` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `transfer_file` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `send_file_to_chat` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `run_command` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `run_command_async` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `check_command_status` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `kill_command` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `list_command_tasks` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `wait_command_tasks` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `web_search` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `web_scrape` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `web_scrape_site` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `browser_open` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `screen_read_windows` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `screen_act_tag` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `screen_act_batch` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `screen_capture` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `mcp_list_servers` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `mcp_connect` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `mcp_call_tool` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `mcp_read_resource` | [AgentTools.kt](../app/src/main/java/com/forgemind/app/agent/AgentTools.kt) |
| `file_info` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `file_move` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `snapshot_list` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `snapshot_restore` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `editor_open_file` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `editor_list_tabs` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `preview_server_control` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `browser_agent_tabs` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `browser_agent_navigate` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `browser_agent_inspect` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `browser_agent_markdown` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `browser_agent_act` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `browser_agent_batch` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `browser_agent_eval_js` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `browser_agent_screenshot` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `browser_agent_mode` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `terminal_list_tabs` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `terminal_open_tab` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `terminal_send` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `chat_list_sessions` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `chat_search_history` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `chat_read_session` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `chat_manage` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `chat_export_markdown` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `context_compact` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `app_navigate` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |
| `voice_speak` | [AppLeverTools.kt](../app/src/main/java/com/forgemind/app/agent/AppLeverTools.kt) |

Memory proposals always need human approval, even in autopilot. `memory_search` only exposes approved, enabled notes in the current chat's accessible scopes. Task cancellation targets the task managed by ForgeMind; it must not be used as a blanket process/session cleanup.
