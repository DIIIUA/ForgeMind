package com.forgemind.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class AgentBrowserController(private val appContext: Context) {

    // User toggle in UI: false = isolated User browser, true = Live Agent view.
    private val _agentModeEnabled = MutableStateFlow(false)
    val agentModeEnabled: StateFlow<Boolean> = _agentModeEnabled.asStateFlow()

    // --- User browser (isolated WebView) ---
    private val _userCurrentUrl = MutableStateFlow("")
    val userCurrentUrl: StateFlow<String> = _userCurrentUrl.asStateFlow()

    private val _userPageTitle = MutableStateFlow("")
    val userPageTitle: StateFlow<String> = _userPageTitle.asStateFlow()

    private val _userIsLoading = MutableStateFlow(false)
    val userIsLoading: StateFlow<Boolean> = _userIsLoading.asStateFlow()

    private val _userLoadProgress = MutableStateFlow(0)
    val userLoadProgress: StateFlow<Int> = _userLoadProgress.asStateFlow()

    private val _userCanGoBack = MutableStateFlow(false)
    val userCanGoBack: StateFlow<Boolean> = _userCanGoBack.asStateFlow()

    private val _userCanGoForward = MutableStateFlow(false)
    val userCanGoForward: StateFlow<Boolean> = _userCanGoForward.asStateFlow()

    private val _userConsoleLogs = MutableStateFlow<List<String>>(emptyList())
    val userConsoleLogs: StateFlow<List<String>> = _userConsoleLogs.asStateFlow()

    // --- Background Agent browser (dedicated WebView exclusively for AI) ---
    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadProgress = MutableStateFlow(0)
    val loadProgress: StateFlow<Int> = _loadProgress.asStateFlow()

    private val _agentCanGoBack = MutableStateFlow(false)
    val agentCanGoBack: StateFlow<Boolean> = _agentCanGoBack.asStateFlow()

    private val _agentCanGoForward = MutableStateFlow(false)
    val agentCanGoForward: StateFlow<Boolean> = _agentCanGoForward.asStateFlow()

    private val _lastActionBanner = MutableStateFlow<String?>(null)
    val lastActionBanner: StateFlow<String?> = _lastActionBanner.asStateFlow()

    private val _actionLog = MutableStateFlow<List<String>>(emptyList())
    val actionLog: StateFlow<List<String>> = _actionLog.asStateFlow()

    private val _consoleLogs = MutableStateFlow<List<String>>(emptyList())
    val consoleLogs: StateFlow<List<String>> = _consoleLogs.asStateFlow()

    private var userWebView: WebView? = null
    private var agentWebView: WebView? = null

    @Volatile
    private var agentLoadWaiter: CompletableDeferred<String>? = null

    // Serializes all agent WebView operations (navigate / inspect / act / evalJs / captureScreenshot)
    // so parallel tool calls in a single LLM step never capture a mid-repaint blank frame.
    private val agentOpMutex = Mutex()
    private val visualFrameCounter = AtomicLong(1L)

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setAgentMode(enabled: Boolean) {
        _agentModeEnabled.value = enabled
    }

    fun clearUserLogs() {
        _userConsoleLogs.value = emptyList()
    }

    fun clearLogs() {
        _consoleLogs.value = emptyList()
    }

    fun clearActionLog() {
        _actionLog.value = emptyList()
        _lastActionBanner.value = null
    }

    fun resetToHome() = resetUserToHome()

    fun resetUserToHome() {
        _userCurrentUrl.value = ""
        _userPageTitle.value = ""
        _userIsLoading.value = false
        _userLoadProgress.value = 0
        _userCanGoBack.value = false
        _userCanGoForward.value = false
        pauseAndBlankOnMain(userWebView)
    }

    fun resetAgentBrowser() {
        _currentUrl.value = ""
        _pageTitle.value = ""
        _isLoading.value = false
        _loadProgress.value = 0
        _agentCanGoBack.value = false
        _agentCanGoForward.value = false
        appendAction("Agent browser reset to start page")
        pauseAndBlankOnMain(agentWebView)
    }

    private fun pauseAndBlankOnMain(wv: WebView?) {
        wv ?: return
        val stopAction = Runnable {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.onPause()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopAction.run()
        } else {
            Handler(Looper.getMainLooper()).post(stopAction)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(
        urlState: MutableStateFlow<String>,
        titleState: MutableStateFlow<String>,
        loadingState: MutableStateFlow<Boolean>,
        progressState: MutableStateFlow<Int>,
        canBackState: MutableStateFlow<Boolean>,
        canFwdState: MutableStateFlow<Boolean>,
        consoleState: MutableStateFlow<List<String>>,
        onAgentFinished: ((String?) -> Unit)? = null,
        onAlertBanner: ((String) -> Unit)? = null
    ): WebView = WebView(appContext).apply {
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
                loadingState.value = true
                if (!url.isNullOrBlank() && url != "about:blank") {
                    urlState.value = url
                }
                canBackState.value = view?.canGoBack() == true
                canFwdState.value = view?.canGoForward() == true
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                canBackState.value = view?.canGoBack() == true
                canFwdState.value = view?.canGoForward() == true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loadingState.value = false
                if (!url.isNullOrBlank() && url != "about:blank") {
                    urlState.value = url
                }
                if (url != "about:blank") {
                    titleState.value = view?.title.orEmpty()
                }
                canBackState.value = view?.canGoBack() == true
                canFwdState.value = view?.canGoForward() == true
                onAgentFinished?.invoke(url)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressState.value = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (urlState.value.isNotBlank()) {
                    titleState.value = title.orEmpty()
                }
            }

            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                if (cm != null) {
                    val line = "[${cm.messageLevel()}] ${cm.message()} (:${cm.lineNumber()})"
                    consoleState.value = (consoleState.value + line).takeLast(150)
                }
                return true
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                onAlertBanner?.invoke("JS alert: ${message.orEmpty().take(100)}")
                result?.confirm()
                return true
            }
        }
    }

    fun ensureUserWebView(): WebView {
        userWebView?.let { return it }
        return createConfiguredWebView(
            urlState = _userCurrentUrl,
            titleState = _userPageTitle,
            loadingState = _userIsLoading,
            progressState = _userLoadProgress,
            canBackState = _userCanGoBack,
            canFwdState = _userCanGoForward,
            consoleState = _userConsoleLogs
        ).also { userWebView = it }
    }

    fun ensureAgentWebView(): WebView {
        agentWebView?.let { return it }
        return createConfiguredWebView(
            urlState = _currentUrl,
            titleState = _pageTitle,
            loadingState = _isLoading,
            progressState = _loadProgress,
            canBackState = _agentCanGoBack,
            canFwdState = _agentCanGoForward,
            consoleState = _consoleLogs,
            onAgentFinished = { url ->
                val waiter = agentLoadWaiter
                if (waiter != null && url != "about:blank") {
                    waiter.complete(url.orEmpty())
                    agentLoadWaiter = null
                }
            },
            onAlertBanner = { msg -> appendAction(msg) }
        ).also { agentWebView = it }
    }

    fun acquireAttachedWebView(
        forAgent: Boolean = _agentModeEnabled.value,
        urlToLoadIfBlank: String? = null
    ): WebView {
        val wv = if (forAgent) ensureAgentWebView() else ensureUserWebView()
        val urlState = if (forAgent) _currentUrl else _userCurrentUrl
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.onResume()
        val cur = urlState.value.trim()
        if (!urlToLoadIfBlank.isNullOrBlank() && (wv.url.isNullOrBlank() || wv.url == "about:blank") && cur.isNotBlank()) {
            wv.loadUrl(cur)
        }
        return wv
    }

    fun releaseAttachedParent(forAgent: Boolean? = null) {
        if (forAgent == null || forAgent == false) {
            userWebView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }
        if (forAgent == null || forAgent == true) {
            agentWebView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }
    }

    suspend fun navigate(
        rawUrl: String,
        enableAgentMode: Boolean = false,
        waitFinishMs: Long = 15_000L
    ): Result<String> = withSerializedAgentOp(agentOpMutex) {
        runCatching {
            val normalized = normalizeUrl(rawUrl)
            if (normalized.isBlank()) error("Empty URL")
            if (enableAgentMode) {
                _agentModeEnabled.value = true
            }
            val waiter = CompletableDeferred<String>()
            withContext(Dispatchers.Main) {
                val wv = ensureAgentWebView()
                wv.onResume()
                layoutIfDetached(wv)
                agentLoadWaiter?.cancel()
                agentLoadWaiter = if (normalized == "about:blank") null else waiter
                _currentUrl.value = normalized
                appendAction("Navigate: $normalized")
                wv.loadUrl(normalized)
            }
            if (normalized != "about:blank") {
                withTimeoutOrNull(waitFinishMs.coerceIn(1000L, 30_000L)) {
                    waiter.await()
                }
                withContext(Dispatchers.Main) {
                    if (agentLoadWaiter === waiter) agentLoadWaiter = null
                }
            }
            delay(250)
            val summary = withContext(Dispatchers.Main) {
                val wv = ensureAgentWebView()
                val u = wv.url?.takeUnless { it == "about:blank" } ?: _currentUrl.value
                val t = wv.title.orEmpty()
                _currentUrl.value = u
                _pageTitle.value = t
                "Opened in background agent browser: $u" + if (t.isNotBlank()) " — \"$t\"" else ""
            }
            summary
        }
    }

    suspend fun navigateFromUser(rawUrl: String) {
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isBlank()) return
        withContext(Dispatchers.Main) {
            val wv = ensureUserWebView()
            wv.onResume()
            _userCurrentUrl.value = normalized
            wv.loadUrl(normalized)
        }
    }

    suspend fun goBack(forAgent: Boolean = true, waitFinish: Boolean = false): Result<String> =
        if (forAgent) withSerializedAgentOp(agentOpMutex) { goBackInternal(forAgent = true, waitFinish = waitFinish) }
        else goBackInternal(forAgent = false, waitFinish = waitFinish)

    private suspend fun goBackInternal(forAgent: Boolean, waitFinish: Boolean): Result<String> = runCatching {
        val urlState = if (forAgent) _currentUrl else _userCurrentUrl
        withContext(Dispatchers.Main) {
            val wv = if (forAgent) ensureAgentWebView() else ensureUserWebView()
            if (!wv.canGoBack()) error("Back history is empty")
            if (forAgent) appendAction("History Back")
            wv.goBack()
        }
        if (waitFinish) delay(450)
        "Back -> ${urlState.value}"
    }

    suspend fun goForward(forAgent: Boolean = true, waitFinish: Boolean = false): Result<String> =
        if (forAgent) withSerializedAgentOp(agentOpMutex) { goForwardInternal(forAgent = true, waitFinish = waitFinish) }
        else goForwardInternal(forAgent = false, waitFinish = waitFinish)

    private suspend fun goForwardInternal(forAgent: Boolean, waitFinish: Boolean): Result<String> = runCatching {
        val urlState = if (forAgent) _currentUrl else _userCurrentUrl
        withContext(Dispatchers.Main) {
            val wv = if (forAgent) ensureAgentWebView() else ensureUserWebView()
            if (!wv.canGoForward()) error("Forward history is empty")
            if (forAgent) appendAction("History Forward")
            wv.goForward()
        }
        if (waitFinish) delay(450)
        "Forward -> ${urlState.value}"
    }

    suspend fun reload(forAgent: Boolean = true, waitFinishMs: Long = 10_000L): Result<String> =
        if (forAgent) withSerializedAgentOp(agentOpMutex) { reloadInternal(forAgent = true, waitFinishMs = waitFinishMs) }
        else reloadInternal(forAgent = false, waitFinishMs = waitFinishMs)

    private suspend fun reloadInternal(forAgent: Boolean, waitFinishMs: Long = 10_000L): Result<String> = runCatching {
        val urlState = if (forAgent) _currentUrl else _userCurrentUrl
        if (forAgent) {
            val waiter = CompletableDeferred<String>()
            withContext(Dispatchers.Main) {
                val wv = ensureAgentWebView()
                agentLoadWaiter?.cancel()
                agentLoadWaiter = waiter
                appendAction("Reload page")
                wv.reload()
            }
            withTimeoutOrNull(waitFinishMs) { waiter.await() }
            withContext(Dispatchers.Main) {
                if (agentLoadWaiter === waiter) agentLoadWaiter = null
            }
        } else {
            withContext(Dispatchers.Main) {
                ensureUserWebView().reload()
            }
        }
        "Page reloaded: ${urlState.value}"
    }

    suspend fun inspect(maxTextChars: Int = 5500): Result<String> = withSerializedAgentOp(agentOpMutex) {
        runCatching {
            appendAction("Inspect DOM & interactive elements")
            val script = """
                (function() {
                  try {
                    var els = Array.prototype.slice.call(
                      document.querySelectorAll('a,button,input,textarea,select,[role="button"],[onclick]')
                    );
                    var visible = [];
                    for (var i = 0; i < els.length && visible.length < 45; i++) {
                      var el = els[i];
                      var r = el.getBoundingClientRect();
                      var st = window.getComputedStyle(el);
                      if (st.display === 'none' || st.visibility === 'hidden' || (r.width === 0 && r.height === 0)) continue;
                      var idx = visible.length;
                      el.setAttribute('data-fm-agent-idx', String(idx));
                      var tag = el.tagName.toLowerCase();
                      var txt = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('title') || '').replace(/\s+/g, ' ').trim().slice(0, 70);
                      var type = el.getAttribute('type') || '';
                      var href = el.getAttribute('href') || '';
                      var id = el.id ? '#' + el.id : '';
                      visible.push('[' + idx + '] <' + tag + id + (type ? ':' + type : '') + '> ' + (txt ? '"' + txt + '"' : '') + (href ? ' -> ' + href.slice(0, 60) : ''));
                    }
                    var bodyText = (document.body ? document.body.innerText : '') || '';
                    bodyText = bodyText.replace(/\n{3,}/g, '\n\n').trim().slice(0, ${maxTextChars.coerceIn(500, 14_000)});
                    return JSON.stringify({
                      url: location.href,
                      title: document.title || '',
                      scrollY: Math.round(window.scrollY || 0),
                      scrollHeight: Math.round((document.documentElement && document.documentElement.scrollHeight) || 0),
                      viewportH: Math.round(window.innerHeight || 0),
                      elements: visible,
                      text: bodyText
                    });
                  } catch (e) {
                    return JSON.stringify({ error: String(e) });
                  }
                })();
            """.trimIndent()
            val raw = evalJsInternal(script).getOrThrow()
            val consoleErrors = _consoleLogs.value.filter { it.contains("ERROR") }.takeLast(5)
            buildString {
                appendLine(raw)
                if (consoleErrors.isNotEmpty()) {
                    appendLine("\nRecent JS console errors:")
                    consoleErrors.forEach { appendLine("- $it") }
                }
            }
        }
    }

    suspend fun act(
        action: String,
        selector: String? = null,
        elementIndex: Int? = null,
        textMatch: String? = null,
        value: String? = null
    ): Result<String> = withSerializedAgentOp(agentOpMutex) {
        val actNorm = action.trim().lowercase()
        when (actNorm) {
            "back" -> return@withSerializedAgentOp goBackInternal(forAgent = true, waitFinish = true)
            "forward" -> return@withSerializedAgentOp goForwardInternal(forAgent = true, waitFinish = true)
            "reload" -> return@withSerializedAgentOp reloadInternal(forAgent = true)
        }
        runCatching {
            when (actNorm) {
                "wait" -> {
                    val ms = (value?.toLongOrNull() ?: 1000L).coerceIn(100L, 10_000L)
                    appendAction("Wait ${ms}ms")
                    delay(ms)
                    return@runCatching "Wait ${ms}ms complete (${_currentUrl.value})"
                }
                "scroll" -> {
                    val dir = (value ?: "down").trim().lowercase()
                    appendAction("Scroll: $dir")
                    val js = when (dir) {
                        "top" -> "window.scrollTo(0, 0); 'scrolled to top'"
                        "bottom" -> "window.scrollTo(0, document.body.scrollHeight); 'scrolled to bottom'"
                        "up" -> "window.scrollBy(0, -Math.max(420, window.innerHeight * 0.75)); 'scrolled up'"
                        "down" -> "window.scrollBy(0, Math.max(420, window.innerHeight * 0.75)); 'scrolled down'"
                        else -> {
                            val px = dir.toIntOrNull() ?: 500
                            "window.scrollBy(0, $px); 'scrolled $px px'"
                        }
                    }
                    val res = evalJsInternal(js).getOrThrow()
                    delay(200)
                    return@runCatching "$res (scroll: ${_currentUrl.value})"
                }
            }

            val payload = buildJsonObject {
                put("action", JsonPrimitive(actNorm))
                selector?.let { put("selector", JsonPrimitive(it)) }
                elementIndex?.let { put("index", JsonPrimitive(it)) }
                textMatch?.let { put("text", JsonPrimitive(it)) }
                value?.let { put("value", JsonPrimitive(it)) }
            }.toString()

            appendAction(
                when (actNorm) {
                    "click" -> "Click: ${selector ?: elementIndex?.let { "[$it]" } ?: textMatch ?: "?"}"
                    "type" -> "Type text into ${selector ?: elementIndex?.let { "[$it]" } ?: textMatch ?: "?"}"
                    "press_enter" -> "Submit (Enter) in ${selector ?: elementIndex?.let { "[$it]" } ?: textMatch ?: "active"}"
                    "select" -> "Select value '$value'"
                    else -> "Action $actNorm"
                }
            )

            val js = """
                (function(cfg) {
                  try {
                    var el = null;
                    if (typeof cfg.index === 'number') {
                      el = document.querySelector('[data-fm-agent-idx="' + cfg.index + '"]');
                    }
                    if (!el && cfg.selector) {
                      el = document.querySelector(cfg.selector);
                    }
                    if (!el && cfg.text) {
                      var q = cfg.text.toLowerCase();
                      var cands = document.querySelectorAll('a,button,input,textarea,select,[role="button"],label');
                      for (var i = 0; i < cands.length; i++) {
                        var c = cands[i];
                        var txt = ((c.innerText || c.value || c.getAttribute('aria-label') || c.getAttribute('placeholder') || '') + '').toLowerCase();
                        if (txt.indexOf(q) >= 0) { el = c; break; }
                      }
                    }
                    if (!el && cfg.action === 'press_enter') {
                      el = document.activeElement;
                    }
                    if (!el) return 'ERR: Element not found (' + JSON.stringify(cfg) + ')';
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    if (cfg.action === 'click') {
                      el.focus();
                      el.click();
                      return 'OK: click -> ' + el.tagName.toLowerCase();
                    }
                    if (cfg.action === 'type') {
                      var val = cfg.value || '';
                      el.focus();
                      var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                      var setter = proto && Object.getOwnPropertyDescriptor(proto, 'value') && Object.getOwnPropertyDescriptor(proto, 'value').set;
                      if (setter) setter.call(el, val); else el.value = val;
                      el.dispatchEvent(new Event('input', { bubbles: true }));
                      el.dispatchEvent(new Event('change', { bubbles: true }));
                      return 'OK: type (' + val.length + ' chars) -> ' + el.tagName.toLowerCase();
                    }
                    if (cfg.action === 'select') {
                      el.value = cfg.value || '';
                      el.dispatchEvent(new Event('change', { bubbles: true }));
                      return 'OK: select -> ' + el.value;
                    }
                    if (cfg.action === 'press_enter') {
                      el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                      el.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                      el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                      if (el.form) {
                        if (typeof el.form.requestSubmit === 'function') el.form.requestSubmit(); else el.form.submit();
                      }
                      return 'OK: press_enter';
                    }
                    return 'ERR: Unknown action ' + cfg.action;
                  } catch (e) {
                    return 'ERR: ' + String(e);
                  }
                })($payload);
            """.trimIndent()

            val result = evalJsInternal(js).getOrThrow()
            delay(450)
            withContext(Dispatchers.Main) {
                agentWebView?.url?.takeIf { it.isNotBlank() && it != "about:blank" }?.let { _currentUrl.value = it }
                agentWebView?.title?.let { _pageTitle.value = it }
            }
            if (result.startsWith("ERR:")) error(result)
            "$result | URL: ${_currentUrl.value}"
        }
    }

    suspend fun evalJs(script: String): Result<String> =
        withSerializedAgentOp(agentOpMutex) { evalJsInternal(script) }

    private suspend fun evalJsInternal(script: String): Result<String> = runCatching {
        withContext(Dispatchers.Main) {
            val wv = ensureAgentWebView()
            layoutIfDetached(wv)
            val raw = withTimeoutOrNull(10_000L) {
                suspendCancellableCoroutine<String> { cont ->
                    wv.evaluateJavascript(script) { value ->
                        if (cont.isActive) cont.resume(value ?: "null")
                    }
                }
            } ?: error("evaluateJavascript timeout")
            decodeJsResultString(raw)
        }
    }

    suspend fun captureScreenshot(outFile: File): Result<File> = withSerializedAgentOp(agentOpMutex) {
        runCatching {
            appendAction("Screenshot: ${outFile.name}")
            // Brief settle pause so preceding DOM / scroll mutations commit before drawing
            delay(75)
            withContext(Dispatchers.Main) {
                val wv = ensureAgentWebView()
                layoutIfDetached(wv)
                awaitVisualFrameOnMain(wv)
                val w = if (wv.width > 0) wv.width else 1080
                val h = if (wv.height > 0) wv.height else 1920
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bmp)
                    wv.draw(canvas)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 92, fos)
                        fos.flush()
                    }
                } finally {
                    bmp.recycle()
                }
                outFile
            }
        }
    }

    private suspend fun awaitVisualFrameOnMain(wv: WebView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val reqId = visualFrameCounter.getAndIncrement()
            withTimeoutOrNull(250L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    wv.postVisualStateCallback(reqId, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    })
                }
            }
        }
    }

    private fun layoutIfDetached(wv: WebView) {
        if (wv.width <= 0 || wv.height <= 0) {
            val wSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            wv.measure(wSpec, hSpec)
            wv.layout(0, 0, 1080, 1920)
        }
    }

    private fun appendAction(msg: String) {
        _lastActionBanner.value = msg
        val stamped = "[${timeFmt.format(Date())}] $msg"
        _actionLog.value = (_actionLog.value + stamped).takeLast(120)
    }

    companion object {
        internal suspend fun <T> withSerializedAgentOp(mutex: Mutex, block: suspend () -> T): T =
            mutex.withLock { block() }

        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return ""
            return when {
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) ||
                    trimmed.startsWith("about:", ignoreCase = true) -> trimmed
                trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
                else -> "https://duckduckgo.com/?q=${URLEncoder.encode(trimmed, "UTF-8")}"
            }
        }

        fun decodeJsResultString(raw: String): String {
            if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                return buildString(raw.length) {
                    var i = 1
                    val end = raw.length - 1
                    while (i < end) {
                        val c = raw[i]
                        if (c == '\\' && i + 1 < end) {
                            when (val nxt = raw[i + 1]) {
                                'n' -> { append('\n'); i += 2 }
                                'r' -> { append('\r'); i += 2 }
                                't' -> { append('\t'); i += 2 }
                                '\\' -> { append('\\'); i += 2 }
                                '"' -> { append('"'); i += 2 }
                                'u' -> if (i + 5 < end) {
                                    val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                                    if (code != null) {
                                        append(code.toChar())
                                        i += 6
                                    } else {
                                        append(c); i++
                                    }
                                } else {
                                    append(c); i++
                                }
                                else -> { append(nxt); i += 2 }
                            }
                        } else {
                            append(c)
                            i++
                        }
                    }
                }
            }
            return raw
        }
    }
}
