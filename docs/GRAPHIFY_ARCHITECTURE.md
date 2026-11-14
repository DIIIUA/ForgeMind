# ForgeMind — Complete Graphify Architecture & Connection Specification

> **Generated & verified via [Graphify](https://pypi.org/project/graphifyy/) deterministic local AST extraction (`tree-sitter`) on `app/src/main`:**
> - **Codebase Graph:** `84` source files · **`1,571` AST nodes** · **`3,586` structural edges** (`97%` AST-extracted, `3%` inferred, `0%` ambiguous)
> - **Modular Topology:** **`66` cohesive communities** · **`0` import cycles**
> - **Interactive collapsible tree:** [GRAPHIFY_TREE.html](GRAPHIFY_TREE.html) (download and open locally in a browser)
> - **Built-In Agent Tool Surface:** **`56` native tools** (`29` core workspace/network/screen/MCP tools + `27` in-app lever tools) + unlimited dynamic MCP schema injection + built-in Android Streamable HTTP MCP Host (`:8765`).

---

## 1. Top 10 Core Hub Abstractions (Graphify Degree & Centrality Ranking)

| Rank | Core Symbol | Structural Edges | Architectural Role Across External & Internal Contours |
| :---: | :--- | :---: | :--- |
| 1 | `MainViewModel` | `169` | Central state orchestrator bridging Compose UI, `AgentEngine`, `LlmRouter`, `WorkspaceManager`, `TermuxBridge`, `McpClientManager`, `ForgeMcpHostServer`, `AgentBrowserController`, voice, and themes |
| 2 | `AgentBrowserController` | `76` | Isolated 8-window parallel background WebView pool, shared `⚡ Turbo` interceptor, 30-step batch runner, and live DOM/GFM Markdown extractor |
| 3 | `ProviderConfig` | `59` | Unified configuration model spanning cloud presets, custom external HTTP/HTTPS gateways, and LAN/local servers, auth modes, key pools, custom headers/body, TLS, and per-model context overrides |
| 4 | `FloatingChatService` | `59` | System-wide Android overlay bubble and resizable floating mini-chat + voice window operating over any foreground app |
| 5 | `WorkspaceManager` | `47` | Unified 4-environment VFS router (`internal`, `device_storage`, `saf`, `termux_ssh`) with pre-edit snapshot capture, diffs, archives, and cross-environment file transfer |
| 6 | `WorkspaceEnvType` | `39` | Environment discriminator binding chat sessions, editor tabs, shell commands, and SFTP/SAF/sandbox operations |
| 7 | `ForgeAccessibilityService` | `35` | Reads the screen content of any third-party app across all visible windows (`[W1..Wn]`: foreground app, split-screen, floating windows, IME, system panels), tags text and controls (`[#1..#k]`), executes single/batched UI actions inside those apps, and captures on-demand screenshots |
| 8 | `TermuxBridge` | `34` | SSH/SFTP session pool (`JSch`), Cloudflare Access WebSocket SSH (`wss://`) tunnel integration, async PID tracker, and local Termux `RUN_COMMAND` IPC |
| 9 | `SshProfileEntity` | `34` | Persistent profile model for local Termux, LAN/Tailscale hosts, remote internet servers, and Cloudflare Zero-Trust SSH endpoints |
| 10 | `ChatMessageEntity` | `32` | Branch-aware (`parentMessageId`) message, thinking, attachment, tool-call, and diff-card persistence unit |

---

## 2. Package-Level Symbol Distribution (`11` Subsystems)

| Package (`com.forgemind.app.*`) | AST Symbols | Key Classes & Responsibilities |
| :--- | :---: | :--- |
| `ui` | `318` | `MainViewModel`, `ChatScreen`, `FilesScreen` (dual-pane), `TerminalScreen`, `PreviewScreen` (vertical tabs + Regular/Agent Live WebViews), `SettingsScreen` (6 tabs incl. Theme Studio), `ConnectionStatusStrip`, `InAppFileViewer` |
| `data` | `235` | `ForgeMindDatabase` (Room v5), `DomainModels`, `ContextWindowResolver`, `ConversationBranching`, `SettingsRepository` (`AES256_GCM`), `ChatArchiveManager` (Markdown & ZIP backup/restore) |
| `bridge` | `205` | `WorkspaceManager`, `TermuxBridge`, `CloudflareAccessSshSupport`, `EmbeddedAiShell` (`/system/bin/sh`), `TerminalSessionManager`, `InAppDocumentParser`, `ArchiveUtils`, `DiffUtils` |
| `web` | `133` | `AgentBrowserController` (8 live windows + `⚡ Turbo`), `WebScraperEngine` (5 search engines + single-page & whole-site GFM Markdown crawler `web_scrape_site`), `LocalPreviewServer` |
| `llm` | `118` | `LlmRouter`, `OpenAiCompatibleClient`, `OpenAiResponsesClient`, `AnthropicClient`, `GeminiClient`, `OllamaNativeClient`, `ModelScanner`, `LanAiScanner`, `ContextCompactor`, `LlmHttpFactory` (DoH + custom TLS) |
| `agent` | `101` | `AgentEngine` (universal tool loop, 7 inline tool parsers, HTTP 400 fallback, reflection checkpoints, approvals), `AgentToolRegistry` (`29` core tools), `AppLeverTools` (`27` app tools), `AgentForegroundService` |
| `voice` | `93` | `SttManager` (Groq/OpenAI/Custom Whisper + Android Speech), `SttFallbackPolicy`, `TtsManager`, `MicrosoftEdgeTtsClient` (`wss://` neural TTS), `AudioRecorder` |
| `floating` | `57` | `FloatingChatService` (draggable overlay bubble, multi-corner resize, live streaming chat & voice over other apps) |
| `accessibility` | `46` | `ForgeAccessibilityService` (Window-Tag DOM, batch UI automation, hardware screenshots), `PackageReplacedReceiver` |
| `mcp` | `28` | `McpClientManager` (`STREAMABLE_HTTP`, `HTTP_SSE`, `SSH_STDIO`), `ForgeMcpHostServer` (on-device Streamable HTTP MCP server `:8765`) |
| `i18n` & `root` | `33` | `ForgeMindApp` (`AppContainer` DI hub), `MainActivity` (share-intent receiver), `LocaleHelper` (14 languages) |

---

## 3. Complete Connection Taxonomy (All External & Internal Channels)

| Direction & Scope | Protocol / Transport | Endpoints & Targets Supported |
| :--- | :--- | :--- |
| **External Outbound (LLM)** | `HTTPS / HTTP + SSE` & `NDJSON` | **5 Wire Protocols:** `OPENAI_COMPAT`, `OPENAI_RESPONSES`, `ANTHROPIC`, `GEMINI`, `OLLAMA_NATIVE` across **16 Provider Types** (12 cloud presets, 4 local/LAN server families, and arbitrary `CUSTOM` external gateways with `BEARER`, `X_API_KEY`, `QUERY_KEY`, `CUSTOM_HEADER`, `NONE`, custom JSON headers/body, DoH, and insecure LAN TLS) |
| **External Outbound (Zero-Trust SSH)** | `WSS` (WebSocket over TLS) | **Cloudflare Access SSH (`CloudflareAccessWebSocketSocket`):** encapsulates SSH/SFTP over `wss://` with `CF-Access-Client-Id` & `CF-Access-Client-Secret` (zero open ports, no `cloudflared` binary) |
| **External & Local (SSH / SFTP)** | `SSH-2 / SFTP` (`JSch`) | Remote VPS/Linux/macOS/Windows hosts, Tailscale (`100.x.y.z`), LAN hosts, and local on-device Termux `sshd` (`127.0.0.1:2222`) with password or private key auth |
| **External & Local Outbound (MCP)** | `STREAMABLE_HTTP` · `HTTP_SSE` · `SSH_STDIO` | Remote cloud/enterprise MCP gateways, home/Tailscale MCP servers, and CLI stdio MCP servers (`npx`, `uvx`, `python3`) spawned over SSH |
| **Inbound / Loopback Host (MCP)** | `HTTP JSON-RPC 2.0` (`Streamable HTTP`) | **`ForgeMcpHostServer` (`127.0.0.1:8765`):** `/mcp` & `/health` with constant-time `Bearer` token check, exposing phone tools to local agents, Termux, `adb forward`, or tunnels |
| **External Outbound (Web & Search)** | `HTTPS` + Isolated WebView | DuckDuckGo, Tavily, Brave, Google CSE, custom SearXNG, `WebScraperEngine` site crawler (`/sitemap.xml`, `/llms.txt`), and 8-window `AgentBrowserController` |
| **External & Internal (Voice)** | `WSS` · `HTTPS Multipart/REST` · Android Binder | Microsoft Edge Neural TTS (`wss://`), Groq/OpenAI/Custom Whisper STT, OpenAI/XTTS v2/Custom External TTS, plus offline Android `SpeechRecognizer` & `TextToSpeech` |
| **Internal On-Device (Storage & IPC)** | POSIX · SAF `ContentResolver` · Loopback HTTP · Accessibility · WindowManager | `internal` sandbox, `device_storage` (`/storage/emulated/0`), `saf` (`DocumentFile`), persistent `/system/bin/sh` (`EmbeddedAiShell`), Termux `RUN_COMMAND` Intent IPC, `LocalPreviewServer` (`127.0.0.1`), `ForgeAccessibilityService`, and `FloatingChatService` |

---

## 4. Complete Catalog of All 56 Built-In Agent Tools

1. **Environment, Files, Archives & Snapshots (`17`):**
   `check_environment_status`, `list_files`, `search_files`, `search_codebase`, `file_info`, `file_read`, `file_write`, `file_edit`, `file_move`, `file_delete`, `create_folder`, `archive_create`, `archive_extract`, `transfer_file`, `send_file_to_chat`, `snapshot_list`, `snapshot_restore`
2. **Embedded Shell, SSH & Terminal Tabs (`7`):**
   `run_command`, `run_command_async`, `check_command_status`, `kill_command`, `terminal_list_tabs`, `terminal_open_tab`, `terminal_send`
3. **Web Search, Whole-Site Scraper, 8-Window `⚡ Turbo` Browser & Preview Server (`14`):**
   `web_search`, `web_scrape`, `web_scrape_site`, `browser_open`, `browser_agent_tabs`, `browser_agent_navigate`, `browser_agent_inspect`, `browser_agent_markdown`, `browser_agent_act`, `browser_agent_batch`, `browser_agent_eval_js`, `browser_agent_screenshot`, `browser_agent_mode`, `preview_server_control`
4. **Android Window-Tag DOM Screen Automation (`4`):**
   `screen_read_windows`, `screen_act_tag`, `screen_act_batch`, `screen_capture`
5. **Sora Code Editor, Chat Sessions, Compaction, Navigation & Voice (`10`):**
   `editor_open_file`, `editor_list_tabs`, `chat_list_sessions`, `chat_search_history`, `chat_read_session`, `chat_manage`, `chat_export_markdown`, `context_compact`, `app_navigate`, `voice_speak`
6. **Model Context Protocol Client + Dynamic Server Tools (`4` + Dynamic):**
   `mcp_list_servers`, `mcp_connect`, `mcp_call_tool`, `mcp_read_resource` + all discovered tools from enabled MCP servers.
