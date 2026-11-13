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

            val autoTitle = if ((session.title.isBlank() || session.title == "New Chat") && userText.isNotBlank()) {
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
                AgentForegroundService.start(context, "ForgeMind is processing your request...")
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
                            content = "Unexpected agent error: ${t.message ?: t::class.java.simpleName}",
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
            val session = sessionDao.getById(sessionId) ?: error("Session not found")
            val allMsgs = messageDao.getForSession(sessionId).filter { it.orderIndex >= session.compactedUpToOrder }
            val keepTail = 4
            val splitIdx = safeCompactionSplitIndex(allMsgs, keepTail)
            if (splitIdx <= 0) error("Conversation is too short to compact")
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
            "Context compacted: ${outcome.tokensBefore} -> ${outcome.tokensAfter} tokens (LLM=${outcome.usedLlm})."
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
                content = "⚠️ Reached maximum agent turn limit ($maxTurns). You can ask to continue or increase the limit in Settings."
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

        _statusBanner.value = "Compacting long context..."
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
            "Reflection pause after $toolCallsSinceThink tool calls (${providerCfg.modelId})..."
        } else {
            "Generating response (${providerCfg.modelId})..."
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
            val stoppedContent = if (cleanedCancelled.isNotEmpty()) "$cleanedCancelled\n\n*(stopped)*" else ""
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
                append("⚠️ *(stream interrupted: $streamErr)*")
            } else if (isBlank() && toolCalls.isEmpty() && finalThink.isNotBlank()) {
                append(
                    if (isReflectionStep && !hasDoneMarker) {
                        "*(Reflection checkpoint: see Model Reasoning above — continuing execution.)*"
                    } else {
                        "*(Model completed with reasoning only — expand Model Reasoning above.)*"
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

        _statusBanner.value = "Executing tools (${toolCalls.size})..."
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
            val loopNote = if (isLooping) "\n\n[SYSTEM WARNING: Repeated tool-call cycle detected. Do not repeat identical calls — adjust your approach or provide a final answer.]" else ""
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
                title = "Allow ${tc.name}?",
                details = tc.argumentsJson.take(600)
            )
            waiter.await()
        } else true

        if (!allowed) {
            cards[idx] = cards[idx].copy(
                status = ToolExecutionStatus.DENIED,
                resultPreview = "Denied by user"
            )
            messageDao.upsert(assistantMsg.copy(toolCardsJson = LlmHttpFactory.json.encodeToString(cards)))
            return tc to ToolRunOutput("User denied execution of ${tc.name}.", isError = true)
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
            appendLine("=== FORCED REFLECTION CHECKPOINT ===")
            appendLine("Consecutive tool calls executed: $callsInStreak (streak threshold: $threshold). Tools are disabled for this turn.")
            appendLine("Before continuing, pause and evaluate current progress:")
            appendLine("1. What has actually been verified by tool outputs so far?")
            appendLine("2. Are there any loops, redundant calls, or mistaken assumptions?")
            appendLine("3. What remains to be done and what is the most precise next step?")
            appendLine("- If the task is NOT YET COMPLETE: write a concise assessment and next-step plan — tools will re-enable on the next turn.")
            append("- If the task IS FULLY COMPLETE and no further tools are needed: write your final user response and append $TASK_DONE_MARKER at the very end.")
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
                "$systemPrompt\n\n[COMPACTED PREVIOUS DIALOGUE MEMORY]\n$compactedSummary\n[/COMPACTED MEMORY]"
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
                    val fallbackText = "Interrupted: result of tool ${tc.name} was not saved."
                    if (plainTextToolHistory) {
                        if (pendingToolResults.isNotEmpty()) pendingToolResults.append("\n\n")
                        pendingToolResults.append("[Result ${tc.name} (INTERRUPTED)]:\n$fallbackText")
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
                        val statusTag = if (m.isError) " (ERROR)" else ""
                        pendingToolResults.append("[Result ${m.toolName ?: "tool"}$statusTag]:\n${m.content.take(4_000)}")
                        continue
                    }
                    flushPendingToolResults()
                    val plainContent = if (role == LlmRole.ASSISTANT && tcs.isNotEmpty()) {
                        buildString {
                            if (m.content.isNotBlank()) appendLine(m.content)
                            tcs.forEach { tc ->
                                appendLine("[Call ${tc.name}: ${tc.argumentsJson.take(400)}]")
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
                    content = "Continue executing the task according to the planned steps."
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
            appendLine("You are ForgeMind, an AI agent and engineering assistant inside the ForgeMind Android application (app tabs: Chat, AI Files, Terminal, Browser, Settings).")
            appendLine()
            appendLine("=== FORGEMIND APPLICATION CAPABILITIES ===")
            appendLine("- File environments: internal app sandbox ('internal'), user-selected Android SAF folder ('saf'), and custom SSH/SFTP connections ('termux_ssh') configured in the Settings tab.")
            appendLine("- Embedded physical AI shell: a persistent local '/system/bin/sh' runs directly inside the app sandbox ('internal') and stays online even if SSH/Termux disconnects (standard Android POSIX/toybox utilities; full Linux packages like python3, nodejs, git, or CLI tools require connecting Termux or a remote host via 'termux_ssh'). Users can watch your live commands in 'Terminal -> Watch Agent (Live)' or type manually in 'Manual' mode.")
            appendLine("- Command execution and code editing: work through the built-in file, shell, and diff tools strictly within the active environment and user permissions.")
            appendLine("- Built-in app levers (passive background mode): you have direct tools for the background code editor buffer (`editor_*`), isolated background Agent Browser (`browser_agent_*`), local HTTP server (`preview_server_control`), terminal tabs (`terminal_*`), rollback snapshots (`snapshot_*`), chat history and export (`chat_*`), context compaction (`context_compact`), tab navigation strictly on explicit user request (`app_navigate`), and speech synthesis (`voice_speak`). User and agent do not interfere with each other — the user's screen and regular browser remain untouched.")
            appendLine("- Connected MCP servers: if the user added and enabled MCP servers in Settings, use `mcp_list_servers` and available `mcp_*` / `mcp_call_tool` tools (including Code Mode or tool discovery gateways when exposed by a specific MCP server).")
            appendLine("- Fact verification: poll long-running background commands until completion, read files completely, and rely solely on real tool outputs (`exit_code`, `stdout`, `stderr`, size/content).")
            append("- If you created or saved an image/screenshot (`png`/`jpg`/`webp`) to a file and it was not already attached by an MCP tool, call `send_file_to_chat` so the user sees an inline preview directly in chat.")
        }

        fun buildAppLeversManual(): String = buildString {
            appendLine("=== COMPLETE BUILT-IN APPLICATION LEVERS MANUAL (READY IMMEDIATELY) ===")
            appendLine("You do not need to search or read the app source code to understand its capabilities: every lever is built-in and documented below.")
            appendLine()
            appendLine("1. UI isolation (user and agent never collide):")
            appendLine("   - You work passively in your background buffers, embedded physical shell, and dedicated WebView while the user freely uses Chat, their Regular Browser, Manual Terminal, or code editor.")
            appendLine("   - In the 'Browser' tab there are two independent WebViews: 'Regular' (user's personal browser) and 'Agent (Live)' (your background browser). In the 'Terminal' tab there are two modes: 'Watch Agent (Live)' and 'Manual'.")
            appendLine("   - Never hijack the user's active screen uninvited: call `app_navigate(section)` ONLY if the user explicitly asked to switch tabs.")
            appendLine()
            appendLine("2. Live Agent Browser (dedicated background `agentWebView`) and web:")
            appendLine("   - `browser_agent_navigate(url, wait_seconds?)` — load URL and wait for render.")
            appendLine("   - `browser_agent_inspect(max_chars?)` — inspect URL, title, scroll, indexed interactive elements `[0], [1]...`, visible text, and JS console errors.")
            appendLine("   - `browser_agent_act(action, element_index?, selector?, text?, value?)` — perform `click | type | scroll | press_enter | select | back | forward | reload | wait` by index `[N]`, CSS selector, or visible text.")
            appendLine("   - `browser_agent_eval_js(script)` — run JavaScript on the current Agent Browser page.")
            appendLine("   - `browser_agent_screenshot(filename?, attach_to_chat?)` — capture a PNG screenshot of the current page (attached to chat by default).")
            appendLine("   - `browser_agent_mode(action)` — `status | logs | home` (check status/action log or reset your background browser to its start page).")
            appendLine("   - `web_search(query, count?)` — web search; `web_scrape(url, mode?, css_selector?, render_js?, headers_json?, max_chars?)` — page scraping (and loading in the Agent Browser); `browser_open(url)` — open URL in the background Agent Browser.")
            appendLine()
            appendLine("3. File environments (`internal` | `saf` | `termux_ssh` + `server`), diffs, and rollback:")
            appendLine("   - Discovery & reading: `check_environment_status()`, `list_files(path?, target_env?, server?)`, `search_files(query, target_env?, server?)`, `search_codebase(query, target_env?, server?)`, `file_read(path, offset_line?, limit_lines?, target_env?, server?)`, `file_info(path, target_env?, server?)`.")
            appendLine("   - Creation, editing & transfer: `file_write(path, content, target_env?, server?)`, `file_edit(path, old_string, new_string, target_env?, server?)` (generates Diff and saves snapshot), `file_move(source_path, target_path, target_env?, server?)`, `file_delete(path, target_env?, server?)`, `create_folder(path, target_env?, server?)`.")
            appendLine("   - Archives, cross-env transfer & chat: `archive_create(source_path, archive_path?)`, `archive_extract(archive_path, target_dir?)` (in `internal`), `transfer_file(source_env, source_path, target_env, target_path, source_server?, target_server?)`, `send_file_to_chat(path, target_env?, server?, caption?)`.")
            appendLine("   - Rollback snapshots: `snapshot_list(limit?)`, `snapshot_restore(snapshot_id)` — restore a file to a saved snapshot.")
            appendLine()
            appendLine("4. Code Editor, Local HTTP Preview Server, and Terminal (passive background):")
            appendLine("   - Editor: `editor_open_file(path, target_env?, server?)` opens a file in the background tab buffer (without disturbing the user's active tab); `editor_list_tabs(include_content?)` lists open tabs and unsaved text.")
            appendLine("   - Local preview server: `preview_server_control(action=start|stop|status, open_in_browser?)` starts an HTTP server over the `'internal'` project folder (`http://127.0.0.1:<port>/`) and opens it in the background Agent Browser when `open_in_browser=true`.")
            appendLine("   - Terminal tabs: `terminal_list_tabs(tail_chars?)`, `terminal_open_tab(mode=local|ssh, server?, title?)`, `terminal_send(command?, signal=ctrl_c|tab|esc|clear, tab_id?, wait_ms?)`.")
            appendLine("   - Direct shell commands: `run_command(command, workdir?, target_env?, server?, timeout_seconds?)` (uses SSH or embedded physical `/system/bin/sh` with automatic local fallback); for long jobs: `run_command_async(command, workdir?, target_env?, server?)` -> `check_command_status(pid, log_file, lines?, target_env?, server?)` / `kill_command(pid, target_env?, server?)`.")
            appendLine()
            appendLine("5. Chats, Context, Navigation, Voice, and MCP:")
            appendLine("   - Sessions & memory: `chat_list_sessions(include_archived?, folder_id?)`, `chat_search_history(query, limit?)`, `chat_read_session(session_id, limit_messages?)`, `chat_manage(action=create_session|rename_session|create_folder|assign_folder|pin|unpin, session_id?, title?, folder_id?)`, `chat_export_markdown(session_id?, filename?, attach_to_chat?)`.")
            appendLine("   - Compaction & voice: `context_compact()` (summarize long dialogue), `voice_speak(action=speak|stop|status, text?)` (TTS playback), `app_navigate(section=chat|files|terminal|preview|settings)` (only on user request).")
            append("- External MCP: `mcp_list_servers()`, `mcp_connect(name, url, transport?, headers_json?)`, `mcp_call_tool(server, tool_name, arguments_json?)`, `mcp_read_resource(server, uri)` + direct tools from enabled MCP servers. Never modify user profile settings (API keys, SSH profiles, MCP configs) — those belong to the user.")
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
            appendLine("=== DIALOGUE OPERATING RULES ===")
            appendLine("1. Your environment map is already provided above — do not call check_environment_status, list_files, or run_command 'just to look around' during casual conversation.")
            appendLine("2. Reply directly in the user's language for greetings, general questions, or open discussion.")
            appendLine("3. When a task involves files, code, shell commands, web, or MCP — pick the exact target_env ('internal' / 'saf' / 'termux_ssh' + server) and rely strictly on verified tool outputs.")
            appendLine("4. Never invent paths like /workspace or /home/user unless actually working on a matching SSH server.")
            if (agentSettings.globalSystemPrompt.isNotBlank()) {
                appendLine()
                appendLine("=== User Global Instructions ===")
                appendLine(agentSettings.globalSystemPrompt.trim())
            }
            if (session.customSystemPrompt.isNotBlank()) {
                appendLine()
                appendLine("=== Session Instructions ===")
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
