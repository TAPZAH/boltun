package ru.boltun.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsController(context: Context) {

    enum class State { IDLE, SPEAKING, PAUSED }

    data class EngineOption(val name: String, val label: String, val enabled: Boolean)

    interface Listener {
        fun onState(state: State)
        fun onError(message: String?)
        fun onProgress(position: Int, total: Int)
    }

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var pendingStart = 0
    private var engineId: String? = null
    private var triedFallback = false

    private var chunks: MutableList<String> = mutableListOf()
    private var index = 0
    private var paused = false
    private var sequence = 0
    private var activeId = ""

    var speed: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.5f)
            tts?.setSpeechRate(field)
        }

    var state: State = State.IDLE
        private set

    var listener: Listener? = null

    var onReady: (() -> Unit)? = null

    private val initListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts
            if (engine == null) {
                listener?.onError("TTS unavailable")
                return@OnInitListener
            }
            val result = engine.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(engineLanguage(engine))
            }
            engine.setSpeechRate(speed)
            engine.setOnUtteranceProgressListener(progressListener)
            ready = true
            onReady?.invoke()
            val queued = pendingText
            pendingText = null
            if (queued != null) speak(queued, pendingStart)
        } else if (!triedFallback) {
            triedFallback = true
            engineId = null
            createEngine(null)
        } else {
            setState(State.IDLE)
            listener?.onError("TTS initialization failed")
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            if (utteranceId != activeId) return
            if (paused) return
            index++
            prefs.lastIndex = index
            if (index < chunks.size) speakCurrent() else finish()
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {}

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (utteranceId != activeId) return
            listener?.onError("Playback error")
            finish()
        }
    }

    init {
        refreshEngineIfNeeded()
    }

    fun refreshEngineIfNeeded() {
        val wanted = prefs.engine.ifBlank { systemDefaultEngine() }
        if (tts != null && wanted == engineId) return
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
        engineId = wanted
        triedFallback = false
        createEngine(wanted)
    }

    private fun createEngine(engineName: String?) {
        ready = false
        tts = if (engineName.isNullOrBlank()) {
            TextToSpeech(appContext, initListener)
        } else {
            TextToSpeech(appContext, initListener, engineName)
        }
    }

    private fun systemDefaultEngine(): String? {
        return try {
            Settings.Secure.getString(appContext.contentResolver, "tts_default_synth")
        } catch (_: Exception) {
            null
        }
    }

    fun engineLabel(): String {
        val engine = tts
        val name = try {
            engine?.defaultEngine
        } catch (_: Exception) {
            null
        } ?: engineId
        if (name == null) return "-"
        val label = try {
            engine?.engines?.firstOrNull { it.name == name }?.label?.toString()
        } catch (_: Exception) {
            null
        }
        return label ?: name
    }

    fun engineList(): List<EngineOption> {
        val packageManager = appContext.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        @Suppress("DEPRECATION")
        val services = try {
            packageManager.queryIntentServices(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }

        val enabled = try {
            tts?.engines.orEmpty().map { it.name }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val labels = try {
            tts?.engines.orEmpty().associate { it.name to (it.label?.toString() ?: it.name) }
        } catch (_: Exception) {
            emptyMap()
        }

        return services
            .map { it.serviceInfo.packageName }
            .distinct()
            .map { pkg ->
                val label = labels[pkg] ?: try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg
                }
                EngineOption(pkg, label, enabled.contains(pkg))
            }
    }

    fun selectedEngine(): String = prefs.engine

    private fun engineLanguage(engine: TextToSpeech): Locale {
        return try {
            engine.defaultVoice?.locale ?: Locale.getDefault()
        } catch (_: Exception) {
            Locale.getDefault()
        }
    }

    fun speak(text: String, startIndex: Int = 0) {
        refreshEngineIfNeeded()
        val clean = text.trim()
        if (clean.isEmpty()) {
            listener?.onError("empty")
            return
        }
        pendingStart = startIndex
        if (!ready) {
            pendingText = clean
            return
        }
        startPlayback(clean, startIndex)
    }

    private fun startPlayback(clean: String, startIndex: Int) {
        chunks = split(clean).toMutableList()
        index = startIndex.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
        paused = false
        prefs.lastText = clean
        prefs.lastIndex = index
        setState(State.SPEAKING)
        speakCurrent()
    }

    fun jumpBy(delta: Int) {
        if (chunks.isEmpty()) return
        index = (index + delta).coerceIn(0, chunks.size - 1)
        paused = false
        prefs.lastIndex = index
        setState(State.SPEAKING)
        speakCurrent()
    }

    fun repeatCurrent() {
        if (chunks.isEmpty()) return
        paused = false
        setState(State.SPEAKING)
        speakCurrent()
    }

    fun replay() {
        val text = prefs.lastText
        if (text.isBlank()) return
        speak(text, 0)
    }

    fun positionLabel(): String {
        if (chunks.isEmpty()) return ""
        return "${index + 1} / ${chunks.size}"
    }

    fun canResume(): Boolean = prefs.lastText.isNotBlank()

    fun resumeLast() {
        val text = prefs.lastText
        if (text.isBlank()) return
        speak(text, prefs.lastIndex)
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        if (index >= chunks.size) {
            finish()
            return
        }
        listener?.onProgress(index + 1, chunks.size)
        sequence++
        activeId = "$UTTERANCE_ID-$sequence"
        engine.speak(chunks[index], TextToSpeech.QUEUE_FLUSH, Bundle(), activeId)
    }

    fun pause() {
        if (state != State.SPEAKING) return
        paused = true
        if (chunks.isNotEmpty()) prefs.lastIndex = index
        tts?.stop()
        setState(State.PAUSED)
    }

    fun resume() {
        if (state != State.PAUSED) return
        paused = false
        setState(State.SPEAKING)
        speakCurrent()
    }

    fun stop() {
        paused = false
        if (chunks.isNotEmpty()) prefs.lastIndex = index
        chunks = mutableListOf()
        index = 0
        activeId = ""
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        if (state != State.IDLE) setState(State.IDLE)
    }

    fun shutdown() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
        state = State.IDLE
    }

    private fun finish() {
        paused = false
        index = 0
        chunks = mutableListOf()
        prefs.lastIndex = 0
        activeId = ""
        setState(State.IDLE)
    }

    private fun setState(value: State) {
        if (state != value) {
            state = value
            listener?.onState(value)
        }
    }

    private fun split(text: String): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\u2026' || ch == '\n' ||
                ch == '\u3002' || ch == '\uFF01' || ch == '\uFF1F'
            ) {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) parts.add(s)
                sb.setLength(0)
            }
        }
        val rest = sb.toString().trim()
        if (rest.isNotEmpty()) parts.add(rest)
        if (parts.isEmpty()) parts.add(text)

        val capped = ArrayList<String>()
        for (part in parts) {
            var s = part
            while (s.length > 900) {
                capped.add(s.substring(0, 900))
                s = s.substring(900)
            }
            if (s.isNotEmpty()) capped.add(s)
        }
        return capped
    }

    companion object {
        private const val UTTERANCE_ID = "boltun"
    }
}
