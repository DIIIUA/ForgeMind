package com.forgemind.app.ui.screens.files

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forgemind.app.R
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.SshProfileEntity
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.model.WorkspaceFileItem
import com.forgemind.app.ui.EditorTabKind
import com.forgemind.app.ui.FileClipboardEntry
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.OpenEditorTab
import com.forgemind.app.ui.components.ForgeActionPill
import com.forgemind.app.ui.components.ForgeIconButton
import com.forgemind.app.ui.components.InAppFileTabViewer
import com.forgemind.app.ui.components.PlainDocumentReader
import com.forgemind.app.ui.components.copyToClipboard
import com.forgemind.app.ui.formatByteCount
import com.forgemind.app.ui.formatSafTreeLabelLocalized
import com.forgemind.app.ui.localizedLabel
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor

private fun needsAllFilesAccessPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

private fun launchAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val perAppIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(perAppIntent) }.onFailure {
            val allAppsIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(allAppsIntent) }
        }
    }
}

private data class NewExplorerItemRequest(
    val isDir: Boolean,
    val inSecondPane: Boolean
)

@Composable
fun FilesScreen(
    vm: MainViewModel,
    onUploadFileClick: () -> Unit,
    onPickSafFolderClick: () -> Unit
) {
    val context = LocalContext.current
    val env by vm.explorerEnv.collectAsState()
    val path by vm.explorerPath.collectAsState()
    val items by vm.explorerItems.collectAsState()
    val error by vm.explorerError.collectAsState()
    val loading by vm.explorerLoading.collectAsState()

    val splitEnabled by vm.splitExplorerEnabled.collectAsState()
    val secondEnv by vm.secondExplorerEnv.collectAsState()
    val secondPath by vm.secondExplorerPath.collectAsState()
    val secondItems by vm.secondExplorerItems.collectAsState()
    val secondError by vm.secondExplorerError.collectAsState()
    val secondLoading by vm.secondExplorerLoading.collectAsState()
    val secondSshId by vm.secondExplorerSshProfileId.collectAsState()
    val fileClipboard by vm.fileClipboard.collectAsState()

    val editorTabs by vm.editorTabs.collectAsState()
    val activeTabId by vm.activeEditorTabId.collectAsState()
    val safTreeUri by vm.safTreeUri.collectAsState()
    val explorerSshId by vm.explorerSshProfileId.collectAsState()
    val sshProfiles by vm.sshProfiles.collectAsState()
    val enabledSsh = remember(sshProfiles) { sshProfiles.filter { it.enabled } }

    val activeTab = remember(editorTabs, activeTabId) {
        editorTabs.firstOrNull { it.id == activeTabId }
    }

    var missingStoragePerm by remember { mutableStateOf(needsAllFilesAccessPermission()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowMissing = needsAllFilesAccessPermission()
                val justGranted = missingStoragePerm && !nowMissing
                missingStoragePerm = nowMissing
                if (!nowMissing) {
                    if (vm.explorerEnv.value == WorkspaceEnvType.DEVICE_STORAGE || justGranted) {
                        vm.refreshExplorer()
                    }
                    if (vm.splitExplorerEnabled.value &&
                        (vm.secondExplorerEnv.value == WorkspaceEnvType.DEVICE_STORAGE || justGranted)
                    ) {
                        vm.refreshSecondExplorer()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var newItemDialog by remember { mutableStateOf<NewExplorerItemRequest?>(null) }
    var folderInfoEnv by remember { mutableStateOf<WorkspaceEnvType?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeColors.BgDeep)
    ) {
        // Open Editor Tabs Bar (shown when files are opened in editor)
        AnimatedVisibility(
            visible = editorTabs.isNotEmpty(),
            enter = expandVertically(tween(180)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
        ) {
            Surface(color = ForgeColors.SurfaceElevated) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
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
                        val isExplorerSelected = activeTabId == null
                        Surface(
                            color = if (isExplorerSelected) ForgeColors.AccentBlue.copy(alpha = 0.25f) else ForgeColors.SurfaceDark,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isExplorerSelected) ForgeColors.AccentBlue else ForgeColors.BorderSubtle
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { vm.selectEditorTab(null) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
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
                                    stringResource(R.string.files_title),
                                    fontSize = 12.sp,
                                    color = if (isExplorerSelected) ForgeColors.AccentBlue else ForgeColors.TextPrimary
                                )
                            }
                        }

                        editorTabs.forEach { tab ->
                            val selected = tab.id == activeTabId
                            Surface(
                                color = if (selected) ForgeColors.AccentBlue.copy(alpha = 0.25f) else ForgeColors.SurfaceDark,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) ForgeColors.AccentBlue else ForgeColors.BorderSubtle
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.selectEditorTab(tab.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
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
                                        stringResource(R.string.files_close_tab),
                                        tint = ForgeColors.TextSecondary,
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clip(CircleShape)
                                            .clickable { vm.closeEditorTab(tab.id) }
                                    )
                                }
                            }
                        }
                    }

                    if (activeTab != null) {
                        ForgeActionPill(
                            text = "◫ " + stringResource(R.string.files_split_view_btn),
                            active = splitEnabled,
                            minHeight = 30.dp,
                            horizontalPadding = 9.dp,
                            verticalPadding = 4.dp,
                            onClick = {
                                vm.selectEditorTab(null)
                                if (!splitEnabled) vm.setSplitExplorerEnabled(true)
                            }
                        )
                    }
                }
            }
        }

        // Body: active viewer / Sora CodeEditor tab OR single/dual-pane file explorer
        if (activeTab != null) {
            when (activeTab.kind) {
                EditorTabKind.IMAGE,
                EditorTabKind.PDF,
                EditorTabKind.DOCUMENT,
                EditorTabKind.ARCHIVE,
                EditorTabKind.BINARY -> {
                    InAppFileTabViewer(
                        tab = activeTab,
                        onAttachToChat = { vm.attachTabToChat(activeTab) },
                        onCopyText = { vm.showToast(it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
                else -> {
                    SoraEditorPane(
                        tab = activeTab,
                        onContentChanged = { vm.updateEditorContent(activeTab.id, it) },
                        onSave = { vm.saveActiveEditorTab() },
                        onCopyCode = { code ->
                            copyToClipboard(context, "Code", code)
                            vm.showToast(context.getString(R.string.code_copied))
                        }
                    )
                }
            }
        } else if (splitEnabled) {
            ExplorerPane(
                isSecondPane = false,
                paneBadge = stringResource(R.string.files_split_pane_top),
                paneBadgeColor = ForgeColors.AccentBlue,
                splitEnabled = true,
                onToggleSplit = { vm.toggleSplitExplorer() },
                env = env,
                path = path,
                items = items,
                loading = loading,
                error = error,
                safTreeUri = safTreeUri,
                selectedSshId = explorerSshId,
                enabledSsh = enabledSsh,
                missingStoragePerm = missingStoragePerm,
                fileClipboard = fileClipboard,
                onGrantStoragePerm = { launchAllFilesAccessSettings(context) },
                onSelectEnv = { selected ->
                    missingStoragePerm = needsAllFilesAccessPermission()
                    if (selected == WorkspaceEnvType.SAF && safTreeUri.isNullOrBlank()) {
                        vm.prepareExplorerSafPick(forSecondPane = false)
                        onPickSafFolderClick()
                    } else {
                        vm.selectExplorerEnv(selected)
                    }
                },
                onPickSaf = {
                    vm.prepareExplorerSafPick(forSecondPane = false)
                    onPickSafFolderClick()
                },
                onShowInfo = { folderInfoEnv = env },
                onSelectSshProfile = { vm.selectExplorerSshProfile(it) },
                onNavigateUp = { vm.navigateExplorerUp() },
                onUploadClick = {
                    vm.prepareExplorerUpload(toSecondPane = false)
                    onUploadFileClick()
                },
                onCreateNew = { isDir ->
                    newItemDialog = NewExplorerItemRequest(isDir = isDir, inSecondPane = false)
                },
                onRefresh = {
                    missingStoragePerm = needsAllFilesAccessPermission()
                    vm.refreshExplorer()
                },
                onPasteClipboard = { vm.pasteClipboardToExplorer(toSecondPane = false) },
                onClearClipboard = { vm.clearFileClipboard() },
                onOpenItem = { vm.openFileInEditor(it, inSecondPane = false) },
                onAttachToChat = { vm.addOutgoingAttachmentFromWorkspace(it) },
                onCopyToClipboard = { vm.copyOrCutToClipboard(it, isCut = false) },
                onCutToClipboard = { vm.copyOrCutToClipboard(it, isCut = true) },
                onTransferToOtherPane = { item, move ->
                    vm.transferBetweenExplorerPanes(item, toSecondPane = true, move = move)
                },
                onTransferToEnv = { item, target, targetSsh ->
                    vm.transferExplorerItem(item, target, targetSsh)
                },
                onDeleteItem = { vm.deleteExplorerItem(it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            HorizontalDivider(
                thickness = 2.dp,
                color = ForgeColors.AccentBlue.copy(alpha = 0.45f)
            )

            ExplorerPane(
                isSecondPane = true,
                paneBadge = stringResource(R.string.files_split_pane_bottom),
                paneBadgeColor = ForgeColors.AccentEmerald,
                splitEnabled = true,
                onToggleSplit = null,
                env = secondEnv,
                path = secondPath,
                items = secondItems,
                loading = secondLoading,
                error = secondError,
                safTreeUri = safTreeUri,
                selectedSshId = secondSshId,
                enabledSsh = enabledSsh,
                missingStoragePerm = missingStoragePerm,
                fileClipboard = fileClipboard,
                onGrantStoragePerm = { launchAllFilesAccessSettings(context) },
                onSelectEnv = { selected ->
                    missingStoragePerm = needsAllFilesAccessPermission()
                    if (selected == WorkspaceEnvType.SAF && safTreeUri.isNullOrBlank()) {
                        vm.prepareExplorerSafPick(forSecondPane = true)
                        onPickSafFolderClick()
                    } else {
                        vm.selectSecondExplorerEnv(selected)
                    }
                },
                onPickSaf = {
                    vm.prepareExplorerSafPick(forSecondPane = true)
                    onPickSafFolderClick()
                },
                onShowInfo = { folderInfoEnv = secondEnv },
                onSelectSshProfile = { vm.selectSecondExplorerSshProfile(it) },
                onNavigateUp = { vm.navigateSecondExplorerUp() },
                onUploadClick = {
                    vm.prepareExplorerUpload(toSecondPane = true)
                    onUploadFileClick()
                },
                onCreateNew = { isDir ->
                    newItemDialog = NewExplorerItemRequest(isDir = isDir, inSecondPane = true)
                },
                onRefresh = {
                    missingStoragePerm = needsAllFilesAccessPermission()
                    vm.refreshSecondExplorer()
                },
                onPasteClipboard = { vm.pasteClipboardToExplorer(toSecondPane = true) },
                onClearClipboard = { vm.clearFileClipboard() },
                onOpenItem = { vm.openFileInEditor(it, inSecondPane = true) },
                onAttachToChat = { vm.addOutgoingAttachmentFromWorkspace(it) },
                onCopyToClipboard = { vm.copyOrCutToClipboard(it, isCut = false) },
                onCutToClipboard = { vm.copyOrCutToClipboard(it, isCut = true) },
                onTransferToOtherPane = { item, move ->
                    vm.transferBetweenExplorerPanes(item, toSecondPane = false, move = move)
                },
                onTransferToEnv = { item, target, targetSsh ->
                    vm.transferExplorerItem(item, target, targetSsh)
                },
                onDeleteItem = { vm.deleteExplorerItem(it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            ExplorerPane(
                isSecondPane = false,
                paneBadge = null,
                paneBadgeColor = ForgeColors.AccentBlue,
                splitEnabled = false,
                onToggleSplit = { vm.toggleSplitExplorer() },
                env = env,
                path = path,
                items = items,
                loading = loading,
                error = error,
                safTreeUri = safTreeUri,
                selectedSshId = explorerSshId,
                enabledSsh = enabledSsh,
                missingStoragePerm = missingStoragePerm,
                fileClipboard = fileClipboard,
                onGrantStoragePerm = { launchAllFilesAccessSettings(context) },
                onSelectEnv = { selected ->
                    missingStoragePerm = needsAllFilesAccessPermission()
                    if (selected == WorkspaceEnvType.SAF && safTreeUri.isNullOrBlank()) {
                        vm.prepareExplorerSafPick(forSecondPane = false)
                        onPickSafFolderClick()
                    } else {
                        vm.selectExplorerEnv(selected)
                    }
                },
                onPickSaf = {
                    vm.prepareExplorerSafPick(forSecondPane = false)
                    onPickSafFolderClick()
                },
                onShowInfo = { folderInfoEnv = env },
                onSelectSshProfile = { vm.selectExplorerSshProfile(it) },
                onNavigateUp = { vm.navigateExplorerUp() },
                onUploadClick = {
                    vm.prepareExplorerUpload(toSecondPane = false)
                    onUploadFileClick()
                },
                onCreateNew = { isDir ->
                    newItemDialog = NewExplorerItemRequest(isDir = isDir, inSecondPane = false)
                },
                onRefresh = {
                    missingStoragePerm = needsAllFilesAccessPermission()
                    vm.refreshExplorer()
                },
                onPasteClipboard = { vm.pasteClipboardToExplorer(toSecondPane = false) },
                onClearClipboard = { vm.clearFileClipboard() },
                onOpenItem = { vm.openFileInEditor(it, inSecondPane = false) },
                onAttachToChat = { vm.addOutgoingAttachmentFromWorkspace(it) },
                onCopyToClipboard = { vm.copyOrCutToClipboard(it, isCut = false) },
                onCutToClipboard = { vm.copyOrCutToClipboard(it, isCut = true) },
                onTransferToOtherPane = { item, move ->
                    vm.transferBetweenExplorerPanes(item, toSecondPane = true, move = move)
                },
                onTransferToEnv = { item, target, targetSsh ->
                    vm.transferExplorerItem(item, target, targetSsh)
                },
                onDeleteItem = { vm.deleteExplorerItem(it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }

    folderInfoEnv?.let { infoEnv ->
        val isSaf = infoEnv == WorkspaceEnvType.SAF
        val safLabel = formatSafTreeLabelLocalized(context, safTreeUri)
        val safReal = WorkspaceManager.safUriToDisplayPath(safTreeUri) ?: "-"
        val internalPath = "/data/data/${context.packageName}/files/workspaces"
        AlertDialog(
            onDismissRequest = { folderInfoEnv = null },
            title = {
                Text(
                    text = if (isSaf) stringResource(R.string.files_saf_info_title) else WorkspaceEnvType.INTERNAL.localizedLabel(context),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isSaf) {
                        stringResource(R.string.files_saf_info_body, safLabel, safReal)
                    } else {
                        stringResource(R.string.files_internal_info_body, internalPath)
                    },
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = ForgeColors.TextSecondary
                )
            },
            confirmButton = {
                Button(onClick = { folderInfoEnv = null }) {
                    Text(stringResource(R.string.action_understood))
                }
            }
        )
    }

    newItemDialog?.let { req ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newItemDialog = null },
            title = { Text(stringResource(if (req.isDir) R.string.files_new_folder else R.string.files_new_file)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(if (req.isDir) "folder_name" else "script.py") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.createFileOrFolder(name, req.isDir, inSecondPane = req.inSecondPane)
                    newItemDialog = null
                }) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(onClick = { newItemDialog = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun ExplorerPane(
    isSecondPane: Boolean,
    paneBadge: String?,
    paneBadgeColor: Color,
    splitEnabled: Boolean,
    onToggleSplit: (() -> Unit)?,
    env: WorkspaceEnvType,
    path: String,
    items: List<WorkspaceFileItem>,
    loading: Boolean,
    error: String?,
    safTreeUri: String?,
    selectedSshId: String?,
    enabledSsh: List<SshProfileEntity>,
    missingStoragePerm: Boolean,
    fileClipboard: FileClipboardEntry?,
    onGrantStoragePerm: () -> Unit,
    onSelectEnv: (WorkspaceEnvType) -> Unit,
    onPickSaf: () -> Unit,
    onShowInfo: () -> Unit,
    onSelectSshProfile: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onUploadClick: () -> Unit,
    onCreateNew: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onPasteClipboard: () -> Unit,
    onClearClipboard: () -> Unit,
    onOpenItem: (WorkspaceFileItem) -> Unit,
    onAttachToChat: (WorkspaceFileItem) -> Unit,
    onCopyToClipboard: (WorkspaceFileItem) -> Unit,
    onCutToClipboard: (WorkspaceFileItem) -> Unit,
    onTransferToOtherPane: (WorkspaceFileItem, Boolean) -> Unit,
    onTransferToEnv: (WorkspaceFileItem, WorkspaceEnvType, String?) -> Unit,
    onDeleteItem: (WorkspaceFileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val showPermCard = env == WorkspaceEnvType.DEVICE_STORAGE && missingStoragePerm

    Column(modifier = modifier) {
        Surface(color = ForgeColors.SurfaceDark) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = if (splitEnabled) 4.dp else 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        if (paneBadge != null) {
                            Surface(
                                color = paneBadgeColor.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(7.dp),
                                border = BorderStroke(1.dp, paneBadgeColor.copy(alpha = 0.65f))
                            ) {
                                Text(
                                    text = paneBadge,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = paneBadgeColor,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                )
                            }
                        }

                        WorkspaceEnvType.entries.forEach { e ->
                            FilterChip(
                                selected = env == e,
                                onClick = { onSelectEnv(e) },
                                label = { Text(e.localizedLabel(context), fontSize = 12.sp) }
                            )
                        }

                        if (env == WorkspaceEnvType.SAF) {
                            val phonePrefix = stringResource(R.string.files_phone_storage_prefix)
                            val safLabel = remember(safTreeUri, phonePrefix) {
                                if (safTreeUri.isNullOrBlank()) context.getString(R.string.files_choose_folder)
                                else formatSafTreeLabelLocalized(context, safTreeUri).removePrefix(phonePrefix)
                            }
                            OutlinedButton(
                                onClick = onPickSaf,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(safLabel, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        if (env == WorkspaceEnvType.SAF || env == WorkspaceEnvType.INTERNAL) {
                            ForgeIconButton(
                                icon = Icons.Rounded.Info,
                                contentDescription = stringResource(R.string.files_info_desc),
                                tint = ForgeColors.AccentBlue,
                                size = 30.dp,
                                iconSize = 15.dp,
                                onClick = onShowInfo
                            )
                        }
                    }

                    if (onToggleSplit != null) {
                        ForgeActionPill(
                            text = "◫ " + stringResource(R.string.files_split_view_btn),
                            active = splitEnabled,
                            minHeight = 32.dp,
                            horizontalPadding = 10.dp,
                            verticalPadding = 5.dp,
                            onClick = onToggleSplit
                        )
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
                            val isSel = selectedSshId == prof.id ||
                                (selectedSshId == null && prof == enabledSsh.firstOrNull())
                            FilterChip(
                                selected = isSel,
                                onClick = { onSelectSshProfile(prof.id) },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        ForgeIconButton(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_up),
                            tint = ForgeColors.TextPrimary,
                            enabled = path.isNotBlank(),
                            size = if (splitEnabled) 31.dp else 34.dp,
                            iconSize = 16.dp,
                            onClick = onNavigateUp
                        )
                        val rootDisplay = if (env == WorkspaceEnvType.DEVICE_STORAGE) {
                            "/storage/emulated/0"
                        } else {
                            stringResource(R.string.files_root_slash)
                        }
                        Text(
                            text = if (path.isBlank()) rootDisplay else path,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                            color = ForgeColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        val btnSize = if (splitEnabled) 31.dp else 34.dp
                        ForgeIconButton(
                            icon = Icons.Rounded.UploadFile,
                            contentDescription = stringResource(R.string.files_upload_phone),
                            tint = ForgeColors.AccentEmerald,
                            size = btnSize,
                            iconSize = 16.dp,
                            onClick = onUploadClick
                        )
                        ForgeIconButton(
                            icon = Icons.AutoMirrored.Rounded.NoteAdd,
                            contentDescription = stringResource(R.string.files_new_file),
                            tint = ForgeColors.AccentBlue,
                            size = btnSize,
                            iconSize = 16.dp,
                            onClick = { onCreateNew(false) }
                        )
                        ForgeIconButton(
                            icon = Icons.Rounded.CreateNewFolder,
                            contentDescription = stringResource(R.string.files_new_folder),
                            tint = ForgeColors.AccentAmber,
                            size = btnSize,
                            iconSize = 16.dp,
                            onClick = { onCreateNew(true) }
                        )
                        ForgeIconButton(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            tint = ForgeColors.TextPrimary,
                            size = btnSize,
                            iconSize = 16.dp,
                            onClick = onRefresh
                        )
                    }
                }
            }
        }

        // Sticky clipboard bar when an item is copied or cut
        AnimatedVisibility(
            visible = fileClipboard != null,
            enter = expandVertically(tween(150)) + fadeIn(tween(130)),
            exit = shrinkVertically(tween(130)) + fadeOut(tween(100))
        ) {
            fileClipboard?.let { clipEntry ->
                ClipboardStickyBar(
                    entry = clipEntry,
                    onPasteHere = onPasteClipboard,
                    onClear = onClearClipboard
                )
            }
        }

        // Android 11+ Full Storage Access 1-tap permission banner/card
        if (showPermCard) {
            DeviceStoragePermissionCard(
                compact = splitEnabled && items.isNotEmpty(),
                onGrantClick = onGrantStoragePerm
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                showPermCard && (!error.isNullOrBlank() || items.isEmpty()) -> {
                    // Permission card above already explains & handles granting access cleanly
                }
                !error.isNullOrBlank() -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(error, color = ForgeColors.DangerRed, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onRefresh) { Text(stringResource(R.string.action_retry)) }
                }
                items.isEmpty() -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!splitEnabled) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(ForgeColors.SurfaceElevated, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = ForgeColors.AccentBlue,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(stringResource(R.string.files_dir_empty), color = ForgeColors.TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onUploadClick) {
                        Icon(Icons.Rounded.UploadFile, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.files_upload_phone))
                    }
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    val panePrefix = if (isSecondPane) "p2" else "p1"
                    items(
                        items = items,
                        key = { "$panePrefix:${it.envType.id}:${it.sshServerId ?: ""}:${it.path}" }
                    ) { item ->
                        FileExplorerRow(
                            item = item,
                            isSecondPane = isSecondPane,
                            splitEnabled = splitEnabled,
                            hasSafUri = !safTreeUri.isNullOrBlank(),
                            sshProfiles = enabledSsh,
                            onOpen = { onOpenItem(item) },
                            onAttachToChat = { onAttachToChat(item) },
                            onCopyToClipboard = { onCopyToClipboard(item) },
                            onCutToClipboard = { onCutToClipboard(item) },
                            onTransferToOtherPane = { move -> onTransferToOtherPane(item, move) },
                            onTransfer = { target, targetSsh -> onTransferToEnv(item, target, targetSsh) },
                            onDelete = { onDeleteItem(item) }
                        )
                        HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceStoragePermissionCard(
    compact: Boolean,
    onGrantClick: () -> Unit
) {
    Surface(
        color = ForgeColors.SurfaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, ForgeColors.AccentAmber.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhoneAndroid,
                    contentDescription = null,
                    tint = ForgeColors.AccentAmber,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.files_perm_banner_title),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ForgeColors.TextPrimary
                )
            }
            Text(
                text = stringResource(R.string.files_perm_banner_desc),
                fontSize = 12.sp,
                lineHeight = 16.5.sp,
                color = ForgeColors.TextSecondary
            )
            Button(
                onClick = onGrantClick,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.files_perm_grant_btn),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ClipboardStickyBar(
    entry: FileClipboardEntry,
    onPasteHere: () -> Unit,
    onClear: () -> Unit
) {
    val accent = if (entry.isCut) ForgeColors.AccentAmber else ForgeColors.AccentBlue
    Surface(
        color = ForgeColors.SurfaceElevated,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (entry.isCut) Icons.Rounded.ContentCut else Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = entry.item.name,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = ForgeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ForgeActionPill(
                text = stringResource(R.string.files_clipboard_paste_here),
                icon = Icons.Rounded.ContentPaste,
                accentColor = accent,
                contentColor = accent,
                borderColor = accent.copy(alpha = 0.7f),
                minHeight = 28.dp,
                horizontalPadding = 9.dp,
                verticalPadding = 4.dp,
                onClick = onPasteHere
            )
            ForgeIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.action_cancel),
                tint = ForgeColors.TextSecondary,
                size = 28.dp,
                iconSize = 15.dp,
                onClick = onClear
            )
        }
    }
}

@Composable
private fun FileExplorerRow(
    item: WorkspaceFileItem,
    isSecondPane: Boolean,
    splitEnabled: Boolean,
    hasSafUri: Boolean,
    sshProfiles: List<SshProfileEntity>,
    onOpen: () -> Unit,
    onAttachToChat: () -> Unit,
    onCopyToClipboard: () -> Unit,
    onCutToClipboard: () -> Unit,
    onTransferToOtherPane: (Boolean) -> Unit,
    onTransfer: (WorkspaceEnvType, String?) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    val copyToOtherLabel = stringResource(
        if (isSecondPane) R.string.files_copy_to_top_pane else R.string.files_copy_to_other_pane
    )
    val moveToOtherLabel = stringResource(
        if (isSecondPane) R.string.files_move_to_top_pane else R.string.files_move_to_other_pane
    )

    val (rowIcon, rowTint) = remember(item.name, item.isDirectory) {
        if (item.isDirectory) {
            Icons.Rounded.Folder to ForgeColors.AccentAmber
        } else {
            val ext = item.name.substringAfterLast('.', "").lowercase()
            when (ext) {
                "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "json", "xml",
                "sh", "bash", "yaml", "yml", "toml", "gradle", "sql", "html", "css",
                "c", "cpp", "h", "hpp", "rs", "go" -> Icons.Rounded.Code to ForgeColors.AccentBlue
                "md", "markdown", "txt", "log", "csv", "conf", "ini", "properties",
                "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp" ->
                    Icons.Rounded.Description to ForgeColors.AccentCyan
                "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "heic", "heif" ->
                    Icons.Rounded.Image to ForgeColors.AccentEmerald
                "pdf" -> Icons.Rounded.PictureAsPdf to Color(0xFFF87171)
                "zip", "tar", "gz", "tgz", "7z", "rar", "apk" -> Icons.Rounded.Archive to ForgeColors.AccentAmber
                "mp3", "wav", "ogg", "m4a", "flac", "aac" -> Icons.Rounded.Audiotrack to Color(0xFFA78BFA)
                else -> Icons.AutoMirrored.Rounded.InsertDriveFile to ForgeColors.TextSecondary
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = if (splitEnabled) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (splitEnabled) 32.dp else 36.dp)
                .background(rowTint.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = rowIcon,
                contentDescription = null,
                tint = rowTint,
                modifier = Modifier.size(if (splitEnabled) 18.dp else 20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontSize = if (splitEnabled) 13.5.sp else 14.sp,
                fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                color = ForgeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.isDirectory) {
                Text(
                    text = formatByteCount(item.sizeBytes),
                    fontSize = 11.sp,
                    color = ForgeColors.TextSecondary
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (splitEnabled) {
                ForgeIconButton(
                    icon = Icons.AutoMirrored.Rounded.DriveFileMove,
                    contentDescription = copyToOtherLabel,
                    tint = ForgeColors.AccentEmerald,
                    size = 29.dp,
                    iconSize = 15.dp,
                    onClick = { onTransferToOtherPane(false) }
                )
            }
            ForgeIconButton(
                icon = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.files_clipboard_copy),
                tint = ForgeColors.AccentBlue,
                size = 29.dp,
                iconSize = 14.dp,
                onClick = onCopyToClipboard
            )
            ForgeIconButton(
                icon = Icons.Rounded.ContentCut,
                contentDescription = stringResource(R.string.files_clipboard_cut),
                tint = ForgeColors.AccentAmber,
                size = 29.dp,
                iconSize = 14.dp,
                onClick = onCutToClipboard
            )
            Box {
                ForgeIconButton(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = null,
                    tint = ForgeColors.TextPrimary,
                    size = 30.dp,
                    iconSize = 16.dp,
                    onClick = { menuOpen = true }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!item.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_open_in_app)) },
                            leadingIcon = { Icon(Icons.Rounded.Visibility, null) },
                            onClick = {
                                menuOpen = false
                                onOpen()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_attach_chat)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Send, null) },
                            onClick = {
                                menuOpen = false
                                onAttachToChat()
                            }
                        )
                    }
                    if (splitEnabled) {
                        DropdownMenuItem(
                            text = { Text(copyToOtherLabel) },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = ForgeColors.AccentEmerald) },
                            onClick = {
                                menuOpen = false
                                onTransferToOtherPane(false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(moveToOtherLabel) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null, tint = ForgeColors.AccentAmber) },
                            onClick = {
                                menuOpen = false
                                onTransferToOtherPane(true)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_clipboard_copy)) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = {
                            menuOpen = false
                            onCopyToClipboard()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_clipboard_cut)) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCut, null) },
                        onClick = {
                            menuOpen = false
                            onCutToClipboard()
                        }
                    )
                    listOf(
                        WorkspaceEnvType.INTERNAL,
                        WorkspaceEnvType.DEVICE_STORAGE,
                        WorkspaceEnvType.SAF
                    )
                        .filter { it != item.envType && (it != WorkspaceEnvType.SAF || hasSafUri) }
                        .forEach { target ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_copy_to, target.localizedLabel(context))) },
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
                                text = { Text(stringResource(R.string.files_copy_to_ssh, prof.name)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) },
                                onClick = {
                                    menuOpen = false
                                    onTransfer(WorkspaceEnvType.TERMUX_SSH, prof.id)
                                }
                            )
                        }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = ForgeColors.DangerRed) },
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
}

@Composable
private fun SoraEditorPane(
    tab: OpenEditorTab,
    onContentChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCopyCode: (String) -> Unit
) {
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }
    var previewMarkdown by remember(tab.id) { mutableStateOf(false) }

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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (tab.kind == EditorTabKind.MARKDOWN) {
                    FilterChip(
                        selected = previewMarkdown,
                        onClick = { previewMarkdown = !previewMarkdown },
                        label = {
                            Text(
                                stringResource(if (previewMarkdown) R.string.viewer_tab_code else R.string.viewer_tab_preview),
                                fontSize = 11.sp
                            )
                        }
                    )
                }
                if (!previewMarkdown) {
                    ForgeIconButton(
                        icon = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.files_search_in_file),
                        active = showSearch,
                        size = 32.dp,
                        iconSize = 16.dp,
                        onClick = { showSearch = !showSearch }
                    )
                }
                if (tab.isDirty) {
                    Button(
                        onClick = onSave,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Rounded.Save, null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_save), fontSize = 11.5.sp, maxLines = 1, softWrap = false)
                    }
                } else {
                    ForgeActionPill(
                        text = stringResource(R.string.editor_saved_clean),
                        icon = Icons.Rounded.CheckCircle,
                        accentColor = ForgeColors.AccentEmerald,
                        contentColor = ForgeColors.AccentEmerald,
                        borderColor = ForgeColors.AccentEmerald.copy(alpha = 0.55f),
                        minHeight = 32.dp,
                        onClick = onSave
                    )
                }
            }
        }

        if (previewMarkdown && tab.kind == EditorTabKind.MARKDOWN) {
            PlainDocumentReader(
                text = tab.content,
                isMarkdown = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onCopyCode = onCopyCode
            )
            return@Column
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
                    placeholder = { Text(stringResource(R.string.files_find_hint), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { runCatching { editorRef?.searcher?.gotoNext() } }) {
                    Text(stringResource(R.string.action_next), fontSize = 11.5.sp)
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
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, ForgeColors.BorderHighlight.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
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
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
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
