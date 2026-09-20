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

@Serializable
data class Channel(
    val slug: String,
    val title: String,
    val page: String,
    val streams: List<StreamItem>,
    /** Логотип, найденный на странице канала (может быть null — тогда берётся иконка из EPG). */
    val logo: String? = null,
)

/** Передача из телепрограммы; время — миллисекунды Unix. */
@Serializable
data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String? = null,
)

/** Канал, который не удалось загрузить при обновлении. cached = в списке остался сохранённый вариант. */
data class ChannelFailure(
    val slug: String,
    val name: String,
    val reason: String,
    val cached: Boolean,
)

data class EpgStatus(
    val loading: Boolean = false,
    val error: String? = null,
    val matched: Int = 0,
    val total: Int = 0,
)
