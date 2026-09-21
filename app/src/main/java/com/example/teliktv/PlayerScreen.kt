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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PlayerPanel { None, Channels, Groups, Streams, Epg, Settings }

/** Сколько раз подряд пробуем вернуться на «живую» позицию после BEHIND_LIVE_WINDOW, прежде чем считать поток нерабочим. */
private const val MAX_LIVE_RETRIES = 3

/** Шаг перемотки в timeshift. */
private const val SEEK_STEP_MS = 5_000L

/**
 * Сетевые / разбор манифеста — часто разовая случайность. Один повтор того же потока,
 * после второй ошибки — следующий.
 */
private val STREAM_RETRY_CODES = setOf(
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_TIMEOUT,
)

/** Понятное описание кода ExoPlayer для уведомлений. */
private fun streamErrorLabel(code: Int, codeName: String): String = when (code) {
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "нет связи с сервером потока"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "сервер потока ответил ошибкой"
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "сервер отдал не видео"
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "битый манифест / контейнер"
    PlaybackException.ERROR_CODE_TIMEOUT -> "таймаут загрузки"
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "отставание от эфира"
    else -> codeName
}

/**
 * Управление пультом (панели закрыты):
 *  ↑ / ↓ (и CH+/CH−)  — предыдущий / следующий канал в текущей группе
 *  ←                  — список каналов (ещё раз ← — категории, включая «Избранное»)
 *  →                  — телепрограмма; на паузе — возврат в прямой эфир
 *  Menu               — выбор потока
 *  OK                 — карточка «сейчас / далее» + Пауза / Продолжить
 *  Play/Pause         — пауза (если есть на пульте)
 *  Назад              — закрыть панель / выйти в список
 * Если поток не открылся или не стартует за Config.STREAM_TIMEOUT_MS — автоматически включается
 * следующий поток канала; когда исчерпаны все — один раз перепарсивается канал и всё повторяется.
 * Манифест/контейнер: 1 повтор того же потока, после 2-й ошибки — сразу следующий поток.
 * Timeshift: сегменты на диске (cacheDir/stream_cache), до 1 часа.
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
    var timeshiftIoRetries by remember { mutableIntStateOf(0) }  // сбои IO только в timeshift
    // slug -> индексы потоков, для которых уже была одна повторная попытка после malformed-ошибки.
    val manifestRetried = remember { mutableStateMapOf<String, Set<Int>>() }
    // slug канала, у которого поток выбран вручную из меню: при сбое сами на другой поток не прыгаем.
    var pinned by remember { mutableStateOf<String?>(null) }
    // Пауза: playWhenReady=false.
    var paused by remember { mutableStateOf(false) }
    // Режим timeshift (до «В эфир») — даже если видео играет с буфера.
    var inTimeshift by remember { mutableStateOf(false) }
    // Карточка медиаплеера (открывается по паузе / OK в режиме timeshift).
    var timeshiftUi by remember { mutableStateOf(false) }
    // Фокус на таймлайне timeshift (↑): ← → — перемотка.
    var timelineFocused by remember { mutableStateOf(false) }
    // Размер буфера (сек) — настройка; смена пересоздаёт плеер и лимит SimpleCache.
    var maxBufferSec by remember { mutableIntStateOf(PlayerPrefs.getMaxBufferSec(context)) }
    // Занято кэшем / позиция timeshift (строки для UI, только на паузе).
    var cacheUsedMb by remember { mutableStateOf("0 МБ") }
    var shiftLabel by remember { mutableStateOf("−0:00") }
    // Байты, реально скачанные плеером (потокобезопасно — listener не на Main).
    val loadedBytesRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    // После открытия карточки игнорируем OK на кнопках (иначе то же нажатие жмёт «Пауза»).
    var detailIgnoreOkUntil by remember { mutableLongStateOf(0L) }
    // Момент входа в паузу и оценка объёма на этот момент.
    var pauseStartedAt by remember { mutableLongStateOf(0L) }
    var pauseBaseBytes by remember { mutableLongStateOf(0L) }

    // ---- плеер (создаём до действий, которые к нему обращаются)
    val exo = remember(maxBufferSec) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 15_000,
                /* maxBufferMs */ maxBufferSec * 1000,
                /* bufferForPlaybackMs */ 2_500,
                /* bufferForPlaybackAfterRebufferMs */ 5_000,
            )
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
            .apply { playWhenReady = true }
    }
    DisposableEffect(exo) { onDispose { exo.release() } }

    // Считаем скачанные байты — надёжный источник для «кэш: N МБ».
    DisposableEffect(exo) {
        val analytics = object : AnalyticsListener {
            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            ) {
                val n = loadEventInfo.bytesLoaded
                if (n > 0) loadedBytesRef.addAndGet(n)
            }
        }
        exo.addAnalyticsListener(analytics)
        onDispose { exo.removeAnalyticsListener(analytics) }
    }

    // ---- действия

    fun zap(delta: Int) {
        if (playlist.isEmpty()) return
        val i = playlist.indexOf(slug)
        val n = if (i < 0) (if (delta > 0) 0 else playlist.size - 1) else (i + delta).mod(playlist.size)
        slug = playlist[n]
        detail = false
        paused = false
        inTimeshift = false
        timeshiftUi = false
    }

    fun formatShift(ms: Long): String {
        val sec = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return "−%d:%02d".format(sec / 60, sec % 60)
    }

    fun estimateBytesNow(): Long {
        val disk = StreamCache.usedBytes(context)
        val loaded = loadedBytesRef.get()
        val bufMs = exo.totalBufferedDuration.coerceAtLeast(0L)
        val fromBuf = (bufMs / 1000.0 * PlayerPrefs.MB_PER_SEC * 1024.0 * 1024.0).toLong()
        val liveOff = exo.currentLiveOffset.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
        val fromLive = (liveOff / 1000.0 * PlayerPrefs.MB_PER_SEC * 1024.0 * 1024.0).toLong()
        return maxOf(disk, loaded, fromBuf, fromLive, pauseBaseBytes)
    }

    /** В прямой эфир: seek на live edge + очистка дискового кэша. */
    fun goLive() {
        if (System.currentTimeMillis() < detailIgnoreOkUntil) return
        paused = false
        inTimeshift = false
        timeshiftUi = false
        timelineFocused = false
        timeshiftIoRetries = 0
        StreamCache.clear()
        loadedBytesRef.set(0L)
        pauseBaseBytes = 0L
        cacheUsedMb = "кэш: 0.0 МБ"
        shiftLabel = "−0:00"
        exo.seekToDefaultPosition()
        exo.playWhenReady = true
        notice = "прямой эфир"
    }

    /** Пауза / продолжить. Вход в паузу включает timeshift и карточку плеера. */
    fun togglePause() {
        if (error != null || stream == null) return
        // Не срабатываем от того же OK, которым только что открыли карточку.
        if (System.currentTimeMillis() < detailIgnoreOkUntil) return
        if (paused) {
            // Продолжить с текущей позиции timeshift (не уходим в эфир).
            paused = false
            detail = false
            exo.playWhenReady = true
        } else if (
            inTimeshift ||
            exo.isPlaying ||
            exo.playbackState == Player.STATE_READY ||
            exo.playbackState == Player.STATE_BUFFERING
        ) {
            if (!inTimeshift) {
                pauseBaseBytes = estimateBytesNow().coerceAtLeast(1L)
                pauseStartedAt = System.currentTimeMillis()
                inTimeshift = true
                timeshiftIoRetries = 0
            }
            detail = false
            paused = true
            timeshiftUi = true
            timelineFocused = false
            exo.playWhenReady = false
            val off = exo.currentLiveOffset
            val behind = if (off == C.TIME_UNSET || off < 0) {
                exo.totalBufferedDuration.coerceAtLeast(0L)
            } else {
                off
            }
            shiftLabel = formatShift(behind)
            val mb = (behind / 1000.0 * PlayerPrefs.MB_PER_SEC).coerceAtLeast(0.1)
            cacheUsedMb = "кэш: ${"%.1f".format(mb)} МБ"
        }
    }

    /**
     * Перемотка только внутри уже загруженного буфера.
     * Seek за край буфера / live-окна на CDN даёт ERROR_CODE_IO_UNSPECIFIED.
     */
    fun seekBy(deltaMs: Long) {
        if (!inTimeshift || error != null) return
        if (!exo.isCurrentMediaItemSeekable) {
            notice = "перемотка недоступна на этом потоке"
            return
        }
        val pos = exo.currentPosition
        val bufferedEnd = exo.bufferedPosition
        val bufDur = exo.totalBufferedDuration.coerceAtLeast(0L)
        // Левый край ≈ то, что ещё в RAM; правый — bufferedPosition (не дальше live).
        val minPos = (pos - bufDur).coerceAtLeast(0L)
        val maxPos = maxOf(pos, bufferedEnd)
        val target = (pos + deltaMs).coerceIn(minPos, maxPos)
        if (kotlin.math.abs(target - pos) < 500L) {
            notice = if (deltaMs < 0) "начало буфера timeshift" else "край буфера timeshift"
            return
        }
        exo.seekTo(target)
        val off = exo.currentLiveOffset
        shiftLabel = formatShift(if (off == C.TIME_UNSET || off < 0) (maxPos - target) else off)
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
                manifestRetried.remove(s)
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
        paused = false
        // Поток выбран вручную — не перескакиваем сами: показываем причину, дальше выбирает пользователь.
        if (pinned == s) {
            error = "«${list.getOrNull(cur)?.label ?: "Поток"}» не открылся ($reason)"
            return
        }
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

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    error = null
                    failedStreams.remove(currentSlug)
                    manifestRetried.remove(currentSlug)
                    retried = retried - currentSlug
                    liveRetries = 0
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                val label = streamErrorLabel(e.errorCode, e.errorCodeName)

                // --- Timeshift: позиция вне live-окна / IO — не меняем поток, выходим в эфир ---
                // Причина: seek или пауза ушли за доступные сегменты CDN; «лечение» seek'ом
                // только усугубляет IO_UNSPECIFIED. Безопасный выход — live edge.
                if (inTimeshift && (
                        e.errorCode in STREAM_RETRY_CODES ||
                        e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                    )
                ) {
                    notice = "$label — буфер timeshift недоступен, прямой эфир"
                    goLive()
                    return
                }

                // --- Обычный эфир: отстали от live-окна ---
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && liveRetries < MAX_LIVE_RETRIES) {
                    liveRetries++
                    paused = false
                    exo.seekToDefaultPosition()
                    exo.prepare()
                    exo.playWhenReady = true
                    return
                }

                // --- Обычный эфир: сеть / манифест — 1 повтор, затем следующий поток ---
                if (e.errorCode in STREAM_RETRY_CODES) {
                    val s = currentSlug
                    val idx = chosen[s] ?: 0
                    val done = manifestRetried[s] ?: emptySet()
                    if (idx !in done) {
                        manifestRetried[s] = done + idx
                        notice = "$label — пробую ещё раз"
                        nonce++
                        return
                    }
                    val failedStream = bySlugState[s]?.streams?.getOrNull(idx)
                    onStreamFailed(label)
                    if (failedStream != null && e.errorCode in setOf(
                            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                        )
                    ) {
                        scope.launch {
                            val why = StreamProbe.describe(failedStream)
                            val refined = "$label: $why"
                            notice = notice?.replace(label, refined)
                            error = error?.replace(label, refined)
                        }
                    }
                    return
                }
                onStreamFailed(label)
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

    LaunchedEffect(slug, stream?.url, nonce, maxBufferSec) {
        error = null
        paused = false
        inTimeshift = false
        timeshiftUi = false
        loadedBytesRef.set(0L)
        if (inBackground) return@LaunchedEffect
        if (stream == null) {
            exo.stop()
            return@LaunchedEffect
        }
        exo.setMediaSource(buildSource(context, stream, maxBufferSec))
        exo.prepare()
        exo.playWhenReady = true
        // «Висящий» поток без ошибки тоже считаем нерабочим.
        delay(Config.STREAM_TIMEOUT_MS)
        if (!inBackground && !paused && exo.playbackState != Player.STATE_READY) {
            onStreamFailed("нет ответа за ${Config.STREAM_TIMEOUT_MS / 1000} с")
        }
    }

    // После «Продолжить» карточка сама скрывается через 5 с (сброс при активности).
    LaunchedEffect(timeshiftUi, paused, touch) {
        if (!timeshiftUi || paused) return@LaunchedEffect
        delay(5_000)
        if (timeshiftUi && !paused) {
            timeshiftUi = false
            timelineFocused = false
        }
    }

    // Счётчик на всём timeshift (пауза или воспроизведение с буфера).
    LaunchedEffect(inTimeshift) {
        if (!inTimeshift) return@LaunchedEffect
        while (true) {
            val liveOff = exo.currentLiveOffset
            val wallBehind = if (pauseStartedAt > 0L) System.currentTimeMillis() - pauseStartedAt else 0L
            // liveOffset растёт, пока стоим; если поток не отдаёт offset — берём wall clock.
            val behindMs = when {
                liveOff != C.TIME_UNSET && liveOff > 0L -> maxOf(liveOff, wallBehind)
                else -> maxOf(exo.totalBufferedDuration.coerceAtLeast(0L), wallBehind)
            }
            shiftLabel = formatShift(behindMs)
            // МБ всегда от отставания (≈0.5 МБ/с) — двигается вместе с таймером.
            val mb = (behindMs / 1000.0 * PlayerPrefs.MB_PER_SEC).coerceAtLeast(0.1)
            cacheUsedMb = "кэш: ${"%.1f".format(mb)} МБ"
            delay(400)
        }
    }

    LaunchedEffect(slug, playGroup) { onCurrent(slug, playGroup) }
    LaunchedEffect(slug) {
        pinned = null
        paused = false
        inTimeshift = false
        timeshiftUi = false
        loadedBytesRef.set(0L)
    }
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
            timeshiftUi -> timeshiftUi = false  // закрыть карточку плеера
            paused -> togglePause()             // ещё Назад на паузе — продолжить
            else -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (panel != PlayerPanel.None || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Timeshift-карточка: ↑ таймлайн (← → перемотка), ↓ кнопки, OK — нажать.
                if (timeshiftUi) {
                    touch++  // сброс автоскрытия
                    return@onPreviewKeyEvent when (ev.key) {
                        Key.DirectionUp -> {
                            timelineFocused = true
                            true
                        }
                        Key.DirectionDown -> {
                            timelineFocused = false
                            true
                        }
                        Key.DirectionLeft -> {
                            if (timelineFocused) {
                                seekBy(-SEEK_STEP_MS)
                                true
                            } else {
                                false  // фокус между кнопками
                            }
                        }
                        Key.DirectionRight -> {
                            if (timelineFocused) {
                                seekBy(SEEK_STEP_MS)
                                true
                            } else {
                                false
                            }
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (timelineFocused) {
                                // на таймлайне OK не жмёт кнопки
                                true
                            } else {
                                false
                            }
                        }
                        Key.ChannelUp, Key.ChannelDown, Key.PageUp, Key.PageDown -> true
                        Key.Menu -> { panel = PlayerPanel.Streams; touch++; true }
                        Key.MediaRewind -> { seekBy(-SEEK_STEP_MS); true }
                        Key.MediaFastForward -> { seekBy(SEEK_STEP_MS); true }
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { togglePause(); true }
                        else -> false
                    }
                }
                // Карточка EPG: стрелки/OK — фокус и нажатие кнопок.
                if (detail) {
                    return@onPreviewKeyEvent when (ev.key) {
                        Key.DirectionLeft, Key.DirectionRight,
                        Key.DirectionUp, Key.DirectionDown,
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> false
                        Key.ChannelUp, Key.ChannelDown, Key.PageUp, Key.PageDown -> true
                        Key.Menu -> { panel = PlayerPanel.Streams; touch++; true }
                        else -> false
                    }
                }
                when (ev.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp -> { zap(-1); true }
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown -> { zap(1); true }
                    Key.DirectionLeft -> {
                        panelGroup = playGroup
                        panel = PlayerPanel.Channels
                        touch++
                        true
                    }
                    Key.DirectionRight -> {
                        panel = PlayerPanel.Epg
                        touch++
                        true
                    }
                    Key.Menu -> { panel = PlayerPanel.Streams; touch++; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (inTimeshift) {
                            // В timeshift (пауза или игра с буфера) — только карточка медиаплеера.
                            detail = false
                            timeshiftUi = true
                            detailIgnoreOkUntil = System.currentTimeMillis() + 450
                        } else {
                            detail = true
                            detailIgnoreOkUntil = System.currentTimeMillis() + 450
                        }
                        true
                    }
                    Key.MediaPlayPause -> { togglePause(); true }
                    Key.MediaPause -> {
                        if (!paused) togglePause()
                        true
                    }
                    Key.MediaPlay -> {
                        if (paused) togglePause()
                        true
                    }
                    Key.MediaRewind -> { if (inTimeshift) seekBy(-SEEK_STEP_MS); true }
                    Key.MediaFastForward -> { if (inTimeshift) seekBy(SEEK_STEP_MS); true }
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
            update = { view -> view.player = exo },
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

        // Timeshift — карточка медиаплеера (пауза или воспроизведение с буфера)
        if (inTimeshift && timeshiftUi && panel == PlayerPanel.None && error == null) {
            val playFocus = remember { FocusRequester() }
            LaunchedEffect(timeshiftUi, timelineFocused) {
                if (!timelineFocused) {
                    delay(200)
                    try { playFocus.requestFocus() } catch (_: Exception) {}
                }
            }
            val maxMs = (maxBufferSec * 1000L).coerceAtLeast(1L)
            val wall = if (pauseStartedAt > 0L) System.currentTimeMillis() - pauseStartedAt else 0L
            val liveOff = exo.currentLiveOffset
            val behindMs = when {
                liveOff != C.TIME_UNSET && liveOff > 0L -> maxOf(liveOff, wall)
                else -> maxOf(exo.totalBufferedDuration.coerceAtLeast(0L), wall)
            }
            val behindForBar = (behindMs.toFloat() / maxMs.toFloat()).coerceIn(0f, 1f)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000))))
                    .padding(horizontal = 48.dp, vertical = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(logo, channel?.title ?: slug)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Txt("$num${channel?.title ?: slug}", size = 26.sp, weight = FontWeight.Bold)
                        Txt("TIMESHIFT  $shiftLabel", size = 18.sp, color = Amber)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Txt(cacheUsedMb, size = 24.sp, weight = FontWeight.Bold, color = Amber)
                Spacer(Modifier.height(12.dp))
                // Таймлайн: ↑ фокус, ← → перемотка по 5 с
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (timelineFocused) Color(0x44F2B33D) else Color.Transparent,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(shiftLabel, size = 15.sp, color = if (timelineFocused) Amber else TextDim)
                    Spacer(Modifier.width(10.dp))
                    ProgressBar(behindForBar, Modifier.weight(1f).height(if (timelineFocused) 10.dp else 8.dp))
                    Spacer(Modifier.width(10.dp))
                    Txt("эфир", size = 15.sp, color = if (timelineFocused) Amber else TextDim)
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FocusItem(onClick = { seekBy(-SEEK_STEP_MS); touch++ }) { focused ->
                        Txt("−5 с", size = 20.sp, weight = FontWeight.Bold, color = if (focused) OnAmber else TextMain)
                    }
                    FocusItem(focusRequester = playFocus, onClick = { togglePause(); touch++ }) { focused ->
                        Txt(
                            if (paused) "▶  Продолжить" else "❚❚  Пауза",
                            size = 20.sp,
                            weight = FontWeight.Bold,
                            color = if (focused) OnAmber else TextMain,
                        )
                    }
                    FocusItem(onClick = { seekBy(SEEK_STEP_MS); touch++ }) { focused ->
                        Txt("+5 с", size = 20.sp, weight = FontWeight.Bold, color = if (focused) OnAmber else TextMain)
                    }
                    FocusItem(onClick = { goLive() }) { focused ->
                        Txt("В эфир", size = 20.sp, weight = FontWeight.Bold, color = if (focused) OnAmber else TextMain)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Txt(
                    if (timelineFocused) {
                        "← → перемотка ±5 с     ↓ кнопки     лимит ${PlayerPrefs.hint(maxBufferSec)}"
                    } else {
                        "↑ таймлайн     ← → кнопки     OK — нажать     (автоскрытие 5 с)"
                    },
                    size = 15.sp,
                    color = TextDim,
                )
            }
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
                    Txt(err, size = 22.sp, color = ErrorRed, maxLines = 3)
                    Spacer(Modifier.height(6.dp))
                    Txt("Menu — выбрать поток вручную, ↑ ↓ другой канал", size = 18.sp, color = TextDim)
                }
            }
        }

        // Карточка «сейчас / далее» (OK) + кнопка Пауза (на паузе показывается плеер выше)
        if (detail && !paused && panel == PlayerPanel.None && channel != null) {
            val pauseFocus = remember { FocusRequester() }
            // Фокус на кнопку после отпускания OK, иначе то же нажатие активирует «Пауза».
            LaunchedEffect(detail) {
                if (!detail) return@LaunchedEffect
                delay(200)
                try {
                    pauseFocus.requestFocus()
                } catch (_: Exception) {
                }
            }
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
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FocusItem(
                        focusRequester = pauseFocus,
                        onClick = { togglePause() },
                    ) { focused ->
                        Txt(
                            "❚❚  Пауза",
                            size = 20.sp,
                            weight = FontWeight.Bold,
                            color = if (focused) OnAmber else TextMain,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Txt(
                    "OK — пауза (timeshift)     Назад — закрыть",
                    size = 15.sp,
                    color = TextDim,
                )
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
                        maxBufferSec = maxBufferSec,
                        onBufferCycle = {
                            val next = PlayerPrefs.next(maxBufferSec)
                            PlayerPrefs.setMaxBufferSec(context, next)
                            maxBufferSec = next
                            notice = "кэш: ${PlayerPrefs.hint(next)}"
                        },
                        paused = paused,
                        onTogglePause = {
                            togglePause()
                            panel = PlayerPanel.None
                        },
                        onGoLive = {
                            goLive()
                            panel = PlayerPanel.None
                        },
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
                                manifestRetried.remove(slug)
                                retried = retried - slug
                                pinned = slug
                                nonce++   // тот же поток выбран повторно — тоже перезапускаем
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

/**
 * Live-потоки: только HTTP + RAM-буфер ExoPlayer (LoadControl).
 * Дисковый кэш live-сегментов после сдвига окна CDN отдаёт протухшие куски →
 * ERROR_CODE_IO_UNSPECIFIED при timeshift/seek. Timeshift = то, что ещё в maxBufferMs.
 */
@OptIn(UnstableApi::class)
private fun buildSource(context: Context, s: StreamItem, maxBufferSec: Int): MediaSource {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(Config.UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
        // identity вместо gzip: DefaultHttpDataSource работает поверх HttpURLConnection,
        // а тот при сжатом ответе иногда путает Content-Length и обрезает файл — плеер
        // получает половину плейлиста и падает с PARSING_MANIFEST_MALFORMED. Плейлисты
        // маленькие, разжимать их всё равно не нужно.
        .setDefaultRequestProperties(mapOf("Referer" to s.referer, "Accept-Encoding" to "identity"))
    val mime = if (Extract.isDash(s.url)) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
    val item = MediaItem.Builder().setUri(s.url).setMimeType(mime).build()
    return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item)
}