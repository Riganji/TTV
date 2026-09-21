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
            "kino-tv", "hit-hd", "kinopokaz", "kinosat", "komedijnoe", "dom-kino",
            "lyubimoe-kino", "tv-xxi", "hollywood", "trash-tv", "ostrosyuzhetnoe-hd",
            "sony-sci-fi", "illyuzion", "sony-turbo", "strashnoe-hd", "shokiruyushchee",
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
            "viasat-history", "nat-geo-wild", "id-investigation-discovery", "muzhskoj", "avto24",
        ),
        "Другое" to listOf(
            "pervyj-kanal", "rossiya-1", "ntv", "ren-tv", "pyatyj-kanal", "rossiya-24", "rbk",
        ),
    )

    val ALL_SLUGS: List<String> = GROUPS.flatMap { it.second }

    // ------------------------------------------------------- каналы с постоянной ссылкой

    /**
     * Канал с прямой ссылкой на поток: telik.live для него не разбирается.
     * slug используется и как ключ иконки/телепрограммы (см. EpgConfig.CHANNEL_MAP).
     */
    data class FixedChannel(
        val slug: String,
        val title: String,
        val url: String,
        val label: String = "Прямой эфир",
    )

    val FIXED: List<FixedChannel> = listOf(
        FixedChannel(
            slug = "rbk",
            title = "РБК",
            url = "https://online-video.rbc.ru/online2/rbctv.m3u8",
        ),
    )

    /** Готовые Channel для FIXED — подставляются в список без обращения к сети. */
    val FIXED_CHANNELS: List<Channel> = FIXED.map { f ->
        Channel(
            slug = f.slug,
            title = f.title,
            page = f.url,
            streams = listOf(StreamItem(label = f.label, player = f.url, url = f.url)),
        )
    }

    val FIXED_SLUGS: Set<String> = FIXED.map { it.slug }.toSet()

    /** Что реально идёт в парсер: всё, кроме каналов с постоянной ссылкой. */
    val SCRAPE_SLUGS: List<String> = ALL_SLUGS.filterNot { it in FIXED_SLUGS }

    // ------------------------------------------------------------------ EPG

    // Телепрограмма берётся постранично с programma-peredach.com — см. EpgConfig.kt.
    // Общий XMLTV (iptvx.one и т.п.) больше не используется.

    /** Сколько ждать старта потока, прежде чем считать его нерабочим и переключиться на следующий. */
    const val STREAM_TIMEOUT_MS = 20_000L

    /** Любой оверлей поверх видео закрывается сам через это время без нажатий. */
    const val OVERLAY_TIMEOUT_MS = 30_000L
}
