package com.forgemind.app.data.model

import com.forgemind.app.data.db.ChatMessageEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class BranchSwitchInfo(
    val parentKey: String,
    val currentIndex: Int,
    val totalCount: Int,
    val prevChildId: String?,
    val nextChildId: String?
)

data class ResolvedBranchView(
    val activeMessages: List<ChatMessageEntity>,
    val switchInfoByMessageId: Map<String, BranchSwitchInfo>,
    val effectiveParentById: Map<String, String>
)

object ConversationBranching {
    const val ROOT_KEY = "__root__"

    private val branchJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeSelections(raw: String): Map<String, String> {
        if (raw.isBlank() || raw == "{}") return emptyMap()
        return runCatching {
            branchJson.decodeFromString<Map<String, String>>(raw)
        }.getOrDefault(emptyMap())
    }

    fun encodeSelections(map: Map<String, String>): String {
        if (map.isEmpty()) return "{}"
        return branchJson.encodeToString(map)
    }

    fun withSelection(raw: String, parentKey: String, childId: String): String {
        val updated = decodeSelections(raw).toMutableMap()
        updated[parentKey] = childId
        return encodeSelections(updated)
    }

    /**
     * Resolves the active linear conversation path from a full session message set
     * (supporting both legacy linear messages with parentMessageId == null and branched trees).
     */
    fun resolve(
        allMessages: List<ChatMessageEntity>,
        branchSelectionsJson: String = "{}"
    ): ResolvedBranchView {
        if (allMessages.isEmpty()) {
            return ResolvedBranchView(emptyList(), emptyMap(), emptyMap())
        }

        val sorted = allMessages.sortedWith(
            compareBy<ChatMessageEntity> { it.orderIndex }.thenBy { it.createdAt }
        )
        val selections = decodeSelections(branchSelectionsJson)

        val effectiveParentById = LinkedHashMap<String, String>(sorted.size)
        val childrenByParent = LinkedHashMap<String, MutableList<ChatMessageEntity>>()

        var lastLegacyId = ROOT_KEY
        for (msg in sorted) {
            val pKey = msg.parentMessageId?.takeIf { it.isNotBlank() } ?: lastLegacyId
            effectiveParentById[msg.id] = pKey
            childrenByParent.getOrPut(pKey) { mutableListOf() }.add(msg)
            if (msg.parentMessageId.isNullOrBlank()) {
                lastLegacyId = msg.id
            }
        }

        val activePath = ArrayList<ChatMessageEntity>(sorted.size)
        val directSwitch = LinkedHashMap<String, BranchSwitchInfo>()
        val visited = HashSet<String>(sorted.size)

        var curParent = ROOT_KEY
        while (true) {
            val siblings = childrenByParent[curParent] ?: break
            if (siblings.isEmpty()) break

            val selectedId = selections[curParent]
            val chosenIdx = if (selectedId != null) {
                val idx = siblings.indexOfFirst { it.id == selectedId }
                if (idx >= 0) idx else siblings.lastIndex
            } else {
                siblings.lastIndex
            }

            val chosen = siblings[chosenIdx]
            if (!visited.add(chosen.id)) break

            if (siblings.size > 1) {
                directSwitch[chosen.id] = BranchSwitchInfo(
                    parentKey = curParent,
                    currentIndex = chosenIdx,
                    totalCount = siblings.size,
                    prevChildId = siblings.getOrNull(chosenIdx - 1)?.id,
                    nextChildId = siblings.getOrNull(chosenIdx + 1)?.id
                )
            }

            activePath.add(chosen)
            curParent = chosen.id
        }

        // Propagate assistant-turn root switch info to the final ASSISTANT bubble of that turn
        // so `< 1/2 >` appears right on the final answer bubble even after multi-step tool calls.
        val bubbleSwitch = LinkedHashMap<String, BranchSwitchInfo>()
        var i = 0
        while (i < activePath.size) {
            val m = activePath[i]
            if (m.role == LlmRole.USER.name || m.role == LlmRole.SYSTEM.name) {
                directSwitch[m.id]?.let { bubbleSwitch[m.id] = it }
                i++
            } else {
                val turnStart = i
                var lastAssistantInTurn: ChatMessageEntity? = null
                var turnSwitch: BranchSwitchInfo? = directSwitch[m.id]
                while (i < activePath.size &&
                    activePath[i].role != LlmRole.USER.name &&
                    activePath[i].role != LlmRole.SYSTEM.name
                ) {
                    val step = activePath[i]
                    if (step.role == LlmRole.ASSISTANT.name) {
                        lastAssistantInTurn = step
                    }
                    if (i > turnStart && directSwitch[step.id] != null) {
                        turnSwitch = directSwitch[step.id]
                    }
                    i++
                }
                if (turnSwitch != null) {
                    val targetId = lastAssistantInTurn?.id ?: activePath[turnStart].id
                    bubbleSwitch[targetId] = turnSwitch
                }
            }
        }

        return ResolvedBranchView(
            activeMessages = activePath,
            switchInfoByMessageId = bubbleSwitch,
            effectiveParentById = effectiveParentById
        )
    }

    /**
     * Finds the parentKey right before the assistant turn containing [targetMessage]
     * (or right after [targetMessage] if [targetMessage] is a USER message).
     */
    fun findRetryParentKey(
        resolved: ResolvedBranchView,
        targetMessage: ChatMessageEntity
    ): Pair<String, List<ChatMessageEntity>> {
        val active = resolved.activeMessages
        val idx = active.indexOfFirst { it.id == targetMessage.id }
        if (idx < 0) {
            val tailParent = active.lastOrNull()?.id ?: ROOT_KEY
            return tailParent to active
        }
        return if (targetMessage.role == LlmRole.USER.name) {
            targetMessage.id to active.subList(0, idx + 1)
        } else {
            var turnStart = idx
            while (turnStart > 0 &&
                active[turnStart - 1].role != LlmRole.USER.name &&
                active[turnStart - 1].role != LlmRole.SYSTEM.name
            ) {
                turnStart--
            }
            val firstMsgOfTurn = active[turnStart]
            val parentKey = resolved.effectiveParentById[firstMsgOfTurn.id]
                ?: active.getOrNull(turnStart - 1)?.id
                ?: ROOT_KEY
            parentKey to active.subList(0, turnStart)
        }
    }
}
