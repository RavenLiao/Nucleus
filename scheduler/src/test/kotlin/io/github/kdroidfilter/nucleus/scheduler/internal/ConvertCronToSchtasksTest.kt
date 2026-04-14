package io.github.kdroidfilter.nucleus.scheduler.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConvertCronToSchtasksTest {

    private fun convert(expression: String): List<String>? =
        WindowsTaskScheduler.convertCronToSchtasks(expression)

    @Test
    fun `daily at specific time`() {
        assertEquals(
            listOf("/SC", "DAILY", "/ST", "09:00"),
            convert("*-*-* 09:00:00"),
        )
    }

    @Test
    fun `daily at midnight`() {
        assertEquals(
            listOf("/SC", "DAILY", "/ST", "00:00"),
            convert("*-*-* 00:00:00"),
        )
    }

    @Test
    fun `every hour`() {
        assertEquals(
            listOf("/SC", "HOURLY"),
            convert("*-*-* *:00:00"),
        )
    }

    @Test
    fun `specific weekday with time`() {
        assertEquals(
            listOf("/SC", "WEEKLY", "/D", "MON", "/ST", "08:30"),
            convert("Mon *-*-* 08:30:00"),
        )
    }

    @Test
    fun `day range Mon to Fri`() {
        assertEquals(
            listOf("/SC", "WEEKLY", "/D", "MON,TUE,WED,THU,FRI", "/ST", "18:00"),
            convert("Mon..Fri *-*-* 18:00:00"),
        )
    }

    @Test
    fun `day range Tue to Thu`() {
        assertEquals(
            listOf("/SC", "WEEKLY", "/D", "TUE,WED,THU", "/ST", "12:00"),
            convert("Tue..Thu *-*-* 12:00:00"),
        )
    }

    @Test
    fun `unsupported expression returns null`() {
        assertNull(convert("*-*-01 00:00:00"))
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals(
            listOf("/SC", "DAILY", "/ST", "09:00"),
            convert("  *-*-* 09:00:00  "),
        )
    }

    @Test
    fun `invalid day range returns null`() {
        assertNull(convert("Fri..Mon *-*-* 09:00:00"))
    }
}
