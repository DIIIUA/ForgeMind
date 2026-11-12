package com.forgemind.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.ui.AppSection
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.screens.chat.ChatScreen
import com.forgemind.app.ui.screens.files.FilesScreen
import com.forgemind.app.ui.screens.preview.PreviewScreen
import com.forgemind.app.ui.screens.settings.SettingsScreen
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeMindTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var pendingAttachmentToSave: ChatAttachment? = null

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

    private val pickZipRestore = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.importAllArchiveZip(it) }
    }

    private val requestAudioPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.toggleVoiceRecording()
        else vm.showToast("Нужно разрешение на микрофон для голосового ввода")
    }

    private val requestStartupPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        requestNonBlockingPermissions()
        handleIncomingShareIntent(intent)

        setContent {
            ForgeMindTheme {
                val section by vm.selectedSection.collectAsState()
                val toastMessage by vm.toastMessage.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(toastMessage) {
                    val msg = toastMessage
                    if (!msg.isNullOrBlank()) {
                        snackbarHostState.showSnackbar(msg)
                        vm.clearToast()
                    }
                }

                val density = LocalDensity.current
                val imeVisible = WindowInsets.ime.getBottom(density) >
                    WindowInsets.navigationBars.getBottom(density)

                Scaffold(
                    containerColor = ForgeColors.BgDeep,
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    bottomBar = {
                        if (!imeVisible) {
                            NavigationBar(containerColor = ForgeColors.SurfaceDark) {
                            AppSection.entries.forEach { tab ->
                                val icon = when (tab) {
                                    AppSection.CHAT -> Icons.AutoMirrored.Rounded.Chat
                                    AppSection.FILES -> Icons.Rounded.Code
                                    AppSection.PREVIEW -> Icons.Rounded.Public
                                    AppSection.SETTINGS -> Icons.Rounded.Settings
                                }
                                NavigationBarItem(
                                    selected = section == tab,
                                    onClick = { vm.selectSection(tab) },
                                    icon = { Icon(icon, tab.label) },
                                    label = { Text(tab.label, fontSize = 10.5.sp, maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = ForgeColors.AccentBlue,
                                        selectedTextColor = ForgeColors.AccentBlue,
                                        indicatorColor = ForgeColors.AccentBlue.copy(alpha = 0.16f),
                                        unselectedIconColor = ForgeColors.TextSecondary,
                                        unselectedTextColor = ForgeColors.TextSecondary
                                    )
                                )
                            }
                            }
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
                        when (section) {
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
                                    pendingAttachmentToSave = att
                                    createAttachmentSave.launch(att.name)
                                },
                                onExportMarkdown = {
                                    createMarkdownExport.launch("forgemind_chat.md")
                                },
                                onExportZip = {
                                    createZipBackup.launch("forgemind_backup.zip")
                                },
                                onImportZip = {
                                    pickZipRestore.launch(arrayOf("application/zip", "application/octet-stream"))
                                },
                                onPickSafFolder = { pickSafDirectory.launch(null) }
                            )

                            AppSection.FILES -> FilesScreen(
                                vm = vm,
                                onUploadFileClick = { pickWorkspaceUpload.launch(arrayOf("*/*")) },
                                onPickSafFolderClick = { pickSafDirectory.launch(null) }
                            )

                            AppSection.PREVIEW -> PreviewScreen(vm)

                            AppSection.SETTINGS -> SettingsScreen(
                                vm = vm,
                                onExportZip = { createZipBackup.launch("forgemind_backup.zip") },
                                onImportZip = { pickZipRestore.launch(arrayOf("application/zip", "application/octet-stream")) },
                                onPickSafFolder = { pickSafDirectory.launch(null) }
                            )
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

    private fun requestNonBlockingPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            requestStartupPerms.launch(needed.toTypedArray())
        }
    }
}
