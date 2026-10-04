package ru.boltun.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        val showButton = prefs.enabled && Settings.canDrawOverlays(context)
        if (prefs.keepNotification || showButton) {
            FloatingService.ensure(context)
        }
    }
}
