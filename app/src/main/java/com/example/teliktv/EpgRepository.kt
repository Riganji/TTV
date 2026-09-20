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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 * Телепрограмма с programma-peredach.com: по одной странице на канал и день, только для каналов
 * из EpgConfig.CHANNEL_MAP. Общий XMLTV не качаем — отсюда и скорость, и размер кэша
 * (сотни килобайт вместо десятков мегабайт).
 *
 * Кэш — filesDir/epg.json: slug -> список передач. Время хранится в UTC-миллисекундах,
 * пересчитанное из часового пояса источника, поэтому устройство может быть в любом поясе.
 */
class EpgRepository(context: Context) {

    private val file = File(context.filesDir, "epg.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val zone: ZoneId = ZoneId.of(EpgConfig.SOURCE_TZ)

    private val client = OkHttpClient.Builder()
        // Сайт ставит куку выбранного города и без неё гоняет по редиректам.
        .cookieJar(MemoryCookieJar())
        .followRedirects(true)
        // Часть зеркал рвёт HTTP/2 («stream was reset: PROTOCOL_ERROR»); страницы мелкие,
        // мультиплексирование тут ни к чему.
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", Config.UA)
                    .header("Accept", "text/html,application/xhtml+xml")
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

    /** У части каналов есть городской раздел, у части — нет; пробуем оба адреса. */
    private fun urlsFor(ppSlug: String, day: String): List<String> = listOf(
        "${EpgConfig.BASE}/${EpgConfig.CITY}/kanal_$ppSlug/$day/",
        "${EpgConfig.BASE}/kanal_$ppSlug/$day/",
    )

    private fun fetch(url: String): String? =
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        } catch (e: IOException) {
            Log.w(TAG, "$url: ${e.message}")
            null
        }

    /** Расписание одного канала на все дни из EpgConfig.DAYS. Пусто — ничего не нашли. */
    suspend fun fetchOne(slug: String, today: LocalDate): List<Programme> {
        val ppSlug = EpgConfig.CHANNEL_MAP[slug] ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val all = ArrayList<Programme>()
            EpgConfig.DAYS.forEachIndexed { offset, day ->
                val date = today.plusDays(offset.toLong())
                val programmes = runInterruptible {
                    urlsFor(ppSlug, day)
                        .asSequence()
                        .mapNotNull { fetch(it) }
                        .map { Peredach.parse(it, date, zone) }
                        .firstOrNull { it.isNotEmpty() }
                        .orEmpty()
                }
                all.addAll(programmes)
            }
            // Страницы соседних дней перекрываются ночным блоком — убираем дубли.
            all.distinctBy { it.start to it.title }.sortedBy { it.start }
        }
    }

    /**
     * Все каналы из маппинга: 4 параллельно, пауза 200 мс. onResult вызывается по мере
     * готовности — список в UI наполняется постепенно, а не одним рывком в конце.
     */
    suspend fun fetchAll(
        today: LocalDate,
        parallelism: Int = 4,
        onResult: suspend (slug: String, programmes: List<Programme>) -> Unit,
    ) = coroutineScope {
        val gate = Semaphore(parallelism)
        for (slug in EpgConfig.CHANNEL_MAP.keys) {
            launch {
                val programmes = gate.withPermit {
                    delay(200)
                    try {
                        fetchOne(slug, today)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "$slug: ${e.message}")
                        emptyList()
                    }
                }
                onResult(slug, programmes)
            }
        }
    }

    /** Полное обновление. Бросает IOException, если не получилось ни по одному каналу. */
    suspend fun download(
        now: Long,
        onProgress: suspend (String, List<Programme>) -> Unit = { _, _ -> },
    ): EpgCache {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val result = ConcurrentHashMap<String, List<Programme>>()
        fetchAll(today) { slug, programmes ->
            if (programmes.isNotEmpty()) {
                result[slug] = programmes
                onProgress(slug, programmes)
            }
        }
        if (result.isEmpty()) {
            throw IOException("${Extract.netloc(EpgConfig.BASE)}: не ответил или изменилась вёрстка")
        }
        return EpgCache(updatedAt = now, source = EpgConfig.BASE, programmes = HashMap(result))
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
