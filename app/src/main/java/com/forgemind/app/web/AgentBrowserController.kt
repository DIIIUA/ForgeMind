package com.forgemind.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import kotlin.coroutines.resume

class AgentBrowserController(private val appContext: Context) {

    private val _agentModeEnabled = MutableStateFlow(false)
    val agentModeEnabled: StateFlow<Boolean> = _agentModeEnabled.asStateFlow()

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadProgress = MutableStateFlow(0)
    val loadProgress: StateFlow<Int> = _loadProgress.asStateFlow()

    private val _lastActionBanner = MutableStateFlow<String?>(null)
    val lastActionBanner: StateFlow<String?> = _lastActionBanner.asStateFlow()

    private val _actionLog = MutableStateFlow<List<String>>(emptyList())
    val actionLog: StateFlow<List<String>> = _actionLog.asStateFlow()

    private val _consoleLogs = MutableStateFlow<List<String>>(emptyList())
    val consoleLogs: StateFlow<List<String>> = _consoleLogs.asStateFlow()

    private var webView: WebView? = null

    @Volatile
    private var pageLoadWaiter: CompletableDeferred<String>? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setAgentMode(enabled: Boolean) {
        _agentModeEnabled.value = enabled
        appendAction(if (enabled) "Режим Агентного браузера включён" else "Переключён в ручной режим")
    }

    fun clearLogs() {
        _consoleLogs.value = emptyList()
    }

    fun clearActionLog() {
        _actionLog.value = emptyList()
        _lastActionBanner.value = null
    }

