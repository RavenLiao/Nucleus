package io.github.kdroidfilter.nucleus.scheduler.internal

import io.github.kdroidfilter.nucleus.scheduler.CronExpression
import io.github.kdroidfilter.nucleus.scheduler.TaskRequest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class BuildTimerUnitTest {

    @Test
    fun `periodic timer has OnUnitActiveSec`() {
        val request = TaskRequest.periodic("test-task", 1.hours)
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(unit.contains("OnUnitActiveSec=3600s"), "Expected OnUnitActiveSec=3600s")
    }

    @Test
    fun `periodic timer has OnBootSec`() {
        val request = TaskRequest.periodic("test-task", 1.hours)
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        // 3600 / 10 = 360 → clamped to 300
        assertTrue(unit.contains("OnBootSec=300s"), "Expected OnBootSec=300s (clamped)")
    }

    @Test
    fun `periodic timer boot delay minimum is 60s`() {
        val request = TaskRequest.periodic("test-task", 15.minutes)
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        // 900 / 10 = 90 → within [60, 300]
        assertTrue(unit.contains("OnBootSec=90s"), "Expected OnBootSec=90s")
    }

    @Test
    fun `periodic timer boot delay floors at 60s`() {
        // Minimum allowed interval is 15 min = 900s → 900/10 = 90s, above floor.
        // Test with exactly 15 min and verify it's >= 60
        val request = TaskRequest.periodic("short-task", 15.minutes)
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)
        val match = Regex("OnBootSec=(\\d+)s").find(unit)
        val bootSec = match!!.groupValues[1].toLong()
        assertTrue(bootSec >= 60, "OnBootSec should be at least 60s, was ${bootSec}s")
    }

    @Test
    fun `calendar timer has OnCalendar`() {
        val request = TaskRequest.calendar("daily-report", CronExpression.everyDayAt(9))
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(unit.contains("OnCalendar=*-*-* 09:00:00"), "Expected OnCalendar expression")
    }

    @Test
    fun `calendar timer weekday expression`() {
        val request = TaskRequest.calendar("weekday-task", CronExpression.everyWeekdayAt(18))
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(
            unit.contains("OnCalendar=Mon..Fri *-*-* 18:00:00"),
            "Expected weekday OnCalendar expression",
        )
    }

    @Test
    fun `timer has Persistent=true`() {
        val request = TaskRequest.periodic("test-task", 1.hours)
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(unit.contains("Persistent=true"))
    }

    @Test
    fun `timer has Install section`() {
        val request = TaskRequest.periodic("test-task", 1.hours)
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(unit.contains("[Install]"))
        assertTrue(unit.contains("WantedBy=timers.target"))
    }
}
