package com.example.teliktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PeredachTest {

    private val msk: ZoneId = ZoneId.of("Europe/Moscow")
    private val day: LocalDate = LocalDate.of(2026, 9, 20)

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /** Время и название в отдельных ячейках таблицы. */
    @Test
    fun parsesTableLayout() {
        val html = """
            <html><head><title>Кино ТВ</title></head><body>
            <div class="nav">Программа передач</div>
            <table>
              <tr><td class="t">06:00</td><td class="n">Доброе утро<span class="d">Утренний эфир</span></td></tr>
              <tr><td class="t">10:00</td><td class="n">Новости</td></tr>
              <tr><td class="t">10:30</td><td class="n">Сериал &laquo;Дом&raquo;</td></tr>
              <tr><td class="t">12:00</td><td class="n">Кино дня</td></tr>
              <tr><td class="t">14:15</td><td class="n">Вечерний показ</td></tr>
            </table>
            </body></html>
        """.trimIndent()

        val list = Peredach.parse(html, day, msk)
        assertEquals(5, list.size)
        assertEquals("Доброе утро", list[0].title)
        assertEquals("Утренний эфир", list[0].desc)
        // 06:00 MSK = 03:00 UTC
        assertEquals(ms("2026-09-20T03:00:00Z"), list[0].start)
        // конец первой = начало второй
        assertEquals(list[1].start, list[0].stop)
        assertEquals(ms("2026-09-20T11:15:00Z"), list[4].start)
        // у последней — час по умолчанию
        assertEquals(list[4].start + 3_600_000L, list[4].stop)
    }

    /** Время слипается с названием в одной строке. */
    @Test
    fun parsesInlineTimeLayout() {
        val html = """
            <ul>
              <li>06:00 Доброе утро</li>
              <li>08:30 Новости</li>
              <li>09:00 Погода</li>
              <li>09:15 Сериал</li>
              <li>11:00 Обед</li>
            </ul>
        """.trimIndent()

        val list = Peredach.parse(html, day, msk)
        assertEquals(5, list.size)
        assertEquals("Доброе утро", list[0].title)
        assertEquals("Новости", list[1].title)
        assertEquals(ms("2026-09-20T05:30:00Z"), list[1].start)
    }

    /** Ночной блок после полуночи относится к следующим суткам. */
    @Test
    fun rollsOverMidnight() {
        val html = """
            <div>20:00</div><div>Вечерний фильм</div>
            <div>22:00</div><div>Новости</div>
            <div>23:30</div><div>Ток-шоу</div>
            <div>00:40</div><div>Ночное кино</div>
            <div>02:00</div><div>Музыка</div>
        """.trimIndent()

        val list = Peredach.parse(html, day, msk)
        assertEquals(5, list.size)
        assertEquals(ms("2026-09-20T17:00:00Z"), list[0].start)
        // 00:40 уже 21 сентября по Москве = 21:40 UTC 20-го
        assertEquals(ms("2026-09-20T21:40:00Z"), list[3].start)
        assertEquals(ms("2026-09-20T23:00:00Z"), list[4].start)
        assertTrue(list.zipWithNext().all { (a, b) -> a.start < b.start })
    }

    /** Страница без расписания (ошибка, заглушка) не должна давать мусор. */
    @Test
    fun ignoresPageWithoutSchedule() {
        val html = "<html><body><h1>Канал не найден</h1><p>12:00 — время обновления</p></body></html>"
        assertEquals(emptyList<Programme>(), Peredach.parse(html, day, msk))
    }

    /** Скрипты и стили не попадают в названия. */
    @Test
    fun skipsScripts() {
        val html = """
            <script>var t = "06:00 реклама";</script>
            <style>.a{content:"07:00"}</style>
            <div>06:00</div><div>Утро</div>
            <div>07:00</div><div>Новости</div>
            <div>08:00</div><div>Кино</div>
            <div>09:00</div><div>Спорт</div>
        """.trimIndent()

        val list = Peredach.parse(html, day, msk)
        assertEquals(4, list.size)
        assertEquals("Утро", list[0].title)
    }
}
