package ru.boltun.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class SpeakActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = extractText(intent)
        if (!text.isNullOrBlank()) {
            FloatingService.speak(this, text)
        }
        finish()
    }

    private fun extractText(intent: Intent?): String? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
    }
}
