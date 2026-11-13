package com.forgemind.app.floating

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.forgemind.app.ForgeMindApp
import com.forgemind.app.MainActivity
import com.forgemind.app.R
import com.forgemind.app.i18n.LocaleHelper
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
    private lateinit var actionButton: ImageView // Switches between Send and Stop
    private lateinit var micButton: ImageView
    private lateinit var statusIndicatorDot: View
    private lateinit var cardStatusDot: View
    private lateinit var approvalsContainer: LinearLayout
    private lateinit var drawerScrim: View
    private lateinit var drawerLayout: LinearLayout
    private lateinit var sessionsListContainer: LinearLayout
    private var drawerWidthPx: Int = 0
    private var bubbleSnapAnimator: ValueAnimator? = null
    private var dotPulseAnimator: ObjectAnimator? = null
    private var micPulseAnimator: ObjectAnimator? = null

    private var isAgentRunning = false
    private var isRecording = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

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
        bubbleSnapAnimator?.cancel()
        dotPulseAnimator?.cancel()
        micPulseAnimator?.cancel()
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
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1D2C4D"), Color.parseColor("#11192A"))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dpToPx(2), Color.parseColor("#4F8CFF"))
            }
            elevation = dpToPx(10).toFloat()
        }

        val emblemView = ImageView(this).apply {
            setImageResource(R.drawable.ic_stat_forgemind)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#82B4FF"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dpToPx(13), dpToPx(13), dpToPx(13), dpToPx(13))
        }
        frame.addView(emblemView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        statusIndicatorDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
                setStroke(dpToPx(2), Color.parseColor("#11192A"))
            }
        }
        val dotSize = dpToPx(13)
        val dotParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dpToPx(2)
            rightMargin = dpToPx(2)
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
                    bubbleSnapAnimator?.cancel()
                    initialX = bp.x
                    initialY = bp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    frame.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90L).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                        isDragging = true
                    }
                    bp.x = initialX + dx
                    bp.y = (initialY + dy).coerceIn(
                        dpToPx(28),
                        (resources.displayMetrics.heightPixels - frame.height - dpToPx(36)).coerceAtLeast(dpToPx(40))
                    )
                    try {
                        wm.updateViewLayout(frame, bp)
                    } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    frame.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(140L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    if (!isDragging && event.action == MotionEvent.ACTION_UP) {
                        expandToCard()
                    } else {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val targetX = if (bp.x + frame.width / 2 < screenWidth / 2) {
                            dpToPx(14)
                        } else {
                            screenWidth - frame.width - dpToPx(14)
                        }
                        bubbleSnapAnimator?.cancel()
                        bubbleSnapAnimator = ValueAnimator.ofInt(bp.x, targetX).apply {
                            duration = 260L
                            interpolator = OvershootInterpolator(0.85f)
                            addUpdateListener { anim ->
                                bp.x = anim.animatedValue as Int
                                try {
                                    wm.updateViewLayout(frame, bp)
                                } catch (_: Throwable) {}
                            }
                            start()
                        }
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

        // Header: [Menu] + Title/Status + [Minimize] + [Fullscreen] + [Close]
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1E283D"), Color.parseColor("#161F31"))
            )
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            gravity = Gravity.CENTER_VERTICAL
        }

        // Drawer Menu Button
        val btnMenu = createHeaderIconButton(
            iconRes = R.drawable.ic_fg_menu,
            tintColor = Color.parseColor("#CBD5E1"),
            bgColor = Color.parseColor("#24314A")
        ) { toggleDrawer() }
        header.addView(btnMenu)

        val headerTextLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpToPx(10)
                rightMargin = dpToPx(6)
            }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cardStatusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
        }
        titleRow.addView(cardStatusDot, LinearLayout.LayoutParams(dpToPx(7), dpToPx(7)).apply {
            rightMargin = dpToPx(6)
        })

        titleTextView = TextView(this).apply {
            text = "ForgeMind"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EDF3"))
            maxLines = 1
        }
        titleRow.addView(titleTextView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        statusTextView = TextView(this).apply {
            text = getString(R.string.floating_ready)
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            maxLines = 1
        }
        headerTextLayout.addView(titleRow)
        headerTextLayout.addView(statusTextView)
        header.addView(headerTextLayout)

        // Action buttons: Minimize to bubble, Fullscreen app, Close overlay
        val btnMinimize = createHeaderIconButton(
            iconRes = R.drawable.ic_fg_minimize,
            tintColor = Color.parseColor("#CBD5E1"),
            bgColor = Color.parseColor("#24314A")
        ) { collapseToBubble() }
        val btnFullscreen = createHeaderIconButton(
            iconRes = R.drawable.ic_fg_open_app,
            tintColor = Color.parseColor("#7CB2FF"),
            bgColor = Color.parseColor("#1E2F4F")
        ) { openFullApp() }
        val btnClose = createHeaderIconButton(
            iconRes = R.drawable.ic_fg_close,
            tintColor = Color.parseColor("#FCA5A5"),
            bgColor = Color.parseColor("#331D24")
        ) { stopSelf() }

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
        val headerDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#2B3548"))
        }
        contentLayout.addView(headerDivider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)))

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
                setColor(Color.parseColor("#261D12"))
                setStroke(dpToPx(1), Color.parseColor("#F59E0B"))
                cornerRadius = dpToPx(10).toFloat()
            }
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            visibility = View.GONE
        }
        val appLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dpToPx(8), 0, dpToPx(8), dpToPx(4))
        }
        contentLayout.addView(approvalsContainer, appLp)

        val footerDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#2B3548"))
        }
        contentLayout.addView(footerDivider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)))

        // Footer input bar (extra left padding keeps bottom-left resize grip clear of input)
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141B2B"))
            }
            setPadding(dpToPx(26), dpToPx(8), dpToPx(10), dpToPx(10))
            gravity = Gravity.CENTER_VERTICAL
        }

        inputEditText = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dpToPx(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.parseColor("#1C2538"))
                setStroke(dpToPx(1), Color.parseColor("#2F3D57"))
            }
            setPadding(dpToPx(14), dpToPx(9), dpToPx(14), dpToPx(9))
            hint = getString(R.string.floating_input_hint)
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 13f
            maxLines = 4
        }
        footer.addView(inputEditText)

        // Microphone Button (42x42dp circular vector button)
        val btnSize = dpToPx(42)
        val btnIconPad = dpToPx(10)
        micButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_fg_mic)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#CBD5E1"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnIconPad, btnIconPad, btnIconPad, btnIconPad)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                rightMargin = dpToPx(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E273B"))
                setStroke(dpToPx(1), Color.parseColor("#334155"))
            }
            attachPressFeedback(this)
            setOnClickListener { toggleVoiceRecording() }
        }
        footer.addView(micButton)

        // Action Button: Send or Stop (42x42dp circular vector button)
        actionButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_fg_send)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnIconPad, btnIconPad, btnIconPad, btnIconPad)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4F8CFF"))
            }
            attachPressFeedback(this)
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

        // Layer 2: Corner Resize Handle (bottom-left vector grip away from Send button)
        val resizeGripSize = dpToPx(34)
        val resizeGrip = ImageView(this).apply {
            setImageResource(R.drawable.ic_fg_resize_grip_left)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dpToPx(4), dpToPx(12), dpToPx(12), dpToPx(4))
        }
        val resizeParams = FrameLayout.LayoutParams(resizeGripSize, resizeGripSize).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }

        var startW = 0
        var startH = 0
        var startX = 0
        var startY = 0
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
                    startX = cp.x
                    startY = cp.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    resizeGrip.imageTintList = ColorStateList.valueOf(Color.parseColor("#4F8CFF"))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    val next = calculateBottomLeftResize(
                        startW = startW,
                        startH = startH,
                        startCenterX = startX,
                        startCenterY = startY,
                        dx = dx,
                        dy = dy,
                        minW = minW,
                        maxW = maxW,
                        minH = minH,
                        maxH = maxH
                    )
                    cp.width = next.width
                    cp.height = next.height
                    cp.x = next.centerX
                    cp.y = next.centerY
                    try {
                        wm.updateViewLayout(rootFrame, cp)
                    } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resizeGrip.imageTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
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
        drawerWidthPx = (dm.widthPixels * 0.72f).toInt().coerceAtMost(dpToPx(300))

        drawerScrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#88060911"))
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { hideDrawer() }
        }
        rootFrame.addView(drawerScrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        drawerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0E1420"))
                setStroke(dpToPx(1), Color.parseColor("#2B3548"))
            }
            elevation = dpToPx(20).toFloat()
            translationX = -drawerWidthPx.toFloat()
            visibility = View.GONE
        }
        val drawerParams = FrameLayout.LayoutParams(drawerWidthPx, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START or Gravity.TOP
        }

        // Drawer Header: Chats title + close drawer button
        val dHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), dpToPx(10), dpToPx(8), dpToPx(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161F31"))
            }
        }
        val dTitle = TextView(this).apply {
            text = getString(R.string.floating_chats)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EDF3"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnCloseDrawer = createHeaderIconButton(
            iconRes = R.drawable.ic_fg_close,
            tintColor = Color.parseColor("#94A3B8"),
            bgColor = Color.parseColor("#24314A")
        ) { hideDrawer() }
        dHeader.addView(dTitle)
        dHeader.addView(btnCloseDrawer)
        drawerLayout.addView(dHeader)

        // Inside Drawer: New chat button with vector icon
        val btnNewChat = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#3B82F6"), Color.parseColor("#2563EB"))
            ).apply {
                cornerRadius = dpToPx(10).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(9), dpToPx(12), dpToPx(9))
            attachPressFeedback(this)
            setOnClickListener { createNewChatFromDrawer() }
        }
        val newChatIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_fg_add)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dpToPx(16), dpToPx(16)).apply {
                rightMargin = dpToPx(6)
            }
        }
        val newChatLabel = TextView(this).apply {
            text = getString(R.string.floating_new_chat)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        btnNewChat.addView(newChatIcon)
        btnNewChat.addView(newChatLabel)

        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(8))
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
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(10))
        }
        sv.addView(sessionsListContainer)
        drawerLayout.addView(sv)

        rootFrame.addView(drawerLayout, drawerParams)
    }

    private fun toggleDrawer() {
        if (drawerLayout.visibility == View.VISIBLE && drawerLayout.translationX > -drawerWidthPx * 0.5f) {
            hideDrawer()
        } else {
            showDrawer()
        }
    }

    private fun showDrawer() {
        refreshSessionsList()
        drawerScrim.visibility = View.VISIBLE
        drawerScrim.animate().cancel()
        drawerScrim.animate().alpha(1f).setDuration(200L).start()

        drawerLayout.visibility = View.VISIBLE
        drawerLayout.animate().cancel()
        if (drawerLayout.translationX < -drawerWidthPx) {
            drawerLayout.translationX = -drawerWidthPx.toFloat()
        }
        drawerLayout.animate()
            .translationX(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    private fun hideDrawer() {
        if (drawerLayout.visibility != View.VISIBLE) return
        drawerScrim.animate().cancel()
        drawerScrim.animate()
            .alpha(0f)
            .setDuration(170L)
            .withEndAction { drawerScrim.visibility = View.GONE }
            .start()

        drawerLayout.animate().cancel()
        drawerLayout.animate()
            .translationX(-drawerWidthPx.toFloat())
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator(1.3f))
            .withEndAction { drawerLayout.visibility = View.GONE }
            .start()
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
                            cornerRadius = dpToPx(10).toFloat()
                            setColor(if (isSelected) Color.parseColor("#1D2E4E") else Color.parseColor("#141C2B"))
                            if (isSelected) {
                                setStroke(dpToPx(1), Color.parseColor("#4F8CFF"))
                            } else {
                                setStroke(dpToPx(1), Color.parseColor("#1F2A3D"))
                            }
                        }
                        setPadding(dpToPx(11), dpToPx(9), dpToPx(11), dpToPx(9))
                        attachPressFeedback(this)
                        setOnClickListener {
                            activeSessionId = s.id
                            titleTextView.text = s.title
                            currentModelName = s.modelId
                            startObservingMessages(s.id)
                            hideDrawer()
                        }
                    }

                    val titleV = TextView(this@FloatingChatService).apply {
                        text = s.title.ifBlank { getString(R.string.floating_untitled) }
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
                title = getString(R.string.floating_new_chat),
                modelId = currentModelName
            )
            container.db.sessionDao().upsert(newSession)
            activeSessionId = newSession.id
            withContext(Dispatchers.Main) {
                titleTextView.text = newSession.title
                statusTextView.text = getString(R.string.floating_ready)
                startObservingMessages(newSession.id)
                hideDrawer()
            }
        }
    }

    private fun updateActionButton(running: Boolean) {
        isAgentRunning = running
        if (running) {
            actionButton.setImageResource(R.drawable.ic_fg_stop)
            actionButton.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EF4444"))
            }
        } else {
            actionButton.setImageResource(R.drawable.ic_fg_send)
            actionButton.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4F8CFF"))
            }
        }
        actionButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun stopAgentRun() {
        val app = application as? ForgeMindApp ?: return
        app.container.agentEngine.cancelRun()
        statusTextView.text = getString(R.string.floating_stopped)
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

        card.animate().cancel()
        card.alpha = 0f
        card.scaleX = 0.90f
        card.scaleY = 0.90f

        try {
            wm.addView(card, cardParams)
        } catch (_: Throwable) {}

        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(210L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()

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
        drawerScrim.visibility = View.GONE
        drawerLayout.visibility = View.GONE
        drawerLayout.translationX = -drawerWidthPx.toFloat()

        card.animate().cancel()
        card.animate()
            .alpha(0f)
            .scaleX(0.90f)
            .scaleY(0.90f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                runCatching { wm.removeView(card) }
                card.alpha = 1f
                card.scaleX = 1f
                card.scaleY = 1f
                bubble.alpha = 0f
                bubble.scaleX = 0.78f
                bubble.scaleY = 0.78f
                runCatching { wm.addView(bubble, bubbleParams) }
                bubble.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200L)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
            .start()
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
                micButton.setImageResource(R.drawable.ic_fg_stop)
                micButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
                micButton.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#EF4444"))
                    setStroke(dpToPx(2), Color.parseColor("#FCA5A5"))
                }
                startMicPulseAnimation()
                cardStatusDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#EF4444"))
                }
                statusTextView.text = getString(R.string.floating_listening)
            } else {
                statusTextView.text = getString(R.string.floating_mic_err)
            }
        } else {
            isRecording = false
            stopMicPulseAnimation()
            micButton.setImageResource(R.drawable.ic_fg_mic)
            micButton.imageTintList = ColorStateList.valueOf(Color.parseColor("#CBD5E1"))
            micButton.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E273B"))
                setStroke(dpToPx(1), Color.parseColor("#334155"))
            }
            updateStatusDots(isAgentRunning)
            statusTextView.text = getString(R.string.floating_transcribing)
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
                            statusTextView.text = getString(R.string.floating_ready)
                        }
                    }.onFailure { err ->
                        withContext(Dispatchers.Main) {
                            statusTextView.text = getString(R.string.floating_stt_err, err.message?.take(25) ?: "")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusTextView.text = getString(R.string.floating_ready)
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
                    cornerRadius = dpToPx(12).toFloat()
                    setColor(if (isUser) Color.parseColor("#1B2D4D") else Color.parseColor("#151C2B"))
                    setStroke(dpToPx(1), Color.parseColor(if (isUser) "#2D4A7C" else "#263147"))
                }
                setPadding(dpToPx(11), dpToPx(8), dpToPx(11), dpToPx(8))
            }

            // Role / Author header
            val authorView = TextView(this).apply {
                text = if (isUser) getString(R.string.label_you) else "ForgeMind (${currentModelName.substringAfterLast('/')})"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isUser) Color.parseColor("#60A5FA") else Color.parseColor("#10B981"))
            }
            msgBox.addView(authorView)

            // Thinking snippet (clean vector icon)
            if (!isUser && m.thinking.isNotBlank()) {
                val thinkingBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(8).toFloat()
                        setColor(Color.parseColor("#211B13"))
                        setStroke(dpToPx(1), Color.parseColor("#47361B"))
                    }
                    setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                }
                val thinkHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val thinkIcon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_fg_thinking)
                    imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                    layoutParams = LinearLayout.LayoutParams(dpToPx(13), dpToPx(13)).apply {
                        rightMargin = dpToPx(5)
                    }
                }
                val thinkTitle = TextView(this).apply {
                    text = getString(R.string.label_thinking_chars, m.thinking.length)
                    textSize = 10.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#F59E0B"))
                }
                thinkHeader.addView(thinkIcon)
                thinkHeader.addView(thinkTitle)
                thinkingBox.addView(thinkHeader)

                val thinkingBody = TextView(this).apply {
                    text = "${m.thinking.take(180)}${if (m.thinking.length > 180) "..." else ""}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#CBD5E1"))
                    setPadding(0, dpToPx(3), 0, 0)
                }
                thinkingBox.addView(thinkingBody)

                val thinkLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(5)
                    bottomMargin = dpToPx(4)
                }
                msgBox.addView(thinkingBox, thinkLp)
            }

            // Tool execution badge (clean vector terminal icon on pill)
            if (!m.toolName.isNullOrBlank()) {
                val toolPill = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(6).toFloat()
                        setColor(Color.parseColor("#16233B"))
                        setStroke(dpToPx(1), Color.parseColor("#2A426E"))
                    }
                    setPadding(dpToPx(7), dpToPx(4), dpToPx(8), dpToPx(4))
                }
                val toolIcon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_fg_tool)
                    imageTintList = ColorStateList.valueOf(Color.parseColor("#60A5FA"))
                    layoutParams = LinearLayout.LayoutParams(dpToPx(13), dpToPx(13)).apply {
                        rightMargin = dpToPx(5)
                    }
                }
                val toolLabel = TextView(this).apply {
                    text = m.toolName
                    textSize = 10.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#93C5FD"))
                }
                toolPill.addView(toolIcon)
                toolPill.addView(toolLabel)
                val toolLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(4)
                    bottomMargin = dpToPx(2)
                }
                msgBox.addView(toolPill, toolLp)
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
                updateStatusDots(running)
            }
        }

        serviceScope.launch {
            engine.statusBanner.collectLatest { banner ->
                statusTextView.text = banner?.ifBlank { getString(R.string.floating_ready) } ?: getString(R.string.floating_ready)
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
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val warnIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_fg_warning)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(15), dpToPx(15)).apply {
                rightMargin = dpToPx(6)
            }
        }
        val titleView = TextView(this).apply {
            text = getString(R.string.floating_approval_title, first.toolName)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F59E0B"))
        }
        titleRow.addView(warnIcon)
        titleRow.addView(titleView)
        approvalsContainer.addView(titleRow)

        val detailView = TextView(this).apply {
            text = first.details.take(150)
            textSize = 10.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dpToPx(3), 0, 0)
        }
        approvalsContainer.addView(detailView)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(6), 0, 0)
        }

        val btnAllow = createApprovalPillButton(
            iconRes = R.drawable.ic_fg_check,
            label = getString(R.string.action_allow),
            bgColor = Color.parseColor("#10B981"),
            fgColor = Color.parseColor("#06120E")
        ) {
            engine.resolveApproval(first.id, true)
        }

        val btnDeny = createApprovalPillButton(
            iconRes = R.drawable.ic_fg_close,
            label = getString(R.string.action_deny),
            bgColor = Color.parseColor("#EF4444"),
            fgColor = Color.WHITE
        ) {
            engine.resolveApproval(first.id, false)
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

    private fun createApprovalPillButton(
        @DrawableRes iconRes: Int,
        label: String,
        bgColor: Int,
        fgColor: Int,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dpToPx(8).toFloat()
        }
        setPadding(dpToPx(10), dpToPx(5), dpToPx(12), dpToPx(5))
        attachPressFeedback(this)
        addView(ImageView(this@FloatingChatService).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(fgColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(14), dpToPx(14)).apply {
                rightMargin = dpToPx(4)
            }
        })
        addView(TextView(this@FloatingChatService).apply {
            text = label
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fgColor)
        })
        setOnClickListener { onClick() }
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

    private fun createHeaderIconButton(
        @DrawableRes iconRes: Int,
        tintColor: Int,
        bgColor: Int,
        onClick: () -> Unit
    ): ImageView {
        val s = dpToPx(34)
        val p = dpToPx(8)
        return ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tintColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                leftMargin = dpToPx(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(9).toFloat()
                setColor(bgColor)
            }
            attachPressFeedback(this)
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachPressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.90f).scaleY(0.90f).alpha(0.85f).setDuration(80L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(130L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            false
        }
    }

    private fun updateStatusDots(running: Boolean) {
        val dotColor = when {
            isRecording -> Color.parseColor("#EF4444")
            running -> Color.parseColor("#4F8CFF")
            else -> Color.parseColor("#10B981")
        }
        statusIndicatorDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
            setStroke(dpToPx(2), Color.parseColor("#11192A"))
        }
        if (this::cardStatusDot.isInitialized) {
            cardStatusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
        }
        dotPulseAnimator?.cancel()
        if (running) {
            dotPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                statusIndicatorDot,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.32f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.32f, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f, 1f)
            ).apply {
                duration = 950L
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            statusIndicatorDot.scaleX = 1f
            statusIndicatorDot.scaleY = 1f
            statusIndicatorDot.alpha = 1f
        }
    }

    private fun startMicPulseAnimation() {
        micPulseAnimator?.cancel()
        micPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            micButton,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f, 1f)
        ).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopMicPulseAnimation() {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        micButton.scaleX = 1f
        micButton.scaleY = 1f
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
                getString(R.string.floating_notif_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.floating_channel_desc)
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
            .setSmallIcon(R.drawable.ic_stat_forgemind)
            .setContentTitle(getString(R.string.floating_notif_title))
            .setContentText(getString(R.string.floating_notif_text))
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_close), stopPi)
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

        data class ResizeBounds(
            val width: Int,
            val height: Int,
            val centerX: Int,
            val centerY: Int
        )

        /**
         * Computes the new width/height and window center coordinates when dragging the bottom-left
         * resize handle with Gravity.CENTER, keeping the top and right edges anchored.
         */
        fun calculateBottomLeftResize(
            startW: Int,
            startH: Int,
            startCenterX: Int,
            startCenterY: Int,
            dx: Int,
            dy: Int,
            minW: Int,
            maxW: Int,
            minH: Int,
            maxH: Int
        ): ResizeBounds {
            val newW = (startW - dx).coerceIn(minW, maxW)
            val newH = (startH + dy).coerceIn(minH, maxH)
            val newCenterX = startCenterX - (newW - startW) / 2
            val newCenterY = startCenterY + (newH - startH) / 2
            return ResizeBounds(width = newW, height = newH, centerX = newCenterX, centerY = newCenterY)
        }

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
