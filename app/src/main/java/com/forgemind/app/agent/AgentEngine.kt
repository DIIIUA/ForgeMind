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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private var activeJob: Job? = null

    fun resolveApproval(approvalId: String, allow: Boolean) {
        approvalWaiters.remove(approvalId)?.complete(allow)
        _pendingApprovals.value = _pendingApprovals.value.filterNot { it.id == approvalId }
    }

    fun cancelRun() {
        activeJob?.cancel()
        approvalWaiters.values.forEach { it.complete(false) }
        approvalWaiters.clear()
        _pendingApprovals.value = emptyList()
        _isRunning.value = false
        _runningSessionId.value = null
        _statusBanner.value = null
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
        if (_isRunning.value) return
        launchAgentJob(sessionId, onReplyComplete) {
            val session = sessionDao.getById(sessionId) ?: return@launchAgentJob ""
            val trimFromOrder = if (targetMessage.role == LlmRole.USER.name) {
                targetMessage.orderIndex + 1
            } else {
                targetMessage.orderIndex
            }
            messageDao.deleteFromOrder(sessionId, trimFromOrder)
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
        if (clean.isEmpty()) return
        if (!resend) {
            scope.launch {
                messageDao.upsert(userMessage.copy(content = clean))
                sessionDao.touchUpdatedAt(sessionId)
            }
            return
        }
        if (_isRunning.value) return
        launchAgentJob(sessionId, onReplyComplete) {
            val session = sessionDao.getById(sessionId) ?: return@launchAgentJob ""
            messageDao.upsert(userMessage.copy(content = clean))
            messageDao.deleteFromOrder(sessionId, userMessage.orderIndex + 1)
            sessionDao.touchUpdatedAt(sessionId)
            runAgentLoop(session)
        }
    }

    private fun launchAgentJob(
        sessionId: String,
        onReplyComplete: ((assistantText: String) -> Unit)?,
        block: suspend () -> String
    ) {
        activeJob = scope.launch {
            _isRunning.value = true
            _runningSessionId.value = sessionId
            AgentForegroundService.start(context, "ForgeMind обрабатывает запрос...")
            try {
                val finalAssistantText = block()
                if (finalAssistantText.isNotBlank()) {
                    onReplyComplete?.invoke(finalAssistantText)
                }
            } catch (_: CancellationException) {
                // Already finalized in stream catch
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
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
                _isRunning.value = false
                _runningSessionId.value = null
                _statusBanner.value = null
                AgentForegroundService.stop(context)
            }
        }
    }

    suspend fun compactSessionNow(sessionId: String): Result<String> = runCatching {
        val session = sessionDao.getById(sessionId) ?: error("Сессия не найдена")
        val allMsgs = messageDao.getForSession(sessionId).filter { it.orderIndex >= session.compactedUpToOrder }
        if (allMsgs.size < 4) error("Диалог слишком короткий для сжатия")
        val keepTail = 4
        val toCompact = allMsgs.dropLast(keepTail)
        val cfg = settingsRepo.getProviderConfig(session.providerId)
            .copy(modelId = session.modelId.ifBlank { settingsRepo.getProviderConfig(session.providerId).modelId })
        val outcome = compactor.compactMessages(cfg, session.compactedSummary, toCompact)
        val remaining = allMsgs.takeLast(keepTail)
        val newEst = compactor.estimateSessionTokens(buildSystemPrompt(session, settingsRepo.agentSettings.value), outcome.newSummary, remaining)
        sessionDao.updateCompaction(
            id = session.id,
            summary = outcome.newSummary,
            upToOrder = outcome.compactedUpToOrder,
            tokens = newEst
        )
        "Контекст сжат: ${outcome.tokensBefore} -> ${outcome.tokensAfter} токенов (LLM=${outcome.usedLlm})."
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
    ): ChatSessionEntity {
        val activeMsgs = messageDao.getForSession(session.id).filter { it.orderIndex >= session.compactedUpToOrder }
        val estTokens = compactor.estimateSessionTokens(sysPrompt, session.compactedSummary, activeMsgs)
        sessionDao.updateContextTokens(session.id, estTokens)

        if (!agentSettings.autoCompactContext ||
            !compactor.shouldAutoCompact(estTokens, providerCfg.contextWindow, agentSettings.compactThresholdPercent, activeMsgs.size)
        ) {
            return session
        }

        _statusBanner.value = "Сжатие длинного контекста..."
        val keepTail = 6
        val splitIdx = (activeMsgs.size - keepTail).coerceAtLeast(1)
        val toCompact = activeMsgs.take(splitIdx)
        val outcome = compactor.compactMessages(providerCfg, session.compactedSummary, toCompact)
        val afterEst = compactor.estimateSessionTokens(sysPrompt, outcome.newSummary, activeMsgs.drop(splitIdx))
        val updated = session.copy(
            compactedSummary = outcome.newSummary,
            compactedUpToOrder = outcome.compactedUpToOrder,
            estimatedContextTokens = afterEst
        )
        sessionDao.updateCompaction(
            id = session.id,
            summary = outcome.newSummary,
            upToOrder = outcome.compactedUpToOrder,
            tokens = afterEst
        )
        return updated
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
            messageDao.upsert(
                assistantMsg.copy(
                    content = textBuf.toString() + if (textBuf.isNotEmpty()) "\n\n*(остановлено)*" else "",
                    thinking = thinkBuf.toString(),
                    isStreaming = false
                )
            )
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
            val indexedResults = toolCalls.mapIndexed { idx, tc ->
                scope.async {
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
            fun flushPendingToolResults() {
                if (pendingToolResults.isNotEmpty()) {
                    wire += LlmWireMessage(role = LlmRole.USER, content = pendingToolResults.toString().trim())
                    pendingToolResults.clear()
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
                    wire += LlmWireMessage(role = role, content = plainContent, attachments = atts)
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
            }
            if (plainTextToolHistory) {
                flushPendingToolResults()
            }
            return wire
        }

        fun buildEcosystemAndOuterContourGuide(): String = buildString {
            appendLine("Ты — ForgeMind, AI-агент и инженерный ассистент внутри Android-приложения ForgeMind (вкладки приложения: Чат, Файлы ИИ, Браузер, Настройки).")
            appendLine()
            appendLine("=== ВОЗМОЖНОСТИ ПРИЛОЖЕНИЯ FORGEMIND ===")
            appendLine("- Файловые среды: внутренняя песочница приложения ('internal'), выбранная пользователем папка Android SAF ('saf') и пользовательские SSH/SFTP-подключения ('termux_ssh'), настроенные во вкладке Настройки.")
            appendLine("- Выполнение команд и редактирование кода: работай через встроенные файловые, shell- и diff-инструменты строго в пределах активного окружения и разрешений пользователя.")
            appendLine("- Внутренние рычаги приложения: у тебя есть прямые инструменты для редактора кода (`editor_*`), живого Агентного браузера (`browser_agent_*`), локального HTTP-сервера (`preview_server_control`), вкладок терминала (`terminal_*`), снимков отката (`snapshot_*`), истории и экспорта чатов (`chat_*`), сжатия контекста (`context_compact`), навигации по вкладкам (`app_navigate`) и озвучивания (`voice_speak`).")
            appendLine("- Подключаемые MCP-серверы: если пользователь добавил и включил MCP-серверы в Настройках, используй `mcp_list_servers` и доступные инструменты `mcp_*` / `mcp_call_tool` (включая Code Mode или шлюзы поиска инструментов, если конкретный MCP-сервер их предоставляет).")
            appendLine("- Проверка фактов: долгие фоновые команды опрашивай до завершения, файлы дочитывай целиком и опирайся только на реальные ответы инструментов (`exit_code`, `stdout`, `stderr`, размер/содержимое).")
            append("- Если ты создал или получил изображение/скриншот (`png`/`jpg`/`webp`) в файле, вызови `send_file_to_chat`, чтобы пользователь увидел превью прямо в чате.")
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
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith("<think>")) return null to raw
        val endIdx = trimmed.indexOf("</think>")
        if (endIdx < 0) {
            return trimmed.substring("<think>".length).trimStart() to ""
        }
        val thought = trimmed.substring("<think>".length, endIdx).trim()
        val after = trimmed.substring(endIdx + "</think>".length).trimStart()
        return thought to after
    }
}
