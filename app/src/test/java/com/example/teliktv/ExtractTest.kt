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
    fun configHasNoDuplicatesAndSlugsLookValid() {
        // Не привязано к конкретному списку каналов — можно свободно править Config.GROUPS.
        assertEquals(Config.GROUPS.sumOf { it.second.size }, Config.ALL_SLUGS.size)
        val dups = Config.ALL_SLUGS.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), dups)
        val bad = Config.ALL_SLUGS.filterNot { Regex("[a-z0-9-]+").matches(it) }
        assertEquals(emptyList<String>(), bad)
    }

    private val page = "https://telik.live/kino-tv.html"

    @Test
    fun logoBySlugInPath() {
        val html = """<img src="/ad.gif" alt="реклама"><img src="/img/kino-tv.png" alt="x">"""
        assertEquals("https://telik.live/img/kino-tv.png", Extract.findLogo(html, page, "kino-tv", "Кино ТВ"))
    }

    @Test
    fun logoByAltTitle() {
        val html = """<img src="/ad.gif" alt="реклама"><img src="/a/b.jpg" alt="Смотреть Кино ТВ онлайн">"""
        assertEquals("https://telik.live/a/b.jpg", Extract.findLogo(html, page, "zzz", "Кино ТВ"))
    }

    @Test
    fun logoFromOgImageEitherAttributeOrder() {
        val html = """<meta content="//cdn.example.com/p/pic.png" property="og:image">"""
        assertEquals("https://cdn.example.com/p/pic.png", Extract.findLogo(html, page, "zzz", "Кино ТВ"))
    }

    @Test
    fun logoLazyLoadedAndLogoHint() {
        val lazy = """<img src="data:image/gif;base64,R0lGOD" data-src="/lazy/kino-tv.webp">"""
        assertEquals("https://telik.live/lazy/kino-tv.webp", Extract.findLogo(lazy, page, "kino-tv", "Кино ТВ"))
        val hint = """<img src="/static/site-logo.png" class="x">"""
        assertEquals("https://telik.live/static/site-logo.png", Extract.findLogo(hint, page, "zzz", "Кино ТВ"))
    }

    @Test
    fun logoSkipsSvgFaviconAndData() {
        val html = """<img src="/logo.svg" class="logo"><img src="/favicon.png" class="logo"><img src="data:image/png;base64,AAAA" class="logo">"""
        assertEquals(null, Extract.findLogo(html, page, "zzz", "Кино ТВ"))
    }

    @Test
    fun sharedLogosAreDetected() {
        assertEquals(setOf("a"), Extract.sharedLogoUrls(listOf("a", "a", "a", "b", null, "b")))
        assertEquals(emptySet<String>(), Extract.sharedLogoUrls(listOf("a", "b", null)))
    }
}
