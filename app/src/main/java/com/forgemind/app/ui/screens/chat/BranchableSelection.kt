package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.CompletableDeferred

private class SelectionMenuRequest(val data: TextContextMenuDataProvider,
    val closed: CompletableDeferred<Unit> = CompletableDeferred()) : TextContextMenuSession {
    override fun close() { closed.complete(Unit) }
}

private class SelectionMenuProvider : TextContextMenuProvider {
    var request by mutableStateOf<SelectionMenuRequest?>(null)
    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val next=SelectionMenuRequest(dataProvider)
        request?.close();request=next
        try { next.closed.await() } finally { if(request===next)request=null }
    }
}

/** The branch action reads only the selection copied by the user's own click. */
@Suppress("DEPRECATION")
@Composable
fun BranchableSelection(onBranch: (String) -> Unit, onQuote: (String) -> Unit = {}, content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val branch by rememberUpdatedState(onBranch)
    val quote by rememberUpdatedState(onQuote)
    var afterCopy by remember { mutableStateOf<((String) -> Unit)?>(null) }
    // SelectionContainer copies asynchronously in Compose 1.9. Capture only the new clip entry
    // produced by the user's explicit Branch action; never read the existing clipboard.
    val selectionClipboard=remember(clipboard) { object:Clipboard {
        override val nativeClipboard get()=clipboard.nativeClipboard
        override suspend fun getClipEntry()=clipboard.getClipEntry()
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            val action = afterCopy
            afterCopy = null
            clipboard.setClipEntry(clipEntry)
            if(action!=null && clipEntry!=null && clipEntry.clipData.itemCount>0) {
                val text=clipEntry.clipData.getItemAt(0).text?.toString().orEmpty()
                if(text.isNotBlank())action(text)
            }
        }
    } }
    val modern=remember { SelectionMenuProvider() }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(modern) { onDispose { modern.request?.close() } }
    val toolbar = remember(clipboard) { object : TextToolbar {
        var rect by mutableStateOf(Rect.Zero)
        var copy by mutableStateOf<(() -> Unit)?>(null)
        var selectAll by mutableStateOf<(() -> Unit)?>(null)
        override var status by mutableStateOf(TextToolbarStatus.Hidden)
        override fun hide() { status = TextToolbarStatus.Hidden }
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            this.rect = rect; copy = onCopyRequested; selectAll = onSelectAllRequested
            status = TextToolbarStatus.Shown
        }
    } }
    Box(Modifier.onGloballyPositioned { coordinates=it }) {
    CompositionLocalProvider(LocalTextToolbar provides toolbar,LocalClipboard provides selectionClipboard,
        LocalTextContextMenuToolbarProvider provides modern,LocalTextContextMenuDropdownProvider provides modern) {
        SelectionContainer(content = content)
    }
    val request=modern.request
    val items=request?.data?.data()?.components?.filterIsInstance<TextContextMenuItem>().orEmpty()
    val copyItem=items.firstOrNull {it.key==TextContextMenuKeys.CopyKey}
    val allItem=items.firstOrNull {it.key==TextContextMenuKeys.SelectAllKey}
    val copyAction:(()->Unit)? = if(request!=null)copyItem?.let { item -> { item.onClick.invoke(request) } } else toolbar.copy
    val allAction:(()->Unit)? = if(request!=null)allItem?.let { item -> { item.onClick.invoke(request) } } else toolbar.selectAll
    val anchor=coordinates
    val bounds=if(request!=null && anchor?.isAttached==true)
        request.data.contentBounds(anchor).translate(anchor.localToWindow(Offset.Zero)) else toolbar.rect
    fun hide() {request?.close();toolbar.hide()}
    if (request!=null || toolbar.status == TextToolbarStatus.Shown) Popup(popupPositionProvider = object: PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
            layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
                bounds.left.toInt().coerceIn(0,(windowSize.width-popupContentSize.width).coerceAtLeast(0)),
                (bounds.top.toInt()-popupContentSize.height).coerceIn(0,(windowSize.height-popupContentSize.height).coerceAtLeast(0)))
    }, onDismissRequest = ::hide) {
        Surface { Row {
            TextButton(enabled=copyAction!=null,onClick = { afterCopy=null;copyAction?.invoke();hide() }) { Text("Копировать") }
            TextButton(enabled = copyAction != null, onClick = {
                afterCopy={ branch(it) }
                copyAction?.invoke()
                hide()
            }) { Text("Ветка") }
            TextButton(enabled=copyAction!=null, onClick = { afterCopy={ quote(it) }; copyAction?.invoke(); hide() }) { Text("Цитата") }
            TextButton(enabled=allAction!=null,onClick = { allAction?.invoke() }) { Text("Всё") }
        } }
    }
    }
}
