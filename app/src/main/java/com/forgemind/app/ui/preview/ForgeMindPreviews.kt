package com.forgemind.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.db.SshProfileEntity
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.McpToolInfo
import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.data.model.PendingApproval
import com.forgemind.app.data.model.ToolExecutionCard
import com.forgemind.app.data.model.ToolExecutionStatus
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.ui.components.ChatMessageBubble
import com.forgemind.app.ui.components.ConnectionStatusStrip
import com.forgemind.app.ui.components.ContextUsageBar
import com.forgemind.app.ui.components.ModelSelectorButton
import com.forgemind.app.ui.components.PendingApprovalBanner
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeMindTheme
import kotlinx.serialization.encodeToString

@Preview(name = "Chat Message Bubble + Tool Cards", showBackground = true, widthDp = 390)
@Composable
fun PreviewChatMessages() {
    val cards = listOf(
        ToolExecutionCard(
            callId = "tc_1",
            toolName = "run_command",
            status = ToolExecutionStatus.SUCCESS,
            argumentsJson = """{"command":"ls -la"}""",
            resultPreview = "total 24\ndrwxr-xr-x app\n-rw-r--r-- build.gradle.kts",
            durationMs = 145
        ),
        ToolExecutionCard(
            callId = "tc_2",
            toolName = "edit_file",
            status = ToolExecutionStatus.ERROR,
            argumentsJson = """{"path":"AgentEngine.kt"}""",
            resultPreview = "Target text snippet not found",
            diffText = "--- a/AgentEngine.kt\n+++ b/AgentEngine.kt\n@@ -1 +1 @@\n-old\n+new",
            durationMs = 32
        )
    )
    val userMsg = ChatMessageEntity(
        id = "m1",
        sessionId = "s1",
        orderIndex = 1,
        role = LlmRole.USER.name,
        content = "Check the agent loop settings and model selector dropdown."
    )
    val assistantMsg = ChatMessageEntity(
        id = "m2",
        sessionId = "s1",
        orderIndex = 2,
        role = LlmRole.ASSISTANT.name,
        thinking = "Checked AgentSettings: default maxTurns was 25, added forced reflection pause.",
        content = "### Done\nUpdated agent loop:\n- Reflection pause every **25** command calls\n- Safety cap `maxTurns = 100`\n\n```kotlin\nif (shouldTriggerForcedReflection(calls, threshold)) {\n    forceThinkTurn = true\n}\n```",
        toolCardsJson = LlmHttpFactory.json.encodeToString(cards),
        modelId = "nvidia-nemotron-3-5-super-vl-120b-a12b",
        promptTokens = 4200,
        completionTokens = 380
    )

    ForgeMindTheme {
        Column(
            modifier = Modifier
                .background(ForgeColors.BgDeep)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ChatMessageBubble(
                message = userMsg,
                isSpeakingThis = false,
                onSpeakClick = {},
                onCopyText = {},
                onSaveAttachment = {},
                onRestoreSnapshot = {},
                onError = {}
            )
            ChatMessageBubble(
                message = assistantMsg,
                isSpeakingThis = true,
                onSpeakClick = {},
                onCopyText = {},
                onSaveAttachment = {},
                onRestoreSnapshot = {},
                onError = {}
            )
        }
    }
}

@Preview(name = "Connection Status & Context & Approval", showBackground = true, widthDp = 390)
@Composable
fun PreviewStatusContextAndApproval() {
    val mcpServers = listOf(
        McpServerEntity(
            id = "srv1",
            name = "MCP Gateway",
            endpoint = "https://mcp.example.com/mcp",
            enabled = true,
            cachedToolsJson = LlmHttpFactory.json.encodeToString(
                listOf(
                    McpToolInfo("workspace_status", "Show workspace status"),
                    McpToolInfo("device_screen_text", "Read UI tree")
                )
            ),
            lastConnectedAt = 1700000000000L
        ),
        McpServerEntity(
            id = "srv2",
            name = "Windows MCP",
            endpoint = "https://windows-mcp.example.com/mcp",
            enabled = false
        )
    )
    val sshProfiles = listOf(
        SshProfileEntity(
            id = "ssh1",
            name = "Termux Local",
            host = "127.0.0.1",
            port = 2222,
            username = "u0_a334",
            isDefault = true
        )
    )

    ForgeMindTheme {
        Column(
            modifier = Modifier
                .background(ForgeColors.BgDeep)
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ConnectionStatusStrip(
                mcpServers = mcpServers,
                sshProfiles = sshProfiles,
                connectedSshIds = setOf("ssh1"),
                safTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWorkspace",
                isTermuxIntentReady = true,
                decodeTools = { raw -> runCatching { LlmHttpFactory.json.decodeFromString<List<McpToolInfo>>(raw) }.getOrDefault(emptyList()) },
                expanded = true,
                onToggleExpand = {},
                onRefreshAll = {},
                onToggleMcp = { _, _ -> },
                onTestMcp = {},
                onTestSsh = {},
                onOpenSettings = {}
            )
            ContextUsageBar(
                estimatedTokens = 97_500,
                contextWindow = 250_000,
                onCompactClick = {}
            )
            PendingApprovalBanner(
                approval = PendingApproval(
                    id = "ap1",
                    toolName = "run_command",
                    title = "Allow run_command?",
                    details = """{"command":"./gradlew assembleDebug"}""",
                    diffPreview = null
                ),
                onAllow = {},
                onDeny = {}
            )
        }
    }
}

@Preview(name = "Model Selector Button States", showBackground = true, widthDp = 390)
@Composable
fun PreviewModelSelectorButton() {
    val sampleModels = listOf(
        ModelInfo("nvidia-nemotron-3-5-super-vl-120b-a12b", contextLength = 250_000, pricingHint = "$0.6/M"),
        ModelInfo("anthropic/claude-sonnet-4", contextLength = 200_000),
        ModelInfo("gemini-2.5-pro", contextLength = 1_000_000)
    )
    ForgeMindTheme {
        Column(
            modifier = Modifier
                .background(ForgeColors.SurfaceDark)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModelSelectorButton(
                models = sampleModels,
                currentModelId = "nvidia-nemotron-3-5-super-vl-120b-a12b",
                isScanning = false,
                onScanIfNeeded = {},
                onSelectModel = {}
            )
            ModelSelectorButton(
                models = emptyList(),
                currentModelId = "",
                isScanning = false,
                onScanIfNeeded = {},
                onSelectModel = {}
            )
        }
    }
}
