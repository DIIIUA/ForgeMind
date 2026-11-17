package com.forgemind.app.agent

/** A run-local, ordered ring. Re-entering a model always requires consent. */
data class FallbackModel(val providerId: String, val modelId: String) {
    val label: String get() = "$providerId / $modelId"

    companion object {
        fun fromFavorites(tokens: List<String>, providerIds: Set<String>): List<FallbackModel> =
            tokens.mapNotNull { token ->
                val parts = token.split("::", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val provider = parts[0].trim()
                val model = parts[1].trim()
                if (provider !in providerIds || model.isEmpty()) null else FallbackModel(provider, model)
            }.distinct()
    }
}

data class FallbackStep(
    val from: FallbackModel,
    val to: FallbackModel,
    val requiresConfirmation: Boolean,
    val mandatoryGuard: Boolean,
    val attempt: Int,
    val failedSwitches: Int
)

class ModelFallbackSequence(initial: FallbackModel, favorites: List<FallbackModel>) {
    private val models = (listOf(initial) + favorites).distinct()
    private var index = 0
    private var switched = false
    private var failedSwitches = 0
    private val attempted = mutableSetOf(initial)

    fun afterFailure(askBeforeSwitch: Boolean): FallbackStep? {
        if (models.size < 2) return null
        if (switched) failedSwitches++
        val next = models[(index + 1) % models.size]
        val guard = failedSwitches >= MAX_FAILED_SWITCHES || next in attempted
        return FallbackStep(
            models[index], next, askBeforeSwitch || guard, guard,
            if (guard) 1 else failedSwitches + 1, failedSwitches
        )
    }

    /** Call only after any required approval; denial leaves the selected model unchanged. */
    fun accept(step: FallbackStep) {
        require(step.from == models[index] && step.to == models[(index + 1) % models.size])
        if (step.mandatoryGuard) {
            failedSwitches = 0
            attempted.clear()
            attempted += step.from
        }
        index = (index + 1) % models.size
        attempted += step.to
        switched = true
    }

    fun succeeded() {
        failedSwitches = 0
        switched = false
        attempted.clear()
        attempted += models[index]
    }

    companion object {
        const val MAX_FAILED_SWITCHES = 3
        const val EVENT_TOOL_NAME = "model_fallback"
        /** A visible note about context handling (for example a failed auto-compaction). */
        const val CONTEXT_NOTICE_TOOL_NAME = "context_notice"
        /** SYSTEM rows shown in the chat but never sent to the model. */
        val UI_EVENT_TOOL_NAMES = setOf(EVENT_TOOL_NAME, CONTEXT_NOTICE_TOOL_NAME)
    }
}
