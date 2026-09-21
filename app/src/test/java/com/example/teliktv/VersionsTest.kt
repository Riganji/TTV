package com.example.teliktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сравнение versionName с тегом релиза: от него зависит, предложит ли приложение обновление. */
class VersionsTest {

    @Test
    fun parsesTags() {
        assertEquals(listOf(0, 3, 0), Versions.parse("v0.3.0"))
        assertEquals(listOf(0, 3, 0), Versions.parse("0.3.0"))
        assertEquals(listOf(1, 2), Versions.parse(" V1.2-beta "))
        assertEquals(emptyList<Int>(), Versions.parse("release"))
    }

    @Test
    fun newerOnlyWhenStrictlyGreater() {
        assertTrue(Versions.isNewer("v0.3.0", "0.2.4"))
        assertTrue(Versions.isNewer("0.2.10", "0.2.9"))
        assertTrue(Versions.isNewer("1.0", "0.9.9"))
        assertFalse(Versions.isNewer("0.2.4", "0.2.4"))
        assertFalse(Versions.isNewer("v0.2.4", "0.2.4.0"))
        assertFalse(Versions.isNewer("0.2.3", "0.2.4"))
        assertFalse(Versions.isNewer("latest", "0.2.4"))   // тег без номера обновлением не считаем
    }
}
