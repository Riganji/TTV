package com.example.teliktv

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Base64

/**
 * Чистая логика разбора страниц (без сети и без Android) — порт функций из parser.py.
 * Вынесена отдельно, чтобы её можно было гонять обычными JVM-тестами.
 */
object Extract {

    private val STREAM_RE = Regex(
        """(?:https?:)?//[^\s"'<>\\()\[\]{}]+?\.(?:m3u8|mpd)(?:\?[^\s"'<>\\()\[\]{}]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val ENCODED_URL_RE = Regex("""https?%3A%2F%2F[^\s"'<>&\\]+""", RegexOption.IGNORE_CASE)
    private val B64_RE = Regex("""["'`]([A-Za-z0-9+/]{32,}={0,2})["'`]""")
    private val IFRAME_RE = Regex(
        """<iframe\b[^>]*?\b(?:data-src|src)\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TAB_RE = Regex(
        """<a\b[^>]*href=["']#(?:vkl|video\d*)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val HOST_URL_RE = Regex(
        """https?://(?:[\w-]+\.)*${Regex.escape(Config.PLAYER_HOST)}[^\s"'<>\\]*""",
        RegexOption.IGNORE_CASE,
    )
    private val DIRECT_STREAM_RE = Regex("""\.(m3u8|mpd)(\?|$)""", RegexOption.IGNORE_CASE)
    private val TITLE_RE = Regex("""<title>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TITLE_CUT_RE = Regex("""\s+прямой эфир""", RegexOption.IGNORE_CASE)
    private val TAG_RE = Regex("""<[^>]+>""")
    private val WS_RE = Regex("""\s+""")

    private val SCHEME_RE = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*:""")
    private val ORIGIN_RE = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*)://([^/?#]*)""")
    private val HEADER_CHARSET_RE = Regex("""charset\s*=\s*["']?([\w\-]+)""", RegexOption.IGNORE_CASE)
    private val META_CHARSET_RE = Regex("""<meta[^>]+charset\s*=\s*["']?([\w\-]+)""", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- URL helpers

    fun normalizeText(text: String): String =
        text.replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

    /** Аналог urllib.parse.urljoin, но терпимый к «грязным» ссылкам (пробелы, {} и т.п.). */
    fun urlJoin(base: String, ref: String): String {
        val r = ref.trim()
        if (r.isEmpty()) return base
        if (SCHEME_RE.containsMatchIn(r)) return r
        return try {
            URI(base).resolve(URI(r)).toString()
        } catch (e: Exception) {
            manualJoin(base, r)
        }
    }

    private fun manualJoin(base: String, r: String): String {
        val m = ORIGIN_RE.find(base) ?: return r
        val scheme = m.groupValues[1]
        val origin = m.value
        return when {
            r.startsWith("//") -> "$scheme:$r"
            r.startsWith("/") -> origin + r
            else -> {
                val noQuery = base.substringBefore('#').substringBefore('?')
                var dir = noQuery.substring(0, noQuery.lastIndexOf('/') + 1)
                if (dir.length <= origin.length) dir = "$origin/"
                dir + r
            }
        }
    }

    fun netloc(url: String): String = ORIGIN_RE.find(url)?.groupValues?.get(2)?.lowercase() ?: ""

    /** "scheme://host/" — так же строился Referer в write_m3u(). */
    fun origin(url: String): String {
        val m = ORIGIN_RE.find(url) ?: return url
        return "${m.groupValues[1]}://${m.groupValues[2]}/"
    }

    fun isIgnored(url: String): Boolean {
        val host = netloc(url)
        return Config.IGNORE_HOSTS.any { host.contains(it) }
    }

    fun isDirectStream(url: String): Boolean = DIRECT_STREAM_RE.containsMatchIn(url)

    fun isDash(url: String): Boolean = url.substringBefore('?').lowercase().endsWith(".mpd")

    private fun unquote(s: String): String =
        try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            s
        }

    // ---------------------------------------------------------------- потоки

    fun extractStreams(text: String, baseUrl: String): List<String> {
        val t = normalizeText(text)
        val found = LinkedHashSet<String>()

        fun add(raw: String) {
            var u = raw.trim()
            if (u.startsWith("//")) u = "https:$u"
            found.add(urlJoin(baseUrl, u))
        }

        STREAM_RE.findAll(t).forEach { add(it.value) }
        ENCODED_URL_RE.findAll(t).forEach { m ->
            STREAM_RE.findAll(unquote(m.value)).forEach { add(it.value) }
        }
        B64_RE.findAll(t).forEach { m ->
            val s = m.groupValues[1]
            val padded = s + "=".repeat((4 - s.length % 4) % 4)
            val dec = try {
                String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                return@forEach
            }
            STREAM_RE.findAll(normalizeText(dec)).forEach { add(it.value) }
        }
        return found.toList()
    }

    /** Значения src/data-src у всех iframe (как есть, без urljoin). */
    fun iframeSrcs(html: String): List<String> =
        IFRAME_RE.findAll(normalizeText(html)).map { it.groupValues[1] }.toList()

    fun findPlayers(html: String, pageUrl: String): List<String> {
        val h = normalizeText(html)
        val pageHost = netloc(pageUrl)
        val players = LinkedHashSet<String>()

        fun add(raw: String) {
            var u = raw
            if (u.startsWith("//")) u = "https:$u"
            u = urlJoin(pageUrl, u)
            if (!u.startsWith("http") || isIgnored(u)) return
            if (netloc(u) == pageHost && !(u.endsWith(".php") || u.endsWith(".m3u8"))) return
            players.add(u)
        }

        IFRAME_RE.findAll(h).forEach { add(it.groupValues[1]) }
        HOST_URL_RE.findAll(h).forEach { add(it.value) }
        return players.toList()
    }

    fun findTabLabels(html: String): List<String> {
        val labels = ArrayList<String>()
        TAB_RE.findAll(html).forEach { m ->
            val label = TAG_RE.replace(m.groupValues[1], "").trim()
            labels.add(label.ifEmpty { "Поток ${labels.size + 1}" })
        }
        return labels
    }

    fun channelTitle(html: String, fallback: String): String {
        val m = TITLE_RE.find(html) ?: return fallback
        var t = WS_RE.replace(m.groupValues[1], " ").trim()
        t = TITLE_CUT_RE.split(t)[0]
        return t.trim('«', '»', ' ').ifEmpty { fallback }
    }

    // ---------------------------------------------------------------- логотип

    private val OG_IMAGE_RE = Regex(
        """<meta[^>]*\bproperty\s*=\s*["']og:image["'][^>]*\bcontent\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val OG_IMAGE_RE2 = Regex(
        """<meta[^>]*\bcontent\s*=\s*["']([^"']+)["'][^>]*\bproperty\s*=\s*["']og:image["']""",
        RegexOption.IGNORE_CASE,
    )
    private val LOGO_IMG_RE = Regex(
        """<img[^>]*\bclass\s*=\s*["'][^"']*\blogo\b[^"']*["'][^>]*\bsrc\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val LOGO_IMG_RE2 = Regex(
        """<img[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*\bclass\s*=\s*["'][^"']*\blogo\b[^"']*["']""",
        RegexOption.IGNORE_CASE,
    )

    fun channelLogo(html: String, baseUrl: String): String? {
        val h = normalizeText(html)
        for (re in listOf(OG_IMAGE_RE, OG_IMAGE_RE2, LOGO_IMG_RE, LOGO_IMG_RE2)) {
            val m = re.find(h) ?: continue
            val v = m.groupValues[1].trim()
            if (v.isEmpty()) continue
            val u = urlJoin(baseUrl, v)
            if (u.startsWith("http")) return u
        }
        return null
    }

    // ---------------------------------------------------------------- EPG со страницы канала

    /** Контейнеры, в которых обычно лежит сетка вещания. */
    private val PROGRAM_CONTAINER_RE = Regex(
        """<(?:div|section|ul|ol|table)\b[^>]*\bclass\s*=\s*["'][^"']*""" +
            """(?:epg|program|schedule|raspisanie|tv-?program|broadcast|efir|tv-?guide)""" +
            """[^"']*["'][^>]*>(.*?)</(?:div|section|ul|ol|table)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** "12:00 Название", "12:00 - Название", "12:00: Название", "12:00 — Название". */
    private val TIME_TITLE_RE = Regex(
        """(?<![\d:])(\d{1,2}):(\d{2})(?!\d)\s*[-–—:]?\s*([A-Za-zА-Яа-яЁё«"'][^<>\n\r]{1,160})""",
        RegexOption.MULTILINE,
    )

    /**
     * Пытается вытащить программу передач со страницы канала.
     * Сначала ищем тематические контейнеры (class~epg/program/schedule/…);
     * если там меньше 3 строк — сканируем всю страницу. Возвращает список, отсортированный по времени.
     */
    fun extractPrograms(html: String): List<Program> {
        val h = normalizeText(html)

        val fromContainers = LinkedHashMap<String, Program>()
        PROGRAM_CONTAINER_RE.findAll(h).forEach { m ->
            collectPrograms(m.groupValues[1], fromContainers)
        }
        if (fromContainers.size >= 3) return fromContainers.values.sortedBy { it.time }

        val fromPage = LinkedHashMap<String, Program>()
        collectPrograms(h, fromPage)
        return fromPage.values.sortedBy { it.time }
    }

    private fun collectPrograms(chunk: String, out: MutableMap<String, Program>) {
        TIME_TITLE_RE.findAll(chunk).forEach { m ->
            val hh = m.groupValues[1].toIntOrNull() ?: return@forEach
            val mm = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (hh !in 0..23 || mm !in 0..59) return@forEach

            var title = m.groupValues[3]
                .replace(TAG_RE, " ")
                .replace(WS_RE, " ")
                .trim()
                .trim('-', '–', '—', ':', '.', ',', '"', '\'', '«', '»', ' ')

            if (title.length < 3) return@forEach
            val low = title.lowercase()
            if (low == "прямой эфир" || low == "смотреть онлайн" || low == "онлайн") return@forEach
            if (title.length > 140) title = title.take(140).trim()

            val time = "%02d:%02d".format(hh, mm)
            out.putIfAbsent(time, Program(time = time, title = title))
        }
    }

    // ---------------------------------------------------------------- кодировка

    /** Кодировка: из заголовка → из <meta> → UTF-8 (requests в parser.py делал похожий fallback). */
    fun decodeBody(bytes: ByteArray, contentType: String?): String {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val fromHeader = contentType?.let { HEADER_CHARSET_RE.find(it)?.groupValues?.get(1) }
        val fromMeta = META_CHARSET_RE.find(head)?.groupValues?.get(1)
        val headerIsReliable = fromHeader != null && !fromHeader.equals("iso-8859-1", ignoreCase = true)
        val name = if (headerIsReliable) fromHeader else fromMeta
        val cs = try {
            if (name != null) Charset.forName(name) else Charsets.UTF_8
        } catch (e: Exception) {
            Charsets.UTF_8
        }
        return String(bytes, cs)
    }
}