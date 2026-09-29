package de.securitycam

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var recordAudio: Boolean
        get() = prefs.getBoolean("audio", false)
        set(v) = prefs.edit().putBoolean("audio", v).apply()

    var frontCamera: Boolean
        get() = prefs.getBoolean("front", false)
        set(v) = prefs.edit().putBoolean("front", v).apply()

    var maxStorageGb: Int
        get() = prefs.getInt("max_gb", 8)
        set(v) = prefs.edit().putInt("max_gb", v).apply()

    val maxStorageBytes: Long get() = maxStorageGb.toLong() * 1024 * 1024 * 1024

    companion object {
        val STORAGE_OPTIONS_GB = listOf(2, 4, 8, 16, 32, 64)
        /** Länge eines Videoabschnitts. */
        const val SEGMENT_MINUTES = 10L
    }
}
