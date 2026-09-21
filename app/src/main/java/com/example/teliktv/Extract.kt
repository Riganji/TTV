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

    // ---------------------------------------------------------------- парсинг

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
            val label = cleanLabel(m.groupValues[1])
            labels.add(label.ifEmpty { "Поток ${labels.size + 1}" })
        }
        return labels
    }

    // ---------------------------------------------------------------- названия потоков

    private val LABEL_TRIM_CHARS = charArrayOf('«', '»', '"', '\'', '|', '·', '•', ':', ';', ',', '.', '-', '–', '—', ' ')

    /**
     * Хвостовой номер вместе с отделяющими его знаками: «Плеер 1», «Плеер №2», «Плеер - 3».
     * Разделитель обязателен, иначе номер отрезался бы и от слитных названий вроде «Авто24».
     */
    private val LABEL_NUM_TAIL_RE = Regex("""[\s\-–—:.,#№()\[\]]+\d+\s*[)\]]?\s*$""")

    /** Текст ярлыка без тегов, лишних пробелов и обрамляющей пунктуации. */
    fun cleanLabel(raw: String): String =
        WS_RE.replace(TAG_RE.replace(raw, " "), " ").trim().trim(*LABEL_TRIM_CHARS).let { WS_RE.replace(it, " ").trim() }

    /**
     * Основа названия — без номера в конце: «Плеер 2» -> «Плеер».
     * Нумерацию потом расставляет [uniqueLabels] один раз, без «Плеер 2 3».
     */
    fun labelBase(raw: String): String {
        val t = cleanLabel(raw)
        // Номеров в хвосте может быть несколько — «Плеер 1 2» из кэша прошлых версий.
        var stripped = t
        var guard = 0
        while (guard++ < 3) {
            val next = LABEL_NUM_TAIL_RE.replace(stripped, "").trim(*LABEL_TRIM_CHARS).trim()
            if (next.isEmpty() || next == stripped) break
            stripped = next
        }
        return stripped.ifEmpty { t }
    }

    /**
     * Единственная нумерация в названиях: уникальная основа остаётся как есть,
     * повторяющаяся получает номер по порядку — 1..n.
     *
     * Если основа сама заканчивается цифрой (часовой пояс «+2», «Дубль 2»), номер берётся
     * в скобки: «+2 (1)», а не «+2 1» — иначе на экране это читается как «+21».
     */
    fun uniqueLabels(bases: List<String>): List<String> {
        val names = bases.map { it.ifEmpty { "Поток" } }
        val counts = names.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return names.map { n ->
            if ((counts[n] ?: 0) <= 1) {
                n
            } else {
                val i = (seen[n] ?: 0) + 1
                seen[n] = i
                if (n.lastOrNull()?.isDigit() == true) "$n ($i)" else "$n $i"
            }
        }
    }

    /** Приводит названия уже сохранённых потоков к тому же виду (кэш от прошлых версий). */
    fun cleanStreams(streams: List<StreamItem>): List<StreamItem> {
        if (streams.isEmpty()) return streams
        val labels = uniqueLabels(streams.map { labelBase(it.label) })
        return streams.mapIndexed { i, s -> if (s.label == labels[i]) s else s.copy(label = labels[i]) }
    }

    fun channelTitle(html: String, fallback: String): String {
        val m = TITLE_RE.find(html) ?: return fallback
        var t = WS_RE.replace(m.groupValues[1], " ").trim()
        t = TITLE_CUT_RE.split(t)[0]
        return t.trim('«', '»', ' ').ifEmpty { fallback }
    }

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

    // ---------------------------------------------------------------- логотипы

    private val ATTR_RE = Regex("([\\w:-]+)\\s*=\\s*([\"'])(.*?)\\2", RegexOption.DOT_MATCHES_ALL)
    private val META_TAG_RE = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val IMG_TAG_RE = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val LINK_TAG_RE = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** Ключ для сравнения названий: буквы и цифры в нижнем регистре, ё -> е. */
    fun norm(s: String): String = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    /** Атрибуты одного тега; имена в нижнем регистре. */
    fun attributes(tag: String): Map<String, String> {
        val out = HashMap<String, String>()
        ATTR_RE.findAll(tag).forEach { out.putIfAbsent(it.groupValues[1].lowercase(), it.groupValues[3]) }
        return out
    }

    /**
     * Ищет логотип канала на странице. Порядок доверия:
     * 1) картинка, в адресе которой есть slug канала; 2) картинка, у которой alt/title содержит название;
     * 3) og:image; 4) картинка со словом «logo» в адресе/классе/alt; 5) link rel=image_src.
     * SVG, favicon и data: пропускаются (Coil не рисует SVG без доп. модуля).
     */
    fun findLogo(html: String, pageUrl: String, slug: String, title: String): String? {
        val h = normalizeText(html)
        val titleKey = norm(title)
        val slugKey = slug.lowercase()
        val imgs = IMG_TAG_RE.findAll(h).map { attributes(it.value) }.toList()

        fun usable(raw: String?): String? {
            val r = raw?.trim() ?: return null
            if (r.isEmpty() || r.startsWith("data:")) return null
            val u = urlJoin(pageUrl, if (r.startsWith("//")) "https:$r" else r)
            val low = u.lowercase()
            if (!low.startsWith("http") || isIgnored(u)) return null
            if (low.contains("favicon") || low.substringBefore('?').endsWith(".svg")) return null
            return u
        }

        fun srcOf(a: Map<String, String>): String? {
            val src = a["src"]?.trim()
            if (!src.isNullOrEmpty() && !src.startsWith("data:")) return src
            return a["data-src"] ?: a["data-lazy-src"] ?: a["data-original"]
        }

        for (a in imgs) {
            val u = usable(srcOf(a)) ?: continue
            if (u.lowercase().contains(slugKey)) return u
        }
        if (titleKey.isNotEmpty()) {
            for (a in imgs) {
                val u = usable(srcOf(a)) ?: continue
                if (norm((a["alt"] ?: "") + " " + (a["title"] ?: "")).contains(titleKey)) return u
            }
        }
        for (m in META_TAG_RE.findAll(h)) {
            val a = attributes(m.value)
            val prop = (a["property"] ?: a["name"])?.lowercase()
            if (prop == "og:image" || prop == "twitter:image") usable(a["content"])?.let { return it }
        }
        for (a in imgs) {
            val u = usable(srcOf(a)) ?: continue
            val hinted = listOf(a["class"], a["id"], a["alt"], u).any { it?.contains("logo", ignoreCase = true) == true }
            if (hinted) return u
        }
        for (m in LINK_TAG_RE.findAll(h)) {
            val a = attributes(m.value)
            if (a["rel"]?.lowercase() == "image_src") usable(a["href"])?.let { return it }
        }
        return null
    }

    /** Адреса, которые встречаются у threshold+ каналов сразу, — это общий баннер сайта, а не логотип. */
    fun sharedLogoUrls(logos: Collection<String?>, threshold: Int = 3): Set<String> =
        logos.filterNotNull().groupingBy { it }.eachCount().filterValues { it >= threshold }.keys
}
