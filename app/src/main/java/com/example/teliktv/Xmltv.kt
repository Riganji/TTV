package com.example.teliktv

import java.io.Reader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Телепрограмма, привязанная к нашим каналам (ключ — slug). */
data class EpgData(
    val bySlug: Map<String, List<Programme>> = emptyMap(),
    val icons: Map<String, String> = emptyMap(),
    val updatedAt: Long = 0L,
    val source: String = "",
)

/** Хелперы для показа передач. Списки передач отсортированы по началу. */
object Epg {
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun time(ms: Long): String = HHMM.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

    fun current(list: List<Programme>?, now: Long): Programme? =
        list?.firstOrNull { it.start <= now && now < it.stop }

    fun upcoming(list: List<Programme>?, now: Long, count: Int): List<Programme> =
        list?.filter { it.start > now }?.take(count).orEmpty()

    fun progress(p: Programme, now: Long): Float =
        ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1L)).coerceIn(0f, 1f)
}

/**
 * Сопоставляет каналы из XMLTV с нашими (slug -> название). Сначала точное совпадение
 * «нормализованных» названий/ids, затем «мягкое» (без hd/тв/канал и т.п.); если мягкий ключ
 * подходит сразу нескольким нашим каналам — он считается неоднозначным и пропускается.
 */
class EpgMatcher(channels: List<Pair<String, String>>, aliases: Map<String, List<String>> = emptyMap()) {
    private val exact = HashMap<String, String>()
    private val relaxed = HashMap<String, String?>()

    init {
        for ((slug, title) in channels) {
            val names = listOf(title, slug) + (aliases[slug] ?: emptyList())
            for (n in names) {
                val e = Extract.norm(n)
                if (e.isNotEmpty()) exact.putIfAbsent(e, slug)
                val r = relaxedNorm(n)
                if (r.isNotEmpty()) {
                    if (relaxed.containsKey(r) && relaxed[r] != slug) relaxed[r] = null else relaxed[r] = slug
                }
            }
        }
    }

    fun resolve(id: String, names: List<String>): String? {
        val candidates = names + listOf(id, id.substringBefore('.'))
        for (c in candidates) exact[Extract.norm(c)]?.let { return it }
        for (c in candidates) relaxed[relaxedNorm(c)]?.let { return it }
        return null
    }

    companion object {
        private val NOISE = setOf("hd", "fhd", "sd", "uhd", "hevc", "канал", "телеканал", "тв", "tv", "ru")
        private val SPLIT_RE = Regex("[^\\p{L}\\p{N}]+")

        fun relaxedNorm(s: String): String =
            s.lowercase().replace('ё', 'е').split(SPLIT_RE).filter { it.isNotEmpty() && it !in NOISE }.joinToString("")
    }
}

/** Потоковый разбор XMLTV: в памяти остаются только передачи наших каналов в нужном окне времени. */
object Xmltv {
    class Parsed(val programmes: Map<String, List<Programme>>, val icons: Map<String, String>)

    private val ELEMENT_RE = Regex("""<(channel|programme)\b([^>]*?)(?:/>|>(.*?)</\1>)""", RegexOption.DOT_MATCHES_ALL)
    private val DISPLAY_NAME_RE = Regex("""<display-name\b[^>]*>(.*?)</display-name>""", RegexOption.DOT_MATCHES_ALL)
    private val ICON_RE = Regex("""<icon\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""")
    private val TITLE_RE = Regex("""<title\b[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
    private val DESC_RE = Regex("""<desc\b[^>]*>(.*?)</desc>""", RegexOption.DOT_MATCHES_ALL)
    private val TIME_RE = Regex("""^\s*(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*(?:([+-])(\d{2}):?(\d{2}))?""")
    private val ENTITY_RE = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

    fun parse(reader: Reader, matcher: EpgMatcher, fromMs: Long, toMs: Long): Parsed {
        val idToSlug = HashMap<String, String?>()
        val slugOwner = HashMap<String, String>()
        val progs = HashMap<String, MutableList<Programme>>()
        val icons = HashMap<String, String>()

        fun handle(m: MatchResult) {
            val attrs = Extract.attributes(m.groupValues[2])
            val body = m.groups[3]?.value ?: ""
            if (m.groupValues[1] == "channel") {
                val id = attrs["id"] ?: return
                val names = DISPLAY_NAME_RE.findAll(body)
                    .map { unescape(it.groupValues[1]).trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                var slug = matcher.resolve(id, names)
                if (slug != null) {
                    val owner = slugOwner.putIfAbsent(slug, id)
                    if (owner != null && owner != id) slug = null   // второй XMLTV-канал на тот же наш — игнорируем
                }
                idToSlug[id] = slug
                if (slug != null) {
                    ICON_RE.find(body)?.let { icons.putIfAbsent(slug, unescape(it.groupValues[1]).trim()) }
                }
            } else {
                val slug = idToSlug[attrs["channel"] ?: return] ?: return
                val start = parseTime(attrs["start"]) ?: return
                val stop = parseTime(attrs["stop"]) ?: return
                if (stop < fromMs || start > toMs) return
                val title = TITLE_RE.find(body)?.let { unescape(it.groupValues[1]).trim() } ?: return
                val desc = DESC_RE.find(body)?.let { unescape(it.groupValues[1]).trim() }?.takeIf { it.isNotEmpty() }
                progs.getOrPut(slug) { ArrayList() }.add(Programme(start, stop, title, desc))
            }
        }

        val buf = StringBuilder()
        val chunk = CharArray(64 * 1024)
        while (true) {
            val n = reader.read(chunk)
            if (n < 0) break
            buf.append(chunk, 0, n)
            var consumed = 0
            for (m in ELEMENT_RE.findAll(buf)) {
                handle(m)
                consumed = m.range.last + 1
            }
            if (consumed > 0) {
                buf.delete(0, consumed)
            } else if (buf.length > 1_000_000) {
                buf.delete(0, buf.length - 100_000)   // защита от мусора без нужных тегов
            }
        }
        return Parsed(
            programmes = progs.mapValues { (_, v) -> v.sortedBy { it.start } },
            icons = icons,
        )
    }

    /** XMLTV-время: 20260920201500 +0300 (смещение необязательно, тогда UTC). */
    fun parseTime(s: String?): Long? {
        val m = TIME_RE.find(s ?: return null) ?: return null
        val g = m.groupValues
        return try {
            val dt = LocalDateTime.of(
                g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt(),
            )
            val offset = if (g[7].isEmpty()) {
                ZoneOffset.UTC
            } else {
                val sign = if (g[7] == "-") -1 else 1
                ZoneOffset.ofTotalSeconds(sign * (g[8].toInt() * 3600 + g[9].toInt() * 60))
            }
            dt.toInstant(offset).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    fun unescape(s: String): String {
        var t = s
        if (t.contains("<![CDATA[")) t = t.replace("<![CDATA[", "").replace("]]>", "")
        if (!t.contains('&')) return t
        return ENTITY_RE.replace(t) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> try {
                    val code = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                    String(Character.toChars(code))
                } catch (ex: Exception) {
                    m.value
                }
            }
        }
    }
}
