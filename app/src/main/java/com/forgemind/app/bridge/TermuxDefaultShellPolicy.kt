package com.forgemind.app.bridge

import com.forgemind.app.data.model.WorkspaceEnvType

/**
 * Decides whether default shell commands (`internal` / `device_storage` without an explicit SSH server)
 * should run through the connected loopback Termux bridge instead of Android's bare `/system/bin/sh`.
 *
 * Security / UID boundary:
 * ForgeMind runs under its own Android UID, separate from `com.termux`. Termux cannot access files
 * inside ForgeMind's private app data directory (`/data/user/0/com.forgemind.app/...`). Therefore:
 * - Commands whose `workdir` or text explicitly targets a private app root stay on `EmbeddedAiShell`.
 * - All other default shell commands run directly in Termux through the bridge (no SSH required),
 *   so Termux binaries (`python`, `fish`, `git`, `pkg`, etc.) work out of the box.
 */
object TermuxDefaultShellPolicy {

    fun normalizeRoot(path: String): String = path.trim().trimEnd('/')

    fun isAppPrivatePath(path: String?, privateRoots: List<String>): Boolean {
        val candidate = path?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val normalized = candidate.trimEnd('/')
        return privateRoots
            .map(::normalizeRoot)
            .filter { it.isNotEmpty() }
            .any { root -> normalized == root || normalized.startsWith("$root/") }
    }

    fun commandTargetsPrivateRoot(command: String, privateRoots: List<String>): Boolean {
        if (command.isBlank()) return false
        return privateRoots
            .map(::normalizeRoot)
            .filter { it.isNotEmpty() }
            .any { root -> command.contains(root) }
    }

    fun shouldRouteToTermuxBridge(
        env: WorkspaceEnvType,
        serverHint: String?,
        workDir: String?,
        command: String,
        bridgeReady: Boolean,
        privateRoots: List<String>
    ): Boolean {
        if (!bridgeReady) return false
        if (!serverHint.isNullOrBlank()) return false
        if (env == WorkspaceEnvType.TERMUX_SSH) return false
        if (isAppPrivatePath(workDir, privateRoots)) return false
        if (commandTargetsPrivateRoot(command, privateRoots)) return false
        return true
    }

    fun resolveBridgeWorkDir(
        env: WorkspaceEnvType,
        workDir: String?,
        deviceStorageRoot: String,
        termuxHome: String = RemoteTermuxClient.HOME
    ): String {
        val explicit = workDir?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit
        return if (env == WorkspaceEnvType.DEVICE_STORAGE && deviceStorageRoot.isNotBlank()) {
            deviceStorageRoot
        } else {
            termuxHome
        }
    }
}
