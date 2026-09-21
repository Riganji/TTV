package com.example.teliktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamProbeTest {
    @Test
    fun emptyAndHtml() {
        assertEquals("пустой ответ", StreamProbe.summarize("", false))
        assertEquals("вместо плейлиста пришла HTML/XML-разметка", StreamProbe.summarize("<!DOCTYPE html><html>", false))
    }

    @Test
    fun manifestKinds() {
        assertEquals("плейлист получен, но не разобран", StreamProbe.summarize("#EXTM3U\n#EXT-X-VERSION:3", false))
        assertEquals("вместо DASH пришёл HLS-плейлист", StreamProbe.summarize("#EXTM3U", true))
        assertEquals("вместо HLS пришёл манифест DASH", StreamProbe.summarize("<?xml version=\"1.0\"?><MPD>", false))
        assertEquals("манифест DASH получен, но не разобран", StreamProbe.summarize("<MPD xmlns=\"x\">", true))
    }

    @Test
    fun plainText() {
        val s = StreamProbe.summarize("Forbidden\n  by   geo", false)
        assertTrue(s.startsWith("не плейлист: «Forbidden by geo"))
    }
}