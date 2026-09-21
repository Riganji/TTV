package com.example.teliktv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Повторный запуск приложения после установки обновления.
 *
 * Установка поверх убивает процесс, и пользователь остаётся на рабочем столе. Поэтому перед
 * коммитом установки ставится метка (arm), а после замены пакета система шлёт
 * ACTION_MY_PACKAGE_REPLACED — PackageReplacedReceiver видит метку и открывает MainActivity.
 * Метка нужна, чтобы не открываться после чужой установки (adb install и т.п.).
 *
 * Ограничение: с Android 10 система не даёт открывать окна из фона, и запуск из приёмника
 * может быть молча заблокирован (на Android 9 и ниже работает всегда).
 */
object UpdateRelaunch {
    private const val PREFS = "teliktv"
    private const val KEY = "relaunch_after_update_at"

    /** Метка живёт недолго: если установку не подтвердили, она не должна сработать спустя часы. */
    private const val TTL_MS = 30 * 60 * 1000L

    // commit(), а не apply(): сразу после установки процесс убивают, отложенная запись может не успеть.
    fun arm(context: Context) {
        prefs(context).edit().putLong(KEY, System.currentTimeMillis()).commit()
    }

    fun disarm(context: Context) {
        prefs(context).edit().remove(KEY).commit()
    }

    /** true — метка стоит и свежая; метка при этом снимается. */
    fun consume(context: Context): Boolean {
        val p = prefs(context)
        val at = p.getLong(KEY, 0L)
        if (at == 0L) return false
        p.edit().remove(KEY).commit()
        return System.currentTimeMillis() - at in 0..TTL_MS
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Получает ACTION_MY_PACKAGE_REPLACED — только для нашего пакета, после обновления. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!UpdateRelaunch.consume(context)) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.w("UpdateRelaunch", "не удалось открыть приложение после обновления: ${e.message}")
        }
    }
}