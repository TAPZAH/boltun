package ru.readaloud.app

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
    private var panelView: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var speedButton: TextView? = null
    private var panelAdded = false

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
        panelView?.let { removeWindow(it) }
        bubbleView = null
        panelView = null
        bubbleParams = null
        panelParams = null
        speedButton = null
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.x
            y = prefs.y
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            visibility = View.GONE
        }

        val play = makeIconButton(R.drawable.ic_play, R.string.cd_play)
        val pause = makeIconButton(R.drawable.ic_pause, R.string.cd_pause)
        val stop = makeIconButton(R.drawable.ic_stop, R.string.cd_stop)
        val speed = makeSpeedButton()
        speedButton = speed

        play.setOnClickListener {
            if (tts.state == TtsController.State.PAUSED) tts.resume() else readSelection()
        }
        pause.setOnClickListener { tts.pause() }
        stop.setOnClickListener { tts.stop() }
        speed.setOnClickListener { cycleSpeed() }

        panel.addView(play)
        panel.addView(pause)
        panel.addView(stop)
        panel.addView(speed)

        val panelLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        bubbleView = bubble
        panelView = panel
        bubbleParams = bubbleLp
        panelParams = panelLp

        bubble.setOnTouchListener(DragListener(bubbleLp))

        try {
            windowManager.addView(bubble, bubbleLp)
        } catch (_: Exception) {
            toast(getString(R.string.toast_no_overlay))
        }
    }

    private inner class DragListener(
        private val lp: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragged = false

        private val slop = ViewConfiguration.get(this@FloatingService).scaledTouchSlop
        private val longPress = Runnable { if (!dragged) tts.stop() }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragged = false
                    handler.postDelayed(
                        longPress,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) {
                        dragged = true
                        handler.removeCallbacks(longPress)
                    }
                    lp.x = startX + dx
                    lp.y = startY + dy
                    bubbleView?.let { updateWindow(it, lp) }
                    positionPanel()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (dragged) {
                        prefs.x = lp.x
                        prefs.y = lp.y
                    } else {
                        readSelection()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    return true
                }
            }
            return false
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

    private fun setPanelVisible(visible: Boolean) {
        val panel = panelView ?: return
        val panelLp = panelParams ?: return
        if (visible) {
            panel.visibility = View.VISIBLE
            if (!panelAdded) {
                try {
                    windowManager.addView(panel, panelLp)
                    panelAdded = true
                } catch (_: Exception) {
                }
            }
            panel.post { positionPanel() }
        } else {
            panel.visibility = View.GONE
            if (panelAdded) {
                removeWindow(panel)
                panelAdded = false
            }
        }
    }

    private fun positionPanel() {
        val panel = panelView ?: return
        val panelLp = panelParams ?: return
        val bubbleLp = bubbleParams ?: return
        if (panel.visibility != View.VISIBLE) return
        val panelWidth = panel.width
        val panelHeight = panel.height
        if (panelWidth <= 0 || panelHeight <= 0) return

        val gap = dp(6)
        var x = bubbleLp.x - gap - panelWidth
        if (x < 0) {
            x = bubbleLp.x + dp(BUBBLE_DP) + gap
            if (x + panelWidth > screenWidth()) x = screenWidth() - panelWidth
            if (x < 0) x = 0
        }
        var y = bubbleLp.y
        if (y + panelHeight > screenHeight()) y = screenHeight() - panelHeight
        if (y < 0) y = 0

        panelLp.x = x
        panelLp.y = y
        updateWindow(panel, panelLp)
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
                TtsController.State.SPEAKING, TtsController.State.PAUSED -> setPanelVisible(true)
                TtsController.State.IDLE -> setPanelVisible(false)
            }
        }
    }

    override fun onError(message: String?) {
        handler.post {
            setPanelVisible(false)
            if (!message.isNullOrBlank()) toast(message)
        }
    }

    companion object {
        const val ACTION_START = "ru.readaloud.app.action.START"
        const val ACTION_STOP = "ru.readaloud.app.action.STOP"
        const val ACTION_SPEAK = "ru.readaloud.app.action.SPEAK"
        const val EXTRA_TEXT = "ru.readaloud.app.extra.TEXT"

        private const val CHANNEL_ID = "readaloud_foreground"
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
