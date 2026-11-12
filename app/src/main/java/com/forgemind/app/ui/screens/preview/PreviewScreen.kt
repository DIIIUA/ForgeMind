package com.forgemind.app.ui.screens.preview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.theme.ForgeColors

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(vm: MainViewModel) {
    val aiBrowserUrl by vm.aiBrowserUrl.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var activeUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var showConsole by remember { mutableStateOf(false) }
    val consoleLogs = remember { mutableStateListOf<String>() }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun navigateTo(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return
        val normalized = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("about:", ignoreCase = true) -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://duckduckgo.com/?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
        }
        urlInput = normalized
        activeUrl = normalized
        vm.openBrowserUrl(normalized)
        webViewRef?.loadUrl(normalized)
    }

    LaunchedEffect(aiBrowserUrl) {
        val incoming = aiBrowserUrl.trim()
        if (incoming.isNotBlank() && incoming != activeUrl) {
            urlInput = incoming
            activeUrl = incoming
            webViewRef?.loadUrl(incoming)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeColors.BgDeep)
    ) {
        Surface(color = ForgeColors.SurfaceDark) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = { webViewRef?.goBack() },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            "Назад",
                            tint = ForgeColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { webViewRef?.goForward() },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            "Вперёд",
                            tint = ForgeColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (activeUrl.isNotBlank()) webViewRef?.reload()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            "Обновить",
                            tint = ForgeColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        placeholder = { Text("Адрес сайта или поиск...", fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { navigateTo(urlInput) }),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            activeUrl = ""
                            urlInput = ""
                            vm.openBrowserUrl("")
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Home,
                            "Стартовая страница",
                            tint = ForgeColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { showConsole = !showConsole },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Terminal,
                            "JS Консоль",
                            tint = if (showConsole) ForgeColors.AccentEmerald else ForgeColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (pageTitle.isNotBlank() && activeUrl.isNotBlank()) {
                    Text(
                        text = pageTitle,
                        fontSize = 11.sp,
                        color = ForgeColors.TextSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                progress = { (loadProgress / 100f).coerceIn(0.05f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = ForgeColors.AccentBlue
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (activeUrl.isBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Rounded.Public,
                        contentDescription = null,
                        tint = ForgeColors.AccentBlue,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Браузер ИИ",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = ForgeColors.TextPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Здесь отображаются веб-страницы, с которыми работает ИИ (web_scrape / browser_open), и любые сайты по адресу выше.",
                        fontSize = 13.sp,
                        color = ForgeColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { navigateTo("https://duckduckgo.com") },
                            colors = ButtonDefaults.buttonColors(containerColor = ForgeColors.AccentBlue)
                        ) {
                            Icon(Icons.Rounded.Public, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Открыть поиск", fontSize = 12.5.sp)
                        }
                        OutlinedButton(onClick = { vm.startStaticPreviewServer(openInBrowser = true) }) {
                            Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Локальные файлы ИИ", fontSize = 12.5.sp)
                        }
                    }
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                builtInZoomControls = true
                                displayZoomControls = false
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean = false

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    isLoading = true
                                    if (!url.isNullOrBlank()) {
                                        urlInput = url
                                        activeUrl = url
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    if (!url.isNullOrBlank()) {
                                        urlInput = url
                                        activeUrl = url
                                    }
                                    pageTitle = view?.title.orEmpty()
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title.orEmpty()
                                }

                                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                                    if (cm != null) {
                                        consoleLogs.add("[${cm.messageLevel()}] ${cm.message()} (:${cm.lineNumber()})")
                                        if (consoleLogs.size > 150) consoleLogs.removeAt(0)
                                    }
                                    return true
                                }
                            }
                            loadUrl(activeUrl)
                        }
                    },
                    update = { view ->
                        webViewRef = view
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (showConsole) {
            HorizontalDivider(color = ForgeColors.BorderSubtle)
            Surface(color = ForgeColors.BgDeep, modifier = Modifier.height(175.dp).fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "JS Консоль (${consoleLogs.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ForgeColors.TextSecondary
                        )
                        IconButton(
                            onClick = { consoleLogs.clear() },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Rounded.Delete, "Очистить", tint = ForgeColors.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(consoleLogs) { log ->
                            Text(
                                log,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (log.contains("ERROR")) ForgeColors.DangerRed else ForgeColors.TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
