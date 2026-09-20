package com.example.teliktv

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Порт сетевой части parser.py: страница канала -> iframe-плееры -> ссылки на потоки (+ логотип). */
class TelikScraper {

    class HttpError(val url: String, val status: Int) : IOException("$url: HTTP $status")

    /** Страница загрузилась, но нужного на ней нет (нет плеера / нет ссылок). */
    class ScrapeException(message: String) : Exception(message)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", Config.UA)
                    .header("Accept-Language", "ru,en;q=0.8")
                    .build(),
            )
        }
        .build()

    /** Человекочитаемая причина сбоя — для сообщения «какие каналы не загрузились». */
    fun describe(e: Throwable): String = when (e) {
        is HttpError -> when (e.status) {
            404 -> "страница не найдена (HTTP 404)"
            403 -> "доступ запрещён (HTTP 403)"
            429 -> "слишком много запросов (HTTP 429)"
            in 500..599 -> "ошибка сервера (HTTP ${e.status})"
            else -> "HTTP ${e.status}"
        }
        is ScrapeException -> e.message ?: "ошибка разбора"
        is IOException -> "нет ответа (${e.message ?: e.javaClass.simpleName})"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ------------------------------------------------------------------ HTTP

    private suspend fun fetchOnce(url: String, referer: String?, iframe: Boolean): String =
        runInterruptible(Dispatchers.IO) {
            val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("некорректный адрес: $url")
            val rb = Request.Builder().url(httpUrl)
            if (referer != null) rb.header("Referer", referer)
            if (iframe) {
                rb.header("Sec-Fetch-Dest", "iframe")
                rb.header("Sec-Fetch-Mode", "navigate")
                rb.header("Sec-Fetch-Site", "cross-site")
                rb.header("Upgrade-Insecure-Requests", "1")
            }
            client.newCall(rb.build()).execute().use { r ->
                if (r.code >= 400) throw HttpError(url, r.code)
                Extract.decodeBody(r.body?.bytes() ?: ByteArray(0), r.header("Content-Type"))
            }
        }

    /** get() из parser.py: ретраи на сетевых ошибках, 5xx и 429; остальные 4xx — сразу ошибка. */
    private suspend fun get(
        url: String,
        referer: String? = null,
        retries: Int = 3,
        iframe: Boolean = false,
    ): String {
        var last: IOException? = null
        repeat(retries) { attempt ->
            try {
                return fetchOnce(url, referer, iframe)
            } catch (e: HttpError) {
                if (e.status < 500 && e.status != 429) throw e
                last = e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                last = IOException("${e.message}", e)
            }
            delay(1500L * (attempt + 1))
        }
        throw last ?: IOException(url)
    }

    /** get_player(): iframe нередко отдаёт 403 без нужного Referer — пробуем страницу, затем origin. */
    private suspend fun getPlayer(url: String, pageUrl: String): String {
        var last: HttpError? = null
        for (ref in linkedSetOf(pageUrl, Extract.origin(pageUrl))) {
            try {
                return get(url, referer = ref, iframe = true)
            } catch (e: HttpError) {
                last = e
                if (e.status != 401 && e.status != 403) throw e
            }
        }
        throw last ?: IOException(url)
    }

    // ------------------------------------------------------------------ разбор

    private suspend fun resolvePlayer(playerUrl: String, referer: String, depth: Int = 2): List<String> {
        if (Extract.isDirectStream(playerUrl)) return listOf(playerUrl)
        val html = getPlayer(playerUrl, referer)
        val streams = ArrayList(Extract.extractStreams(html, playerUrl))
        if (streams.isEmpty() && depth > 0) {
            for (src in Extract.iframeSrcs(html)) {
                val nested = Extract.urlJoin(playerUrl, src)
                if (Extract.isIgnored(nested)) continue
                val found = try {
                    resolvePlayer(nested, playerUrl, depth - 1)
                } catch (e: IOException) {
                    continue
                }
                for (s in found) if (s !in streams) streams.add(s)
            }
        }
        return streams
    }

    suspend fun parseChannel(slug: String): Channel = withContext(Dispatchers.Default) {
        val url = Extract.urlJoin(Config.BASE, "$slug.html")
        val html = get(url)
        val title = Extract.channelTitle(html, slug)
        val players = Extract.findPlayers(html, url)
        val labels = Extract.findTabLabels(html)
        if (players.isEmpty()) throw ScrapeException("на странице не найден плеер")

        val streams = LinkedHashMap<String, StreamItem>()
        val playerErrors = ArrayList<String>()
        players.forEachIndexed { i, player ->
            val base = if (labels.size == players.size) labels[i] else "Поток ${i + 1}"
            val urls = try {
                resolvePlayer(player, url)
            } catch (e: IOException) {
                Log.w(TAG, "$slug: плеер $player: ${e.message}")
                playerErrors.add(describe(e))
                return@forEachIndexed
            }
            urls.forEachIndexed { j, s ->
                if (s !in streams) {
                    val label = if (urls.size > 1) "$base ${j + 1}" else base
                    streams[s] = StreamItem(label = label, player = player, url = s)
                }
            }
        }
        if (streams.isEmpty()) {
            throw ScrapeException(
                if (playerErrors.isNotEmpty()) {
                    "плееры не открылись: ${playerErrors.distinct().joinToString("; ")}"
                } else {
                    "плеер найден, но ссылок на поток в нём нет"
                },
            )
        }
        Channel(
            slug = slug,
            title = title,
            page = url,
            streams = streams.values.toList(),
            logo = Extract.findLogo(html, url, slug, title),
        )
    }

    /**
     * Параллельно (4 потока, пауза 0.3 c — как в main() у parser.py).
     * Для каждого канала вызывается onResult: либо channel != null, либо error с причиной.
     */
    suspend fun scrapeAll(
        slugs: List<String>,
        parallelism: Int = 4,
        onResult: (slug: String, channel: Channel?, error: String?) -> Unit,
    ) = coroutineScope {
        val gate = Semaphore(parallelism)
        for (slug in slugs) {
            launch {
                var channel: Channel? = null
                var error: String? = null
                gate.withPermit {
                    delay(300)
                    try {
                        channel = parseChannel(slug)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "$slug: ${e.message}")
                        error = describe(e)
                    }
                }
                onResult(slug, channel, error)
            }
        }
    }

    private class MemoryCookieJar : CookieJar {
        private val jar = CopyOnWriteArrayList<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                jar.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                jar.add(c)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return jar.filter { it.expiresAt > now && it.matches(url) }
        }
    }

    private companion object {
        const val TAG = "TelikScraper"
    }
}
