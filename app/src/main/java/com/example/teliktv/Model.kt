package com.example.teliktv

import kotlinx.serialization.Serializable

/** Один поток канала (у канала их может быть несколько — вкладки на странице). */
@Serializable
data class StreamItem(
    val label: String,
    val player: String,
    val url: String,
) {
    /** Referer для запросов к потоку — как #EXTVLCOPT:http-referrer в write_m3u() из parser.py. */
    val referer: String get() = Extract.origin(player)
}

/** Строка программы в сетке вещания. time — "HH:MM" (локальное время страницы). */
@Serializable
data class Program(
    val time: String,
    val title: String,
)

@Serializable
data class Channel(
    val slug: String,
    val title: String,
    val page: String,
    val streams: List<StreamItem>,
    val logo: String? = null,
    val programs: List<Program> = emptyList(),
)

/** Программа, идущая в момент now ("HH:MM"). Если расписание на сегодня — берём последнюю подходящую. */
fun List<Program>.currentAt(now: String): Program? =
    lastOrNull { it.time <= now } ?: firstOrNull()