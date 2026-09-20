package com.example.teliktv

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@Serializable
data class EpgCache(
    val updatedAt: Long,
    val source: String,
    val programmes: Map<String, List<Programme>>,
    val icons: Map<String, String>,
)

/** Загрузка XMLTV (в т.ч. .gz), потоковый разбор и кэш в filesDir/epg.json. */
class EpgRepository(context: Context) {
    private val file = File(context.filesDir, "epg.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", Config.UA).build())
        }
        .build()

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

    /** Пробует Config.EPG_URLS по очереди; бросает IOException с причиной, если ни один не подошёл. */
    suspend fun download(channels: List<Pair<String, String>>, now: Long): EpgCache {
        val matcher = EpgMatcher(channels, Config.EPG_ALIASES)
        var lastError: String? = null
        for (url in Config.EPG_URLS) {
            try {
                val parsed = runInterruptible(Dispatchers.IO) {
                    fetchAndParse(url, matcher, now - Config.EPG_PAST_MS, now + Config.EPG_FUTURE_MS)
                }
                if (parsed.programmes.isEmpty()) {
                    lastError = "в источнике нет наших каналов (${hostOf(url)})"
                    continue
                }
                return EpgCache(now, url, parsed.programmes, parsed.icons)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = "${hostOf(url)}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException(lastError ?: "не задан ни один источник (Config.EPG_URLS)")
    }

    private fun fetchAndParse(url: String, matcher: EpgMatcher, from: Long, to: Long): Xmltv.Parsed {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("некорректный адрес")
        }
        return client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("пустой ответ")
            val raw = BufferedInputStream(body.byteStream())
            raw.mark(2)
            val b0 = raw.read()
            val b1 = raw.read()
            raw.reset()
            val stream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw) else raw
            InputStreamReader(stream, Charsets.UTF_8).use { Xmltv.parse(it, matcher, from, to) }
        }
    }

    private fun hostOf(url: String): String = Extract.netloc(url).ifEmpty { url }
}
