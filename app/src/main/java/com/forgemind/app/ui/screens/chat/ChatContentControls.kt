package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.text.TextLayoutResult
import com.forgemind.app.data.model.ChatContentSearch
import com.forgemind.app.data.model.ChatSearchHit
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeType
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.forgemind.app.R

val LocalChatSearchQuery = staticCompositionLocalOf { "" }
val LocalChatSearchFocus = staticCompositionLocalOf<ChatSearchHit?> { null }

/** Raw source during focused search makes offsets exact even in code, tables and links. */
@Composable
fun SearchFocusedText(text: String, offset: Int, modifier: Modifier = Modifier) {
    val requester = remember { BringIntoViewRequester() }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(text, offset, layout?.size) {
        val measured = layout
        if (measured != null && text.isNotEmpty()) requester.bringIntoView(measured.getBoundingBox(offset.coerceIn(0, text.lastIndex)))
    }
    Text(highlightChatText(AnnotatedString(text)), modifier = modifier.bringIntoViewRequester(requester),
        style = MaterialTheme.typography.bodyMedium, onTextLayout = { layout = it })
}

@Composable
fun highlightChatText(text: AnnotatedString): AnnotatedString {
    val query = LocalChatSearchQuery.current
    if (query.isBlank()) return text
    return remember(text, query) {
        val ranges = ChatContentSearch.ranges(text.text, query)
        if (ranges.isEmpty()) text else AnnotatedString.Builder(text).apply {
            ranges.forEach {
                addStyle(SpanStyle(background = Color(0xFFFFD166), color = Color(0xFF171717)), it.first, it.last + 1)
            }
        }.toAnnotatedString()
    }
}

@Composable
fun ChatSearchControls(query: String, onQuery: (String) -> Unit, hits: List<ChatSearchHit>, target: ChatSearchHit?,
    onTarget: (ChatSearchHit?) -> Unit, onClose: () -> Unit) {
    val index = hits.indexOfFirst { target != null && it.messageId == target.messageId && it.offset == target.offset && it.inThinking == target.inThinking }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OutlinedTextField(query, onQuery, singleLine = true, label = { Text(stringResource(R.string.chat_search_in_chat_label)) },
            modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.chat_search_close)) } })
        Row {
            val suffix = if (hits.size == 1000) stringResource(R.string.chat_search_first_1000) else ""
            Text(
                if (hits.isEmpty()) stringResource(R.string.chat_search_no_matches)
                else stringResource(R.string.chat_search_match_fmt, index.coerceAtLeast(0) + 1, hits.size, suffix),
                Modifier.weight(1f)
            )
            TextButton(enabled = hits.isNotEmpty(), onClick = { onTarget(hits[(index - 1).mod(hits.size)]) }) { Text("↑") }
            TextButton(enabled = hits.isNotEmpty(), onClick = { onTarget(hits[(index + 1).mod(hits.size)]) }) { Text("↓") }
        }
        hits.getOrNull(index)?.let { hit ->
            CompositionLocalProvider(LocalChatSearchQuery provides query) {
                Text(highlightChatText(AnnotatedString(hit.excerpt)), maxLines = 3, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ContextExclusionDialog(vm: MainViewModel) {
    val request by vm.exclusionRequest.collectAsState()
    val running by vm.isAgentRunning.collectAsState()
    request?.let { message ->
        var wholeTurn by remember(message.id) { mutableStateOf(false) }
        val restoring = message.excludedFromContext
        AlertDialog(
            onDismissRequest = { vm.exclusionRequest.value = null },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = {
                Text(
                    stringResource(if (restoring) R.string.context_excl_restore_title else R.string.context_excl_exclude_title),
                    fontSize = ForgeType.Title,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.context_excl_body),
                        fontSize = ForgeType.Body,
                        color = ForgeColors.TextSecondary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(wholeTurn, { wholeTurn = it })
                        Text(stringResource(R.string.context_excl_whole_turn), fontSize = ForgeType.Body)
                    }
                    if (running) Text(stringResource(R.string.context_excl_stop_first), color = ForgeColors.AccentAmber, fontSize = ForgeType.Meta)
                }
            },
            confirmButton = {
                TextButton(enabled = !running, onClick = {
                    vm.setMessageExclusion(message, wholeTurn, !restoring)
                }) { Text(stringResource(if (restoring) R.string.context_excl_restore_btn else R.string.context_excl_exclude_btn)) }
            },
            dismissButton = { TextButton(onClick = { vm.exclusionRequest.value = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
