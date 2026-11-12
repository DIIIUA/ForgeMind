package com.forgemind.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.PendingApproval
import com.forgemind.app.data.model.ToolExecutionCard
import com.forgemind.app.data.model.ToolExecutionStatus
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

private val uiJson = Json { ignoreUnknownKeys = true }

fun decodeToolCards(raw: String): List<ToolExecutionCard> =
    if (raw.isBlank() || raw == "[]") emptyList()
    else runCatching { uiJson.decodeFromString<List<ToolExecutionCard>>(raw) }.getOrDefault(emptyList())

fun decodeAttachments(raw: String): List<ChatAttachment> =
    if (raw.isBlank() || raw == "[]") emptyList()
    else runCatching { uiJson.decodeFromString<List<ChatAttachment>>(raw) }.getOrDefault(emptyList())

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f КБ", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f МБ", mb)
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun openAttachmentExternally(context: Context, att: ChatAttachment, onError: (String) -> Unit) {
    val file = File(att.localPath)
    if (!file.exists()) {
        onError("Файл не найден: ${att.name}")
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, att.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Открыть ${att.name}"))
    }.onFailure {
        onError("Нет приложения для открытия: ${it.message}")
    }
}

fun shareAttachmentExternally(context: Context, att: ChatAttachment, onError: (String) -> Unit) {
    val file = File(att.localPath)
    if (!file.exists()) {
        onError("Файл не найден: ${att.name}")
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = att.mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться ${att.name}"))
    }.onFailure {
        onError("Не удалось поделиться: ${it.message}")
    }
}

@Composable
fun ScrollToBottomFab(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        androidx.compose.material3.SmallFloatingActionButton(
            onClick = onClick,
            containerColor = ForgeColors.SurfaceElevated,
            contentColor = ForgeColors.AccentBlue,
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = "Вниз"
            )
        }
    }
}

@Composable
fun ContextUsageBar(
    estimatedTokens: Int,
    contextWindow: Int,
    onCompactClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeWindow = contextWindow.coerceAtLeast(1024)
    val ratio = (estimatedTokens.toFloat() / safeWindow.toFloat()).coerceIn(0f, 1f)
    val pct = (ratio * 100f).toInt()
    val barColor = when {
        ratio >= 0.80f -> ForgeColors.DangerRed
        ratio >= 0.60f -> ForgeColors.AccentAmber
        else -> ForgeColors.AccentEmerald
    }

    Surface(
        color = ForgeColors.SurfaceDark,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Контекст: ${formatCompactCount(estimatedTokens)} / ${formatCompactCount(safeWindow)} ($pct%)",
                        style = MaterialTheme.typography.labelMedium,
                        color = ForgeColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = barColor,
                    trackColor = ForgeColors.BorderSubtle
                )
            }
            TextButton(
                onClick = onCompactClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Compress,
                    contentDescription = "Сжать контекст",
                    modifier = Modifier.size(14.dp),
                    tint = ForgeColors.AccentBlue
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Сжать", fontSize = 11.sp, color = ForgeColors.AccentBlue)
            }
        }
    }
}

private fun formatCompactCount(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000f)
    else -> n.toString()
}

