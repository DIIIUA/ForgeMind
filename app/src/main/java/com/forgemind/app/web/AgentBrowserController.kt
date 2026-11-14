package com.forgemind.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

data class AgentBrowserTabInfo(
    val id: String,
    val url: String,
    val title: String,
    val isLoading: Boolean,
    val progress: Int,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val isActive: Boolean
)

data class BrowserBatchStep(
    val action: String,
    val url: String? = null,
    val index: Int? = null,
    val selector: String? = null,
    val text: String? = null,
    val value: String? = null,
    val script: String? = null,
    val fields: Map<String, String>? = null,
    val submit: Boolean = false,
    val timeoutMs: Long? = null,
    val filename: String? = null,
    val attachToChat: Boolean = true,
    val tabId: String? = null,
    val newTab: Boolean = false
)

data class BrowserBatchOutcome(
    val summaryText: String,
    val capturedFiles: List<Pair<File, Boolean>>,
    val hadError: Boolean
)

class AgentBrowserController(private val appContext: Context) {

    private class AgentTabSession(
        val id: String,
        initialUrl: String = "",
        initialTitle: String = ""
    ) {
        val opMutex = Mutex()
        val url = MutableStateFlow(initialUrl)
        val title = MutableStateFlow(initialTitle)
        val isLoading = MutableStateFlow(false)
        val progress = MutableStateFlow(0)
        val canGoBack = MutableStateFlow(false)
        val canGoForward = MutableStateFlow(false)
        val consoleLogs = MutableStateFlow<List<String>>(emptyList())

        @Volatile
        var webView: WebView? = null

        @Volatile
        var loadWaiter: CompletableDeferred<String>? = null
    }

    private val prefs: SharedPreferences? = runCatching {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.getOrNull()

    // User toggle in UI: false = isolated User browser, true = Live Agent view.
    private val _agentModeEnabled = MutableStateFlow(
        runCatching { prefs?.getBoolean(KEY_AGENT_MODE, false) ?: false }.getOrDefault(false)
    )
    val agentModeEnabled: StateFlow<Boolean> = _agentModeEnabled.asStateFlow()

    // Shared persistent AI + User Turbo toggle (tracker/font/video blocking + 0s CSS transitions + fast DOM settle)
    private val _turboModeEnabled = MutableStateFlow(
        runCatching { prefs?.getBoolean(KEY_TURBO_MODE, false) ?: false }.getOrDefault(false)
    )
    val turboModeEnabled: StateFlow<Boolean> = _turboModeEnabled.asStateFlow()

    @Volatile
    private var blockImagesPreferenceInTurbo: Boolean = _turboModeEnabled.value

    private val activeScreenshotCaptures = AtomicInteger(0)

    // --- Multi-Tab Isolated User Browser state ---
    private val userTabsLock = Any()
    private val userTabSessions = LinkedHashMap<String, AgentTabSession>()
    private val nextUserTabNumber = AtomicInteger(1)

    private val _activeUserTabId = MutableStateFlow("user_1")
    val activeUserTabId: StateFlow<String> = _activeUserTabId.asStateFlow()

    private val _userTabs = MutableStateFlow<List<AgentBrowserTabInfo>>(emptyList())
    val userTabs: StateFlow<List<AgentBrowserTabInfo>> = _userTabs.asStateFlow()

    // Mirrors of the currently active User tab for seamless UI binding
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

    // --- Multi-Tab Background Agent Browser state ---
    private val tabsLock = Any()
    private val tabSessions = LinkedHashMap<String, AgentTabSession>()
    private val nextTabNumber = AtomicInteger(1)

    private val _activeAgentTabId = MutableStateFlow("tab_1")
    val activeAgentTabId: StateFlow<String> = _activeAgentTabId.asStateFlow()

    private val _agentTabs = MutableStateFlow<List<AgentBrowserTabInfo>>(emptyList())
    val agentTabs: StateFlow<List<AgentBrowserTabInfo>> = _agentTabs.asStateFlow()

    // Mirrors of the currently active AI tab for seamless UI binding
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

    private val visualFrameCounter = AtomicLong(1L)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        restorePersistedTabs()
        restorePersistedUserTabs()
    }

    private fun shouldCurrentlyBlockImages(): Boolean =
        _turboModeEnabled.value &&
            blockImagesPreferenceInTurbo &&
            !_agentModeEnabled.value &&
            activeScreenshotCaptures.get() <= 0

    fun setAgentMode(enabled: Boolean) {
        _agentModeEnabled.value = enabled
        runCatching { prefs?.edit()?.putBoolean(KEY_AGENT_MODE, enabled)?.apply() }
        applyImageGateToAllAgentWebViews()
    }

    fun setTurboMode(enabled: Boolean, blockImages: Boolean = enabled) {
        val changed = _turboModeEnabled.value != enabled
        _turboModeEnabled.value = enabled
        blockImagesPreferenceInTurbo = enabled && blockImages
        runCatching { prefs?.edit()?.putBoolean(KEY_TURBO_MODE, enabled)?.apply() }
        if (changed) {
            appendAction(if (enabled) "⚡ Turbo mode ON" else "⚡ Turbo mode OFF")
        }
        applyImageGateToAllAgentWebViews()
    }

    private fun applyImageGateToAllAgentWebViews() {
        val blockImgs = shouldCurrentlyBlockImages()
        val sessionsSnapshot = synchronized(tabsLock) { tabSessions.values.toList() }
        runOnMainBestEffort {
            for (s in sessionsSnapshot) {
                s.webView?.settings?.apply {
                    blockNetworkImage = blockImgs
                    loadsImagesAutomatically = !blockImgs
                }
            }
        }
    }

