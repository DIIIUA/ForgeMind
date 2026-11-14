package com.forgemind.app.agent

import android.content.Context
import com.forgemind.app.data.db.ChatMessageDao
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.ChatSessionDao
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.ConversationBranching
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
import com.forgemind.app.llm.LlmToolSpec
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
            val allMsgs = messageDao.getForSession(sessionId)
            val resolved = ConversationBranching.resolve(allMsgs, session.branchSelectionsJson)
            val parentKey = resolved.activeMessages.lastOrNull()?.id ?: ConversationBranching.ROOT_KEY
            val nextOrder = messageDao.getMaxOrderIndex(sessionId) + 1
            val userMsg = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                orderIndex = nextOrder,
                role = LlmRole.USER.name,
                content = userText,
                attachmentsJson = LlmHttpFactory.json.encodeToString(attachments),
                parentMessageId = parentKey
            )
            messageDao.upsert(userMsg)
            val updatedSelections = ConversationBranching.withSelection(
                session.branchSelectionsJson,
                parentKey,
                userMsg.id
            )
            if (updatedSelections != session.branchSelectionsJson) {
                sessionDao.updateBranchSelections(session.id, updatedSelections)
            }

            val isDefaultTitle = session.title.equals("Новый чат", ignoreCase = true) ||
                session.title.equals("New chat", ignoreCase = true)
            val autoTitle = if (isDefaultTitle && userText.isNotBlank()) {
                userText.replace('\n', ' ').trim().take(42)
            } else session.title
            if (autoTitle != session.title) {
                sessionDao.updateTitle(session.id, autoTitle)
            } else {
                sessionDao.touchUpdatedAt(session.id)
            }

            runAgentLoop(
                initialSession = session.copy(title = autoTitle, branchSelectionsJson = updatedSelections),
                initialParentOverride = userMsg.id
            )
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
            val resolved = ConversationBranching.resolve(allMsgs, session.branchSelectionsJson)
            val (branchParentKey, prefixMsgs) = ConversationBranching.findRetryParentKey(resolved, targetMessage)

            // If targetMessage is a completely empty failed stub with no text/thinking/cards, remove stub cleanly;
            // otherwise keep it intact so `< 1/2 >` lets the user switch between variants!
            val isEmptyStub = targetMessage.role == LlmRole.ASSISTANT.name &&
                targetMessage.content.isBlank() &&
                targetMessage.thinking.isBlank() &&
                (targetMessage.toolCardsJson.isBlank() || targetMessage.toolCardsJson == "[]")
            if (isEmptyStub) {
                messageDao.deleteById(targetMessage.id)
            }

            val anchorOrder = prefixMsgs.lastOrNull()?.orderIndex ?: -1
            if (anchorOrder < session.compactedUpToOrder && session.compactedUpToOrder > 0) {
                sessionDao.updateCompaction(sessionId, "", 0, 0)
                session = sessionDao.getById(sessionId) ?: session.copy(compactedSummary = "", compactedUpToOrder = 0)
            }
            sessionDao.touchUpdatedAt(sessionId)
            runAgentLoop(session, initialParentOverride = branchParentKey)
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
            val allMsgs = messageDao.getForSession(sessionId)
            val resolved = ConversationBranching.resolve(allMsgs, session.branchSelectionsJson)
            val parentKey = resolved.effectiveParentById[userMessage.id] ?: ConversationBranching.ROOT_KEY

            val nextOrder = messageDao.getMaxOrderIndex(sessionId) + 1
            val branchedUserMsg = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                orderIndex = nextOrder,
                role = LlmRole.USER.name,
                content = clean,
                attachmentsJson = userMessage.attachmentsJson,
                parentMessageId = parentKey
            )
            messageDao.upsert(branchedUserMsg)

            val updatedSelections = ConversationBranching.withSelection(
                session.branchSelectionsJson,
                parentKey,
                branchedUserMsg.id
            )
            sessionDao.updateBranchSelections(sessionId, updatedSelections)

            if (userMessage.orderIndex <= session.compactedUpToOrder && session.compactedUpToOrder > 0) {
                sessionDao.updateCompaction(sessionId, "", 0, 0)
                session = sessionDao.getById(sessionId) ?: session.copy(
                    compactedSummary = "",
                    compactedUpToOrder = 0,
                    branchSelectionsJson = updatedSelections
                )
            } else {
                session = session.copy(branchSelectionsJson = updatedSelections)
            }
            sessionDao.touchUpdatedAt(sessionId)
            runAgentLoop(session, initialParentOverride = branchedUserMsg.id)
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
                    val sess = sessionDao.getById(sessionId)
                    val activeLeaf = if (sess != null) {
                        ConversationBranching.resolve(
                            messageDao.getForSession(sessionId),
                            sess.branchSelectionsJson
                        ).activeMessages.lastOrNull()?.id ?: ConversationBranching.ROOT_KEY
                    } else ConversationBranching.ROOT_KEY
                    val nextOrder = messageDao.getMaxOrderIndex(sessionId) + 1
                    messageDao.upsert(
                        ChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            orderIndex = nextOrder,
                            role = LlmRole.ASSISTANT.name,
                            content = "Неожиданная ошибка агента: ${t.message ?: t::class.java.simpleName}",
                            isError = true,
                            parentMessageId = activeLeaf
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
            val activeBranch = ConversationBranching.resolve(
                messageDao.getForSession(sessionId),
                session.branchSelectionsJson
            ).activeMessages
            val allMsgs = activeBranch.filter { it.orderIndex >= session.compactedUpToOrder }
            val keepTail = 4
            val splitIdx = safeCompactionSplitIndex(allMsgs, keepTail)
            if (splitIdx <= 0) error("Диалог слишком короткий для сжатия")
            val toCompact = allMsgs.take(splitIdx)
            val cfg = settingsRepo.getProviderConfig(session.providerId)
                .copy(modelId = session.modelId.ifBlank { settingsRepo.getProviderConfig(session.providerId).modelId })
            val outcome = compactor.compactMessages(cfg, session.compactedSummary, toCompact)
            val remaining = allMsgs.drop(splitIdx)
            val newEst = compactor.estimateSessionTokens(
                buildSystemPrompt(session, settingsRepo.agentSettings.value, cfg, emptyList()),
                outcome.newSummary,
                remaining
            )
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

    private fun sliceUpToParent(
        activeMessages: List<ChatMessageEntity>,
        parentKey: String
    ): List<ChatMessageEntity> {
        if (parentKey == ConversationBranching.ROOT_KEY) return emptyList()
        val idx = activeMessages.indexOfFirst { it.id == parentKey }
        return if (idx >= 0) activeMessages.subList(0, idx + 1) else activeMessages
    }

    private suspend fun runAgentLoop(
        initialSession: ChatSessionEntity,
        initialParentOverride: String? = null
    ): String {
        var session = initialSession
        val agentSettings = settingsRepo.agentSettings.value
        val providerCfg = settingsRepo.getProviderConfig(session.providerId)
            .copy(modelId = session.modelId.ifBlank { settingsRepo.getProviderConfig(session.providerId).modelId })
        val defaultEnv = WorkspaceEnvType.fromId(session.workspaceEnv)

        var allTools = toolRegistry.buildToolSpecs(agentSettings)
        var tools = toolRegistry.selectToolsForProvider(allTools, providerCfg)
        val recentSignatures = ArrayDeque<String>(6)
        var lastVisibleText = ""
        val maxTurns = normalizeMaxTurns(agentSettings.maxTurns)
        val thinkEvery = normalizeThinkInterval(agentSettings.thinkEveryToolCalls)
        var toolCallsSinceThink = 0
        var forceThinkTurn = false
        var didNudgePlainShellOnce = false
        var transientNudgePrompt: String? = null

        val initialResolved = ConversationBranching.resolve(
            messageDao.getForSession(session.id),
            session.branchSelectionsJson
        )
        var nextParentId: String = initialParentOverride
            ?: initialResolved.activeMessages.lastOrNull()?.id
            ?: ConversationBranching.ROOT_KEY

        for (turn in 1..maxTurns) {
            session = sessionDao.getById(session.id) ?: break
            val sysPrompt = buildSystemPrompt(session, agentSettings, providerCfg, tools)
            session = maybeAutoCompact(session, agentSettings, providerCfg, sysPrompt, nextParentId)

            val branchActive = sliceUpToParent(
                ConversationBranching.resolve(
                    messageDao.getForSession(session.id),
                    session.branchSelectionsJson
                ).activeMessages,
                nextParentId
            )
            val currentMsgs = branchActive.filter { it.orderIndex >= session.compactedUpToOrder }
            val isReflectionStep = forceThinkTurn
            val baseWireMessages = if (isReflectionStep) {
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
            val activeNudge = transientNudgePrompt
            transientNudgePrompt = null
            val wireMessages = if (activeNudge != null) {
                baseWireMessages + LlmWireMessage(role = LlmRole.USER, content = activeNudge)
            } else {
                baseWireMessages
            }
            val toolsForTurn = if (isReflectionStep) emptyList() else tools
            val prevParentBeforeTurn = nextParentId

            val outcome = streamAssistantTurn(
                session = session,
                providerCfg = providerCfg,
                wireMessages = wireMessages,
                toolsForTurn = toolsForTurn,
                isReflectionStep = isReflectionStep,
                toolCallsSinceThink = toolCallsSinceThink,
                parentMessageId = nextParentId
            )
            nextParentId = outcome.assistantMsg.id
            if (outcome.hardStreamFailure) return ""

            val (noToolCleaned, hasNoToolNeededMarker) = stripNoToolNeededMarker(outcome.cleanedText)
            var effectiveCleanedText = noToolCleaned
            var effectiveFinalContent = if (hasNoToolNeededMarker) {
                outcome.finalContent.replace(Regex("\\[\\[NO_TOOL_NEEDED\\]\\]", RegexOption.IGNORE_CASE), "").trim()
            } else {
                outcome.finalContent
            }
            var effectiveToolCalls = outcome.toolCalls

            if (effectiveToolCalls.isEmpty() && mayContainInlineToolMarkup(effectiveCleanedText)) {
                val knownNames = allTools.mapTo(HashSet(allTools.size)) { it.name }
                val fallback = extractInlineFallbackToolCalls(effectiveCleanedText, knownNames)
                if (fallback.toolCalls.isNotEmpty() || fallback.cleanedText != effectiveCleanedText) {
                    effectiveCleanedText = fallback.cleanedText
                    effectiveToolCalls = fallback.toolCalls
                    effectiveFinalContent = buildString {
                        append(effectiveCleanedText)
                        if (outcome.streamErr != null && effectiveToolCalls.isEmpty()) {
                            if (isNotEmpty()) append("\n\n")
                            append("⚠️ *(стрим прерван: ${outcome.streamErr})*")
                        } else if (isBlank() && effectiveToolCalls.isEmpty() && outcome.finalThink.isNotBlank()) {
                            append(
                                if (isReflectionStep && !outcome.hasDoneMarker) {
                                    "*(Пауза на осмысление: см. «Размышления модели» выше — продолжаю выполнение.)*"
                                } else {
                                    "*(Модель завершила ответ только блоком размышлений — откройте «Размышления модели» выше.)*"
                                }
                            )
                        }
                    }
                }
            }

            if (effectiveCleanedText.isNotBlank() || !isReflectionStep) {
                lastVisibleText = effectiveCleanedText
            }

            if (isReflectionStep && (effectiveToolCalls.isEmpty() || outcome.hasDoneMarker)) {
                messageDao.upsert(
                    outcome.assistantMsg.copy(
                        content = effectiveFinalContent,
                        thinking = outcome.finalThink,
                        isStreaming = false,
                        isError = outcome.streamErr != null && effectiveCleanedText.isBlank()
                    )
                )
                sessionDao.touchUpdatedAt(session.id)
                toolCallsSinceThink = 0
                forceThinkTurn = false
                if (outcome.hasDoneMarker || (outcome.streamErr != null && effectiveCleanedText.isBlank())) {
                    return lastVisibleText
                }
                continue
            }

            if (isReflectionStep) {
                // Model emitted inline DSML / bracket tool calls during reflection pause:
                // reset reflection counter and execute the parsed tools cleanly without leaking raw markup.
                toolCallsSinceThink = 0
                forceThinkTurn = false
            }

            if (effectiveToolCalls.isEmpty()) {
                val lastUserMsgText = currentMsgs.lastOrNull { it.role == LlmRole.USER.name }?.content.orEmpty()
                if (!didNudgePlainShellOnce &&
                    !hasNoToolNeededMarker &&
                    outcome.streamErr == null &&
                    toolsForTurn.isNotEmpty() &&
                    shouldNudgeUnexecutedCommandOutput(
                        userPrompt = lastUserMsgText,
                        assistantText = effectiveCleanedText,
                        isCompactOrUncensored = AgentToolRegistry.shouldUseCompactModelMode(providerCfg)
                    )
                ) {
                    didNudgePlainShellOnce = true
                    _statusBanner.value = "Перевод текстовой команды в вызов инструмента..."
                    val extractedCmd = extractFirstShellCodeBlock(effectiveCleanedText)
                    messageDao.deleteById(outcome.assistantMsg.id)
                    nextParentId = prevParentBeforeTurn
                    transientNudgePrompt = buildUnexecutedCommandNudgePrompt(
                        assistantDraft = effectiveCleanedText.take(1200),
                        extractedShellCommand = extractedCmd,
                        defaultEnvId = defaultEnv.id
                    )
                    continue
                }

                messageDao.upsert(
                    outcome.assistantMsg.copy(
                        content = effectiveFinalContent,
                        thinking = outcome.finalThink,
                        isStreaming = false,
                        isError = outcome.streamErr != null && effectiveCleanedText.isBlank()
                    )
                )
                sessionDao.touchUpdatedAt(session.id)
                return lastVisibleText
            }

            val turnSig = effectiveToolCalls.joinToString("|") { "${it.name}:${it.argumentsJson.hashCode()}" }
            recentSignatures.addLast(turnSig)
            if (recentSignatures.size > 4) recentSignatures.removeFirst()
            val isLooping = recentSignatures.size == 4 &&
                recentSignatures.elementAt(0) == recentSignatures.elementAt(2) &&
                recentSignatures.elementAt(1) == recentSignatures.elementAt(3)

            val (needSchemaRefresh, lastToolMsgId) = executeToolBatch(
                session = session,
                initialAssistantMsg = outcome.assistantMsg,
                cleanedText = effectiveCleanedText,
                finalThink = outcome.finalThink,
                toolCalls = effectiveToolCalls,
                defaultEnv = defaultEnv,
                agentSettings = agentSettings,
                isLooping = isLooping
            )
            nextParentId = lastToolMsgId
            if (needSchemaRefresh) {
                allTools = toolRegistry.buildToolSpecs(agentSettings)
                tools = toolRegistry.selectToolsForProvider(allTools, providerCfg)
            }
            toolCallsSinceThink += effectiveToolCalls.size
            if (shouldTriggerForcedReflection(toolCallsSinceThink, thinkEvery)) {
                forceThinkTurn = true
            }
        }

        val maxOrder = messageDao.getMaxOrderIndex(session.id) + 1
        val limitMsg = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            orderIndex = maxOrder,
            role = LlmRole.ASSISTANT.name,
            content = "⚠️ Достигнут максимальный лимит ходов агента ($maxTurns). Можете попросить продолжить или увеличить лимит в Настройках.",
            parentMessageId = nextParentId
        )
        messageDao.upsert(limitMsg)
        val freshSess = sessionDao.getById(session.id)
        if (freshSess != null) {
            val sel = ConversationBranching.withSelection(freshSess.branchSelectionsJson, nextParentId, limitMsg.id)
            if (sel != freshSess.branchSelectionsJson) sessionDao.updateBranchSelections(session.id, sel)
        }
        return lastVisibleText
    }

    private suspend fun maybeAutoCompact(
        session: ChatSessionEntity,
        agentSettings: AgentSettings,
        providerCfg: ProviderConfig,
        sysPrompt: String,
        upToParentId: String
    ): ChatSessionEntity = compactionMutex.withLock {
        val freshSession = sessionDao.getById(session.id) ?: session
        val branchMsgs = sliceUpToParent(
            ConversationBranching.resolve(
                messageDao.getForSession(freshSession.id),
                freshSession.branchSelectionsJson
            ).activeMessages,
            upToParentId
        )
        val activeMsgs = branchMsgs.filter { it.orderIndex >= freshSession.compactedUpToOrder }
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
        toolCallsSinceThink: Int,
        parentMessageId: String
    ): TurnStreamOutcome {
        val orderIdx = messageDao.getMaxOrderIndex(session.id) + 1
        var assistantMsg = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            orderIndex = orderIdx,
            role = LlmRole.ASSISTANT.name,
            providerId = providerCfg.providerId,
            modelId = providerCfg.modelId,
            isStreaming = true,
            parentMessageId = parentMessageId
        )
        messageDao.upsert(assistantMsg)
        val freshSession = sessionDao.getById(session.id) ?: session
        val updatedSel = ConversationBranching.withSelection(
            freshSession.branchSelectionsJson,
            parentMessageId,
            assistantMsg.id
        )
        if (updatedSel != freshSession.branchSelectionsJson) {
            sessionDao.updateBranchSelections(session.id, updatedSel)
        }

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
                            val (liveThink, rawLiveText) = splitLiveEmbeddedThink(textBuf.toString())
                            val liveText = stripLiveDsmlPreview(rawLiveText)
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
            val (rawCancelled, _) = stripTaskDoneMarker(rawCleanedText)
            val cleanedCancelled = stripLiveDsmlPreview(rawCancelled)
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
    ): Pair<Boolean, String> {
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
        var chainParentId = assistantMsg.id
        var curSelections = (sessionDao.getById(session.id) ?: session).branchSelectionsJson
        for ((tc, out) in results) {
            val loopNote = if (isLooping) "\n\n[СИСТЕМНОЕ ПРЕДУПРЕЖДЕНИЕ: Обнаружен повтор вызовов инструментов. Не повторяй те же вызовы — измени подход или дай финальный ответ пользователю.]" else ""
            val toolMsgId = UUID.randomUUID().toString()
            messageDao.upsert(
                ChatMessageEntity(
                    id = toolMsgId,
                    sessionId = session.id,
                    orderIndex = toolOrder++,
                    role = LlmRole.TOOL.name,
                    content = out.text.take(14_000) + loopNote,
                    toolCallId = tc.id,
                    toolName = tc.name,
                    isError = out.isError,
                    parentMessageId = chainParentId
                )
            )
            curSelections = ConversationBranching.withSelection(curSelections, chainParentId, toolMsgId)
            chainParentId = toolMsgId
        }
        sessionDao.updateBranchSelections(session.id, curSelections)
        return needSchemaRefresh to chainParentId
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
        const val NO_TOOL_NEEDED_MARKER = "[[NO_TOOL_NEEDED]]"
        const val MAX_TURNS_UPPER_BOUND = 500
        const val THINK_INTERVAL_UPPER_BOUND = 200

        fun normalizeMaxTurns(value: Int): Int = value.coerceIn(1, MAX_TURNS_UPPER_BOUND)

        fun normalizeThinkInterval(value: Int): Int = value.coerceIn(0, THINK_INTERVAL_UPPER_BOUND)

        fun shouldTriggerForcedReflection(toolCallsSinceThink: Int, thinkEveryToolCalls: Int): Boolean {
            val threshold = normalizeThinkInterval(thinkEveryToolCalls)
            return threshold > 0 && toolCallsSinceThink >= threshold
        }

        data class FallbackToolParseResult(
            val cleanedText: String,
            val toolCalls: List<ToolCallInfo>
        )

        private val DSML_CALLS_BLOCK_REGEX = Regex(
            """<[｜|]\s*DSML\s*[｜|]\s*(?:function_)?calls\s*>([\s\S]*?)</[｜|]\s*DSML\s*[｜|]\s*(?:function_)?calls\s*>"""
        )
        private val DSML_INVOKE_REGEX = Regex(
            """<[｜|]\s*DSML\s*[｜|]\s*invoke\s+name="([^"]+)"\s*>([\s\S]*?)</[｜|]\s*DSML\s*[｜|]\s*invoke\s*>"""
        )
        private val DSML_PARAM_REGEX = Regex(
            """<[｜|]\s*DSML\s*[｜|]\s*parameter\s+name="([^"]+)"(?:\s+string="(true|false)")?\s*>([\s\S]*?)</[｜|]\s*DSML\s*[｜|]\s*parameter\s*>"""
        )
        private val DSML_OPEN_PREFIX_REGEX = Regex("""<[｜|]\s*DSML\b""")

        // Native DeepSeek V3 / V3.5 / V4 / R1 tokenizer markup (`<｜tool▁calls▁begin｜>` and ASCII `<|tool_calls_begin|>`)
        private val DEEPSEEK_CALLS_OUTER_REGEX = Regex(
            """<[｜|]tool[▁_]calls[▁_]begin[｜|]>([\s\S]*?)(?:<[｜|]tool[▁_]calls[▁_]end[｜|]>|$)"""
        )
        private val DEEPSEEK_SINGLE_CALL_REGEX = Regex(
            """<[｜|]tool[▁_]call[▁_]begin[｜|]>\s*(?:function\s*<[｜|]tool[▁_]sep[｜|]>\s*)?([A-Za-z0-9_.-]+)\s*(?:<[｜|]tool[▁_]sep[｜|]>|\r?\n)\s*([\s\S]*?)(?:<[｜|]tool[▁_]call[▁_]end[｜|]>|$)"""
        )
        private val DEEPSEEK_OPEN_PREFIX_REGEX = Regex("""<[｜|]tool[▁_]calls?[▁_]begin[｜|]>""")

        // Standard Hermes / Qwen / Llama / Universal `<tool_call>...</tool_call>`
        private val XML_TOOL_CALL_REGEX = Regex(
            """<tool_?call>\s*([\s\S]*?)\s*</tool_?call>""",
            RegexOption.IGNORE_CASE
        )
        private val UNCLOSED_XML_TOOL_CALL_REGEX = Regex(
            """<tool_?call>\s*(\{[\s\S]*)$""",
            RegexOption.IGNORE_CASE
        )
        private val OPEN_TOOL_CALL_TAG_REGEX = Regex("""<tool_?call>""", RegexOption.IGNORE_CASE)

        // Qwen3-Coder / Llama-3 / Claude XML `<function=name>...</function>` and `<invoke name="...">...</invoke>`
        private val FUNCTION_EQ_BLOCK_REGEX = Regex(
            """<function\s*=\s*"?([A-Za-z0-9_.-]+)"?\s*>([\s\S]*?)</function>""",
            RegexOption.IGNORE_CASE
        )
        private val PARAM_EQ_REGEX = Regex(
            """<parameter\s*=\s*"?([A-Za-z0-9_.-]+)"?\s*>([\s\S]*?)</parameter>""",
            RegexOption.IGNORE_CASE
        )
        private val GENERIC_FUNCTION_CALLS_WRAPPER_REGEX = Regex(
            """<function_calls>\s*([\s\S]*?)\s*</function_calls>""",
            RegexOption.IGNORE_CASE
        )
        private val GENERIC_INVOKE_REGEX = Regex(
            """<invoke\s+name="([A-Za-z0-9_.-]+)"\s*>([\s\S]*?)</invoke>""",
            RegexOption.IGNORE_CASE
        )
        private val GENERIC_PARAM_REGEX = Regex(
            """<parameter\s+name="([A-Za-z0-9_.-]+)"(?:\s+string="(true|false)")?\s*>([\s\S]*?)</parameter>""",
            RegexOption.IGNORE_CASE
        )

        // Mistral `[TOOL_CALLS] [...]` and ForgeMind `[Вызов name: {...}]`
        private val MISTRAL_TOOL_CALLS_REGEX = Regex(
            """\[TOOL_CALLS\]\s*(\[[\s\S]*?\])"""
        )
        private val BRACKET_CALL_LINE_REGEX = Regex(
            """(?m)^[ \t]*\[(?:[Вв]ызов|[Cc]all)\s+([A-Za-z0-9_.-]+)\s*:\s*(\{[^\r\n]*\})\s*\][ \t]*$"""
        )

        // Fenced markdown tool blocks: ```tool_call / ```tool / ```function_call / ```json
        private val FENCED_EXPLICIT_TOOL_BLOCK_REGEX = Regex(
            """```(?:tool_?call|tool_?code|function_?call)\s*\r?\n([\s\S]*?)```""",
            RegexOption.IGNORE_CASE
        )
        private val FENCED_JSON_BLOCK_REGEX = Regex(
            """```(?:json)?\s*\r?\n(\s*[\{\[][\s\S]*?[\}\]]\s*)```""",
            RegexOption.IGNORE_CASE
        )

        // Pythonic single-call syntax from small local GGUF models, e.g. `run_command(command="ls -la")`
        private val PYTHONIC_FENCED_CALL_REGEX = Regex(
            """```(?:python|py|tool|bash|sh)?\s*\r?\n\s*([a-z][a-z0-9_]{2,60})\s*\(([\s\S]{1,1200}?)\)\s*\r?\n?```""",
            RegexOption.IGNORE_CASE
        )
        private val PYTHONIC_STANDALONE_LINE_REGEX = Regex(
            """(?m)^[ \t]*([a-z][a-z0-9_]{2,60})\(([^\r\n]{1,600})\)[ \t]*$"""
        )
        private val KEY_VALUE_ARG_REGEX = Regex(
            """([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|(true|false|-?\d+(?:\.\d+)?))"""
        )

        private val SHELL_FENCED_BLOCK_REGEX = Regex(
            """```(?:bash|sh|shell|zsh|console)\s*\r?\n([\s\S]*?)```""",
            RegexOption.IGNORE_CASE
        )
        private val EDUCATIONAL_PROMPT_REGEX = Regex(
            """(?iu)(?:^|[^\p{L}\p{N}_])(?:пример\p{L}*|покажи\s+как|объясн\p{L}*|расскажи\s+как|что\s+делает\s+команд\p{L}*|синтаксис\p{L}*|шпаргалк\p{L}*|как\s+выглядит|example\w*|explain\w*|how\s+to\s+write|what\s+does\s+the\s+command|syntax|cheatsheet)(?=$|[^\p{L}\p{N}_])"""
        )
        private val EXECUTION_INTENT_TEXT_REGEX = Regex(
            """(?iu)(?:выполн|запущ|запускаю|проверю|проверим|посмотр|узнаем|сейчас\s+(?:я\s+)?(?:сделаю|вызову|прочитаю)|let'?s\s+run|let\s+me\s+(?:run|check|execute|inspect)|running\s+command|executing)"""
        )

        fun mayContainInlineToolMarkup(text: String): Boolean {
            if (text.isBlank()) return false
            return text.contains("<tool_call", ignoreCase = true) ||
                text.contains("<toolcall", ignoreCase = true) ||
                text.contains("DSML") ||
                text.contains("tool▁call") ||
                text.contains("tool_call") ||
                text.contains("<function", ignoreCase = true) ||
                text.contains("<invoke", ignoreCase = true) ||
                text.contains("[TOOL_CALLS]") ||
                text.contains("[Вызов ") ||
                text.contains("[Call ") ||
                text.contains("```") ||
                text.contains("run_command(") ||
                text.contains("list_files(") ||
                text.contains("file_read(") ||
                text.contains("file_write(") ||
                text.contains("web_search(")
        }

        fun stripLiveDsmlPreview(text: String): String {
            if (text.isEmpty()) return text
            var cleaned = text
            if (cleaned.contains("DSML") || cleaned.contains("<｜") || cleaned.contains("<|")) {
                cleaned = DSML_CALLS_BLOCK_REGEX.replace(cleaned, "")
                cleaned = DEEPSEEK_CALLS_OUTER_REGEX.replace(cleaned, "")
                val openMatch = DSML_OPEN_PREFIX_REGEX.find(cleaned)
                    ?: DEEPSEEK_OPEN_PREFIX_REGEX.find(cleaned)
                if (openMatch != null) {
                    cleaned = cleaned.substring(0, openMatch.range.first)
                } else {
                    val trailingBarTag = cleaned.lastIndexOf("<｜").takeIf { it >= 0 }
                        ?: cleaned.lastIndexOf("<|").takeIf { it >= 0 }
                    if (trailingBarTag != null && cleaned.length - trailingBarTag <= 24) {
                        cleaned = cleaned.substring(0, trailingBarTag)
                    }
                }
            }
            if (cleaned.contains("<tool", ignoreCase = true)) {
                cleaned = XML_TOOL_CALL_REGEX.replace(cleaned, "")
                val openTc = OPEN_TOOL_CALL_TAG_REGEX.find(cleaned)
                if (openTc != null) {
                    cleaned = cleaned.substring(0, openTc.range.first)
                }
            }
            return cleaned.trimEnd()
        }

        fun repairLooseToolJson(raw: String): String {
            var s = raw.trim()
            if (s.startsWith("```")) {
                s = s.replaceFirst(Regex("""^```[a-zA-Z0-9_-]*\s*"""), "")
                    .replaceFirst(Regex("""\s*```$"""), "")
                    .trim()
            }
            // Remove trailing commas before `}` or `]`
            s = s.replace(Regex(""",\s*([\}\]])"""), "$1")
            // Escape raw unescaped newlines inside double-quoted JSON strings
            val sb = StringBuilder(s.length)
            var inQuotes = false
            var escaped = false
            for (ch in s) {
                if (escaped) {
                    sb.append(ch)
                    escaped = false
                    continue
                }
                when (ch) {
                    '\\' -> {
                        sb.append(ch)
                        if (inQuotes) escaped = true
                    }
                    '"' -> {
                        inQuotes = !inQuotes
                        sb.append(ch)
                    }
                    '\n' -> if (inQuotes) sb.append("\\n") else sb.append(ch)
                    '\r' -> if (!inQuotes) sb.append(ch)
                    '\t' -> if (inQuotes) sb.append("\\t") else sb.append(ch)
                    else -> sb.append(ch)
                }
            }
            val passOne = sb.toString().trim()
            if (runCatching { LlmHttpFactory.json.parseToJsonElement(passOne) }.isSuccess) {
                return passOne
            }
            // Fallback pass for Python-style single-quoted dicts (`{'name': 'run_command', 'arguments': {...}}`)
            if (passOne.contains('\'') || passOne.contains("True") || passOne.contains("False") || passOne.contains("None")) {
                val pyConverted = convertPythonSingleQuotedJson(passOne)
                if (runCatching { LlmHttpFactory.json.parseToJsonElement(pyConverted) }.isSuccess) {
                    return pyConverted
                }
            }
            return passOne
        }

        private fun convertPythonSingleQuotedJson(src: String): String {
            val out = StringBuilder(src.length + 16)
            val outsideBuf = StringBuilder()
            fun flushOutside() {
                if (outsideBuf.isEmpty()) return
                val normalized = outsideBuf.toString()
                    .replace(Regex("""\bTrue\b"""), "true")
                    .replace(Regex("""\bFalse\b"""), "false")
                    .replace(Regex("""\bNone\b"""), "null")
                out.append(normalized)
                outsideBuf.setLength(0)
            }

            var i = 0
            while (i < src.length) {
                val ch = src[i]
                when (ch) {
                    '"' -> {
                        flushOutside()
                        out.append('"')
                        i++
                        while (i < src.length) {
                            val c = src[i]
                            if (c == '\\' && i + 1 < src.length) {
                                out.append(c).append(src[i + 1])
                                i += 2
                                continue
                            }
                            if (c == '"') {
                                out.append('"')
                                i++
                                break
                            }
                            if (c == '\n') out.append("\\n")
                            else if (c == '\t') out.append("\\t")
                            else if (c != '\r') out.append(c)
                            i++
                        }
                    }
                    '\'' -> {
                        flushOutside()
                        out.append('"')
                        i++
                        while (i < src.length) {
                            val c = src[i]
                            if (c == '\\' && i + 1 < src.length) {
                                val next = src[i + 1]
                                when (next) {
                                    '\'' -> out.append('\'')
                                    '"' -> out.append("\\\"")
                                    else -> out.append('\\').append(next)
                                }
                                i += 2
                                continue
                            }
                            if (c == '\'') {
                                out.append('"')
                                i++
                                break
                            }
                            when (c) {
                                '"' -> out.append("\\\"")
                                '\n' -> out.append("\\n")
                                '\t' -> out.append("\\t")
                                '\r' -> Unit
                                else -> out.append(c)
                            }
                            i++
                        }
                    }
                    else -> {
                        outsideBuf.append(ch)
                        i++
                    }
                }
            }
            flushOutside()
            return out.toString().replace(Regex(""",\s*([\}\]])"""), "$1").trim()
        }

        private fun parseJsonToolCallItems(
            rawPayload: String,
            knownToolNames: Set<String>,
            requireKnownName: Boolean
        ): List<ToolCallInfo> {
            val repaired = repairLooseToolJson(rawPayload)
            val root: JsonElement = runCatching { LlmHttpFactory.json.parseToJsonElement(repaired) }.getOrNull()
                ?: return emptyList()

            val items: List<JsonObject> = when (root) {
                is JsonObject -> {
                    val nestedArr = root["tool_calls"] as? JsonArray
                    if (nestedArr != null) nestedArr.mapNotNull { it as? JsonObject } else listOf(root)
                }
                is JsonArray -> root.mapNotNull { it as? JsonObject }
                else -> emptyList()
            }

            val out = mutableListOf<ToolCallInfo>()
            for (obj in items) {
                val fnSub = obj["function"] as? JsonObject
                val rawName = (fnSub?.get("name") as? JsonPrimitive)?.contentOrNull
                    ?: (obj["name"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["tool"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["tool_name"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["function"] as? JsonPrimitive)?.contentOrNull
                    ?: continue
                val name = rawName.trim()
                if (name.isEmpty()) continue
                if (requireKnownName && knownToolNames.isNotEmpty() && name !in knownToolNames) continue
                if (!requireKnownName && knownToolNames.isNotEmpty() && name !in knownToolNames) continue

                val rawArgsEl = fnSub?.get("arguments")
                    ?: obj["arguments"]
                    ?: obj["args"]
                    ?: obj["parameters"]
                    ?: obj["input"]

                val hasExplicitWrapperKey = fnSub != null ||
                    "arguments" in obj || "args" in obj || "parameters" in obj || "input" in obj

                val argsJson = when {
                    rawArgsEl is JsonObject -> rawArgsEl.toString()
                    rawArgsEl is JsonPrimitive -> {
                        val str = rawArgsEl.contentOrNull?.trim().orEmpty()
                        if (str.isEmpty()) "{}"
                        else {
                            val rep = repairLooseToolJson(str)
                            val parsedSub = runCatching { LlmHttpFactory.json.parseToJsonElement(rep) as? JsonObject }.getOrNull()
                            parsedSub?.toString() ?: continue
                        }
                    }
                    rawArgsEl == null && !requireKnownName -> {
                        // Some models output `{"name": "run_command", "command": "ls -la"}` directly
                        val rest = buildJsonObject {
                            for ((k, v) in obj) {
                                if (k !in setOf("name", "tool", "tool_name", "function", "type", "id")) {
                                    put(k, v)
                                }
                            }
                        }
                        rest.toString()
                    }
                    rawArgsEl == null && hasExplicitWrapperKey -> "{}"
                    else -> continue
                }

                out += ToolCallInfo(
                    id = "txt_${UUID.randomUUID().toString().take(8)}",
                    name = name,
                    argumentsJson = argsJson,
                    viaTextFallback = true
                )
            }
            return out
        }

        private fun parseXmlParamsObject(body: String, paramRegex: Regex, hasStringFlag: Boolean): JsonObject = buildJsonObject {
            for (pm in paramRegex.findAll(body)) {
                val paramName = pm.groupValues[1].trim()
                if (paramName.isEmpty()) continue
                val isStringAttr = if (hasStringFlag) pm.groupValues[2] else ""
                val rawValue = if (hasStringFlag) pm.groupValues[3] else pm.groupValues[2]
                val element = when (isStringAttr) {
                    "false" -> runCatching { LlmHttpFactory.json.parseToJsonElement(rawValue.trim()) }
                        .getOrElse { JsonPrimitive(rawValue.trim()) }
                    "true" -> JsonPrimitive(rawValue)
                    else -> {
                        val trimmed = rawValue.trim()
                        if (trimmed.startsWith("{") || trimmed.startsWith("[") ||
                            trimmed == "true" || trimmed == "false" || trimmed.toIntOrNull() != null
                        ) {
                            runCatching { LlmHttpFactory.json.parseToJsonElement(trimmed) }
                                .getOrElse { JsonPrimitive(trimmed) }
                        } else {
                            JsonPrimitive(trimmed)
                        }
                    }
                }
                put(paramName, element)
            }
        }

        private fun parsePythonicToolCall(
            fnName: String,
            rawArgsInsideParens: String,
            knownToolNames: Set<String>
        ): ToolCallInfo? {
            val cleanName = fnName.trim()
            if (knownToolNames.isEmpty() || cleanName !in knownToolNames) return null
            val inside = rawArgsInsideParens.trim()
            if (inside.isEmpty()) {
                return ToolCallInfo(
                    id = "py_${UUID.randomUUID().toString().take(8)}",
                    name = cleanName,
                    argumentsJson = "{}",
                    viaTextFallback = true
                )
            }
            // If model passed a JSON dict inside parens: `run_command({"command": "ls"})`
            if (inside.startsWith("{") && inside.endsWith("}")) {
                val repaired = repairLooseToolJson(inside)
                val obj = runCatching { LlmHttpFactory.json.parseToJsonElement(repaired) as? JsonObject }.getOrNull()
                if (obj != null) {
                    return ToolCallInfo(
                        id = "py_${UUID.randomUUID().toString().take(8)}",
                        name = cleanName,
                        argumentsJson = obj.toString(),
                        viaTextFallback = true
                    )
                }
            }
            val kvMatches = KEY_VALUE_ARG_REGEX.findAll(inside).toList()
            if (kvMatches.isNotEmpty()) {
                val obj = buildJsonObject {
                    for (m in kvMatches) {
                        val key = m.groupValues[1]
                        val dq = m.GroupsSafe(2)
                        val sq = m.GroupsSafe(3)
                        val lit = m.GroupsSafe(4)
                        when {
                            dq != null -> put(key, JsonPrimitive(dq.replace("\\\"", "\"").replace("\\n", "\n")))
                            sq != null -> put(key, JsonPrimitive(sq.replace("\\'", "'").replace("\\n", "\n")))
                            lit != null -> {
                                val parsedLit = runCatching { LlmHttpFactory.json.parseToJsonElement(lit) }
                                    .getOrElse { JsonPrimitive(lit) }
                                put(key, parsedLit)
                            }
                        }
                    }
                }
                return ToolCallInfo(
                    id = "py_${UUID.randomUUID().toString().take(8)}",
                    name = cleanName,
                    argumentsJson = obj.toString(),
                    viaTextFallback = true
                )
            }
            // Single positional string argument for common core tools: e.g. `run_command("ls -la")`
            val singlePositional = Regex("""^(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)')$""").find(inside)
            if (singlePositional != null) {
                val primaryParam = when (cleanName) {
                    "run_command", "run_command_async" -> "command"
                    "list_files", "file_read", "file_info", "file_delete", "create_folder" -> "path"
                    "search_files", "search_codebase", "web_search" -> "query"
                    "web_scrape", "browser_open", "browser_agent_navigate" -> "url"
                    else -> null
                } ?: return null
                val rawStr = (singlePositional.GroupsSafe(1) ?: singlePositional.GroupsSafe(2)).orEmpty()
                    .replace("\\\"", "\"").replace("\\'", "'").replace("\\n", "\n")
                val obj = buildJsonObject { put(primaryParam, JsonPrimitive(rawStr)) }
                return ToolCallInfo(
                    id = "py_${UUID.randomUUID().toString().take(8)}",
                    name = cleanName,
                    argumentsJson = obj.toString(),
                    viaTextFallback = true
                )
            }
            return null
        }

        private fun MatchResult.GroupsSafe(index: Int): String? = groups[index]?.value

        fun extractInlineFallbackToolCalls(
            text: String,
            knownToolNames: Set<String> = emptySet()
        ): FallbackToolParseResult {
            if (!mayContainInlineToolMarkup(text)) return FallbackToolParseResult(text, emptyList())
            val calls = mutableListOf<ToolCallInfo>()
            var remaining = text

            // 1. DSML markup (`<｜DSML｜function_calls>...`)
            fun parseDsmlInvokeBlocks(scopeText: String) {
                for (inv in DSML_INVOKE_REGEX.findAll(scopeText)) {
                    val toolName = inv.groupValues[1].trim()
                    if (toolName.isEmpty()) continue
                    if (knownToolNames.isNotEmpty() && toolName !in knownToolNames) continue
                    val argsObj = parseXmlParamsObject(inv.groupValues[2], DSML_PARAM_REGEX, hasStringFlag = true)
                    calls += ToolCallInfo(
                        id = "dsml_${UUID.randomUUID().toString().take(8)}",
                        name = toolName,
                        argumentsJson = argsObj.toString(),
                        viaTextFallback = true
                    )
                }
            }

            if (remaining.contains("DSML")) {
                val blockMatches = DSML_CALLS_BLOCK_REGEX.findAll(remaining).toList()
                if (blockMatches.isNotEmpty()) {
                    blockMatches.forEach { parseDsmlInvokeBlocks(it.groupValues[1]) }
                    remaining = DSML_CALLS_BLOCK_REGEX.replace(remaining, "")
                }
                if (DSML_INVOKE_REGEX.containsMatchIn(remaining)) {
                    parseDsmlInvokeBlocks(remaining)
                    remaining = DSML_INVOKE_REGEX.replace(remaining, "")
                }
                val danglingDsml = DSML_OPEN_PREFIX_REGEX.find(remaining)
                if (danglingDsml != null) {
                    remaining = remaining.substring(0, danglingDsml.range.first)
                }
            }

            // 2. Native DeepSeek V3 / V3.5 / V4 / R1 `<｜tool▁calls▁begin｜>` / `<|tool_calls_begin|>`
            if (remaining.contains("tool▁call") || remaining.contains("tool_calls_begin") || remaining.contains("tool_call_begin")) {
                remaining = DEEPSEEK_CALLS_OUTER_REGEX.replace(remaining) { outer ->
                    var parsedAny = false
                    for (single in DEEPSEEK_SINGLE_CALL_REGEX.findAll(outer.groupValues[1])) {
                        val tName = single.groupValues[1].trim()
                        if (knownToolNames.isNotEmpty() && tName !in knownToolNames) continue
                        val repairedArgs = repairLooseToolJson(single.groupValues[2])
                        val argsObj = if (repairedArgs.isBlank()) {
                            buildJsonObject {}
                        } else {
                            runCatching { LlmHttpFactory.json.parseToJsonElement(repairedArgs) as? JsonObject }.getOrNull()
                        }
                        if (argsObj != null) {
                            calls += ToolCallInfo(
                                id = "dsv_${UUID.randomUUID().toString().take(8)}",
                                name = tName,
                                argumentsJson = argsObj.toString(),
                                viaTextFallback = true
                            )
                            parsedAny = true
                        }
                    }
                    if (parsedAny) "" else outer.value
                }
                // Also catch standalone `<｜tool▁call▁begin｜>` without outer wrapper
                remaining = DEEPSEEK_SINGLE_CALL_REGEX.replace(remaining) { single ->
                    val tName = single.groupValues[1].trim()
                    val allowed = knownToolNames.isEmpty() || tName in knownToolNames
                    val repairedArgs = repairLooseToolJson(single.groupValues[2])
                    val argsObj = if (allowed) {
                        if (repairedArgs.isBlank()) buildJsonObject {}
                        else runCatching { LlmHttpFactory.json.parseToJsonElement(repairedArgs) as? JsonObject }.getOrNull()
                    } else null
                    if (argsObj != null) {
                        calls += ToolCallInfo(
                            id = "dsv_${UUID.randomUUID().toString().take(8)}",
                            name = tName,
                            argumentsJson = argsObj.toString(),
                            viaTextFallback = true
                        )
                        ""
                    } else single.value
                }
            }

            // 3. Standard `<tool_call>...</tool_call>` (JSON or nested `<function=...>`)
            if (remaining.contains("<tool_call", ignoreCase = true) || remaining.contains("<toolcall", ignoreCase = true)) {
                remaining = XML_TOOL_CALL_REGEX.replace(remaining) { m ->
                    val inner = m.groupValues[1].trim()
                    val parsedJsonCalls = parseJsonToolCallItems(inner, knownToolNames, requireKnownName = false)
                    if (parsedJsonCalls.isNotEmpty()) {
                        calls += parsedJsonCalls
                        ""
                    } else if (FUNCTION_EQ_BLOCK_REGEX.containsMatchIn(inner)) {
                        var subParsed = false
                        for (fnMatch in FUNCTION_EQ_BLOCK_REGEX.findAll(inner)) {
                            val fnName = fnMatch.groupValues[1].trim()
                            if (knownToolNames.isNotEmpty() && fnName !in knownToolNames) continue
                            val fnBody = fnMatch.groupValues[2].trim()
                            val argsObj = if (PARAM_EQ_REGEX.containsMatchIn(fnBody)) {
                                parseXmlParamsObject(fnBody, PARAM_EQ_REGEX, hasStringFlag = false)
                            } else {
                                runCatching { LlmHttpFactory.json.parseToJsonElement(repairLooseToolJson(fnBody)) as? JsonObject }.getOrNull()
                                    ?: buildJsonObject {}
                            }
                            calls += ToolCallInfo(
                                id = "xmlfn_${UUID.randomUUID().toString().take(8)}",
                                name = fnName,
                                argumentsJson = argsObj.toString(),
                                viaTextFallback = true
                            )
                            subParsed = true
                        }
                        if (subParsed) "" else m.value
                    } else {
                        m.value
                    }
                }
                // Trailing `<tool_call>{...}` where EOS stopped right before `</tool_call>`
                val unclosed = UNCLOSED_XML_TOOL_CALL_REGEX.find(remaining)
                if (unclosed != null) {
                    val parsedTail = parseJsonToolCallItems(unclosed.groupValues[1], knownToolNames, requireKnownName = false)
                    if (parsedTail.isNotEmpty()) {
                        calls += parsedTail
                        remaining = remaining.substring(0, unclosed.range.first)
                    }
                }
            }

            // 4. `<function=name>...</function>` and `<invoke name="...">...</invoke>`
            if (remaining.contains("<function", ignoreCase = true) || remaining.contains("<invoke", ignoreCase = true)) {
                remaining = GENERIC_FUNCTION_CALLS_WRAPPER_REGEX.replace(remaining) { it.groupValues[1] }
                remaining = FUNCTION_EQ_BLOCK_REGEX.replace(remaining) { fnMatch ->
                    val fnName = fnMatch.groupValues[1].trim()
                    val allowed = knownToolNames.isEmpty() || fnName in knownToolNames
                    if (!allowed) return@replace fnMatch.value
                    val fnBody = fnMatch.groupValues[2].trim()
                    val argsObj = when {
                        PARAM_EQ_REGEX.containsMatchIn(fnBody) ->
                            parseXmlParamsObject(fnBody, PARAM_EQ_REGEX, hasStringFlag = false)
                        fnBody.isBlank() -> buildJsonObject {}
                        else -> runCatching {
                            LlmHttpFactory.json.parseToJsonElement(repairLooseToolJson(fnBody)) as? JsonObject
                        }.getOrNull()
                    }
                    if (argsObj != null) {
                        calls += ToolCallInfo(
                            id = "fn_${UUID.randomUUID().toString().take(8)}",
                            name = fnName,
                            argumentsJson = argsObj.toString(),
                            viaTextFallback = true
                        )
                        ""
                    } else fnMatch.value
                }
                remaining = GENERIC_INVOKE_REGEX.replace(remaining) { inv ->
                    val fnName = inv.groupValues[1].trim()
                    val allowed = knownToolNames.isEmpty() || fnName in knownToolNames
                    if (!allowed) return@replace inv.value
                    val argsObj = parseXmlParamsObject(inv.groupValues[2], GENERIC_PARAM_REGEX, hasStringFlag = true)
                    calls += ToolCallInfo(
                        id = "inv_${UUID.randomUUID().toString().take(8)}",
                        name = fnName,
                        argumentsJson = argsObj.toString(),
                        viaTextFallback = true
                    )
                    ""
                }
            }

            // 5. Mistral `[TOOL_CALLS] [...]` and `[Вызов name: {...}]`
            if (remaining.contains("[TOOL_CALLS]")) {
                remaining = MISTRAL_TOOL_CALLS_REGEX.replace(remaining) { m ->
                    val parsed = parseJsonToolCallItems(m.groupValues[1], knownToolNames, requireKnownName = false)
                    if (parsed.isNotEmpty()) {
                        calls += parsed
                        ""
                    } else m.value
                }
            }
            if (remaining.contains("[Вызов ") || remaining.contains("[Call ")) {
                remaining = BRACKET_CALL_LINE_REGEX.replace(remaining) { match ->
                    val toolName = match.groupValues[1].trim()
                    val rawJson = repairLooseToolJson(match.groupValues[2])
                    val allowed = knownToolNames.isEmpty() || toolName in knownToolNames
                    val parsedObj = if (allowed) {
                        runCatching { LlmHttpFactory.json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
                    } else null
                    if (parsedObj != null) {
                        calls += ToolCallInfo(
                            id = "inline_${UUID.randomUUID().toString().take(8)}",
                            name = toolName,
                            argumentsJson = parsedObj.toString(),
                            viaTextFallback = true
                        )
                        ""
                    } else {
                        match.value
                    }
                }
            }

            // 6. Explicit fenced ```tool_call blocks, and ```json blocks matching known tool schemas
            if (calls.isEmpty() && remaining.contains("```")) {
                remaining = FENCED_EXPLICIT_TOOL_BLOCK_REGEX.replace(remaining) { m ->
                    val parsed = parseJsonToolCallItems(m.groupValues[1], knownToolNames, requireKnownName = false)
                    if (parsed.isNotEmpty()) {
                        calls += parsed
                        ""
                    } else m.value
                }
                if (calls.isEmpty() && knownToolNames.isNotEmpty()) {
                    remaining = FENCED_JSON_BLOCK_REGEX.replace(remaining) { m ->
                        val parsed = parseJsonToolCallItems(m.groupValues[1], knownToolNames, requireKnownName = true)
                        if (parsed.isNotEmpty()) {
                            calls += parsed
                            ""
                        } else m.value
                    }
                }
            }

            // 7. Pythonic tool invocation from small local GGUF models (`run_command(command="ls -la")`)
            if (calls.isEmpty() && knownToolNames.isNotEmpty()) {
                if (remaining.contains("```")) {
                    remaining = PYTHONIC_FENCED_CALL_REGEX.replace(remaining) { m ->
                        val tc = parsePythonicToolCall(m.groupValues[1], m.groupValues[2], knownToolNames)
                        if (tc != null) {
                            calls += tc
                            ""
                        } else m.value
                    }
                }
                if (calls.isEmpty()) {
                    remaining = PYTHONIC_STANDALONE_LINE_REGEX.replace(remaining) { m ->
                        val tc = parsePythonicToolCall(m.groupValues[1], m.groupValues[2], knownToolNames)
                        if (tc != null) {
                            calls += tc
                            ""
                        } else m.value
                    }
                }
            }

            val normalized = remaining
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
            return FallbackToolParseResult(cleanedText = normalized, toolCalls = calls)
        }

        fun stripTaskDoneMarker(text: String): Pair<String, Boolean> {
            val idx = text.indexOf(TASK_DONE_MARKER, ignoreCase = true)
            if (idx < 0) return text to false
            val cleaned = text.replace(Regex("\\[\\[TASK_DONE\\]\\]", RegexOption.IGNORE_CASE), "").trim()
            return cleaned to true
        }

        fun stripNoToolNeededMarker(text: String): Pair<String, Boolean> {
            val idx = text.indexOf(NO_TOOL_NEEDED_MARKER, ignoreCase = true)
            if (idx < 0) return text to false
            val cleaned = text.replace(Regex("\\[\\[NO_TOOL_NEEDED\\]\\]", RegexOption.IGNORE_CASE), "").trim()
            return cleaned to true
        }

        fun extractFirstShellCodeBlock(text: String): String? {
            val m = SHELL_FENCED_BLOCK_REGEX.find(text) ?: return null
            val cmd = m.groupValues[1].trim()
            return cmd.takeIf { it.isNotEmpty() }
        }

        fun shouldNudgeUnexecutedCommandOutput(
            userPrompt: String,
            assistantText: String,
            isCompactOrUncensored: Boolean
        ): Boolean {
            if (assistantText.isBlank()) return false
            // Broken / malformed tool markup leaked into assistant text without parsing cleanly
            if (assistantText.contains("<tool_call", ignoreCase = true) ||
                assistantText.contains("<｜tool▁call") ||
                assistantText.contains("<|tool_call") ||
                assistantText.contains("[TOOL_CALLS]")
            ) {
                return true
            }
            val shellCmd = extractFirstShellCodeBlock(assistantText) ?: return false
            val nonBlankLines = shellCmd.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            if (nonBlankLines.isEmpty() || nonBlankLines.size > 14) return false
            if (EDUCATIONAL_PROMPT_REGEX.containsMatchIn(userPrompt)) return false
            return isCompactOrUncensored || EXECUTION_INTENT_TEXT_REGEX.containsMatchIn(assistantText)
        }

        fun buildUnexecutedCommandNudgePrompt(
            assistantDraft: String,
            extractedShellCommand: String?,
            defaultEnvId: String
        ): String = buildString {
            appendLine("[СИСТЕМНАЯ КОРРЕКЦИЯ ПРОТОКОЛА ИНСТРУМЕНТОВ]")
            appendLine("Ты только что вывела команду или разметку обычным текстом в чат, поэтому она НЕ была запущена:")
            appendLine(assistantDraft.take(600))
            appendLine()
            appendLine("Обычный блок ```bash``` в чате НЕ выполняет команды! Если для ответа пользователю нужно реально выполнить команду — вызови инструмент СЕЙЧАС строгим блоком `<tool_call>` и остановись до получения результата:")
            val sampleCmd = (extractedShellCommand ?: "ls -la")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " && ")
                .take(300)
            appendLine("<tool_call>")
            appendLine("{\"name\": \"run_command\", \"arguments\": {\"command\": \"$sampleCmd\", \"target_env\": \"$defaultEnvId\"}}")
            appendLine("</tool_call>")
            append("Если же запускать ничего НЕ требовалось (пользователь просил только показать справочный пример текста/кода) — повтори свой итоговый ответ пользователю и добавь в самом конце $NO_TOOL_NEEDED_MARKER.")
        }

        fun buildForcedReflectionPrompt(callsInStreak: Int, threshold: Int): String = buildString {
            appendLine("=== ПРИНУДИТЕЛЬНАЯ ПАУЗА НА ОСМЫСЛЕНИЕ ===")
            appendLine("Выполнено вызовов команд/инструментов подряд: $callsInStreak (порог серии: $threshold). Инструменты на этот шаг отключены.")
            appendLine("Перед продолжением ОБЯЗАТЕЛЬНО остановись и осмысли происходящее:")
            appendLine("1. Что уже реально сделано и подтверждено результатами инструментов?")
            appendLine("2. Нет ли зацикливания, лишних повторов или ошибочной гипотезы?")
            appendLine("3. Что именно осталось сделать и какой самый точный следующий шаг?")
            appendLine("ВАЖНО: Не пиши вызовы инструментов, DSML/XML-блоки или псевдовызовы в тексте — на этом шаге нужен только чистый текст размышления или финального ответа.")
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
                    if (plainTextToolHistory || tc.viaTextFallback) {
                        if (pendingToolResults.isNotEmpty()) pendingToolResults.append("\n\n")
                        pendingToolResults.append("[Результат инструмента ${tc.name} (ПРЕРВАНО)]:\n$fallbackText")
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

                var matchedToolCall: ToolCallInfo? = null
                if (role == LlmRole.TOOL) {
                    if (expectedToolCallQueue.isEmpty()) {
                        // Orphaned TOOL row with no active parent ASSISTANT tool_call — drop it
                        continue
                    }
                    val callId = m.toolCallId
                    matchedToolCall = if (callId != null) {
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
                                appendLine("(выполнен инструмент ${tc.name})")
                            }
                        }.trim()
                    } else m.content
                    if (role == LlmRole.USER) {
                        appendOrMergeUser(plainContent, atts)
                    } else {
                        wire += LlmWireMessage(role = role, content = plainContent, attachments = atts)
                    }
                } else if (role == LlmRole.TOOL && matchedToolCall?.viaTextFallback == true) {
                    if (pendingToolResults.isNotEmpty()) pendingToolResults.append("\n\n")
                    val statusTag = if (m.isError) " (ОШИБКА)" else ""
                    pendingToolResults.append(
                        "[Результат инструмента ${m.toolName ?: matchedToolCall.name}$statusTag]:\n${m.content.take(6_000)}"
                    )
                    continue
                } else {
                    flushPendingToolResults()
                    if (role == LlmRole.ASSISTANT && tcs.isNotEmpty() && tcs.all { it.viaTextFallback }) {
                        val inlineCalls = tcs.joinToString("\n") { tc ->
                            val safeName = LlmHttpFactory.json.encodeToString(
                                kotlinx.serialization.serializer<String>(),
                                tc.name
                            )
                            "<tool_call>\n{\"name\": $safeName, \"arguments\": ${tc.argumentsJson.ifBlank { "{}" }}}\n</tool_call>"
                        }
                        val combinedContent = listOf(m.content.trim(), inlineCalls)
                            .filter { it.isNotEmpty() }
                            .joinToString("\n\n")
                        wire += LlmWireMessage(
                            role = LlmRole.ASSISTANT,
                            content = combinedContent,
                            toolCalls = emptyList(),
                            attachments = atts
                        )
                    } else if (role == LlmRole.USER) {
                        appendOrMergeUser(m.content, atts)
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

                if (role == LlmRole.ASSISTANT && tcs.isNotEmpty()) {
                    tcs.forEach { expectedToolCallQueue.addLast(it) }
                }
            }

            if (expectedToolCallQueue.isNotEmpty()) {
                flushDanglingToolCallsAsInterrupted()
            }
            flushPendingToolResults()
            if (!plainTextToolHistory && wire.lastOrNull()?.role == LlmRole.ASSISTANT) {
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
            appendLine("You are ForgeMind, an AI agent and engineering assistant inside the ForgeMind Android application (app tabs: Chat, AI Files, Terminal, Browser, Settings).")
            appendLine()
            appendLine("=== FORGEMIND APPLICATION CAPABILITIES ===")
            appendLine("- File environments: internal app sandbox ('internal'), device shared storage ('device_storage'), user-selected Android SAF folder ('saf'), and custom SSH/SFTP connections ('termux_ssh') configured in the Settings tab.")
            appendLine("- Embedded physical AI shell: a persistent local '/system/bin/sh' runs directly inside the app sandbox ('internal') and stays online even if SSH/Termux disconnects (standard Android POSIX/toybox utilities; full Linux packages like python3, nodejs, git, or CLI tools require connecting Termux or a remote host via 'termux_ssh'). Users can watch your live commands in 'Terminal -> Watch Agent (Live)' or type manually in 'Manual' mode.")
            appendLine("- Command execution and code editing: work through the built-in file, shell, and diff tools strictly within the active environment and user permissions.")
            appendLine("- Built-in app levers (passive background mode): you have direct tools for the background code editor buffer (`editor_*`), isolated background Agent Browser (`browser_agent_*`), local HTTP server (`preview_server_control`), terminal tabs (`terminal_*`), rollback snapshots (`snapshot_*`), chat history and export (`chat_*`), context compaction (`context_compact`), tab navigation strictly on explicit user request (`app_navigate`), and speech synthesis (`voice_speak`). User and agent do not interfere with each other — the user's screen and regular browser remain untouched.")
            appendLine("- Connected MCP servers: if the user added and enabled MCP servers in Settings, use `mcp_list_servers` and available `mcp_*` / `mcp_call_tool` tools (including Code Mode or tool discovery gateways when exposed by a specific MCP server).")
            appendLine("- Fact verification: poll long-running background commands until completion, read files completely, and rely solely on real tool outputs (`exit_code`, `stdout`, `stderr`, size/content).")
            append("- If you created or saved an image/screenshot (`png`/`jpg`/`webp`) to a file and it was not already attached by an MCP tool, call `send_file_to_chat` so the user sees an inline preview directly in chat.")
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
            appendLine("2. Мультиоконный Агентный браузер высокой скорости (до 8 живых фоновых окон), общий режим ⚡ Turbo и скрапер сайтов в Markdown:")
            appendLine("   - ПАРАЛЛЕЛЬНЫЙ МУЛЬТИОКОННЫЙ РЕЖИМ (`browser_agent_tabs` + `tab_id`): у тебя есть собственный пул до 8 одновременно работающих живых фоновых WebView-окон (`[tab_1]..[tab_8]` или твои имена `['docs','repo','pricing']`), которые не усыпляются в фоне и не трогают личный браузер пользователя.")
            appendLine("     • `browser_agent_tabs(action='open_parallel', urls=['https://...','https://...'], tab_ids?, turbo?, return_inspect?, return_markdown?, max_chars_per_tab?)` — открыть сразу несколько сайтов параллельно в фоне за один ход и получить их элементы `[0..N]` или MD-вёрстку.")
            appendLine("     • `browser_agent_tabs(action='list'|'new'|'switch'|'inspect_all'|'markdown_all'|'close'|'close_others'|'close_all', tab_id?, url?)` — читать все открытые сайты разом (`inspect_all` / `markdown_all`), переключаться и управлять окнами. Все инструменты `browser_agent_*` принимают `tab_id` для прямой работы с любым фоновым окном без потери остальных.")
            appendLine("   - ЧТО ИМЕННО ДЕЛАЕТ СКОРОСТНОЙ РЕЖИМ ⚡ TURBO (общий выбор ИИ и пользователя, по умолчанию выкл):")
            appendLine("     • Механика ускорения: (1) на сетевом уровне отсекает рекламные сети, аналитику, пиксели, CMP-баннеры, тяжёлые веб-шрифты (`.woff2/.ttf/.otf`) и фоновое видео/аудио (`.mp4/.webm/.mp3`), а также не тянет картинки в фоне (при просмотре пользователем «Агентный (Live)» или вызове `browser_agent_screenshot` загрузка картинок включается автоматически); (2) обнуляет CSS-анимации и переходы (`0s`) и сжимает межшаговые паузы с `90–100мс` до `15–35мс`; (3) включает **Turbo Early DOM Ready** — на тяжёлых сайтах возвращает управление сразу после готовности живого текста в DOM (`readyState` `interactive`/`complete`), не ожидая по 3–4 секунды загрузки сторонних рекламных iframe.")
            appendLine("     • Честные границы: ⚡ Turbo сильно ускоряет тяжёлые и рекламные сайты и пакетные цепочки шагов, но НЕ отменяет физическую задержку самого сервера/сети на лёгких страницах и не ускоряет локальный JS-разбор `extract_markdown`.")
            appendLine("     • Общий переключатель и индикация: включить/выключить ⚡ Turbo можешь как ты (`browser_agent_mode(action='turbo_on'|'turbo_off')` или `turbo=true|false` в `navigate`/`tabs`/`batch`), так и сам пользователь в интерфейсе. Когда ⚡ Turbo включён, у пользователя в шапке Чата и в панели Браузера горит заметный значок **⚡ Turbo**. Включай ⚡ Turbo для быстрых обходов, поиска, чтения статей и пакетных форм; выключай (`turbo_off`), если сайту необходимы иконочные веб-шрифты, медиаплеер или визуальная капча.")
            appendLine("   - `browser_agent_batch(steps, tab_id?, turbo?, stop_on_error?, return_inspect?, return_markdown?, max_chars?)` — ГЛАВНЫЙ УСКОРИТЕЛЬ ЦЕПОЧЕК: выполняет до 30 действий подряд за один вызов без сетевых пауз между шагами (`navigate`, `switch_tab`, `fill_form`, `click`, `type`, `select`, `press_enter`, `scroll`, `wait_selector`, `wait_text`, `wait`, `eval_js`, `extract_markdown`, `screenshot`), позволяя прямо внутри одного пакета переходить между разными окнами через `tab_id` / `new_tab` на каждом шаге.")
            appendLine("   - `browser_agent_navigate(url, tab_id?, new_tab?, wait_seconds?, turbo?, return_inspect?, return_markdown?, max_chars?)` — открыть URL в нужном или новом фоновом окне и сразу получить элементы `[0..N]` или MD-вёрстку.")
            appendLine("   - `browser_agent_markdown(tab_id?, max_chars?, selector?, include_interactive?, save_to_path?, target_env?, server?)` — выгрузить живую страницу окна (включая закрытые SPA после авторизации) в структурный GFM Markdown (заголовки, таблицы, ссылки, код + элементы `[0..N]`).")
            appendLine("   - `browser_agent_inspect(tab_id?, max_chars?, format='default'|'markdown')` — прочитать URL, title, scroll, элементы `[0..N]`, видимый текст/Markdown и ошибки JS.")
            appendLine("   - `browser_agent_act(action, tab_id?, element_index?, selector?, text?, value?, auto_inspect?)` — выполнить действие; при `click`/`press_enter`/`select` автоматически переиндексирует и возвращает новые элементы `[0..N]`.")
            appendLine("   - `browser_agent_eval_js(script, tab_id?)` — выполнить JS; `browser_agent_screenshot(tab_id?, filename?, attach_to_chat?)` — полный PNG-снимок окна; `browser_agent_mode(action='status'|'logs'|'home'|'turbo_on'|'turbo_off', tab_id?)` — статус всех окон, переключение ⚡ Turbo или сброс. Список открытых окон, cookies и режим сохраняются при перезапуске APK.")
            appendLine("   - `web_scrape_site(start_url, max_pages?, max_depth?, concurrency?, same_path_prefix?, url_filter?, include_sitemap?, render_js_first_page?, save_to_path?, target_env?, server?)` — СКРАПЕР ЦЕЛОГО САЙТА В MARKDOWN: параллельно обходит сайт, подхватывает `/sitemap.xml` и `/llms.txt`, собирает карту навигации `INDEX.md` и единую GFM MD-вёрстку `FULL_SITE.md` (а при `save_to_path` сохраняет пакет `.md` файлов на диск; в `saf` автоматически принимает как относительные пути, так и полный путь внутри выбранной SAF-папки).")
            appendLine("   - `web_search(query, count?)` — поиск в интернете; `web_scrape(url, mode?, css_selector?, render_js?, headers_json?, max_chars?)` — выгрузка одной страницы в богатый GFM Markdown/текст/таблицы/ссылки; `browser_open(url)` — открыть URL в фоновом браузере.")
            appendLine()
            appendLine("3. Файловые среды (`internal` | `device_storage` | `saf` | `termux_ssh` + `server`), диффы и откат:")
            appendLine("   - Поиск и чтение: `check_environment_status()`, `list_files(path?, target_env?, server?)`, `search_files(query, target_env?, server?)`, `search_codebase(query, target_env?, server?)`, `file_read(path, offset_line?, limit_lines?, target_env?, server?)`, `file_info(path, target_env?, server?)`.")
            appendLine("   - Создание, правка и перенос: `file_write(path, content, target_env?, server?)`, `file_edit(path, old_string, new_string, target_env?, server?)` (строит Diff и сохраняет снэпшот), `file_move(source_path, target_path, target_env?, server?)`, `file_delete(path, target_env?, server?)`, `create_folder(path, target_env?, server?)`.")
            appendLine("   - Архивы, обмен и чат: `archive_create(source_path, archive_path?)`, `archive_extract(archive_path, target_dir?)` (в `internal`), `transfer_file(source_env, source_path, target_env, target_path, move?, source_server?, target_server?)`, `send_file_to_chat(path, target_env?, server?, caption?)`.")
            appendLine("   - Снэпшоты отката: `snapshot_list(limit?)`, `snapshot_restore(snapshot_id)` — откат к сохранённой версии файла.")
            appendLine()
            appendLine("4. Код-редактор, Локальный HTTP-сервер и Терминал (пассивно в фоне):")
            appendLine("   - Редактор: `editor_open_file(path, target_env?, server?)` открывает файл в фоновый буфер вкладок (не мешая активной вкладке пользователя); `editor_list_tabs(include_content?)` показывает открытые вкладки и несохранённый текст.")
            appendLine("   - Локальный сервер: `preview_server_control(action=start|stop|status, open_in_browser?)` запускает HTTP-предпросмотр папки `'internal'` (`http://127.0.0.1:<port>/`) и при `open_in_browser=true` открывает его в фоновом агентном браузере.")
            appendLine("   - Вкладки терминала: `terminal_list_tabs(tail_chars?)`, `terminal_open_tab(mode=local|ssh, server?, title?)`, `terminal_send(command?, signal=ctrl_c|tab|esc|clear, tab_id?, wait_ms?)`.")
            appendLine("   - Прямые shell-команды: `run_command(command, workdir?, target_env?, server?, timeout_seconds?)` — при `target_env='internal'` выполняется через постоянный физический встроенный терминал приложения (`/system/bin/sh`), который сохраняет `cd`/`export` и работает даже при обрыве SSH (и служит авторезервом при падении локального SSH); для долгих процессов: `run_command_async` -> `check_command_status` / `kill_command`.")
            appendLine()
            appendLine("5. Чаты, Контекст, Навигация, Голос и MCP:")
            appendLine("   - Диалоги и память: `chat_list_sessions(limit?, include_archived?, folder_id?)`, `chat_search_history(query, limit?)`, `chat_read_session(session_id, limit_messages?)`, `chat_manage(action=create_session|rename_session|create_folder|assign_folder|pin|unpin, session_id?, title?, folder_id?)`, `chat_export_markdown(session_id?, filename?, attach_to_chat?)`.")
            appendLine("   - Восприятие открытых окон и управление экраном Android в реальном времени (Window-Tag DOM): `screen_read_windows(include_self?, only_interactive?, window_filter?, max_nodes?)` читает все видимые окна `[W1..Wn]` и их элементы `[#1..#k]` напрямую за миллисекунды; `screen_act_tag(action=click|long_click|set_text|append_text|scroll_forward|scroll_backward|focus|back|home|recents|notifications, tag?, text?, submit?)` выполняет точное действие по тегу `[#N]`; `screen_act_batch(steps, stop_on_error?, return_dom?)` выполняет целую цепочку кликов, ввода текста, свайпов и ожиданий `wait_text` за один вызов без задержек; `screen_capture(format?, max_edge?, quality?, include_dom?)` делает быстрый аппаратный снимок экрана по запросу.")
            appendLine("   - Сжатие и голос: `context_compact()` (суммаризация длинного диалога), `voice_speak(action=speak|stop|status, text?)` (озвучивание через TTS), `app_navigate(section=chat|files|terminal|preview|settings)` (только по просьбе пользователя).")
            append("- Внешние MCP: `mcp_list_servers()`, `mcp_connect(name, url, transport?, headers_json?)`, `mcp_call_tool(server, tool_name, arguments_json?)`, `mcp_read_resource(server, uri)` + прямые инструменты подключённых MCP-серверов. Настройку профилей (API-ключи, SSH-профили, конфигурацию MCP) ты не меняешь — это зона пользователя.")
        }

        fun buildUniversalToolContract(defaultEnv: WorkspaceEnvType): String = buildString {
            appendLine("=== ЕДИНЫЙ ПРОТОКОЛ ВЫЗОВА ИНСТРУМЕНТОВ (ДЛЯ ВСЕХ МОДЕЛЕЙ) ===")
            appendLine("1. Когда пользователь просит выполнить команду, прочитать/создать/изменить файл, проверить систему, открыть сайт или управлять экраном — РЕАЛЬНО ВЫЗОВИ ИНСТРУМЕНТ. КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО просто писать ```bash / ```sh команды в чат вместо их выполнения или выдумывать вывод команды от себя!")
            appendLine("2. Если твой сервер поддерживает нативный API `tool_calls`, вызывай инструменты штатно. Если нативный `tool_calls` недоступен в шаблоне GGUF/сервера, выведи точный XML-блок `<tool_call>` с валидным JSON и сразу останови генерацию до получения результата:")
            appendLine("<tool_call>")
            appendLine("{\"name\": \"run_command\", \"arguments\": {\"command\": \"ls -la\", \"target_env\": \"${defaultEnv.id}\"}}")
            appendLine("</tool_call>")
            append("3. На обычный разговорный вопрос или теоретическое объяснение (где не требуется действие) отвечай сразу текстом без лишних вызовов инструментов.")
        }

        private fun buildCompactEcosystemSummary(): String = buildString {
            appendLine("You are ForgeMind, an autonomous AI engineering agent inside the ForgeMind Android app (tabs: Chat, AI Files, Terminal, Browser, Settings).")
            appendLine("- Environments: internal sandbox ('internal'), device storage ('device_storage'), SAF folder ('saf'), and SSH/Termux ('termux_ssh'). In Termux use \"\$TMPDIR\" or ~/work instead of /tmp.")
            appendLine("- Run shell commands via `run_command(command, target_env='internal'|'termux_ssh')`; for external MCP servers use `mcp_list_servers()` and `mcp_call_tool(server, tool_name, arguments_json)`.")
        }.trim()

        fun formatInlineToolSignatures(tools: List<LlmToolSpec>, maxTools: Int = 32): String {
            if (tools.isEmpty()) return ""
            return buildString {
                appendLine("=== ДОСТУПНЫЕ ИНСТРУМЕНТЫ (ИМЯ И АРГУМЕНТЫ) ===")
                tools.take(maxTools).forEach { spec ->
                    val props = (spec.parametersSchema["properties"] as? JsonObject)?.keys.orEmpty()
                    val req = (spec.parametersSchema["required"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?.toSet()
                        .orEmpty()
                    val sig = props.joinToString(", ") { p -> if (p in req) p else "$p?" }
                    val desc = spec.description.lineSequence().firstOrNull().orEmpty().take(95)
                    appendLine("- ${spec.name}($sig): $desc")
                }
            }.trim()
        }
    }

    private suspend fun buildSystemPrompt(
        session: ChatSessionEntity,
        agentSettings: AgentSettings,
        providerCfg: ProviderConfig,
        activeTools: List<LlmToolSpec>
    ): String {
        val provPrompt = providerCfg.systemPrompt.ifBlank {
            settingsRepo.getProviderConfig(session.providerId).systemPrompt
        }
        val defaultEnv = WorkspaceEnvType.fromId(session.workspaceEnv)
        val compactMode = AgentToolRegistry.shouldUseCompactModelMode(providerCfg)
        return buildString {
            if (compactMode) {
                appendLine(buildCompactEcosystemSummary())
                appendLine()
                appendLine(toolRegistry.describeConnectedEnvironments(session.workspacePath, defaultEnv))
                appendLine()
                appendLine(buildUniversalToolContract(defaultEnv))
                if (activeTools.isNotEmpty()) {
                    appendLine()
                    appendLine(formatInlineToolSignatures(activeTools))
                }
            } else {
                appendLine(buildEcosystemAndOuterContourGuide())
                appendLine()
                appendLine(toolRegistry.describeConnectedEnvironments(session.workspacePath, defaultEnv))
                appendLine()
                appendLine(buildAppLeversManual())
                appendLine()
                appendLine(buildUniversalToolContract(defaultEnv))
                appendLine()
                appendLine("=== КАК РАБОТАТЬ В ДИАЛОГЕ ===")
                appendLine("1. Карта окружения уже дана выше — не вызывай check_environment_status, list_files или run_command «чтобы осмотреться» при обычном разговоре.")
                appendLine("2. На приветствие, вопрос или общую тему отвечай сразу обычным текстом на языке пользователя.")
                appendLine("3. Когда задача касается файлов, кода, команд, интернета или MCP — выбирай точный target_env ('internal' / 'device_storage' / 'saf' / 'termux_ssh' + server) и опирайся только на реальные ответы инструментов.")
                appendLine("4. Не выдумывай пути вроде /workspace или /home/user, если не работаешь в соответствующем SSH-профиле.")
            }
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