@Composable
fun PendingApprovalBanner(
    approval: PendingApproval,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF281F10)),
        border = androidx.compose.foundation.BorderStroke(1.dp, ForgeColors.AccentAmber)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = ForgeColors.AccentAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = approval.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeColors.AccentAmber,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = approval.details,
                fontFamily = MonoFont,
                fontSize = 11.5.sp,
                color = ForgeColors.TextPrimary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
            if (!approval.diffPreview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                UnifiedDiffView(diffText = approval.diffPreview)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ForgeColors.DangerRed)
                ) {
                    Text("Отклонить", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onAllow,
                    colors = ButtonDefaults.buttonColors(containerColor = ForgeColors.AccentEmerald)
                ) {
                    Text("Разрешить", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatMessageBubble(
    message: ChatMessageEntity,
    isSpeakingThis: Boolean,
    canRetry: Boolean = false,
    canEdit: Boolean = false,
    onRetryClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onSpeakClick: () -> Unit,
    onCopyText: (String) -> Unit,
    onSaveAttachment: (ChatAttachment) -> Unit,
    onRestoreSnapshot: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val isUser = message.role == LlmRole.USER.name
    val cards = remember(message.toolCardsJson) { decodeToolCards(message.toolCardsJson) }
    val attachments = remember(message.attachmentsJson) { decodeAttachments(message.attachmentsJson) }
    var previewImage by remember { mutableStateOf<ChatAttachment?>(null) }

    val bubbleBg = when {
        message.isError -> Color(0xFF331418)
        isUser -> Color(0xFF1B2E4E)
        else -> ForgeColors.SurfaceCard
    }
    val bubbleBorder = when {
        message.isError -> ForgeColors.DangerRed.copy(alpha = 0.65f)
        isUser -> ForgeColors.AccentBlue.copy(alpha = 0.45f)
        else -> ForgeColors.BorderSubtle
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = bubbleBg,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 16.dp
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, bubbleBorder),
            tonalElevation = if (isUser) 2.dp else 1.dp,
            modifier = if (isUser) Modifier.widthIn(max = 340.dp) else Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (isUser) "Вы" else "ForgeMind",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isUser) ForgeColors.AccentBlue else ForgeColors.AccentEmerald
                        )
                        if (!isUser && !message.modelId.isNullOrBlank()) {
                            Text(
                                text = message.modelId.substringAfterLast('/'),
                                fontSize = 10.sp,
                                color = ForgeColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 150.dp)
                            )
                        }
                        if (message.isStreaming) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                strokeWidth = 1.8.dp,
                                color = ForgeColors.AccentBlue
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (canRetry) {
                            IconButton(
                                onClick = onRetryClick,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Повторить",
                                    modifier = Modifier.size(16.dp),
                                    tint = if (message.isError) ForgeColors.DangerRed else ForgeColors.AccentAmber
                                )
                            }
                        }
                        if (canEdit) {
                            IconButton(
                                onClick = onEditClick,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "Редактировать",
                                    modifier = Modifier.size(15.dp),
                                    tint = ForgeColors.TextSecondary
                                )
                            }
                        }
                        if (!isUser && message.content.isNotBlank()) {
                            IconButton(
                                onClick = onSpeakClick,
                                modifier = Modifier.size(26.dp)
                            ) {
                            Icon(
                                imageVector = if (isSpeakingThis) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = if (isSpeakingThis) "Остановить озвучку" else "Озвучить",
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSpeakingThis) ForgeColors.AccentAmber else ForgeColors.TextSecondary
                                )
                            }
                        }
                        if (message.content.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    copyToClipboard(context, "ForgeMind", message.content)
                                    onCopyText("Скопировано в буфер")
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Копировать",
                                    modifier = Modifier.size(15.dp),
                                    tint = ForgeColors.TextSecondary
                                )
                            }
                        }
                    }
                }

                // Thinking block
                if (message.thinking.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    ThinkingAccordion(thinkingText = message.thinking)
                }

                // Tool Execution Cards
                if (cards.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        cards.forEach { card ->
                            ToolExecutionCardView(
                                card = card,
                                onRestoreSnapshot = onRestoreSnapshot
                            )
                        }
                    }
                }

                // Main content with Markdown + Code Blocks
                if (message.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    SelectionContainer {
                        RichMarkdownText(
                            markdown = message.content,
                            onCopyCode = { code ->
                                copyToClipboard(context, "Code", code)
                                onCopyText("Код скопирован")
                            }
                        )
                    }
                }

                // Attachments (both user uploaded and AI generated)
                if (attachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        attachments.forEach { att ->
                            ChatAttachmentCard(
                                attachment = att,
                                onImageClick = { previewImage = att },
                                onSaveClick = { onSaveAttachment(att) },
                                onOpenClick = { openAttachmentExternally(context, att, onError) },
                                onShareClick = { shareAttachmentExternally(context, att, onError) }
                            )
                        }
                    }
                }

                if (canRetry) {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onRetryClick,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (message.isError) ForgeColors.DangerRed else ForgeColors.AccentAmber
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Повторить",
                            fontSize = 11.5.sp,
                            color = if (message.isError) ForgeColors.DangerRed else ForgeColors.TextPrimary
                        )
                    }
                }

                if (!isUser && (message.promptTokens > 0 || message.completionTokens > 0)) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "in ${message.promptTokens} • out ${message.completionTokens} tok",
                        fontSize = 10.sp,
                        color = ForgeColors.TextSecondary.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    previewImage?.let { imgAtt ->
        FullscreenImageDialog(
            attachment = imgAtt,
            onDismiss = { previewImage = null },
            onSave = { onSaveAttachment(imgAtt) },
            onShare = { shareAttachmentExternally(context, imgAtt, onError) }
        )
    }
}

