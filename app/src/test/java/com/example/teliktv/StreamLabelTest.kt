package com.example.teliktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLabelTest {

    @Test
    fun cleanLabelStripsTagsAndPunctuation() {
        assertEquals("HD", Extract.cleanLabel("  <span>HD</span> "))
        assertEquals("Резерв", Extract.cleanLabel("— Резерв —"))
        assertEquals("Плеер 2", Extract.cleanLabel("«Плеер 2»"))
    }

    @Test
    fun labelBaseDropsTrailingNumber() {
        assertEquals("Плеер", Extract.labelBase("Плеер 2"))
        assertEquals("Плеер", Extract.labelBase("Плеер №3"))
        assertEquals("Плеер", Extract.labelBase("Плеер - 4"))
        assertEquals("Поток", Extract.labelBase("Поток (10)"))
        assertEquals("HD", Extract.labelBase("HD"))
        // Из одних цифр — оставляем как есть, иначе названия не останется.
        assertEquals("2", Extract.labelBase("2"))
    }

    @Test
    fun uniqueLabelsNumberOnlyDuplicates() {
        assertEquals(listOf("HD", "Резерв"), Extract.uniqueLabels(listOf("HD", "Резерв")))
        assertEquals(
            listOf("Плеер 1", "Плеер 2", "Резерв"),
            Extract.uniqueLabels(listOf("Плеер", "Плеер", "Резерв")),
        )
        assertEquals(listOf("Поток 1", "Поток 2"), Extract.uniqueLabels(listOf("", "")))
    }

    @Test
    fun cachedLabelsLoseDoubleNumbering() {
        val streams = listOf(
            StreamItem("Плеер 1 1", "https://p/1", "https://a/1.m3u8"),
            StreamItem("Плеер 1 2", "https://p/1", "https://a/2.m3u8"),
            StreamItem("Резерв", "https://p/2", "https://a/3.m3u8"),
        )
        assertEquals(
            listOf("Плеер 1", "Плеер 2", "Резерв"),
            Extract.cleanStreams(streams).map { it.label },
        )
    }

    @Test
    fun numberIsBracketedAfterDigitLabels() {
        // «+2 1 / +2 2» на экране читается как «+21 / +22» — поэтому скобки.
        assertEquals(listOf("+2 (1)", "+2 (2)"), Extract.uniqueLabels(listOf("+2", "+2")))
        assertEquals(listOf("Плеер 1", "Плеер 2"), Extract.uniqueLabels(listOf("Плеер", "Плеер")))
        // Повторная чистка ничего не ломает: «+2 (1)» снова даёт основу «+2».
        assertEquals("+2", Extract.labelBase("+2 (1)"))
        assertEquals("+2", Extract.labelBase("+2"))
        val streams = listOf(
            StreamItem("+2 1", "https://p/1", "https://a/1.m3u8"),
            StreamItem("+2 2", "https://p/1", "https://a/2.m3u8"),
        )
        assertEquals(listOf("+2 (1)", "+2 (2)"), Extract.cleanStreams(streams).map { it.label })
    }

    @Test
    fun fixedChannelsAreInGroupsAndNotScraped() {
        for (f in Config.FIXED) {
            assertTrue("${f.slug} нет ни в одной группе", f.slug in Config.ALL_SLUGS)
            assertTrue("${f.slug} не должен парситься", f.slug !in Config.SCRAPE_SLUGS)
            assertTrue("${f.slug} нет в маппинге телепрограммы", f.slug in EpgConfig.CHANNEL_MAP)
        }
        val rbk = Config.FIXED_CHANNELS.single { it.slug == "rbk" }
        assertEquals("РБК", rbk.title)
        assertEquals("https://online-video.rbc.ru/online2/rbctv.m3u8", rbk.streams.single().url)
        assertEquals("https://online-video.rbc.ru/", rbk.streams.single().referer)
        assertTrue("РБК должен быть в группе «Другое»", "rbk" in Config.GROUPS.single { it.first == "Другое" }.second)
    }
}
