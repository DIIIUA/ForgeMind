package com.forgemind.app.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.forgemind.app.floating.FloatingChatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

data class CapturedWindowTag(
    val tagId: Int,
    val windowTag: String,
    val windowId: Int,
    val packageName: String,
    val className: String,
    val viewResourceId: String?,
    val text: String,
    val contentDesc: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean
)

data class CapturedScreenFile(
    val file: File,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val originalWidth: Int,
    val originalHeight: Int,
    val elapsedMs: Long
)

class ForgeAccessibilityService : AccessibilityService() {

    private val tagSnapshot = ConcurrentHashMap<Int, CapturedWindowTag>()
    private val knownImePackages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastForegroundAppPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerAppContext(applicationContext)
        seedDefaultImePackage()
        runCatching {
            val cur = serviceInfo ?: AccessibilityServiceInfo()
            cur.flags = cur.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            cur.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            cur.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            cur.notificationTimeout = 220L
            serviceInfo = cur
        }
        instance = this
        _isConnected.value = true
    }

    private fun seedDefaultImePackage() {
        val rawIme = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull().orEmpty()
        val imePkg = rawIme.substringBefore('/').trim()
        if (imePkg.isNotEmpty()) {
            knownImePackages += imePkg
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Lightweight foreground package tracker (zero node traversal or background polling).
        val ev = event ?: return
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = ev.packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return
        if (pkg in knownImePackages) return
        lastForegroundAppPkg = pkg
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        lastForegroundAppPkg = null
        tagSnapshot.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        lastForegroundAppPkg = null
        tagSnapshot.clear()
        super.onDestroy()
    }

    private data class RawWindowCandidate(
        val windowId: Int,
        val rawType: Int,
        val typeLabel: String,
        val title: String,
        val pkg: String,
        val bounds: Rect,
        val focused: Boolean,
        val active: Boolean,
        val root: AccessibilityNodeInfo
    )

    private fun climbToWindowRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur = node
        var guard = 0
        while (guard++ < 48) {
            val parent = runCatching { cur.parent }.getOrNull() ?: break
            cur = parent
        }
        return cur
    }

    private fun hasRenderableContent(root: AccessibilityNodeInfo): Boolean {
        if (root.childCount > 0) return true
        if (!root.text.isNullOrBlank() || !root.contentDescription.isNullOrBlank()) return true
        return false
    }

    private fun collectWindowCandidatesOnce(
        selfPkg: String,
        includeSelf: Boolean,
        filterClean: String?
    ): List<RawWindowCandidate> {
        val rawWindows = runCatching { windows }.getOrNull().orEmpty()
        val list = mutableListOf<RawWindowCandidate>()
        val appWindowPackages = HashSet<String>()
        val imeWindowPackages = HashSet<String>()
        val systemWindowPackages = HashSet<String>()

        for (w in rawWindows) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (root.childCount == 0) {
                runCatching { root.refresh() }
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty()) {
                when (w.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> {
                        appWindowPackages += pkg
                        if (pkg != selfPkg && pkg != "com.android.systemui" && pkg != "android" && pkg !in knownImePackages) {
                            if (w.isActive || w.isFocused) {
                                lastForegroundAppPkg = pkg
                            }
                        }
                    }
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> {
                        imeWindowPackages += pkg
                        knownImePackages += pkg
                    }
                    AccessibilityWindowInfo.TYPE_SYSTEM -> {
                        systemWindowPackages += pkg
                    }
                }
            }
            if (!includeSelf && pkg == selfPkg) continue
            val title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                w.title?.toString().orEmpty()
            } else ""
            if (filterClean != null) {
                val hay = "$pkg $title".lowercase()
                if (!hay.contains(filterClean)) continue
            }
            val b = Rect()
            runCatching { w.getBoundsInScreen(b) }
            list += RawWindowCandidate(
                windowId = w.id,
                rawType = w.type,
                typeLabel = windowTypeName(w.type),
                title = title,
                pkg = pkg,
                bounds = b,
                focused = w.isFocused,
                active = w.isActive,
                root = root
            )
        }

        val trackedFgPkg = lastForegroundAppPkg
        // Never blacklist a package that owns an active/known TYPE_APPLICATION window just because
        // OEM ROMs (HyperOS / MIUI) attach an auxiliary TYPE_SYSTEM bar/overlay under the same package.
        val nonAppWindowPackages = HashSet<String>().apply {
            add("com.android.systemui")
            addAll(knownImePackages)
            addAll(imeWindowPackages)
            for (sysPkg in systemWindowPackages) {
                if (sysPkg !in appWindowPackages && sysPkg != trackedFgPkg) {
                    add(sysPkg)
                }
            }
        }

        // Resolve input-focused root as well: when IME keyboard pops up (e.g. clicking "Search settings"),
        // WindowManager can temporarily hand `isActive` to TYPE_INPUT_METHOD or omit the transitioning APP window,
        // while FOCUS_INPUT points directly at the live EditText inside the foreground app window!
        val focusNode = runCatching {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        }.getOrNull()
        val focusRoot = focusNode?.let { climbToWindowRoot(it) }
        val focusPkg = focusRoot?.packageName?.toString().orEmpty()
        val focusWinId = focusRoot?.windowId ?: -1

        // Promote matching APP window to [active,focused] if it owns input focus while IME is open
        if (focusRoot != null && focusPkg.isNotEmpty() && focusPkg !in nonAppWindowPackages) {
            if (focusPkg != selfPkg && focusPkg != "android") {
                lastForegroundAppPkg = focusPkg
            }
            for (i in list.indices) {
                val c = list[i]
                if (c.rawType == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (c.windowId == focusWinId || c.pkg == focusPkg) &&
                    (!c.active || !c.focused)
                ) {
                    list[i] = c.copy(active = true, focused = true)
                }
            }
        }

        val fallbackAppRoots = buildList {
            runCatching { rootInActiveWindow }.getOrNull()?.let { add(it) }
            if (focusRoot != null) add(focusRoot)
        }

        for (extraRoot in fallbackAppRoots) {
            if (extraRoot.childCount == 0) {
                runCatching { extraRoot.refresh() }
            }
            val extraPkg = extraRoot.packageName?.toString().orEmpty()
            if (extraPkg.isEmpty()) continue
            val isKnownNonApp = extraPkg in nonAppWindowPackages || extraPkg == "com.android.systemui"
            if (isKnownNonApp && (filterClean == null || !extraPkg.lowercase().contains(filterClean))) continue
            val allowedPkg = includeSelf || extraPkg != selfPkg
            val matchesFilter = filterClean == null || extraPkg.lowercase().contains(filterClean)
            val alreadyCaptured = list.any {
                it.windowId == extraRoot.windowId ||
                    (it.rawType == AccessibilityWindowInfo.TYPE_APPLICATION && it.pkg == extraPkg)
            }
            if (allowedPkg && matchesFilter && !alreadyCaptured) {
                val b = Rect()
                runCatching { extraRoot.getBoundsInScreen(b) }
                list += RawWindowCandidate(
                    windowId = extraRoot.windowId,
                    rawType = AccessibilityWindowInfo.TYPE_APPLICATION,
                    typeLabel = "APP",
                    title = "",
                    pkg = extraPkg,
                    bounds = b,
                    focused = true,
                    active = true,
                    root = extraRoot
                )
            }
        }

        // Ensure at least the top foreground APP window carries [active] when only IME/SYSTEM claimed active
        val effectiveFgPkg = lastForegroundAppPkg
        val hasAnyActiveApp = list.any { it.rawType == AccessibilityWindowInfo.TYPE_APPLICATION && (it.active || it.focused) }
        if (!hasAnyActiveApp) {
            val preferredIdx = if (!effectiveFgPkg.isNullOrEmpty()) {
                list.indexOfFirst { it.rawType == AccessibilityWindowInfo.TYPE_APPLICATION && it.pkg == effectiveFgPkg }
            } else -1
            val targetIdx = if (preferredIdx >= 0) {
                preferredIdx
            } else {
                list.indexOfFirst { it.rawType == AccessibilityWindowInfo.TYPE_APPLICATION }
            }
            if (targetIdx >= 0) {
                list[targetIdx] = list[targetIdx].copy(active = true)
            }
        }

        // Prioritize foreground APP windows first so IME keyboard (80+ keys) or system bars never starve APP tags
        return list.sortedWith(
            compareBy<RawWindowCandidate>(
                { cand ->
                    when (cand.rawType) {
                        AccessibilityWindowInfo.TYPE_APPLICATION -> 0
                        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> 1
                        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> 2
                        AccessibilityWindowInfo.TYPE_SYSTEM -> 3
                        else -> 1
                    }
                },
                { cand -> if (cand.active || cand.focused) 0 else 1 },
                { cand -> if (!effectiveFgPkg.isNullOrEmpty() && cand.pkg == effectiveFgPkg) 0 else 1 }
            )
        )
    }

    suspend fun captureWindowsDom(
        includeSelf: Boolean = false,
        onlyInteractive: Boolean = false,
        windowFilter: String? = null,
        maxNodes: Int = 260
    ): String {
        FloatingChatService.yieldWindowFocusForScreenAction()

        val selfPkg = packageName
        val filterClean = windowFilter?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

        var candidates = collectWindowCandidatesOnce(selfPkg, includeSelf, filterClean)
        // Wait out Activity/Search/IME transition animations (up to ~850ms on heavy HyperOS transitions,
        // 0ms fast-path when a renderable APP window is already on screen).
        var retryAttempt = 0
        while (retryAttempt < 10) {
            val hasLiveApp = candidates.any {
                it.rawType == AccessibilityWindowInfo.TYPE_APPLICATION && hasRenderableContent(it.root)
            }
            val shouldWait = if (filterClean == null) !hasLiveApp else candidates.isEmpty()
            if (!shouldWait) break
            delay(85L)
            candidates = collectWindowCandidatesOnce(selfPkg, includeSelf, filterClean)
            retryAttempt++
        }

        if (candidates.isEmpty()) {
            return "На экране не найдено подходящих внешних окон (include_self=$includeSelf, filter=${windowFilter ?: "none"})."
        }

        val imeTop: Int? = candidates
            .filter { it.rawType == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.bounds.height() > 80 }
            .minOfOrNull { it.bounds.top }

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        tagSnapshot.clear()
        val cappedMax = maxNodes.coerceIn(20, 600)
        var nextTagId = 1
        var truncated = false

        val windowSections = mutableListOf<String>()
        var emittedWins = 0

        for (win in candidates) {
            val tentativeWTag = "W${emittedWins + 1}"
            val collected = mutableListOf<CapturedWindowTag>()
            var windowTruncatedByLimit = false
            var occludedByImeNodes = 0
            val isAppWindow = win.rawType == AccessibilityWindowInfo.TYPE_APPLICATION

            fun visit(node: AccessibilityNodeInfo?, isRoot: Boolean = false) {
                if (node == null) return
                if (nextTagId > cappedMax) {
                    truncated = true
                    windowTruncatedByLimit = true
                    return
                }

                val rect = Rect()
                node.getBoundsInScreen(rect)
                val validBounds = rect.width() > 4 && rect.height() > 4 &&
                    rect.right > 0 && rect.bottom > 0 && rect.left < screenW && rect.top < screenH

                val visibleAboveIme = imeTop != null && isAppWindow && validBounds && rect.centerY() < imeTop
                val occludedUnderIme = imeTop != null && isAppWindow && validBounds && rect.centerY() >= imeTop
                val nodeVisible = node.isVisibleToUser || visibleAboveIme

                if (!nodeVisible) {
                    if (occludedUnderIme) occludedByImeNodes++
                    // Do not abort traversal on container views whose lower portion is overlapped by IME
                    if (!isRoot && node.childCount <= 0) return
                }

                val rawText = node.text?.toString()?.trim().orEmpty()
                val rawDesc = node.contentDescription?.toString()?.trim().orEmpty()
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    node.hintText?.toString()?.trim().orEmpty()
                } else ""
                val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    node.stateDescription?.toString()?.trim().orEmpty()
                } else ""

                val clickable = node.isClickable
                val longClickable = node.isLongClickable
                val editable = node.isEditable
                val scrollable = node.isScrollable
                val checkable = node.isCheckable
                val interactive = clickable || longClickable || editable || scrollable || checkable

                val primaryLabel = when {
                    rawText.isNotEmpty() -> rawText
                    rawDesc.isNotEmpty() -> rawDesc
                    hint.isNotEmpty() -> "hint:$hint"
                    stateDesc.isNotEmpty() -> "state:$stateDesc"
                    else -> ""
                }

                val shouldEmit = nodeVisible && validBounds && (
                    if (onlyInteractive) interactive
                    else (interactive || primaryLabel.isNotEmpty())
                )

                val resolvedLabel = if (shouldEmit && primaryLabel.isEmpty() && interactive) {
                    collectShortChildText(node, maxDepth = 3).take(90)
                } else {
                    primaryLabel
                }

                if (shouldEmit && (interactive || resolvedLabel.isNotEmpty())) {
                    val tagId = nextTagId++
                    val item = CapturedWindowTag(
                        tagId = tagId,
                        windowTag = tentativeWTag,
                        windowId = win.windowId,
                        packageName = node.packageName?.toString().orEmpty(),
                        className = shortClassName(node.className?.toString()),
                        viewResourceId = node.viewIdResourceName?.substringAfterLast('/'),
                        text = resolvedLabel.replace(Regex("\\s+"), " ").take(180),
                        contentDesc = rawDesc.replace(Regex("\\s+"), " ").take(120),
                        bounds = rect,
                        clickable = clickable,
                        longClickable = longClickable,
                        editable = editable,
                        scrollable = scrollable,
                        checkable = checkable,
                        checked = node.isChecked
                    )
                    tagSnapshot[tagId] = item
                    collected += item
                }

                val childCount = node.childCount
                if (childCount > 0 && nextTagId <= cappedMax) {
                    val children = ArrayList<Pair<Rect, AccessibilityNodeInfo>>(childCount)
                    for (i in 0 until childCount) {
                        val ch = runCatching { node.getChild(i) }.getOrNull() ?: continue
                        val cr = Rect()
                        ch.getBoundsInScreen(cr)
                        children += cr to ch
                    }
                    children.sortWith(compareBy<Pair<Rect, AccessibilityNodeInfo>>({ it.first.top / 24 }, { it.first.left }))
                    for ((_, ch) in children) {
                        visit(ch, isRoot = false)
                    }
                } else if (childCount > 0 && nextTagId > cappedMax) {
                    truncated = true
                    windowTruncatedByLimit = true
                }
            }

            visit(win.root, isRoot = true)

            // Skip passive status-bar / non-interactive SYSTEM overlays when unfiltered `only_interactive=true` is requested
            if (collected.isEmpty() && !windowTruncatedByLimit && onlyInteractive && filterClean == null &&
                win.rawType == AccessibilityWindowInfo.TYPE_SYSTEM
            ) {
                continue
            }

            emittedWins++
            val wTag = "W$emittedWins"
            val titlePart = if (win.title.isNotBlank()) " title=\"${win.title.replace('\n', ' ')}\"" else ""
            val statePart = buildList {
                if (win.active) add("active")
                if (win.focused) add("focused")
            }.joinToString(",")
            val stateSuffix = if (statePart.isNotEmpty()) " [$statePart]" else ""

            val sec = StringBuilder()
            sec.appendLine("[$wTag] type=${win.typeLabel} pkg=${win.pkg.ifBlank { "?" }}$titlePart bounds=(${win.bounds.left},${win.bounds.top})-(${win.bounds.right},${win.bounds.bottom})$stateSuffix")

            if (collected.isEmpty()) {
                when {
                    windowTruncatedByLimit -> sec.appendLine("  (узлы окна не вошли из-за лимита max_nodes=$cappedMax — укажите window_filter=\"${win.pkg}\" или увеличьте max_nodes)")
                    occludedByImeNodes > 0 -> sec.appendLine("  (элементы этого участка окна перекрыты открытой клавиатурой IME — скройте клавиатуру через action='back' или прокрутите окно)")
                    onlyInteractive -> sec.appendLine("  (нет интерактивных элементов при only_interactive=true; для чтения текста и индикаторов вызовите с only_interactive=false)")
                    else -> sec.appendLine("  (нет видимых интерактивных или текстовых узлов)")
                }
            } else {
                for (t in collected) {
                    val flags = buildList {
                        if (t.editable) add("input")
                        else if (t.clickable) add("click")
                        if (t.longClickable && !t.clickable) add("long_click")
                        if (t.scrollable) add("scroll")
                        if (t.checkable) add(if (t.checked) "checked" else "unchecked")
                    }.joinToString(",")
                    val flagBadge = if (flags.isNotEmpty()) " {$flags}" else ""
                    val idPart = t.viewResourceId?.takeIf { it.isNotBlank() }?.let { " #$it" }.orEmpty()
                    val textPart = if (t.text.isNotBlank()) " \"${t.text}\"" else ""
                    sec.appendLine(
                        "  [#${t.tagId}] <${t.className}$idPart>$flagBadge$textPart @(${t.bounds.centerX()},${t.bounds.centerY()})"
                    )
                }
            }
            windowSections += sec.toString().trimEnd()
        }

        if (windowSections.isEmpty()) {
            return "На экране не найдено интерактивных элементов в видимых окнах (only_interactive=$onlyInteractive, include_self=$includeSelf, filter=${windowFilter ?: "none"} — попробуйте only_interactive=false)."
        }

        val sb = StringBuilder()
        sb.appendLine("=== ЭКРАН ANDROID: WINDOW-TAG DOM (окон: ${windowSections.size}) ===")
        for (section in windowSections) {
            sb.appendLine()
            sb.appendLine(section)
        }

        if (truncated) {
            sb.appendLine()
            sb.appendLine("... [Вывод ограничен $cappedMax тегами; укажите window_filter или only_interactive=true для сужения]")
        }
        sb.appendLine()
        sb.append("Подсказка: используйте screen_act_tag или пакетный screen_act_batch для мгновенных действий без задержек.")
        return sb.toString()
    }

    suspend fun actOnTag(
        tagId: Int?,
        action: String,
        text: String? = null,
        submit: Boolean = false
    ): Result<String> {
        FloatingChatService.yieldWindowFocusForScreenAction()
        val normAction = action.trim().lowercase()

        performGlobalNavIfMatched(normAction)?.let { return it }

        val id = tagId ?: return Result.failure(
            IllegalArgumentException("Для действия '$normAction' необходим параметр tag (сначала вызовите screen_read_windows или используйте screen_act_batch с label/x,y).")
        )
        val captured = tagSnapshot[id] ?: return Result.failure(
            IllegalStateException("Тег [#$id] не найден в текущем снимке. Вызовите screen_read_windows для обновления тегов.")
        )

        val liveNode = findBestLiveNode(captured)

        return when (normAction) {
            "click", "tap" -> {
                val cx = captured.bounds.centerX().toFloat()
                val cy = captured.bounds.centerY().toFloat()
                val clickedViaNode = liveNode?.let { performOnSelfOrAncestor(it, AccessibilityNodeInfo.ACTION_CLICK) } ?: false
                if (clickedViaNode) {
                    showTouchPulse(cx, cy)
                    delay(65L)
                    Result.success("Нажат элемент [#$id] (${captured.windowTag} <${captured.className}> \"${captured.text.take(50)}\") через узел Accessibility.")
                } else {
                    val tapped = dispatchTapGesture(cx, cy, 75L)
                    if (tapped) {
                        delay(65L)
                        Result.success("Нажат элемент [#$id] по точным координатам (${captured.bounds.centerX()}, ${captured.bounds.centerY()}).")
                    } else {
                        Result.failure(IllegalStateException("Не удалось нажать элемент [#$id]. Обновите снимок через screen_read_windows."))
                    }
                }
            }

            "long_click", "long_press" -> {
                val cx = captured.bounds.centerX().toFloat()
                val cy = captured.bounds.centerY().toFloat()
                val okNode = liveNode?.let { performOnSelfOrAncestor(it, AccessibilityNodeInfo.ACTION_LONG_CLICK) } ?: false
                if (okNode) {
                    showTouchPulse(cx, cy)
                    Result.success("Долгое нажатие на [#$id] выполнено.")
                } else {
                    val tapped = dispatchTapGesture(cx, cy, 620L)
                    if (tapped) Result.success("Долгое нажатие на [#$id] выполнено жестом (${captured.bounds.centerX()}, ${captured.bounds.centerY()}).")
                    else Result.failure(IllegalStateException("Не удалось выполнить долгое нажатие на [#$id]."))
                }
            }

            "focus" -> {
                val cx = captured.bounds.centerX().toFloat()
                val cy = captured.bounds.centerY().toFloat()
                val ok = liveNode?.let {
                    it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                        it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } ?: dispatchTapGesture(cx, cy, 70L)
                if (ok) {
                    showTouchPulse(cx, cy)
                    Result.success("Фокус установлен на [#$id].")
                } else {
                    Result.failure(IllegalStateException("Не удалось установить фокус на [#$id]."))
                }
            }

            "set_text", "type", "input", "append_text" -> {
                val value = text ?: return Result.failure(IllegalArgumentException("Для $normAction укажите параметр text."))
                val target = liveNode?.let { findEditableSelfOrChild(it) }
                    ?: return Result.failure(IllegalStateException("Поле ввода [#$id] больше не активно на экране. Вызовите screen_read_windows."))
                applyTextToEditableNode(
                    target = target,
                    value = value,
                    append = normAction == "append_text",
                    submit = submit,
                    labelForReport = "[#$id]"
                ).also {
                    if (it.isSuccess) {
                        showTouchPulse(captured.bounds.centerX().toFloat(), captured.bounds.centerY().toFloat())
                    }
                }
            }

            "scroll_forward", "scroll_down" -> {
                val scrolled = liveNode?.let { performOnSelfOrAncestor(it, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } ?: false
                if (scrolled) {
                    Result.success("Прокрутка вперёд выполнена на [#$id].")
                } else {
                    val cx = captured.bounds.centerX().toFloat()
                    val startY = (captured.bounds.bottom - captured.bounds.height() * 0.22f).coerceAtLeast(captured.bounds.top + 10f)
                    val endY = (captured.bounds.top + captured.bounds.height() * 0.22f).coerceAtMost(startY - 40f)
                    val swiped = dispatchSwipeGesture(cx, startY, cx, endY, 240L)
                    if (swiped) Result.success("Прокрутка вперёд (свайп вверх) выполнена в границах [#$id].")
                    else Result.failure(IllegalStateException("Не удалось прокрутить [#$id]."))
                }
            }

            "scroll_backward", "scroll_up" -> {
                val scrolled = liveNode?.let { performOnSelfOrAncestor(it, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } ?: false
                if (scrolled) {
                    Result.success("Прокрутка назад выполнена на [#$id].")
                } else {
                    val cx = captured.bounds.centerX().toFloat()
                    val startY = (captured.bounds.top + captured.bounds.height() * 0.22f)
                    val endY = (captured.bounds.bottom - captured.bounds.height() * 0.22f).coerceAtLeast(startY + 40f)
                    val swiped = dispatchSwipeGesture(cx, startY, cx, endY, 240L)
                    if (swiped) Result.success("Прокрутка назад (свайп вниз) выполнена в границах [#$id].")
                    else Result.failure(IllegalStateException("Не удалось прокрутить [#$id]."))
                }
            }

            else -> Result.failure(
                IllegalArgumentException("Неизвестное действие '$action'. Допустимы: click, long_click, set_text, append_text, focus, scroll_forward, scroll_backward, back, home, recents, notifications, quick_settings.")
            )
        }
    }

    /**
     * Executes a multi-step UI sequence in RAM without network round-trip latency between steps.
     */
    suspend fun executeBatch(
        steps: JsonArray,
        stopOnError: Boolean = true,
        returnDom: Boolean = true,
        includeSelf: Boolean = false,
        onlyInteractive: Boolean = false,
        windowFilter: String? = null,
        maxNodes: Int = 220
    ): Pair<Boolean, String> {
        if (steps.isEmpty()) {
            return true to "Передан пустой список шагов steps=[]."
        }
        FloatingChatService.yieldWindowFocusForScreenAction()
        val cappedSteps = if (steps.size > MAX_BATCH_STEPS) steps.take(MAX_BATCH_STEPS) else steps.toList()
        val batchStart = SystemClock.elapsedRealtime()
        val report = StringBuilder()
        var hadError = false

        for ((idx, el) in cappedSteps.withIndex()) {
            val stepNum = idx + 1
            val stepObj = el as? JsonObject
            if (stepObj == null) {
                hadError = true
                report.appendLine("[$stepNum/${cappedSteps.size}] ERROR: шаг должен быть JSON-объектом.")
                if (stopOnError) break else continue
            }

            val stepStart = SystemClock.elapsedRealtime()
            val stepRes = executeSingleBatchStep(stepObj, includeSelf, onlyInteractive, windowFilter, maxNodes)
            val dt = SystemClock.elapsedRealtime() - stepStart

            stepRes.fold(
                onSuccess = { msg ->
                    report.appendLine("[$stepNum/${cappedSteps.size}] OK (${dt}ms): $msg")
                },
                onFailure = { err ->
                    hadError = true
                    report.appendLine("[$stepNum/${cappedSteps.size}] ERROR (${dt}ms): ${err.message}")
                }
            )
            if (stepRes.isFailure && stopOnError) {
                break
            }
        }

        val totalMs = SystemClock.elapsedRealtime() - batchStart
        report.insert(0, "=== ПАКЕТ ДЕЙСТВИЙ ВЫПОЛНЕН (${totalMs}ms, шагов: ${cappedSteps.size}) ===\n")

        if (returnDom) {
            // Brief settle pause if the last step was an interactive UI mutation
            val lastAction = (cappedSteps.lastOrNull() as? JsonObject)
                ?.let { (it["action"] as? JsonPrimitive)?.contentOrNull?.lowercase() }
                .orEmpty()
            if (lastAction !in setOf("wait_ms", "sleep", "read_windows", "refresh_dom")) {
                delay(90L)
            }
            report.appendLine()
            report.append(
                captureWindowsDom(
                    includeSelf = includeSelf,
                    onlyInteractive = onlyInteractive,
                    windowFilter = windowFilter,
                    maxNodes = maxNodes
                )
            )
        }
        return (!hadError) to report.toString().trimEnd()
    }

    private suspend fun executeSingleBatchStep(
        step: JsonObject,
        includeSelf: Boolean,
        onlyInteractive: Boolean,
        windowFilter: String?,
        maxNodes: Int
    ): Result<String> {
        fun str(k: String): String? = (step[k] as? JsonPrimitive)?.contentOrNull
        fun int(k: String): Int? = (step[k] as? JsonPrimitive)?.intOrNull
        fun long(k: String): Long? = (step[k] as? JsonPrimitive)?.longOrNull
        fun float(k: String): Float? = (step[k] as? JsonPrimitive)?.floatOrNull
        fun bool(k: String, def: Boolean): Boolean = (step[k] as? JsonPrimitive)?.booleanOrNull ?: def

        val action = str("action")?.trim()?.lowercase()
            ?: return Result.failure(IllegalArgumentException("Отсутствует поле 'action' в шаге пакета"))

        performGlobalNavIfMatched(action)?.let { return it }

        return when (action) {
            "read_windows", "refresh_dom" -> {
                captureWindowsDom(
                    includeSelf = bool("include_self", includeSelf),
                    onlyInteractive = bool("only_interactive", onlyInteractive),
                    windowFilter = str("window_filter") ?: windowFilter,
                    maxNodes = int("max_nodes") ?: maxNodes
                )
                Result.success("Снимок Window-Tag DOM обновлён (тегов: ${tagSnapshot.size})")
            }

            "wait_ms", "sleep" -> {
                val ms = (long("ms") ?: long("duration_ms") ?: 200L).coerceIn(10L, 10_000L)
                delay(ms)
                Result.success("Пауза ${ms}ms")
            }

            "wait_text" -> {
                val query = (str("label") ?: str("text"))?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return Result.failure(IllegalArgumentException("Для wait_text укажите label или text"))
                val timeoutMs = (long("timeout_ms") ?: 5_000L).coerceIn(100L, 15_000L)
                val clickFound = bool("click_when_found", false)
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                var matched: Pair<AccessibilityNodeInfo, Rect>? = null
                while (SystemClock.elapsedRealtime() <= deadline) {
                    matched = findSmallestNodeByLabel(query, includeSelf = bool("include_self", includeSelf))
                    if (matched != null) break
                    delay(120L)
                }
                val pair = matched
                    ?: return Result.failure(IllegalStateException("Текст '$query' не появился за ${timeoutMs}ms"))
                if (clickFound) {
                    val (node, rect) = pair
                    val clicked = performOnSelfOrAncestor(node, AccessibilityNodeInfo.ACTION_CLICK) ||
                        dispatchTapGesture(rect.centerX().toFloat(), rect.centerY().toFloat(), 75L)
                    if (!clicked) {
                        return Result.failure(IllegalStateException("Текст '$query' найден, но клик не удался"))
                    }
                    showTouchPulse(rect.centerX().toFloat(), rect.centerY().toFloat())
                    Result.success("Найден и нажат '$query' @(${rect.centerX()},${rect.centerY()})")
                } else {
                    Result.success("Найден '$query' @(${pair.second.centerX()},${pair.second.centerY()})")
                }
            }

            "open_app" -> {
                val pkg = (str("package_name") ?: str("pkg") ?: str("text"))?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return Result.failure(IllegalArgumentException("Для open_app укажите package_name"))
                val launch = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } ?: return Result.failure(IllegalStateException("Не найден лаунчер-интент для пакета '$pkg'"))
                runCatching { startActivity(launch) }.fold(
                    onSuccess = { Result.success("Открыто приложение $pkg") },
                    onFailure = { Result.failure(IllegalStateException("Ошибка запуска $pkg: ${it.message}")) }
                )
            }

            "tap_xy" -> {
                val x = float("x") ?: return Result.failure(IllegalArgumentException("Для tap_xy укажите x и y"))
                val y = float("y") ?: return Result.failure(IllegalArgumentException("Для tap_xy укажите x и y"))
                val dur = (long("duration_ms") ?: 75L).coerceIn(30L, 1_500L)
                if (dispatchTapGesture(x, y, dur)) {
                    Result.success("Тап по координатам (${x.roundToInt()}, ${y.roundToInt()})")
                } else {
                    Result.failure(IllegalStateException("Жест tap_xy не выполнен"))
                }
            }

            "swipe" -> {
                val x1 = float("x1") ?: return Result.failure(IllegalArgumentException("Для swipe укажите x1, y1, x2, y2"))
                val y1 = float("y1") ?: return Result.failure(IllegalArgumentException("Для swipe укажите x1, y1, x2, y2"))
                val x2 = float("x2") ?: return Result.failure(IllegalArgumentException("Для swipe укажите x1, y1, x2, y2"))
                val y2 = float("y2") ?: return Result.failure(IllegalArgumentException("Для swipe укажите x1, y1, x2, y2"))
                val dur = (long("duration_ms") ?: 250L).coerceIn(50L, 2_500L)
                if (dispatchSwipeGesture(x1, y1, x2, y2, dur)) {
                    Result.success("Свайп (${x1.roundToInt()},${y1.roundToInt()}) -> (${x2.roundToInt()},${y2.roundToInt()})")
                } else {
                    Result.failure(IllegalStateException("Жест swipe не выполнен"))
                }
            }

            "click", "tap", "long_click", "long_press" -> {
                val isLong = action == "long_click" || action == "long_press"
                val tag = int("tag")
                if (tag != null) {
                    return actOnTag(tagId = tag, action = action)
                }
                val label = (str("label") ?: str("target_text"))?.trim()?.takeIf { it.isNotEmpty() }
                if (label != null) {
                    val (node, rect) = findSmallestNodeByLabel(label, includeSelf = bool("include_self", includeSelf))
                        ?: return Result.failure(IllegalStateException("Элемент с надписью '$label' не найден на экране"))
                    val nodeAction = if (isLong) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                    val cx = rect.centerX().toFloat()
                    val cy = rect.centerY().toFloat()
                    val viaNode = performOnSelfOrAncestor(node, nodeAction)
                    if (viaNode) {
                        showTouchPulse(cx, cy)
                        return Result.success("${if (isLong) "Долгое нажатие" else "Нажат"} '$label' через узел @(${rect.centerX()},${rect.centerY()})")
                    }
                    val tapped = dispatchTapGesture(cx, cy, if (isLong) 620L else 75L)
                    return if (tapped) {
                        Result.success("${if (isLong) "Долгое нажатие" else "Нажат"} '$label' жестом @(${rect.centerX()},${rect.centerY()})")
                    } else {
                        Result.failure(IllegalStateException("Не удалось нажать '$label'"))
                    }
                }
                val x = float("x")
                val y = float("y")
                if (x != null && y != null) {
                    val dur = (long("duration_ms") ?: if (isLong) 620L else 75L).coerceIn(30L, 2_000L)
                    return if (dispatchTapGesture(x, y, dur)) {
                        Result.success("${if (isLong) "Долгий тап" else "Тап"} (${x.roundToInt()}, ${y.roundToInt()})")
                    } else {
                        Result.failure(IllegalStateException("Не удалось выполнить тап по координатам"))
                    }
                }
                Result.failure(IllegalArgumentException("Для '$action' укажите tag, label или координаты x, y"))
            }

            "set_text", "type", "input", "append_text" -> {
                val value = str("text") ?: return Result.failure(IllegalArgumentException("Для $action укажите text"))
                val submit = bool("submit", false)
                val tag = int("tag")
                if (tag != null) {
                    return actOnTag(tagId = tag, action = action, text = value, submit = submit)
                }
                val label = (str("label") ?: str("target_text"))?.trim()?.takeIf { it.isNotEmpty() }
                val targetNode: AccessibilityNodeInfo? = if (label != null) {
                    findSmallestNodeByLabel(label, includeSelf = bool("include_self", includeSelf))
                        ?.first
                        ?.let { findEditableSelfOrChild(it) }
                } else {
                    findFocusedOrFirstEditableNode(includeSelf = bool("include_self", includeSelf))
                }
                val target = targetNode ?: return Result.failure(
                    IllegalStateException("Не найдено активное поле ввода на экране (укажите tag или label поля)")
                )
                val bounds = Rect().also { target.getBoundsInScreen(it) }
                applyTextToEditableNode(
                    target = target,
                    value = value,
                    append = action == "append_text",
                    submit = submit,
                    labelForReport = label?.let { "'$it'" } ?: "активное поле"
                ).also {
                    if (it.isSuccess && bounds.width() > 0) {
                        showTouchPulse(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    }
                }
            }

            "scroll_forward", "scroll_down", "scroll_backward", "scroll_up" -> {
                val tag = int("tag")
                if (tag != null) {
                    return actOnTag(tagId = tag, action = action)
                }
                val forward = action == "scroll_forward" || action == "scroll_down"
                val nodeAction = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                val scrollable = findFirstScrollableNode(includeSelf = bool("include_self", includeSelf))
                if (scrollable != null && scrollable.performAction(nodeAction)) {
                    Result.success("Прокрутка ${if (forward) "вперёд" else "назад"} выполнена")
                } else {
                    val dm = resources.displayMetrics
                    val cx = dm.widthPixels * 0.5f
                    val yStart = if (forward) dm.heightPixels * 0.72f else dm.heightPixels * 0.28f
                    val yEnd = if (forward) dm.heightPixels * 0.28f else dm.heightPixels * 0.72f
                    if (dispatchSwipeGesture(cx, yStart, cx, yEnd, 250L)) {
                        Result.success("Прокрутка ${if (forward) "вперёд" else "назад"} выполнена свайпом")
                    } else {
                        Result.failure(IllegalStateException("Не удалось выполнить прокрутку экрана"))
                    }
                }
            }

            "focus" -> {
                val tag = int("tag") ?: return Result.failure(IllegalArgumentException("Для focus укажите tag"))
                actOnTag(tagId = tag, action = "focus")
            }

            else -> Result.failure(IllegalArgumentException("Неизвестное действие шага: '$action'"))
        }
    }

    /**
     * Hardware SurfaceFlinger screenshot on demand (Android 11+ / API 30+).
     * Zero background polling — captures a single frame only when explicitly requested.
     */
    suspend fun captureScreenFast(
        outDir: File,
        format: String = "webp",
        maxEdge: Int = 1280,
        quality: Int = 75
    ): Result<CapturedScreenFile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(
                UnsupportedOperationException("Аппаратный снимок экрана Accessibility требует Android 11+ (API 30+).")
            )
        }
        val t0 = SystemClock.elapsedRealtime()
        val rawPair = suspendCancellableCoroutine<Result<Pair<Bitmap, Pair<Int, Int>>>> { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val wrapped = runCatching {
                            val hwBuffer = screenshot.hardwareBuffer
                            try {
                                val hwBmp = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                    ?: error("Не удалось обернуть HardwareBuffer снимка")
                                val origW = hwBmp.width
                                val origH = hwBmp.height
                                val softBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                                hwBmp.recycle()
                                softBmp to (origW to origH)
                            } finally {
                                runCatching { hwBuffer.close() }
                            }
                        }
                        if (cont.isActive) cont.resume(wrapped)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) {
                            cont.resume(
                                Result.failure(
                                    IllegalStateException("Accessibility takeScreenshot вернул код ошибки $errorCode (возможна частотная защита или защищённое окно FLAG_SECURE).")
                                )
                            )
                        }
                    }
                }
            )
        }

        val (srcBitmap, origDim) = rawPair.getOrElse { return Result.failure(it) }
        return withContext(Dispatchers.IO) {
            runCatching {
                outDir.mkdirs()
                val cappedEdge = maxEdge.coerceIn(128, 4096)
                val curMax = max(srcBitmap.width, srcBitmap.height)
                val finalBmp = if (curMax > cappedEdge) {
                    val ratio = cappedEdge.toFloat() / curMax.toFloat()
                    val nw = (srcBitmap.width * ratio).roundToInt().coerceAtLeast(1)
                    val nh = (srcBitmap.height * ratio).roundToInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(srcBitmap, nw, nh, true)
                    if (scaled !== srcBitmap) srcBitmap.recycle()
                    scaled
                } else {
                    srcBitmap
                }

                val normFmt = format.trim().lowercase()
                val (compressFormat, ext, mime) = when (normFmt) {
                    "png" -> Triple(Bitmap.CompressFormat.PNG, "png", "image/png")
                    "jpg", "jpeg" -> Triple(Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg")
                    else -> {
                        val webpFmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            @Suppress("DEPRECATION")
                            Bitmap.CompressFormat.WEBP
                        }
                        Triple(webpFmt, "webp", "image/webp")
                    }
                }

                val q = quality.coerceIn(10, 95)
                val outFile = File(outDir, "screen_${System.currentTimeMillis()}.$ext")
                FileOutputStream(outFile).use { fos ->
                    finalBmp.compress(compressFormat, q, fos)
                }
                val finalW = finalBmp.width
                val finalH = finalBmp.height
                finalBmp.recycle()
                val elapsed = SystemClock.elapsedRealtime() - t0
                CapturedScreenFile(
                    file = outFile,
                    mimeType = mime,
                    width = finalW,
                    height = finalH,
                    originalWidth = origDim.first,
                    originalHeight = origDim.second,
                    elapsedMs = elapsed
                )
            }
        }
    }

    private suspend fun performGlobalNavIfMatched(normAction: String): Result<String>? {
        return when (normAction) {
            "back" -> performBackReliably()
            "home" -> {
                FloatingChatService.yieldWindowFocusForScreenAction()
                val ok = withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_HOME) }
                if (ok) Result.success("Выполнено системное действие: HOME")
                else Result.failure(IllegalStateException("Не удалось выполнить GLOBAL_ACTION_HOME"))
            }
            "recents" -> {
                FloatingChatService.yieldWindowFocusForScreenAction()
                val ok = withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_RECENTS) }
                if (ok) Result.success("Выполнено системное действие: RECENTS")
                else Result.failure(IllegalStateException("Не удалось выполнить GLOBAL_ACTION_RECENTS"))
            }
            "notifications" -> {
                val ok = withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }
                if (ok) Result.success("Открыта шторка уведомлений")
                else Result.failure(IllegalStateException("Не удалось открыть уведомления"))
            }
            "quick_settings" -> {
                val ok = withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }
                if (ok) Result.success("Открыты быстрые настройки")
                else Result.failure(IllegalStateException("Не удалось открыть быстрые настройки"))
            }
            else -> null
        }
    }

    private suspend fun performBackReliably(): Result<String> {
        FloatingChatService.yieldWindowFocusForScreenAction()

        // 1. Primary native Accessibility global action on Main thread (after overlay released focus)
        val directOk = withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_BACK) }
        if (directOk) {
            return Result.success("Выполнено системное действие: BACK")
        }

        // 2. Fallback A: click visible system navigation bar Back button (#back / "Назад" / "Back")
        val sysBack = findSystemNavBackNode()
        if (sysBack != null) {
            val (node, rect) = sysBack
            val clicked = performOnSelfOrAncestor(node, AccessibilityNodeInfo.ACTION_CLICK) ||
                dispatchTapGesture(rect.centerX().toFloat(), rect.centerY().toFloat(), 70L)
            if (clicked) {
                showTouchPulse(rect.centerX().toFloat(), rect.centerY().toFloat())
                return Result.success("Выполнено системное действие: BACK (через системную кнопку Назад)")
            }
        }

        // 3. Fallback B: Android Edge-Back gesture swipe from left screen edge (for gesture navigation on HyperOS/Android 10+)
        val dm = resources.displayMetrics
        val startX = 4f
        val endX = (dm.widthPixels * 0.32f).coerceAtLeast(180f)
        val midY = dm.heightPixels * 0.52f
        if (dispatchSwipeGesture(startX, midY, endX, midY, 125L)) {
            return Result.success("Выполнено системное действие: BACK (системным жестом от края экрана)")
        }

        return Result.failure(IllegalStateException("Не удалось выполнить GLOBAL_ACTION_BACK"))
    }

    private fun findSystemNavBackNode(): Pair<AccessibilityNodeInfo, Rect>? {
        val rawWins = runCatching { windows }.getOrNull().orEmpty()
        for (w in rawWins) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            var found: Pair<AccessibilityNodeInfo, Rect>? = null
            fun walk(n: AccessibilityNodeInfo?) {
                if (n == null || found != null || !n.isVisibleToUser) return
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 6 && r.height() > 6) {
                    val idShort = n.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
                    val desc = n.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                    val txt = n.text?.toString()?.trim()?.lowercase().orEmpty()
                    val isBackMatch = idShort == "back" ||
                        idShort == "back_button" ||
                        desc == "back" || desc == "назад" ||
                        txt == "back" || txt == "назад"
                    if (isBackMatch) {
                        found = n to r
                        return
                    }
                }
                for (i in 0 until n.childCount) {
                    walk(runCatching { n.getChild(i) }.getOrNull())
                }
            }
            walk(root)
            if (found != null) return found
        }
        return null
    }

    private fun applyTextToEditableNode(
        target: AccessibilityNodeInfo,
        value: String,
        append: Boolean,
        submit: Boolean,
        labelForReport: String
    ): Result<String> {
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val existing = if (append) {
            val showingHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) target.isShowingHintText else false
            if (showingHint) "" else target.text?.toString().orEmpty()
        } else ""
        val finalText = existing + value
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            return Result.failure(
                IllegalStateException("Элемент $labelForReport не принял ACTION_SET_TEXT (возможно, не редактируемое поле).")
            )
        }
        if (submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return Result.success(
            "В $labelForReport ${if (append) "дописан" else "введён"} текст (${value.length} симв.)${if (submit) " + отправлен Enter" else ""}."
        )
    }

    private fun iterCandidateRoots(includeSelf: Boolean): List<AccessibilityNodeInfo> {
        val selfPkg = packageName
        val out = mutableListOf<AccessibilityNodeInfo>()
        val seenWinIds = HashSet<Int>()

        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null && (includeSelf || active.packageName?.toString() != selfPkg)) {
            out += active
            seenWinIds += active.windowId
        }

        val rawWins = runCatching { windows }.getOrNull().orEmpty()
        for (w in rawWins) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            if (w.id in seenWinIds) continue
            val r = runCatching { w.root }.getOrNull() ?: continue
            val pkg = r.packageName?.toString().orEmpty()
            if (!includeSelf && pkg == selfPkg) continue
            out += r
            seenWinIds += w.id
        }
        return out
    }

    private fun findSmallestNodeByLabel(query: String, includeSelf: Boolean): Pair<AccessibilityNodeInfo, Rect>? {
        val qLower = query.lowercase()
        var best: Pair<AccessibilityNodeInfo, Rect>? = null
        var bestArea = Long.MAX_VALUE

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 6 && r.height() > 6) {
                val txt = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
                val resId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
                val hay = "$txt $desc $hint $resId"
                if (hay.lowercase().contains(qLower)) {
                    val area = r.width().toLong() * r.height().toLong()
                    if (area < bestArea) {
                        bestArea = area
                        best = node to r
                    }
                }
            }
            for (i in 0 until node.childCount) {
                walk(runCatching { node.getChild(i) }.getOrNull())
            }
        }

        for (root in iterCandidateRoots(includeSelf)) {
            walk(root)
        }
        return best
    }

    private fun findFocusedOrFirstEditableNode(includeSelf: Boolean): AccessibilityNodeInfo? {
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.let { focused ->
            if (focused.isEditable && (includeSelf || focused.packageName?.toString() != packageName)) {
                return focused
            }
        }
        for (root in iterCandidateRoots(includeSelf)) {
            var found: AccessibilityNodeInfo? = null
            fun walk(n: AccessibilityNodeInfo?) {
                if (n == null || found != null || !n.isVisibleToUser) return
                if (n.isEditable) {
                    found = n
                    return
                }
                for (i in 0 until n.childCount) {
                    walk(runCatching { n.getChild(i) }.getOrNull())
                }
            }
            walk(root)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstScrollableNode(includeSelf: Boolean): AccessibilityNodeInfo? {
        for (root in iterCandidateRoots(includeSelf)) {
            var found: AccessibilityNodeInfo? = null
            fun walk(n: AccessibilityNodeInfo?) {
                if (n == null || found != null || !n.isVisibleToUser) return
                if (n.isScrollable) {
                    found = n
                    return
                }
                for (i in 0 until n.childCount) {
                    walk(runCatching { n.getChild(i) }.getOrNull())
                }
            }
            walk(root)
            if (found != null) return found
        }
        return null
    }

    private fun findBestLiveNode(target: CapturedWindowTag): AccessibilityNodeInfo? {
        val winRoot = runCatching { windows }.getOrNull()
            ?.firstOrNull { it.id == target.windowId }
            ?.let { runCatching { it.root }.getOrNull() }
            ?: runCatching { rootInActiveWindow }.getOrNull()
            ?: return null

        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Double.MAX_VALUE

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 2 && r.height() > 2) {
                val dist = hypot(
                    (r.centerX() - target.bounds.centerX()).toDouble(),
                    (r.centerY() - target.bounds.centerY()).toDouble()
                )
                val resId = node.viewIdResourceName?.substringAfterLast('/')
                val cls = shortClassName(node.className?.toString())
                var penalty = dist
                if (target.viewResourceId != null && resId == target.viewResourceId) penalty -= 35.0
                if (cls == target.className) penalty -= 15.0
                if (target.editable && node.isEditable) penalty -= 25.0
                if (dist <= 160.0 && penalty < bestScore) {
                    bestScore = penalty
                    bestNode = node
                }
            }
            for (i in 0 until node.childCount) {
                walk(runCatching { node.getChild(i) }.getOrNull())
            }
        }

        walk(winRoot)
        return bestNode
    }

    private fun performOnSelfOrAncestor(node: AccessibilityNodeInfo, action: Int): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 6) {
            val capable = when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> cur.isClickable
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> cur.isLongClickable
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> cur.isScrollable
                else -> true
            }
            if (capable && cur.performAction(action)) return true
            cur = runCatching { cur.parent }.getOrNull()
            depth++
        }
        return node.performAction(action)
    }

    private fun findEditableSelfOrChild(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        if (depth >= 4) return if (depth == 0) node else null
        for (i in 0 until node.childCount) {
            val ch = runCatching { node.getChild(i) }.getOrNull() ?: continue
            findEditableSelfOrChild(ch, depth + 1)?.let { return it }
        }
        return if (depth == 0) node else null
    }

    private suspend fun dispatchTapGesture(x: Float, y: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        showTouchPulse(x, y)
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    private suspend fun dispatchSwipeGesture(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        showSwipePulse(x1, y1, x2, y2)
        val path = Path().apply {
            moveTo(x1.coerceAtLeast(1f), y1.coerceAtLeast(1f))
            lineTo(x2.coerceAtLeast(1f), y2.coerceAtLeast(1f))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    private fun showTouchPulse(x: Float, y: Float) {
        if (!visualTouchFeedback) return
        mainHandler.post {
            runCatching {
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                val sizePx = (resources.displayMetrics.density * 44f).roundToInt().coerceAtLeast(48)
                val half = sizePx / 2
                val pulseView = object : View(this) {
                    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = Color.argb(72, 16, 185, 129)
                    }
                    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = resources.displayMetrics.density * 2.5f
                        color = Color.argb(235, 52, 211, 153)
                    }

                    override fun onDraw(canvas: Canvas) {
                        val c = width / 2f
                        val r = c - ringPaint.strokeWidth
                        canvas.drawCircle(c, c, r, fillPaint)
                        canvas.drawCircle(c, c, r, ringPaint)
                        canvas.drawCircle(c, c, ringPaint.strokeWidth * 1.3f, ringPaint)
                    }
                }
                val lp = WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x.roundToInt() - half
                    this.y = y.roundToInt() - half
                }
                wm.addView(pulseView, lp)
                mainHandler.postDelayed({
                    runCatching { wm.removeViewImmediate(pulseView) }
                }, 180L)
            }
        }
    }

    private fun showSwipePulse(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (!visualTouchFeedback) return
        mainHandler.post {
            runCatching {
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                val dm = resources.displayMetrics
                val trailView = object : View(this) {
                    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeWidth = dm.density * 3.5f
                        color = Color.argb(210, 56, 189, 248)
                    }

                    override fun onDraw(canvas: Canvas) {
                        canvas.drawLine(x1, y1, x2, y2, linePaint)
                        canvas.drawCircle(x2, y2, dm.density * 7f, linePaint)
                    }
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                wm.addView(trailView, lp)
                mainHandler.postDelayed({
                    runCatching { wm.removeViewImmediate(trailView) }
                }, 220L)
            }
        }
    }

    private fun collectShortChildText(node: AccessibilityNodeInfo, maxDepth: Int): String {
        if (maxDepth <= 0) return ""
        val parts = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val ch = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val t = ch.text?.toString()?.trim().orEmpty()
                .ifBlank { ch.contentDescription?.toString()?.trim().orEmpty() }
            if (t.isNotBlank()) parts += t
            else {
                val sub = collectShortChildText(ch, maxDepth - 1)
                if (sub.isNotBlank()) parts += sub
            }
            if (parts.size >= 3) break
        }
        return parts.joinToString(" · ")
    }

    private fun shortClassName(raw: String?): String {
        if (raw.isNullOrBlank()) return "View"
        return raw.substringAfterLast('.').ifBlank { raw }
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_DIVIDER"
        else -> "WINDOW_$type"
    }

    companion object {
        const val MAX_BATCH_STEPS = 25

        @Volatile
        var instance: ForgeAccessibilityService? = null
            private set

        @Volatile
        var visualTouchFeedback: Boolean = true

        @Volatile
        private var appContextRef: Context? = null

        private val selfHealMutex = Mutex()

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun registerAppContext(context: Context) {
            appContextRef = context.applicationContext ?: context
        }

        /**
         * Pure helper for toggling our Accessibility component out of and back into
         * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` so OEM ROMs (Xiaomi HyperOS / MIUI)
         * clear the stale "Crashed services" state after an APK update without disturbing
         * other authorized services (such as Tasker).
         *
         * Returns `(withoutSelf, withSelfRestored)` ONLY if our component is already listed
         * in `rawEnabled` (respecting explicit user disablement).
         */
        internal fun computeRebindServiceStrings(
            rawEnabled: String?,
            selfPackage: String,
            serviceClassName: String = ForgeAccessibilityService::class.java.name
        ): Pair<String, String>? {
            if (rawEnabled.isNullOrBlank() || selfPackage.isBlank()) return null
            val fullComp = "$selfPackage/$serviceClassName"
            val shortSuffix = serviceClassName.removePrefix("$selfPackage.")
            val shortComp = "$selfPackage/.$shortSuffix"

            val entries = rawEnabled.split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            fun isSelfEntry(entry: String): Boolean =
                entry.equals(fullComp, ignoreCase = true) ||
                    entry.equals(shortComp, ignoreCase = true) ||
                    (entry.startsWith("$selfPackage/") && entry.endsWith("ForgeAccessibilityService"))

            if (entries.none(::isSelfEntry)) return null

            val others = entries.filterNot(::isSelfEntry)
            val withoutSelf = others.joinToString(":")
            val restored = (others + fullComp).joinToString(":")
            return withoutSelf to restored
        }

        fun isListedInSecureSettings(context: Context? = appContextRef): Boolean {
            val ctx = context ?: return false
            val raw = runCatching {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            }.getOrNull()
            return computeRebindServiceStrings(raw, ctx.packageName) != null
        }

        /**
         * Fast-path returns [instance] in 0ms when already bound.
         * If unbound yet still listed in `ENABLED_ACCESSIBILITY_SERVICES` (e.g. placed in
         * "Crashed services" with `accessibility_enabled=0` after an APK update on HyperOS)
         * and `WRITE_SECURE_SETTINGS` is granted, automatically toggles the component entry
         * and waits up to ~1.5s for Android to rebind the service.
         */
        suspend fun ensureConnectedOrSelfHeal(context: Context? = appContextRef): ForgeAccessibilityService? {
            instance?.let { return it }
            val ctx = context ?: return null
            return selfHealMutex.withLock {
                instance?.let { return@withLock it }
                val cr = runCatching { ctx.contentResolver }.getOrNull() ?: return@withLock null
                val rawEnabled = runCatching {
                    Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                }.getOrNull()
                val togglePair = computeRebindServiceStrings(rawEnabled, ctx.packageName)
                    ?: return@withLock instance

                // Give Android a brief moment in case binding is already in flight on cold boot
                delay(120L)
                instance?.let { return@withLock it }

                val hasSecurePerm = runCatching {
                    ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                if (!hasSecurePerm) return@withLock instance

                runCatching {
                    val (withoutSelf, restored) = togglePair
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withoutSelf)
                    delay(120L)
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, restored)
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }

                repeat(25) {
                    instance?.let { return@withLock it }
                    delay(60L)
                }
                instance
            }
        }

        fun describeUnavailableMessage(english: Boolean = false, context: Context? = appContextRef): String {
            val listedButSuspended = isListedInSecureSettings(context)
            val pkg = runCatching { context?.packageName }.getOrNull() ?: "com.forgemind.app"
            return if (listedButSuspended) {
                if (english) {
                    "Window-Tag DOM service is listed in Accessibility Settings, but Android/HyperOS suspended it after an app update ('Crashed services' / accessibility_enabled=0). Re-toggle ForgeMind in Accessibility Settings, or grant once via ADB/Shizuku (`pm grant $pkg android.permission.WRITE_SECURE_SETTINGS`) so ForgeMind auto-recovers across all future updates."
                } else {
                    "Служба Window-Tag DOM числится в списке разрешённых, но Android/HyperOS приостановил её после обновления пакета («Неисправные службы» / accessibility_enabled=0). Переключите тумблер ForgeMind в Спецвозможностях или один раз выдайте разрешение `pm grant $pkg android.permission.WRITE_SECURE_SETTINGS` (через ADB или Shizuku), чтобы служба поднималась автоматически после каждого обновления."
                }
            } else {
                if (english) {
                    "Window-Tag DOM service is not enabled yet in Android Accessibility Settings. Ask the user to open 'Settings -> Web & Agent -> Window-Tag DOM' and enable ForgeMind."
                } else {
                    "Служба Window-Tag DOM ещё не включена в Спецвозможностях Android. Подскажите пользователю открыть «Настройки -> Агент и Веб -> Window-Tag DOM» и включить службу ForgeMind."
                }
            }
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
