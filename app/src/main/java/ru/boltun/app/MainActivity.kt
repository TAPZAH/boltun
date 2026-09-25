package ru.boltun.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var tts: TtsController
    private lateinit var statusView: TextView
    private lateinit var speedLabel: TextView
    private lateinit var engineLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        tts = TtsController(this)
        tts.speed = prefs.speed

        statusView = findViewById(R.id.status)
        speedLabel = findViewById(R.id.speed_label)
        engineLabel = findViewById(R.id.engine)

        tts.onReady = { runOnUiThread { updateEngineLabel() } }
        updateEngineLabel()
        val speedBar = findViewById<SeekBar>(R.id.speed)

        speedBar.progress = (prefs.speed * 100).toInt()
        updateSpeedLabel(prefs.speed)
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(50) / 100f
                tts.speed = value
                prefs.speed = value
                updateSpeedLabel(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_overlay).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.btn_a11y).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btn_notif).setOnClickListener { requestNotifications() }
        findViewById<Button>(R.id.btn_start).setOnClickListener { startFloating() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopFloating() }
        findViewById<Button>(R.id.btn_battery).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.btn_tts_settings).setOnClickListener { openTtsSettings() }
        findViewById<Button>(R.id.btn_pick_engine).setOnClickListener { pickEngine() }
        findViewById<Button>(R.id.btn_test).setOnClickListener {
            tts.speak(getString(R.string.test_phrase))
        }
        findViewById<Button>(R.id.btn_probe).setOnClickListener { probeCapture() }
    }

    private fun probeCapture() {
        val service = SelectionAccessibilityService.instance
        if (service == null) {
            toast(getString(R.string.toast_a11y_needed))
            return
        }
        val text = service.captureText()
        if (text.isNullOrBlank()) {
            toast(getString(R.string.probe_empty))
        } else {
            toast(
                getString(
                    R.string.probe_ok,
                    text.length,
                    SelectionAccessibilityService.lastSource,
                    text.take(60)
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        tts.refreshEngineIfNeeded()
        updateStatus()
        updateEngineLabel()
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    private fun startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_no_overlay))
            openOverlaySettings()
            return
        }
        FloatingService.start(this)
    }

    private fun stopFloating() {
        prefs.enabled = false
        FloatingService.stop(this)
        updateStatus()
    }

    private fun updateStatus() {
        val lines = listOf(
            if (Settings.canDrawOverlays(this)) R.string.status_overlay_on else R.string.status_overlay_off,
            if (isAccessibilityEnabled()) R.string.status_a11y_on else R.string.status_a11y_off,
            if (notificationsAllowed()) R.string.status_notif_on else R.string.status_notif_off
        )
        statusView.text = lines.joinToString("\n") { getString(it) }
    }

    private fun updateSpeedLabel(value: Float) {
        speedLabel.text = getString(R.string.speed_label, value)
    }

    private fun updateEngineLabel() {
        engineLabel.text = getString(R.string.engine_label, tts.engineLabel())
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent(TTS_SETTINGS_ACTION))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun pickEngine() {
        val engines = tts.engineList()
        if (engines.isEmpty()) {
            toast(getString(R.string.engine_none))
            return
        }
        val options = ArrayList<String>()
        options.add(getString(R.string.engine_system_option))
        engines.forEach { option ->
            val suffix = if (option.enabled) "" else " (${getString(R.string.engine_disabled)})"
            options.add("${option.label}\n${option.name}$suffix")
        }

        val current = tts.selectedEngine()
        val checked = if (current.isBlank()) {
            0
        } else {
            val index = engines.indexOfFirst { it.name == current }
            if (index >= 0) index + 1 else 0
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.engine_pick_title)
            .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                if (which == 0) {
                    prefs.engine = ""
                } else {
                    val option = engines[which - 1]
                    prefs.engine = option.name
                    if (!option.enabled) toast(getString(R.string.engine_enable_hint))
                }
                tts.refreshEngineIfNeeded()
                updateEngineLabel()
                dialog.dismiss()
            }
            .show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == packageName }
    }

    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationsAllowed()
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 100
        private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"
    }
}
