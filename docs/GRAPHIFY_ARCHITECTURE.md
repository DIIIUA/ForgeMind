# ForgeMind architecture — v0.2.4-alpha

This is the current source map, maintained from the public tree. There are **103 main Kotlin files**, **66 built-in tools**, five LLM wire protocols and Room schema **8**. Counts describe source structure, not runtime coverage. The old Graphify analysis has not been regenerated: [historical report](history/GRAPHIFY_v0.2.0.md) and [historical tree](GRAPHIFY_TREE.html).

Это актуальная карта публичного кода: **103 основных Kotlin-файла**, **66 инструментов**, пять протоколов LLM, схема Room **8**. Старый граф Graphify сохранён как исторический; его метрики не выдаются за результат нового анализа.

All paths below are relative to `app/src/main/java/com/forgemind/app/`.

| Area | Entry points | Responsibility |
| --- | --- | --- |
| App and UI state | `ForgeMindApp.kt`, `MainActivity.kt`, `ui/MainViewModel.kt` | Dependency ownership, persistent screen state, chat and workspace coordination |
| Agent lifecycle | `agent/AgentEngine.kt`, `AgentForegroundService.kt`, `QueuedUserInput.kt`, `ContextHistoryPolicy.kt` | Streaming loop, queued/intervening user input, stop/recovery, compaction and history selection |
| Instructions and fallback | `SystemPromptComposer.kt`, `ModelFallbackApproval.kt`, `ModelFallbackSequence.kt`, `TextToolProtocol.kt` in `agent/` | Base/global/chat/profile composition, fallback consent, explicit text tool protocol and example guards |
| Tool surface | `agent/AgentTools.kt`, `AppLeverTools.kt`, `ToolCatalog.kt`, `ToolResultFormatter.kt` | 39 core + 27 app tools, complete schemas, media results, execution routing |
| LLM transport | `llm/LlmRouter.kt`, `OpenAiCompatibleClient.kt`, `OpenAiResponsesClient.kt`, `AnthropicClient.kt`, `GeminiClient.kt`, `OllamaNativeClient.kt` | Five protocols, streaming, reasoning, tools and images according to provider capability |
| Model discovery | `llm/ModelScanner.kt`, `LanAiScanner.kt`, `data/model/DomainModels.kt` | Catalog normalization, network discovery, provider and context configuration |
| Assistant profiles | `data/db/AssistantProfiles.kt`, `data/memory/AssistantRepository.kt`, `ui/screens/chat/AssistantProfilesDialog.kt` | Profile CRUD, categories, model/history defaults and chat assignment |
| Scoped memory | `data/db/MemoryEntities.kt`, `MemoryDao.kt`, `data/memory/MemoryRepository.kt`, `MemoryPolicy.kt` | CHAT/ASSISTANT/FOLDER/MODEL/GLOBAL scopes, approval, revision guards and bounded context assembly |
| Semantic ranking | `data/memory/LocalSemanticSearch.kt`, `SemanticMath.kt`, `WordPieceTokenizer.kt` | Pinned multilingual model download, ONNX CPU, token windows, pooling/projection, cached cosine ranking |
| Persistence and backup | `data/db/ForgeMindDatabase.kt`, `CompactionCheckpointEntity.kt`, `data/archive/ChatArchiveManager.kt`, `SettingsTransfer.kt`, `SettingsBackupCipher.kt` | Room migrations through 8, compaction checkpoints, archive format 3 and encrypted settings transfer |
| Shells and tasks | `bridge/EmbeddedAiShell.kt`, `TerminalSessionManager.kt`, `CommandTaskController.kt`, `CommandTaskStore.kt`, `CommandTaskService.kt`, `CancellableBlockingIo.kt` | Local shell, tab ownership, persistent task records, status/wait/cancel and blocking I/O cancellation |
| Termux/SSH routes | `bridge/TermuxBridge.kt`, `ManagedTermuxSessions.kt`, `SharedTermuxRuntime.kt`, `SharedTmuxExecutor.kt`, `SharedWorkspaceShell.kt`, `PtySessionClient.kt` | Permission-aware commands, SSH/SFTP, managed session isolation; shared runtime only where build/UID permits |
| Files and editing | `bridge/WorkspaceManager.kt`, `TolerantEdit.kt`, `UnifiedPatch.kt`, `InAppDocumentParser.kt` | Four workspace routes, file transfer, snapshots, patch checks and document extraction |
| Web and preview | `web/AgentBrowserController.kt`, `WebScraperEngine.kt`, `LocalPreviewServer.kt`, `ui/screens/preview/BrowserPage.kt` | Separate user/agent WebViews, page/site extraction, local preview and retained navigation state |
| Android integration | `accessibility/ForgeAccessibilityService.kt`, `AccessibilityConnectionWaiter.kt`, `PackageReplacedReceiver.kt`, `floating/FloatingChatService.kt` | Screen trees/actions/capture, connection recovery and overlay; permissions remain Android-controlled |
| MCP and voice | `mcp/`, `voice/` | Outbound MCP transports, on-device host, speech recognition/synthesis and hallucination filtering |

## Data flow

1. Chat selects provider/model, optional profile, workspace and instruction/history settings.
2. System instruction composition includes applicable approved memory. Semantic retrieval filters scope and enabled state before ranking; note proposals are never used as approved memory.
3. `LlmRouter` streams responses. Native or validated text calls reach the same registry and approval path; images remain typed tool output where supported.
4. Shell tasks, browser sessions and external tools have separate lifecycles from the visible Compose page. Browser navigation must not cancel the active agent run.
5. Room stores chat, profiles, memory/proposals and compaction state. Files/settings use their dedicated storage and export mechanisms.

## Build and verification boundaries

Public `debug` has `TERMUX_PLUGIN=false`; the optional `appIdOverride` changes package identity, not features. Shared-UID Termux integration is not activated by this public APK. See [installation](INSTALL.md).

JVM tests live in `app/src/test`; device/instrumentation scenarios in `app/src/androidTest`. A green JVM suite does not execute Android UI, Accessibility, ONNX native inference or real network servers. See the [release validation](releases/v0.2.4-alpha.md) for observed coverage.
