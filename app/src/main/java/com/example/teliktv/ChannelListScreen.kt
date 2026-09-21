package com.example.teliktv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChannelListScreen(
    channels: List<Channel>,
    favorites: Set<String>,
    epg: EpgData,
    epgStatus: EpgStatus,
    status: LoadStatus,
    failures: List<ChannelFailure>,
    update: UpdateState,
    groupIndex: Int,
    onGroupChange: (Int) -> Unit,
    lastPlayed: String?,
    onPlay: (slug: String, group: Int) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: () -> Unit,
    onRefreshEpg: () -> Unit,
    onUpdate: () -> Unit,
) {
    val now = rememberNow()
    val group = groupIndex.coerceIn(0, Groups.names.size - 1)
    val visible = remember(channels, favorites, group) { Groups.channelsFor(group, channels, favorites) }
    var showReport by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    BackHandler(enabled = showReport || showSettings) {
        if (showReport) showReport = false else showSettings = false
    }

    Box(
        Modifier
            .fillMaxSize()
            // Menu на пульте открывает и закрывает настройки из любого места списка.
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || ev.key != Key.Menu) return@onPreviewKeyEvent false
                if (showReport) showReport = false else showSettings = !showSettings
                true
            },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .background(Ink)
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            // ---- левая колонка: группы каналов и вход в меню настроек
            Column(Modifier.width(300.dp).fillMaxHeight()) {
                Txt("Эфир", size = 34.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(Groups.names) { i, name ->
                        FocusItem(
                            modifier = Modifier.fillMaxWidth(),
                            selected = i == group,
                            onFocused = { onGroupChange(i) },
                            onClick = { onGroupChange(i) },
                        ) { focused ->
                            Txt(name, Modifier.weight(1f), color = if (focused) OnAmber else TextMain)
                            Txt(
                                Groups.count(i, channels, favorites).toString(),
                                color = if (focused) OnAmber else TextDim,
                            )
                        }
                    }
                    // Обновление, телепрограмма и отчёт живут в отдельном меню (Menu на пульте).
                    item {
                        Column {
                            Spacer(Modifier.height(16.dp))
                            FocusItem(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { showSettings = true },
                            ) { focused ->
                                GearIcon(if (focused) OnAmber else TextDim)
                                Spacer(Modifier.width(12.dp))
                                Txt(
                                    "Настройки",
                                    Modifier.weight(1f),
                                    color = if (focused) OnAmber else TextDim,
                                )
                                when {
                                    status.loading -> Txt(
                                        "${status.done}/${status.total}",
                                        size = 16.sp,
                                        color = if (focused) OnAmber else TextDim,
                                    )
                                    epgStatus.loading -> Txt(
                                        "ТВ-программа…",
                                        size = 16.sp,
                                        color = if (focused) OnAmber else TextDim,
                                    )
                                    update.available != null -> Txt(
                                        "обновление",
                                        size = 16.sp,
                                        color = if (focused) OnAmber else Amber,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(32.dp))

            // ---- правая колонка: баннер об ошибках + каналы выбранной группы
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (failures.isNotEmpty()) {
                    val names = failures.take(4).joinToString(", ") { it.name }
                    val more = if (failures.size > 4) " и ещё ${failures.size - 4}" else ""
                    FocusItem(modifier = Modifier.fillMaxWidth(), onClick = { showReport = true }) { focused ->
                        Txt(
                            "Не загрузились (${failures.size}): $names$more — OK, подробности",
                            color = if (focused) OnAmber else ErrorRed,
                            size = 17.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (visible.isEmpty()) {
                    Txt(
                        text = when {
                            group == Groups.FAVORITES ->
                                "В избранном пусто. В любой группе перейдите со строки канала вправо на звёздочку и нажмите OK."
                            status.loading -> "Загружаю каналы…"
                            else -> "Каналов нет. Нажмите Menu и выберите «Обновить каналы»."
                        },
                        color = TextDim,
                        size = 22.sp,
                        maxLines = 3,
                    )
                } else {
                    ChannelColumn(
                        visible = visible,
                        favorites = favorites,
                        epg = epg,
                        now = now,
                        group = group,
                        lastPlayed = lastPlayed,
                        onPlay = onPlay,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
            }
        }

        if (showSettings && !showReport) {
            SettingsPanel(
                status = status,
                epg = epg,
                epgStatus = epgStatus,
                failures = failures,
                update = update,
                onRefreshChannels = {
                    showSettings = false
                    onRefresh()
                },
                onRefreshEpg = {
                    showSettings = false
                    onRefreshEpg()
                },
                onShowReport = { showReport = true },
                // Панель не закрываем: прогресс загрузки обновления виден прямо в пункте меню.
                onUpdate = onUpdate,
            )
        }

        if (showReport) {
            val missing = if (epg.updatedAt > 0) {
                channels.filter { epg.bySlug[it.slug].isNullOrEmpty() }.map { it.title }
            } else {
                emptyList()
            }
            ReportOverlay(failures, epgStatus, missing) { showReport = false }
        }
    }
}

@Composable
private fun ChannelColumn(
    visible: List<Channel>,
    favorites: Set<String>,
    epg: EpgData,
    now: Long,
    group: Int,
    lastPlayed: String?,
    onPlay: (slug: String, group: Int) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val requesters = remember { HashMap<String, FocusRequester>() }

    // Вернулись из плеера — ставим фокус на последний открытый канал.
    LaunchedEffect(Unit) {
        val slug = lastPlayed ?: return@LaunchedEffect
        val i = visible.indexOfFirst { it.slug == slug }
        if (i >= 0) {
            listState.scrollToItem(i)
            withFrameNanos { }
            try {
                requesters[slug]?.requestFocus()
            } catch (e: Exception) {
                // элемент ещё не скомпонован — остаёмся на дефолтном фокусе
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(visible, key = { _, c -> c.slug }) { idx, ch ->
            ChannelRow(
                number = idx + 1,
                channel = ch,
                logoUrl = ch.logo ?: epg.icons[ch.slug],
                nowTitle = Epg.current(epg.bySlug[ch.slug], now)?.title,
                favorite = ch.slug in favorites,
                modifier = Modifier.fillMaxWidth(),
                rowFocusRequester = requesters.getOrPut(ch.slug) { FocusRequester() },
                onClick = { onPlay(ch.slug, group) },
                onToggleFavorite = { onToggleFavorite(ch.slug) },
            )
        }
    }
}
