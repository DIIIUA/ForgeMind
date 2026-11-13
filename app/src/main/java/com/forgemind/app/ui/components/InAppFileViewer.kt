package com.forgemind.app.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.forgemind.app.R
import com.forgemind.app.bridge.InAppDocumentParser
import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.ui.EditorTabKind
import com.forgemind.app.ui.OpenEditorTab
import com.forgemind.app.ui.formatByteCount
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

fun openLocalFileExternally(
    context: Context,
    file: File,
    mimeType: String,
    displayName: String,
    onError: (String) -> Unit
) {
    if (!file.exists()) {
        onError(context.getString(R.string.file_not_found_name, displayName))
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_file_name, displayName)))
    }.onFailure {
        onError(context.getString(R.string.no_app_to_open, it.message.orEmpty()))
    }
}

fun shareLocalFileExternally(
    context: Context,
    file: File,
    mimeType: String,
    displayName: String,
    onError: (String) -> Unit
) {
    if (!file.exists()) {
        onError(context.getString(R.string.file_not_found_name, displayName))
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_file_name, displayName)))
    }.onFailure {
        onError(context.getString(R.string.share_failed, it.message.orEmpty()))
    }
}

@Composable
fun ZoomableImageBox(
    imageFile: File,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    var scale by remember(imageFile.absolutePath) { mutableFloatStateOf(1f) }
    var offset by remember(imageFile.absolutePath) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .background(Color(0xFF0A0D12))
            .pointerInput(imageFile.absolutePath) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = nextScale
                    offset = if (nextScale <= 1.02f) {
                        Offset.Zero
                    } else {
                        offset + pan
                    }
                }
            }
            .pointerInput(imageFile.absolutePath) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.2f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageFile,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )

        if (scale > 1.05f) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(20.dp),
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(
                        text = "${String.format(Locale.US, "%.1fx", scale)} • ${stringResource(R.string.viewer_zoom_reset)}",
                        color = Color.White,
                        fontSize = 11.5.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PdfInAppPane(
    pdfFile: File,
    extractedText: String,
    onOpenExternal: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showTextMode by remember(pdfFile.absolutePath) { mutableStateOf(false) }

    val rendererState = remember(pdfFile.absolutePath) {
        runCatching {
            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            fd to renderer
        }.getOrNull()
    }
    val renderMutex = remember(pdfFile.absolutePath) { Mutex() }

    DisposableEffect(rendererState) {
        onDispose {
            runCatching { rendererState?.second?.close() }
            runCatching { rendererState?.first?.close() }
        }
    }

    val pageCount = rendererState?.second?.pageCount ?: 0

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ForgeColors.SurfaceDark)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (pageCount > 0) {
                FilterChip(
                    selected = !showTextMode,
                    onClick = { showTextMode = false },
                    label = { Text(stringResource(R.string.viewer_tab_pages) + " ($pageCount)", fontSize = 11.5.sp) }
                )
            }
            FilterChip(
                selected = showTextMode || pageCount == 0,
                onClick = { showTextMode = true },
                label = { Text(stringResource(R.string.viewer_tab_text), fontSize = 11.5.sp) }
            )
            Spacer(modifier = Modifier.weight(1f))
            if (showTextMode && extractedText.isNotBlank()) {
                IconButton(onClick = { onCopyText(extractedText) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy), tint = ForgeColors.AccentBlue, modifier = Modifier.size(17.dp))
                }
            }
            IconButton(onClick = onOpenExternal, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.viewer_open_external), tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = ForgeColors.BorderSubtle)

        if (showTextMode || rendererState == null || pageCount == 0) {
            PlainDocumentReader(
                text = extractedText,
                modifier = Modifier.fillMaxSize(),
                onOpenExternal = onOpenExternal
            )
        } else {
            val renderer = rendererState.second
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0E1218)),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(pageCount) { index ->
                    PdfPageCard(
                        renderer = renderer,
                        mutex = renderMutex,
                        pageIndex = index,
                        totalPages = pageCount
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPageCard(
    renderer: PdfRenderer,
    mutex: Mutex,
    pageIndex: Int,
    totalPages: Int
) {
    val pageBitmap by produceState<Bitmap?>(initialValue = null, renderer, pageIndex) {
        value = withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    renderer.openPage(pageIndex).use { page ->
                        val targetWidth = 1120
                        val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                        val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }.getOrNull()
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.viewer_page_fmt, pageIndex + 1, totalPages),
            fontSize = 11.sp,
            color = ForgeColors.TextSecondary,
            modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
        )
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            val bmp = pageBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.viewer_page_fmt, pageIndex + 1, totalPages),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
fun PlainDocumentReader(
    text: String,
    modifier: Modifier = Modifier,
    isMarkdown: Boolean = false,
    onCopyCode: (String) -> Unit = {},
    onOpenExternal: (() -> Unit)? = null
) {
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        if (!isMarkdown) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (searchOpen) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.files_find_hint), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp)
                    )
                }
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Search, stringResource(R.string.files_search_in_file), tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (text.isBlank()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Description, null, tint = ForgeColors.TextSecondary, modifier = Modifier.size(40.dp))
                    Text(stringResource(R.string.viewer_empty_doc), color = ForgeColors.TextSecondary, fontSize = 13.5.sp)
                    if (onOpenExternal != null) {
                        OutlinedButton(onClick = onOpenExternal) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.viewer_open_external), fontSize = 12.5.sp)
                        }
                    }
                }
            }
        } else {
            val filteredText = remember(text, searchQuery) {
                if (searchQuery.isBlank()) text
                else text.lines()
                    .filter { it.contains(searchQuery, ignoreCase = true) }
                    .joinToString("\n")
            }
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    if (isMarkdown) {
                        RichMarkdownText(markdown = filteredText, onCopyCode = onCopyCode)
                    } else {
                        Text(
                            text = filteredText,
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            fontFamily = MonoFont,
                            color = ForgeColors.TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InAppFileTabViewer(
    tab: OpenEditorTab,
    onAttachToChat: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cacheFile = remember(tab.localCachePath) { tab.localCachePath?.let { File(it) } }
    val copiedToast = stringResource(R.string.copied_to_clipboard)

    fun openExternal() {
        if (cacheFile != null) {
            openLocalFileExternally(context, cacheFile, tab.mimeType, tab.title) { err ->
                onCopyText(err)
            }
        }
    }

    fun shareFile() {
        if (cacheFile != null) {
            shareLocalFileExternally(context, cacheFile, tab.mimeType, tab.title) { err ->
                onCopyText(err)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header toolbar for non-code tabs (Image / PDF / Document / Archive / Binary)
        Surface(color = ForgeColors.SurfaceDark) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tab.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForgeColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            formatByteCount(tab.sizeBytes),
                            tab.subtitle
                        ).joinToString(" • "),
                        fontSize = 11.sp,
                        color = ForgeColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (tab.content.isNotBlank()) {
                    IconButton(
                        onClick = {
                            copyToClipboard(context, tab.title, tab.content)
                            onCopyText(copiedToast)
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy), tint = ForgeColors.AccentBlue, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onAttachToChat, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.files_attach_chat), tint = ForgeColors.AccentEmerald, modifier = Modifier.size(18.dp))
                }
                if (cacheFile != null) {
                    IconButton(onClick = { openExternal() }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.viewer_open_external), tint = ForgeColors.TextPrimary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { shareFile() }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Share, stringResource(R.string.action_share), tint = ForgeColors.AccentBlue, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = ForgeColors.BorderSubtle)

        when (tab.kind) {
            EditorTabKind.IMAGE -> {
                if (cacheFile != null && cacheFile.exists()) {
                    ZoomableImageBox(
                        imageFile = cacheFile,
                        contentDescription = tab.title,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    PlainDocumentReader(text = "", onOpenExternal = { openExternal() })
                }
            }

            EditorTabKind.PDF -> {
                if (cacheFile != null && cacheFile.exists()) {
                    PdfInAppPane(
                        pdfFile = cacheFile,
                        extractedText = tab.content,
                        onOpenExternal = { openExternal() },
                        onCopyText = { txt ->
                            copyToClipboard(context, tab.title, txt)
                            onCopyText(copiedToast)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    PlainDocumentReader(
                        text = tab.content,
                        modifier = Modifier.weight(1f),
                        onOpenExternal = { openExternal() }
                    )
                }
            }

            EditorTabKind.DOCUMENT, EditorTabKind.ARCHIVE -> {
                PlainDocumentReader(
                    text = tab.content,
                    isMarkdown = false,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    onOpenExternal = if (cacheFile != null) ({ openExternal() }) else null
                )
            }

            EditorTabKind.BINARY -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.InsertDriveFile, null, tint = ForgeColors.AccentAmber, modifier = Modifier.size(48.dp))
                        Text(stringResource(R.string.viewer_binary_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ForgeColors.TextPrimary)
                        Text(
                            text = "${tab.title} (${formatByteCount(tab.sizeBytes)} • ${tab.mimeType})",
                            fontSize = 12.5.sp,
                            color = ForgeColors.AccentBlue
                        )
                        Text(
                            text = stringResource(R.string.viewer_binary_desc),
                            fontSize = 12.5.sp,
                            color = ForgeColors.TextSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onAttachToChat) {
                                Icon(Icons.AutoMirrored.Rounded.Send, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.files_attach_chat), fontSize = 12.sp)
                            }
                            if (cacheFile != null) {
                                OutlinedButton(onClick = { openExternal() }) {
                                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.action_open), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
fun InAppAttachmentViewerDialog(
    attachment: ChatAttachment,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val file = remember(attachment.localPath) { File(attachment.localPath) }
    val copiedMsg = stringResource(R.string.copied_to_clipboard)
    val isImage = attachment.kind == AttachmentKind.IMAGE || InAppDocumentParser.isImageExtension(attachment.name)
    val isPdf = attachment.kind == AttachmentKind.PDF || InAppDocumentParser.isPdfExtension(attachment.name)
    val isMarkdown = InAppDocumentParser.isMarkdownExtension(attachment.name)

    val loadedText by produceState<String?>(initialValue = attachment.previewText, attachment.localPath) {
        if (!isImage && value == null && file.exists()) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = file.readBytes()
                    when {
                        isPdf -> InAppDocumentParser.parsePdf(bytes).text
                        InAppDocumentParser.isOfficeDocumentExtension(attachment.name) ->
                            InAppDocumentParser.parseOfficeOrStructuredDocument(attachment.name, bytes).text
                        InAppDocumentParser.isArchiveExtension(attachment.name) ->
                            InAppDocumentParser.listArchiveEntries(bytes).text
                        !com.forgemind.app.bridge.WorkspaceManager.looksBinaryBytes(bytes) ->
                            bytes.toString(Charsets.UTF_8)
                        else -> ""
                    }
                }.getOrDefault("")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        if (isImage) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
            ) {
                ZoomableImageBox(
                    imageFile = file,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize()
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onSave,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    ) {
                        Icon(Icons.Rounded.Download, stringResource(R.string.action_save), tint = Color.White)
                    }
                    IconButton(
                        onClick = { openLocalFileExternally(context, file, attachment.mimeType, attachment.name, onError) },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.viewer_open_external), tint = Color.White)
                    }
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    ) {
                        Icon(Icons.Rounded.Share, stringResource(R.string.action_share), tint = Color.White)
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    ) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_close), tint = Color.White)
                    }
                }
            }
        } else {
            Surface(
                color = ForgeColors.BgDeep,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(color = ForgeColors.SurfaceDark) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isPdf) Icons.Rounded.PictureAsPdf else Icons.Rounded.Description,
                                contentDescription = null,
                                tint = ForgeColors.AccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = attachment.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ForgeColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatByteCount(attachment.sizeBytes),
                                    fontSize = 11.sp,
                                    color = ForgeColors.TextSecondary
                                )
                            }
                            IconButton(onClick = onSave) {
                                Icon(Icons.Rounded.Download, stringResource(R.string.action_save), tint = ForgeColors.AccentEmerald)
                            }
                            IconButton(onClick = { openLocalFileExternally(context, file, attachment.mimeType, attachment.name, onError) }) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.viewer_open_external), tint = ForgeColors.TextPrimary)
                            }
                            IconButton(onClick = onShare) {
                                Icon(Icons.Rounded.Share, stringResource(R.string.action_share), tint = ForgeColors.AccentBlue)
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.action_close), tint = ForgeColors.TextSecondary)
                            }
                        }
                    }
                    HorizontalDivider(color = ForgeColors.BorderSubtle)
                    if (isPdf && file.exists()) {
                        PdfInAppPane(
                            pdfFile = file,
                            extractedText = loadedText.orEmpty(),
                            onOpenExternal = { openLocalFileExternally(context, file, attachment.mimeType, attachment.name, onError) },
                            onCopyText = { txt ->
                                copyToClipboard(context, attachment.name, txt)
                                onError(copiedMsg)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        PlainDocumentReader(
                            text = loadedText.orEmpty(),
                            isMarkdown = isMarkdown,
                            modifier = Modifier.weight(1f),
                            onCopyCode = { code ->
                                copyToClipboard(context, "Code", code)
                                onError(copiedMsg)
                            },
                            onOpenExternal = { openLocalFileExternally(context, file, attachment.mimeType, attachment.name, onError) }
                        )
                    }
                }
            }
        }
    }
}
