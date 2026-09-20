package com.example.teliktv

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class OverlayMode { None, Channels, Groups, Streams }

/**
 * Пульт:
 *  Без оверлея:  ↑ ↓ — канал, ← — список каналов, → — потоки, OK/Menu — потоки
 *  Каналы:       ↑ ↓ — навигация, OK — открыть, ← — категории, → — потоки
 *  Категории:    ↑ ↓ — навигация, OK — выбрать и вернуться к каналам, → — каналы
 *  Потоки:       ↑ ↓ / ← → — навигация, OK — выбрать, Back — закрыть
 *  Back:         закрыть оверлей / выйти из плеера
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    slugs: List<String>,
    startSlug: String,
    channels: Map<String, Channel>,
    favorites: Set<String>,
    reloadChannel: suspend (String) -> Unit,
    onCurrentChannel: (String) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var playlist by remember { mutableStateOf(slugs) }
    var index by remember { mutableIntStateOf(playlist.indexOf(startSlug).coerceAtLeast(0)) }
    val slug = playlist.getOrNull(index) ?: startSlug
    val channel = channels[slug]
    val streams = channel?.streams.orEmpty()

    val chosen = remember { mutableStateMapOf<String, Int>() }
    val streamIdx = (chosen[slug] ?: 0).coerceIn(0, (streams.size - 1).coerceAtLeast(0))
    val stream = streams.getOrNull(streamIdx)

    var overlay by remember { mutableStateOf(OverlayMode.None) }
    var info by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloading by remember { mutableStateOf(false) }
    var nonce by remember { mutableIntStateOf(0) }
    var retried by remember { mutableStateOf(emptySet<String>()) }

    val currentSlug by rememberUpdatedState(slug)
    val channelsState by rememberUpdatedState(channels)

    var channelCursor by remember { mutableIntStateOf(index) }
    var groupCursor by remember { mutableIntStateOf(0) }
    var streamCursor by remember { mutableIntStateOf(streamIdx) }

    val groupLists: List<Pair<String, List<String>>> = remember(channels, favorites) {
        buildList {
            add("Все каналы" to Config.ALL_SLUGS.filter { it in channels })
            add("★ Избранное" to favorites.filter { it in channels })
            Config.GROUPS.forEach { (name, sl) -> add(name to sl.filter { it in channels }) }
        }
    }

    LaunchedEffect(overlay) {
        when (overlay) {
            OverlayMode.Channels -> channelCursor = index.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
            OverlayMode.Streams -> streamCursor = streamIdx
            OverlayMode.Groups -> {
                val i = groupLists.indexOfFirst { it.second.contains(slug) }
                if (i >= 0) groupCursor = i
            }
            OverlayMode.None -> {}
        }
    }

    fun zap(delta: Int) {
        if (playlist.isEmpty()) return
        index = (index + delta).mod(playlist.size)
        overlay = OverlayMode.None
    }

    fun switchPlaylist(newList: List<String>, startAt: String? = null) {
        if (newList.isEmpty()) return
        val target = startAt?.takeIf { it in newList } ?: newList.first()
        playlist = newList
        index = newList.indexOf(target)
    }

    fun reload(s: String) {
        scope.launch {
            reloading = true
            try { reloadChannel(s) } finally { reloading = false }
            nonce++
        }
    }

    // ---- ExoPlayer
    val exo = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { exo.release() } }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    error = null
                    retried = retried - currentSlug
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                val s = currentSlug
                val ch = channelsState[s]
                if (ch != null && ch.streams.size > 1) {
                    val cur = (chosen[s] ?: 0).coerceIn(0, ch.streams.size - 1)
                    if (cur + 1 < ch.streams.size) {
                        chosen[s] = cur + 1
                        error = "Поток не открылся. Пробую следующий (${cur + 2} из ${ch.streams.size})…"
                        return
                    }
                }
                if (s !in retried) {
                    retried = retried + s
                    error = "Обновляю ссылки канала…"
                    reload(s)
                    return
                }
                error = "Поток не открылся (${e.errorCodeName})"
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    LaunchedEffect(slug, stream?.url, nonce) {
        error = null
        if (stream != null) {
            exo.setMediaSource(buildSource(context, stream))
            exo.prepare()
            exo.playWhenReady = true
        } else {
            exo.stop()
        }
    }

    LaunchedEffect(slug) { onCurrentChannel(slug) }
    LaunchedEffect(slug, streamIdx) {
        info = true
        delay(3500)
        info = false
    }

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(overlay) {
        if (overlay == OverlayMode.None) {
            try { rootFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    BackHandler {
        if (overlay != OverlayMode.None) overlay = OverlayMode.None else onExit()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (overlay) {
                    OverlayMode.None -> when (ev.key) {
                        Key.DirectionUp, Key.ChannelUp, Key.PageUp -> { zap(-1); true }
                        Key.DirectionDown, Key.ChannelDown, Key.PageDown -> { zap(1); true }
                        Key.DirectionLeft -> { overlay = OverlayMode.Channels; true }
                        Key.DirectionRight -> { overlay = OverlayMode.Streams; true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> {
                            overlay = OverlayMode.Streams; true
                        }
                        else -> false
                    }

                    OverlayMode.Channels -> when (ev.key) {
                        Key.DirectionUp -> { channelCursor = (channelCursor - 1).coerceAtLeast(0); true }
                        Key.DirectionDown -> {
                            channelCursor = (channelCursor + 1).coerceAtMost((playlist.size - 1).coerceAtLeast(0)); true
                        }
                        Key.DirectionLeft -> { overlay = OverlayMode.Groups; true }
                        Key.DirectionRight -> { overlay = OverlayMode.Streams; true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (channelCursor in playlist.indices) {
                                index = channelCursor
                                overlay = OverlayMode.None
                            }
                            true
                        }
                        else -> false
                    }

                    OverlayMode.Groups -> when (ev.key) {
                        Key.DirectionUp -> { groupCursor = (groupCursor - 1).coerceAtLeast(0); true }
                        Key.DirectionDown -> {
                            groupCursor = (groupCursor + 1).coerceAtMost((groupLists.size - 1).coerceAtLeast(0)); true
                        }
                        Key.DirectionRight -> { overlay = OverlayMode.Channels; true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val list = groupLists.getOrNull(groupCursor)?.second.orEmpty()
                            if (list.isNotEmpty()) {
                                switchPlaylist(list)
                                channelCursor = 0
                                overlay = OverlayMode.Channels
                            }
                            true
                        }
                        else -> false
                    }

                    OverlayMode.Streams -> when (ev.key) {
                        Key.DirectionUp, Key.DirectionLeft -> {
                            streamCursor = (streamCursor - 1).coerceAtLeast(0); true
                        }
                        Key.DirectionDown, Key.DirectionRight -> {
                            streamCursor = (streamCursor + 1).coerceAtMost(streams.size); true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (streamCursor < streams.size) {
                                chosen[slug] = streamCursor
                                overlay = OverlayMode.None
                            } else {
                                overlay = OverlayMode.None
                                reload(slug)
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    player = exo
                }
            },
        )

        // ---- плашка «сейчас смотрим»
        val nowTime = remember(slug) {
            SimpleDateFormat("HH:mm", Locale.US).format(Date())
        }
        val program = remember(channel, nowTime) { channel?.programs?.currentAt(nowTime) }
        if (info && overlay == OverlayMode.None && channel != null) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(40.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Txt("${index + 1}. ${channel.title}", size = 26.sp, weight = FontWeight.Bold)
                if (program != null) {
                    Spacer(Modifier.height(4.dp))
                    Txt("${program.time}  ${program.title}", size = 18.sp, color = Amber)
                }
                if (streams.size > 1 && stream != null) {
                    Spacer(Modifier.height(2.dp))
                    Txt("${stream.label}  (${streamIdx + 1} из ${streams.size})", size = 16.sp, color = TextDim)
                }
            }
        }

        // ---- ошибка / перезагрузка
        val err = error
        if (overlay == OverlayMode.None && (err != null || reloading)) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (reloading) {
                    Txt("Обновляю ссылки канала…", size = 22.sp)
                } else if (err != null) {
                    Txt(err, size = 22.sp, color = ErrorRed)
                    Spacer(Modifier.height(6.dp))
                    Txt("→ — выбрать другой поток", size = 18.sp, color = TextDim)
                }
            }
        }

        when (overlay) {
            OverlayMode.Channels -> ChannelsOverlay(
                playlist = playlist,
                channels = channels,
                favorites = favorites,
                cursor = channelCursor,
                onCursor = { channelCursor = it },
                onPick = { i ->
                    index = i
                    overlay = OverlayMode.None
                },
            )

            OverlayMode.Groups -> GroupsOverlay(
                groups = groupLists,
                cursor = groupCursor,
                onCursor = { groupCursor = it },
                onPick = { i ->
                    val list = groupLists.getOrNull(i)?.second.orEmpty()
                    if (list.isNotEmpty()) {
                        switchPlaylist(list)
                        channelCursor = 0
                        overlay = OverlayMode.Channels
                    }
                },
            )

            OverlayMode.Streams -> StreamsOverlay(
                title = channel?.title ?: slug,
                streams = streams,
                cursor = streamCursor,
                currentIdx = streamIdx,
                onCursor = { streamCursor = it },
                onPick = { i ->
                    if (i < streams.size) chosen[slug] = i else reload(slug)
                    overlay = OverlayMode.None
                },
            )

            OverlayMode.None -> {}
        }
    }
}

// ------------------------------------------------------------------ оверлеи

@Composable
private fun ChannelsOverlay(
    playlist: List<String>,
    channels: Map<String, Channel>,
    favorites: Set<String>,
    cursor: Int,
    onCursor: (Int) -> Unit,
    onPick: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(cursor) { listState.animateScrollToItem(cursor) }

    Column(
        Modifier
            .fillMaxHeight()
            .width(480.dp)
            .background(Color(0xD9000000))
            .padding(24.dp),
    ) {
        Txt("Каналы", size = 26.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(playlist) { i, s ->
                val ch = channels[s]
                val title = ch?.title ?: s
                FocusItem(
                    modifier = Modifier.fillMaxWidth(),
                    selected = i == cursor,
                    onFocused = { onCursor(i) },
                    onClick = { onPick(i) },
                ) { focused ->
                    if (ch?.logo != null) {
                        AsyncImage(
                            model = ch.logo,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    if (s in favorites) {
                        Txt("★", color = if (focused) OnAmber else Amber, size = 16.sp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Txt(title, Modifier.weight(1f), size = 20.sp, color = if (focused) OnAmber else TextMain)
                }
            }
        }
    }
}

@Composable
private fun GroupsOverlay(
    groups: List<Pair<String, List<String>>>,
    cursor: Int,
    onCursor: (Int) -> Unit,
    onPick: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(480.dp)
            .background(Color(0xD9000000))
            .padding(24.dp),
    ) {
        Txt("Категории", size = 26.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(groups) { i, (name, list) ->
                FocusItem(
                    modifier = Modifier.fillMaxWidth(),
                    selected = i == cursor,
                    onFocused = { onCursor(i) },
                    onClick = { onPick(i) },
                ) { focused ->
                    Txt(name, Modifier.weight(1f), color = if (focused) OnAmber else TextMain)
                    Txt(list.size.toString(), color = if (focused) OnAmber else TextDim)
                }
            }
        }
    }
}

@Composable
private fun StreamsOverlay(
    title: String,
    streams: List<StreamItem>,
    cursor: Int,
    currentIdx: Int,
    onCursor: (Int) -> Unit,
    onPick: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000))))
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Txt(title, size = 28.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Txt(if (streams.size > 1) "Выберите поток" else "У канала один поток", size = 18.sp, color = TextDim)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            streams.forEachIndexed { i, s ->
                FocusItem(
                    selected = i == currentIdx,
                    onFocused = { onCursor(i) },
                    onClick = { onPick(i) },
                ) { focused ->
                    val mark = if (i == currentIdx) "• " else ""
                    Txt(mark + s.label, color = if (focused) OnAmber else TextMain)
                }
            }
            FocusItem(
                onFocused = { onCursor(streams.size) },
                onClick = { onPick(streams.size) },
            ) { focused ->
                Txt("↻ Обновить ссылки", color = if (focused) OnAmber else TextDim)
            }
        }
        Spacer(Modifier.height(14.dp))
        Txt("↑ ↓ / ← → — поток     OK — выбрать     Назад — закрыть", size = 15.sp, color = TextDim)
    }
}

/** Ссылки отдаются с проверкой Referer — как #EXTVLCOPT в playlist.m3u из parser.py. */
@OptIn(UnstableApi::class)
private fun buildSource(context: Context, s: StreamItem): MediaSource {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(Config.UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
        .setDefaultRequestProperties(mapOf("Referer" to s.referer))
    val mime = if (Extract.isDash(s.url)) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
    val item = MediaItem.Builder().setUri(s.url).setMimeType(mime).build()
    return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item)
}