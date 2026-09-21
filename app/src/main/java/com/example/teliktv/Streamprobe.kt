package com.example.teliktv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Что на самом деле отдаёт ссылка потока. Нужна для понятного сообщения, когда ExoPlayer пишет
 * PARSING_MANIFEST_MALFORMED: по одному коду не видно, пришла ли HTML-заглушка, пустой ответ
 * или действительно битый плейлист.
 */
object StreamProbe {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Короткое описание ответа сервера на ссылку потока (с теми же заголовками, что у плеера). */
    suspend fun describe(s: StreamItem): String =
        try {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    val req = Request.Builder()
                        .url(s.url)
                        .header("User-Agent", Config.UA)
                        .header("Referer", s.referer)
                        .header("Accept-Encoding", "identity")
                        .build()
                    client.newCall(req).execute().use { r ->
                        if (r.code >= 400) {
                            "сервер ответил HTTP ${r.code}"
                        } else {
                            summarize(r.peekBody(2048).string().trim(), Extract.isDash(s.url))
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "сервер не ответил (${e.javaClass.simpleName})"
        }

    /** Разбор начала тела ответа; expectDash — ссылка ведёт на .mpd, иначе ждём HLS. */
    internal fun summarize(head: String, expectDash: Boolean): String = when {
        head.isEmpty() -> "пустой ответ"
        head.startsWith("#EXTM3U") ->
            if (expectDash) "вместо DASH пришёл HLS-плейлист" else "плейлист получен, но не разобран"
        head.contains("<MPD", ignoreCase = true) ->
            if (expectDash) "манифест DASH получен, но не разобран" else "вместо HLS пришёл манифест DASH"
        head.startsWith("<") -> "вместо плейлиста пришла HTML/XML-разметка"
        else -> "не плейлист: «${head.take(40).replace(Regex("\\s+"), " ")}»"
    }
}