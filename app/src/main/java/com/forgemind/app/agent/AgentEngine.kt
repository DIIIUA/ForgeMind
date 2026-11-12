package com.forgemind.app.agent

import android.content.Context
import com.forgemind.app.data.db.ChatMessageDao
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.ChatSessionDao
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.PendingApproval
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ToolCallInfo
import com.forgemind.app.data.model.ToolExecutionCard
import com.forgemind.app.data.model.ToolExecutionStatus
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.llm.ContextCompactor
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.llm.LlmRouter
import com.forgemind.app.llm.LlmStreamEvent
import com.forgemind.app.llm.LlmWireMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AgentEngine(
    private val context: Context,
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao,
    private val settingsRepo: SettingsRepository,
    private val llmRouter: LlmRouter,
    private val compactor: ContextCompactor,
    private val toolRegistry: AgentToolRegistry
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _runningSessionId = MutableStateFlow<String?>(null)
    val runningSessionId: StateFlow<String?> = _runningSessionId.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingApproval>> = _pendingApprovals.asStateFlow()

    private val approvalWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val runStateLock = Any()
    private val compactionMutex = Mutex()
    private var activeJob: Job? = null

    fun resolveApproval(approvalId: String, allow: Boolean) {
        approvalWaiters.remove(approvalId)?.complete(allow)
        _pendingApprovals.value = _pendingApprovals.value.filterNot { it.id == approvalId }
    }

    fun cancelRun() {
        synchronized(runStateLock) {
            activeJob?.cancel()
            activeJob = null
            _isRunning.value = false
            _runningSessionId.value = null
            _statusBanner.value = null
        }
        approvalWaiters.values.forEach { it.complete(false) }
        approvalWaiters.clear()
        _pendingApprovals.value = emptyList()
        AgentForegroundService.stop(context)
    }

    fun sendUserTurn(
        sessionId: String,
        userText: String,
        attachments: List<ChatAttachment>,
        onReplyComplete: ((assistantText: String) -> Unit)? = null
    ) {
        if (_isRunning.value) return
        launchAgentJob(sessionId, onReplyComplete) {
            val session = sessionDao.getById(sessionId) ?: return@launchAgentJob ""
            val nextOrder = messageDao.getMaxOrderIndex(sessionId) + 1
            val userMsg = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                orderIndex = nextOrder,
                role = LlmRole.USER.name,
                content = userText,
                attachmentsJson = LlmHttpFactory.json.encodeToString(attachments)
            )
            messageDao.upsert(userMsg)

            val autoTitle = if (session.title == "Новый чат" && userText.isNotBlank()) {
                userText.replace('\n', ' ').trim().take(42)
            } else session.title
            if (autoTitle != session.title) {
                sessionDao.updateTitle(session.id, autoTitle)
            } else {
                sessionDao.touchUpdatedAt(session.id)
            }

            runAgentLoop(session.copy(title = autoTitle))
        }
    }

    fun retryFromMessage(
        sessionId: String,
        targetMessage: ChatMessageEntity,
        onReplyComplete: ((assistantText: String) -> Unit)? = null
    ) {
        launchAgentJob(sessionId, onReplyComplete) {
            var session = sessionDao.getById(sessionId) ?: return@launchAgentJob ""
            val allMsgs = messageDao.getForSession(sessionId)
            val anchor = if (targetMessage.role == LlmRole.TOOL.name) {
                allMsgs.lastOrNull { it.orderIndex <= targetMessage.orderIndex && it.role != LlmRole.TOOL.name }
                    ?: targetMessage
            } else {
                targetMessage
            }
            val trimFromOrder = if (anchor.role == LlmRole.USER.name) {
                anchor.orderIndex + 1
            } else {
                anchor.orderIndex
            }
            messageDao.deleteFromOrder(sessionId, trimFromOrder)
            if (trimFromOrder <= session.compactedUpToOrder && session.compactedUpToOrder > 0) {
                sessionDao.updateCompaction(sessionId, "", 0, 0)
                session = sessionDao.getById(sessionId) ?: session.copy(compactedSummary = "", compactedUpToOrder = 0)
            }
            sessionDao.touchUpdatedAt(sessionId)
            runAgentLoop(session)
        }
    }

    fun editUserMessage(
        sessionId: String,
        userMessage: ChatMessageEntity,
        newContent: String,
        resend: Boolean,
        onReplyComplete: ((assistantText: String) -> Unit)? = null
    ) {
        val clean = newContent.trim()
        val hasAttachments = userMessage.attachmentsJson.isNotBlank() && userMessage.attachmentsJson != "[]"
        if (clean.isEmpty() && !hasAttachments) return
        if (!resend) {
            scope.launch {
                messageDao.upsert(userMessage.copy(content = clean))
                sessionDao.touchUpdatedAt(sessionId)
            }
            return
        }
        launchAgentJob(sessionId, onReplyComplete) {
            var session = sessionDao.getById(sessionId) ?: return@launchAgentJob ""
            messageDao.upsert(userMessage.copy(content = clean))
            val trimFromOrder = userMessage.orderIndex + 1
            messageDao.deleteFromOrder(sessionId, trimFromOrder)
            if (userMessage.orderIndex < session.compactedUpToOrder && session.compactedUpToOrder > 0) {
                sessionDao.updateCompaction(sessionId, "", 0, 0)
                session = sessionDao.getById(sessionId) ?: session.copy(compactedSummary = "", compactedUpToOrder = 0)
            }
            sessionDao.touchUpdatedAt(sessionId)
            runAgentLoop(session)
        }
    }

    private fun launchAgentJob(
        sessionId: String,
        onReplyComplete: ((assistantText: String) -> Unit)?,
        block: suspend () -> String
    ) {
        synchronized(runStateLock) {
            if (_isRunning.value) return
            _isRunning.value = true
            _runningSessionId.value = sessionId
            lateinit var launched: Job
            launched = scope.launch {
                val thisJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                AgentForegroundService.start(context, "ForgeMind обрабатывает запрос...")
                try {
                    val finalAssistantText = block()
                    if (finalAssistantText.isNotBlank()) {
                        onReplyComplete?.invoke(finalAssistantText)
                    }
                } catch (_: CancellationException) {
                    // Already finalized in stream catch
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val nextOrder = messageDao.getMaxOrderIndex(sessionId) + 1
                    messageDao.upsert(
                        ChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            orderIndex = nextOrder,
                            role = LlmRole.ASSISTANT.name,
                            content = "Неожиданная ошибка агента: ${t.message ?: t::class.java.simpleName}",
                            isError = true
                        )
                    )
                } finally {
                    val isCurrent = synchronized(runStateLock) {
                        if (activeJob === thisJob) {
                            activeJob = null
                            _isRunning.value = false
                            _runningSessionId.value = null
                            _statusBanner.value = null
                            true
                        } else {
                            false
                        }
                    }
                    if (isCurrent) {
                        AgentForegroundService.stop(context)
                    }
                }
            }
            activeJob = launched
        }
    }

    suspend fun compactSessionNow(sessionId: String): Result<String> = runCatching {
        compactionMutex.withLock {
            val session = sessionDao.getById(sessionId) ?: error("Сессия не найдена")
            val allMsgs = messageDao.getForSession(sessionId).filter { it.orderIndex >= session.compactedUpToOrder }
            val keepTail = 4
            val splitIdx = safeCompactionSplitIndex(allMsgs, keepTail)
            if (splitIdx <= 0) error("Диалог слишком короткий для сжатия")
            val toCompact = allMsgs.take(splitIdx)
            val cfg = settingsRepo.getProviderConfig(session.providerId)
                .copy(modelId = session.modelId.ifBlank { settingsRepo.getProviderConfig(session.providerId).modelId })
            val outcome = compactor.compactMessages(cfg, session.compactedSummary, toCompact)
            val remaining = allMsgs.drop(splitIdx)
            val newEst = compactor.estimateSessionTokens(buildSystemPrompt(session, settingsRepo.agentSettings.value), outcome.newSummary, remaining)
            val effectiveUpToOrder = outcome.compactedUpToOrder.coerceAtLeast(session.compactedUpToOrder)
            sessionDao.updateCompaction(
                id = session.id,
                summary = outcome.newSummary,
                upToOrder = effectiveUpToOrder,
                tokens = newEst
            )
            "Контекст сжат: ${outcome.tokensBefore} -> ${outcome.tokensAfter} токенов (LLM=${outcome.usedLlm})."
        }
    }

    private data class TurnStreamOutcome(
        val assistantMsg: ChatMessageEntity,
        val cleanedText: String,
        val finalThink: String,
        val finalContent: String,
        val hasDoneMarker: Boolean,
        val streamErr: String?,
        val toolCalls: List<ToolCallInfo>,
        val hardStreamFailure: Boolean
    )

    private suspend fun runAgentLoop(initialSession: ChatSessionEntity): String {
        var session = initialSession
        val agentSettings = settingsRepo.agentSettings.value
        val providerCfg = settingsRepo.getProviderConfig(session.providerId)
            .copy(modelId = session.modelId.ifBlank { settingsRepo.getProviderConfig(session.providerId).modelId })
        val defaultEnv = WorkspaceEnvType.fromId(session.workspaceEnv)

        var tools = toolRegistry.buildToolSpecs(agentSettings)
        val recentSignatures = ArrayDeque<String>(6)
        var lastVisibleText = ""
        val maxTurns = normalizeMaxTurns(agentSettings.maxTurns)
        val thinkEvery = normalizeThinkInterval(agentSettings.thinkEveryToolCalls)
        var toolCallsSinceThink = 0
        var forceThinkTurn = false

        for (turn in 1..maxTurns) {
            session = sessionDao.getById(session.id) ?: break
            val sysPrompt = buildSystemPrompt(session, agentSettings)
            session = maybeAutoCompact(session, agentSettings, providerCfg, sysPrompt)

            val currentMsgs = messageDao.getForSession(session.id).filter { it.orderIndex >= session.compactedUpToOrder }
            val isReflectionStep = forceThinkTurn
            val wireMessages = if (isReflectionStep) {
                buildReflectionWireHistory(
                    systemPrompt = sysPrompt,
                    compactedSummary = session.compactedSummary,
                    messages = currentMsgs,
                    callsInStreak = toolCallsSinceThink,
                    threshold = thinkEvery
                )
            } else {
                buildWireHistory(sysPrompt, session.compactedSummary, currentMsgs)
            }
            val toolsForTurn = if (isReflectionStep) emptyList() else tools

            val outcome = streamAssistantTurn(
                session = session,
                providerCfg = providerCfg,
                wireMessages = wireMessages,
                toolsForTurn = toolsForTurn,
                isReflectionStep = isReflectionStep,
                toolCallsSinceThink = toolCallsSinceThink
            )
            if (outcome.hardStreamFailure) return ""

            if (outcome.cleanedText.isNotBlank() || !isReflectionStep) {
                lastVisibleText = outcome.cleanedText
            }

            if (isReflectionStep) {
                messageDao.upsert(
                    outcome.assistantMsg.copy(
                        content = outcome.finalContent,
                        thinking = outcome.finalThink,
                        isStreaming = false,
                        isError = outcome.streamErr != null && outcome.cleanedText.isBlank()
                    )
                )
                sessionDao.touchUpdatedAt(session.id)
                toolCallsSinceThink = 0
                forceThinkTurn = false
                if (outcome.hasDoneMarker || (outcome.streamErr != null && outcome.cleanedText.isBlank())) {
                    return lastVisibleText
                }
                continue
            }

            if (outcome.toolCalls.isEmpty()) {
                messageDao.upsert(
                    outcome.assistantMsg.copy(
                        content = outcome.finalContent,
                        thinking = outcome.finalThink,
                        isStreaming = false,
                        isError = outcome.streamErr != null && outcome.cleanedText.isBlank()
                    )
                )
                sessionDao.touchUpdatedAt(session.id)
                return lastVisibleText
            }

            val turnSig = outcome.toolCalls.joinToString("|") { "${it.name}:${it.argumentsJson.hashCode()}" }
            recentSignatures.addLast(turnSig)
            if (recentSignatures.size > 4) recentSignatures.removeFirst()
            val isLooping = recentSignatures.size == 4 &&
                recentSignatures.elementAt(0) == recentSignatures.elementAt(2) &&
                recentSignatures.elementAt(1) == recentSignatures.elementAt(3)

            val needSchemaRefresh = executeToolBatch(
                session = session,
                initialAssistantMsg = outcome.assistantMsg,
                cleanedText = outcome.cleanedText,
                finalThink = outcome.finalThink,
                toolCalls = outcome.toolCalls,
                defaultEnv = defaultEnv,
                agentSettings = agentSettings,
                isLooping = isLooping
            )
            if (needSchemaRefresh) {
                tools = toolRegistry.buildToolSpecs(agentSettings)
            }
            toolCallsSinceThink += outcome.toolCalls.size
            if (shouldTriggerForcedReflection(toolCallsSinceThink, thinkEvery)) {
                forceThinkTurn = true
            }
        }

        val maxOrder = messageDao.getMaxOrderIndex(session.id) + 1
        messageDao.upsert(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = session.id,
                orderIndex = maxOrder,
                role = LlmRole.ASSISTANT.name,
                content = "⚠️ Достигнут максимальный лимит ходов агента ($maxTurns). Можете попросить продолжить или увеличить лимит в Настройках."
            )
        )
        return lastVisibleText
    }

    private suspend fun maybeAutoCompact(
        session: ChatSessionEntity,
        agentSettings: AgentSettings,
        providerCfg: ProviderConfig,
        sysPrompt: String
    ): ChatSessionEntity = compactionMutex.withLock {
        val freshSession = sessionDao.getById(session.id) ?: session
        val activeMsgs = messageDao.getForSession(freshSession.id).filter { it.orderIndex >= freshSession.compactedUpToOrder }
        val estTokens = compactor.estimateSessionTokens(sysPrompt, freshSession.compactedSummary, activeMsgs)
        sessionDao.updateContextTokens(freshSession.id, estTokens)

        if (!agentSettings.autoCompactContext ||
            !compactor.shouldAutoCompact(estTokens, providerCfg.contextWindow, agentSettings.compactThresholdPercent, activeMsgs.size)
        ) {
            return@withLock freshSession
        }

        val keepTail = 6
        val splitIdx = safeCompactionSplitIndex(activeMsgs, keepTail)
        if (splitIdx <= 0) return@withLock freshSession

        _statusBanner.value = "Сжатие длинного контекста..."
        val toCompact = activeMsgs.take(splitIdx)
        val outcome = compactor.compactMessages(providerCfg, freshSession.compactedSummary, toCompact)
        val afterEst = compactor.estimateSessionTokens(sysPrompt, outcome.newSummary, activeMsgs.drop(splitIdx))
        val effectiveUpToOrder = outcome.compactedUpToOrder.coerceAtLeast(freshSession.compactedUpToOrder)
        val updated = freshSession.copy(
            compactedSummary = outcome.newSummary,
            compactedUpToOrder = effectiveUpToOrder,
            estimatedContextTokens = afterEst
        )
        sessionDao.updateCompaction(
            id = freshSession.id,
            summary = outcome.newSummary,
            upToOrder = effectiveUpToOrder,
            tokens = afterEst
        )
        updated
    }

    private suspend fun streamAssistantTurn(
        session: ChatSessionEntity,
        providerCfg: ProviderConfig,
        wireMessages: List<LlmWireMessage>,
        toolsForTurn: List<com.forgemind.app.llm.LlmToolSpec>,
        isReflectionStep: Boolean,
        toolCallsSinceThink: Int
    ): TurnStreamOutcome {
        val orderIdx = messageDao.getMaxOrderIndex(session.id) + 1
        var assistantMsg = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            orderIndex = orderIdx,
            role = LlmRole.ASSISTANT.name,
            providerId = providerCfg.providerId,
            modelId = providerCfg.modelId,
            isStreaming = true
        )
        messageDao.upsert(assistantMsg)

        _statusBanner.value = if (isReflectionStep) {
            "Пауза на размышление после $toolCallsSinceThink вызовов (${providerCfg.modelId})..."
        } else {
            "Модель отвечает (${providerCfg.modelId})..."
        }
        val textBuf = StringBuilder()
        val thinkBuf = StringBuilder()
        val toolCalls = mutableListOf<ToolCallInfo>()
        var streamErr: String? = null
        var lastFlushMs = 0L

        try {
            llmRouter.streamChat(providerCfg, wireMessages, toolsForTurn).collect { ev ->
                when (ev) {
                    is LlmStreamEvent.TextDelta -> {
                        textBuf.append(ev.text)
                        val now = System.currentTimeMillis()
                        if (now - lastFlushMs >= 90L) {
                            lastFlushMs = now
                            val (liveThink, liveText) = splitLiveEmbeddedThink(textBuf.toString())
                            val mergedThink = buildString {
                                if (thinkBuf.isNotEmpty()) append(thinkBuf)
                                if (!liveThink.isNullOrBlank()) {
                                    if (isNotEmpty()) append("\n\n")
                                    append(liveThink)
                                }
                            }
                            assistantMsg = assistantMsg.copy(content = liveText, thinking = mergedThink)
                            messageDao.upsert(assistantMsg)
                        }
                    }
                    is LlmStreamEvent.ThinkingDelta -> {
                        thinkBuf.append(ev.text)
                        val now = System.currentTimeMillis()
                        if (now - lastFlushMs >= 110L) {
                            lastFlushMs = now
                            assistantMsg = assistantMsg.copy(thinking = thinkBuf.toString())
                            messageDao.upsert(assistantMsg)
                        }
                    }
                    is LlmStreamEvent.ToolCallReady -> toolCalls += ev.toolCall
                    is LlmStreamEvent.Usage -> {
                        assistantMsg = assistantMsg.copy(
                            promptTokens = ev.promptTokens,
                            completionTokens = ev.completionTokens
                        )
                        val totalUsed = ev.promptTokens + ev.completionTokens
                        if (totalUsed > 0) sessionDao.updateContextTokens(session.id, totalUsed)
                    }
                    is LlmStreamEvent.StatusNote -> _statusBanner.value = ev.text
                    is LlmStreamEvent.Error -> streamErr = ev.message
                    is LlmStreamEvent.Done -> Unit
                }
            }
        } catch (ce: CancellationException) {
            val (extractedThink, rawCleanedText) = splitLiveEmbeddedThink(textBuf.toString())
            val (cleanedCancelled, _) = stripTaskDoneMarker(rawCleanedText)
            val stoppedThink = buildString {
                if (thinkBuf.isNotEmpty()) append(thinkBuf)
                if (!extractedThink.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(extractedThink)
                }
            }
            val stoppedContent = if (cleanedCancelled.isNotEmpty()) "$cleanedCancelled\n\n*(остановлено)*" else ""
            withContext(NonCancellable) {
                messageDao.upsert(
                    assistantMsg.copy(
                        content = stoppedContent,
                        thinking = stoppedThink,
                        isStreaming = false
                    )
                )
            }
            throw ce
        }

        if (streamErr != null && textBuf.isEmpty() && toolCalls.isEmpty()) {
            messageDao.upsert(
                assistantMsg.copy(
                    content = "⚠️ $streamErr",
                    thinking = thinkBuf.toString(),
                    isStreaming = false,
                    isError = true
                )
            )
            return TurnStreamOutcome(assistantMsg, "", "", "", false, streamErr, toolCalls, hardStreamFailure = true)
        }

        val (extractedThink, rawCleanedText) = splitLiveEmbeddedThink(textBuf.toString())
        val (cleanedText, hasDoneMarker) = stripTaskDoneMarker(rawCleanedText)
        val finalThink = buildString {
            if (thinkBuf.isNotEmpty()) append(thinkBuf)
            if (!extractedThink.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(extractedThink)
            }
        }
        val finalContent = buildString {
            append(cleanedText)
            if (streamErr != null && toolCalls.isEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("⚠️ *(стрим прерван: $streamErr)*")
            } else if (isBlank() && toolCalls.isEmpty() && finalThink.isNotBlank()) {
                append(
                    if (isReflectionStep && !hasDoneMarker) {
                        "*(Пауза на осмысление: см. «Размышления модели» выше — продолжаю выполнение.)*"
                    } else {
                        "*(Модель завершила ответ только блоком размышлений — откройте «Размышления модели» выше.)*"
                    }
                )
            }
        }
        return TurnStreamOutcome(
            assistantMsg = assistantMsg,
            cleanedText = cleanedText,
            finalThink = finalThink,
            finalContent = finalContent,
            hasDoneMarker = hasDoneMarker,
            streamErr = streamErr,
            toolCalls = toolCalls,
            hardStreamFailure = false
        )
    }

    private suspend fun executeToolBatch(
        session: ChatSessionEntity,
        initialAssistantMsg: ChatMessageEntity,
        cleanedText: String,
        finalThink: String,
        toolCalls: List<ToolCallInfo>,
        defaultEnv: WorkspaceEnvType,
        agentSettings: AgentSettings,
        isLooping: Boolean
    ): Boolean {
        val cards = toolCalls.map { tc ->
            ToolExecutionCard(
                callId = tc.id,
                toolName = tc.name,
                status = ToolExecutionStatus.RUNNING,
                argumentsJson = tc.argumentsJson
            )
        }.toMutableList()

        var assistantMsg = initialAssistantMsg.copy(
            content = cleanedText,
            thinking = finalThink,
            toolCallsJson = LlmHttpFactory.json.encodeToString(toolCalls),
            toolCardsJson = LlmHttpFactory.json.encodeToString(cards),
            isStreaming = false
        )
        messageDao.upsert(assistantMsg)

        _statusBanner.value = "Выполнение инструментов (${toolCalls.size})..."
        val canRunParallel = agentSettings.parallelToolCalls &&
            toolCalls.size > 1 &&
            toolCalls.none { toolRegistry.requiresApproval(it.name, it.argumentsJson, agentSettings) }

        val results: List<Pair<ToolCallInfo, ToolRunOutput>> = if (canRunParallel) {
            val indexedResults = coroutineScope {
                toolCalls.mapIndexed { idx, tc ->
                    async {
                        val t0 = System.currentTimeMillis()
                        val out = toolRegistry.execute(
                            toolName = tc.name,
                            argsJson = tc.argumentsJson,
                            defaultEnv = defaultEnv,
                            internalProject = session.workspacePath,
                            sessionId = session.id,
                            messageId = assistantMsg.id,
                            agentSettings = agentSettings,
                            searchSettings = settingsRepo.searchSettings.value
                        )
                        val dur = System.currentTimeMillis() - t0
                        Triple(idx, tc, out to dur)
                    }
                }.awaitAll()
            }
            indexedResults.map { (idx, tc, outAndDur) ->
                val (out, dur) = outAndDur
                cards[idx] = cards[idx].copy(
                    status = if (out.isError) ToolExecutionStatus.ERROR else ToolExecutionStatus.SUCCESS,
                    resultPreview = out.text.take(800),
                    diffText = out.diffText,
                    snapshotId = out.snapshotId,
                    mcpServerName = out.mcpServerName,
                    durationMs = dur
                )
                tc to out
            }
        } else {
            toolCalls.mapIndexed { idx, tc ->
                executeSequentialToolCall(
                    idx = idx,
                    tc = tc,
                    cards = cards,
                    assistantMsg = assistantMsg,
                    session = session,
                    defaultEnv = defaultEnv,
                    agentSettings = agentSettings
                )
            }
        }

        val outgoingAtts = mutableListOf<ChatAttachment>()
        var needSchemaRefresh = false
        for ((_, out) in results) {
            outgoingAtts += out.outgoingAttachments
            if (out.refreshToolSchemas) needSchemaRefresh = true
        }

        assistantMsg = assistantMsg.copy(
            toolCardsJson = LlmHttpFactory.json.encodeToString(cards),
            attachmentsJson = LlmHttpFactory.json.encodeToString(outgoingAtts)
        )
        messageDao.upsert(assistantMsg)

        var toolOrder = messageDao.getMaxOrderIndex(session.id) + 1
        for ((tc, out) in results) {
            val loopNote = if (isLooping) "\n\n[СИСТЕМНОЕ ПРЕДУПРЕЖДЕНИЕ: Обнаружен повтор вызовов инструментов. Не повторяй те же вызовы — измени подход или дай финальный ответ пользователю.]" else ""
            messageDao.upsert(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = session.id,
                    orderIndex = toolOrder++,
                    role = LlmRole.TOOL.name,
                    content = out.text.take(14_000) + loopNote,
                    toolCallId = tc.id,
                    toolName = tc.name,
                    isError = out.isError
                )
            )
        }
        return needSchemaRefresh
    }

    private suspend fun executeSequentialToolCall(
        idx: Int,
        tc: ToolCallInfo,
        cards: MutableList<ToolExecutionCard>,
        assistantMsg: ChatMessageEntity,
        session: ChatSessionEntity,
        defaultEnv: WorkspaceEnvType,
        agentSettings: AgentSettings
    ): Pair<ToolCallInfo, ToolRunOutput> {
        val needsApprove = toolRegistry.requiresApproval(tc.name, tc.argumentsJson, agentSettings)
        val allowed = if (needsApprove) {
            val appId = UUID.randomUUID().toString()
            cards[idx] = cards[idx].copy(status = ToolExecutionStatus.NEEDS_APPROVAL)
            messageDao.upsert(assistantMsg.copy(toolCardsJson = LlmHttpFactory.json.encodeToString(cards)))

            val waiter = CompletableDeferred<Boolean>()
            approvalWaiters[appId] = waiter
            _pendingApprovals.value = _pendingApprovals.value + PendingApproval(
                id = appId,
                toolName = tc.name,
                title = "Разрешить ${tc.name}?",
                details = tc.argumentsJson.take(600)
            )
            waiter.await()
        } else true

        if (!allowed) {
            cards[idx] = cards[idx].copy(
                status = ToolExecutionStatus.DENIED,
                resultPreview = "Отклонено пользователем"
            )
            messageDao.upsert(assistantMsg.copy(toolCardsJson = LlmHttpFactory.json.encodeToString(cards)))
            return tc to ToolRunOutput("Пользователь отклонил выполнение ${tc.name}.", isError = true)
        }

        cards[idx] = cards[idx].copy(status = ToolExecutionStatus.RUNNING)
        messageDao.upsert(assistantMsg.copy(toolCardsJson = LlmHttpFactory.json.encodeToString(cards)))
        val t0 = System.currentTimeMillis()
        val out = toolRegistry.execute(
            toolName = tc.name,
            argsJson = tc.argumentsJson,
            defaultEnv = defaultEnv,
            internalProject = session.workspacePath,
            sessionId = session.id,
            messageId = assistantMsg.id,
            agentSettings = agentSettings,
            searchSettings = settingsRepo.searchSettings.value
        )
        val dur = System.currentTimeMillis() - t0
        cards[idx] = cards[idx].copy(
            status = if (out.isError) ToolExecutionStatus.ERROR else ToolExecutionStatus.SUCCESS,
            resultPreview = out.text.take(800),
            diffText = out.diffText,
            snapshotId = out.snapshotId,
            mcpServerName = out.mcpServerName,
            durationMs = dur
        )
        messageDao.upsert(assistantMsg.copy(toolCardsJson = LlmHttpFactory.json.encodeToString(cards)))
        return tc to out
    }

    companion object {
        const val TASK_DONE_MARKER = "[[TASK_DONE]]"
        const val MAX_TURNS_UPPER_BOUND = 500
        const val THINK_INTERVAL_UPPER_BOUND = 200

        fun normalizeMaxTurns(value: Int): Int = value.coerceIn(1, MAX_TURNS_UPPER_BOUND)

        fun normalizeThinkInterval(value: Int): Int = value.coerceIn(0, THINK_INTERVAL_UPPER_BOUND)

        fun shouldTriggerForcedReflection(toolCallsSinceThink: Int, thinkEveryToolCalls: Int): Boolean {
            val threshold = normalizeThinkInterval(thinkEveryToolCalls)
            return threshold > 0 && toolCallsSinceThink >= threshold
        }

        fun stripTaskDoneMarker(text: String): Pair<String, Boolean> {
            val idx = text.indexOf(TASK_DONE_MARKER, ignoreCase = true)
            if (idx < 0) return text to false
            val cleaned = text.replace(Regex("\\[\\[TASK_DONE\\]\\]", RegexOption.IGNORE_CASE), "").trim()
            return cleaned to true
        }

        fun buildForcedReflectionPrompt(callsInStreak: Int, threshold: Int): String = buildString {
            appendLine("=== ПРИНУДИТЕЛЬНАЯ ПАУЗА НА ОСМЫСЛЕНИЕ ===")
            appendLine("Выполнено вызовов команд/инструментов подряд: $callsInStreak (порог серии: $threshold). Инструменты на этот шаг отключены.")
            appendLine("Перед продолжением ОБЯЗАТЕЛЬНО остановись и осмысли происходящее:")
            appendLine("1. Что уже реально сделано и подтверждено результатами инструментов?")
            appendLine("2. Нет ли зацикливания, лишних повторов или ошибочной гипотезы?")
            appendLine("3. Что именно осталось сделать и какой самый точный следующий шаг?")
            appendLine("- Если задача ЕЩЁ НЕ ЗАВЕРШЕНА: запиши краткий анализ и чёткий план продолжения — на следующем шаге инструменты снова включатся.")
            append("- Если задача УЖЕ ПОЛНОСТЬЮ ЗАВЕРШЕНА и команды больше не нужны: напиши итоговый ответ пользователю и добавь в самом конце маркер $TASK_DONE_MARKER.")
        }

        fun buildWireHistory(
            systemPrompt: String,
            compactedSummary: String,
            messages: List<ChatMessageEntity>
        ): List<LlmWireMessage> = buildWireHistoryInternal(
            systemPrompt = systemPrompt,
            compactedSummary = compactedSummary,
            messages = messages,
            plainTextToolHistory = false
        )

        fun buildReflectionWireHistory(
            systemPrompt: String,
            compactedSummary: String,
            messages: List<ChatMessageEntity>,
            callsInStreak: Int,
            threshold: Int
        ): List<LlmWireMessage> {
            val reflectionSys = buildString {
                appendLine(systemPrompt)
                appendLine()
                append(buildForcedReflectionPrompt(callsInStreak, threshold))
            }
            return buildWireHistoryInternal(
                systemPrompt = reflectionSys,
                compactedSummary = compactedSummary,
                messages = messages,
                plainTextToolHistory = true
            )
        }

        fun safeCompactionSplitIndex(msgs: List<ChatMessageEntity>, keepTail: Int): Int {
            if (msgs.size <= keepTail) return 0
            var splitIdx = msgs.size - keepTail
            while (splitIdx > 0 && msgs[splitIdx].role == LlmRole.TOOL.name) {
                splitIdx--
            }
            if (splitIdx > 0) {
                val boundary = msgs[splitIdx]
                val hasCalls = boundary.role == LlmRole.ASSISTANT.name &&
                    boundary.toolCallsJson.isNotBlank() &&
                    boundary.toolCallsJson != "[]"
                val nextIsTool = msgs.getOrNull(splitIdx + 1)?.role == LlmRole.TOOL.name
                if (hasCalls && !nextIsTool) {
                    // Boundary assistant has dangling calls; keep intact boundary earlier
                }
            }
            return splitIdx.coerceAtLeast(0)
        }

        private fun buildWireHistoryInternal(
            systemPrompt: String,
            compactedSummary: String,
            messages: List<ChatMessageEntity>,
            plainTextToolHistory: Boolean
        ): List<LlmWireMessage> {
            val wire = mutableListOf<LlmWireMessage>()
            val fullSys = if (compactedSummary.isNotBlank()) {
                "$systemPrompt\n\n[СЖАТАЯ ПАМЯТЬ ПРЕДЫДУЩЕГО ДИАЛОГА]\n$compactedSummary\n[/СЖАТАЯ ПАМЯТЬ]"
            } else systemPrompt
            wire += LlmWireMessage(role = LlmRole.SYSTEM, content = fullSys)

            val pendingToolResults = StringBuilder()
            fun appendOrMergeUser(text: String, atts: List<ChatAttachment> = emptyList()) {
                val prev = wire.lastOrNull()
                if (prev != null && prev.role == LlmRole.USER) {
                    val combinedText = listOf(prev.content, text).filter { it.isNotBlank() }.joinToString("\n\n")
                    wire[wire.lastIndex] = prev.copy(
                        content = combinedText,
                        attachments = prev.attachments + atts
                    )
                } else {
                    wire += LlmWireMessage(role = LlmRole.USER, content = text, attachments = atts)
                }
            }

            fun flushPendingToolResults() {
                if (pendingToolResults.isNotEmpty()) {
                    appendOrMergeUser(pendingToolResults.toString().trim())
                    pendingToolResults.clear()
                }
            }

            // Track open tool_call IDs expected after the immediately preceding ASSISTANT message
            val expectedToolCallQueue = ArrayDeque<ToolCallInfo>()

            fun flushDanglingToolCallsAsInterrupted() {
                while (expectedToolCallQueue.isNotEmpty()) {
                    val tc = expectedToolCallQueue.removeFirst()
                    val fallbackText = "Прервано: результат инструмента ${tc.name} не был сохранён."
                    if (plainTextToolHistory) {
                        if (pendingToolResults.isNotEmpty()) pendingToolResults.append("\n\n")
                        pendingToolResults.append("[Результат ${tc.name} (ПРЕРВАНО)]:\n$fallbackText")
                    } else {
                        wire += LlmWireMessage(
                            role = LlmRole.TOOL,
                            content = fallbackText,
                            toolCallId = tc.id,
                            toolName = tc.name
                        )
                    }
                }
            }

            for (m in messages) {
                val role = runCatching { LlmRole.valueOf(m.role) }.getOrDefault(LlmRole.USER)
                val tcs = runCatching {
                    LlmHttpFactory.json.decodeFromString<List<ToolCallInfo>>(m.toolCallsJson)
                }.getOrDefault(emptyList())
                val atts = runCatching {
                    LlmHttpFactory.json.decodeFromString<List<ChatAttachment>>(m.attachmentsJson)
                }.getOrDefault(emptyList())
                if (role == LlmRole.ASSISTANT && m.isError && tcs.isEmpty()) continue
                if ((role == LlmRole.ASSISTANT || role == LlmRole.USER) && m.content.isBlank() && tcs.isEmpty() && atts.isEmpty()) continue

                if (role == LlmRole.TOOL) {
                    if (expectedToolCallQueue.isEmpty()) {
                        // Orphaned TOOL row with no active parent ASSISTANT tool_call — drop it
                        continue
                    }
                    val callId = m.toolCallId
                    if (callId != null) {
                        expectedToolCallQueue.removeFirstOrNullMatching { it.id == callId }
                    } else {
                        expectedToolCallQueue.removeFirst()
                    }
                } else if (expectedToolCallQueue.isNotEmpty()) {
                    flushDanglingToolCallsAsInterrupted()
                }

                if (plainTextToolHistory) {
                    if (role == LlmRole.TOOL) {
                        if (pendingToolResults.isNotEmpty()) pendingToolResults.append("\n\n")
                        val statusTag = if (m.isError) " (ОШИБКА)" else ""
                        pendingToolResults.append("[Результат ${m.toolName ?: "tool"}$statusTag]:\n${m.content.take(4_000)}")
                        continue
                    }
                    flushPendingToolResults()
                    val plainContent = if (role == LlmRole.ASSISTANT && tcs.isNotEmpty()) {
                        buildString {
                            if (m.content.isNotBlank()) appendLine(m.content)
                            tcs.forEach { tc ->
                                appendLine("[Вызов ${tc.name}: ${tc.argumentsJson.take(400)}]")
                            }
                        }.trim()
                    } else m.content
                    if (role == LlmRole.USER) {
                        appendOrMergeUser(plainContent, atts)
                    } else {
                        wire += LlmWireMessage(role = role, content = plainContent, attachments = atts)
                    }
                } else {
                    wire += LlmWireMessage(
                        role = role,
                        content = m.content,
                        toolCalls = tcs,
                        toolCallId = m.toolCallId,
                        toolName = m.toolName,
                        attachments = atts
                    )
                }

                if (role == LlmRole.ASSISTANT && tcs.isNotEmpty()) {
                    tcs.forEach { expectedToolCallQueue.addLast(it) }
                }
            }

            if (expectedToolCallQueue.isNotEmpty()) {
                flushDanglingToolCallsAsInterrupted()
            }
            if (plainTextToolHistory) {
                flushPendingToolResults()
            } else if (wire.lastOrNull()?.role == LlmRole.ASSISTANT) {
                // Ensure next turn after an intermediate reflection message does not end with ASSISTANT prefill
                wire += LlmWireMessage(
                    role = LlmRole.USER,
                    content = "Продолжай выполнение задачи по намеченному плану."
                )
            }
            return wire
        }

        private inline fun <T> ArrayDeque<T>.removeFirstOrNullMatching(predicate: (T) -> Boolean): T? {
            val iter = iterator()
            while (iter.hasNext()) {
                val item = iter.next()
                if (predicate(item)) {
                    iter.remove()
                    return item
                }
            }
            return if (isNotEmpty()) removeFirst() else null
        }

        fun buildEcosystemAndOuterContourGuide(): String = buildString {
            appendLine("Ты — ForgeMind, AI-агент и инженерный ассистент внутри Android-приложения ForgeMind (вкладки приложения: Чат, Файлы ИИ, Браузер, Настройки).")
            appendLine()
            appendLine("=== ВОЗМОЖНОСТИ ПРИЛОЖЕНИЯ FORGEMIND ===")
            appendLine("- Файловые среды: внутренняя песочница приложения ('internal'), выбранная пользователем папка Android SAF ('saf') и пользовательские SSH/SFTP-подключения ('termux_ssh'), настроенные во вкладке Настройки.")
            appendLine("- Выполнение команд и редактирование кода: работай через встроенные файловые, shell- и diff-инструменты строго в пределах активного окружения и разрешений пользователя.")
            appendLine("- Внутренние рычаги приложения (пассивный фоновый режим): у тебя есть прямые инструменты для фонового буфера редактора кода (`editor_*`), изолированного фонового Агентного браузера (`browser_agent_*`), локального HTTP-сервера (`preview_server_control`), вкладок терминала (`terminal_*`), снимков отката (`snapshot_*`), истории и экспорта чатов (`chat_*`), сжатия контекста (`context_compact`), навигации по вкладкам только по просьбе пользователя (`app_navigate`) и озвучивания (`voice_speak`). Пользователь и агент не мешают друг другу — его экран и обычный браузер остаются неприкосновенными.")
            appendLine("- Подключаемые MCP-серверы: если пользователь добавил и включил MCP-серверы в Настройках, используй `mcp_list_servers` и доступные инструменты `mcp_*` / `mcp_call_tool` (включая Code Mode или шлюзы поиска инструментов, если конкретный MCP-сервер их предоставляет).")
            appendLine("- Проверка фактов: долгие фоновые команды опрашивай до завершения, файлы дочитывай целиком и опирайся только на реальные ответы инструментов (`exit_code`, `stdout`, `stderr`, размер/содержимое).")
            append("- Если ты создал или получил изображение/скриншот (`png`/`jpg`/`webp`) в файле и оно ещё не передано вложением от MCP, вызови `send_file_to_chat`, чтобы пользователь увидел превью прямо в чате.")
        }

        fun buildAppLeversManual(): String = buildString {
            appendLine("=== ПОЛНОЕ ОПИСАНИЕ ВСТРОЕННЫХ РЫЧАГОВ ПРИЛОЖЕНИЯ (ВСЁ ДОСТУПНО СРАЗУ ПРИ ВХОДЕ) ===")
            appendLine("Тебе не нужно искать или читать исходный код приложения, чтобы понять его устройство: все рычаги встроены и описаны ниже.")
            appendLine()
            appendLine("1. Изоляция интерфейсов (пользователь и агент не пересекаются):")
            appendLine("   - Ты работаешь пассивно в своих фоновых буферах и отдельном WebView, пока пользователь свободно находится в Чате, своём «Обычном» браузере или своей вкладке редактора.")
            appendLine("   - Во вкладке «Браузер» есть два независимых WebView: «Обычный» (личный браузер пользователя) и «Агентный (Live)» (твой фоновый браузер). Пользователь может в любой момент нажать «Агентный (Live)», чтобы наблюдать за твоей работой изнутри.")
            appendLine("   - Запрещено без прямой просьбы перехватывать экран пользователя: `app_navigate(section)` вызывай ТОЛЬКО если пользователь сам попросил переключить вкладку.")
            appendLine()
            appendLine("2. Агентный браузер в реальном времени (отдельный фоновый `agentWebView`) и сеть:")
            appendLine("   - `browser_agent_navigate(url, wait_seconds?)` — загрузить URL и дождаться рендера.")
            appendLine("   - `browser_agent_inspect(max_chars?)` — получить URL, title, scroll, список кликабельных/вводимых элементов `[0], [1]...`, видимый текст и ошибки JS-консоли.")
            appendLine("   - `browser_agent_act(action, element_index?, selector?, text?, value?)` — выполнить `click | type | scroll | press_enter | select | back | forward | reload | wait` по индексу `[N]`, CSS-селектору или видимому тексту.")
            appendLine("   - `browser_agent_eval_js(script)` — выполнить JS на текущей странице агентного браузера.")
            appendLine("   - `browser_agent_screenshot(filename?, attach_to_chat?)` — сделать PNG-снимок текущей страницы (по умолчанию сразу прикрепляется в чат).")
            appendLine("   - `browser_agent_mode(action)` — `status | logs | home` (узнать статус/историю действий или сбросить твой фоновый браузер на стартовую).")
            appendLine("   - `web_search(query, count?)` — поиск в интернете; `web_scrape(url, mode?, css_selector?, render_js?, headers_json?, max_chars?)` — парсинг (и загрузка в агентный браузер); `browser_open(url)` — открыть URL в фоновом агентном браузере.")
            appendLine()
            appendLine("3. Файловые среды (`internal` | `saf` | `termux_ssh` + `server`), диффы и откат:")
            appendLine("   - Поиск и чтение: `check_environment_status()`, `list_files(path?, target_env?, server?)`, `search_files(query, target_env?, server?)`, `search_codebase(query, target_env?, server?)`, `file_read(path, offset_line?, limit_lines?, target_env?, server?)`, `file_info(path, target_env?, server?)`.")
            appendLine("   - Создание, правка и перенос: `file_write(path, content, target_env?, server?)`, `file_edit(path, old_string, new_string, target_env?, server?)` (строит Diff и сохраняет снэпшот), `file_move(source_path, target_path, target_env?, server?)`, `file_delete(path, target_env?, server?)`, `create_folder(path, target_env?, server?)`.")
            appendLine("   - Архивы, обмен и чат: `archive_create(source_path, archive_path?)`, `archive_extract(archive_path, target_dir?)` (в `internal`), `transfer_file(source_env, source_path, target_env, target_path, source_server?, target_server?)`, `send_file_to_chat(path, target_env?, server?, caption?)`.")
            appendLine("   - Снэпшоты отката: `snapshot_list(limit?)`, `snapshot_restore(snapshot_id)` — откат к сохранённой версии файла.")
            appendLine()
            appendLine("4. Код-редактор, Локальный HTTP-сервер и Терминал (пассивно в фоне):")
            appendLine("   - Редактор: `editor_open_file(path, target_env?, server?)` открывает файл в фоновый буфер вкладок (не мешая активной вкладке пользователя); `editor_list_tabs(include_content?)` показывает открытые вкладки и несохранённый текст.")
            appendLine("   - Локальный сервер: `preview_server_control(action=start|stop|status, open_in_browser?)` запускает HTTP-предпросмотр папки `'internal'` (`http://127.0.0.1:<port>/`) и при `open_in_browser=true` открывает его в фоновом агентном браузере.")
            appendLine("   - Вкладки терминала: `terminal_list_tabs(tail_chars?)`, `terminal_open_tab(mode=local|ssh, server?, title?)`, `terminal_send(command?, signal=ctrl_c|tab|esc|clear, tab_id?, wait_ms?)`.")
            appendLine("   - Прямые shell-команды: `run_command(command, workdir?, target_env?, server?, timeout_seconds?)`; для долгих процессов: `run_command_async(command, workdir?, server?)` -> `check_command_status(pid, log_file, lines?, server?)` / `kill_command(pid, server?)`.")
            appendLine()
            appendLine("5. Чаты, Контекст, Навигация, Голос и MCP:")
            appendLine("   - Диалоги и память: `chat_list_sessions(limit?, include_archived?, folder_id?)`, `chat_search_history(query, limit?)`, `chat_read_session(session_id, limit_messages?)`, `chat_manage(action=create_session|rename_session|create_folder|assign_folder|pin|unpin, session_id?, title?, folder_id?)`, `chat_export_markdown(session_id?, filename?, attach_to_chat?)`.")
            appendLine("   - Сжатие и голос: `context_compact()` (суммаризация длинного диалога), `voice_speak(action=speak|stop|status, text?)` (озвучивание через TTS), `app_navigate(section=chat|files|preview|settings)` (только по просьбе пользователя).")
            append("- Внешние MCP: `mcp_list_servers()`, `mcp_connect(name, url, transport?, headers_json?)`, `mcp_call_tool(server, tool_name, arguments_json?)`, `mcp_read_resource(server, uri)` + прямые инструменты подключённых MCP-серверов. Настройку профилей (API-ключи, SSH-профили, конфигурацию MCP) ты не меняешь — это зона пользователя.")
        }
    }

    private suspend fun buildSystemPrompt(session: ChatSessionEntity, agentSettings: AgentSettings): String {
        val provPrompt = settingsRepo.getProviderConfig(session.providerId).systemPrompt
        val defaultEnv = WorkspaceEnvType.fromId(session.workspaceEnv)
        return buildString {
            appendLine(buildEcosystemAndOuterContourGuide())
            appendLine()
            appendLine(toolRegistry.describeConnectedEnvironments(session.workspacePath, defaultEnv))
            appendLine()
            appendLine(buildAppLeversManual())
            appendLine()
            appendLine("=== КАК РАБОТАТЬ В ДИАЛОГЕ ===")
            appendLine("1. Карта окружения уже дана выше — не вызывай check_environment_status, list_files или run_command «чтобы осмотреться» при обычном разговоре.")
            appendLine("2. На приветствие, вопрос или общую тему отвечай сразу обычным текстом на языке пользователя.")
            appendLine("3. Когда задача касается файлов, кода, команд, интернета или MCP — выбирай точный target_env ('internal' / 'saf' / 'termux_ssh' + server) и опирайся только на реальные ответы инструментов.")
            appendLine("4. Не выдумывай пути вроде /workspace или /home/user, если не работаешь в соответствующем SSH-профиле.")
            if (agentSettings.globalSystemPrompt.isNotBlank()) {
                appendLine()
                appendLine("=== Глобальные инструкции пользователя ===")
                appendLine(agentSettings.globalSystemPrompt.trim())
            }
            if (session.customSystemPrompt.isNotBlank()) {
                appendLine()
                appendLine("=== Инструкции чата ===")
                appendLine(session.customSystemPrompt.trim())
            }
            if (provPrompt.isNotBlank()) {
                appendLine()
                appendLine(provPrompt.trim())
            }
        }.trim()
    }

    private fun splitLiveEmbeddedThink(raw: String): Pair<String?, String> {
        if (!raw.contains("<think>")) return null to raw
        val thoughts = mutableListOf<String>()
        val visible = StringBuilder()
        var idx = 0
        while (idx < raw.length) {
            val openIdx = raw.indexOf("<think>", idx)
            if (openIdx < 0) {
                visible.append(raw.substring(idx))
                break
            }
            if (openIdx > idx) visible.append(raw.substring(idx, openIdx))
            val contentStart = openIdx + "<think>".length
            val closeIdx = raw.indexOf("</think>", contentStart)
            if (closeIdx < 0) {
                val tailThink = raw.substring(contentStart).trim()
                if (tailThink.isNotEmpty()) thoughts += tailThink
                break
            }
            val block = raw.substring(contentStart, closeIdx).trim()
            if (block.isNotEmpty()) thoughts += block
            idx = closeIdx + "</think>".length
        }
        val mergedThoughts = thoughts.joinToString("\n\n").ifBlank { null }
        val cleanedVisible = visible.toString().trimStart()
        return mergedThoughts to cleanedVisible
    }
}