@Composable
private fun ThinkingAccordion(thinkingText: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = ForgeColors.SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ForgeColors.AccentAmber.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Psychology,
                        contentDescription = null,
                        tint = ForgeColors.AccentAmber,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        "Размышления модели • ${thinkingText.length} симв.",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = ForgeColors.AccentAmber
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = ForgeColors.TextSecondary
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = thinkingText.trim(),
                    fontSize = 12.sp,
                    color = ForgeColors.TextSecondary,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
fun ToolExecutionCardView(
    card: ToolExecutionCard,
    onRestoreSnapshot: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (card.status) {
        ToolExecutionStatus.RUNNING -> ForgeColors.AccentBlue
        ToolExecutionStatus.SUCCESS -> ForgeColors.AccentEmerald
        ToolExecutionStatus.ERROR, ToolExecutionStatus.DENIED -> ForgeColors.DangerRed
        ToolExecutionStatus.NEEDS_APPROVAL -> ForgeColors.AccentAmber
    }

    Surface(
        color = ForgeColors.BgDeep.copy(alpha = 0.72f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    when (card.status) {
                        ToolExecutionStatus.RUNNING -> CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.6.dp,
                            color = statusColor
                        )
                        ToolExecutionStatus.SUCCESS -> Icon(
                            Icons.Rounded.CheckCircle, null, tint = statusColor, modifier = Modifier.size(14.dp)
                        )
                        ToolExecutionStatus.ERROR, ToolExecutionStatus.DENIED -> Icon(
                            Icons.Rounded.ErrorOutline, null, tint = statusColor, modifier = Modifier.size(14.dp)
                        )
                        ToolExecutionStatus.NEEDS_APPROVAL -> Icon(
                            Icons.Rounded.HourglassTop, null, tint = statusColor, modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = card.toolName,
                        fontFamily = MonoFont,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForgeColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!card.mcpServerName.isNullOrBlank()) {
                        Surface(
                            color = ForgeColors.AccentBlue.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "MCP:${card.mcpServerName}",
                                fontSize = 9.5.sp,
                                color = ForgeColors.AccentBlue,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.durationMs > 0) {
                        Text("${card.durationMs}ms", fontSize = 10.sp, color = ForgeColors.TextSecondary)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = ForgeColors.TextSecondary
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        text = "Аргументы: ${card.argumentsJson}",
                        fontFamily = MonoFont,
                        fontSize = 10.5.sp,
                        color = ForgeColors.TextSecondary
                    )
                    if (card.resultPreview.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = card.resultPreview,
                            fontFamily = MonoFont,
                            fontSize = 11.sp,
                            color = ForgeColors.TextPrimary,
                            maxLines = 14,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!card.diffText.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        UnifiedDiffView(diffText = card.diffText)
                    }
                    if (!card.snapshotId.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { onRestoreSnapshot(card.snapshotId) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Rounded.Restore, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Откатить изменение", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnifiedDiffView(diffText: String) {
    val scroll = rememberScrollState()
    Surface(
        color = Color(0xFF0A0E14),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ForgeColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(scroll)
                .padding(6.dp)
        ) {
            diffText.lines().take(60).forEach { line ->
                val bg = when {
                    line.startsWith("+") && !line.startsWith("+++") -> ForgeColors.DiffAddBg
                    line.startsWith("-") && !line.startsWith("---") -> ForgeColors.DiffDelBg
                    else -> Color.Transparent
                }
                val fg = when {
                    line.startsWith("+") && !line.startsWith("+++") -> ForgeColors.DiffAddText
                    line.startsWith("-") && !line.startsWith("---") -> ForgeColors.DiffDelText
                    line.startsWith("@@") -> ForgeColors.AccentBlue
                    else -> ForgeColors.TextSecondary
                }
                Text(
                    text = line,
                    fontFamily = MonoFont,
                    fontSize = 10.5.sp,
                    color = fg,
                    modifier = Modifier
                        .background(bg)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
fun ChatAttachmentCard(
    attachment: ChatAttachment,
    onImageClick: () -> Unit,
    onSaveClick: () -> Unit,
    onOpenClick: () -> Unit,
    onShareClick: () -> Unit
) {
    Surface(
        color = ForgeColors.SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ForgeColors.BorderSubtle),
        modifier = Modifier.widthIn(min = 160.dp, max = 280.dp)
    ) {
        val hasExistingImageFile = remember(attachment.localPath, attachment.kind) {
            attachment.kind == AttachmentKind.IMAGE && File(attachment.localPath).exists()
        }
        Column(modifier = Modifier.padding(8.dp)) {
            if (hasExistingImageFile) {
                AsyncImage(
                    model = File(attachment.localPath),
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onImageClick() }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (attachment.kind) {
                    AttachmentKind.PDF -> Icons.Rounded.PictureAsPdf
                    AttachmentKind.AUDIO -> Icons.Rounded.Audiotrack
                    AttachmentKind.CODE_TEXT -> Icons.Rounded.Code
                    AttachmentKind.IMAGE -> Icons.Rounded.AttachFile
                    AttachmentKind.BINARY -> Icons.Rounded.Archive
                }
                Icon(icon, null, tint = ForgeColors.AccentBlue, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ForgeColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(formatBytes(attachment.sizeBytes))
                            if (attachment.isFromAssistant) append(" • от ИИ")
                        },
                        fontSize = 10.sp,
                        color = ForgeColors.TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onSaveClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Download, "Сохранить", tint = ForgeColors.AccentEmerald, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onOpenClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Открыть", tint = ForgeColors.TextPrimary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onShareClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Share, "Поделиться", tint = ForgeColors.AccentBlue, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun FullscreenImageDialog(
    attachment: ChatAttachment,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
        ) {
            AsyncImage(
                model = File(attachment.localPath),
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onSave,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Rounded.Download, "Сохранить", tint = Color.White)
                }
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Rounded.Share, "Поделиться", tint = Color.White)
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Rounded.Close, "Закрыть", tint = Color.White)
                }
            }
        }
    }
}

private sealed interface MdSegment {
    data class TextBlock(val text: String) : MdSegment
    data class CodeBlock(val lang: String, val code: String) : MdSegment
}

private sealed interface MdRenderBlock {
    data class Heading(val level: Int, val text: AnnotatedString) : MdRenderBlock
    data object Divider : MdRenderBlock
    data class Paragraph(val text: AnnotatedString) : MdRenderBlock
    data class CodeBlock(val lang: String, val code: String) : MdRenderBlock
}

@Composable
fun RichMarkdownText(
    markdown: String,
    onCopyCode: (String) -> Unit
) {
    val blocks = remember(markdown) { buildRichMarkdownBlocks(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdRenderBlock.CodeBlock -> CodeSnippetCard(
                    lang = block.lang,
                    code = block.code,
                    onCopy = { onCopyCode(block.code) }
                )
                is MdRenderBlock.Heading -> {
                    val fontSize = when (block.level) {
                        1 -> 17.sp
                        2 -> 16.sp
                        else -> 15.sp
                    }
                    Text(
                        text = block.text,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        color = ForgeColors.TextPrimary
                    )
                }
                MdRenderBlock.Divider -> {
                    HorizontalDivider(color = ForgeColors.BorderSubtle, modifier = Modifier.padding(vertical = 2.dp))
                }
                is MdRenderBlock.Paragraph -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeColors.TextPrimary
                )
            }
        }
    }
}

@Composable
private fun CodeSnippetCard(
    lang: String,
    code: String,
    onCopy: () -> Unit
) {
    val hScroll = rememberScrollState()
    Surface(
        color = Color(0xFF0A0E16),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ForgeColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeColors.SurfaceElevated)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang.ifBlank { "code" },
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    color = ForgeColors.AccentBlue
                )
                Row(
                    modifier = Modifier.clickable { onCopy() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, tint = ForgeColors.TextSecondary, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Копировать", fontSize = 11.sp, color = ForgeColors.TextSecondary)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hScroll)
                    .padding(10.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFFD6DEEB)
                )
            }
        }
    }
}

