package com.forgemind.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.forgemind.app.R
import com.forgemind.app.ui.formatSafTreeLabelLocalized
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    val context = LocalContext.current
    val mcpLabel = when {
        mcpServers.isEmpty() -> stringResource(R.string.conn_mcp_none)
        enabledMcp.isEmpty() -> stringResource(R.string.conn_mcp_off, mcpServers.size)
        errorMcp.isNotEmpty() -> stringResource(R.string.conn_mcp_err_fraction, connectedMcp.size, enabledMcp.size)
        hasGateway -> "MCP: ${connectedMcp.size}/${enabledMcp.size} ($totalDirectTools+ Gateway)"
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
        isSshConfigured -> stringResource(R.string.conn_ssh_ready, activeSsh.name)
        isTermuxIntentReady -> "Termux API"
        activeSsh == null -> stringResource(R.string.conn_ssh_off)
        else -> stringResource(R.string.conn_ssh_not_configured)
    }

    val hasSaf = !safTreeUri.isNullOrBlank()
    val safDotColor = if (hasSaf) ForgeColors.AccentEmerald else ForgeColors.TextSecondary
    val safLabel = if (hasSaf) {
        val safShortFallback = stringResource(R.string.env_saf_short)
        val short = formatSafTreeLabelLocalized(context, safTreeUri).substringAfterLast(" / ").ifBlank { safShortFallback }
        "SAF: $short"
    } else {
        stringResource(R.string.conn_saf_none)
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
                    StatusIndicatorPill(label = mcpLabel, dotColor = mcpDotColor, icon = Icons.Rounded.Hub, onClick = onToggleExpand)
                    StatusIndicatorPill(label = sshLabel, dotColor = sshDotColor, icon = Icons.Rounded.Terminal, onClick = onToggleExpand)
                    StatusIndicatorPill(label = safLabel, dotColor = safDotColor, icon = Icons.Rounded.FolderOpen, onClick = onToggleExpand)
                }

                IconButton(onClick = onRefreshAll, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = stringResource(R.string.conn_check_connections),
                        tint = ForgeColors.AccentBlue,
                        modifier = Modifier.size(15.dp)
                    )
                }
                val chevronRotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                    label = "statusExpandChevron"
                )
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(R.string.conn_details),
                        tint = ForgeColors.TextSecondary,
                        modifier = Modifier
                            .size(17.dp)
                            .rotate(chevronRotation)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(130))
            ) {
                Surface(
                    color = ForgeColors.SurfaceElevated,
                    shape = RoundedCornerShape(12.dp),
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
                                stringResource(R.string.conn_status_title),
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
                                Text(stringResource(R.string.nav_settings), fontSize = 11.5.sp)
                            }
                        }

                        if (mcpServers.isEmpty()) {
                            Text(
                                stringResource(R.string.conn_no_mcp),
                                fontSize = 11.5.sp,
                                color = ForgeColors.TextSecondary
                            )
                        } else {
                            mcpServers.forEach { srv ->
                                val insight = insightsById[srv.id]
                                    ?: McpClientManager.analyzeCatalog(emptyList())
                                val (badge, color) = when {
                                    !srv.enabled -> stringResource(R.string.conn_mcp_disabled) to ForgeColors.TextSecondary
                                    !srv.lastError.isNullOrBlank() -> stringResource(R.string.label_error) to ForgeColors.DangerRed
                                    srv.lastConnectedAt != null -> stringResource(R.string.conn_mcp_connected_count, insight.directToolCount) to ForgeColors.AccentEmerald
                                    else -> stringResource(R.string.conn_mcp_pending_count, insight.directToolCount) to ForgeColors.AccentAmber
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
                                                    stringResource(R.string.conn_check_mcp),
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
                                val sshTitle = activeSsh?.name ?: stringResource(R.string.env_termux_ssh)
                                val sshDetail = when {
                                    activeSsh == null -> stringResource(R.string.conn_no_active_ssh)
                                    isSshConnected -> stringResource(R.string.conn_ssh_active_session, activeSsh.host, activeSsh.port)
                                    isSshConfigured -> stringResource(R.string.conn_ssh_ready_host, activeSsh.host, activeSsh.port)
                                    else -> stringResource(R.string.conn_ssh_incomplete)
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
                                    Text(stringResource(R.string.conn_test_ssh), fontSize = 11.sp)
                                }
                            }
                        }

                        Text(
                            stringResource(R.string.conn_saf_label, formatSafTreeLabelLocalized(context, safTreeUri)),
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
    icon: ImageVector,
    onClick: () -> Unit
) {
    val animatedDotColor by animateColorAsState(
        targetValue = dotColor,
        animationSpec = tween(260),
        label = "statusDotColor"
    )
    Surface(
        color = ForgeColors.SurfaceElevated,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, animatedDotColor.copy(alpha = 0.28f)),
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
                    .background(animatedDotColor, CircleShape)
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedDotColor.copy(alpha = 0.9f),
                modifier = Modifier.size(12.dp)
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
