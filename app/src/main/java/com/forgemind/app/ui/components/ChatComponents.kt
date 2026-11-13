package com.forgemind.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.res.stringResource
import com.forgemind.app.R
import com.forgemind.app.ui.formatByteCount
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont
import kotlinx.coroutines.delay
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

fun formatBytes(bytes: Long): String = formatByteCount(bytes)

fun copyToClipboard(context: Context, label: String, text: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun openAttachmentExternally(context: Context, att: ChatAttachment, onError: (String) -> Unit) {
    openLocalFileExternally(context, File(att.localPath), att.mimeType, att.name, onError)
}

fun shareAttachmentExternally(context: Context, att: ChatAttachment, onError: (String) -> Unit) {
    shareLocalFileExternally(context, File(att.localPath), att.mimeType, att.name, onError)
}

@Composable
fun ScrollToBottomFab(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(190, easing = FastOutSlowInEasing)) { it / 2 } +
            scaleIn(tween(190), initialScale = 0.84f) +
            fadeIn(tween(160)),
        exit = slideOutVertically(tween(150)) { it / 2 } +
            scaleOut(tween(150), targetScale = 0.84f) +
            fadeOut(tween(120)),
        modifier = modifier
    ) {
        androidx.compose.material3.SmallFloatingActionButton(
            onClick = onClick,
            containerColor = ForgeColors.SurfaceCard,
            contentColor = ForgeColors.AccentBlue,
            shape = CircleShape,
            modifier = Modifier.border(1.dp, ForgeColors.BorderSubtle, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = stringResource(R.string.scroll_bottom)
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
    val targetBarColor = when {
        ratio >= 0.80f -> ForgeColors.DangerRed
        ratio >= 0.60f -> ForgeColors.AccentAmber
        else -> ForgeColors.AccentEmerald
    }
    val animatedRatio by animateFloatAsState(
        targetValue = ratio,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "contextRatio"
    )
    val animatedBarColor by animateColorAsState(
        targetValue = targetBarColor,
        animationSpec = tween(300),
        label = "contextColor"
    )

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
                        text = stringResource(
                            R.string.context_meter_format,
                            formatCompactCount(estimatedTokens),
                            formatCompactCount(safeWindow),
                            pct
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = ForgeColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { animatedRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = animatedBarColor,
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
                    contentDescription = stringResource(R.string.action_compact_context),
                    modifier = Modifier.size(14.dp),
                    tint = ForgeColors.AccentBlue
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_compact_short), fontSize = 11.sp, color = ForgeColors.AccentBlue)
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
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ForgeColors.DangerRed)
                ) {
                    Icon(Icons.Rounded.Close, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_deny), fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onAllow,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ForgeColors.AccentEmerald)
                ) {
                    Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_allow), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
    var previewAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    var justCopied by remember(message.id) { mutableStateOf(false) }

    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1400L)
            justCopied = false
        }
    }

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
                        Icon(
                            imageVector = if (isUser) Icons.Rounded.Person else Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = if (isUser) ForgeColors.AccentBlue else ForgeColors.AccentEmerald,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (isUser) stringResource(R.string.label_you) else "ForgeMind",
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
                            val streamTransition = rememberInfiniteTransition(label = "streamPulse")
                            val pulseAlpha by streamTransition.animateFloat(
                                initialValue = 0.35f,
                                targetValue = 1.0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(650, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "streamAlpha"
                            )
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .alpha(pulseAlpha)
                                    .background(ForgeColors.AccentBlue, CircleShape)
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (canEdit) {
                            IconButton(
                                onClick = onEditClick,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = stringResource(R.string.action_edit),
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
                                    contentDescription = if (isSpeakingThis) {
                                        stringResource(R.string.tts_stop_speaking)
                                    } else {
                                        stringResource(R.string.tts_speak)
                                    },
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSpeakingThis) ForgeColors.AccentAmber else ForgeColors.TextSecondary
                                )
                            }
                        }
                        if (message.content.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    copyToClipboard(context, "ForgeMind", message.content)
                                    justCopied = true
                                    onCopyText(context.getString(R.string.copied_to_clipboard))
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                AnimatedContent(
                                    targetState = justCopied,
                                    transitionSpec = {
                                        (fadeIn(tween(150)) + scaleIn(tween(150), 0.75f))
                                            .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), 0.75f))
                                    },
                                    label = "msgCopyAnim"
                                ) { copied ->
                                    Icon(
                                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                        contentDescription = if (copied) {
                                            stringResource(R.string.action_copied)
                                        } else {
                                            stringResource(R.string.action_copy)
                                        },
                                        modifier = Modifier.size(15.dp),
                                        tint = if (copied) ForgeColors.AccentEmerald else ForgeColors.TextSecondary
                                    )
                                }
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
                                onCopyText(context.getString(R.string.code_copied))
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
                                onImageClick = { previewAttachment = att },
                                onSaveClick = { onSaveAttachment(att) },
                                onOpenClick = { previewAttachment = att },
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
                            text = stringResource(R.string.action_retry),
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

    previewAttachment?.let { att ->
        InAppAttachmentViewerDialog(
            attachment = att,
            onDismiss = { previewAttachment = null },
            onSave = { onSaveAttachment(att) },
            onShare = { shareAttachmentExternally(context, att, onError) },
            onError = onError
        )
    }
}

