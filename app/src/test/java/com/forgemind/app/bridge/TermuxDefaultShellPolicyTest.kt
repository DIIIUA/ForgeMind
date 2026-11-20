package com.forgemind.app.bridge

import com.forgemind.app.data.model.WorkspaceEnvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TermuxDefaultShellPolicyTest {

    private val privateRoots = listOf(
        "/data/user/0/com.forgemind.app/files",
        "/data/user/0/com.forgemind.app"
    )

    @Test
    fun routesDefaultInternalCommandToTermuxBridgeWhenBridgeReady() {
        assertTrue(
            TermuxDefaultShellPolicy.shouldRouteToTermuxBridge(
                env = WorkspaceEnvType.INTERNAL,
                serverHint = null,
                workDir = null,
                command = "fish --version && which python",
                bridgeReady = true,
                privateRoots = privateRoots
            )
        )
    }

    @Test
    fun routesDeviceStorageCommandToTermuxBridgeAndPreservesSdcardWorkDir() {
        assertTrue(
            TermuxDefaultShellPolicy.shouldRouteToTermuxBridge(
                env = WorkspaceEnvType.DEVICE_STORAGE,
                serverHint = null,
                workDir = null,
                command = "ls -la",
                bridgeReady = true,
                privateRoots = privateRoots
            )
        )
        assertEquals(
            "/storage/emulated/0",
            TermuxDefaultShellPolicy.resolveBridgeWorkDir(
                env = WorkspaceEnvType.DEVICE_STORAGE,
                workDir = null,
                deviceStorageRoot = "/storage/emulated/0"
            )
        )
    }

    @Test
    fun keepsAppPrivateWorkDirOnEmbeddedShell() {
        assertFalse(
            TermuxDefaultShellPolicy.shouldRouteToTermuxBridge(
                env = WorkspaceEnvType.INTERNAL,
                serverHint = null,
                workDir = "/data/user/0/com.forgemind.app/files/ai_workspaces/default",
                command = "ls -la",
                bridgeReady = true,
                privateRoots = privateRoots
            )
        )
    }

    @Test
    fun keepsCommandReferencingPrivateRootOnEmbeddedShell() {
        assertFalse(
            TermuxDefaultShellPolicy.shouldRouteToTermuxBridge(
                env = WorkspaceEnvType.INTERNAL,
                serverHint = null,
                workDir = null,
                command = "cat /data/user/0/com.forgemind.app/files/notes.txt",
                bridgeReady = true,
                privateRoots = privateRoots
            )
        )
    }

    @Test
    fun fallsBackToEmbeddedShellWhenBridgeUnavailableOrExplicitSsh() {
        assertFalse(
            TermuxDefaultShellPolicy.shouldRouteToTermuxBridge(
                env = WorkspaceEnvType.INTERNAL,
                serverHint = null,
                workDir = null,
                command = "uname -a",
                bridgeReady = false,
                privateRoots = privateRoots
            )
        )
        assertFalse(
            TermuxDefaultShellPolicy.shouldRouteToTermuxBridge(
                env = WorkspaceEnvType.TERMUX_SSH,
                serverHint = "default_termux",
                workDir = null,
                command = "uname -a",
                bridgeReady = true,
                privateRoots = privateRoots
            )
        )
    }

    @Test
    fun bundledTermuxHelperSupportsUserShellFishAndHotUpgrade() {
        val candidates = listOf(
            File("src/main/assets/termux/forgemind_helper.py"),
            File("app/src/main/assets/termux/forgemind_helper.py")
        )
        val helperFile = candidates.firstOrNull { it.isFile }
            ?: error("forgemind_helper.py not found in ${candidates.map { it.absolutePath }}")
        val text = helperFile.readText(Charsets.UTF_8)
        assertTrue("Expected helper version 1.0.1", text.contains("VERSION = '1.0.1'"))
        assertTrue("Expected user_shell() honoring ~/.termux/shell", text.contains("HOME / '.termux/shell'"))
        assertTrue("Expected fish support with HOME return", text.contains("if base == 'fish':"))
        assertTrue("Expected ~/.local/bin in PATH", text.contains("HOME / '.local/bin'"))
        assertTrue("Expected authorized shutdown handler", text.contains("elif method=='shutdown':"))
    }
}