    private fun restorePersistedTabs() {
        val savedJson = runCatching { prefs?.getString(KEY_AGENT_TABS_JSON, null) }.getOrNull()
        val savedActive = runCatching { prefs?.getString(KEY_ACTIVE_AGENT_TAB, null) }.getOrNull()
        val legacyUrl = runCatching { prefs?.getString(KEY_LAST_AGENT_URL, "").orEmpty() }.getOrDefault("")

        synchronized(tabsLock) {
            tabSessions.clear()
            if (!savedJson.isNullOrBlank()) {
                runCatching {
                    val arr = Json.parseToJsonElement(savedJson) as? JsonArray
                    arr?.forEach { item ->
                        if (tabSessions.size >= MAX_AGENT_TABS) return@forEach
                        val obj = item as? JsonObject ?: return@forEach
                        val rawId = (obj["id"] as? JsonPrimitive)?.contentOrNull
                        val id = sanitizeTabId(rawId) ?: return@forEach
                        val u = (obj["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            .let { if (it == "about:blank") "" else it.trim() }
                        val t = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        tabSessions[id] = AgentTabSession(id = id, initialUrl = u, initialTitle = t)
                        bumpTabSequenceFromId(id)
                    }
                }
            }
            if (tabSessions.isEmpty()) {
                val cleanLegacy = if (legacyUrl == "about:blank") "" else legacyUrl.trim()
                tabSessions["tab_1"] = AgentTabSession(id = "tab_1", initialUrl = cleanLegacy)
                nextTabNumber.set(2)
            }
            val chosenActive = if (savedActive != null && tabSessions.containsKey(savedActive)) {
                savedActive
            } else {
                tabSessions.keys.first()
            }
            _activeAgentTabId.value = chosenActive
            syncActiveAndTabListLocked(persist = false)
        }
    }

    private fun bumpTabSequenceFromId(id: String) {
        val num = id.removePrefix("tab_").toIntOrNull() ?: return
        while (true) {
            val cur = nextTabNumber.get()
            if (num < cur || nextTabNumber.compareAndSet(cur, num + 1)) break
        }
    }

    private fun allocateFreshTabIdLocked(): String {
        while (true) {
            val candidate = "tab_${nextTabNumber.getAndIncrement()}"
            if (!tabSessions.containsKey(candidate)) return candidate
        }
    }

    private fun syncActiveAndTabListLocked(persist: Boolean = true) {
        if (tabSessions.isEmpty()) {
            val fallbackId = allocateFreshTabIdLocked()
            tabSessions[fallbackId] = AgentTabSession(fallbackId)
            _activeAgentTabId.value = fallbackId
        }
        var activeId = _activeAgentTabId.value
        if (!tabSessions.containsKey(activeId)) {
            activeId = tabSessions.keys.last()
            _activeAgentTabId.value = activeId
        }
        val activeSession = tabSessions.getValue(activeId)
        _currentUrl.value = activeSession.url.value
        _pageTitle.value = activeSession.title.value
        _isLoading.value = activeSession.isLoading.value
        _loadProgress.value = activeSession.progress.value
        _agentCanGoBack.value = activeSession.canGoBack.value
        _agentCanGoForward.value = activeSession.canGoForward.value
        _consoleLogs.value = activeSession.consoleLogs.value

        val snapshot = tabSessions.values.map { s ->
            AgentBrowserTabInfo(
                id = s.id,
                url = s.url.value,
                title = s.title.value,
                isLoading = s.isLoading.value,
                progress = s.progress.value,
                canGoBack = s.canGoBack.value,
                canGoForward = s.canGoForward.value,
                isActive = s.id == activeId
            )
        }
        _agentTabs.value = snapshot

        if (persist) {
            persistTabsBestEffort(snapshot, activeId, activeSession.url.value)
        }
    }

    private fun persistTabsBestEffort(
        tabs: List<AgentBrowserTabInfo>,
        activeId: String,
        activeUrl: String
    ) {
        val p = prefs ?: return
        runCatching {
            val jsonStr = buildJsonArray {
                tabs.forEach { t ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(t.id))
                            put("url", JsonPrimitive(if (t.url == "about:blank") "" else t.url))
                            put("title", JsonPrimitive(t.title.take(140)))
                        }
                    )
                }
            }.toString()
            val cleanActiveUrl = if (activeUrl == "about:blank") "" else activeUrl.trim()
            p.edit()
                .putString(KEY_AGENT_TABS_JSON, jsonStr)
                .putString(KEY_ACTIVE_AGENT_TAB, activeId)
                .putString(KEY_LAST_AGENT_URL, cleanActiveUrl)
                .apply()
        }
    }

    private fun onSessionStateMutated(persist: Boolean = false) {
        synchronized(tabsLock) {
            syncActiveAndTabListLocked(persist = persist)
        }
    }

    private fun restorePersistedUserTabs() {
        val savedJson = runCatching { prefs?.getString(KEY_USER_TABS_JSON, null) }.getOrNull()
        val savedActive = runCatching { prefs?.getString(KEY_ACTIVE_USER_TAB, null) }.getOrNull()

        synchronized(userTabsLock) {
            userTabSessions.clear()
            if (!savedJson.isNullOrBlank()) {
                runCatching {
                    val arr = Json.parseToJsonElement(savedJson) as? JsonArray
                    arr?.forEach { item ->
                        if (userTabSessions.size >= MAX_USER_TABS) return@forEach
                        val obj = item as? JsonObject ?: return@forEach
                        val rawId = (obj["id"] as? JsonPrimitive)?.contentOrNull
                        val id = sanitizeTabId(rawId) ?: return@forEach
                        val u = (obj["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            .let { if (it == "about:blank") "" else it.trim() }
                        val t = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        userTabSessions[id] = AgentTabSession(id = id, initialUrl = u, initialTitle = t)
                        bumpUserTabSequenceFromId(id)
                    }
                }
            }
            if (userTabSessions.isEmpty()) {
                userTabSessions["user_1"] = AgentTabSession(id = "user_1")
                nextUserTabNumber.set(2)
            }
            val chosenActive = if (savedActive != null && userTabSessions.containsKey(savedActive)) {
                savedActive
            } else {
                userTabSessions.keys.first()
            }
            _activeUserTabId.value = chosenActive
            syncActiveUserTabAndListLocked(persist = false)
        }
    }

    private fun bumpUserTabSequenceFromId(id: String) {
        val num = id.removePrefix("user_").toIntOrNull() ?: return
        while (true) {
            val cur = nextUserTabNumber.get()
            if (num < cur || nextUserTabNumber.compareAndSet(cur, num + 1)) break
        }
    }

    private fun allocateFreshUserTabIdLocked(): String {
        while (true) {
            val candidate = "user_${nextUserTabNumber.getAndIncrement()}"
            if (!userTabSessions.containsKey(candidate)) return candidate
        }
    }

    private fun syncActiveUserTabAndListLocked(persist: Boolean = true) {
        if (userTabSessions.isEmpty()) {
            val fallbackId = allocateFreshUserTabIdLocked()
            userTabSessions[fallbackId] = AgentTabSession(fallbackId)
            _activeUserTabId.value = fallbackId
        }
        var activeId = _activeUserTabId.value
        if (!userTabSessions.containsKey(activeId)) {
            activeId = userTabSessions.keys.last()
            _activeUserTabId.value = activeId
        }
        val activeSession = userTabSessions.getValue(activeId)
        _userCurrentUrl.value = activeSession.url.value
        _userPageTitle.value = activeSession.title.value
        _userIsLoading.value = activeSession.isLoading.value
        _userLoadProgress.value = activeSession.progress.value
        _userCanGoBack.value = activeSession.canGoBack.value
        _userCanGoForward.value = activeSession.canGoForward.value
        _userConsoleLogs.value = activeSession.consoleLogs.value

        val snapshot = userTabSessions.values.map { s ->
            AgentBrowserTabInfo(
                id = s.id,
                url = s.url.value,
                title = s.title.value,
                isLoading = s.isLoading.value,
                progress = s.progress.value,
                canGoBack = s.canGoBack.value,
                canGoForward = s.canGoForward.value,
                isActive = s.id == activeId
            )
        }
        _userTabs.value = snapshot

        if (persist) {
            persistUserTabsBestEffort(snapshot, activeId)
        }
    }

    private fun persistUserTabsBestEffort(
        tabs: List<AgentBrowserTabInfo>,
        activeId: String
    ) {
        val p = prefs ?: return
        runCatching {
            val jsonStr = buildJsonArray {
                tabs.forEach { t ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(t.id))
                            put("url", JsonPrimitive(if (t.url == "about:blank") "" else t.url))
                            put("title", JsonPrimitive(t.title.take(140)))
                        }
                    )
                }
            }.toString()
            p.edit()
                .putString(KEY_USER_TABS_JSON, jsonStr)
                .putString(KEY_ACTIVE_USER_TAB, activeId)
                .apply()
        }
    }

    private fun onUserSessionStateMutated(persist: Boolean = false) {
        synchronized(userTabsLock) {
            syncActiveUserTabAndListLocked(persist = persist)
        }
    }

    private fun flushCookiesBestEffort() {
        runCatching { CookieManager.getInstance().flush() }
    }

    fun clearUserLogs(tabId: String? = null) {
        synchronized(userTabsLock) {
            val targetId = sanitizeTabId(tabId) ?: _activeUserTabId.value
            userTabSessions[targetId]?.consoleLogs?.value = emptyList()
            syncActiveUserTabAndListLocked(persist = false)
        }
    }

    fun clearLogs(tabId: String? = null) {
        synchronized(tabsLock) {
            val targetId = sanitizeTabId(tabId) ?: _activeAgentTabId.value
            tabSessions[targetId]?.consoleLogs?.value = emptyList()
            syncActiveAndTabListLocked(persist = false)
        }
    }

    fun clearActionLog() {
        _actionLog.value = emptyList()
        _lastActionBanner.value = null
    }

    fun resetToHome() = resetUserToHome()

    fun resetUserToHome(tabId: String? = null) {
        val targetSession = synchronized(userTabsLock) {
            syncActiveUserTabAndListLocked(persist = false)
            val resolvedId = sanitizeTabId(tabId)?.takeIf { userTabSessions.containsKey(it) } ?: _activeUserTabId.value
            val s = userTabSessions[resolvedId] ?: return
            s.url.value = ""
            s.title.value = ""
            s.isLoading.value = false
            s.progress.value = 0
            s.canGoBack.value = false
            s.canGoForward.value = false
            syncActiveUserTabAndListLocked(persist = true)
            s
        }
        pauseAndBlankOnMain(targetSession.webView, pauseJs = true)
    }

    // ---- User Browser Multi-Tab Management API ----

    fun createOrSelectUserTab(
        requestedTabId: String? = null,
        initialUrl: String = "",
        makeActive: Boolean = true
    ): String {
        var evictedWebView: WebView? = null
        val chosenId = synchronized(userTabsLock) {
            val cleanReq = sanitizeTabId(requestedTabId)
            if (cleanReq != null && userTabSessions.containsKey(cleanReq)) {
                if (makeActive) _activeUserTabId.value = cleanReq
                syncActiveUserTabAndListLocked(persist = true)
                return cleanReq
            }
            if (userTabSessions.size >= MAX_USER_TABS) {
                val activeId = _activeUserTabId.value
                val victimKey = userTabSessions.keys.firstOrNull { it != activeId } ?: userTabSessions.keys.first()
                evictedWebView = userTabSessions.remove(victimKey)?.webView
            }
            val newId = cleanReq ?: allocateFreshUserTabIdLocked()
            bumpUserTabSequenceFromId(newId)
            val cleanInitUrl = if (initialUrl == "about:blank") "" else initialUrl.trim()
            userTabSessions[newId] = AgentTabSession(id = newId, initialUrl = cleanInitUrl)
            if (makeActive) {
                _activeUserTabId.value = newId
            }
            syncActiveUserTabAndListLocked(persist = true)
            newId
        }
        destroyWebViewOnMain(evictedWebView)
        return chosenId
    }

    fun switchUserTab(tabId: String): Boolean {
        val clean = sanitizeTabId(tabId) ?: return false
        return synchronized(userTabsLock) {
            if (!userTabSessions.containsKey(clean)) return@synchronized false
            _activeUserTabId.value = clean
            syncActiveUserTabAndListLocked(persist = true)
            true
        }
    }

    fun closeUserTab(tabId: String) {
        val clean = sanitizeTabId(tabId) ?: return
        var toDestroy: WebView? = null
        synchronized(userTabsLock) {
            val existing = userTabSessions[clean] ?: return
            if (userTabSessions.size == 1) {
                existing.url.value = ""
                existing.title.value = ""
                existing.isLoading.value = false
                existing.progress.value = 0
                existing.canGoBack.value = false
                existing.canGoForward.value = false
                existing.consoleLogs.value = emptyList()
                syncActiveUserTabAndListLocked(persist = true)
                pauseAndBlankOnMain(existing.webView, pauseJs = true)
                return
            }
            userTabSessions.remove(clean)
            toDestroy = existing.webView
            if (_activeUserTabId.value == clean) {
                _activeUserTabId.value = userTabSessions.keys.last()
            }
            syncActiveUserTabAndListLocked(persist = true)
        }
        destroyWebViewOnMain(toDestroy)
    }

    fun resetAgentBrowser(tabId: String? = null) {
        val targetSession = synchronized(tabsLock) {
            val resolvedId = sanitizeTabId(tabId) ?: _activeAgentTabId.value
            val s = tabSessions[resolvedId] ?: return
            s.url.value = ""
            s.title.value = ""
            s.isLoading.value = false
            s.progress.value = 0
            s.canGoBack.value = false
            s.canGoForward.value = false
            syncActiveAndTabListLocked(persist = true)
            s
        }
        appendAction("[${targetSession.id}] Reset to start page")
        pauseAndBlankOnMain(targetSession.webView, pauseJs = false)
    }

    // ---- Multi-Tab Management API ----

    fun createOrSelectTab(
        requestedTabId: String? = null,
        initialUrl: String = "",
        makeActive: Boolean = true,
        recordAction: Boolean = true
    ): String {
        var evictedWebView: WebView? = null
        val chosenId = synchronized(tabsLock) {
            val cleanReq = sanitizeTabId(requestedTabId)
            if (cleanReq != null && tabSessions.containsKey(cleanReq)) {
                if (makeActive) _activeAgentTabId.value = cleanReq
                syncActiveAndTabListLocked(persist = true)
                return cleanReq
            }
            if (tabSessions.size >= MAX_AGENT_TABS) {
                val activeId = _activeAgentTabId.value
                val victimKey = tabSessions.keys.firstOrNull { it != activeId } ?: tabSessions.keys.first()
                evictedWebView = tabSessions.remove(victimKey)?.webView
            }
            val newId = cleanReq ?: allocateFreshTabIdLocked()
            bumpTabSequenceFromId(newId)
            val cleanInitUrl = if (initialUrl == "about:blank") "" else initialUrl.trim()
            tabSessions[newId] = AgentTabSession(id = newId, initialUrl = cleanInitUrl)
            if (makeActive) {
                _activeAgentTabId.value = newId
            }
            syncActiveAndTabListLocked(persist = true)
            newId
        }
        destroyWebViewOnMain(evictedWebView)
        if (recordAction) {
            appendAction("Opened tab [$chosenId]")
        }
        return chosenId
    }

    fun switchAgentTab(tabId: String): Boolean {
        val clean = sanitizeTabId(tabId) ?: return false
        val switched = synchronized(tabsLock) {
            if (!tabSessions.containsKey(clean)) return@synchronized false
            _activeAgentTabId.value = clean
            syncActiveAndTabListLocked(persist = true)
            true
        }
        if (switched) {
            appendAction("Switched active tab -> [$clean]")
        }
        return switched
    }

    fun closeAgentTab(tabId: String): Result<String> = runCatching {
        val clean = sanitizeTabId(tabId) ?: error("Invalid tab_id '$tabId'")
        var toDestroy: WebView? = null
        val summary = synchronized(tabsLock) {
            val existing = tabSessions[clean] ?: error("Tab '$clean' not found")
            if (tabSessions.size == 1) {
                // Keep one clean tab ready
                existing.url.value = ""
                existing.title.value = ""
                existing.isLoading.value = false
                existing.progress.value = 0
                existing.canGoBack.value = false
                existing.canGoForward.value = false
                existing.consoleLogs.value = emptyList()
                syncActiveAndTabListLocked(persist = true)
                pauseAndBlankOnMain(existing.webView, pauseJs = false)
                return@synchronized "Reset last remaining tab [$clean] to blank."
            }
            tabSessions.remove(clean)
            toDestroy = existing.webView
            if (_activeAgentTabId.value == clean) {
                _activeAgentTabId.value = tabSessions.keys.last()
            }
            syncActiveAndTabListLocked(persist = true)
            "Closed tab [$clean] (active is now [${_activeAgentTabId.value}], ${tabSessions.size} open)."
        }
        destroyWebViewOnMain(toDestroy)
        appendAction(summary)
        summary
    }

    fun closeOtherAgentTabs(keepTabId: String? = null): String {
        val toDestroy = mutableListOf<WebView?>()
        val msg = synchronized(tabsLock) {
            val keep = sanitizeTabId(keepTabId)?.takeIf { tabSessions.containsKey(it) } ?: _activeAgentTabId.value
            val dropKeys = tabSessions.keys.filter { it != keep }
            for (k in dropKeys) {
                toDestroy += tabSessions.remove(k)?.webView
            }
            _activeAgentTabId.value = keep
            syncActiveAndTabListLocked(persist = true)
            "Closed ${dropKeys.size} background tab(s); kept [$keep]."
        }
        toDestroy.forEach { destroyWebViewOnMain(it) }
        appendAction(msg)
        return msg
    }

    fun closeAllAgentTabs(): String {
        val toDestroy = mutableListOf<WebView?>()
        synchronized(tabsLock) {
            tabSessions.values.forEach { toDestroy += it.webView }
            tabSessions.clear()
            nextTabNumber.set(1)
            val fresh = allocateFreshTabIdLocked()
            tabSessions[fresh] = AgentTabSession(fresh)
            _activeAgentTabId.value = fresh
            syncActiveAndTabListLocked(persist = true)
        }
        toDestroy.forEach { destroyWebViewOnMain(it) }
        val msg = "Closed all background tabs and reset to [tab_1]."
        appendAction(msg)
        return msg
    }

    fun listTabsSummary(): String {
        val tabs = _agentTabs.value
        val turbo = _turboModeEnabled.value
        val live = _agentModeEnabled.value
        return buildString {
            appendLine(
                "Agent Browser Tabs (${tabs.size}/$MAX_AGENT_TABS, active=[${_activeAgentTabId.value}], " +
                    "⚡ Turbo=${if (turbo) "ON" else "off"}, UserWatchingLive=$live, persisted across restarts):"
            )
            tabs.forEach { t ->
                val activeMark = if (t.isActive) " *ACTIVE*" else ""
                val stateStr = if (t.isLoading) "loading ${t.progress}%" else "ready"
                val urlStr = t.url.ifBlank { "(blank)" }
                val titleStr = if (t.title.isNotBlank()) " \"${t.title.take(70)}\"" else ""
                appendLine("- [${t.id}$activeMark]$titleStr — $urlStr ($stateStr)")
            }
        }.trimEnd()
    }

    suspend fun openParallelTabs(
        urls: List<String>,
        tabIds: List<String>? = null,
        waitFinishMs: Long = 15_000L,
        turbo: Boolean? = null,
        includeInspect: Boolean = true,
        includeMarkdown: Boolean = false,
        maxCharsPerTab: Int = 3500
    ): Result<String> = runCatching {
        val cleanUrls = urls.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_AGENT_TABS)
        if (cleanUrls.isEmpty()) error("urls list is empty")
        if (turbo != null) {
            setTurboMode(turbo)
        }
        val cappedPerTab = maxCharsPerTab.coerceIn(120, 25_000)

        // Reuse initial blank active tab for the first URL if no explicit tab_id was requested and it is blank
        val allocatedTargets = cleanUrls.mapIndexed { idx, rawUrl ->
            val explicitId = tabIds?.getOrNull(idx)?.let { sanitizeTabId(it) }
            val reuseBlankActive = idx == 0 && explicitId == null && synchronized(tabsLock) {
                val cur = tabSessions[_activeAgentTabId.value]
                cur != null && cur.url.value.isBlank() && tabSessions.size == 1
            }
            val tid = if (reuseBlankActive) {
                _activeAgentTabId.value
            } else {
                createOrSelectTab(
                    requestedTabId = explicitId,
                    initialUrl = "",
                    makeActive = idx == 0,
                    recordAction = false
                )
            }
            tid to rawUrl
        }

        val t0 = System.currentTimeMillis()
        appendAction("Parallel open: ${allocatedTargets.size} tabs (turbo=${_turboModeEnabled.value})")

        val perTabOutputs = coroutineScope {
            allocatedTargets.map { (tid, rawUrl) ->
                async {
                    val res = navigate(
                        rawUrl = rawUrl,
                        enableAgentMode = false,
                        waitFinishMs = waitFinishMs,
                        turbo = null,
                        includeInspect = includeInspect,
                        includeMarkdown = includeMarkdown,
                        maxChars = cappedPerTab,
                        tabId = tid,
                        newTab = false,
                        switchActiveOnNavigate = false
                    )
                    tid to res
                }
            }.awaitAll()
        }

        val totalMs = System.currentTimeMillis() - t0
        buildString {
            appendLine(
                "Opened ${perTabOutputs.size} tabs in parallel in ${totalMs}ms " +
                    "(⚡ Turbo=${if (_turboModeEnabled.value) "ON" else "off"}, active=[${_activeAgentTabId.value}]):"
            )
            for ((tid, res) in perTabOutputs) {
                appendLine()
                appendLine("=== TAB [$tid] ===")
                res.fold(
                    onSuccess = { appendLine(it) },
                    onFailure = { appendLine("ERROR: ${it.message}") }
                )
            }
        }.trim()
    }

    suspend fun inspectAllTabs(
        format: String = "default",
        maxCharsPerTab: Int = 3500
    ): Result<String> = runCatching {
        val openSessions = synchronized(tabsLock) {
            tabSessions.values.filter { it.url.value.isNotBlank() }
        }
        if (openSessions.isEmpty()) {
            return@runCatching "No non-blank Agent Browser tabs are currently open.\n${listTabsSummary()}"
        }
        val isMd = format.trim().equals("markdown", ignoreCase = true) ||
            format.trim().equals("md", ignoreCase = true)
        val cappedPerTab = maxCharsPerTab.coerceIn(120, 30_000)
        appendAction(
            if (isMd) "Markdown all tabs (${openSessions.size} tabs, max_chars_per_tab=$cappedPerTab)"
            else "Inspect all tabs (${openSessions.size} tabs, max_chars_per_tab=$cappedPerTab)"
        )
        val perTab = coroutineScope {
            openSessions.map { s ->
                async {
                    val out = if (isMd) {
                        extractMarkdown(
                            maxChars = cappedPerTab,
                            selector = null,
                            includeInteractive = true,
                            tabId = s.id,
                            recordAction = false
                        )
                    } else {
                        inspect(
                            maxTextChars = cappedPerTab,
                            format = "default",
                            tabId = s.id,
                            recordAction = false
                        )
                    }
                    s.id to out
                }
            }.awaitAll()
        }
        buildString {
            appendLine(listTabsSummary())
            for ((tid, res) in perTab) {
                appendLine()
                appendLine("=== TAB [$tid] ===")
                res.fold(
                    onSuccess = { appendLine(it) },
                    onFailure = { appendLine("ERROR: ${it.message}") }
                )
            }
        }.trim()
    }

    private fun resolveSessionForOp(
        tabId: String?,
        createIfMissing: Boolean = false,
        newTab: Boolean = false,
        makeActive: Boolean = false
    ): AgentTabSession {
        if (newTab) {
            val createdId = createOrSelectTab(requestedTabId = tabId, makeActive = makeActive)
            return synchronized(tabsLock) { tabSessions.getValue(createdId) }
        }
        val clean = sanitizeTabId(tabId)
        if (clean != null) {
            val existing = synchronized(tabsLock) { tabSessions[clean] }
            if (existing != null) {
                if (makeActive) switchAgentTab(clean)
                return existing
            }
            if (createIfMissing) {
                val createdId = createOrSelectTab(requestedTabId = clean, makeActive = makeActive)
                return synchronized(tabsLock) { tabSessions.getValue(createdId) }
            }
            error("Agent browser tab '$clean' does not exist. Open tabs: ${_agentTabs.value.joinToString { it.id }}")
        }
        return synchronized(tabsLock) {
            syncActiveAndTabListLocked(persist = false)
            tabSessions.getValue(_activeAgentTabId.value)
        }
    }

    private fun pauseAndBlankOnMain(wv: WebView?, pauseJs: Boolean) {
        wv ?: return
        runOnMainBestEffort {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            if (pauseJs) wv.onPause()
        }
    }

    private fun destroyWebViewOnMain(wv: WebView?) {
        wv ?: return
        runOnMainBestEffort {
            runCatching {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
            }
        }
    }

    private fun runOnMainBestEffort(block: () -> Unit) {
        runCatching {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper == null || Looper.myLooper() == mainLooper) {
                block()
            } else {
                Handler(mainLooper).post { runCatching { block() } }
            }
        }.onFailure {
            runCatching { block() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(
        isAgentBrowser: Boolean,
        urlState: MutableStateFlow<String>,
        titleState: MutableStateFlow<String>,
        loadingState: MutableStateFlow<Boolean>,
        progressState: MutableStateFlow<Int>,
        canBackState: MutableStateFlow<Boolean>,
        canFwdState: MutableStateFlow<Boolean>,
        consoleState: MutableStateFlow<List<String>>,
        onStateChanged: ((persist: Boolean) -> Unit)? = null,
        onAgentFinished: ((String?) -> Unit)? = null,
        onAlertBanner: ((String) -> Unit)? = null
    ): WebView {
        ensureSlowWholeDocumentDraw()
        return WebView(appContext).apply {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                if (isAgentBrowser && shouldCurrentlyBlockImages()) {
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (isAgentBrowser && _turboModeEnabled.value && request != null) {
                        val reqUrl = request.url?.toString().orEmpty()
                        if (shouldBlockInTurboMode(reqUrl, shouldCurrentlyBlockImages())) {
                            return WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                204,
                                "No Content",
                                emptyMap(),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loadingState.value = true
                    if (!url.isNullOrBlank() && url != "about:blank") {
                        urlState.value = url
                    }
                    canBackState.value = view?.canGoBack() == true
                    canFwdState.value = view?.canGoForward() == true
                    onStateChanged?.invoke(true)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    canBackState.value = view?.canGoBack() == true
                    canFwdState.value = view?.canGoForward() == true
                    onStateChanged?.invoke(false)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loadingState.value = false
                    if (!url.isNullOrBlank() && url != "about:blank") {
                        urlState.value = url
                    }
                    if (url != "about:blank") {
                        titleState.value = view?.title.orEmpty()
                        if (isAgentBrowser && _turboModeEnabled.value) {
                            view?.evaluateJavascript(TURBO_DISABLE_ANIMATIONS_JS, null)
                        }
                    }
                    canBackState.value = view?.canGoBack() == true
                    canFwdState.value = view?.canGoForward() == true
                    onStateChanged?.invoke(true)
                    flushCookiesBestEffort()
                    onAgentFinished?.invoke(url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressState.value = newProgress
                    onStateChanged?.invoke(false)
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (urlState.value.isNotBlank()) {
                        titleState.value = title.orEmpty()
                        onStateChanged?.invoke(true)
                    }
                }

                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                    if (cm != null) {
                        val line = "[${cm.messageLevel()}] ${cm.message()} (:${cm.lineNumber()})"
                        consoleState.value = (consoleState.value + line).takeLast(150)
                        onStateChanged?.invoke(false)
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
    }

    private fun resolveUserSession(
        tabId: String? = null,
        createIfMissing: Boolean = false
    ): AgentTabSession {
        return synchronized(userTabsLock) {
            syncActiveUserTabAndListLocked(persist = false)
            val clean = sanitizeTabId(tabId)
            if (clean != null) {
                userTabSessions[clean]?.let { return@synchronized it }
                if (createIfMissing) {
                    if (userTabSessions.size >= MAX_USER_TABS) {
                        val activeId = _activeUserTabId.value
                        val victimKey = userTabSessions.keys.firstOrNull { it != activeId } ?: userTabSessions.keys.first()
                        val evicted = userTabSessions.remove(victimKey)?.webView
                        destroyWebViewOnMain(evicted)
                    }
                    bumpUserTabSequenceFromId(clean)
                    val created = AgentTabSession(id = clean)
                    userTabSessions[clean] = created
                    syncActiveUserTabAndListLocked(persist = true)
                    return@synchronized created
                }
            }
            userTabSessions.getValue(_activeUserTabId.value)
        }
    }

    private fun ensureUserSessionWebViewOnMain(
        session: AgentTabSession,
        autoLoadSavedUrl: Boolean = true
    ): WebView {
        session.webView?.let { existing ->
            existing.onResume()
            return existing
        }
        return createConfiguredWebView(
            isAgentBrowser = false,
            urlState = session.url,
            titleState = session.title,
            loadingState = session.isLoading,
            progressState = session.progress,
            canBackState = session.canGoBack,
            canFwdState = session.canGoForward,
            consoleState = session.consoleLogs,
            onStateChanged = { persist -> onUserSessionStateMutated(persist) }
        ).also { created ->
            created.onResume()
            session.webView = created
            val savedUrl = session.url.value.trim()
            if (autoLoadSavedUrl && savedUrl.isNotBlank() && savedUrl != "about:blank") {
                created.loadUrl(savedUrl)
            }
        }
    }

    fun ensureUserWebView(tabId: String? = null): WebView {
        val session = resolveUserSession(tabId = tabId, createIfMissing = tabId != null)
        return ensureUserSessionWebViewOnMain(session, autoLoadSavedUrl = true)
    }

    private fun ensureSessionWebViewOnMain(
        session: AgentTabSession,
        autoLoadSavedUrl: Boolean = true
    ): WebView {
        session.webView?.let { existing ->
            existing.onResume()
            return existing
        }
        return createConfiguredWebView(
            isAgentBrowser = true,
            urlState = session.url,
            titleState = session.title,
            loadingState = session.isLoading,
            progressState = session.progress,
            canBackState = session.canGoBack,
            canFwdState = session.canGoForward,
            consoleState = session.consoleLogs,
            onStateChanged = { persist -> onSessionStateMutated(persist) },
            onAgentFinished = { url ->
                val waiter = session.loadWaiter
                if (waiter != null && url != "about:blank") {
                    waiter.complete(url.orEmpty())
                    session.loadWaiter = null
                }
            },
            onAlertBanner = { msg -> appendAction("[${session.id}] $msg") }
        ).also { created ->
            created.onResume()
            session.webView = created
            val savedUrl = session.url.value.trim()
            if (autoLoadSavedUrl && savedUrl.isNotBlank() && savedUrl != "about:blank") {
                created.loadUrl(savedUrl)
            }
        }
    }

    fun ensureAgentWebView(tabId: String? = null): WebView {
        val session = resolveSessionForOp(tabId, createIfMissing = true)
        return ensureSessionWebViewOnMain(session, autoLoadSavedUrl = true)
    }

    private suspend fun ensureSessionHydrated(session: AgentTabSession) {
        val needsHydrate = session.webView == null &&
            session.url.value.isNotBlank() &&
            session.url.value != "about:blank"
        if (!needsHydrate) return

        val waiter = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            val wv = ensureSessionWebViewOnMain(session, autoLoadSavedUrl = false)
            layoutIfDetached(wv)
            val saved = session.url.value.trim()
            if (saved.isNotBlank() && saved != "about:blank" && (wv.url.isNullOrBlank() || wv.url == "about:blank")) {
                session.loadWaiter?.cancel()
                session.loadWaiter = waiter
                wv.loadUrl(saved)
            } else {
                waiter.complete(saved)
            }
        }
        withTimeoutOrNull(8_000L) { waiter.await() }
        withContext(Dispatchers.Main) {
            if (session.loadWaiter === waiter) session.loadWaiter = null
        }
        delay(if (_turboModeEnabled.value) 30L else 80L)
    }

    fun acquireAttachedWebView(
        forAgent: Boolean = _agentModeEnabled.value,
        urlToLoadIfBlank: String? = null,
        tabId: String? = null
    ): WebView {
        val session: AgentTabSession
        val wv: WebView
        if (forAgent) {
            session = synchronized(tabsLock) {
                syncActiveAndTabListLocked(persist = false)
                val clean = sanitizeTabId(tabId)
                (if (clean != null) tabSessions[clean] else null) ?: tabSessions.getValue(_activeAgentTabId.value)
            }
            wv = ensureSessionWebViewOnMain(session, autoLoadSavedUrl = false)
        } else {
            session = resolveUserSession(tabId = tabId, createIfMissing = false)
            wv = ensureUserSessionWebViewOnMain(session, autoLoadSavedUrl = false)
        }
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.onResume()
        val cur = (urlToLoadIfBlank?.takeIf { it.isNotBlank() } ?: session.url.value).trim()
        if (cur.isNotBlank() && cur != "about:blank" && (wv.url.isNullOrBlank() || wv.url == "about:blank")) {
            wv.loadUrl(cur)
        }
        return wv
    }

    fun releaseAttachedParent(forAgent: Boolean? = null) {
        if (forAgent == null || forAgent == false) {
            val userSessions = synchronized(userTabsLock) { userTabSessions.values.toList() }
            userSessions.forEach { s ->
                s.webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            }
        }
        if (forAgent == null || forAgent == true) {
            val sessions = synchronized(tabsLock) { tabSessions.values.toList() }
            sessions.forEach { s ->
                s.webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            }
        }
    }

    suspend fun navigate(
        rawUrl: String,
        enableAgentMode: Boolean = false,
        waitFinishMs: Long = 15_000L,
        turbo: Boolean? = null,
        includeInspect: Boolean = false,
        includeMarkdown: Boolean = false,
        maxChars: Int = 5500,
        tabId: String? = null,
        newTab: Boolean = false,
        switchActiveOnNavigate: Boolean = true
    ): Result<String> = runCatching {
        if (turbo != null) {
            setTurboMode(turbo)
        }
        val session = resolveSessionForOp(
            tabId = tabId,
            createIfMissing = true,
            newTab = newTab,
            makeActive = switchActiveOnNavigate && (newTab || tabId != null)
        )
        withSerializedAgentOp(session.opMutex) {
            navigateSessionUnlocked(
                session = session,
                rawUrl = rawUrl,
                enableAgentMode = enableAgentMode,
                waitFinishMs = waitFinishMs,
                includeInspect = includeInspect,
                includeMarkdown = includeMarkdown,
                maxChars = maxChars
            ).getOrThrow()
        }
    }

    private suspend fun navigateSessionUnlocked(
        session: AgentTabSession,
        rawUrl: String,
        enableAgentMode: Boolean = false,
        waitFinishMs: Long = 15_000L,
        includeInspect: Boolean = false,
        includeMarkdown: Boolean = false,
        maxChars: Int = 5500
    ): Result<String> = runCatching {
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isBlank()) error("Empty URL")
        if (enableAgentMode) {
            setAgentMode(true)
        }
        val t0 = System.currentTimeMillis()
        val waiter = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            val wv = ensureSessionWebViewOnMain(session, autoLoadSavedUrl = false)
            wv.onResume()
            layoutIfDetached(wv)
            session.loadWaiter?.cancel()
            session.loadWaiter = if (normalized == "about:blank") null else waiter
            session.url.value = if (normalized == "about:blank") "" else normalized
            onSessionStateMutated(persist = true)
            appendAction("[${session.id}] Navigate: $normalized")
            wv.loadUrl(normalized)
        }
        if (normalized != "about:blank") {
            val turboActive = _turboModeEnabled.value
            withTimeoutOrNull(waitFinishMs.coerceIn(800L, 30_000L)) {
                if (!turboActive) {
                    waiter.await()
                } else {
                    coroutineScope {
                        val earlyDomJob = async {
                            delay(260L)
                            while (!waiter.isCompleted) {
                                if (session.progress.value >= 60 || !session.isLoading.value) {
                                    val ready = withContext(Dispatchers.Main) {
                                        val wv = session.webView ?: return@withContext false
                                        val curUrl = wv.url.orEmpty()
                                        if (curUrl.isBlank() || curUrl == "about:blank") {
                                            false
                                        } else {
                                            val raw = withTimeoutOrNull(600L) {
                                                suspendCancellableCoroutine<String> { cont ->
                                                    wv.evaluateJavascript(TURBO_EARLY_DOM_CHECK_JS) { v ->
                                                        if (cont.isActive) cont.resume(v ?: "null")
                                                    }
                                                }
                                            }
                                            val ok = decodeJsResultString(raw ?: "0") == "1"
                                            if (ok) {
                                                wv.evaluateJavascript(TURBO_DISABLE_ANIMATIONS_JS, null)
                                                session.isLoading.value = false
                                                session.title.value = wv.title.orEmpty()
                                                waiter.complete(curUrl)
                                            }
                                            ok
                                        }
                                    }
                                    if (ready) break
                                }
                                delay(120L)
                            }
                        }
                        try {
                            waiter.await()
                        } finally {
                            earlyDomJob.cancel()
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (session.loadWaiter === waiter) session.loadWaiter = null
            }
        }
        delay(if (_turboModeEnabled.value) 35L else 100L)
        val elapsed = System.currentTimeMillis() - t0
        val header = withContext(Dispatchers.Main) {
            val wv = ensureSessionWebViewOnMain(session, autoLoadSavedUrl = false)
            val u = wv.url?.takeUnless { it == "about:blank" } ?: session.url.value
            val t = wv.title.orEmpty()
            session.url.value = u
            session.title.value = t
            onSessionStateMutated(persist = true)
            "Opened in Agent Browser [${session.id}] (${elapsed}ms, ⚡ Turbo=${if (_turboModeEnabled.value) "ON" else "off"}): $u" +
                if (t.isNotBlank()) " — \"$t\"" else ""
        }
        buildString {
            append(header)
            val effectiveMax = maxChars.coerceIn(120, 35_000)
            val snapBudget = if (effectiveMax < 600) {
                (effectiveMax - header.length - 2).coerceAtLeast(120)
            } else {
                effectiveMax
            }
            if (includeMarkdown) {
                val md = extractMarkdownSessionUnlocked(
                    session = session,
                    maxChars = snapBudget,
                    selector = null,
                    includeInteractive = true,
                    recordAction = false
                ).getOrNull()
                if (!md.isNullOrBlank()) {
                    append("\n\n").append(md)
                }
            } else if (includeInspect) {
                val snap = evalJsOnSession(session, buildInspectScript(snapBudget)).getOrNull()
                if (!snap.isNullOrBlank()) {
                    append("\n\n").append(snap)
                }
            }
        }
    }

    suspend fun navigateFromUser(rawUrl: String, tabId: String? = null) {
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isBlank()) return
        val session = resolveUserSession(tabId = tabId, createIfMissing = tabId != null)
        withContext(Dispatchers.Main) {
            val wv = ensureUserSessionWebViewOnMain(session, autoLoadSavedUrl = false)
            wv.onResume()
            session.url.value = if (normalized == "about:blank") "" else normalized
            onUserSessionStateMutated(persist = true)
            wv.loadUrl(normalized)
        }
    }

    suspend fun goBack(forAgent: Boolean = true, waitFinish: Boolean = false, tabId: String? = null): Result<String> =
        runCatching {
            if (forAgent) {
                val session = resolveSessionForOp(tabId)
                withSerializedAgentOp(session.opMutex) {
                    goBackInternal(session = session, waitFinish = waitFinish).getOrThrow()
                }
            } else {
                val userSession = resolveUserSession(tabId = tabId, createIfMissing = false)
                goBackInternal(session = null, waitFinish = waitFinish, userSession = userSession).getOrThrow()
            }
        }

    private suspend fun goBackInternal(
        session: AgentTabSession?,
        waitFinish: Boolean,
        userSession: AgentTabSession? = null
    ): Result<String> = runCatching {
        if (session != null) ensureSessionHydrated(session)
        val resolvedUser = if (session == null) (userSession ?: resolveUserSession()) else null
        val urlState = session?.url ?: resolvedUser!!.url
        withContext(Dispatchers.Main) {
            val wv = if (session != null) {
                ensureSessionWebViewOnMain(session)
            } else {
                ensureUserSessionWebViewOnMain(resolvedUser!!)
            }
            if (!wv.canGoBack()) error("Back history is empty")
            if (session != null) appendAction("[${session.id}] History Back")
            wv.goBack()
        }
        if (waitFinish) delay(if (_turboModeEnabled.value) 80L else 220L)
        "Back -> ${urlState.value}"
    }

    suspend fun goForward(forAgent: Boolean = true, waitFinish: Boolean = false, tabId: String? = null): Result<String> =
        runCatching {
            if (forAgent) {
                val session = resolveSessionForOp(tabId)
                withSerializedAgentOp(session.opMutex) {
                    goForwardInternal(session = session, waitFinish = waitFinish).getOrThrow()
                }
            } else {
                val userSession = resolveUserSession(tabId = tabId, createIfMissing = false)
                goForwardInternal(session = null, waitFinish = waitFinish, userSession = userSession).getOrThrow()
            }
        }

    private suspend fun goForwardInternal(
        session: AgentTabSession?,
        waitFinish: Boolean,
        userSession: AgentTabSession? = null
    ): Result<String> = runCatching {
        if (session != null) ensureSessionHydrated(session)
        val resolvedUser = if (session == null) (userSession ?: resolveUserSession()) else null
        val urlState = session?.url ?: resolvedUser!!.url
        withContext(Dispatchers.Main) {
            val wv = if (session != null) {
                ensureSessionWebViewOnMain(session)
            } else {
                ensureUserSessionWebViewOnMain(resolvedUser!!)
            }
            if (!wv.canGoForward()) error("Forward history is empty")
            if (session != null) appendAction("[${session.id}] History Forward")
            wv.goForward()
        }
        if (waitFinish) delay(if (_turboModeEnabled.value) 80L else 220L)
        "Forward -> ${urlState.value}"
    }

    suspend fun reload(forAgent: Boolean = true, waitFinishMs: Long = 10_000L, tabId: String? = null): Result<String> =
        runCatching {
            if (forAgent) {
                val session = resolveSessionForOp(tabId)
                withSerializedAgentOp(session.opMutex) {
                    reloadInternal(session = session, waitFinishMs = waitFinishMs).getOrThrow()
                }
            } else {
                val userSession = resolveUserSession(tabId = tabId, createIfMissing = false)
                reloadInternal(session = null, waitFinishMs = waitFinishMs, userSession = userSession).getOrThrow()
            }
        }

    private suspend fun reloadInternal(
        session: AgentTabSession?,
        waitFinishMs: Long = 10_000L,
        userSession: AgentTabSession? = null
    ): Result<String> = runCatching {
        if (session != null) {
            ensureSessionHydrated(session)
            val waiter = CompletableDeferred<String>()
            withContext(Dispatchers.Main) {
                val wv = ensureSessionWebViewOnMain(session)
                session.loadWaiter?.cancel()
                session.loadWaiter = waiter
                appendAction("[${session.id}] Reload page")
                wv.reload()
            }
            withTimeoutOrNull(waitFinishMs) { waiter.await() }
            withContext(Dispatchers.Main) {
                if (session.loadWaiter === waiter) session.loadWaiter = null
            }
            "Page reloaded [${session.id}]: ${session.url.value}"
        } else {
            val resolvedUser = userSession ?: resolveUserSession()
            withContext(Dispatchers.Main) {
                ensureUserSessionWebViewOnMain(resolvedUser).reload()
            }
            "Page reloaded: ${resolvedUser.url.value}"
        }
    }

    suspend fun inspect(
        maxTextChars: Int = 5500,
        format: String = "default",
        tabId: String? = null,
        recordAction: Boolean = true
    ): Result<String> = runCatching {
        val session = resolveSessionForOp(tabId)
        withSerializedAgentOp(session.opMutex) {
            inspectSessionUnlocked(session, maxTextChars, format, recordAction = recordAction).getOrThrow()
        }
    }

    private suspend fun inspectSessionUnlocked(
        session: AgentTabSession,
        maxTextChars: Int = 5500,
        format: String = "default",
        recordAction: Boolean = true
    ): Result<String> = runCatching {
        ensureSessionHydrated(session)
        val isMd = format.trim().equals("markdown", ignoreCase = true) ||
            format.trim().equals("md", ignoreCase = true)
        if (recordAction) {
            appendAction("[${session.id}] " + if (isMd) "Extract live DOM as Markdown" else "Inspect DOM & interactive elements")
        }
        val tabPrefix = "[Tab: ${session.id} | ⚡ Turbo: ${if (_turboModeEnabled.value) "ON" else "off"}]"
        val effectiveBudget = maxTextChars.coerceIn(120, 35_000)
        val jsBudget = if (effectiveBudget < 600) {
            (effectiveBudget - tabPrefix.length - 1).coerceAtLeast(120)
        } else {
            effectiveBudget
        }
        val raw = if (isMd) {
            evalJsOnSession(
                session,
                buildMarkdownExtractScript(jsBudget, "null", true)
            ).getOrThrow()
        } else {
            evalJsOnSession(session, buildInspectScript(jsBudget)).getOrThrow()
        }
        val consoleErrors = session.consoleLogs.value.filter { it.contains("ERROR") }.takeLast(5)
        buildString {
            appendLine(tabPrefix)
            appendLine(raw)
            if (consoleErrors.isNotEmpty() && length + 40 < effectiveBudget) {
                appendLine("\nRecent JS console errors:")
                consoleErrors.forEach { appendLine("- $it") }
            }
        }.trimEnd()
    }

    suspend fun extractMarkdown(
        maxChars: Int = 16_000,
        selector: String? = null,
        includeInteractive: Boolean = true,
        tabId: String? = null,
        recordAction: Boolean = true
    ): Result<String> = runCatching {
        val session = resolveSessionForOp(tabId)
        withSerializedAgentOp(session.opMutex) {
            extractMarkdownSessionUnlocked(
                session = session,
                maxChars = maxChars,
                selector = selector,
                includeInteractive = includeInteractive,
                recordAction = recordAction
            ).getOrThrow()
        }
    }

    private suspend fun extractMarkdownSessionUnlocked(
        session: AgentTabSession,
        maxChars: Int = 16_000,
        selector: String? = null,
        includeInteractive: Boolean = true,
        recordAction: Boolean = true
    ): Result<String> = runCatching {
        ensureSessionHydrated(session)
        if (recordAction) {
            appendAction("[${session.id}] Extract live page Markdown")
        }
        val selLiteral = if (selector.isNullOrBlank()) "null" else JsonPrimitive(selector.trim()).toString()
        evalJsOnSession(
            session,
            buildMarkdownExtractScript(maxChars.coerceIn(120, 45_000), selLiteral, includeInteractive)
        ).getOrThrow()
    }

    suspend fun act(
        action: String,
        selector: String? = null,
        elementIndex: Int? = null,
        textMatch: String? = null,
        value: String? = null,
        autoInspect: Boolean = false,
        settleMs: Long? = null,
        tabId: String? = null
    ): Result<String> = runCatching {
        val session = resolveSessionForOp(tabId)
        withSerializedAgentOp(session.opMutex) {
            actSessionUnlocked(
                session = session,
                action = action,
                selector = selector,
                elementIndex = elementIndex,
                textMatch = textMatch,
                value = value,
                autoInspect = autoInspect,
                settleMs = settleMs
            ).getOrThrow()
        }
    }

    private suspend fun actSessionUnlocked(
        session: AgentTabSession,
        action: String,
        selector: String? = null,
        elementIndex: Int? = null,
        textMatch: String? = null,
        value: String? = null,
        autoInspect: Boolean = false,
        settleMs: Long? = null
    ): Result<String> {
        val actNorm = action.trim().lowercase()
        when (actNorm) {
            "back" -> return goBackInternal(session = session, waitFinish = true)
            "forward" -> return goForwardInternal(session = session, waitFinish = true)
            "reload" -> return reloadInternal(session = session)
        }
        return runCatching {
            ensureSessionHydrated(session)
            when (actNorm) {
                "wait" -> {
                    val ms = (value?.toLongOrNull() ?: 500L).coerceIn(25L, 10_000L)
                    appendAction("[${session.id}] Wait ${ms}ms")
                    delay(ms)
                    return@runCatching "Wait ${ms}ms complete ([${session.id}] ${session.url.value})"
                }
                "scroll" -> {
                    val dir = (value ?: "down").trim().lowercase()
                    appendAction("[${session.id}] Scroll: $dir")
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
                    val res = evalJsOnSession(session, js).getOrThrow()
                    delay(settleMs ?: if (_turboModeEnabled.value) 20L else 60L)
                    return@runCatching "$res ([${session.id}] scroll: ${session.url.value})"
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
                "[${session.id}] " + when (actNorm) {
                    "click" -> "Click: ${selector ?: elementIndex?.let { "[$it]" } ?: textMatch ?: "?"}"
                    "type" -> "Type text into ${selector ?: elementIndex?.let { "[$it]" } ?: textMatch ?: "?"}"
                    "press_enter" -> "Submit (Enter) in ${selector ?: elementIndex?.let { "[$it]" } ?: textMatch ?: "active"}"
                    "select" -> "Select value '$value'"
                    else -> "Action $actNorm"
                }
            )

            val js = buildActScript(payload)
            val result = evalJsOnSession(session, js).getOrThrow()
            val waitAfter = settleMs ?: when {
                _turboModeEnabled.value -> if (actNorm == "type") 15L else 30L
                actNorm == "type" -> 45L
                else -> 90L
            }
            delay(waitAfter)
            withContext(Dispatchers.Main) {
                session.webView?.url?.takeIf { it.isNotBlank() && it != "about:blank" }?.let {
                    session.url.value = it
                }
                session.webView?.title?.let { session.title.value = it }
                onSessionStateMutated(persist = true)
            }
            flushCookiesBestEffort()
            if (result.startsWith("ERR:")) error(result)

            val freshElementsSuffix = if (autoInspect) {
                val compactIdx = evalJsOnSession(session, buildReindexSummaryScript(maxElements = 40)).getOrNull()
                if (!compactIdx.isNullOrBlank()) "\n$compactIdx" else ""
            } else ""

            "[${session.id}] $result | URL: ${session.url.value}$freshElementsSuffix"
        }
    }

    /**
     * Executes up to 30 browser steps back-to-back with sub-50ms polling and zero LLM round-trip overhead.
     * Supports jumping freely across multiple background tabs via per-step `tabId` / `newTab`!
     * Respects persistent shared Turbo state unless `turbo` is explicitly provided.
     */
    suspend fun executeBatch(
        steps: List<BrowserBatchStep>,
        turbo: Boolean? = null,
        stopOnError: Boolean = true,
        returnInspect: Boolean = true,
        returnMarkdown: Boolean = false,
        maxChars: Int = 8000,
        tabId: String? = null,
        screenshotFileResolver: ((String?) -> File)? = null
    ): Result<BrowserBatchOutcome> = runCatching {
        val bounded = steps.take(30)
        if (bounded.isEmpty()) error("steps list is empty")

        if (turbo != null) {
            setTurboMode(turbo)
        }

        val hasScreenshotStep = bounded.any { it.action.trim().equals("screenshot", ignoreCase = true) }
        if (hasScreenshotStep) {
            activeScreenshotCaptures.incrementAndGet()
            applyImageGateToAllAgentWebViews()
        }

        val batchStart = System.currentTimeMillis()
        val stepLogs = mutableListOf<String>()
        val captured = mutableListOf<Pair<File, Boolean>>()
        var hadErr = false
        var currentBatchSession = resolveSessionForOp(tabId, createIfMissing = tabId != null)

        try {
            appendAction("Batch run: ${bounded.size} steps (⚡ Turbo=${_turboModeEnabled.value})")
            for ((i, step) in bounded.withIndex()) {
                val stepNum = "[${i + 1}/${bounded.size}]"
                val actName = step.action.trim().lowercase()
                val tStep = System.currentTimeMillis()

                if (actName == "switch_tab") {
                    val targetTid = step.tabId ?: step.value
                    val switchRes = runCatching {
                        val s = resolveSessionForOp(targetTid, createIfMissing = false, makeActive = true)
                        currentBatchSession = s
                        "switched to [${s.id}] (${s.url.value.ifBlank { "blank" }})"
                    }
                    val stepMs = System.currentTimeMillis() - tStep
                    switchRes.fold(
                        onSuccess = { stepLogs += "$stepNum OK switch_tab (${stepMs}ms): $it" },
                        onFailure = { err ->
                            hadErr = true
                            stepLogs += "$stepNum ERR switch_tab (${stepMs}ms): ${err.message}"
                        }
                    )
                    if (hadErr && stopOnError) break
                    continue
                }

                val stepSessionRes = runCatching {
                    if (step.newTab || step.tabId != null) {
                        resolveSessionForOp(
                            tabId = step.tabId,
                            createIfMissing = actName in setOf("navigate", "open", "goto") || step.newTab,
                            newTab = step.newTab,
                            makeActive = false
                        ).also { currentBatchSession = it }
                    } else {
                        currentBatchSession
                    }
                }

                val session = stepSessionRes.getOrElse { err ->
                    hadErr = true
                    stepLogs += "$stepNum ERR $actName (0ms): ${err.message}"
                    if (stopOnError) break else continue
                }

                val stepRes: Result<String> = withSerializedAgentOp(session.opMutex) {
                    when (actName) {
                        "navigate", "open", "goto" -> {
                            val target = step.url ?: step.value
                            if (target.isNullOrBlank()) {
                                Result.failure(IllegalArgumentException("navigate requires url"))
                            } else {
                                navigateSessionUnlocked(
                                    session = session,
                                    rawUrl = target,
                                    enableAgentMode = false,
                                    waitFinishMs = step.timeoutMs ?: 12_000L
                                ).also {
                                    runCatching { evalJsOnSession(session, buildReindexSummaryScript(maxElements = 80)) }
                                }
                            }
                        }

                        "fill_form", "fill" -> {
                            val fieldsMap = step.fields.orEmpty()
                            if (fieldsMap.isEmpty()) {
                                Result.failure(IllegalArgumentException("fill_form requires non-empty fields object"))
                            } else {
                                ensureSessionHydrated(session)
                                val fieldsJson = buildJsonObject {
                                    fieldsMap.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                                }.toString()
                                evalJsOnSession(session, buildFillFormScript(fieldsJson)).mapCatching { out ->
                                    if (out.startsWith("ERR:")) error(out)
                                    out
                                }
                            }
                        }

                        "wait_selector" -> {
                            val sel = step.selector ?: step.value
                            if (sel.isNullOrBlank()) {
                                Result.failure(IllegalArgumentException("wait_selector requires selector"))
                            } else {
                                ensureSessionHydrated(session)
                                pollDomCondition(
                                    session = session,
                                    timeoutMs = (step.timeoutMs ?: 5_000L).coerceIn(100L, 20_000L),
                                    description = "selector '$sel'",
                                    checkJs = buildCheckSelectorVisibleScript(JsonPrimitive(sel).toString())
                                ).also {
                                    runCatching { evalJsOnSession(session, buildReindexSummaryScript(maxElements = 80)) }
                                }
                            }
                        }

                        "wait_text" -> {
                            val needle = step.text ?: step.value
                            if (needle.isNullOrBlank()) {
                                Result.failure(IllegalArgumentException("wait_text requires text"))
                            } else {
                                ensureSessionHydrated(session)
                                pollDomCondition(
                                    session = session,
                                    timeoutMs = (step.timeoutMs ?: 5_000L).coerceIn(100L, 20_000L),
                                    description = "text '${needle.take(40)}'",
                                    checkJs = buildCheckTextPresentScript(JsonPrimitive(needle).toString())
                                ).also {
                                    runCatching { evalJsOnSession(session, buildReindexSummaryScript(maxElements = 80)) }
                                }
                            }
                        }

                        "wait" -> {
                            val ms = (step.timeoutMs ?: step.value?.toLongOrNull() ?: 250L).coerceIn(20L, 10_000L)
                            delay(ms)
                            Result.success("waited ${ms}ms")
                        }

                        "eval_js", "js" -> {
                            val code = step.script ?: step.value
                            if (code.isNullOrBlank()) {
                                Result.failure(IllegalArgumentException("eval_js requires script"))
                            } else {
                                ensureSessionHydrated(session)
                                evalJsOnSession(session, code).map { it.take(4_000) }
                            }
                        }

                        "extract_markdown", "markdown" -> {
                            extractMarkdownSessionUnlocked(
                                session = session,
                                maxChars = maxChars.coerceIn(120, 35_000),
                                selector = step.selector,
                                includeInteractive = false,
                                recordAction = false
                            )
                        }

                        "inspect" -> {
                            inspectSessionUnlocked(
                                session = session,
                                maxTextChars = maxChars.coerceIn(120, 25_000),
                                recordAction = false
                            )
                        }

                        "screenshot" -> {
                            val resolver = screenshotFileResolver
                            if (resolver == null) {
                                Result.failure(IllegalStateException("Screenshot resolver unavailable"))
                            } else {
                                val targetFile = resolver(step.filename)
                                captureScreenshotSessionUnlocked(session, targetFile).map { saved ->
                                    captured += saved to step.attachToChat
                                    "screenshot saved: ${saved.name} (${saved.length()} bytes)"
                                }
                            }
                        }

                        "type" -> {
                            val typeRes = actSessionUnlocked(
                                session = session,
                                action = "type",
                                selector = step.selector,
                                elementIndex = step.index,
                                textMatch = step.text,
                                value = step.value,
                                autoInspect = false,
                                settleMs = if (_turboModeEnabled.value) 15L else 35L
                            )
                            if (typeRes.isSuccess && step.submit) {
                                val enterRes = actSessionUnlocked(
                                    session = session,
                                    action = "press_enter",
                                    selector = step.selector,
                                    elementIndex = step.index,
                                    textMatch = step.text,
                                    autoInspect = false,
                                    settleMs = if (_turboModeEnabled.value) 35L else 70L
                                )
                                runCatching { evalJsOnSession(session, buildReindexSummaryScript(maxElements = 80)) }
                                enterRes.map { "${typeRes.getOrThrow()} + submit ($it)" }
                            } else {
                                typeRes
                            }
                        }

                        else -> {
                            val r = actSessionUnlocked(
                                session = session,
                                action = actName,
                                selector = step.selector,
                                elementIndex = step.index,
                                textMatch = step.text,
                                value = step.value,
                                autoInspect = false,
                                settleMs = if (_turboModeEnabled.value) {
                                    if (actName == "click" || actName == "press_enter") 30L else 20L
                                } else {
                                    if (actName == "click" || actName == "press_enter") 75L else 45L
                                }
                            )
                            if (actName in setOf("click", "press_enter", "select", "reload", "back", "forward")) {
                                runCatching { evalJsOnSession(session, buildReindexSummaryScript(maxElements = 80)) }
                            }
                            r
                        }
                    }
                }

                val stepMs = System.currentTimeMillis() - tStep
                stepRes.fold(
                    onSuccess = { msg ->
                        stepLogs += "$stepNum [${session.id}] OK $actName (${stepMs}ms): $msg"
                    },
                    onFailure = { err ->
                        hadErr = true
                        stepLogs += "$stepNum [${session.id}] ERR $actName (${stepMs}ms): ${err.message}"
                    }
                )
                if (hadErr && stopOnError) {
                    break
                }
            }

            val totalMs = System.currentTimeMillis() - batchStart
            val finalSession = currentBatchSession
            val summary = buildString {
                appendLine(
                    "Browser batch finished in ${totalMs}ms (${stepLogs.size}/${bounded.size} steps, " +
                        "⚡ Turbo=${if (_turboModeEnabled.value) "ON" else "off"}, finalTab=[${finalSession.id}]):"
                )
                stepLogs.forEach { appendLine(it) }
                if (returnMarkdown) {
                    val md = withSerializedAgentOp(finalSession.opMutex) {
                        extractMarkdownSessionUnlocked(
                            session = finalSession,
                            maxChars = maxChars.coerceIn(120, 35_000),
                            selector = null,
                            includeInteractive = returnInspect,
                            recordAction = false
                        ).getOrNull()
                    }
                    if (!md.isNullOrBlank()) {
                        appendLine()
                        appendLine("=== LIVE PAGE MARKDOWN [${finalSession.id}] ===")
                        appendLine(md)
                    }
                } else if (returnInspect) {
                    val snap = withSerializedAgentOp(finalSession.opMutex) {
                        inspectSessionUnlocked(
                            session = finalSession,
                            maxTextChars = maxChars.coerceIn(120, 25_000),
                            recordAction = false
                        ).getOrNull()
                    }
                    if (!snap.isNullOrBlank()) {
                        appendLine()
                        appendLine("=== LIVE PAGE INSPECT [${finalSession.id}] ===")
                        appendLine(snap)
                    }
                }
            }.trim()

            BrowserBatchOutcome(
                summaryText = summary,
                capturedFiles = captured,
                hadError = hadErr
            )
        } finally {
            if (hasScreenshotStep) {
                activeScreenshotCaptures.decrementAndGet()
                applyImageGateToAllAgentWebViews()
            }
            flushCookiesBestEffort()
        }
    }

    private suspend fun pollDomCondition(
        session: AgentTabSession,
        timeoutMs: Long,
        description: String,
        checkJs: String
    ): Result<String> = runCatching {
        val deadline = System.currentTimeMillis() + timeoutMs
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val matched = evalJsOnSession(session, checkJs).getOrNull()
            if (matched != null && matched.startsWith("FOUND:")) {
                val waited = System.currentTimeMillis() - t0
                return@runCatching "matched $description in ${waited}ms (${matched.removePrefix("FOUND:")})"
            }
            delay(50L)
        }
        error("Timeout (${timeoutMs}ms) waiting for $description")
    }

    suspend fun evalJs(script: String, tabId: String? = null): Result<String> = runCatching {
        val session = resolveSessionForOp(tabId)
        withSerializedAgentOp(session.opMutex) {
            ensureSessionHydrated(session)
            evalJsOnSession(session, script).getOrThrow()
        }
    }

    private suspend fun evalJsOnSession(session: AgentTabSession, script: String): Result<String> = runCatching {
        withContext(Dispatchers.Main) {
            val wv = ensureSessionWebViewOnMain(session)
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

    suspend fun captureScreenshot(outFile: File, tabId: String? = null): Result<File> = runCatching {
        val session = resolveSessionForOp(tabId)
        activeScreenshotCaptures.incrementAndGet()
        applyImageGateToAllAgentWebViews()
        try {
            withSerializedAgentOp(session.opMutex) {
                captureScreenshotSessionUnlocked(session, outFile).getOrThrow()
            }
        } finally {
            activeScreenshotCaptures.decrementAndGet()
            applyImageGateToAllAgentWebViews()
        }
    }

    private suspend fun captureScreenshotSessionUnlocked(session: AgentTabSession, outFile: File): Result<File> = runCatching {
        ensureSessionHydrated(session)
        appendAction("[${session.id}] Screenshot: ${outFile.name}")
        delay(60)
        withContext(Dispatchers.Main) {
            val wv = ensureSessionWebViewOnMain(session)
            layoutIfDetached(wv)
            awaitVisualFrameOnMain(wv)
            val w = if (wv.width > 0) wv.width else 1080
            val h = if (wv.height > 0) wv.height else 1920
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                canvas.save()
                canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
                wv.draw(canvas)
                canvas.restore()
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

    private suspend fun awaitVisualFrameOnMain(wv: WebView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val reqId = visualFrameCounter.getAndIncrement()
            withTimeoutOrNull(220L) {
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
        _actionLog.value = (_actionLog.value + stamped).takeLast(140)
    }

    companion object {
        const val MAX_AGENT_TABS = 8
        const val MAX_USER_TABS = 24

        private const val PREFS_NAME = "forgemind_browser_state"
        private const val KEY_AGENT_MODE = "agent_mode_enabled"
        private const val KEY_TURBO_MODE = "turbo_mode_enabled"
        private const val KEY_LAST_AGENT_URL = "last_agent_url"
        private const val KEY_AGENT_TABS_JSON = "agent_tabs_json"
        private const val KEY_ACTIVE_AGENT_TAB = "active_agent_tab_id"
        private const val KEY_USER_TABS_JSON = "user_tabs_json"
        private const val KEY_ACTIVE_USER_TAB = "active_user_tab_id"

        private val TAB_ID_CLEAN_REGEX = Regex("[^A-Za-z0-9_-]")

        private val TURBO_BLOCKED_HOST_SNIPPETS = listOf(
            "google-analytics.com",
            "googletagmanager.com",
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.",
            "mc.yandex.ru",
            "yandex.ru/ads",
            "connect.facebook.net",
            "hotjar.com",
            "clarity.ms",
            "criteo.com",
            "criteo.net",
            "taboola.com",
            "outbrain.com",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "adnxs.com",
            "amazon-adsystem.com",
            "adsafeprotected.com",
            "doubleverify.com",
            "scorecardresearch.com",
            "quantserve.com",
            "permutive.com",
            "sourcepoint.mgr.",
            "privacy-mgmt.com",
            "tinypass.com",
            "ophan.co.uk",
            "chartbeat.com",
            "optimizely.com",
            "segment.io",
            "amplitude.com",
            "mixpanel.com",
            "sentry.io",
            "bugsnag.com",
            "nr-data.net",
            "newrelic.com"
        )

        private const val TURBO_EARLY_DOM_CHECK_JS = """
            (function(){
              try {
                if (!location.href || location.href === 'about:blank') return '0';
                var rs = document.readyState;
                if (rs !== 'interactive' && rs !== 'complete') return '0';
                var b = document.body;
                if (!b) return '0';
                var len = (b.innerText || '').trim().length;
                return len >= 140 ? '1' : '0';
              } catch (_) { return '0'; }
            })();
        """

        private const val TURBO_DISABLE_ANIMATIONS_JS = """
            (function(){
              try {
                if (!document.getElementById('fm-turbo-style')) {
                  var st = document.createElement('style');
                  st.id = 'fm-turbo-style';
                  st.textContent = '*,*::before,*::after{transition-duration:0s!important;animation-duration:0s!important;scroll-behavior:auto!important;}';
                  (document.head || document.documentElement).appendChild(st);
                }
              } catch (_) {}
            })();
        """

        private val wholeDocumentDrawEnabled = AtomicBoolean(false)

        fun sanitizeTabId(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val cleaned = TAB_ID_CLEAN_REGEX.replace(trimmed, "_").trim('_').take(24)
            return cleaned.ifEmpty { null }
        }

        internal fun shouldBlockInTurboMode(url: String, blockImagesAndMedia: Boolean): Boolean {
            if (url.isBlank()) return false
            val lower = url.lowercase()
            if (TURBO_BLOCKED_HOST_SNIPPETS.any { lower.contains(it) }) return true
            if (blockImagesAndMedia) {
                val pathPart = lower.substringBefore('?').substringBefore('#')
                if (pathPart.endsWith(".woff2") || pathPart.endsWith(".woff") ||
                    pathPart.endsWith(".ttf") || pathPart.endsWith(".otf") ||
                    pathPart.endsWith(".eot") || pathPart.endsWith(".mp4") ||
                    pathPart.endsWith(".webm") || pathPart.endsWith(".mov") ||
                    pathPart.endsWith(".mp3") || pathPart.endsWith(".wav") ||
                    pathPart.endsWith(".ogg") || pathPart.endsWith(".gif")
                ) {
                    return true
                }
            }
            return false
        }

        internal fun ensureSlowWholeDocumentDraw() {
            if (wholeDocumentDrawEnabled.compareAndSet(false, true)) {
                runCatching { WebView.enableSlowWholeDocumentDraw() }
            }
        }

        internal suspend fun <T> withSerializedAgentOp(mutex: Mutex, block: suspend () -> T): T =
            mutex.withLock { block() }

        internal fun buildReindexSummaryScript(maxElements: Int = 50): String = """
            (function() {
              try {
                var stale = document.querySelectorAll('[data-fm-agent-idx]');
                for (var s = 0; s < stale.length; s++) stale[s].removeAttribute('data-fm-agent-idx');
                var els = Array.prototype.slice.call(
                  document.querySelectorAll('a,button,input,textarea,select,[role="button"],[onclick],[contenteditable="true"]')
                );
                var visible = [];
                for (var i = 0; i < els.length && visible.length < 80; i++) {
                  var el = els[i];
                  var r = el.getBoundingClientRect();
                  var st = window.getComputedStyle(el);
                  if (st.display === 'none' || st.visibility === 'hidden' || (r.width === 0 && r.height === 0)) continue;
                  var idx = visible.length;
                  el.setAttribute('data-fm-agent-idx', String(idx));
                  if (visible.length < ${maxElements.coerceIn(10, 80)}) {
                    var tag = el.tagName.toLowerCase();
                    var txt = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('title') || '').replace(/\s+/g, ' ').trim().slice(0, 50);
                    var id = el.id ? '#' + el.id : '';
                    visible.push('[' + idx + '] <' + tag + id + '> ' + (txt ? '"' + txt + '"' : ''));
                  } else {
                    visible.push('');
                  }
                }
                var shown = visible.filter(function(x){ return x.length > 0; });
                return 'Updated elements (' + visible.length + '): ' + shown.join(' | ');
              } catch (e) {
                return '';
              }
            })();
        """.trimIndent()

        internal fun buildInspectScript(maxTextChars: Int): String {
            val budget = maxTextChars.coerceIn(120, 25_000)
            val maxInteractive = if (budget < 1200) (budget / 45).coerceIn(5, 80) else 80
            return """
            (function() {
              try {
                var budget = $budget;
                var maxEls = $maxInteractive;
                var stale = document.querySelectorAll('[data-fm-agent-idx]');
                for (var s = 0; s < stale.length; s++) {
                  stale[s].removeAttribute('data-fm-agent-idx');
                }
                var els = Array.prototype.slice.call(
                  document.querySelectorAll('a,button,input,textarea,select,[role="button"],[onclick],[contenteditable="true"]')
                );
                var visible = [];
                for (var i = 0; i < els.length && visible.length < maxEls; i++) {
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
                var fullBody = ((document.body ? document.body.innerText : '') || '').replace(/\n{3,}/g, '\n\n').trim();
                var totalCount = visible.length;
                var res = {
                  url: location.href,
                  title: (document.title || '').slice(0, budget < 360 ? 45 : 120),
                  scrollY: Math.round(window.scrollY || 0),
                  scrollHeight: Math.round((document.documentElement && document.documentElement.scrollHeight) || 0),
                  viewportH: Math.round(window.innerHeight || 0),
                  totalElements: totalCount,
                  elements: visible,
                  text: fullBody
                };
                var fullJson = JSON.stringify(res);
                if (fullJson.length <= budget) return fullJson;

                res.elements = [];
                res.text = '';
                var truncSample = 'shown 0/' + totalCount + ' elements (max_chars=' + budget + ')';
                res.truncated = truncSample;
                var baseOverhead = JSON.stringify(res).length;
                var avail = Math.max(0, budget - baseOverhead);
                var textSliceLen = Math.min(fullBody.length, Math.max(0, Math.floor(avail * 0.42)));
                var candidateText = textSliceLen > 0 ? fullBody.slice(0, textSliceLen) : '';
                while (candidateText.length > 0 && JSON.stringify(candidateText).length - 2 > Math.floor(avail * 0.48)) {
                  candidateText = candidateText.slice(0, Math.max(0, candidateText.length - 8));
                }
                res.text = candidateText;
                var remForEls = Math.max(0, budget - JSON.stringify(res).length);
                var kept = [];
                var usedEl = 0;
                for (var k = 0; k < totalCount; k++) {
                  var itemCost = JSON.stringify(visible[k]).length + (kept.length > 0 ? 1 : 0);
                  if (usedEl + itemCost > remForEls) break;
                  kept.push(visible[k]);
                  usedEl += itemCost;
                }
                res.elements = kept;
                if (kept.length < totalCount || res.text.length < fullBody.length) {
                  res.truncated = 'shown ' + kept.length + '/' + totalCount + ' elements (max_chars=' + budget + ')';
                } else {
                  delete res.truncated;
                }
                return JSON.stringify(res);
              } catch (e) {
                return JSON.stringify({ error: String(e) });
              }
            })();
            """.trimIndent()
        }

        internal fun buildMarkdownExtractScript(
            maxChars: Int,
            selectorJsonLiteral: String,
            includeInteractive: Boolean
        ): String {
            val budget = maxChars.coerceIn(120, 45_000)
            val maxInteractive = if (budget < 1200) (budget / 45).coerceIn(5, 80) else 80
            return """
            (function(sel, withInteractive) {
              try {
                var budget = $budget;
                var maxEls = $maxInteractive;
                function isVis(el) {
                  if (!el || el.nodeType !== 1) return true;
                  var st = window.getComputedStyle(el);
                  if (st.display === 'none' || st.visibility === 'hidden') return false;
                  return true;
                }
                var stale = document.querySelectorAll('[data-fm-agent-idx]');
                for (var s = 0; s < stale.length; s++) stale[s].removeAttribute('data-fm-agent-idx');
                var interactive = [];
                if (withInteractive) {
                  var els = document.querySelectorAll('a,button,input,textarea,select,[role="button"],[onclick],[contenteditable="true"]');
                  for (var i = 0; i < els.length && interactive.length < maxEls; i++) {
                    var c = els[i];
                    var r = c.getBoundingClientRect();
                    if (!isVis(c) || (r.width === 0 && r.height === 0)) continue;
                    var idx = interactive.length;
                    c.setAttribute('data-fm-agent-idx', String(idx));
                    var tg = c.tagName.toLowerCase();
                    var lb = (c.innerText || c.value || c.getAttribute('aria-label') || c.getAttribute('placeholder') || '').replace(/\s+/g, ' ').trim().slice(0, 65);
                    var id = c.id ? '#' + c.id : '';
                    interactive.push('[' + idx + '] <' + tg + id + '> ' + (lb ? '"' + lb + '"' : ''));
                  }
                }
                function inlineMd(node) {
                  if (!node) return '';
                  if (node.nodeType === 3) return (node.nodeValue || '').replace(/[\t\r\n ]+/g, ' ');
                  if (node.nodeType !== 1 || !isVis(node)) return '';
                  var t = node.tagName.toLowerCase();
                  if (t === 'script' || t === 'style' || t === 'svg' || t === 'noscript') return '';
                  if (t === 'br') return '\n';
                  if (t === 'input' || t === 'textarea' || t === 'select') {
                    var ix = node.getAttribute('data-fm-agent-idx');
                    var idStr = node.id ? '#' + node.id : '';
                    var v = (node.value || node.getAttribute('placeholder') || '').slice(0, 60);
                    return '[' + (ix !== null ? ix + ':' : '') + t + idStr + '="' + v + '"]';
                  }
                  var inner = '';
                  for (var k = 0; k < node.childNodes.length; k++) inner += inlineMd(node.childNodes[k]);
                  inner = inner.trim();
                  if (t === 'code') return inner ? '`' + inner + '`' : '';
                  if (t === 'strong' || t === 'b') return inner ? '**' + inner + '**' : '';
                  if (t === 'em' || t === 'i') return inner ? '*' + inner + '*' : '';
                  if (t === 'a') {
                    var href = node.href || node.getAttribute('href') || '';
                    if (href && href.indexOf('javascript:') !== 0) return '[' + (inner || href) + '](' + href + ')';
                    return inner;
                  }
                  if (t === 'button') {
                    var bix = node.getAttribute('data-fm-agent-idx');
                    return '[' + (bix !== null ? bix + ':' : '') + 'button "' + inner + '"]';
                  }
                  return inner ? inner + ' ' : '';
                }
                var blockSet = {div:1,section:1,article:1,main:1,header:1,footer:1,aside:1,nav:1,p:1,h1:1,h2:1,h3:1,h4:1,h5:1,h6:1,ul:1,ol:1,li:1,pre:1,blockquote:1,table:1,hr:1};
                function blockMd(el, depth) {
                  if (!el || !isVis(el)) return '';
                  var out = '', inBuf = '';
                  function flush() {
                    var s = inBuf.replace(/ {2,}/g, ' ').trim();
                    if (s) out += s + '\n\n';
                    inBuf = '';
                  }
                  for (var i = 0; i < el.childNodes.length; i++) {
                    var ch = el.childNodes[i];
                    if (ch.nodeType === 3) {
                      inBuf += (ch.nodeValue || '').replace(/[\t\r\n ]+/g, ' ');
                      continue;
                    }
                    if (ch.nodeType !== 1 || !isVis(ch)) continue;
                    var tg = ch.tagName.toLowerCase();
                    if (tg === 'script' || tg === 'style' || tg === 'svg' || tg === 'noscript') continue;
                    if (!blockSet[tg]) {
                      inBuf += inlineMd(ch);
                      continue;
                    }
                    flush();
                    if (/^h[1-6]$/.test(tg)) {
                      var lvl = parseInt(tg.charAt(1), 10);
                      var ht = inlineMd(ch).trim();
                      if (ht) out += '\n' + '#'.repeat(lvl) + ' ' + ht + '\n\n';
                    } else if (tg === 'p') {
                      var pt = inlineMd(ch).trim();
                      if (pt) out += pt + '\n\n';
                    } else if (tg === 'pre') {
                      var cd = (ch.innerText || ch.textContent || '').replace(/\s+$/, '');
                      if (cd) out += '\n```\n' + cd + '\n```\n\n';
                    } else if (tg === 'ul' || tg === 'ol') {
                      var ord = tg === 'ol', n = 1, pad = '  '.repeat(depth || 0);
                      for (var j = 0; j < ch.children.length; j++) {
                        var li = ch.children[j];
                        if (!li || li.tagName.toLowerCase() !== 'li' || !isVis(li)) continue;
                        var lt = inlineMd(li).trim();
                        if (lt) out += pad + (ord ? (n++) + '. ' : '- ') + lt + '\n';
                      }
                      out += '\n';
                    } else if (tg === 'table') {
                      var rows = ch.querySelectorAll('tr');
                      for (var r = 0; r < rows.length; r++) {
                        var cells = rows[r].querySelectorAll('th,td'), vals = [];
                        for (var cIdx = 0; cIdx < cells.length; cIdx++) {
                          vals.push(inlineMd(cells[cIdx]).replace(/\n/g, ' ').replace(/\|/g, '\\|').trim());
                        }
                        if (vals.length) {
                          out += '| ' + vals.join(' | ') + ' |\n';
                          if (r === 0) out += '| ' + vals.map(function(){return '---';}).join(' | ') + ' |\n';
                        }
                      }
                      out += '\n';
                    } else if (tg === 'blockquote') {
                      var qt = blockMd(ch, depth).trim();
                      if (qt) out += '> ' + qt.replace(/\n/g, '\n> ') + '\n\n';
                    } else if (tg === 'hr') {
                      out += '\n---\n\n';
                    } else {
                      out += blockMd(ch, depth || 0);
                    }
                  }
                  flush();
                  return out;
                }
                var root = sel ? document.querySelector(sel) : (document.querySelector('main,article,[role="main"]') || document.body);
                if (!root) return 'ERR: Selector not found: ' + sel;
                var rawMd = ('# ' + (document.title || location.href) + '\nURL: ' + location.href + '\n\n' + blockMd(root, 0))
                  .replace(/\n{3,}/g, '\n\n').trim();
                var totalEl = interactive.length;
                if (!withInteractive || totalEl === 0) {
                  if (rawMd.length <= budget) return rawMd;
                  var plainSuffix = '\n… [truncated at ' + budget + ' chars]';
                  return rawMd.slice(0, Math.max(20, budget - plainSuffix.length)) + plainSuffix;
                }
                var fullInterHeader = '\n\n---\n### Interactive Elements (' + totalEl + ')\n';
                var fullCombined = rawMd + fullInterHeader + interactive.join('\n');
                if (fullCombined.length <= budget) return fullCombined;

                var mdCap = Math.min(rawMd.length, Math.max(60, Math.floor(budget * 0.54)));
                var mdPart = rawMd.length > mdCap ? (rawMd.slice(0, Math.max(30, mdCap - 1)) + '…') : rawMd;
                var rem = budget - mdPart.length;
                var secPrefix = '\n\n---\n### Interactive Elements (';
                if (rem < secPrefix.length + 28) {
                  var compactTail = '\n[Interactive Elements: 0/' + totalEl + ' shown, max_chars=' + budget + ']';
                  var tightMd = rawMd.slice(0, Math.max(20, budget - compactTail.length - 1)) + '…';
                  return (tightMd + compactTail).slice(0, budget);
                }
                var reserveTail = 44;
                var usedChars = secPrefix.length + 12 + reserveTail;
                var keptEl = [];
                for (var m = 0; m < totalEl; m++) {
                  var lineCost = interactive[m].length + (keptEl.length > 0 ? 1 : 0);
                  if (usedChars + lineCost > rem) break;
                  keptEl.push(interactive[m]);
                  usedChars += lineCost;
                }
                var headerStr = secPrefix + keptEl.length + '/' + totalEl + ')\n';
                var moreNote = keptEl.length < totalEl ? ((keptEl.length > 0 ? '\n' : '') + '… (+' + (totalEl - keptEl.length) + ' more, raise max_chars)') : '';
                var combined = mdPart + headerStr + keptEl.join('\n') + moreNote;
                return combined.length <= budget ? combined : combined.slice(0, budget);
              } catch (e) {
                return 'ERR: ' + String(e);
              }
            })($selectorJsonLiteral, $includeInteractive);
            """.trimIndent()
        }

        internal fun buildFillFormScript(fieldsJson: String): String = """
            (function(fields) {
              try {
                function setVal(el, val) {
                  var tagUpper = (el.tagName || '').toUpperCase();
                  if (el.isContentEditable && tagUpper !== 'INPUT' && tagUpper !== 'TEXTAREA') {
                    el.textContent = val;
                  } else {
                    var applied = false;
                    try {
                      var proto = tagUpper === 'TEXTAREA'
                        ? (window.HTMLTextAreaElement && window.HTMLTextAreaElement.prototype)
                        : (tagUpper === 'INPUT' ? (window.HTMLInputElement && window.HTMLInputElement.prototype) : null);
                      var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
                      if (desc && typeof desc.set === 'function') { desc.set.call(el, val); applied = true; }
                    } catch (_) {}
                    if (!applied) el.value = val;
                  }
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                  el.dispatchEvent(new Event('change', { bubbles: true }));
                }
                var filled = [];
                for (var key in fields) {
                  if (!Object.prototype.hasOwnProperty.call(fields, key)) continue;
                  var val = String(fields[key] == null ? '' : fields[key]);
                  var target = null;
                  var idxMatch = /^\[?(\d+)\]?$/.exec(key.trim());
                  if (idxMatch) {
                    var list = document.querySelectorAll('[data-fm-agent-idx="' + idxMatch[1] + '"]');
                    target = list.length ? list[0] : null;
                  }
                  if (!target) {
                    try { target = document.querySelector(key); } catch (_) {}
                  }
                  if (!target) {
                    target = document.querySelector('[name="' + key.replace(/"/g, '\\"') + '"]');
                  }
                  if (!target) {
                    var q = key.toLowerCase();
                    var inputs = document.querySelectorAll('input,textarea,select,[contenteditable="true"]');
                    for (var i = 0; i < inputs.length; i++) {
                      var c = inputs[i];
                      var hint = ((c.getAttribute('placeholder') || c.getAttribute('aria-label') || c.id || c.name || '') + '').toLowerCase();
                      if (hint.indexOf(q) >= 0) { target = c; break; }
                    }
                  }
                  if (!target) return 'ERR: Field not found: ' + key;
                  setVal(target, val);
                  filled.push((target.id ? '#' + target.id : target.tagName.toLowerCase()));
                }
                return 'OK: fill_form (' + filled.length + ' fields: ' + filled.join(', ') + ')';
              } catch (e) {
                return 'ERR: ' + String(e);
              }
            })($fieldsJson);
        """.trimIndent()

        internal fun buildCheckSelectorVisibleScript(selectorLiteral: String): String = """
            (function(sel) {
              try {
                var nodes = document.querySelectorAll(sel);
                for (var i = 0; i < nodes.length; i++) {
                  var n = nodes[i], r = n.getBoundingClientRect(), st = window.getComputedStyle(n);
                  if (st.display !== 'none' && st.visibility !== 'hidden' && (r.width > 0 || r.height > 0)) {
                    return 'FOUND:<' + n.tagName.toLowerCase() + (n.id ? '#' + n.id : '') + '>';
                  }
                }
                return 'WAIT';
              } catch (e) { return 'WAIT'; }
            })($selectorLiteral);
        """.trimIndent()

        internal fun buildCheckTextPresentScript(textLiteral: String): String = """
            (function(q) {
              try {
                var body = (document.body ? document.body.innerText : '') || '';
                if (body.toLowerCase().indexOf(String(q).toLowerCase()) >= 0) return 'FOUND:visible';
                return 'WAIT';
              } catch (e) { return 'WAIT'; }
            })($textLiteral);
        """.trimIndent()

        internal fun buildActScript(payloadJson: String): String = """
            (function(cfg) {
              try {
                function isNodeVisible(node) {
                  if (!node || !node.getBoundingClientRect) return false;
                  var r = node.getBoundingClientRect();
                  var st = window.getComputedStyle(node);
                  return st.display !== 'none' && st.visibility !== 'hidden' && (r.width > 0 || r.height > 0);
                }
                function describeEl(node) {
                  if (!node || !node.tagName) return 'unknown';
                  var tag = node.tagName.toLowerCase();
                  var id = node.id ? '#' + node.id : '';
                  var label = ((node.innerText || node.value || node.getAttribute('aria-label') || node.getAttribute('placeholder') || '') + '')
                    .replace(/\s+/g, ' ').trim().slice(0, 48);
                  return '<' + tag + id + '>' + (label ? ' "' + label + '"' : '');
                }
                var el = null;
                if (typeof cfg.index === 'number') {
                  var indexedMatches = document.querySelectorAll('[data-fm-agent-idx="' + cfg.index + '"]');
                  for (var m = 0; m < indexedMatches.length; m++) {
                    if (isNodeVisible(indexedMatches[m])) { el = indexedMatches[m]; break; }
                  }
                  if (!el && indexedMatches.length > 0) el = indexedMatches[indexedMatches.length - 1];
                }
                if (!el && cfg.selector) {
                  el = document.querySelector(cfg.selector);
                }
                if (!el && cfg.text) {
                  var q = cfg.text.toLowerCase();
                  var cands = document.querySelectorAll('a,button,input,textarea,select,[role="button"],label,[contenteditable="true"]');
                  var fallbackMatch = null;
                  for (var i = 0; i < cands.length; i++) {
                    var c = cands[i];
                    var txt = ((c.innerText || c.value || c.getAttribute('aria-label') || c.getAttribute('placeholder') || '') + '').toLowerCase();
                    if (txt.indexOf(q) >= 0) {
                      if (isNodeVisible(c)) { el = c; break; }
                      if (!fallbackMatch) fallbackMatch = c;
                    }
                  }
                  if (!el) el = fallbackMatch;
                }
                if (!el && cfg.action === 'press_enter') {
                  el = document.activeElement;
                }
                if (!el) return 'ERR: Element not found (' + JSON.stringify(cfg) + ')';
                try {
                  el.scrollIntoView({ behavior: 'instant', block: 'center', inline: 'nearest' });
                } catch (_) {
                  try { el.scrollIntoView(true); } catch (_2) {}
                }
                if (cfg.action === 'click') {
                  try { el.focus(); } catch (_) {}
                  el.click();
                  return 'OK: click -> ' + describeEl(el);
                }
                if (cfg.action === 'type') {
                  var val = cfg.value || '';
                  var tagUpper = (el.tagName || '').toUpperCase();
                  if (tagUpper !== 'INPUT' && tagUpper !== 'TEXTAREA' && !el.isContentEditable) {
                    var nested = el.querySelector ? el.querySelector('input,textarea,[contenteditable="true"]') : null;
                    if (nested) {
                      el = nested;
                      tagUpper = (el.tagName || '').toUpperCase();
                    }
                  }
                  if (tagUpper !== 'INPUT' && tagUpper !== 'TEXTAREA' && !el.isContentEditable) {
                    return 'ERR: Target element is not editable: ' + describeEl(el);
                  }
                  try { el.focus(); } catch (_) {}
                  if (el.isContentEditable && tagUpper !== 'INPUT' && tagUpper !== 'TEXTAREA') {
                    el.textContent = val;
                  } else {
                    var applied = false;
                    try {
                      var proto = tagUpper === 'TEXTAREA'
                        ? (window.HTMLTextAreaElement && window.HTMLTextAreaElement.prototype)
                        : (tagUpper === 'INPUT' ? (window.HTMLInputElement && window.HTMLInputElement.prototype) : null);
                      var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
                      if (desc && typeof desc.set === 'function') {
                        desc.set.call(el, val);
                        applied = true;
                      }
                    } catch (_) {}
                    if (!applied) {
                      el.value = val;
                    }
                  }
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                  el.dispatchEvent(new Event('change', { bubbles: true }));
                  return 'OK: type (' + val.length + ' chars) -> ' + describeEl(el);
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
                  return 'OK: press_enter -> ' + describeEl(el);
                }
                return 'ERR: Unknown action ' + cfg.action;
              } catch (e) {
                return 'ERR: ' + String(e);
              }
            })($payloadJson);
        """.trimIndent()

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