private fun splitMarkdownSegments(raw: String): List<MdSegment> {
    val result = mutableListOf<MdSegment>()
    val lines = raw.lines()
    val textBuf = StringBuilder()
    var inCode = false
    var codeLang = ""
    val codeBuf = StringBuilder()

    for (line in lines) {
        val trimmed = line.trimStart()
        if (!inCode && trimmed.startsWith("```")) {
            if (textBuf.isNotEmpty()) {
                result += MdSegment.TextBlock(textBuf.toString().trimEnd())
                textBuf.clear()
            }
            inCode = true
            codeLang = trimmed.removePrefix("```").trim()
            codeBuf.clear()
        } else if (inCode && trimmed.startsWith("```")) {
            result += MdSegment.CodeBlock(codeLang, codeBuf.toString().trimEnd())
            inCode = false
            codeLang = ""
            codeBuf.clear()
        } else if (inCode) {
            if (codeBuf.isNotEmpty()) codeBuf.append('\n')
            codeBuf.append(line)
        } else {
            if (textBuf.isNotEmpty()) textBuf.append('\n')
            textBuf.append(line)
        }
    }

    if (inCode) {
        result += MdSegment.CodeBlock(codeLang, codeBuf.toString())
    } else if (textBuf.isNotEmpty()) {
        result += MdSegment.TextBlock(textBuf.toString())
    }
    return result
}

