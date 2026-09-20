package com.example.teliktv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChannelListScreen(
    channels: List<Channel>,
    favorites: Set<String>,
    status: LoadStatus,
    groupIndex: Int,
    onGroupChange: (Int) -> Unit,
    lastPlayed: String?,
    onPlay: (slug: String, playlist: List<String>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val bySlug = remember(channels) { channels.associateBy { it.slug } }
    val groupNames = remember {
        listOf("Все каналы", "★ Избранное") + Config.GROUPS.map { it.first }
    }
    val group = groupIndex.coerceIn(0, groupNames.size - 1)

    val visible = remember(channels, favorites, group) {
        when (group) {
            0 -> channels
            1 -> Config.ALL_SLUGS.filter { it in favorites && it in bySlug }.mapNotNull { bySlug[it] }
            else -> Config.GROUPS[group - 2].second.mapNotNull { bySlug[it] }
        }
    }

    val now = remember { SimpleDateFormat("HH:mm", Locale.US).format(Date()) }

    Row(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        // ---- левая колонка: группы + статус + неудачные
        Column(Modifier.width(320.dp).fillMaxHeight()) {
            Txt("Эфир", size = 34.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(groupNames) { i, name ->
                    val count = when (i) {
                        0 -> channels.size
                        1 -> favorites.count { it in bySlug }
                        else -> Config.GROUPS[i - 2].second.count { it in bySlug }
                    }
                    FocusItem(
                        modifier = Modifier.fillMaxWidth(),
                        selected = i == group,
                        onFocused = { onGroupChange(i) },
                        onClick = { onGroupChange(i) },
                    ) { focused ->
                        Txt(name, Modifier.weight(1f), color = if (focused) OnAmber else TextMain)
                        Txt(count.toString(), color = if (focused) OnAmber else TextDim)
                    }
                }
                item {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        FocusItem(modifier = Modifier.fillMaxWidth(), onClick = onRefresh) { focused ->
                            val label = if (status.loading) "Обновляю ${status.done} из ${status.total}" else "Обновить каналы"
                            Txt(label, color = if (focused) OnAmber else TextDim)
                        }
                    }
                }
                if (status.failed.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(top = 16.dp)) {
                            Txt("Не спарсились (${status.failed.size}):", color = ErrorRed, size = 15.sp)
                            Spacer(Modifier.height(4.dp))
                            status.failed.forEach { slug ->
                                Txt(slug, color = ErrorRed, size = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(32.dp))

        // ---- правая колонка: каналы
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (visible.isEmpty()) {
                val msg = when {
                    group == 1 && !status.loading -> "В избранном пусто. Нажмите MENU на канале, чтобы добавить."
                    status.loading -> "Загружаю каналы…"
                    else -> "Каналов нет. Выберите «Обновить каналы»."
                }
                Txt(text = msg, color = TextDim, size = 22.sp)
            } else {
                val listState = rememberLazyListState()
                val requesters = remember { HashMap<String, FocusRequester>() }

                LaunchedEffect(Unit) {
                    val slug = lastPlayed ?: return@LaunchedEffect
                    val i = visible.indexOfFirst { it.slug == slug }
                    if (i >= 0) {
                        listState.scrollToItem(i)
                        withFrameNanos { }
                        try {
                            requesters[slug]?.requestFocus()
                        } catch (_: Exception) {
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(visible, key = { _, c -> c.slug }) { idx, ch ->
                        val fr = requesters.getOrPut(ch.slug) { FocusRequester() }
                        FocusItem(
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = fr,
                            onClick = { onPlay(ch.slug, visible.map { it.slug }) },
                            onKeyEvent = { ev ->
                                if (ev.type == KeyEventType.KeyDown &&
                                    (ev.key == Key.Menu || ev.key == Key.MediaPlay || ev.key == Key.MediaPlayPause)
                                ) {
                                    onToggleFavorite(ch.slug)
                                    true
                                } else false
                            },
                        ) { focused ->
                            Txt("${idx + 1}", Modifier.width(48.dp), color = if (focused) OnAmber else TextDim)

                            if (ch.logo != null) {
                                AsyncImage(
                                    model = ch.logo,
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Fit,
                                )
                                Spacer(Modifier.width(12.dp))
                            } else {
                                Spacer(Modifier.width(46.dp))
                            }

                            if (ch.slug in favorites) {
                                Txt("★", color = if (focused) OnAmber else Amber, size = 18.sp)
                                Spacer(Modifier.width(8.dp))
                            }

                            Column(Modifier.weight(1f)) {
                                Txt(ch.title, size = 22.sp, color = if (focused) OnAmber else TextMain)
                                val prog = ch.programs.currentAt(now)
                                if (prog != null) {
                                    Txt(prog.title, size = 14.sp, color = if (focused) OnAmber else TextDim)
                                }
                            }

                            if (ch.streams.size > 1) {
                                Txt(streamsLabel(ch.streams.size), color = if (focused) OnAmber else TextDim, size = 17.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun streamsLabel(n: Int): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "$n поток"
        mod10 in 2..4 && mod100 !in 12..14 -> "$n потока"
        else -> "$n потоков"
    }
}