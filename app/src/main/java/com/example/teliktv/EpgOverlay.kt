package com.example.teliktv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Оверлей телепрограммы текущего канала (кнопка «вправо»).
 * Список передач на сегодня/завтра; фокус ставится на текущую передачу.
 * OK по строке — развернуть описание; Назад / ← — закрыть.
 */
@Composable
fun EpgOverlay(
    channel: Channel?,
    slug: String,
    logo: String?,
    programmes: List<Programme>?,
    now: Long,
    onActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = remember(programmes, now) {
        programmes?.filter { it.stop > now - 2 * 60 * 60 * 1000L }.orEmpty()   // немного прошедшего для контекста
    }
    val currentIndex = remember(list, now) {
        list.indexOfFirst { it.start <= now && now < it.stop }.let { if (it < 0) 0 else it }
    }
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(slug, list.size) {
        if (list.isNotEmpty()) {
            state.scrollToItem(currentIndex)
            withFrameNanos { }
            try {
                focus.requestFocus()
            } catch (e: Exception) {
                // строка ещё не смонтирована — фокус останется на корне
            }
        }
    }

    Column(
        modifier
            .fillMaxHeight()
            .width(680.dp)
            .background(Color(0xF2101419))
            .padding(horizontal = 22.dp, vertical = 20.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) onActivity()
                false
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(logo, channel?.title ?: slug)
            Spacer(Modifier.width(14.dp))
            Column {
                Txt(channel?.title ?: slug, size = 26.sp, weight = FontWeight.Bold)
                Txt("Телепрограмма", size = 16.sp, color = Amber)
            }
        }
        Spacer(Modifier.height(14.dp))

        if (list.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 30.dp)) {
                Txt("Для этого канала нет телепрограммы", size = 20.sp, color = TextDim)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth(), state = state) {
                itemsIndexed(list) { i, p ->
                    val isNow = p.start <= now && now < p.stop
                    val past = p.stop <= now
                    val newDay = i == 0 || Epg.day(list[i - 1].start) != Epg.day(p.start)
                    if (newDay) {
                        Spacer(Modifier.height(if (i == 0) 0.dp else 10.dp))
                        Txt(
                            Epg.dayLabel(p.start, now),
                            Modifier.padding(start = 16.dp, bottom = 4.dp),
                            size = 15.sp,
                            color = Amber,
                            weight = FontWeight.Bold,
                        )
                    }
                    FocusItem(
                        modifier = Modifier.fillMaxWidth(),
                        selected = isNow,
                        alignTop = true,
                        focusRequester = if (i == currentIndex) focus else null,
                        onClick = { },
                    ) { focused ->
                        Txt(
                            Epg.time(p.start),
                            Modifier.width(74.dp),
                            size = 18.sp,
                            color = if (focused) OnAmber else if (isNow) Amber else TextDim,
                            weight = if (isNow) FontWeight.Bold else null,
                        )
                        Column(Modifier.weight(1f)) {
                            Txt(
                                p.title,
                                size = 19.sp,
                                color = when {
                                    focused -> OnAmber
                                    past -> TextDim
                                    else -> TextMain
                                },
                                weight = if (isNow) FontWeight.Bold else null,
                            )
                            if (isNow) {
                                Spacer(Modifier.height(5.dp))
                                ProgressBar(Epg.progress(p, now), Modifier.fillMaxWidth())
                            }
                            // Описание — целиком: строка растёт по высоте, текст не обрезается.
                            if (focused && p.desc != null) {
                                Spacer(Modifier.height(4.dp))
                                Txt(p.desc, size = 15.sp, color = OnAmber, maxLines = Int.MAX_VALUE)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Txt("↑ ↓ — листать     Назад / ← — закрыть", size = 15.sp, color = TextDim)
    }
}
