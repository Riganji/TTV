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

    private var job: Job? = null

    init {
        viewModelScope.launch {
            _channels.value = withContext(Dispatchers.IO) { repo.load() }
            withContext(Dispatchers.IO) { epgRepo.load() }?.let { applyEpg(it) }
            refresh(forceEpg = false)
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
            val slugs = Config.ALL_SLUGS
            _status.value = LoadStatus(loading = true, done = 0, total = slugs.size)
            _failures.value = emptyList()
            val fresh = ConcurrentHashMap<String, Channel>()
            val failed = ConcurrentHashMap<String, String>()
            try {
                scraper.scrapeAll(slugs) { slug, channel, error ->
                    if (channel != null) {
                        fresh[slug] = channel
                        merge(fresh)
                    } else {
                        failed[slug] = error ?: "неизвестная ошибка"
                    }
                    _status.update { it.copy(done = it.done + 1) }
                }
                val current = _channels.value.associateBy { it.slug }
                _failures.value = slugs.mapNotNull { s ->
                    failed[s]?.let { ChannelFailure(s, current[s]?.title ?: s, it, cached = s in current) }
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

    private fun merge(fresh: Map<String, Channel>) {
        _channels.update { old ->
            val byOld = old.associateBy { it.slug }
            val merged = Config.ALL_SLUGS.mapNotNull { fresh[it] ?: byOld[it] }
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
