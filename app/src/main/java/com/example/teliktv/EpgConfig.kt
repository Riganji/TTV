package com.example.teliktv

/**
 * Источник телепрограммы и иконок — https://epg.iptvx.one.
 *
 * Для каждого канала делается один запрос: https://epg.iptvx.one/api/id/<id>.json
 * Ответ содержит ch_programme (список передач) и ch_icon (URL картинки).
 * Иконка также доступна детерминированно: https://iptvx.one/picons/<id>.png
 *
 * Значение в CHANNEL_MAP — то, что идёт ПОСЛЕ "/id/". Ключи — slug'и telik.live (Config.ALL_SLUGS).
 * Канала нет в мапе — телепрограмма и иконка для него не запрашиваются.
 */
object EpgConfig {
    const val BASE = "https://epg.iptvx.one"

    /** Часовой пояс, в котором источник публикует время. */
    const val SOURCE_TZ = "Europe/Moscow"

    /** Сколько ждать между обновлениями. */
    const val TTL_MS = 6 * 60 * 60 * 1000L

    val CHANNEL_MAP: Map<String, String> = mapOf(
        // Кино
        "kino-tv" to "kino-tv",
        "hit-hd" to "khit",
        "kinopokaz" to "kinopokaz",
        "kinosat" to "kinosat",
        "komedijnoe" to "komedijnoe",
        "dom-kino" to "domkino",
        "lyubimoe-kino" to "lubimoe-kino",
        "tv-xxi" to "tvxxi",
        "hollywood" to "hollywood",
        "trash-tv" to "trashtv",
        "ostrosyuzhetnoe-hd" to "ostrosiuzhetnoe-hd",
        "sony-sci-fi" to "sony-scifi",
        "illyuzion" to "illuzion-plus",
        "sony-turbo" to "sony-turbo",
        "strashnoe-hd" to "strashnoe-hd",
        "shokiruyushchee" to "shokiruyushchee-hd",

        // Fresh
        "fresh-premiere" to "fresh-premiere",
        "fresh-adventure" to "fresh-adventure",
        "fresh-cinema" to "fresh-cinema",
        "fresh-comedy" to "fresh-comedy",
        "fresh-fantastic" to "fresh-fantastic",
        "fresh-rating" to "fresh-rating",
        "fresh-thriller" to "fresh-thriller",

        // Cineman		
        "cineman-action" to "cineman-action",
        "cineman-katastrofy" to "cineman-katastrofy",
        "cineman-komediya" to "cineman-comedy",
        "cineman-marvel" to "cineman-marvel",
        "cineman-triller" to "cineman-thriller",
        "cineman-top" to "cineman-top",

        // Познавательные
        "history-channel" to "history",
        "history-2" to "h2",
        "national-geographic" to "national-geographic",
        "discovery-channel" to "discovery-channel",
        "viasat-history" to "viasat-history",
        "nat-geo-wild" to "nat-geo-wild",
        "id-investigation-discovery" to "id-xtra-eu",

        // Другое
        "muzhskoj" to "muzhskoy",
        "avto24" to "avto24",

        // Общие / эфирные
        "pervyj-kanal" to "pervy",
        "rossiya-1" to "rossia1",
        "ntv" to "ntv",
        "ren-tv" to "rentv",
        "pyatyj-kanal" to "5kanal-ru",
        "rossiya-24" to "rossia-24",
        "rbk" to "rbk",
    )
}