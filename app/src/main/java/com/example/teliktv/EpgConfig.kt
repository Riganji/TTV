package com.example.teliktv

/**
 * Источник телепрограммы — programma-peredach.com. Грузим только каналы из CHANNEL_MAP,
 * по одной небольшой странице на канал и день: ни общего XMLTV, ни лишнего трафика.
 *
 * Адрес страницы: https://programma-peredach.com/<CITY>/kanal_<slug>/<day>/
 * У части каналов городского раздела нет — тогда адрес без <CITY>; пробуем оба варианта
 * (см. EpgRepository.urlsFor), поэтому в CHANNEL_MAP город указывать не нужно.
 *
 * Значение в CHANNEL_MAP — то, что идёт ПОСЛЕ "kanal_". Ключи — slug'и telik.live (Config.ALL_SLUGS).
 * Канала нет в мапе — телепрограмма для него не запрашивается.
 */
object EpgConfig {
    const val BASE = "https://programma-peredach.com"
    const val CITY = "moskva"

    /** Часовой пояс, в котором сайт публикует время. Пересчитываем в местное время устройства. */
    const val SOURCE_TZ = "Europe/Moscow"

    /** Какие дни тянем: сегодня и завтра — этого хватает на «сейчас» и «далее». */
    val DAYS: List<String> = listOf("na_segodnya", "na_zavtra")

    /** Сколько ждать между обновлениями (страницы лёгкие, но дёргать сайт чаще смысла нет). */
    const val TTL_MS = 6 * 60 * 60 * 1000L

    val CHANNEL_MAP: Map<String, String> = mapOf(
        // Кино
        "kino-tv" to "kino-tv",
        "hit-hd" to "hit",
        "kinopokaz" to "kinopokaz",
        "kinosat" to "kinosat",
        "komedijnoe" to "komedijnoe",
		"dom-kino" to "dom-kino",
        "lyubimoe-kino" to "lyubimoe-kino",
        "tv-xxi" to "tv-xxi",
        "hollywood" to "hollywood",
        "trash-tv" to "trash",
        "ostrosyuzhetnoe-hd" to "ostrosyuzhetnoe-hd",
        "sony-sci-fi" to "sony-sci-fi",
        "sony-turbo" to "sony-turbo",
        "illyuzion" to "illjuzion-plus",
        "strashnoe-hd" to "strashnoe-hd",
        "shokiruyushchee" to "shokiruyushhee",

        // Fresh
        "fresh-premiere" to "fresh-premiere",
        "fresh-adventure" to "fresh-adventure",
        "fresh-cinema" to "fresh-cinema",
        "fresh-comedy" to "fresh-comedy",
        "fresh-fantastic" to "fresh-fantastic",
        "fresh-rating" to "fresh-rating",
        "fresh-thriller" to "fresh-thriller",

        // Познавательные
        "history-channel" to "history",
        "history-2" to "history2",
        "national-geographic" to "national-geographic",
        "discovery-channel" to "discovery",
        "viasat-history" to "viasat-history",
        "nat-geo-wild" to "nat-geo-wild",
        "id-investigation-discovery" to "id-investigation-discovery",

        // Другое
        "muzhskoj" to "muzhskoj",
        "avto24" to "avto24",

        // Общие / эфирные
        "pervyj-kanal" to "pervyj-kanal",
        "rossiya-1" to "rossija-1",
        "ntv" to "ntv",
        "ren-tv" to "ren-tv",
        "pyatyj-kanal" to "pjatyj",
        "rossiya-24" to "rossija-24",
    )
}