private val INLINE_MARKERS = charArrayOf('*', '`')

private fun buildRichMarkdownBlocks(markdown: String): List<MdRenderBlock> {
    val segments = splitMarkdownSegments(markdown)
    val blocks = ArrayList<MdRenderBlock>(segments.size * 2)
    val pendingLines = ArrayList<String>()

    fun flushPending() {
        if (pendingLines.isEmpty()) return
        val annotated = buildAnnotatedString {
            pendingLines.forEachIndexed { idx, l ->
                if (idx > 0) append('\n')
                appendInlineMarkdown(this, l)
            }
        }
        blocks += MdRenderBlock.Paragraph(annotated)
        pendingLines.clear()
    }

    for (seg in segments) {
        when (seg) {
            is MdSegment.CodeBlock -> {
                flushPending()
                blocks += MdRenderBlock.CodeBlock(seg.lang, seg.code)
            }
            is MdSegment.TextBlock -> {
                for (line in seg.text.lines()) {
                    when {
                        line.startsWith("### ") -> {
                            flushPending()
                            blocks += MdRenderBlock.Heading(3, parseInlineMarkdown(line.removePrefix("### ")))
                        }
                        line.startsWith("## ") -> {
                            flushPending()
                            blocks += MdRenderBlock.Heading(2, parseInlineMarkdown(line.removePrefix("## ")))
                        }
                        line.startsWith("# ") -> {
                            flushPending()
                            blocks += MdRenderBlock.Heading(1, parseInlineMarkdown(line.removePrefix("# ")))
                        }
                        line.startsWith("---") && line.trim().all { it == '-' } -> {
                            flushPending()
                            blocks += MdRenderBlock.Divider
                        }
                        else -> pendingLines += line
                    }
                }
                flushPending()
            }
        }
    }
    return blocks
}

private fun parseInlineMarkdown(line: String): AnnotatedString = buildAnnotatedString {
    appendInlineMarkdown(this, line)
}

private fun appendInlineMarkdown(builder: AnnotatedString.Builder, line: String) {
    val cleanLine = when {
        line.startsWith("- ") -> "• " + line.drop(2)
        line.startsWith("* ") -> "• " + line.drop(2)
        else -> line
    }
    var i = 0
    val len = cleanLine.length
    while (i < len) {
        val next = cleanLine.indexOfAny(INLINE_MARKERS, startIndex = i)
        if (next < 0) {
            builder.append(cleanLine, i, len)
            break
        }
        if (next > i) {
            builder.append(cleanLine, i, next)
            i = next
        }
        when {
            cleanLine.startsWith("**", i) -> {
                val end = cleanLine.indexOf("**", i + 2)
                if (end > i + 2) {
                    builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(cleanLine, i + 2, end)
                    }
                    i = end + 2
                } else {
                    builder.append("**")
                    i += 2
                }
            }
            cleanLine[i] == '`' -> {
                val end = cleanLine.indexOf('`', i + 1)
                if (end > i + 1) {
                    builder.withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0xFF232C3F),
                            color = Color(0xFF93C5FD),
                            fontSize = 13.sp
                        )
                    ) {
                        append(' ')
                        append(cleanLine, i + 1, end)
                        append(' ')
                    }
                    i = end + 1
                } else {
                    builder.append('`')
                    i++
                }
            }
            cleanLine[i] == '*' && (i + 1 < len && !cleanLine[i + 1].isWhitespace()) -> {
                val end = cleanLine.indexOf('*', i + 1)
                if (end > i + 1) {
                    builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(cleanLine, i + 1, end)
                    }
                    i = end + 1
                } else {
                    builder.append('*')
                    i++
                }
            }
            else -> {
                builder.append(cleanLine[i])
                i++
            }
        }
    }
}
