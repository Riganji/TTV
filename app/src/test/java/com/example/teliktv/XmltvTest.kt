package com.example.teliktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader
import java.time.Instant

class XmltvTest {

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="test">
<channel id="kino.tv"><display-name lang="ru">Кино ТВ</display-name><icon src="https://img.example.com/kino.png"/></channel>
<channel id="hit"><display-name>Хит HD</display-name></channel>
<channel id="dup"><display-name>Кино ТВ</display-name></channel>
<channel id="unknown"><display-name>Неизвестный</display-name></channel>
<programme start="20260920200000 +0300" stop="20260920214500 +0300" channel="kino.tv"><title lang="ru">Фильм &amp; Ко</title><desc>Описание &quot;тест&quot; &#1092;</desc></programme>
<programme start="20260920214500 +0300" stop="20260920233000 +0300" channel="kino.tv"><title><![CDATA[Второй <фильм>]]></title></programme>
<programme start="20260920200000 +0300" stop="20260920210000 +0300" channel="hit"><title>Хит-парад</title></programme>
<programme start="20260920200000 +0300" stop="20260920210000 +0300" channel="unknown"><title>Пропуск</title></programme>
<programme start="20260920200000 +0300" stop="20260920210000 +0300" channel="dup"><title>Дубль</title></programme>
<programme start="20250101000000 +0000" stop="20250101010000 +0000" channel="kino.tv"><title>Старое</title></programme>
</tv>"""

    private val ours = listOf("kino-tv" to "Кино ТВ", "hit-hd" to "Хит HD", "fresh-comedy" to "Fresh Comedy")
    private val from = Instant.parse("2026-09-20T12:00:00Z").toEpochMilli()
    private val to = Instant.parse("2026-09-21T12:00:00Z").toEpochMilli()

    private fun parse(reader: Reader) = Xmltv.parse(reader, EpgMatcher(ours), from, to)

    @Test
    fun parsesMatchedChannelsWithinWindow() {
        val r = parse(StringReader(xml))
        assertEquals(setOf("kino-tv", "hit-hd"), r.programmes.keys)
        val kino = r.programmes.getValue("kino-tv")
        assertEquals(listOf("Фильм & Ко", "Второй <фильм>"), kino.map { it.title })
        assertEquals("Описание \"тест\" ф", kino[0].desc)
        assertEquals(Instant.parse("2026-09-20T17:00:00Z").toEpochMilli(), kino[0].start)
        assertEquals("https://img.example.com/kino.png", r.icons["kino-tv"])
    }

    @Test
    fun secondXmltvChannelForSameSlugIsIgnored() {
        val r = parse(StringReader(xml))
        assertTrue(r.programmes.getValue("kino-tv").none { it.title == "Дубль" })
    }

    @Test
    fun worksWhenInputArrivesInTinyChunks() {
        val tiny = object : Reader() {
            private val src = StringReader(xml)
            override fun read(cbuf: CharArray, off: Int, len: Int): Int = src.read(cbuf, off, minOf(len, 7))
            override fun close() = src.close()
        }
        val a = parse(tiny)
        val b = parse(StringReader(xml))
        assertEquals(b.programmes, a.programmes)
        assertEquals(b.icons, a.icons)
    }

    @Test
    fun timeParsing() {
        assertEquals(Instant.parse("2026-09-20T17:15:00Z").toEpochMilli(), Xmltv.parseTime("20260920201500 +0300"))
        assertEquals(Instant.parse("2026-09-20T20:15:00Z").toEpochMilli(), Xmltv.parseTime("20260920201500 -0000"))
        assertEquals(Instant.parse("2026-09-20T20:15:00Z").toEpochMilli(), Xmltv.parseTime("20260920201500"))
        assertEquals(Instant.parse("2026-09-20T20:15:00Z").toEpochMilli(), Xmltv.parseTime("202609202015"))
        assertEquals(null, Xmltv.parseTime("garbage"))
    }

    @Test
    fun matcherExactRelaxedAndAmbiguous() {
        val m = EpgMatcher(listOf("kino-tv" to "Кино ТВ", "kino-hd" to "Кино HD", "hit-hd" to "Хит HD"))
        assertEquals("kino-tv", m.resolve("x", listOf("Кино ТВ")))
        assertEquals("kino-tv", m.resolve("kinotv.ru", emptyList()))
        assertEquals("hit-hd", m.resolve("x", listOf("Хит")))          // мягкое совпадение
        assertEquals(null, m.resolve("x", listOf("Кино")))              // подходит двум каналам
        assertEquals(null, m.resolve("x", listOf("Что-то другое")))
    }

    @Test
    fun matcherUsesAliases() {
        val m = EpgMatcher(listOf("kino-tv" to "Kino TV"), mapOf("kino-tv" to listOf("Кино ТВ (Россия)")))
        assertEquals("kino-tv", m.resolve("x", listOf("Кино ТВ (Россия)")))
    }

    @Test
    fun currentAndUpcoming() {
        val list = listOf(
            Programme(0, 100, "a"), Programme(100, 200, "b"), Programme(200, 300, "c"), Programme(300, 400, "d"),
        )
        assertEquals("b", Epg.current(list, 150)?.title)
        assertEquals(null, Epg.current(list, 500))
        assertEquals(listOf("c", "d"), Epg.upcoming(list, 150, 5).map { it.title })
        assertEquals(listOf("c"), Epg.upcoming(list, 150, 1).map { it.title })
        assertEquals(0.5f, Epg.progress(list[1], 150))
    }

    @Test
    fun entities() {
        assertEquals("a & b < c", Xmltv.unescape("a &amp; b &lt; c"))
        assertEquals("<x>", Xmltv.unescape("<![CDATA[<x>]]>"))
        assertEquals("ф", Xmltv.unescape("&#x444;"))
    }
}
