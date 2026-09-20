package com.example.teliktv

/** Настройки парсера (перенесены из parser.py) и группы каналов для экрана списка. */
object Config {
    const val BASE = "https://telik.live/"

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Хост, с которого отдаются iframe-плееры. */
    const val PLAYER_HOST = "cdntvmedia.com"

    val IGNORE_HOSTS = listOf(
        "programma-peredach.com", "yandex.", "google", "doubleclick", "facebook.com", "vk.com",
    )

    /** Группы (название -> slug'и страниц telik.live/<slug>.html). Порядок = порядок в списке. */
    val GROUPS: List<Pair<String, List<String>>> = listOf(
        "Кино" to listOf(
            "kino-tv", "hit-hd", "kinopokaz", "kinosat", "komedijnoe", "fantastica-hd",
            "lyubimoe-kino", "tv-xxi", "hollywood", "strah-hd", "ostrosyuzhetnoe-hd",
            "sony-sci-fi", "illyuzion", "strashnoe-hd", "shokiruyushchee", "devil-tv",
        ),
        "Fresh" to listOf(
            "fresh-premiere", "fresh-adventure", "fresh-cinema", "fresh-comedy",
            "fresh-fantastic", "fresh-rating", "fresh-thriller",
        ),
        "Cineman" to listOf(
            "cineman-action", "cineman-katastrofy", "cineman-komediya",
            "cineman-marvel", "cineman-triller", "cineman-top",
        ),
        "Познавательные" to listOf(
            "history-channel", "history-2", "national-geographic", "discovery-channel",
            "viasat-history", "nat-geo-wild", "id-investigation-discovery",
        ),
        "Другое" to listOf("muzhskoj", "avto24"),
    )

    val ALL_SLUGS: List<String> = GROUPS.flatMap { it.second }

    // ------------------------------------------------------------------ EPG (XMLTV)

    /**
     * Источники телепрограммы в формате XMLTV (можно .xml или .xml.gz). Пробуются по порядку,
     * берётся первый, где нашлись наши каналы. Если адреса устарели — замените на рабочие.
     */
    val EPG_URLS: List<String> = listOf(
        "https://iptvx.one/EPG",
        "https://iptvx.one/epg/epg.xml.gz",
    )

    /**
     * Ручное сопоставление, если название канала на telik.live не совпадает с названием в XMLTV:
     * slug -> список названий/id из XMLTV, например "kino-tv" to listOf("Кино ТВ", "kinotv.ru").
     */
    val EPG_ALIASES: Map<String, List<String>> = emptyMap()

    /** Как долго кэш телепрограммы считается свежим. */
    const val EPG_TTL_MS = 6 * 60 * 60 * 1000L

    /** Программа хранится в окне [сейчас - 2 ч; сейчас + 36 ч]. */
    const val EPG_PAST_MS = 2 * 60 * 60 * 1000L
    const val EPG_FUTURE_MS = 36 * 60 * 60 * 1000L

    /** Сколько ждать старта потока, прежде чем считать его нерабочим и переключиться на следующий. */
    const val STREAM_TIMEOUT_MS = 20_000L
}
