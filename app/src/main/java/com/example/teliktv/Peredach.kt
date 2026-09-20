package com.example.teliktv

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Разбор страницы расписания programma-peredach.com. Чистая логика, без сети и Android —
 * гоняется обычными JVM-тестами (PeredachTest).
 *
 * Разметка сайта меняется, поэтому НЕ опираемся на классы и теги: приводим страницу к тексту
 * и ищем строки вида «HH:MM», а всё до следующего времени считаем названием и описанием.
 * Так парсер переживает смену вёрстки, пока время и название стоят рядом.
 */
object Peredach {

    private val SCRIPT_RE = Regex("""<(script|style|noscript)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG_RE = Regex("""<[^>]+>""")
    private val TIME_LINE_RE = Regex("""^(\d{1,2}):(\d{2})$""")
    /** Время, слипшееся с названием в одной строке: «06:00 Доброе утро». */
    private val TIME_PREFIX_RE = Regex("""^(\d{1,2}):(\d{2})\s*[-–—|.]?\s+(\S.*)$""")

    /** Строки-мусор из шапки/подвала, которые случайно попадают между передачами. */
    private val JUNK = listOf(
        "программа передач", "телепрограмма", "на сегодня", "на завтра", "на вчера",
        "все каналы", "смотреть онлайн", "реклама", "поделиться", "сейчас в эфире",
    )

    /** Минимум передач, ниже которого считаем, что расписания на странице нет. */
    private const val MIN_PROGRAMMES = 4

    /**
     * @param html      страница канала за один день
     * @param day       календарная дата этого дня в часовом поясе источника
     * @param sourceTz  часовой пояс источника (EpgConfig.SOURCE_TZ)
     * @return передачи с временем в миллисекундах Unix; пусто, если расписания не нашлось
     */
    fun parse(html: String, day: LocalDate, sourceTz: ZoneId): List<Programme> {
        val lines = toLines(html)

        // Собираем пары (минуты от начала суток, название, описание)
        data class Raw(val minutes: Int, val title: String, val desc: String?)

        val raw = ArrayList<Raw>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            var minutes: Int? = null
            var inlineTitle: String? = null

            TIME_LINE_RE.find(line)?.let { m ->
                minutes = toMinutes(m.groupValues[1], m.groupValues[2])
            } ?: TIME_PREFIX_RE.find(line)?.let { m ->
                minutes = toMinutes(m.groupValues[1], m.groupValues[2])
                inlineTitle = m.groupValues[3].trim()
            }

            val mins = minutes
            if (mins == null) {
                i++
                continue
            }

            // Название: либо из той же строки, либо следующая осмысленная строка.
            val body = ArrayList<String>()
            inlineTitle?.let { body.add(it) }
            var j = i + 1
            while (j < lines.size && body.size < 3) {
                val next = lines[j]
                if (TIME_LINE_RE.matches(next) || TIME_PREFIX_RE.matches(next)) break
                if (!isJunk(next)) body.add(next)
                j++
            }
            i = if (inlineTitle != null) i + 1 else j

            val title = body.firstOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            val desc = body.drop(1).joinToString(" ").trim().takeIf { it.length > 3 }
            raw.add(Raw(mins, title, desc))
        }

        if (raw.size < MIN_PROGRAMMES) return emptyList()

        // Время на странице идёт по возрастанию; «откат» назад — это уже следующие сутки
        // (ночной блок эфирного дня). Считаем абсолютные минуты от начала дня.
        val zone = sourceTz
        val absolute = ArrayList<Pair<Long, Raw>>(raw.size)
        var dayShift = 0
        var prev = -1
        for (r in raw) {
            if (prev >= 0 && r.minutes < prev - 60) dayShift++   // -60: защита от мелких скачков
            prev = r.minutes
            val start = day.plusDays(dayShift.toLong())
                .atTime(LocalTime.of(r.minutes / 60, r.minutes % 60))
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            absolute.add(start to r)
        }

        // Конец передачи = начало следующей; у последней — плюс час.
        return absolute.mapIndexed { idx, (start, r) ->
            val stop = absolute.getOrNull(idx + 1)?.first ?: (start + 60 * 60 * 1000L)
            Programme(start = start, stop = stop, title = r.title, desc = r.desc)
        }.filter { it.stop > it.start }
    }

    /** HTML -> непустые текстовые строки. */
    private fun toLines(html: String): List<String> =
        Xmltv.unescape(TAG_RE.replace(SCRIPT_RE.replace(html, " "), "\n"))
            .replace('\u00A0', ' ')
            .lineSequence()
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotEmpty() }
            .toList()

    private fun toMinutes(h: String, m: String): Int? {
        val hh = h.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        return if (hh in 0..23 && mm in 0..59) hh * 60 + mm else null
    }

    private fun isJunk(line: String): Boolean {
        if (line.length < 2) return true
        val l = line.lowercase()
        return JUNK.any { l == it || (l.length < 40 && l.contains(it)) }
    }
}
