package com.wellness.overlay3d.model

import java.util.Calendar
import java.util.TimeZone

/** Due times for the task editor's quick-time pills, always on a whole minute so they match the minute-precision label. */
object ReminderPresets {
    enum class Preset { IN_15_MIN, IN_1_HOUR, NEXT_EVENING, TOMORROW_MORNING }

    fun dueTime(preset: Preset, nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        when (preset) {
            Preset.IN_15_MIN -> calendar.add(Calendar.MINUTE, 15)
            Preset.IN_1_HOUR -> calendar.add(Calendar.HOUR_OF_DAY, 1)
            Preset.NEXT_EVENING -> calendar.set(Calendar.HOUR_OF_DAY, 20)
            Preset.TOMORROW_MORNING -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 9)
            }
        }
        if (preset == Preset.NEXT_EVENING || preset == Preset.TOMORROW_MORNING) calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (preset == Preset.NEXT_EVENING && calendar.timeInMillis <= nowMillis) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }
}
