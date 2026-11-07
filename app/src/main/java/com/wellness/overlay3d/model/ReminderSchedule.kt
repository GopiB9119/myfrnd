package com.wellness.overlay3d.model

/** Interval deadlines use elapsed time; calendar tasks use wall-clock time separately. */
class ReminderSchedule(
    now: Long,
    waterMinutes: Int,
    eyeMinutes: Int,
    screenActive: Boolean
) {
    var waterInterval = waterMinutes * 60_000L
        private set
    var eyeInterval = eyeMinutes * 60_000L
        private set
    var nextWater = now + waterInterval
        private set
    var nextEye: Long? = if (screenActive) now + eyeInterval else null
        private set

    fun configure(now: Long, waterMinutes: Int, eyeMinutes: Int) {
        require(waterMinutes > 0 && eyeMinutes > 0)
        val water = waterMinutes * 60_000L
        val eye = eyeMinutes * 60_000L
        if (water != waterInterval) nextWater = now + water
        if (eye != eyeInterval && nextEye != null) nextEye = now + eye
        waterInterval = water
        eyeInterval = eye
    }

    fun screenChanged(now: Long, active: Boolean) {
        if (!active) nextEye = null
        else if (nextEye == null) nextEye = now + eyeInterval
    }

    fun waterDelivered(now: Long) {
        nextWater = now + waterInterval
    }

    fun eyeDelivered(now: Long) {
        nextEye = now + eyeInterval
    }

    fun nextDelay(now: Long, nextTaskDelay: Long?): Long =
        listOfNotNull(nextWater - now, nextEye?.minus(now), nextTaskDelay)
            .min().coerceAtLeast(0L)
}
