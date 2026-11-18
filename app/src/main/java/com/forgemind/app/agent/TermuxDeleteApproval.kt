package com.forgemind.app.agent

import com.forgemind.app.data.model.WorkspaceEnvType
import kotlinx.serialization.json.*

/** A tool argument cannot grant approval. Only the engine's actual human decision can. */
object TermuxDeleteApproval {
    fun required(name: String, arguments: JsonObject, defaultEnv: WorkspaceEnvType): Boolean {
        fun text(key: String) = (arguments[key] as? JsonPrimitive)?.contentOrNull
        return when (name) {
            "file_delete" -> (text("target_env")?.let(WorkspaceEnvType::fromId) ?: defaultEnv) == WorkspaceEnvType.TERMUX_SSH
            "transfer_file" -> text("move") == "true" && WorkspaceEnvType.fromId(text("source_env")) == WorkspaceEnvType.TERMUX_SSH
            else -> false
        }
    }
    fun snapshot(env: WorkspaceEnvType, existed: Boolean, hasPreviousContent: Boolean): Boolean =
        env == WorkspaceEnvType.TERMUX_SSH && (!existed || !hasPreviousContent)
}
