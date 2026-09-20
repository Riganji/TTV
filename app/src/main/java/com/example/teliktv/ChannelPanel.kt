package com.example.teliktv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Оверлей слева поверх видео.
 *  showGroups = false: только каналы выбранной группы; ещё раз «влево» -> onShowGroups().
 *  showGroups = true: слева колонка категорий (фокус там), справа каналы выбранной категории.
 * Выбор канала -> onPick. Звезда справа от строки — избранное.
 */
@Composable
fun ChannelPanel(
    showGroups: Boolean,
    panelGroup: Int,
    onGroupChange: (Int) -> Unit,
    allChannels: List<Channel>,
    favorites: Set<String>,
    epg: EpgData,
    now: Long,
    currentSlug: String,
    onPick: (Channel) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onShowGroups: () -> Unit,
    onActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember(allChannels, favorites, panelGroup) {
        Groups.channelsFor(panelGroup, allChannels, favorites)
    }
    var starFocused by remember { mutableStateOf(false) }
    val groupRequesters = remember { List(Groups.names.size) { FocusRequester() } }
    val rowRequesters = remember { HashMap<String, FocusRequester>() }
    val listState = rememberLazyListState()

    // Открыли панель — фокус на текущий канал; показали категории — фокус на выбранную категорию.
    LaunchedEffect(showGroups) {
        starFocused = false
        withFrameNanos { }
        try {
            if (showGroups) {
                groupRequesters[panelGroup.coerceIn(0, groupRequesters.size - 1)].requestFocus()
            } else {
                val i = visible.indexOfFirst { it.slug == currentSlug }.coerceAtLeast(0)
                listState.scrollToItem(i)
                withFrameNanos { }
                visible.getOrNull(i)?.let { rowRequesters[it.slug]?.requestFocus() }
            }
        } catch (e: Exception) {
            // элемент ещё не присоединён — остаёмся на дефолтном фокусе
        }
    }

    LaunchedEffect(panelGroup) {
        if (showGroups) listState.scrollToItem(0)
    }

    Row(
        modifier
            .fillMaxHeight()
            .background(Color(0xF00D1014))
            .onPreviewKeyEvent { ev ->
                val down = ev.type == KeyEventType.KeyDown
                if (down) onActivity()
                // Влево со строки канала (не со звезды) -> категории.
                if (down && ev.key == Key.DirectionLeft && !showGroups && !starFocused) {
                    onShowGroups()
                    true
                } else {
                    false
                }
            },
    ) {
        if (showGroups) {
            Column(Modifier.width(250.dp).fillMaxHeight().padding(start = 24.dp, top = 28.dp, end = 8.dp)) {
                Txt("Категории", size = 24.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(Groups.names) { i, name ->
                        FocusItem(
                            modifier = Modifier.fillMaxWidth(),
                            selected = i == panelGroup,
                            focusRequester = groupRequesters[i],
                            onFocused = { onGroupChange(i) },
                            onClick = { onGroupChange(i) },
                        ) { focused ->
                            Txt(name, Modifier.weight(1f), size = 19.sp, color = if (focused) OnAmber else TextMain)
                            Txt(
                                Groups.count(i, allChannels, favorites).toString(),
                                size = 16.sp,
                                color = if (focused) OnAmber else TextDim,
                            )
                        }
                    }
                }
            }
        }

        Column(Modifier.width(470.dp).fillMaxHeight().padding(start = 24.dp, top = 28.dp, end = 16.dp)) {
            Txt(Groups.names[panelGroup.coerceIn(0, Groups.names.size - 1)], size = 24.sp, weight = FontWeight.Bold)
            Txt(
                if (showGroups) "→ к каналам" else "← категории",
                size = 15.sp,
                color = TextDim,
            )
            Spacer(Modifier.height(10.dp))
            if (visible.isEmpty()) {
                Txt(
                    if (panelGroup == Groups.FAVORITES) {
                        "Избранное пусто. Перейдите со строки канала вправо на звёздочку и нажмите OK."
                    } else {
                        "В этой группе нет каналов"
                    },
                    color = TextDim,
                    size = 18.sp,
                    maxLines = 4,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    itemsIndexed(visible, key = { _, c -> c.slug }) { idx, ch ->
                        ChannelRow(
                            number = idx + 1,
                            channel = ch,
                            logoUrl = ch.logo ?: epg.icons[ch.slug],
                            nowTitle = Epg.current(epg.bySlug[ch.slug], now)?.title,
                            favorite = ch.slug in favorites,
                            compact = true,
                            isCurrent = ch.slug == currentSlug,
                            rowFocusRequester = rowRequesters.getOrPut(ch.slug) { FocusRequester() },
                            onStarFocusChange = { starFocused = it },
                            onClick = { onPick(ch) },
                            onToggleFavorite = { onToggleFavorite(ch.slug) },
                        )
                    }
                }
            }
        }
    }
}
