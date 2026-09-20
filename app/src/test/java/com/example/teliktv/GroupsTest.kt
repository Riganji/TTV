package com.example.teliktv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Тесты не зависят от конкретных каналов — всё берётся из Config.GROUPS. */
class GroupsTest {
    private fun ch(slug: String) = Channel(slug, slug, "p", emptyList())
    private val channels = Config.ALL_SLUGS.map { ch(it) }

    @Test
    fun favoritesAllAndConfigGroups() {
        val first = Config.ALL_SLUGS.first()
        val last = Config.ALL_SLUGS.last()
        val fav = setOf(last, first)
        // порядок «Избранного» = порядок каналов, а не порядок добавления
        assertEquals(listOf(first, last), Groups.channelsFor(Groups.FAVORITES, channels, fav).map { it.slug })
        assertEquals(Config.ALL_SLUGS, Groups.channelsFor(Groups.ALL, channels, fav).map { it.slug })
        Config.GROUPS.forEachIndexed { i, (_, slugs) ->
            assertEquals(slugs, Groups.channelsFor(i + 2, channels, fav).map { it.slug })
        }
    }

    @Test
    fun missingChannelsAreSkipped() {
        val withoutFirst = channels.drop(1)
        val group = Config.GROUPS[0].second
        assertEquals(group.drop(1), Groups.channelsFor(2, withoutFirst, emptySet()).map { it.slug })
    }

    @Test
    fun namesAndCounts() {
        assertEquals(Config.GROUPS.size + 2, Groups.names.size)
        assertEquals("Избранное", Groups.names[0])
        assertEquals(0, Groups.count(Groups.FAVORITES, channels, emptySet()))
        assertEquals(channels.size, Groups.count(Groups.ALL, channels, emptySet()))
        assertEquals(channels.size, Groups.count(99, channels, emptySet()))   // неизвестный индекс = все
    }
}
