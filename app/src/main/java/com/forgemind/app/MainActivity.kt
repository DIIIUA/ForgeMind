package com.forgemind.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.forgemind.app.bridge.InAppDocumentParser
import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.floating.FloatingChatService
import com.forgemind.app.ui.AppSection
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.components.ChatImageGalleryHelper
import com.forgemind.app.ui.labelRes
import com.forgemind.app.ui.screens.chat.ChatScreen
import com.forgemind.app.ui.screens.files.FilesScreen
import com.forgemind.app.ui.screens.preview.PreviewScreen
import com.forgemind.app.ui.screens.settings.SettingsScreen
import com.forgemind.app.ui.screens.terminal.TerminalScreen
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeMindTheme
import com.forgemind.app.i18n.LocaleHelper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.forgemind.app.ui.theme.ForgeType

class MainActivity : ComponentActivity() {
    override fun onStop() {
        super.onStop()
        (application as ForgeMindApp).container.chatDrafts.flushSoon()
    }


    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private val vm: MainViewModel by viewModels()
    private var pendingAttachmentToSave: ChatAttachment? = null
    private var pendingBatchSessionIds: Set<String> = emptySet()

    private val pickChatAttachments = registerForActivityResult(
        object : ActivityResultContracts.OpenMultipleDocuments() {
            override fun createIntent(context: android.content.Context, input: Array<String>): Intent {
                return super.createIntent(context, input).apply {
                    val safUri = vm.safTreeUri.value?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                    if (safUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, safUri)
                    }
                }
            }
        }
    ) { uris ->
        uris.forEach { vm.addAttachmentFromUri(it) }
    }

    private val pickWorkspaceUpload = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.uploadFileToExplorer(it) }
    }

    private val pickSafDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.setSafFolderUri(uri)
        }
    }

    private val createAttachmentSave = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val target = pendingAttachmentToSave
        pendingAttachmentToSave = null
        if (uri != null && target != null) {
            vm.exportAttachmentToUri(target.localPath, uri)
        }
    }

    private val createMarkdownExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        uri?.let { vm.exportCurrentSessionMarkdown(it) }
    }

    private val createZipBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { vm.exportAllArchiveZip(it) }
    }

    private val createSelectedZipExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val ids = pendingBatchSessionIds
        pendingBatchSessionIds = emptySet()
        if (uri != null && ids.isNotEmpty()) {
            vm.exportSelectedArchiveZip(uri, ids)
        }
    }

    private val pickSelectedMarkdownFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val ids = pendingBatchSessionIds
        pendingBatchSessionIds = emptySet()
        if (uri != null && ids.isNotEmpty()) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.exportSessionsMarkdownToFolder(uri, ids)
        }
    }

    private val pickZipRestore = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.importAllArchiveZip(it) }
    }

    private val requestAudioPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.toggleVoiceRecording()
        else vm.showToast(getString(R.string.perm_mic_required))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        handleIncomingShareIntent(intent)

        setContent {
            ForgeMindTheme {
                com.forgemind.app.ui.screens.chat.ArchiveImportDialog(vm)
                val section by vm.selectedSection.collectAsState()
                val setupProgress by vm.setupWizard.progress.collectAsState()
                val settingsLoaded by vm.settingsLoaded.collectAsState()
                val showSetup = setupProgress.opensAutomatically
                val toastMessage by vm.toastMessage.collectAsState()
                val aiShellState by vm.aiTerminalState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(toastMessage) {
                    val msg = toastMessage
                    if (!msg.isNullOrBlank()) {
                        snackbarHostState.showSnackbar(msg)
                        vm.clearToast()
                    }
                }

                // The bottom nav stays laid out while the keyboard opens: the IME simply covers it and
                // imePadding() below moves the composer in lockstep with the keyboard animation.
                // (Reading WindowInsets.ime here used to recompose the whole app on every IME frame
                // and the separate nav-bar shrink animation made the composer "creep" at the end.)
                Scaffold(
                    containerColor = ForgeColors.BgDeep,
                    snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState) { data ->
                            Snackbar(
                                snackbarData = data,
                                containerColor = ForgeColors.SurfaceElevated,
                                contentColor = ForgeColors.TextPrimary,
                                actionColor = ForgeColors.AccentBlue,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    },
                    bottomBar = {
                        if (!showSetup) androidx.compose.foundation.layout.Column {
                            if (section != AppSection.CHAT) com.forgemind.app.ui.components.AgentRunPanel(vm)
                            ForgeBottomNav(
                                selected = section,
                                terminalBusy = aiShellState.isRunningCommand,
                                onSelect = { vm.selectSection(it) }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                            .imePadding()
                    ) {
                        val isFloatingChatRunning by FloatingChatService.isRunning.collectAsState()

                        if (showSetup) {
                            if (settingsLoaded) com.forgemind.app.ui.screens.settings.SetupWizardScreen(
                                vm = vm,
                                onPickSafFolder = { pickSafDirectory.launch(null) },
                                onExportZip = { createZipBackup.launch("forgemind_backup.zip") },
                                onImportZip = { pickZipRestore.launch(arrayOf("application/zip", "application/octet-stream")) },
                                onLanguageChanged = { recreate() }
                            ) else androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                        } else AnimatedContent(
                            targetState = section,
                            transitionSpec = {
                                val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                (fadeIn(tween(190, easing = FastOutSlowInEasing)) +
                                    slideInHorizontally(tween(210, easing = FastOutSlowInEasing)) { w -> direction * (w / 18) })
                                    .togetherWith(
                                        fadeOut(tween(130)) +
                                            slideOutHorizontally(tween(160)) { w -> -direction * (w / 22) }
                                    )
                            },
                            label = "mainSectionTransition"
                        ) { targetSection ->
                            when (targetSection) {
                                AppSection.CHAT -> ChatScreen(
                                    vm = vm,
                                    onPickAttachments = { pickChatAttachments.launch(arrayOf("*/*")) },
                                    onRequestVoiceRecord = {
                                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                                            == PackageManager.PERMISSION_GRANTED
                                        ) {
                                            vm.toggleVoiceRecording()
                                        } else {
                                            requestAudioPerm.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    onSaveAttachment = { att ->
                                        if (att.kind == AttachmentKind.IMAGE || InAppDocumentParser.isImageExtension(att.name)) {
                                            ChatImageGalleryHelper.triggerDownloadWithToast(
                                                context = this@MainActivity,
                                                scope = lifecycleScope,
                                                source = att.localPath,
                                                suggestedName = att.name
                                            )
                                        } else {
                                            pendingAttachmentToSave = att
                                            createAttachmentSave.launch(att.name)
                                        }
                                    },
                                    onExportMarkdown = {
                                        createMarkdownExport.launch("forgemind_chat.md")
                                    },
                                    onExportZip = {
                                        createZipBackup.launch("forgemind_backup.zip")
                                    },
                                    onExportSelectedZip = { ids ->
                                        pendingBatchSessionIds = ids
                                        createSelectedZipExport.launch("forgemind_chats_${ids.size}.zip")
                                    },
                                    onExportSelectedToDeviceFolder = { ids ->
                                        pendingBatchSessionIds = ids
                                        pickSelectedMarkdownFolder.launch(null)
                                    },
                                    onImportZip = {
                                        pickZipRestore.launch(arrayOf("application/zip", "application/octet-stream"))
                                    },
                                    onPickSafFolder = { pickSafDirectory.launch(null) },
                                    onToggleFloatingChat = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:$packageName")
                                            )
                                            startActivity(intent)
                                        } else {
                                            FloatingChatService.toggle(this@MainActivity, vm.currentSession.value?.id)
                                        }
                                    },
                                    isFloatingChatActive = isFloatingChatRunning
                                )

                                AppSection.FILES -> FilesScreen(
                                    vm = vm,
                                    onUploadFileClick = { pickWorkspaceUpload.launch(arrayOf("*/*")) },
                                    onPickSafFolderClick = { pickSafDirectory.launch(null) }
                                )

                                AppSection.TERMINAL -> TerminalScreen(vm)

                                AppSection.PREVIEW -> PreviewScreen(vm)

                                AppSection.SETTINGS -> SettingsScreen(
                                    vm = vm,
                                    onExportZip = { createZipBackup.launch("forgemind_backup.zip") },
                                    onImportZip = { pickZipRestore.launch(arrayOf("application/zip", "application/octet-stream")) },
                                    onPickSafFolder = { pickSafDirectory.launch(null) },
                                    onLanguageChanged = { recreate() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!sharedText.isNullOrBlank()) {
            vm.updateComposerText(sharedText)
        }
        val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        streamUri?.let { vm.addAttachmentFromUri(it) }
    }

}

/**
 * 64 dp bottom navigation (redesign): 56×28 accent pill behind the selected icon, 12 sp labels,
 * green dot on Terminal while the AI shell runs. No scale/hide animations — keeps IME frames cheap.
 */
@Composable
private fun ForgeBottomNav(
    selected: AppSection,
    terminalBusy: Boolean,
    onSelect: (AppSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeColors.SurfaceDark)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        HorizontalDivider(color = ForgeColors.Divider, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .selectableGroup()
        ) {
            AppSection.entries.forEach { tab ->
                val isSelected = selected == tab
                val label = stringResource(tab.labelRes())
                val icon = when (tab) {
                    AppSection.CHAT -> if (isSelected) Icons.Rounded.ChatBubble else Icons.Outlined.ChatBubbleOutline
                    AppSection.FILES -> if (isSelected) Icons.Rounded.Folder else Icons.Outlined.Folder
                    AppSection.TERMINAL -> Icons.Rounded.Terminal
                    AppSection.PREVIEW -> if (isSelected) Icons.Rounded.Public else Icons.Outlined.Public
                    AppSection.SETTINGS -> Icons.Rounded.Tune
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 28.dp)
                            .background(
                                if (isSelected) ForgeColors.AccentTint else Color.Transparent,
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) ForgeColors.AccentText else ForgeColors.TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        if (tab == AppSection.TERMINAL && terminalBusy) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 2.dp, end = 14.dp)
                                    .size(10.dp)
                                    .background(ForgeColors.SurfaceDark, CircleShape)
                                    .padding(2.dp)
                                    .background(ForgeColors.AccentEmerald, CircleShape)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        fontSize = ForgeType.Caption,
                        lineHeight = 16.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) ForgeColors.TextPrimary else ForgeColors.TextSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
