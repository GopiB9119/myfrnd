package com.wellness.overlay3d.model

import org.junit.Assert.*
import org.junit.Test

class ReminderScheduleTest {
    @Test fun initialDeadlinesUseElapsedTime() {
        val schedule = ReminderSchedule(500L, 30, 15, true)
        assertEquals(1_800_500L, schedule.nextWater)
        assertEquals(900_500L, schedule.nextEye)
    }

    @Test fun reopeningDashboardDoesNotResetEitherDeadline() {
        val schedule = ReminderSchedule(0, 30, 15, true)
        schedule.configure(60_000L, 30, 15)
        assertEquals(1_800_000L, schedule.nextWater)
        assertEquals(900_000L, schedule.nextEye)
    }

    @Test fun changingWaterDoesNotResetEyes() {
        val schedule = ReminderSchedule(0, 30, 15, true)
        schedule.configure(100L, 45, 15)
        assertEquals(2_700_100L, schedule.nextWater)
        assertEquals(900_000L, schedule.nextEye)
    }

    @Test fun changingEyesDoesNotResetWater() {
        val schedule = ReminderSchedule(0, 30, 15, true)
        schedule.configure(100L, 30, 20)
        assertEquals(1_800_000L, schedule.nextWater)
        assertEquals(1_200_100L, schedule.nextEye)
    }

    @Test fun lockResetsContinuousEyeSessionButRetainsWater() {
        val schedule = ReminderSchedule(0, 30, 15, true)
        schedule.screenChanged(100L, false)
        assertNull(schedule.nextEye)
        schedule.screenChanged(200L, true)
        assertEquals(900_200L, schedule.nextEye)
        assertEquals(1_800_000L, schedule.nextWater)
    }

    @Test fun duplicateUnlockDoesNotExtendEyeTimer() {
        val schedule = ReminderSchedule(0, 30, 15, true)
        schedule.screenChanged(100L, true)
        assertEquals(900_000L, schedule.nextEye)
    }

    @Test fun deliveredEyeBreakSchedulesNextBreak() {
        val schedule = ReminderSchedule(0, 30, 15, true)
        schedule.eyeDelivered(900_000L)
        assertEquals(1_800_000L, schedule.nextEye)
    }

    @Test fun overdueWaterIsNotLostUntilDelivered() {
        val schedule = ReminderSchedule(0, 30, 15, false)
        assertEquals(0L, schedule.nextDelay(2_000_000L, null))
        assertEquals(1_800_000L, schedule.nextWater)
        schedule.waterDelivered(2_000_000L)
        assertEquals(3_800_000L, schedule.nextWater)
    }

    @Test fun earliestTaskDeterminesWakeupWithoutPolling() {
        val schedule = ReminderSchedule(0, 30, 15, true)
        assertEquals(1234L, schedule.nextDelay(0, 1234L))
        assertEquals(900_000L, schedule.nextDelay(0, null))
        assertEquals(0L, schedule.nextDelay(0, -100L))
    }

    @Test fun configurationWhileLockedDoesNotStartEyeSession() {
        val schedule = ReminderSchedule(0, 30, 15, false)
        schedule.configure(100, 30, 20)
        assertNull(schedule.nextEye)
        schedule.screenChanged(200, true)
        assertEquals(1_200_200L, schedule.nextEye)
    }
}
