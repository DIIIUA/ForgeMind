package com.forgemind.app.ui.screens.files

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.SshProfileEntity
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.model.WorkspaceFileItem
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.OpenEditorTab
import com.forgemind.app.ui.components.formatBytes
import com.forgemind.app.ui.screens.terminal.TerminalScreen
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor

@Composable
fun FilesScreen(
    vm: MainViewModel,
    onUploadFileClick: () -> Unit,
    onPickSafFolderClick: () -> Unit
) {
    val env by vm.explorerEnv.collectAsState()
    val path by vm.explorerPath.collectAsState()
    val items by vm.explorerItems.collectAsState()
    val error by vm.explorerError.collectAsState()
    val loading by vm.explorerLoading.collectAsState()
    val editorTabs by vm.editorTabs.collectAsState()
    val activeTabId by vm.activeEditorTabId.collectAsState()
    val safTreeUri by vm.safTreeUri.collectAsState()
    val explorerSshId by vm.explorerSshProfileId.collectAsState()
    val sshProfiles by vm.sshProfiles.collectAsState()
    val enabledSsh = remember(sshProfiles) { sshProfiles.filter { it.enabled } }
    val terminalTabs by vm.terminalTabs.collectAsState()
    var showTerminal by remember { mutableStateOf(false) }

    val activeTab = remember(editorTabs, activeTabId) {
        editorTabs.firstOrNull { it.id == activeTabId }
    }

    var newItemDialog by remember { mutableStateOf<Boolean?>(null) } // true=dir, false=file

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeColors.BgDeep)
    ) {
        // Environment switcher
        Surface(color = ForgeColors.SurfaceDark) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WorkspaceEnvType.entries.forEach { e ->
                        FilterChip(
                            selected = env == e,
                            onClick = {
                                if (e == WorkspaceEnvType.SAF && safTreeUri.isNullOrBlank()) {
                                    onPickSafFolderClick()
                                } else {
                                    vm.selectExplorerEnv(e)
                                }
                            },
                            label = { Text(e.label, fontSize = 12.sp) }
                        )
                    }
                    if (env == WorkspaceEnvType.SAF) {
                        val safLabel = remember(safTreeUri) {
                            if (safTreeUri.isNullOrBlank()) "Выбрать папку"
                            else WorkspaceManager.formatSafTreeLabel(safTreeUri).removePrefix("Память телефона / ")
                        }
                        OutlinedButton(
                            onClick = onPickSafFolderClick,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(safLabel, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                if (env == WorkspaceEnvType.TERMUX_SSH && enabledSsh.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        enabledSsh.forEach { prof ->
                            val isSel = explorerSshId == prof.id ||
                                (explorerSshId == null && prof == enabledSsh.firstOrNull())
                            FilterChip(
                                selected = isSel,
                                onClick = { vm.selectExplorerSshProfile(prof.id) },
                                label = {
                                    Text(
                                        "${prof.name} (${prof.host}:${prof.port})",
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }

                // Breadcrumb + action bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = { vm.navigateExplorerUp() }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Вверх", tint = ForgeColors.TextPrimary)
                        }
                        Text(
                            text = if (path.isBlank()) "/ (корень)" else path,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                            color = ForgeColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row {
                        IconButton(onClick = onUploadFileClick, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.UploadFile, "Загрузить файл с телефона", tint = ForgeColors.AccentEmerald)
                        }
                        IconButton(onClick = { newItemDialog = false }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.NoteAdd, "Новый файл", tint = ForgeColors.AccentBlue)
                        }
                        IconButton(onClick = { newItemDialog = true }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.CreateNewFolder, "Новая папка", tint = ForgeColors.TextPrimary)
                        }
                        IconButton(onClick = { vm.refreshExplorer() }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.Refresh, "Обновить", tint = ForgeColors.TextSecondary)
                        }
                    }
                }
            }
        }

        // Tabs Bar: Explorer, Terminal, and Open Code Editor Tabs
        Surface(color = ForgeColors.SurfaceElevated) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isExplorerSelected = !showTerminal && activeTabId == null
                Surface(
                    color = if (isExplorerSelected) ForgeColors.AccentBlue.copy(alpha = 0.25f) else ForgeColors.SurfaceDark,
                    shape = RoundedCornerShape(6.dp),
                    border = if (isExplorerSelected) androidx.compose.foundation.BorderStroke(1.dp, ForgeColors.AccentBlue) else null,
                    modifier = Modifier.clickable {
                        showTerminal = false
                        vm.selectEditorTab(null)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Folder,
                            null,
                            modifier = Modifier.size(13.dp),
                            tint = if (isExplorerSelected) ForgeColors.AccentBlue else ForgeColors.TextSecondary
                        )
                        Text(
                            "Файлы",
                            fontSize = 12.sp,
                            color = if (isExplorerSelected) ForgeColors.AccentBlue else ForgeColors.TextPrimary
                        )
                    }
                }

                Surface(
                    color = if (showTerminal) ForgeColors.AccentEmerald.copy(alpha = 0.25f) else ForgeColors.SurfaceDark,
                    shape = RoundedCornerShape(6.dp),
                    border = if (showTerminal) androidx.compose.foundation.BorderStroke(1.dp, ForgeColors.AccentEmerald) else null,
                    modifier = Modifier.clickable {
                        showTerminal = true
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Terminal,
                            null,
                            modifier = Modifier.size(13.dp),
                            tint = if (showTerminal) ForgeColors.AccentEmerald else ForgeColors.TextSecondary
                        )
                        Text(
                            if (terminalTabs.isNotEmpty()) "Терминал (${terminalTabs.size})" else "Терминал",
                            fontSize = 12.sp,
                            color = if (showTerminal) ForgeColors.AccentEmerald else ForgeColors.TextPrimary
                        )
                    }
                }

                editorTabs.forEach { tab ->
                    val selected = !showTerminal && tab.id == activeTabId
                    Surface(
                        color = if (selected) ForgeColors.AccentBlue.copy(alpha = 0.25f) else ForgeColors.SurfaceDark,
                        shape = RoundedCornerShape(6.dp),
                        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, ForgeColors.AccentBlue) else null,
                        modifier = Modifier.clickable {
                            showTerminal = false
                            vm.selectEditorTab(tab.id)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (tab.isDirty) "* ${tab.title}" else tab.title,
                                fontSize = 12.sp,
                                color = if (tab.isDirty) ForgeColors.AccentAmber else if (selected) ForgeColors.AccentBlue else ForgeColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Rounded.Close,
                                "Закрыть вкладку",
                                tint = ForgeColors.TextSecondary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { vm.closeEditorTab(tab.id) }
                            )
                        }
                    }
                }
            }
        }

        // Body: Terminal, active Sora CodeEditor tab, or file explorer list
        when {
            showTerminal -> {
                TerminalScreen(vm)
            }
            activeTab != null -> {
                SoraEditorPane(
                    tab = activeTab,
                    onContentChanged = { vm.updateEditorContent(activeTab.id, it) },
                    onSave = { vm.saveActiveEditorTab() }
                )
            }
            else -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        !error.isNullOrBlank() -> Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(error!!, color = ForgeColors.DangerRed, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { vm.refreshExplorer() }) { Text("Повторить") }
                        }
                        items.isEmpty() -> Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Каталог пуст", color = ForgeColors.TextSecondary)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = onUploadFileClick) {
                                Icon(Icons.Rounded.UploadFile, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Загрузить файл с телефона")
                            }
                        }
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(items, key = { "${it.envType.id}:${it.sshServerId ?: ""}:${it.path}" }) { item ->
                                FileExplorerRow(
                                    item = item,
                                    sshProfiles = enabledSsh,
                                    onOpen = {
                                        showTerminal = false
                                        vm.openFileInEditor(item)
                                    },
                                    onAttachToChat = { vm.addOutgoingAttachmentFromWorkspace(item) },
                                    onTransfer = { target, targetSsh -> vm.transferExplorerItem(item, target, targetSsh) },
                                    onDelete = { vm.deleteExplorerItem(item) }
                                )
                                HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }

    newItemDialog?.let { isDir ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newItemDialog = null },
            title = { Text(if (isDir) "Новая папка" else "Новый файл") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(if (isDir) "folder_name" else "script.py") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.createFileOrFolder(name, isDir)
                    newItemDialog = null
                }) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { newItemDialog = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun FileExplorerRow(
    item: WorkspaceFileItem,
    sshProfiles: List<SshProfileEntity>,
    onOpen: () -> Unit,
    onAttachToChat: () -> Unit,
    onTransfer: (WorkspaceEnvType, String?) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
            contentDescription = null,
            tint = if (item.isDirectory) ForgeColors.AccentAmber else ForgeColors.AccentBlue,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontSize = 14.sp,
                fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                color = ForgeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.isDirectory) {
                Text(
                    text = formatBytes(item.sizeBytes),
                    fontSize = 11.sp,
                    color = ForgeColors.TextSecondary
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.MoreVert, null, tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!item.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("Прикрепить в чат AI") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Send, null) },
                        onClick = {
                            menuOpen = false
                            onAttachToChat()
                        }
                    )
                    listOf(WorkspaceEnvType.INTERNAL, WorkspaceEnvType.SAF)
                        .filter { it != item.envType }
                        .forEach { target ->
                            DropdownMenuItem(
                                text = { Text("Копировать в ${target.label}") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) },
                                onClick = {
                                    menuOpen = false
                                    onTransfer(target, null)
                                }
                            )
                        }
                    sshProfiles
                        .filter { !(item.envType == WorkspaceEnvType.TERMUX_SSH && item.sshServerId == it.id) }
                        .forEach { prof ->
                            DropdownMenuItem(
                                text = { Text("Копировать в SSH: ${prof.name}") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) },
                                onClick = {
                                    menuOpen = false
                                    onTransfer(WorkspaceEnvType.TERMUX_SSH, prof.id)
                                }
                            )
                        }
                }
                DropdownMenuItem(
                    text = { Text("Удалить", color = ForgeColors.DangerRed) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = ForgeColors.DangerRed) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun SoraEditorPane(
    tab: OpenEditorTab,
    onContentChanged: (String) -> Unit,
    onSave: () -> Unit
) {
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ForgeColors.SurfaceDark)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = tab.path,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                color = ForgeColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSearch = !showSearch }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Search, "Поиск в файле", tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
                Button(
                    onClick = onSave,
                    enabled = tab.isDirty,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Rounded.Save, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Сохранить", fontSize = 11.5.sp)
                }
            }
        }

        if (showSearch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeColors.SurfaceElevated)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        runCatching {
                            if (it.isNotEmpty()) editorRef?.searcher?.search(it, io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions(true, false))
                            else editorRef?.searcher?.stopSearch()
                        }
                    },
                    placeholder = { Text("Найти в файле...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { runCatching { editorRef?.searcher?.gotoNext() } }) {
                    Text("Далее", fontSize = 11.5.sp)
                }
            }
        }

        val boundTabId = remember { mutableStateOf("") }
        val boundVersion = remember { mutableLongStateOf(0L) }
        val suppressing = remember { mutableStateOf(false) }

        // Quick code symbols bar for mobile editing
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ForgeColors.SurfaceDark)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            listOf("{", "}", "(", ")", "[", "]", ";", "=", ":", "\"", "'", "<", ">", "/", "\\", "$", "Tab").forEach { sym ->
                Surface(
                    color = ForgeColors.SurfaceElevated,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.clickable {
                        editorRef?.let { ed ->
                            if (sym == "Tab") ed.insertText("    ", 4)
                            else ed.insertText(sym, sym.length)
                        }
                    }
                ) {
                    Text(
                        sym,
                        fontFamily = MonoFont,
                        fontSize = 12.sp,
                        color = ForgeColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { ctx ->
                CodeEditor(ctx).apply {
                    typefaceText = Typeface.MONOSPACE
                    setTextSize(13f)
                    isWordwrap = false
                    runCatching {
                        setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()))
                    }
                    subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                        if (!suppressing.value) {
                            onContentChanged(text.toString())
                        }
                    }
                    editorRef = this
                }
            },
            update = { editor ->
                if (boundTabId.value != tab.id || boundVersion.longValue != tab.version) {
                    suppressing.value = true
                    boundTabId.value = tab.id
                    boundVersion.longValue = tab.version
                    editor.setText(tab.content)
                    applyLanguageForFilename(editor, tab.title)
                    suppressing.value = false
                }
            }
        )
    }
}

private fun applyLanguageForFilename(editor: CodeEditor, filename: String) {
    val ext = filename.substringAfterLast('.', "").lowercase()
    val scope = when (ext) {
        "kt", "kts" -> "source.kotlin"
        "java" -> "source.java"
        "py" -> "source.python"
        "js", "mjs", "cjs", "jsx" -> "source.js"
        "ts", "tsx" -> "source.ts"
        "json" -> "source.json"
        "html", "htm" -> "text.html.basic"
        "css" -> "source.css"
        "xml", "svg", "plist" -> "text.xml"
        "md", "markdown" -> "text.html.markdown"
        "lua" -> "source.lua"
        else -> null
    }
    if (scope != null) {
        runCatching {
            val lang = TextMateLanguage.create(scope, true)
            editor.setEditorLanguage(lang)
            editor.setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()))
        }
    }
}
