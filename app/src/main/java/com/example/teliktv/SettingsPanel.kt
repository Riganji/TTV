package com.example.teliktv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class SettingsItem(
    val title: String,
    val hint: String,
    val hintColor: Color = TextDim,
    /** Третья строка — сейчас это описание релиза из GitHub. */
    val extra: String? = null,
    val onClick: () -> Unit,
)

/**
 * Меню настроек (кнопка Menu на пульте или «шестерёнка» в списке групп).
 * Здесь собрано всё, что раньше висело отдельными строками под группами каналов.
 * Назад / Menu — закрыть.
 */
@Composable
fun SettingsPanel(
    status: LoadStatus,
    epg: EpgData,
    epgStatus: EpgStatus,
    failures: List<ChannelFailure>,
    update: UpdateState,
    maxBufferSec: Int = PlayerPrefs.DEFAULT_SEC,
    onBufferCycle: () -> Unit = {},
    /** null — пункт паузы не показываем (список каналов). */
    paused: Boolean? = null,
    onTogglePause: (() -> Unit)? = null,
    onGoLive: (() -> Unit)? = null,
    onRefreshChannels: () -> Unit,
    onRefreshEpg: () -> Unit,
    onShowReport: () -> Unit,
    onUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val freeBytes = remember(maxBufferSec) { StreamCache.freeDiskBytes(context) }

    val items = buildList {
        if (onTogglePause != null && paused != null) {
            add(
                SettingsItem(
                    title = if (paused) "Продолжить" else "Пауза",
                    hint = if (paused) {
                        "с позиции в кэше · OK"
                    } else {
                        "timeshift, кэш на диске до ${PlayerPrefs.hint(maxBufferSec)}"
                    },
                    onClick = onTogglePause,
                ),
            )
            if (paused && onGoLive != null) {
                add(
                    SettingsItem(
                        title = "В прямой эфир",
                        hint = "сбросить timeshift и очистить кэш",
                        onClick = onGoLive,
                    ),
                )
            }
        }
        add(
            SettingsItem(
                title = "Размер кэша (пауза)",
                hint = PlayerPrefs.settingsHint(maxBufferSec, freeBytes),
                hintColor = if (freeBytes < PlayerPrefs.estimateMb(maxBufferSec) * 1024L * 1024L) {
                    ErrorRed
                } else {
                    TextDim
                },
                onClick = onBufferCycle,
            ),
        )
        add(
            SettingsItem(
                title = "Обновить каналы",
                hint = if (status.loading) {
                    "идёт обновление: ${status.done} из ${status.total}"
                } else {
                    "заново разобрать ссылки на все потоки"
                },
                onClick = onRefreshChannels,
            ),
        )
        add(
            SettingsItem(
                title = "Обновить телепрограмму",
                hint = when {
                    epgStatus.loading -> "загружается…"
                    epgStatus.error != null -> "ошибка: ${epgStatus.error}"
                    epg.updatedAt == 0L -> "данных пока нет"
                    else -> "сейчас есть у ${epgStatus.matched} из ${epgStatus.total} каналов"
                },
                hintColor = if (epgStatus.error != null) ErrorRed else TextDim,
                onClick = onRefreshEpg,
            ),
        )
        add(
            SettingsItem(
                title = "Отчёт о загрузке",
                hint = if (failures.isEmpty()) {
                    "все каналы загрузились"
                } else {
                    "не загрузились каналов: ${failures.size}"
                },
                hintColor = if (failures.isEmpty()) TextDim else ErrorRed,
                onClick = onShowReport,
            ),
        )
        add(
            SettingsItem(
                title = if (update.available != null) "Обновить приложение" else "Обновление",
                hint = when {
                    update.checking -> "проверяю релизы на GitHub…"
                    update.downloading ->
                        if (update.progress >= 0) "загрузка: ${update.progress}%" else "загрузка…"
                    update.installing -> "устанавливаю — подтвердите на экране системы"
                    update.error != null -> "ошибка: ${update.error}"
                    update.available != null ->
                        "доступна версия ${update.available.version} — OK, установить"
                    update.checkedAt > 0L -> "установлена последняя версия — OK, проверить ещё раз"
                    else -> "проверить обновление на GitHub"
                },
                hintColor = when {
                    update.error != null -> ErrorRed
                    update.available != null -> Amber
                    else -> TextDim
                },
                extra = update.available?.notes?.takeIf { it.isNotBlank() }?.replace(Regex("\\s+"), " "),
                onClick = onUpdate,
            ),
        )
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            first.requestFocus()
        } catch (e: Exception) {
            // панель ещё не скомпонована — фокус останется на дефолтном элементе
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC05070A)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(620.dp)
                .background(Color(0xFF141A21), RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GearIcon(Amber, iconSize = 26.dp)
                Spacer(Modifier.width(12.dp))
                Txt("Настройки", Modifier.weight(1f), size = 28.sp, weight = FontWeight.Bold)
                Txt(
                    "версия ${update.current}",
                    size = 16.sp,
                    color = if (update.available != null) Amber else TextDim,
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEachIndexed { i, item ->
                    FocusItem(
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = if (i == 0) first else null,
                        onClick = item.onClick,
                    ) { focused ->
                        Column(Modifier.fillMaxWidth()) {
                            Txt(item.title, size = 21.sp, color = if (focused) OnAmber else TextMain)
                            Txt(
                                item.hint,
                                size = 16.sp,
                                color = if (focused) OnAmber else item.hintColor,
                                maxLines = 2,
                            )
                            if (item.extra != null) {
                                Txt(
                                    item.extra,
                                    size = 15.sp,
                                    color = if (focused) OnAmber else TextDim,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Txt("↑ ↓ — выбор     OK — выполнить     Назад / Menu — закрыть", size = 15.sp, color = TextDim)
        }
    }
}
