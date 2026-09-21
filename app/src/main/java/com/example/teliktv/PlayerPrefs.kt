package com.example.teliktv

import android.content.Context

/**
 * Настройки плеера (SharedPreferences «teliktv»).
 * maxBufferSec — макс. время timeshift; на диске кэш ≈ sec × 0,5 МБ/с (~4 Мбит/с HD).
 */
object PlayerPrefs {
    private const val PREFS = "teliktv"
    private const val KEY_MAX_BUFFER_SEC = "max_buffer_sec"

    /** Допустимые значения (сек), до 1 часа. */
    val OPTIONS = listOf(60, 120, 300, 600, 1800, 3600)

    const val DEFAULT_SEC = 300

    /** ~4 Мбит/с HD → 0,5 МБ/с. */
    const val MB_PER_SEC = 0.5

    fun getMaxBufferSec(context: Context): Int {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_BUFFER_SEC, DEFAULT_SEC)
        return if (v in OPTIONS) v else DEFAULT_SEC
    }

    fun setMaxBufferSec(context: Context, sec: Int) {
        val value = if (sec in OPTIONS) sec else DEFAULT_SEC
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_BUFFER_SEC, value)
            .apply()
    }

    fun next(sec: Int): Int {
        val i = OPTIONS.indexOf(sec).let { if (it < 0) 0 else it }
        return OPTIONS[(i + 1) % OPTIONS.size]
    }

    fun estimateMb(sec: Int): Int = (sec * MB_PER_SEC).toInt().coerceAtLeast(1)

    /** Байты под кэш на диске (+15 % запас). */
    fun maxCacheBytes(sec: Int): Long = (estimateMb(sec) * 1024L * 1024L * 115) / 100

    fun formatTime(sec: Int): String = when {
        sec < 60 -> "$sec с"
        sec % 3600 == 0 -> "${sec / 3600} ч"
        sec % 60 == 0 && sec >= 3600 -> "${sec / 3600} ч ${(sec % 3600) / 60} мин"
        sec % 60 == 0 -> "${sec / 60} мин"
        else -> "${"%.1f".format(sec / 60.0)} мин"
    }

    fun formatMb(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024 -> "${"%.1f".format(mb / 1024)} ГБ"
            mb >= 10 -> "${mb.toInt()} МБ"
            mb >= 1 -> "${"%.1f".format(mb)} МБ"
            else -> "${(bytes / 1024).coerceAtLeast(0)} КБ"
        }
    }

    /** «время (≈N МБ)» */
    fun hint(sec: Int): String = "${formatTime(sec)} (≈${estimateMb(sec)} МБ)"

    /** Подпись в настройках с учётом свободного места. */
    fun settingsHint(sec: Int, freeBytes: Long): String {
        val need = estimateMb(sec)
        val free = formatMb(freeBytes)
        val warn = if (freeBytes < need * 1024L * 1024L) " · мало места" else ""
        return "${hint(sec)} · свободно $free$warn — OK, сменить"
    }
}
