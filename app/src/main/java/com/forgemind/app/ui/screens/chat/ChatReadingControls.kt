package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.forgemind.app.data.db.ChatMessageEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

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
fun rememberChatReadingState(sessionId: String?, messages: List<ChatMessageEntity>, running: Boolean,
    autoFollow: Boolean, pinStart: Boolean): ChatReadingState {
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
    val height by remember(state, enabled) { derivedStateOf {
        if (!enabled) 1 else {
            val layout = state.listState.layoutInfo
            val lastHeight = layout.visibleItemsInfo.firstOrNull { it.index == state.count - 1 }?.size ?: 0
            (layout.viewportSize.height - lastHeight - 24).coerceAtLeast(1)
        }
    } }
    Spacer(Modifier.height(with(LocalDensity.current) { height.toDp() }))
}

@Composable
fun ChatReadingControls(state: ChatReadingState, autoFollow: Boolean, pinStart: Boolean,
    onSettings: (Boolean, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(false) }
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
        Row {
            TextButton(onClick = { scope.launch { state.follow = false; state.jump(0) } }) { Text("⇈") }
            TextButton(onClick = { scope.launch { state.follow = false; state.jump(state.listState.firstVisibleItemIndex - 1) } }) { Text("↑") }
            TextButton(onClick = { scope.launch { state.follow = false; state.jump(state.listState.firstVisibleItemIndex + 1) } }) { Text("↓") }
            TextButton(onClick = { settings = true }) { Text("Чтение") }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Чтение ответа") },
        text = { Column {
            Row { Checkbox(autoFollow, { onSettings(it, pinStart) }); Text("Следовать за ответом у низа ленты") }
            Row { Checkbox(pinStart, { onSettings(autoFollow, it) }); Text("Закреплять начало нового ответа") }
            Text("Ручная прокрутка вверх отключает следование. Кнопка перехода вниз включает его снова.")
        } }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Закрыть") } })
}
