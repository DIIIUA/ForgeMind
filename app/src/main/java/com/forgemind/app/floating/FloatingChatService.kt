package com.forgemind.app.floating

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.forgemind.app.ForgeMindApp
import com.forgemind.app.MainActivity
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.PendingApproval
import com.forgemind.app.data.model.SttProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class FloatingChatService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var messagesJob: Job? = null
    private var windowManager: WindowManager? = null

    private var bubbleView: View? = null
    private var cardView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var cardParams: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var activeSessionId: String? = null
    private var currentModelName: String = "ForgeMind AI"

    private lateinit var messagesContainer: LinearLayout
    private lateinit var messagesScrollView: ScrollView
    private lateinit var statusTextView: TextView
    private lateinit var titleTextView: TextView
    private lateinit var inputEditText: EditText
    private lateinit var actionButton: TextView // Switches between Send (➤) and Stop (⏹)
    private lateinit var micButton: TextView
    private lateinit var statusIndicatorDot: View
    private lateinit var approvalsContainer: LinearLayout
    private lateinit var drawerLayout: LinearLayout
    private lateinit var sessionsListContainer: LinearLayout

    private var isAgentRunning = false
    private var isRecording = false

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        ensureChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createViews()
        observeAgentState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val targetSession = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (!targetSession.isNullOrBlank()) {
            activeSessionId = targetSession
            startObservingMessages(targetSession)
        }
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)
        } catch (_: Throwable) {
            // Ignore foreground exception
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        serviceScope.cancel()
        removeViews()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createViews() {
        val wm = windowManager ?: return

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1. Bubble Layout Params
        val bSize = dpToPx(56)
        bubbleParams = WindowManager.LayoutParams(
            bSize,
            bSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = dpToPx(160)
        }

        // 2. Card Layout Params (with persisted user size preference)
        val dm = resources.displayMetrics
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultW = (dm.widthPixels * 0.92f).toInt().coerceAtMost(dpToPx(440))
        val defaultH = (dm.heightPixels * 0.65f).toInt().coerceAtMost(dpToPx(650))
        val cardWidth = prefs.getInt(KEY_CARD_WIDTH, defaultW).coerceIn(dpToPx(280), (dm.widthPixels * 0.98f).toInt())
        val cardHeight = prefs.getInt(KEY_CARD_HEIGHT, defaultH).coerceIn(dpToPx(320), (dm.heightPixels * 0.92f).toInt())

        cardParams = WindowManager.LayoutParams(
            cardWidth,
            cardHeight,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        buildBubbleView()
        buildCardView()

        bubbleView?.let {
            try {
                wm.addView(it, bubbleParams)
            } catch (_: Throwable) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubbleView() {
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1A2234"))
                setStroke(dpToPx(2), Color.parseColor("#4F8CFF"))
            }
            elevation = dpToPx(8).toFloat()
        }

        val textIcon = TextView(this).apply {
            text = "FM"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4F8CFF"))
            gravity = Gravity.CENTER
        }
        frame.addView(textIcon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        statusIndicatorDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
        }
        val dotSize = dpToPx(12)
        val dotParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dpToPx(4)
            rightMargin = dpToPx(4)
        }
        frame.addView(statusIndicatorDot, dotParams)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        frame.setOnTouchListener { _, event ->
            val bp = bubbleParams ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bp.x
                    initialY = bp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                        isDragging = true
                    }
                    bp.x = initialX + dx
                    bp.y = initialY + dy
                    try {
                        wm.updateViewLayout(frame, bp)
                    } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        expandToCard()
                    } else {
                        val screenWidth = resources.displayMetrics.widthPixels
                        bp.x = if (bp.x + frame.width / 2 < screenWidth / 2) dpToPx(16) else screenWidth - frame.width - dpToPx(16)
                        try {
                            wm.updateViewLayout(frame, bp)
                        } catch (_: Throwable) {}
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = frame
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCardView() {
        val rootFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#121826"))
                setStroke(dpToPx(1), Color.parseColor("#2B3548"))
            }
            elevation = dpToPx(14).toFloat()
            clipToOutline = true
        }

        // Layer 1: Main Content Container (Header + Messages + Approvals + Footer)
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Header: [☰ Menu] + Title/Status + [🗕 Minimize] + [⤢ Fullscreen] + [✕ Close]
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A2234"))
            }
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            gravity = Gravity.CENTER_VERTICAL
        }

        // ☰ Drawer Menu Button
        val btnMenu = createHeaderButton("☰") { toggleDrawer() }
        header.addView(btnMenu)

        val headerTextLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpToPx(8)
                rightMargin = dpToPx(6)
            }
        }

        titleTextView = TextView(this).apply {
            text = "ForgeMind"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EDF3"))
            maxLines = 1
        }
        statusTextView = TextView(this).apply {
            text = "Готов к работе"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            maxLines = 1
        }
        headerTextLayout.addView(titleTextView)
        headerTextLayout.addView(statusTextView)
        header.addView(headerTextLayout)

        // Action buttons: Minimize to bubble (🗕), Fullscreen app (⤢), Close overlay (✕)
        val btnMinimize = createHeaderButton("🗕") { collapseToBubble() }
        val btnFullscreen = createHeaderButton("⤢") { openFullApp() }
        val btnClose = createHeaderButton("✕") { stopSelf() }

        header.addView(btnMinimize)
        header.addView(btnFullscreen)
        header.addView(btnClose)

        // Header drag listener
        var hInitialX = 0
        var hInitialY = 0
        var hTouchX = 0f
        var hTouchY = 0f
        header.setOnTouchListener { _, event ->
            val cp = cardParams ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    hInitialX = cp.x
                    hInitialY = cp.y
                    hTouchX = event.rawX
                    hTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - hTouchX).toInt()
                    val dy = (event.rawY - hTouchY).toInt()
                    cp.x = hInitialX + dx
                    cp.y = hInitialY + dy
                    try {
                        wm.updateViewLayout(rootFrame, cp)
                    } catch (_: Throwable) {}
                    true
                }
                else -> false
            }
        }
        contentLayout.addView(header)

        // Messages list
        messagesScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }
        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
        }
        messagesScrollView.addView(messagesContainer)
        contentLayout.addView(messagesScrollView)

        // Approvals Banner Container
        approvalsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#261B22"))
                setStroke(dpToPx(1), Color.parseColor("#F59E0B"))
                cornerRadius = dpToPx(8).toFloat()
            }
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            visibility = View.GONE
        }
        val appLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dpToPx(8), 0, dpToPx(8), dpToPx(4))
        }
        contentLayout.addView(approvalsContainer, appLp)

        // Footer input bar
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161E2E"))
            }
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            gravity = Gravity.CENTER_VERTICAL
        }

        inputEditText = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dpToPx(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#1E273B"))
                setStroke(dpToPx(1), Color.parseColor("#2B3548"))
            }
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            hint = "Задача или вопрос..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 13f
            maxLines = 4
        }
        footer.addView(inputEditText)

        // Microphone Button (44x44dp clear circular target)
        val btnSize = dpToPx(44)
        micButton = TextView(this).apply {
            text = "🎤"
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                rightMargin = dpToPx(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E273B"))
                setStroke(dpToPx(1), Color.parseColor("#334155"))
            }
            setOnClickListener { toggleVoiceRecording() }
        }
        footer.addView(micButton)

        // Action Button: Send (➤) or Stop (⏹) (44x44dp clear circular target)
        actionButton = TextView(this).apply {
            text = "➤"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4F8CFF"))
            }
            setOnClickListener {
                if (isAgentRunning) {
                    stopAgentRun()
                } else {
                    sendCurrentMessage()
                }
            }
        }
        footer.addView(actionButton)
        contentLayout.addView(footer)
        rootFrame.addView(contentLayout)

        // Layer 2: Corner Resize Handle (bottom-right ◢ grip)
        val resizeGripSize = dpToPx(38)
        val resizeGrip = TextView(this).apply {
            text = "◢"
            textSize = 15f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(0, 0, dpToPx(4), dpToPx(2))
        }
        val resizeParams = FrameLayout.LayoutParams(resizeGripSize, resizeGripSize).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        }

        var startW = 0
        var startH = 0
        var startTouchX = 0f
        var startTouchY = 0f
        val dm = resources.displayMetrics
        val minW = dpToPx(280)
        val maxW = (dm.widthPixels * 0.98f).toInt()
        val minH = dpToPx(320)
        val maxH = (dm.heightPixels * 0.92f).toInt()

        resizeGrip.setOnTouchListener { _, event ->
            val cp = cardParams ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = cp.width
                    startH = cp.height
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    resizeGrip.setTextColor(Color.parseColor("#4F8CFF"))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    cp.width = (startW + dx).coerceIn(minW, maxW)
                    cp.height = (startH + dy).coerceIn(minH, maxH)
                    try {
                        wm.updateViewLayout(rootFrame, cp)
                    } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resizeGrip.setTextColor(Color.parseColor("#64748B"))
                    // Persist user preferred dimensions
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_CARD_WIDTH, cp.width)
                        .putInt(KEY_CARD_HEIGHT, cp.height)
                        .apply()
                    true
                }
                else -> false
            }
        }
        rootFrame.addView(resizeGrip, resizeParams)

        // Layer 3: Sliding Sessions Drawer (Left sidebar)
        buildSessionsDrawer(rootFrame)

        cardView = rootFrame
    }

    private fun buildSessionsDrawer(rootFrame: FrameLayout) {
        val dm = resources.displayMetrics
        val drawerWidth = (dm.widthPixels * 0.75f).toInt().coerceAtMost(dpToPx(320))

        drawerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0E1420"))
                setStroke(dpToPx(1), Color.parseColor("#2B3548"))
            }
            elevation = dpToPx(20).toFloat()
            visibility = View.GONE
        }
        val drawerParams = FrameLayout.LayoutParams(drawerWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START or Gravity.TOP
        }

        // Drawer Header: "Чаты" + [✕ Закрыть шторку]
        val dHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(8), dpToPx(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151D2C"))
            }
        }
        val dTitle = TextView(this).apply {
            text = "Чаты"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EDF3"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnCloseDrawer = createHeaderButton("✕") { hideDrawer() }
        dHeader.addView(dTitle)
        dHeader.addView(btnCloseDrawer)
        drawerLayout.addView(dHeader)

        // Inside Drawer: "+ Новый чат" button
        val btnNewChat = TextView(this).apply {
            text = "+ Новый чат"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = dpToPx(8).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            setOnClickListener { createNewChatFromDrawer() }
        }
        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(6))
        }
        drawerLayout.addView(btnNewChat, btnLp)

        // Sessions list inside drawer
        val sv = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }
        sessionsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(8))
        }
        sv.addView(sessionsListContainer)
        drawerLayout.addView(sv)

        rootFrame.addView(drawerLayout, drawerParams)
    }

    private fun toggleDrawer() {
        if (drawerLayout.visibility == View.VISIBLE) {
            hideDrawer()
        } else {
            showDrawer()
        }
    }

    private fun showDrawer() {
        drawerLayout.visibility = View.VISIBLE
        refreshSessionsList()
    }

    private fun hideDrawer() {
        drawerLayout.visibility = View.GONE
    }

    private fun refreshSessionsList() {
        val app = application as? ForgeMindApp ?: return
        serviceScope.launch(Dispatchers.IO) {
            val sessions = app.container.db.sessionDao().getAll()
            withContext(Dispatchers.Main) {
                sessionsListContainer.removeAllViews()
                for (s in sessions) {
                    val isSelected = s.id == activeSessionId
                    val item = LinearLayout(this@FloatingChatService).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable().apply {
                            cornerRadius = dpToPx(6).toFloat()
                            setColor(if (isSelected) Color.parseColor("#1E2D4A") else Color.parseColor("#131B29"))
                            if (isSelected) {
                                setStroke(dpToPx(1), Color.parseColor("#4F8CFF"))
                            }
                        }
                        setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                        setOnClickListener {
                            activeSessionId = s.id
                            titleTextView.text = s.title
                            currentModelName = s.modelId
                            startObservingMessages(s.id)
                            hideDrawer()
                        }
                    }

                    val titleV = TextView(this@FloatingChatService).apply {
                        text = s.title.ifBlank { "Без названия" }
                        textSize = 12.5f
                        typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(if (isSelected) Color.parseColor("#4F8CFF") else Color.parseColor("#CBD5E1"))
                        maxLines = 1
                    }
                    val modelV = TextView(this@FloatingChatService).apply {
                        text = s.modelId.substringAfterLast('/')
                        textSize = 10f
                        setTextColor(Color.parseColor("#64748B"))
                        maxLines = 1
                    }
                    item.addView(titleV)
                    item.addView(modelV)

                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(4)
                    }
                    sessionsListContainer.addView(item, lp)
                }
            }
        }
    }

    private fun createNewChatFromDrawer() {
        val app = application as? ForgeMindApp ?: return
        val container = app.container

        serviceScope.launch(Dispatchers.IO) {
            val newSession = ChatSessionEntity(
                id = UUID.randomUUID().toString(),
                title = "Новый чат",
                modelId = currentModelName
            )
            container.db.sessionDao().upsert(newSession)
            activeSessionId = newSession.id
            withContext(Dispatchers.Main) {
                titleTextView.text = newSession.title
                statusTextView.text = "Готов к работе"
                startObservingMessages(newSession.id)
                hideDrawer()
            }
        }
    }

    private fun updateActionButton(running: Boolean) {
        isAgentRunning = running
        if (running) {
            actionButton.text = "⏹"
            actionButton.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EF4444")) // Bright red stop button
            }
        } else {
            actionButton.text = "➤"
            actionButton.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4F8CFF")) // Blue send button
            }
        }
    }

    private fun stopAgentRun() {
        val app = application as? ForgeMindApp ?: return
        app.container.agentEngine.cancelRun()
        statusTextView.text = "Остановлено"
        updateActionButton(false)
    }

    private fun expandToCard() {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        val card = cardView ?: return
        if (isExpanded) return
        isExpanded = true

        try {
            wm.removeView(bubble)
        } catch (_: Throwable) {}

        try {
            wm.addView(card, cardParams)
        } catch (_: Throwable) {}

        messagesScrollView.post {
            messagesScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun collapseToBubble() {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        val card = cardView ?: return
        if (!isExpanded) return
        isExpanded = false
        hideDrawer()

        try {
            wm.removeView(card)
        } catch (_: Throwable) {}

        try {
            wm.addView(bubble, bubbleParams)
        } catch (_: Throwable) {}
    }

    private fun sendCurrentMessage() {
        val text = inputEditText.text.toString().trim()
        if (text.isBlank()) return
        inputEditText.setText("")

        val app = application as? ForgeMindApp ?: return
        val container = app.container

        serviceScope.launch(Dispatchers.IO) {
            val sessionId = activeSessionId ?: container.db.sessionDao().getAll().firstOrNull()?.id ?: run {
                val newSession = ChatSessionEntity(
                    id = UUID.randomUUID().toString(),
                    title = text.take(30),
                    modelId = currentModelName
                )
                container.db.sessionDao().upsert(newSession)
                newSession.id
            }
            activeSessionId = sessionId
            withContext(Dispatchers.Main) {
                startObservingMessages(sessionId)
            }
            container.agentEngine.sendUserTurn(
                sessionId = sessionId,
                userText = text,
                attachments = emptyList()
            )
        }
    }

    private fun toggleVoiceRecording() {
        val app = application as? ForgeMindApp ?: return
        val recorder = app.container.audioRecorder
        val stt = app.container.sttManager
        val voiceSettings = app.container.settingsRepo.voiceSettings.value

        if (!isRecording) {
            val wavFile = File(cacheDir, "floating_voice_${System.currentTimeMillis()}.wav")
            val started = recorder.startRecording(wavFile).isSuccess
            if (started) {
                isRecording = true
                micButton.text = "🔴"
                micButton.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#EF4444")) // Red pulsing mic
                }
                statusTextView.text = "Слушаю... Нажмите 🔴 для завершения"
            } else {
                statusTextView.text = "Ошибка доступа к микрофону"
            }
        } else {
            isRecording = false
            micButton.text = "🎤"
            micButton.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E273B"))
                setStroke(dpToPx(1), Color.parseColor("#334155"))
            }
            statusTextView.text = "Распознавание..."
            serviceScope.launch(Dispatchers.IO) {
                val recordedFile = recorder.stopRecording()
                if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 512) {
                    val result = if (voiceSettings.sttProvider == SttProviderType.ANDROID_SPEECH) {
                        stt.recognizeWithAndroidSpeech(voiceSettings.sttLanguage)
                    } else {
                        stt.transcribeAudioFile(recordedFile, voiceSettings)
                    }
                    result.onSuccess { text ->
                        withContext(Dispatchers.Main) {
                            val current = inputEditText.text.toString()
                            inputEditText.setText(if (current.isBlank()) text else "$current $text")
                            inputEditText.setSelection(inputEditText.text.length)
                            statusTextView.text = "Готов к работе"
                        }
                    }.onFailure { err ->
                        withContext(Dispatchers.Main) {
                            statusTextView.text = "Ошибка STT: ${err.message?.take(25)}"
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusTextView.text = "Готов к работе"
                    }
                }
            }
        }
    }

    private fun startObservingMessages(sessionId: String) {
        messagesJob?.cancel()
        val app = application as? ForgeMindApp ?: return
        val dao = app.container.db.messageDao()

        messagesJob = serviceScope.launch {
            dao.observeForSession(sessionId).collectLatest { msgs ->
                updateMessagesUi(msgs)
            }
        }
    }

    private fun updateMessagesUi(messages: List<ChatMessageEntity>) {
        messagesContainer.removeAllViews()
        val recent = messages.takeLast(25)
        for (m in recent) {
            if (m.role == LlmRole.SYSTEM.name) continue
            val isUser = m.role.equals(LlmRole.USER.name, ignoreCase = true)

            val msgBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(if (isUser) Color.parseColor("#1E273B") else Color.parseColor("#161D2B"))
                    if (!isUser) {
                        setStroke(dpToPx(1), Color.parseColor("#2B3548"))
                    }
                }
                setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            }

            // Role / Author header
            val authorView = TextView(this).apply {
                text = if (isUser) "Вы" else "Агент (${currentModelName.substringAfterLast('/')})"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isUser) Color.parseColor("#4F8CFF") else Color.parseColor("#10B981"))
            }
            msgBox.addView(authorView)

            // Thinking snippet (collapsible / distinct)
            if (!isUser && m.thinking.isNotBlank()) {
                val thinkingView = TextView(this).apply {
                    text = "💭 Размышления (${m.thinking.length} симв.):\n${m.thinking.take(180)}${if (m.thinking.length > 180) "..." else ""}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#F59E0B")) // Amber
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(4).toFloat()
                        setColor(Color.parseColor("#262016"))
                    }
                    setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
                }
                val thinkLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(4)
                    bottomMargin = dpToPx(4)
                }
                msgBox.addView(thinkingView, thinkLp)
            }

            // Tool execution badge
            if (!m.toolName.isNullOrBlank()) {
                val toolBadge = TextView(this).apply {
                    text = "⚙ Инструмент: ${m.toolName}"
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#60A5FA"))
                }
                msgBox.addView(toolBadge)
            }

            // Main message content
            if (m.content.isNotBlank()) {
                val contentView = TextView(this).apply {
                    text = m.content
                    textSize = 12f
                    setTextColor(if (isUser) Color.parseColor("#E6EDF3") else Color.parseColor("#CBD5E1"))
                }
                val contentLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(2)
                }
                msgBox.addView(contentView, contentLp)
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(6)
            }
            messagesContainer.addView(msgBox, lp)
        }
        messagesScrollView.post {
            messagesScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun observeAgentState() {
        val app = application as? ForgeMindApp ?: return
        val engine = app.container.agentEngine

        serviceScope.launch {
            engine.isRunning.collectLatest { running ->
                updateActionButton(running)
                statusIndicatorDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (running) Color.parseColor("#4F8CFF") else Color.parseColor("#10B981"))
                }
            }
        }

        serviceScope.launch {
            engine.statusBanner.collectLatest { banner ->
                statusTextView.text = banner?.ifBlank { "Готов к работе" } ?: "Готов к работе"
            }
        }

        // Observe pending approvals and render approval dialog directly in the floating card!
        serviceScope.launch {
            engine.pendingApprovals.collectLatest { approvals ->
                updateApprovalsUi(approvals)
            }
        }

        serviceScope.launch(Dispatchers.IO) {
            val mostRecent = app.container.db.sessionDao().getAll().firstOrNull()
            activeSessionId = mostRecent?.id
            currentModelName = mostRecent?.modelId ?: "ForgeMind AI"
            withContext(Dispatchers.Main) {
                titleTextView.text = mostRecent?.title ?: "ForgeMind"
                activeSessionId?.let { startObservingMessages(it) }
            }
        }
    }

    private fun updateApprovalsUi(approvals: List<PendingApproval>) {
        approvalsContainer.removeAllViews()
        if (approvals.isEmpty()) {
            approvalsContainer.visibility = View.GONE
            return
        }
        approvalsContainer.visibility = View.VISIBLE
        val app = application as? ForgeMindApp ?: return
        val engine = app.container.agentEngine

        val first = approvals.first()
        val titleView = TextView(this).apply {
            text = "⚠ Требуется подтверждение: ${first.toolName}"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F59E0B"))
        }
        approvalsContainer.addView(titleView)

        val detailView = TextView(this).apply {
            text = first.details.take(150)
            textSize = 10.5f
            setTextColor(Color.parseColor("#CBD5E1"))
        }
        approvalsContainer.addView(detailView)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(4), 0, 0)
        }

        val btnAllow = TextView(this).apply {
            text = "✓ Разрешить"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = dpToPx(4).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
            setOnClickListener {
                engine.resolveApproval(first.id, true)
            }
        }

        val btnDeny = TextView(this).apply {
            text = "✕ Отклонить"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = dpToPx(4).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
            setOnClickListener {
                engine.resolveApproval(first.id, false)
            }
        }
        val denyParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dpToPx(8)
        }

        btnRow.addView(btnAllow)
        btnRow.addView(btnDeny, denyParams)
        approvalsContainer.addView(btnRow)
    }

    private fun openFullApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            activeSessionId?.let { putExtra("EXTRA_SESSION_ID", it) }
        }
        startActivity(intent)
        collapseToBubble()
    }

    private fun removeViews() {
        val wm = windowManager ?: return
        bubbleView?.let { runCatching { wm.removeView(it) } }
        cardView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        cardView = null
    }

    private fun createHeaderButton(label: String, onClick: () -> Unit): TextView {
        val s = dpToPx(36)
        return TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                leftMargin = dpToPx(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(6).toFloat()
                setColor(Color.parseColor("#26334D"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ForgeMind Плавающий чат",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновое управление плавающим окном чата"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingChatService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ForgeMind Плавающий чат")
            .setContentText("Чат доступен поверх всех приложений")
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", stopPi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "forgemind_floating_chat"
        private const val NOTIFICATION_ID = 4040
        private const val ACTION_STOP = "com.forgemind.app.STOP_FLOATING"
        private const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
        private const val PREFS_NAME = "floating_chat_prefs"
        private const val KEY_CARD_WIDTH = "card_width"
        private const val KEY_CARD_HEIGHT = "card_height"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        fun start(context: Context, sessionId: String? = null) {
            val intent = Intent(context, FloatingChatService::class.java).apply {
                sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingChatService::class.java)
            context.stopService(intent)
        }

        fun toggle(context: Context, sessionId: String? = null) {
            if (_isRunning.value) {
                stop(context)
            } else {
                start(context, sessionId)
            }
        }
    }
}
