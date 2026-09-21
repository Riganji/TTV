package com.example.teliktv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class LoadStatus(val loading: Boolean = false, val done: Int = 0, val total: Int = 0)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ChannelRepository(app)
    private val epgRepo = EpgRepository(app)
    private val scraper = TelikScraper()
    private val updater = Updater(app)

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _status = MutableStateFlow(LoadStatus())
    val status: StateFlow<LoadStatus> = _status.asStateFlow()

    /** Каналы, которые не удалось загрузить при последнем обновлении (с причиной). */
    private val _failures = MutableStateFlow<List<ChannelFailure>>(emptyList())
    val failures: StateFlow<List<ChannelFailure>> = _failures.asStateFlow()

    private val _favorites = MutableStateFlow(repo.loadFavorites())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _epg = MutableStateFlow(EpgData())
    val epg: StateFlow<EpgData> = _epg.asStateFlow()

    private val _epgStatus = MutableStateFlow(EpgStatus())
    val epgStatus: StateFlow<EpgStatus> = _epgStatus.asStateFlow()

    private val _update = MutableStateFlow(UpdateState())
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    private var job: Job? = null
    private var epgJob: Job? = null
    private var updateJob: Job? = null

    init {
        // Результат установки приходит из системного окна подтверждения.
        viewModelScope.launch {
            UpdateBus.results.collect { error ->
                _update.update {
                    it.copy(installing = false, downloading = false, progress = 0, error = error)
                }
            }
        }
        viewModelScope.launch {
            // Названия потоков из кэша прошлых версий приводим к нынешнему виду, каналы
            // с постоянной ссылкой добавляем сразу (merge с пустым fresh это и делает).
            _channels.value = withContext(Dispatchers.IO) {
                repo.load().map { it.copy(streams = Extract.cleanStreams(it.streams)) }
            }
            merge(emptyMap())
            withContext(Dispatchers.IO) { epgRepo.load() }?.let { applyEpg(it) }
            refresh(forceEpg = false)
        }
        checkUpdate(force = false)
    }

    // ------------------------------------------------------------------ обновление приложения

    /**
     * Единственное действие пункта «Обновление» в настройках:
     * ничего не найдено — проверяем, найдено — качаем и ставим.
     */
    fun onUpdateClick() {
        val state = _update.value
        if (state.checking || state.downloading || state.installing) return
        val release = state.available
        if (release == null) checkUpdate(force = true) else install(release)
    }

    /** force = false — не чаще раза в UpdateConfig.CHECK_TTL_MS и молча (ошибки не показываем). */
    fun checkUpdate(force: Boolean) {
        if (updateJob?.isActive == true) return
        val state = _update.value
        val now = System.currentTimeMillis()
        if (!force && state.checkedAt > 0 && now - state.checkedAt < UpdateConfig.CHECK_TTL_MS) return
        updateJob = viewModelScope.launch {
            _update.update { it.copy(checking = true, error = null) }
            try {
                val latest = updater.latest()
                val newer = latest != null && Versions.isNewer(latest.version, BuildConfig.VERSION_NAME)
                _update.update {
                    it.copy(
                        checking = false,
                        available = if (newer) latest else null,
                        checkedAt = System.currentTimeMillis(),
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Молчаливую проверку при старте не «засчитываем»: пункт меню должен
                // остаться в состоянии «проверить», а не «установлена последняя версия».
                _update.update {
                    it.copy(
                        checking = false,
                        checkedAt = if (force) System.currentTimeMillis() else it.checkedAt,
                        error = if (force) (e.message ?: e.javaClass.simpleName) else null,
                    )
                }
            }
        }
    }

    private fun install(release: ReleaseInfo) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            // На Android 8+ без разрешения «устанавливать неизвестные приложения» установка не начнётся.
            if (!updater.canInstall()) {
                val opened = updater.openInstallSettings()
                _update.update {
                    it.copy(
                        error = if (opened) {
                            "разрешите установку приложений из этого источника и повторите"
                        } else {
                            "в системе запрещена установка из неизвестных источников"
                        },
                    )
                }
                return@launch
            }
            _update.update { it.copy(downloading = true, progress = 0, error = null) }
            try {
                val file = updater.download(release) { p ->
                    _update.update { it.copy(progress = p) }
                }
                _update.update { it.copy(downloading = false, installing = true, progress = 100) }
                updater.install(file)   // дальше ответит система -> UpdateBus
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.update {
                    it.copy(
                        downloading = false,
                        installing = false,
                        progress = 0,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ избранное

    fun toggleFavorite(slug: String) {
        val next = _favorites.value.let { if (slug in it) it - slug else it + slug }
        _favorites.value = next
        repo.saveFavorites(next)
    }

    // ------------------------------------------------------------------ каналы

    /** Полное обновление: сначала виден кэш, свежие каналы подставляются по мере разбора. */
    fun refresh(forceEpg: Boolean = true) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val slugs = Config.SCRAPE_SLUGS
            // Названия запоминаем до обновления: сбойный канал исчезает из списка, но в отчёте
            // его надо показать по-человечески, а не слагом.
            val titlesBefore = _channels.value.associate { it.slug to it.title }
            _status.value = LoadStatus(loading = true, done = 0, total = slugs.size)
            _failures.value = emptyList()
            val fresh = ConcurrentHashMap<String, Channel>()
            val failed = ConcurrentHashMap<String, String>()
            try {
                scraper.scrapeAll(slugs) { slug, channel, error ->
                    if (channel != null) {
                        fresh[slug] = channel
                    } else {
                        failed[slug] = error ?: "неизвестная ошибка"
                    }
                    // Канал без разобранных ссылок убираем из списка сразу же.
                    merge(fresh, failed.keys.toSet())
                    _status.update { it.copy(done = it.done + 1) }
                }
                _failures.value = slugs.mapNotNull { s ->
                    failed[s]?.let { ChannelFailure(s, titlesBefore[s] ?: s, it, cached = false) }
                }
                withContext(Dispatchers.IO) { repo.save(_channels.value) }
            } finally {
                _status.update { it.copy(loading = false) }
            }
            refreshEpg(force = forceEpg)
        }
    }

    /** Перепарсить один канал (ссылки с токенами протухают). */
    suspend fun reloadChannel(slug: String) {
        if (slug in Config.FIXED_SLUGS) return   // постоянная ссылка, перепарсивать нечего
        val ch = try {
            scraper.parseChannel(slug)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        merge(mapOf(slug to ch))
        withContext(Dispatchers.IO) { repo.save(_channels.value) }
    }

    /**
     * Пересобирает список в порядке Config.ALL_SLUGS.
     * Канал показывается только если у него есть разобранные ссылки: сбойные (failed) и пустые
     * в список не попадают, даже если в кэше остался прошлый вариант.
     */
    private fun merge(fresh: Map<String, Channel>, failed: Set<String> = emptySet()) {
        _channels.update { old ->
            val byOld = old.associateBy { it.slug }
            val fixed = Config.FIXED_CHANNELS.associateBy { it.slug }
            val merged = Config.ALL_SLUGS
                .mapNotNull { slug ->
                    fixed[slug] ?: if (slug in failed) null else (fresh[slug] ?: byOld[slug])
                }
                .filter { it.streams.isNotEmpty() }
            // Один и тот же «логотип» у нескольких каналов — это баннер сайта, а не логотип.
            val shared = Extract.sharedLogoUrls(merged.map { it.logo })
            if (shared.isEmpty()) {
                merged
            } else {
                merged.map { c ->
                    val l = c.logo
                    if (l != null && l in shared) c.copy(logo = null) else c
                }
            }
        }
    }

    // ------------------------------------------------------------------ телепрограмма

    /** «Обновить телепрограмму» из меню настроек — каналы при этом не перепарсиваются. */
    fun refreshEpgOnly() {
        if (epgJob?.isActive == true) return
        epgJob = viewModelScope.launch { refreshEpg(force = true) }
    }

    private fun applyEpg(cache: EpgCache) {
        _epg.value = EpgData(cache.programmes, cache.icons, cache.updatedAt, cache.source)
        val total = _channels.value.size
        val matched = _channels.value.count { !cache.programmes[it.slug].isNullOrEmpty() }
        _epgStatus.value = EpgStatus(matched = matched, total = total)
    }

    private suspend fun refreshEpg(force: Boolean) {
        val chans = _channels.value
        if (chans.isEmpty()) return
        val now = System.currentTimeMillis()
        val have = _epg.value
        val fresh = have.updatedAt > 0 && now - have.updatedAt < EpgConfig.TTL_MS
        // Каналов без маппинга телепрограммы просто нет в источнике — их не ждём.
        val expected = chans.filter { it.slug in EpgConfig.CHANNEL_MAP }
        // Кэш свежий и покрывает всё, что вообще может прийти — сеть не трогаем.
        if (!force && fresh && expected.all { !have.bySlug[it.slug].isNullOrEmpty() }) {
            _epgStatus.value = EpgStatus(matched = expected.size, total = chans.size)
            return
        }
        if (!force && fresh) {
            applyEpg(EpgCache(have.updatedAt, have.source, have.bySlug, have.icons))
            return
        }
        _epgStatus.value = _epgStatus.value.copy(loading = true, error = null)
        try {
            // Передачи подставляются в UI по мере готовности каналов.
            val partial = LinkedHashMap<String, List<Programme>>(_epg.value.bySlug)
            val cache = epgRepo.download(now) { slug, programmes ->
                partial[slug] = programmes
                _epg.update { it.copy(bySlug = LinkedHashMap(partial), updatedAt = now, source = EpgConfig.BASE) }
            }
            withContext(Dispatchers.IO) { epgRepo.save(cache) }
            applyEpg(cache)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val keep = if (have.updatedAt > 0) " Показана сохранённая программа." else ""
            _epgStatus.value = _epgStatus.value.copy(
                loading = false,
                error = "не загрузилась: ${e.message ?: e.javaClass.simpleName}.$keep",
            )
        }
    }
}