@Composable
private fun ThinkingAccordion(thinkingText: String) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "thinkingExpandArrow"
    )
    Surface(
        color = ForgeColors.SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ForgeColors.AccentAmber.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
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
                        stringResource(R.string.thinking_card_header, thinkingText.length),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = ForgeColors.AccentAmber
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(17.dp)
                        .rotate(arrowRotation),
                    tint = ForgeColors.TextSecondary
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(210, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(170, easing = FastOutSlowInEasing)) + fadeOut(tween(130))
            ) {
                Text(
                    text = thinkingText.trim(),
                    fontSize = 12.sp,
                    color = ForgeColors.TextSecondary,
                    lineHeight = 16.5.sp,
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
    val statusColor by animateColorAsState(
        targetValue = when (card.status) {
            ToolExecutionStatus.RUNNING -> ForgeColors.AccentBlue
            ToolExecutionStatus.SUCCESS -> ForgeColors.AccentEmerald
            ToolExecutionStatus.ERROR, ToolExecutionStatus.DENIED -> ForgeColors.DangerRed
            ToolExecutionStatus.NEEDS_APPROVAL -> ForgeColors.AccentAmber
        },
        animationSpec = tween(260),
        label = "toolStatusColor"
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "toolExpandArrow"
    )

    Surface(
        color = ForgeColors.BgDeep.copy(alpha = 0.72f),
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = if (card.status == ToolExecutionStatus.RUNNING) 0.65f else 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
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
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(arrowRotation),
                        tint = ForgeColors.TextSecondary
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(210, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(170, easing = FastOutSlowInEasing)) + fadeOut(tween(130))
            ) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        text = stringResource(R.string.tool_args_prefix, card.argumentsJson),
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
                            Text(stringResource(R.string.action_revert_change), fontSize = 11.sp)
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

            val fromAiTag = stringResource(R.string.tag_from_ai)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenClick() }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                            if (attachment.isFromAssistant) append(fromAiTag)
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
                    Icon(Icons.Rounded.Download, stringResource(R.string.action_save), tint = ForgeColors.AccentEmerald, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onOpenClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.action_open), tint = ForgeColors.TextPrimary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onShareClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Share, stringResource(R.string.action_share), tint = ForgeColors.AccentBlue, modifier = Modifier.size(16.dp))
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
    var justCopied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1400L)
            justCopied = false
        }
    }
    Surface(
        color = Color(0xFF0A0E16),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeColors.SurfaceElevated)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Code,
                        contentDescription = null,
                        tint = ForgeColors.AccentBlue,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = lang.ifBlank { "code" },
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                        color = ForgeColors.AccentBlue
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            onCopy()
                            justCopied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (justCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        tint = if (justCopied) ForgeColors.AccentEmerald else ForgeColors.TextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (justCopied) stringResource(R.string.action_copied) else stringResource(R.string.action_copy),
                        fontSize = 11.sp,
                        color = if (justCopied) ForgeColors.AccentEmerald else ForgeColors.TextSecondary
                    )
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
