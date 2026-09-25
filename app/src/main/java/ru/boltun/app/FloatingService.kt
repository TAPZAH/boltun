package ru.boltun.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs

class FloatingService : Service(), TtsController.Listener {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var tts: TtsController
    private val handler = Handler(Looper.getMainLooper())

    private var bubbleView: ImageView? = null
    private var menuView: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var counterView: TextView? = null
    private var resumeButton: TextView? = null
    private var playPauseButton: ImageView? = null
    private var speedButton: TextView? = null
    private var menuAdded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        tts = TtsController(this).apply {
            speed = prefs.speed
            listener = this@FloatingService
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SPEAK -> {
                startInForeground()
                if (bubbleView == null) attachOverlay()
                isRunning = true
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrBlank()) tts.speak(text)
                return START_STICKY
            }
        }
        startInForeground()
        if (bubbleView == null) attachOverlay()
        prefs.enabled = true
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        bubbleView?.let { removeWindow(it) }
        menuView?.let { removeWindow(it) }
        bubbleView = null
        menuView = null
        bubbleParams = null
        menuParams = null
        counterView = null
        resumeButton = null
        playPauseButton = null
        speedButton = null
        menuAdded = false
        try {
            tts.shutdown()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun removeWindow(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun startInForeground() {
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    private fun attachOverlay() {
        val size = dp(BUBBLE_DP)
        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble)
            setBackgroundResource(R.drawable.bg_bubble)
            contentDescription = getString(R.string.cd_bubble)
            setPadding(dp(13), dp(13), dp(13), dp(13))
        }

        val bubbleLp = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.x
            y = prefs.y
        }

        val menu = buildMenu()
        val menuLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        bubbleView = bubble
        menuView = menu
        bubbleParams = bubbleLp
        menuParams = menuLp

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                readSelection()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleMenu()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                tts.stop()
            }
        })

        bubble.setOnTouchListener(DragListener(bubbleLp, gestureDetector))

        try {
            windowManager.addView(bubble, bubbleLp)
        } catch (_: Exception) {
            toast(getString(R.string.toast_no_overlay))
        }
    }

    private fun buildMenu(): LinearLayout {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            minimumWidth = dp(180)
            visibility = View.GONE
        }

        val counter = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        }
        counterView = counter

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = makeIconButton(R.drawable.ic_prev, R.string.cd_prev)
        val repeat = makeIconButton(R.drawable.ic_repeat, R.string.cd_repeat)
        val next = makeIconButton(R.drawable.ic_next, R.string.cd_next)
        prev.setOnClickListener {
            tts.jumpBy(-1)
            updateMenuState()
        }
        repeat.setOnClickListener {
            tts.repeatCurrent()
            updateMenuState()
        }
        next.setOnClickListener {
            tts.jumpBy(1)
            updateMenuState()
        }
        navRow.addView(prev)
        navRow.addView(repeat)
        navRow.addView(next)

        val playRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val playPause = makeIconButton(R.drawable.ic_play, R.string.cd_play)
        val stop = makeIconButton(R.drawable.ic_stop, R.string.cd_stop)
        val speed = makeSpeedButton()
        playPauseButton = playPause
        speedButton = speed
        playPause.setOnClickListener {
            togglePlayPause()
            updateMenuState()
        }
        stop.setOnClickListener {
            tts.stop()
            hideMenu()
        }
        speed.setOnClickListener { cycleSpeed() }
        playRow.addView(playPause)
        playRow.addView(stop)
        playRow.addView(speed)

        val resume = makeTextButton(getString(R.string.menu_resume), fullWidth = true)
        resumeButton = resume
        resume.setOnClickListener {
            tts.resumeLast()
            updateMenuState()
        }

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val appButton = makeTextButton(getString(R.string.menu_app), weight = 1f)
        val closeButton = makeTextButton(getString(R.string.menu_close), weight = 1f)
        appButton.setOnClickListener {
            openApp()
            hideMenu()
        }
        closeButton.setOnClickListener { hideMenu() }
        bottomRow.addView(appButton)
        bottomRow.addView(closeButton)

        menu.addView(counter)
        menu.addView(navRow)
        menu.addView(playRow)
        menu.addView(resume)
        menu.addView(bottomRow)
        return menu
    }

    private inner class DragListener(
        private val lp: WindowManager.LayoutParams,
        private val gestureDetector: GestureDetector
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragged = false
        private val slop = ViewConfiguration.get(this@FloatingService).scaledTouchSlop

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) dragged = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    bubbleView?.let { updateWindow(it, lp) }
                    positionMenu()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) {
                        prefs.x = lp.x
                        prefs.y = lp.y
                    }
                }
            }
            return true
        }
    }

    private fun togglePlayPause() {
        when (tts.state) {
            TtsController.State.SPEAKING -> tts.pause()
            TtsController.State.PAUSED -> tts.resume()
            TtsController.State.IDLE -> readSelection()
        }
    }

    private fun readSelection() {
        val service = SelectionAccessibilityService.instance
        if (service == null) {
            toast(getString(R.string.toast_a11y_needed))
            return
        }
        val text = service.captureText() ?: readClipboard()
        if (text.isNullOrBlank()) {
            toast(getString(R.string.toast_no_selection))
            return
        }
        tts.speak(text)
    }

    private fun readClipboard(): String? {
        return try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this).toString().trim().ifBlank { null }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cycleSpeed() {
        val next = SPEEDS.firstOrNull { it > tts.speed + 0.001f } ?: SPEEDS.first()
        tts.speed = next
        prefs.speed = next
        speedButton?.text = formatSpeed(next)
    }

    private fun toggleMenu() {
        if (menuAdded) hideMenu() else showMenu()
    }

    private fun showMenu() {
        val menu = menuView ?: return
        val menuLp = menuParams ?: return
        menu.visibility = View.VISIBLE
        if (!menuAdded) {
            try {
                windowManager.addView(menu, menuLp)
                menuAdded = true
            } catch (_: Exception) {
            }
        }
        updateMenuState()
        menu.post { positionMenu() }
    }

    private fun hideMenu() {
        val menu = menuView ?: return
        menu.visibility = View.GONE
        if (menuAdded) {
            removeWindow(menu)
            menuAdded = false
        }
    }

    private fun updateMenuState() {
        counterView?.text = tts.positionLabel()
        resumeButton?.visibility = if (tts.canResume()) View.VISIBLE else View.GONE
        playPauseButton?.setImageResource(
            if (tts.state == TtsController.State.SPEAKING) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun positionMenu() {
        val menu = menuView ?: return
        val menuLp = menuParams ?: return
        val bubbleLp = bubbleParams ?: return
        if (menu.visibility != View.VISIBLE) return
        val menuWidth = menu.width
        val menuHeight = menu.height
        if (menuWidth <= 0 || menuHeight <= 0) return

        val gap = dp(6)
        var x = bubbleLp.x - gap - menuWidth
        if (x < 0) {
            x = bubbleLp.x + dp(BUBBLE_DP) + gap
            if (x + menuWidth > screenWidth()) x = screenWidth() - menuWidth
            if (x < 0) x = 0
        }
        var y = bubbleLp.y
        if (y + menuHeight > screenHeight()) y = screenHeight() - menuHeight
        if (y < 0) y = 0

        menuLp.x = x
        menuLp.y = y
        updateWindow(menu, menuLp)
    }

    private fun openApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    private fun makeIconButton(iconRes: Int, contentDescriptionRes: Int): ImageView {
        return ImageView(this).apply {
            setImageResource(iconRes)
            contentDescription = getString(contentDescriptionRes)
            setBackgroundResource(R.drawable.bg_panel_button)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = false
            val size = dp(42)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(4) }
        }
    }

    private fun makeSpeedButton(): TextView {
        return TextView(this).apply {
            text = formatSpeed(tts.speed)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.cd_speed)
            setBackgroundResource(R.drawable.bg_panel_button)
            isClickable = true
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(42)).apply { marginStart = dp(4) }
        }
    }

    private fun makeTextButton(label: String, fullWidth: Boolean = false, weight: Float = 0f): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_panel_button)
            isClickable = true
            isFocusable = false
            val height = dp(42)
            layoutParams = when {
                weight > 0f -> LinearLayout.LayoutParams(0, height, weight).apply {
                    topMargin = dp(4)
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
                fullWidth -> LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    height
                ).apply {
                    topMargin = dp(4)
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
                else -> LinearLayout.LayoutParams(dp(120), height).apply { marginStart = dp(4) }
            }
            setPadding(dp(10), 0, dp(10), 0)
        }
    }

    private fun updateWindow(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun formatSpeed(value: Float): String = String.format("%.2f\u00D7", value)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onState(state: TtsController.State) {
        handler.post {
            when (state) {
                TtsController.State.SPEAKING, TtsController.State.PAUSED -> {
                    showMenu()
                    updateMenuState()
                }
                TtsController.State.IDLE -> hideMenu()
            }
        }
    }

    override fun onProgress(position: Int, total: Int) {
        handler.post {
            counterView?.text = "$position / $total"
        }
    }

    override fun onError(message: String?) {
        handler.post {
            hideMenu()
            if (!message.isNullOrBlank()) toast(message)
        }
    }

    companion object {
        const val ACTION_START = "ru.boltun.app.action.START"
        const val ACTION_STOP = "ru.boltun.app.action.STOP"
        const val ACTION_SPEAK = "ru.boltun.app.action.SPEAK"
        const val EXTRA_TEXT = "ru.boltun.app.extra.TEXT"

        private const val CHANNEL_ID = "boltun_foreground"
        private const val NOTIFICATION_ID = 1
        private const val BUBBLE_DP = 52

        private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun speak(context: Context, text: String) {
            val intent = Intent(context, FloatingService::class.java)
                .setAction(ACTION_SPEAK)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingService::class.java))
        }
    }
}
