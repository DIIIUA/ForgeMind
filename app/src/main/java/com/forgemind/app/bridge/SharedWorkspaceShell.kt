package com.forgemind.app.bridge

import java.io.File
import java.security.MessageDigest

/** Per-workspace exported variables and cwd survive the short-lived, individually cancellable panes. */
internal object SharedWorkspaceShell {
    fun script(stateRoot: File, workspace: File, requestedDir: String?, command: String,
               dynamicEnv: Map<String, String>, persistChanges: Boolean = true): String {
        val id = MessageDigest.getInstance("SHA-256").digest(workspace.canonicalPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(stateRoot, id).apply { mkdirs() }
        val exports = File(dir, "exports.sh")
        val cwd = File(dir, "cwd")
        fun q(f: File) = TermuxBridge.shellSingleQuote(f.absolutePath)
        val move = "${SharedTermuxRuntime.PREFIX}/bin/mv"
        val save = "rc=${'$'}?; trap - EXIT; export -p > ${q(File(dir, "exports.tmp"))}; " +
            "$move -f ${q(File(dir, "exports.tmp"))} ${q(exports)}; " +
            "pwd -P > ${q(File(dir, "cwd.tmp"))}; $move -f ${q(File(dir, "cwd.tmp"))} ${q(cwd)}; exit \"${'$'}rc\""
        return buildString {
            appendLine("[[ ! -f ${q(exports)} ]] || source ${q(exports)}")
            if (requestedDir != null) {
                // An explicit directory can recover a project whose previous cwd was deleted.
                appendLine("cd -- ${TermuxBridge.shellSingleQuote(requestedDir)} || exit")
            } else {
                appendLine("if [[ -f ${q(cwd)} ]]; then IFS= read -r saved_cwd < ${q(cwd)}; cd -- \"${'$'}saved_cwd\" || exit; fi")
            }
            dynamicEnv.forEach { (key, value) ->
                if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                    // Bootstrap helper binaries once; later explicit PATH exports belong to the user.
                    val condition = if (key == "PATH") "[[ -f ${q(exports)} ]] || " else ""
                    appendLine("${condition}export $key=${TermuxBridge.shellSingleQuote(value)}")
                }
            }
            if (persistChanges) appendLine("trap ${TermuxBridge.shellSingleQuote(save)} EXIT")
            appendLine(command)
        }
    }
}
