package com.example.teliktv

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupsTest {
    private fun ch(slug: String) = Channel(slug, slug, "p", emptyList())
    private val channels = listOf(ch("kino-tv"), ch("fresh-comedy"), ch("avto24"))

    @Test
    fun favoritesAllAndConfigGroups() {
        val fav = setOf("avto24", "kino-tv")
        assertEquals(listOf("kino-tv", "avto24"), Groups.channelsFor(Groups.FAVORITES, channels, fav).map { it.slug })
        assertEquals(listOf("kino-tv", "fresh-comedy", "avto24"), Groups.channelsFor(Groups.ALL, channels, fav).map { it.slug })
        assertEquals(listOf("kino-tv"), Groups.channelsFor(2, channels, fav).map { it.slug })          // Кино
        assertEquals(listOf("fresh-comedy"), Groups.channelsFor(3, channels, fav).map { it.slug })     // Fresh
        assertEquals(listOf("avto24"), Groups.channelsFor(6, channels, fav).map { it.slug })           // Другое
    }

    @Test
    fun namesAndCounts() {
        assertEquals(7, Groups.names.size)
        assertEquals("Избранное", Groups.names[0])
        assertEquals(0, Groups.count(Groups.FAVORITES, channels, emptySet()))
        assertEquals(3, Groups.count(Groups.ALL, channels, emptySet()))
        assertEquals(3, Groups.count(99, channels, emptySet()))   // неизвестный индекс = все
    }
}
