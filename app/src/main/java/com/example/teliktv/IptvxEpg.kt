package com.example.teliktv

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Разбор JSON-ответа https://epg.iptvx.one/api/id/<id>.json.
 *
 * Формат (пример):
 * {
 *   "ch_id": "khit",
 *   "ch_icon": "https://iptvx.one/picons/khit.png",
 *   "ch_programme": [
 *     { "start": "20-09-2026 06:00", "stop": "20-09-2026 07:30",
 *       "title": "Название", "description": "Описание" },
 *     ...
 *   ]
 * }
 *
 * Время — в часовом поясе EpgConfig.SOURCE_TZ, хранится как epoch millis (UTC).
 * Поля, которых нет в ответе, не роняют парсер: ch_icon и description опциональны.
 */
object IptvxEpg {

    /** База для иконок каналов, если ch_icon не пришёл в ответе. */
    const val PICON_BASE = "https://iptvx.one/picons"

    private val json = Json { ignoreUnknownKeys = true }
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
    private val zone: ZoneId = ZoneId.of(EpgConfig.SOURCE_TZ)

    /** @return список передач (отсортирован по началу) и URL иконки, если он был в ответе. */
    fun parse(body: String): Pair<List<Programme>, String?> {
        val resp = json.decodeFromString<IptvxResponse>(body)
        val programmes = resp.ch_programme
            .mapNotNull { p ->
                val start = parseTime(p.start) ?: return@mapNotNull null
                val stop = parseTime(p.stop) ?: return@mapNotNull null
                if (stop <= start) return@mapNotNull null
                Programme(start, stop, p.title, p.description)
            }
            .sortedBy { it.start }
        return programmes to resp.ch_icon
    }

    private fun parseTime(s: String): Long? = try {
        LocalDateTime.parse(s, formatter).atZone(zone).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    @Serializable
    private data class IptvxResponse(
        val ch_id: String? = null,
        val ch_icon: String? = null,
        val ch_programme: List<IptvxProgramme> = emptyList(),
    )

    @Serializable
    private data class IptvxProgramme(
        val start: String,
        val stop: String,
        val title: String,
        val description: String? = null,
    )
}