package ru.boltun.app

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    var speed: Float
        get() = sp.getFloat(KEY_SPEED, 1.0f)
        set(value) = sp.edit().putFloat(KEY_SPEED, value).apply()

    var engine: String
        get() = sp.getString(KEY_ENGINE, "") ?: ""
        set(value) = sp.edit().putString(KEY_ENGINE, value).apply()

    var lastText: String
        get() = sp.getString(KEY_LAST_TEXT, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_TEXT, value).apply()

    var lastIndex: Int
        get() = sp.getInt(KEY_LAST_INDEX, 0)
        set(value) = sp.edit().putInt(KEY_LAST_INDEX, value).apply()

    var x: Int
        get() = sp.getInt(KEY_X, 0)
        set(value) = sp.edit().putInt(KEY_X, value).apply()

    var y: Int
        get() = sp.getInt(KEY_Y, 300)
        set(value) = sp.edit().putInt(KEY_Y, value).apply()

    companion object {
        private const val NAME = "boltun"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SPEED = "speed"
        private const val KEY_ENGINE = "engine"
        private const val KEY_LAST_TEXT = "last_text"
        private const val KEY_LAST_INDEX = "last_index"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }
}