    fun resetToHome() {
        _currentUrl.value = ""
        _pageTitle.value = ""
        _isLoading.value = false
        _loadProgress.value = 0
        appendAction("Возврат на стартовую страницу")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(context: Context = appContext): WebView {
        webView?.let { return it }
        val wv = WebView(appContext).apply {
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
                    _isLoading.value = true
                    if (!url.isNullOrBlank()) {
                        _currentUrl.value = url
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    _isLoading.value = false
                    if (!url.isNullOrBlank()) {
                        _currentUrl.value = url
                    }
                    _pageTitle.value = view?.title.orEmpty()
                    pageLoadWaiter?.complete(url.orEmpty())
                    pageLoadWaiter = null
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    _loadProgress.value = newProgress
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    _pageTitle.value = title.orEmpty()
                }

                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                    if (cm != null) {
                        val line = "[${cm.messageLevel()}] ${cm.message()} (:${cm.lineNumber()})"
                        _consoleLogs.value = (_consoleLogs.value + line).takeLast(150)
                    }
                    return true
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    appendAction("JS alert: ${message.orEmpty().take(100)}")
                    result?.confirm()
                    return true
                }
            }
        }
        webView = wv
        return wv
    }

    fun acquireAttachedWebView(urlToLoadIfBlank: String? = null): WebView {
        val wv = ensureWebView()
        (wv.parent as? ViewGroup)?.removeView(wv)
        val cur = _currentUrl.value.trim()
        if (!urlToLoadIfBlank.isNullOrBlank() && wv.url.isNullOrBlank() && cur.isNotBlank()) {
            wv.loadUrl(cur)
        }
        return wv
    }

    fun releaseAttachedParent() {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
    }

    suspend fun navigate(
        rawUrl: String,
        enableAgentMode: Boolean = true,
        waitFinishMs: Long = 15_000L
    ): Result<String> = runCatching {
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isBlank()) error("Пустой URL")
        if (enableAgentMode) {
            _agentModeEnabled.value = true
        }
        val waiter = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            val wv = ensureWebView()
            layoutIfDetached(wv)
            pageLoadWaiter?.cancel()
            pageLoadWaiter = waiter
            _currentUrl.value = normalized
            appendAction("Переход: $normalized")
            wv.loadUrl(normalized)
        }
        withTimeoutOrNull(waitFinishMs.coerceIn(1000L, 30_000L)) {
            waiter.await()
        }
        delay(250)
        val summary = withContext(Dispatchers.Main) {
            val wv = ensureWebView()
            val u = wv.url ?: _currentUrl.value
            val t = wv.title.orEmpty()
            _currentUrl.value = u
            _pageTitle.value = t
            "Открыто в агентном браузере: $u" + if (t.isNotBlank()) " — «$t»" else ""
        }
        summary
    }

    suspend fun navigateFromUser(rawUrl: String) {
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isBlank()) return
        withContext(Dispatchers.Main) {
            val wv = ensureWebView()
            _currentUrl.value = normalized
            wv.loadUrl(normalized)
        }
    }

    suspend fun goBack(waitFinish: Boolean = false): Result<String> = runCatching {
        withContext(Dispatchers.Main) {
            val wv = ensureWebView()
            if (!wv.canGoBack()) error("История «Назад» пуста")
            appendAction("Назад по истории")
            wv.goBack()
        }
        if (waitFinish) delay(450)
        "Назад -> ${_currentUrl.value}"
    }

    suspend fun goForward(waitFinish: Boolean = false): Result<String> = runCatching {
        withContext(Dispatchers.Main) {
            val wv = ensureWebView()
            if (!wv.canGoForward()) error("История «Вперёд» пуста")
            appendAction("Вперёд по истории")
            wv.goForward()
        }
        if (waitFinish) delay(450)
        "Вперёд -> ${_currentUrl.value}"
    }

    suspend fun reload(waitFinishMs: Long = 10_000L): Result<String> = runCatching {
        val waiter = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            val wv = ensureWebView()
            pageLoadWaiter?.cancel()
            pageLoadWaiter = waiter
            appendAction("Обновление страницы")
            wv.reload()
        }
        withTimeoutOrNull(waitFinishMs) { waiter.await() }
        "Страница обновлена: ${_currentUrl.value}"
    }

    suspend fun inspect(maxTextChars: Int = 5500): Result<String> = runCatching {
        appendAction("Чтение DOM и интерактивных элементов")
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
        val raw = evalJs(script).getOrThrow()
        val consoleErrors = _consoleLogs.value.filter { it.contains("ERROR") }.takeLast(5)
        buildString {
            appendLine(raw)
            if (consoleErrors.isNotEmpty()) {
                appendLine("\nПоследние ошибки JS-консоли:")
                consoleErrors.forEach { appendLine("- $it") }
            }
        }
    }

    suspend fun act(
        action: String,
        selector: String? = null,
        elementIndex: Int? = null,
        textMatch: String? = null,
        value: String? = null
    ): Result<String> = runCatching {
        val actNorm = action.trim().lowercase()
        _agentModeEnabled.value = true
        when (actNorm) {
            "back" -> return goBack(waitFinish = true)
            "forward" -> return goForward(waitFinish = true)
            "reload" -> return reload()
            "wait" -> {
                val ms = (value?.toLongOrNull() ?: 1000L).coerceIn(100L, 10_000L)
                appendAction("Пауза ${ms}мс")
                delay(ms)
                return@runCatching "Ожидание ${ms}мс завершено (${_currentUrl.value})"
            }
            "scroll" -> {
                val dir = (value ?: "down").trim().lowercase()
                appendAction("Прокрутка: $dir")
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
                val res = evalJs(js).getOrThrow()
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
                "click" -> "Клик: ${selector ?: elementIndex?.let { "[$it]" } ?: textMatch ?: "?"}"
                "type" -> "Ввод текста в ${selector ?: elementIndex?.let { "[$it]" } ?: textMatch ?: "?"}"
                "press_enter" -> "Отправка (Enter) в ${selector ?: elementIndex?.let { "[$it]" } ?: textMatch ?: "active"}"
                "select" -> "Выбор значения '$value'"
                else -> "Действие $actNorm"
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
                if (!el) return 'ERR: Элемент не найден (' + JSON.stringify(cfg) + ')';
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
                return 'ERR: Неизвестное действие ' + cfg.action;
              } catch (e) {
                return 'ERR: ' + String(e);
              }
            })($payload);
        """.trimIndent()

        val result = evalJs(js).getOrThrow()
        delay(450)
        withContext(Dispatchers.Main) {
            webView?.url?.takeIf { it.isNotBlank() }?.let { _currentUrl.value = it }
            webView?.title?.let { _pageTitle.value = it }
        }
        if (result.startsWith("ERR:")) error(result)
        "$result | URL: ${_currentUrl.value}"
    }

    suspend fun evalJs(script: String): Result<String> = runCatching {
        withContext(Dispatchers.Main) {
            val wv = ensureWebView()
            layoutIfDetached(wv)
            val raw = withTimeoutOrNull(10_000L) {
                suspendCancellableCoroutine<String> { cont ->
                    wv.evaluateJavascript(script) { value ->
                        if (cont.isActive) cont.resume(value ?: "null")
                    }
                }
            } ?: error("Таймаут evaluateJavascript")
            decodeJsResultString(raw)
        }
    }

    suspend fun captureScreenshot(outFile: File): Result<File> = runCatching {
        appendAction("Скриншот: ${outFile.name}")
        withContext(Dispatchers.Main) {
            val wv = ensureWebView()
            layoutIfDetached(wv)
            val w = wv.width.coerceAtLeast(1080)
            val h = wv.height.coerceAtLeast(1920)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            wv.draw(canvas)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 92, fos)
                fos.flush()
            }
            bmp.recycle()
            outFile
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
