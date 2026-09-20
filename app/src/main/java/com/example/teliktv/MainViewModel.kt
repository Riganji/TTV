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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class LoadStatus(
    val loading: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: List<String> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ChannelRepository(app)
    private val favRepo = FavoritesRepository(app)
    private val scraper = TelikScraper()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _status = MutableStateFlow(LoadStatus())
    val status: StateFlow<LoadStatus> = _status.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            _channels.value = withContext(Dispatchers.IO) { repo.load() }
            _favorites.value = withContext(Dispatchers.IO) { favRepo.load() }
            refresh()
        }
    }

    fun toggleFavorite(slug: String) {
        _favorites.update { old ->
            val next = if (slug in old) old - slug else old + slug
            favRepo.save(next)
            next
        }
    }

    /** Полное обновление: сначала виден кэш, свежие каналы подставляются по мере разбора. */
    fun refresh() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val slugs = Config.ALL_SLUGS
            _status.value = LoadStatus(loading = true, done = 0, total = slugs.size)
            val fresh = ConcurrentHashMap<String, Channel>()
            val failed = Collections.synchronizedList(ArrayList<String>())
            try {
                scraper.scrapeAll(slugs) { slug, ch ->
                    if (ch != null && ch.streams.isNotEmpty()) {
                        fresh[slug] = ch
                        merge(fresh)
                    } else {
                        failed.add(slug)
                    }
                    _status.update { it.copy(done = it.done + 1) }
                }
                _status.update { it.copy(failed = failed.toList().sorted()) }
                withContext(Dispatchers.IO) { repo.save(_channels.value) }
            } finally {
                _status.update { it.copy(loading = false) }
            }
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
        if (ch.streams.isEmpty()) return
        merge(mapOf(slug to ch))
        withContext(Dispatchers.IO) { repo.save(_channels.value) }
    }

    private fun merge(fresh: Map<String, Channel>) {
        _channels.update { old ->
            val byOld = old.associateBy { it.slug }
            Config.ALL_SLUGS.mapNotNull { fresh[it] ?: byOld[it] }
        }
    }
}