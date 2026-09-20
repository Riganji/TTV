package com.example.teliktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ExtractTest {

    @Test
    fun jsonEscapedAndAmpEntity() {
        val text = """{"file":"https:\/\/cdn.example.com\/live\/index.m3u8?token=abc&amp;e=1"}"""
        assertEquals(
            listOf("https://cdn.example.com/live/index.m3u8?token=abc&e=1"),
            Extract.extractStreams(text, "https://cdntvmedia.com/p/1"),
        )
    }

    @Test
    fun schemeRelativeAndDash() {
        val text = """<source src="//cdn2.example.com/a/b.mpd">"""
        assertEquals(
            listOf("https://cdn2.example.com/a/b.mpd"),
            Extract.extractStreams(text, "https://cdntvmedia.com/p/1"),
        )
    }

    @Test
    fun urlEncoded() {
        val text = """player.php?src=https%3A%2F%2Fcdn3.example.com%2Fx%2Fplaylist.m3u8%3Fa%3D1&x=2"""
        assertEquals(
            listOf("https://cdn3.example.com/x/playlist.m3u8?a=1"),
            Extract.extractStreams(text, "https://cdntvmedia.com/p/1"),
        )
    }

    @Test
    fun base64Encoded() {
        val url = "https://cdn4.example.com/live/master.m3u8"
        val b64 = Base64.getEncoder().encodeToString(url.toByteArray())
        assertTrue(b64.length >= 32)
        assertEquals(
            listOf(url),
            Extract.extractStreams("var s = \"$b64\";", "https://cdntvmedia.com/p/1"),
        )
    }

    @Test
    fun relativeStreamResolvedAgainstPlayer() {
        assertEquals(
            listOf("https://cdntvmedia.com/hls/index.m3u8"),
            Extract.extractStreams("""file: "//cdntvmedia.com/hls/index.m3u8" """, "https://cdntvmedia.com/p/1"),
        )
    }

    @Test
    fun playersSkipAdsAndSameHost() {
        val html = """
            <iframe src="https://cdntvmedia.com/player/abc"></iframe>
            <iframe src="//ads.yandex.ru/x"></iframe>
            <iframe src="/local/page"></iframe>
            <iframe data-src="//cdntvmedia.com/p2" src="about:blank"></iframe>
        """.trimIndent()
        assertEquals(
            listOf("https://cdntvmedia.com/player/abc", "https://cdntvmedia.com/p2"),
            Extract.findPlayers(html, "https://telik.live/kino-tv.html"),
        )
    }

    @Test
    fun tabLabels() {
        val html = """<li><a href="#vkl"><span>HD</span></a></li><li><a href="#video2"> Резерв </a></li><li><a href="#vkl"> </a></li>"""
        assertEquals(listOf("HD", "Резерв", "Поток 3"), Extract.findTabLabels(html))
    }

    @Test
    fun channelTitles() {
        assertEquals("Кино ТВ", Extract.channelTitle("<title>Кино ТВ прямой эфир смотреть онлайн</title>", "x"))
        assertEquals("Кино ТВ", Extract.channelTitle("<title>«Кино ТВ» Прямой эфир</title>", "x"))
        assertEquals("fallback", Extract.channelTitle("<html></html>", "fallback"))
    }

    @Test
    fun urlJoin() {
        val base = "https://telik.live/a/b.html"
        assertEquals("https://telik.live/a/c.html", Extract.urlJoin(base, "c.html"))
        assertEquals("https://telik.live/x", Extract.urlJoin(base, "/x"))
        assertEquals("https://h.com/y", Extract.urlJoin(base, "//h.com/y"))
        assertEquals("https://telik.live/a/c d{}.html", Extract.urlJoin(base, "c d{}.html"))
    }

    @Test
    fun originAndKinds() {
        assertEquals("https://cdntvmedia.com/", Extract.origin("https://cdntvmedia.com/player/abc?x=1"))
        assertTrue(Extract.isDash("https://a.b/x.mpd?t=1"))
        assertTrue(!Extract.isDash("https://a.b/x.m3u8"))
        assertTrue(Extract.isDirectStream("https://a.b/x.m3u8?t=1"))
        assertTrue(!Extract.isDirectStream("https://a.b/player/1"))
    }

    @Test
    fun bodyDecoding() {
        val cp1251 = charset("windows-1251")
        val bytes = "Привет".toByteArray(cp1251)
        assertEquals("Привет", Extract.decodeBody(bytes, "text/html; charset=windows-1251"))
        val withMeta = "<meta charset=\"windows-1251\">Привет".toByteArray(cp1251)
        assertEquals("<meta charset=\"windows-1251\">Привет", Extract.decodeBody(withMeta, "text/html"))
        assertEquals("Привет", Extract.decodeBody("Привет".toByteArray(), null))
    }

    @Test
    fun groupsCoverAllChannelsOnce() {
        assertEquals(38, Config.ALL_SLUGS.size)
        assertEquals(Config.ALL_SLUGS.size, Config.ALL_SLUGS.toSet().size)
    }

    @Test
    fun programsFromContainer() {
        val html = """
            <html><body>
            <div class="tv-program">
              <div>06:00 Утро</div>
              <div>09:00 - Новости</div>
              <div>12:30: Кино</div>
              <div>18:00 — Вечернее шоу</div>
            </div>
            </body></html>
        """.trimIndent()
        val programs = Extract.extractPrograms(html)
        assertEquals(listOf("06:00", "09:00", "12:30", "18:00"), programs.map { it.time })
        assertEquals("Утро", programs[0].title)
        assertEquals("Новости", programs[1].title)
        assertEquals("Кино", programs[2].title)
        assertEquals("Вечернее шоу", programs[3].title)
    }

    @Test
    fun programsFallbackWholePage() {
        val html = "<html><body><p>06:00 Утро</p><p>07:00 Новости</p><p>08:00 Мультфильмы</p></body></html>"
        val programs = Extract.extractPrograms(html)
        assertEquals(3, programs.size)
    }

    @Test
    fun currentProgramAt() {
        val list = listOf(
            Program("06:00", "Утро"),
            Program("09:00", "Новости"),
            Program("12:00", "Кино"),
        )
        assertEquals("Утро", list.currentAt("05:30")?.title)  // до первой — берём первую
        assertEquals("Утро", list.currentAt("06:30")?.title)
        assertEquals("Новости", list.currentAt("11:59")?.title)
        assertEquals("Кино", list.currentAt("23:00")?.title)
    }
}