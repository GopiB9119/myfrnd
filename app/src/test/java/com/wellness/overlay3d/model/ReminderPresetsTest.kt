package com.wellness.overlay3d.model

import com.wellness.overlay3d.model.ReminderPresets.Preset
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPresetsTest {
    private val kolkata = TimeZone.getTimeZone("Asia/Kolkata")

    private fun at(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, millis: Int) =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
        }.timeInMillis

    private fun fields(zone: TimeZone, time: Long) = Calendar.getInstance(zone).apply { timeInMillis = time }.let {
        listOf(it.get(Calendar.YEAR), it.get(Calendar.MONTH), it.get(Calendar.DAY_OF_MONTH),
            it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE), it.get(Calendar.SECOND), it.get(Calendar.MILLISECOND))
    }

    @Test fun relativePresetsStoreTheMinuteTheLabelShows() {
        // Regression: the label showed 6:03 AM while 06:03:12.489 was stored.
        val now = at(kolkata, 2026, Calendar.NOVEMBER, 7, 5, 48, 12, 489)
        val in15 = ReminderPresets.dueTime(Preset.IN_15_MIN, now, kolkata)
        val in60 = ReminderPresets.dueTime(Preset.IN_1_HOUR, now, kolkata)
        assertEquals(listOf(2026, Calendar.NOVEMBER, 7, 6, 3, 0, 0), fields(kolkata, in15))
        assertEquals(listOf(2026, Calendar.NOVEMBER, 7, 6, 48, 0, 0), fields(kolkata, in60))
    }

    @Test fun relativePresetsStayInTheFuture() {
        val now = at(kolkata, 2026, Calendar.NOVEMBER, 7, 5, 48, 59, 999)
        val in15 = ReminderPresets.dueTime(Preset.IN_15_MIN, now, kolkata)
        assertTrue(in15 - now in 14 * 60_000L..15 * 60_000L)
        val in60 = ReminderPresets.dueTime(Preset.IN_1_HOUR, now, kolkata)
        assertTrue(in60 - now in 59 * 60_000L..60 * 60_000L)
    }

    @Test fun nextEveningIsTodayBeforeEightAndTomorrowFromEight() {
        val morning = at(kolkata, 2026, Calendar.NOVEMBER, 7, 9, 15, 30, 0)
        assertEquals(listOf(2026, Calendar.NOVEMBER, 7, 20, 0, 0, 0),
            fields(kolkata, ReminderPresets.dueTime(Preset.NEXT_EVENING, morning, kolkata)))
        val eight = at(kolkata, 2026, Calendar.NOVEMBER, 7, 20, 0, 0, 0)
        assertEquals(listOf(2026, Calendar.NOVEMBER, 8, 20, 0, 0, 0),
            fields(kolkata, ReminderPresets.dueTime(Preset.NEXT_EVENING, eight, kolkata)))
    }

    @Test fun tomorrowMorningIsNineOnTheNextCalendarDay() {
        val lateNight = at(kolkata, 2026, Calendar.DECEMBER, 31, 23, 59, 59, 999)
        assertEquals(listOf(2027, Calendar.JANUARY, 1, 9, 0, 0, 0),
            fields(kolkata, ReminderPresets.dueTime(Preset.TOMORROW_MORNING, lateNight, kolkata)))
    }

    @Test fun tomorrowMorningKeepsNineAcrossADaylightSavingChange() {
        // New York leaves daylight saving time on 2026-11-01 at 02:00.
        val newYork = TimeZone.getTimeZone("America/New_York")
        val evening = at(newYork, 2026, Calendar.OCTOBER, 31, 22, 0, 0, 0)
        assertEquals(listOf(2026, Calendar.NOVEMBER, 1, 9, 0, 0, 0),
            fields(newYork, ReminderPresets.dueTime(Preset.TOMORROW_MORNING, evening, newYork)))
    }
}
