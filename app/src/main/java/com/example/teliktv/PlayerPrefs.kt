package com.example.teliktv

import android.content.Context

/**
 * Настройки плеера (SharedPreferences «teliktv»).
 * maxBufferSec — максимальный размер буфера ExoPlayer в секундах:
 * это и есть примерное время, на которое можно поставить паузу (timeshift)
 * при типичном битрейте ~4 Мбит/с HD (~0,5 МБ/с).
 */
object PlayerPrefs {
    private const val PREFS = "teliktv"
    private const val KEY_MAX_BUFFER_SEC = "max_buffer_sec"

    /** Допустимые значения буфера (сек). */
    val OPTIONS = listOf(30, 60, 120, 180, 300)

    const val DEFAULT_SEC = 60

    /** Оценка битрейта для перевода секунд → МБ (~4 Мбит/с HD). */
    private const val MB_PER_SEC = 0.5  // 4 Мбит/с / 8 = 0,5 МБ/с

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

    /** Следующее значение в цикле OPTIONS. */
    fun next(sec: Int): Int {
        val i = OPTIONS.indexOf(sec).let { if (it < 0) 0 else it }
        return OPTIONS[(i + 1) % OPTIONS.size]
    }

    /** «время (примерный размер МБ)» — для настроек и оверлея паузы. */
    fun hint(sec: Int): String {
        val time = when {
            sec < 60 -> "$sec с"
            sec % 60 == 0 -> "${sec / 60} мин"
            else -> "${"%.1f".format(sec / 60.0)} мин"
        }
        val mb = (sec * MB_PER_SEC).toInt().coerceAtLeast(1)
        return "$time (≈$mb МБ)"
    }
}
