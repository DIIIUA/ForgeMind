package com.forgemind.app.setup

enum class SetupStep { WELCOME, ENVIRONMENT, PERMISSIONS, MODELS, MCP, VOICE, AGENT, APPEARANCE, REVIEW }
enum class SetupMode { MANUAL, AUTOMATIC }
enum class SetupDisposition { ACTIVE, DEFERRED, COMPLETED }

/** Progress is local to this installation; it never changes connection settings or grants. */
data class SetupProgress(
    val step: SetupStep = SetupStep.WELCOME,
    val disposition: SetupDisposition = SetupDisposition.ACTIVE,
    val mode: SetupMode = SetupMode.MANUAL
) {
    val opensAutomatically get() = disposition == SetupDisposition.ACTIVE
    fun visit(step: SetupStep) = copy(step = step, disposition = SetupDisposition.ACTIVE)
    fun defer() = copy(disposition = SetupDisposition.DEFERRED)
    fun complete() = copy(step = SetupStep.REVIEW, disposition = SetupDisposition.COMPLETED)
    fun reopen() = if (disposition == SetupDisposition.COMPLETED) SetupProgress(mode = mode) else visit(step)

    companion object {
        fun restore(step: String?, disposition: String?, mode: String? = null) = SetupProgress(
            SetupStep.entries.firstOrNull { it.name == step } ?: SetupStep.WELCOME,
            SetupDisposition.entries.firstOrNull { it.name == disposition } ?: SetupDisposition.ACTIVE,
            SetupMode.entries.firstOrNull { it.name == mode } ?: SetupMode.MANUAL
        )
    }
}
