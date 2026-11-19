package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forgemind.app.R
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeType
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Stable
class ChatReadingState(val listState: LazyListState) {
    var follow by mutableStateOf(true)
    var programmatic by mutableStateOf(false)
    var initialized by mutableStateOf(false)
    var count by mutableIntStateOf(0)
    val atBottom: Boolean get() = !listState.canScrollForward
    suspend fun jump(index: Int) {
        programmatic = true
        try { listState.scrollToItem(index.coerceIn(0, count)) }
        finally { programmatic = false }
    }
    suspend fun bottom() { follow = true; jump(count) }
}

@Composable
fun rememberChatReadingState(
    sessionId: String?,
    messages: List<ChatMessageEntity>,
    running: Boolean,
    autoFollow: Boolean,
    pinStart: Boolean
): ChatReadingState {
    val state = remember(sessionId) { ChatReadingState(LazyListState()) }
    state.count = messages.size
    val last = messages.lastOrNull()
    LaunchedEffect(state, last?.id) {
        if (last == null) return@LaunchedEffect
        if (!state.initialized) { state.initialized = true; state.bottom() }
        else if (last.role == "USER") state.bottom()
        else if (running && pinStart && last.role == "ASSISTANT") {
            state.follow = false
            state.jump(messages.lastIndex)
        } else if (autoFollow && state.follow) state.bottom()
    }
    LaunchedEffect(state, last?.content?.length, last?.thinking?.length, last?.toolCardsJson?.length) {
        if (autoFollow && state.follow && !state.listState.isScrollInProgress && state.initialized) state.bottom()
    }
    LaunchedEffect(state) {
        snapshotFlow { state.listState.isScrollInProgress to state.listState.canScrollForward }
            .distinctUntilChanged().collect { (scrolling, canDown) ->
                if (scrolling && !state.programmatic) state.follow = !canDown
            }
    }
    return state
}

@Composable
fun AnswerStartSpacer(state: ChatReadingState, enabled: Boolean) {
    val height by remember(state, enabled) {
        derivedStateOf {
            if (!enabled) 1 else {
                val layout = state.listState.layoutInfo
                val lastHeight = layout.visibleItemsInfo.firstOrNull { it.index == state.count - 1 }?.size ?: 0
                (layout.viewportSize.height - lastHeight - 24).coerceAtLeast(1)
            }
        }
    }
    Spacer(Modifier.height(with(LocalDensity.current) { height.toDp() }))
}

@Composable
fun ChatReadingControls(
    state: ChatReadingState,
    autoFollow: Boolean,
    pinStart: Boolean,
    onSettings: (Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(false) }
    val compactPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = ForgeColors.SurfaceElevated.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, ForgeColors.BorderSubtle)
    ) {
        Row(
            modifier = Modifier.height(32.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { scope.launch { state.follow = false; state.jump(0) } },
                contentPadding = compactPadding,
                modifier = Modifier.height(30.dp)
            ) { Text("⇈", color = ForgeColors.TextSecondary, fontSize = ForgeType.Meta) }
            TextButton(
                onClick = { scope.launch { state.follow = false; state.jump(state.listState.firstVisibleItemIndex - 1) } },
                contentPadding = compactPadding,
                modifier = Modifier.height(30.dp)
            ) { Text("↑", color = ForgeColors.TextSecondary, fontSize = ForgeType.Meta) }
            TextButton(
                onClick = { scope.launch { state.follow = false; state.jump(state.listState.firstVisibleItemIndex + 1) } },
                contentPadding = compactPadding,
                modifier = Modifier.height(30.dp)
            ) { Text("↓", color = ForgeColors.TextSecondary, fontSize = ForgeType.Meta) }
            TextButton(
                onClick = { settings = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp)
            ) { Text(stringResource(R.string.chat_reading_btn), color = ForgeColors.AccentText, fontSize = ForgeType.Caption, fontWeight = FontWeight.Medium) }
        }
    }
    if (settings) {
        AlertDialog(
            onDismissRequest = { settings = false },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.chat_reading_dialog_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoFollow, onCheckedChange = { onSettings(it, pinStart) })
                        Text(stringResource(R.string.settings_reading_autofollow), fontSize = ForgeType.Body)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = pinStart, onCheckedChange = { onSettings(autoFollow, it) })
                        Text(stringResource(R.string.settings_reading_pin_start), fontSize = ForgeType.Body)
                    }
                    Text(
                        stringResource(R.string.chat_reading_hint),
                        fontSize = ForgeType.Caption,
                        color = ForgeColors.TextSecondary
                    )
                }
            },
            confirmButton = { TextButton(onClick = { settings = false }) { Text(stringResource(R.string.action_close)) } }
        )
    }
}
