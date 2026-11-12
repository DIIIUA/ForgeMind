package com.forgemind.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.db.SshProfileEntity
import com.forgemind.app.data.model.McpToolInfo
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont

@Composable
fun ConnectionStatusStrip(
    mcpServers: List<McpServerEntity>,
    sshProfiles: List<SshProfileEntity>,
    connectedSshIds: Set<String>,
    safTreeUri: String?,
    isTermuxIntentReady: Boolean,
    decodeTools: (String) -> List<McpToolInfo>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRefreshAll: () -> Unit,
    onToggleMcp: (McpServerEntity, Boolean) -> Unit,
    onTestMcp: (String) -> Unit,
    onTestSsh: (SshProfileEntity) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val insightsById = remember(mcpServers) {
        mcpServers.associate { srv ->
            srv.id to McpClientManager.analyzeCatalog(decodeTools(srv.cachedToolsJson))
        }
    }
    val enabledMcp = remember(mcpServers) { mcpServers.filter { it.enabled } }
    val errorMcp = remember(enabledMcp) { enabledMcp.filter { !it.lastError.isNullOrBlank() } }
    val connectedMcp = remember(enabledMcp) {
        enabledMcp.filter { it.lastError.isNullOrBlank() && it.lastConnectedAt != null }
    }
    val totalDirectTools = remember(enabledMcp, insightsById) {
        enabledMcp.sumOf { srv -> insightsById[srv.id]?.directToolCount ?: 0 }
    }
    val hasGateway = remember(enabledMcp, insightsById) {
        enabledMcp.any { srv -> insightsById[srv.id]?.isGatewayOrCodeMode == true }
    }

    val mcpDotColor = when {
        mcpServers.isEmpty() -> ForgeColors.TextSecondary
        errorMcp.isNotEmpty() -> ForgeColors.DangerRed
        enabledMcp.isEmpty() -> ForgeColors.TextSecondary
        connectedMcp.size == enabledMcp.size -> ForgeColors.AccentEmerald
        else -> ForgeColors.AccentAmber
    }
    val mcpLabel = when {
        mcpServers.isEmpty() -> "MCP: нет"
        enabledMcp.isEmpty() -> "MCP: выкл (${mcpServers.size})"
        errorMcp.isNotEmpty() -> "MCP: ${connectedMcp.size}/${enabledMcp.size} • ош."
        hasGateway -> "MCP: ${connectedMcp.size}/${enabledMcp.size} ($totalDirectTools+ / 244+)"
        else -> "MCP: ${connectedMcp.size}/${enabledMcp.size} ($totalDirectTools)"
    }

    val activeSsh = sshProfiles.firstOrNull { it.enabled && it.isDefault }
        ?: sshProfiles.firstOrNull { it.enabled }
    val isSshConnected = activeSsh != null && connectedSshIds.contains(activeSsh.id)
    val isSshConfigured = activeSsh != null && activeSsh.host.isNotBlank() && activeSsh.username.isNotBlank()
    val sshDotColor = when {
        isSshConnected -> ForgeColors.AccentEmerald
        isSshConfigured -> ForgeColors.AccentBlue
        isTermuxIntentReady -> ForgeColors.AccentAmber
        else -> ForgeColors.TextSecondary
    }
    val sshLabel = when {
        isSshConnected -> "SSH: ${activeSsh.name}"
        isSshConfigured -> "SSH: ${activeSsh.name} (готов)"
        isTermuxIntentReady -> "Termux API"
        activeSsh == null -> "SSH: выкл"
        else -> "SSH: не настр."
    }

    val hasSaf = !safTreeUri.isNullOrBlank()
    val safDotColor = if (hasSaf) ForgeColors.AccentEmerald else ForgeColors.TextSecondary
    val safLabel = if (hasSaf) {
        val short = WorkspaceManager.formatSafTreeLabel(safTreeUri).substringAfterLast(" / ").ifBlank { "Папка Android" }
        "SAF: $short"
    } else {
        "SAF: нет"
    }

    Surface(
        color = ForgeColors.SurfaceDark,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusIndicatorPill(label = mcpLabel, dotColor = mcpDotColor, onClick = onToggleExpand)
                    StatusIndicatorPill(label = sshLabel, dotColor = sshDotColor, onClick = onToggleExpand)
                    StatusIndicatorPill(label = safLabel, dotColor = safDotColor, onClick = onToggleExpand)
                }

                IconButton(onClick = onRefreshAll, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = "Проверить подключения",
                        tint = ForgeColors.AccentBlue,
                        modifier = Modifier.size(15.dp)
                    )
                }
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "Детали подключений",
                        tint = ForgeColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Surface(
                    color = ForgeColors.SurfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Статус контуров и сред",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ForgeColors.TextPrimary
                            )
                            TextButton(
                                onClick = onOpenSettings,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Rounded.Settings, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Настройки", fontSize = 11.5.sp)
                            }
                        }

                        if (mcpServers.isEmpty()) {
                            Text(
                                "MCP-серверы не добавлены.",
                                fontSize = 11.5.sp,
                                color = ForgeColors.TextSecondary
                            )
                        } else {
                            mcpServers.forEach { srv ->
                                val insight = insightsById[srv.id]
                                    ?: McpClientManager.analyzeCatalog(emptyList())
                                val (badge, color) = when {
                                    !srv.enabled -> "Отключён вручную" to ForgeColors.TextSecondary
                                    !srv.lastError.isNullOrBlank() -> "Ошибка" to ForgeColors.DangerRed
                                    srv.lastConnectedAt != null -> "Подключён (${insight.directToolCount})" to ForgeColors.AccentEmerald
                                    else -> "Ожидает (${insight.directToolCount})" to ForgeColors.AccentAmber
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            srv.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ForgeColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "$badge • ${srv.transport}",
                                            fontSize = 11.sp,
                                            color = color,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (srv.enabled) {
                                            IconButton(
                                                onClick = { onTestMcp(srv.id) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Refresh,
                                                    "Проверить MCP",
                                                    tint = ForgeColors.AccentBlue,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = srv.enabled,
                                            onCheckedChange = { onToggleMcp(srv, it) }
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.6f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val sshTitle = activeSsh?.name ?: "SSH / Termux"
                                val sshDetail = when {
                                    activeSsh == null -> "Нет активного SSH-профиля"
                                    isSshConnected -> "Активная SSH-сессия (${activeSsh.host}:${activeSsh.port})"
                                    isSshConfigured -> "Готов к подключению (${activeSsh.host}:${activeSsh.port})"
                                    else -> "Профиль не заполнен"
                                }
                                Text(sshTitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ForgeColors.TextPrimary)
                                Text(sshDetail, fontSize = 11.sp, fontFamily = MonoFont, color = sshDotColor)
                            }
                            if (activeSsh != null && isSshConfigured) {
                                OutlinedButton(
                                    onClick = { onTestSsh(activeSsh) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ForgeColors.AccentBlue)
                                ) {
                                    Text("Тест SSH", fontSize = 11.sp)
                                }
                            }
                        }

                        Text(
                            "Папка Android (SAF): ${WorkspaceManager.formatSafTreeLabel(safTreeUri)}",
                            fontSize = 11.sp,
                            color = if (hasSaf) ForgeColors.AccentEmerald else ForgeColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIndicatorPill(
    label: String,
    dotColor: Color,
    onClick: () -> Unit
) {
    Surface(
        color = ForgeColors.SurfaceElevated,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, ForgeColors.BorderSubtle.copy(alpha = 0.8f)),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = label,
                fontSize = 11.sp,
                color = ForgeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
