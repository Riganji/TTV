package com.example.teliktv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Статусы установки от PackageInstaller.
 *
 * STATUS_PENDING_USER_ACTION — система просит показать окно подтверждения: его нужно
 * запустить самим, иначе установка молча зависнет. Остальные статусы уходят в UpdateBus,
 * чтобы меню настроек показало результат.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    UpdateRelaunch.disarm(context)
                    UpdateBus.report("система не открыла окно подтверждения")
                } else {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        UpdateRelaunch.disarm(context)
                        UpdateBus.report("не открылось окно установки: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> UpdateBus.report(null)

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                UpdateRelaunch.disarm(context)
                UpdateBus.report("установка отменена")
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                UpdateRelaunch.disarm(context)
                UpdateBus.report(
                    "подпись новой версии не совпадает с установленной — удалите приложение и поставьте заново",
                )
            }

            else -> {
                UpdateRelaunch.disarm(context)
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateBus.report(msg?.takeIf { it.isNotBlank() } ?: "ошибка установки (код $status)")
            }
        }
    }
}