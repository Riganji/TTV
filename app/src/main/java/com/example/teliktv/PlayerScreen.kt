package com.example.teliktv

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PlayerPanel { None, Channels, Groups, Streams, Epg, Settings }

/** Сколько раз подряд пробуем вернуться на «живую» позицию после BEHIND_LIVE_WINDOW, прежде чем считать поток нерабочим. */
private const val MAX_LIVE_RETRIES = 3

/**
 * Управление пультом (панели закрыты):
 *  ↑ / ↓ (и CH+/CH−)  — предыдущий / следующий канал в текущей группе
 *  ←                  — список каналов (ещё раз ← — категории, включая «Избранное»)
 *  →                  — телепрограмма текущего канала
 *  Menu               — выбор потока
 *  OK                 — карточка «сейчас / далее» из телепрограммы
 *  Назад              — закрыть панель / выйти в список
 * Если поток не открылся или не стартует за Config.STREAM_TIMEOUT_MS — автоматически включается
 * следующий поток канала; когда исчерпаны все — один раз перепарсивается канал и всё повторяется.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channels: List<Channel>,
    favorites: Set<String>,
    epg: EpgData,
    epgStatus: EpgStatus,
    status: LoadStatus,
    failures: List<ChannelFailure>,
    update: UpdateState,
    startGroup: Int,
    startSlug: String,
    reloadChannel: suspend (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: () -> Unit,
    onRefreshEpg: () -> Unit,
    onUpdate: () -> Unit,
    onCurrent: (slug: String, group: Int) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = rememberNow()

    val bySlug = remember(channels) { channels.associateBy { it.slug } }
    val bySlugState by rememberUpdatedState(bySlug)

    var playGroup by remember { mutableIntStateOf(startGroup) }
    var slug by remember { mutableStateOf(startSlug) }
    val playlist = remember(playGroup, channels, favorites) {
        Groups.channelsFor(playGroup, channels, favorites).map { it.slug }
    }
    val currentSlug by rememberUpdatedState(slug)
    val position = playlist.indexOf(slug)
    val num = if (position >= 0) "${position + 1}. " else ""
    val channel = bySlug[slug]
    val streams = channel?.streams.orEmpty()

    // Выбранный поток и «не открывшиеся» потоки помним отдельно для каждого канала.
    val chosen = remember { mutableStateMapOf<String, Int>() }
    val failedStreams = remember { mutableStateMapOf<String, Set<Int>>() }
    val streamIdx = (chosen[slug] ?: 0).coerceIn(0, (streams.size - 1).coerceAtLeast(0))
    val stream = streams.getOrNull(streamIdx)
    val logo = channel?.logo ?: epg.icons[slug]
    val programmes = epg.bySlug[slug]
    val nowProg = Epg.current(programmes, now)

    var panel by remember { mutableStateOf(PlayerPanel.None) }
    var showReport by remember { mutableStateOf(false) }
    var panelGroup by remember { mutableIntStateOf(startGroup) }
    var touch by remember { mutableIntStateOf(0) }
    var brief by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloading by remember { mutableStateOf(false) }
    var nonce by remember { mutableIntStateOf(0) }
    var inBackground by remember { mutableStateOf(false) }   // Home / экран выключен
    var retried by remember { mutableStateOf(emptySet<String>()) }
    var liveRetries by remember { mutableIntStateOf(0) }   // подряд BEHIND_LIVE_WINDOW без выхода в READY

    // ---- действия

    fun zap(delta: Int) {
        if (playlist.isEmpty()) return
        val i = playlist.indexOf(slug)
        val n = if (i < 0) (if (delta > 0) 0 else playlist.size - 1) else (i + delta).mod(playlist.size)
        slug = playlist[n]
        detail = false
    }

    /** Перепарсить канал; resetStream — вернуться на первый поток (когда перебрали все). */
    fun reload(s: String, resetStream: Boolean) {
        scope.launch {
            reloading = true
            try {
                reloadChannel(s)
            } finally {
                reloading = false
            }
            if (resetStream) {
                chosen[s] = 0
                failedStreams.remove(s)
            }
            nonce++
        }
    }

    /** Поток не открылся / завис: следующий поток канала -> перепарсинг канала -> ошибка. */
    fun onStreamFailed(reason: String) {
        val s = currentSlug
        val list = bySlugState[s]?.streams.orEmpty()
        val cur = (chosen[s] ?: 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        val failed = (failedStreams[s] ?: emptySet()) + cur
        failedStreams[s] = failed
        val next = (1..list.size).map { (cur + it) % list.size }.firstOrNull { it !in failed }
        when {
            next != null -> {
                chosen[s] = next
                notice = "«${list[cur].label}» не открылся ($reason) — включаю «${list[next].label}»"
            }
            s !in retried -> {
                retried = retried + s
                notice = "Ни один поток не открылся — обновляю ссылки канала…"
                reload(s, resetStream = true)
            }
            else -> error = "Ни один поток канала не открылся ($reason)"
        }
    }

    // ---- плеер
    val exo = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { exo.release() } }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    error = null
                    failedStreams.remove(currentSlug)
                    retried = retried - currentSlug
                    liveRetries = 0
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                // Отстали от окна прямого эфира (пауза, буферизация, просадка сети): поток жив,
                // достаточно вернуться на «живую» позицию — переключать поток не нужно.
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && liveRetries < MAX_LIVE_RETRIES) {
                    liveRetries++
                    exo.seekToDefaultPosition()
                    exo.prepare()
                    return
                }
                onStreamFailed(e.errorCodeName)
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    // Нажали «Домой» / погас экран — останавливаем воспроизведение и сеть; вернулись — заново на «прямой эфир».
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner, exo) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                inBackground = true
                exo.stop()
            } else if (event == Lifecycle.Event.ON_START && inBackground) {
                inBackground = false
                nonce++   // перезапускает LaunchedEffect ниже: новый источник, prepare, play
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(slug, stream?.url, nonce) {
        error = null
        if (inBackground) return@LaunchedEffect
        if (stream == null) {
            exo.stop()
            return@LaunchedEffect
        }
        exo.setMediaSource(buildSource(context, stream))
        exo.prepare()
        exo.playWhenReady = true
        // «Висящий» поток без ошибки тоже считаем нерабочим.
        delay(Config.STREAM_TIMEOUT_MS)
        if (!inBackground && exo.playbackState != Player.STATE_READY) {
            onStreamFailed("нет ответа за ${Config.STREAM_TIMEOUT_MS / 1000} с")
        }
    }

    LaunchedEffect(slug, playGroup) { onCurrent(slug, playGroup) }
    LaunchedEffect(slug, streamIdx) {
        brief = true
        delay(3500)
        brief = false
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5000)
            notice = null
        }
    }
    LaunchedEffect(detail) {
        if (detail) {
            delay(Config.OVERLAY_TIMEOUT_MS)
            detail = false
        }
    }
    // Любой оверлей закрывается сам через Config.OVERLAY_TIMEOUT_MS без нажатий (touch сбрасывает отсчёт).
    LaunchedEffect(panel) {
        if (panel != PlayerPanel.Settings) showReport = false
    }
    LaunchedEffect(panel, touch) {
        if (panel == PlayerPanel.None) return@LaunchedEffect
        delay(Config.OVERLAY_TIMEOUT_MS)
        panel = PlayerPanel.None
    }

    // ---- фокус и клавиши
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(panel) {
        if (panel == PlayerPanel.None) {
            try {
                rootFocus.requestFocus()
            } catch (e: Exception) {
                // корневой Box ещё не присоединён
            }
        }
    }
    BackHandler {
        when {
            showReport -> showReport = false
            panel == PlayerPanel.Settings -> panel = PlayerPanel.Groups
            panel == PlayerPanel.Groups -> panel = PlayerPanel.Channels
            panel != PlayerPanel.None -> panel = PlayerPanel.None
            detail -> detail = false
            else -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (panel != PlayerPanel.None || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp -> { zap(-1); true }
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown -> { zap(1); true }
                    Key.DirectionLeft -> {
                        panelGroup = playGroup
                        panel = PlayerPanel.Channels
                        touch++
                        true
                    }
                    Key.DirectionRight -> { panel = PlayerPanel.Epg; touch++; true }
                    Key.Menu -> { panel = PlayerPanel.Streams; touch++; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { detail = !detail; true }
                    else -> false
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

        // Короткая плашка после переключения
        if (brief && panel == PlayerPanel.None && !detail && channel != null) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(40.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Txt("$num${channel.title}", size = 26.sp, weight = FontWeight.Bold)
                if (streams.size > 1 && stream != null) {
                    Txt("${stream.label}  (${streamIdx + 1} из ${streams.size})", size = 18.sp, color = TextDim)
                }
                if (nowProg != null) {
                    Txt("${Epg.time(nowProg.start)}–${Epg.time(nowProg.stop)}  ${nowProg.title}", size = 18.sp, color = TextDim)
                }
            }
        }

        // Уведомление об автопереключении потока
        val msg = notice
        if (msg != null) {
            Txt(
                msg,
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                size = 19.sp,
                color = Amber,
                maxLines = 2,
            )
        }

        // Ошибка / перепарсинг
        val err = error
        if (panel == PlayerPanel.None && (err != null || reloading)) {
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
                    Txt("Menu — выбрать поток вручную, ↑ ↓ другой канал", size = 18.sp, color = TextDim)
                }
            }
        }

        // Карточка «сейчас / далее» (OK)
        if (detail && panel == PlayerPanel.None && channel != null) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000))))
                    .padding(horizontal = 48.dp, vertical = 32.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(logo, channel.title)
                    Spacer(Modifier.width(16.dp))
                    Txt("$num${channel.title}", size = 30.sp, weight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                if (nowProg == null) {
                    Txt(
                        if (programmes.isNullOrEmpty()) "Для этого канала нет телепрограммы" else "Сейчас данных нет",
                        size = 20.sp,
                        color = TextDim,
                    )
                } else {
                    Txt(
                        "${Epg.time(nowProg.start)}–${Epg.time(nowProg.stop)}   ${nowProg.title}",
                        size = 24.sp,
                        weight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(Epg.progress(nowProg, now), Modifier.fillMaxWidth())
                    nowProg.desc?.let {
                        Spacer(Modifier.height(8.dp))
                        Txt(it, size = 17.sp, color = TextDim, maxLines = 2)
                    }
                }
                val next = Epg.upcoming(programmes, now, 3)
                if (next.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Txt("Далее", size = 16.sp, color = Amber)
                    for (p in next) Txt("${Epg.time(p.start)}   ${p.title}", size = 18.sp, color = TextMain)
                }
            }
        }

        // Список каналов / категории (←, ← ещё раз)
        if (panel == PlayerPanel.Channels || panel == PlayerPanel.Groups) {
            ChannelPanel(
                showGroups = panel == PlayerPanel.Groups,
                panelGroup = panelGroup,
                onGroupChange = { panelGroup = it },
                allChannels = channels,
                favorites = favorites,
                epg = epg,
                now = now,
                currentSlug = slug,
                onPick = { ch ->
                    playGroup = panelGroup
                    slug = ch.slug
                    panel = PlayerPanel.None
                },
                onToggleFavorite = onToggleFavorite,
                onShowGroups = { panel = PlayerPanel.Groups },
                onSettings = { panel = PlayerPanel.Settings },
                onActivity = { touch++ },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }

        // Телепрограмма текущего канала (→)
        if (panel == PlayerPanel.Epg) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (ev.key == Key.DirectionLeft) {
                            panel = PlayerPanel.None
                            true
                        } else {
                            false
                        }
                    },
            ) {
                EpgOverlay(
                    channel = channel,
                    slug = slug,
                    logo = logo,
                    programmes = programmes,
                    now = now,
                    onActivity = { touch++ },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }

        // Меню настроек («шестерёнка» в оверлее категорий)
        if (panel == PlayerPanel.Settings) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown) touch++
                        false
                    },
            ) {
                if (showReport) {
                    val missing = if (epg.updatedAt > 0) {
                        channels.filter { epg.bySlug[it.slug].isNullOrEmpty() }.map { it.title }
                    } else {
                        emptyList()
                    }
                    ReportOverlay(failures, epgStatus, missing) { showReport = false }
                } else {
                    SettingsPanel(
                        status = status,
                        epg = epg,
                        epgStatus = epgStatus,
                        failures = failures,
                        update = update,
                        onRefreshChannels = {
                            panel = PlayerPanel.None
                            onRefresh()
                        },
                        onRefreshEpg = {
                            panel = PlayerPanel.None
                            onRefreshEpg()
                        },
                        onShowReport = { showReport = true },
                        onUpdate = onUpdate,
                    )
                }
            }
        }

        // Выбор потока (Menu)
        if (panel == PlayerPanel.Streams) {
            val selectedFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                try {
                    selectedFocus.requestFocus()
                } catch (e: Exception) {
                    // фокус останется на дефолтном элементе
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000))))
                    .padding(horizontal = 48.dp, vertical = 32.dp)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown) touch++
                        false
                    },
            ) {
                Txt("$num${channel?.title ?: slug}", size = 30.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Txt(
                    if (streams.size > 1) "Выберите поток" else "У канала один поток",
                    size = 18.sp,
                    color = TextDim,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val failedNow = failedStreams[slug].orEmpty()
                    streams.forEachIndexed { i, s ->
                        FocusItem(
                            selected = i == streamIdx,
                            focusRequester = if (i == streamIdx) selectedFocus else null,
                            onClick = {
                                chosen[slug] = i
                                failedStreams.remove(slug)
                                retried = retried - slug
                                panel = PlayerPanel.None
                            },
                        ) { focused ->
                            val mark = if (i == streamIdx) "• " else ""
                            val bad = i in failedNow
                            Txt(
                                mark + s.label + if (bad) " — не открылся" else "",
                                color = when {
                                    focused -> OnAmber
                                    bad -> ErrorRed
                                    else -> TextMain
                                },
                            )
                        }
                    }
                    FocusItem(
                        focusRequester = if (streams.isEmpty()) selectedFocus else null,
                        onClick = {
                            panel = PlayerPanel.None
                            reload(slug, resetStream = false)
                        },
                    ) { focused ->
                        Txt("Обновить ссылки", color = if (focused) OnAmber else TextDim)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Txt("← → выбор     OK — включить     Назад — закрыть", size = 16.sp, color = TextDim)
            }
        }
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