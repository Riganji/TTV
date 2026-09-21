package com.example.teliktv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Serializable
data class EpgCache(
    val updatedAt: Long,
    val source: String,
    val programmes: Map<String, List<Programme>>,
    val icons: Map<String, String> = emptyMap(),
)

/**
 * Телепрограмма и иконки с https://epg.iptvx.one — по одному JSON-запросу на канал.
 *
 * Кэш — filesDir/epg.json: slug -> список передач и slug -> URL иконки.
 * Время хранится в UTC-миллисекундах, поэтому устройство может быть в любом поясе.
 */
class EpgRepository(context: Context) {

    private val file = File(context.filesDir, "epg.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        // CookieJar оставлен на всякий случай — iptvx.one может ставить куки города/языка.
        .cookieJar(MemoryCookieJar())
        .followRedirects(true)
        // Часть зеркал рвёт HTTP/2; запросы мелкие, мультиплексирование не нужно.
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", Config.UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "ru,en;q=0.8")
                    .header("Referer", "${EpgConfig.BASE}/")
                    .build(),
            )
        }
        .build()

    // ------------------------------------------------------------------ кэш

    fun load(): EpgCache? =
        try {
            if (file.exists()) json.decodeFromString(EpgCache.serializer(), file.readText()) else null
        } catch (e: Exception) {
            null
        }

    fun save(cache: EpgCache) {
        try {
            file.writeText(json.encodeToString(EpgCache.serializer(), cache))
        } catch (e: Exception) {
            // кэш — best effort
        }
    }

    // ------------------------------------------------------------------ загрузка

    /**
     * Расписание одного канала по его ID на epg.iptvx.one.
     * @return пары «список передач, URL иконки»; иконка null, если запрос совсем не удался.
     */
    suspend fun fetchOne(epgId: String): Pair<List<Programme>, String?> =
        withContext(Dispatchers.IO) {
            val url = "${EpgConfig.BASE}/api/id/$epgId.json"
            val fallbackIcon = "${IptvxEpg.PICON_BASE}/$epgId.png"
            runInterruptible {
                try {
                    client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) return@use emptyList<Programme>() to fallbackIcon
                        val body = r.body?.string()
                        if (body.isNullOrBlank()) return@use emptyList<Programme>() to fallbackIcon
                        val (progs, icon) = IptvxEpg.parse(body)
                        progs to (icon ?: fallbackIcon)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "$url: ${e.message}")
                    emptyList<Programme>() to null
                }
            }
        }

    /**
     * Все каналы из маппинга: parallelism параллельно, пауза 200 мс. onResult вызывается
     * по мере готовности — список в UI наполняется постепенно, а не одним рывком в конце.
     */
    suspend fun fetchAll(
        parallelism: Int = 4,
        onResult: suspend (slug: String, programmes: List<Programme>, icon: String?) -> Unit,
    ) = coroutineScope {
        val gate = Semaphore(parallelism)
        for ((slug, epgId) in EpgConfig.CHANNEL_MAP) {
            launch {
                val (programmes, icon) = gate.withPermit {
                    delay(200)
                    try {
                        fetchOne(epgId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "$slug: ${e.message}")
                        emptyList<Programme>() to null
                    }
                }
                onResult(slug, programmes, icon)
            }
        }
    }

    /** Полное обновление. Бросает IOException, если не получилось ни по одному каналу. */
    suspend fun download(
        now: Long,
        onProgress: suspend (String, List<Programme>) -> Unit = { _, _ -> },
    ): EpgCache {
        val result = ConcurrentHashMap<String, List<Programme>>()
        val icons = ConcurrentHashMap<String, String>()
        fetchAll { slug, programmes, icon ->
            if (programmes.isNotEmpty()) {
                result[slug] = programmes
                onProgress(slug, programmes)
            }
            if (icon != null) icons[slug] = icon
        }
        if (result.isEmpty() && icons.isEmpty()) {
            throw IOException("${EpgConfig.BASE}: не ответил или изменилась структура")
        }
        return EpgCache(
            updatedAt = now,
            source = EpgConfig.BASE,
            programmes = HashMap(result),
            icons = HashMap(icons),
        )
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
        const val TAG = "EpgRepository"
    }
}