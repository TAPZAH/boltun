package ru.boltun.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SelectionAccessibilityService : AccessibilityService() {

    private val windowManager: WindowManager
        get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var focusView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        lastSelection = null
        lastPackage = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        releaseFocus()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val text = extractSelection(event)
                if (!text.isNullOrBlank()) {
                    lastSelection = text
                    lastPackage = event.packageName?.toString()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (pkg != null && pkg != packageName && pkg != lastPackage) {
                    lastSelection = null
                }
            }
        }
    }

    fun captureText(): String? {
        lastSource = SOURCE_NONE

        cachedSelection()?.let {
            lastSource = SOURCE_CACHE
            return it
        }

        val root = rootInActiveWindow

        val candidate = findSelectionNode(root)
            ?: inputFocus()
            ?: findEditableOrFocused(root)

        if (candidate != null) {
            extractSelection(candidate)?.let {
                lastSource = SOURCE_NODE
                return it
            }
            if (candidate.performAction(AccessibilityNodeInfo.ACTION_COPY)) {
                readClipboardWithFocus()?.let {
                    lastSource = SOURCE_COPY
                    return it
                }
            }
        }

        return readClipboardWithFocus()?.also { lastSource = SOURCE_CLIPBOARD }
    }

    private fun cachedSelection(): String? = lastSelection?.takeIf { it.isNotBlank() }

    private fun extractSelection(event: AccessibilityEvent): String? {
        val source = event.source
        val full = source?.text?.toString()
            ?: event.text?.joinToString("")
        val start = source?.textSelectionStart ?: -1
        val end = source?.textSelectionEnd ?: -1
        if (full != null && start in 0 until end && end <= full.length) {
            return full.substring(start, end).trim().ifBlank { null }
        }
        return extractSelection(source) ?: extractSelection(inputFocus())
    }

    private fun findSelectionNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return search(root) { node ->
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            start in 0 until end
        }
    }

    private fun findEditableOrFocused(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return search(root) { node ->
            node.isEditable ||
                node.isFocused ||
                node.className?.toString()?.contains("WebView", ignoreCase = true) == true
        }
    }

    private fun search(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun inputFocus(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }

    private fun extractSelection(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        val text = node.text?.toString() ?: return null
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start in 0 until end && end <= text.length) {
            return text.substring(start, end).trim().ifBlank { null }
        }
        return null
    }

    private fun readClipboardWithFocus(): String? {
        return try {
            acquireFocus()
            readClipboard()
        } catch (_: Exception) {
            null
        } finally {
            releaseFocus()
        }
    }

    private fun acquireFocus() {
        if (focusView != null) return
        val view = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(view, params)
        view.requestFocus()
        focusView = view
        SystemClock.sleep(FOCUS_SETTLE_MS)
    }

    private fun releaseFocus() {
        val view = focusView ?: return
        focusView = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
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

    companion object {
        private const val FOCUS_SETTLE_MS = 150L
        private const val MAX_NODES = 600

        const val SOURCE_NONE = "none"
        const val SOURCE_CACHE = "cache"
        const val SOURCE_NODE = "node"
        const val SOURCE_COPY = "copy"
        const val SOURCE_CLIPBOARD = "clipboard"

        @Volatile
        var instance: SelectionAccessibilityService? = null
            private set

        @Volatile
        private var lastSelection: String? = null

        @Volatile
        private var lastPackage: String? = null

        @Volatile
        var lastSource: String = SOURCE_NONE
            private set

        fun latestSelection(): String? = lastSelection
    }
}
