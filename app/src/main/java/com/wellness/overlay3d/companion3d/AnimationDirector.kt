package com.wellness.overlay3d.companion3d

/**
 * Decides which skeletal clip plays and how two clips blend, without touching Filament, so the
 * timing rules can be unit-tested. One looping clip runs underneath; one-shot clips (a wave, a
 * reach for water) play on top and then cross-fade back to the loop.
 */
class AnimationDirector(
    private val clipDurations: FloatArray,
    private val fadeSeconds: Float = 0.3f
) {
    /** The pose to apply this frame. [previousClip] is -1 when no cross-fade is running. */
    class Pose {
        var clip = -1; internal set
        var time = 0f; internal set
        var previousClip = -1; internal set
        var previousTime = 0f; internal set
        /** Weight of [clip]; 1 means [previousClip] no longer contributes. */
        var blend = 1f; internal set
    }

    private val pose = Pose()
    private var loopClip = -1
    private var current = -1
    private var currentTime = 0f
    private var currentIsOneShot = false
    /** A finished one-shot with no loop to return to keeps its last frame. */
    private var holding = false
    private var oneShotLength = 0f
    private var previous = -1
    private var previousTime = 0f
    private var previousFrozen = false
    private var fadeElapsed = 0f

    val isPlayingOneShot: Boolean get() = currentIsOneShot
    val currentClip: Int get() = current

    /** Sets the clip to loop. A running one-shot finishes first; [startNow] false only records it. */
    fun setLoop(clip: Int, startNow: Boolean = true) {
        if (!valid(clip)) return
        loopClip = clip
        if (startNow && !currentIsOneShot) transitionTo(clip, restart = false)
    }

    /** Ends any one-shot and cross-fade at once, e.g. for a still pose when motion is reduced. */
    fun settle() {
        previous = -1
        if (valid(loopClip) && (currentIsOneShot || current != loopClip)) {
            current = loopClip
            currentTime = 0f
            holding = false
        } else if (currentIsOneShot) {
            holding = true
        }
        currentIsOneShot = false
    }

    /** Plays [clip] [repeats] times, then returns to the loop (or holds its last frame if there is
     *  none). Returns false for unknown clips. */
    fun playOnce(clip: Int, repeats: Int = 1): Boolean {
        if (!valid(clip)) return false
        transitionTo(clip, restart = true)
        currentIsOneShot = true
        oneShotLength = clipDurations[clip] * repeats.coerceAtLeast(1)
        return true
    }

    fun update(deltaSeconds: Float): Pose? {
        if (current < 0) return null
        val dt = deltaSeconds.coerceIn(0f, 0.25f)
        if (!holding) currentTime += dt
        if (previous >= 0) {
            if (!previousFrozen) previousTime += dt
            fadeElapsed += dt
            if (fadeElapsed >= fadeSeconds) previous = -1
        }
        if (currentIsOneShot && currentTime >= oneShotLength) {
            if (valid(loopClip)) {
                transitionTo(loopClip, restart = true)
            } else {
                currentIsOneShot = false
                holding = true
                currentTime = oneShotLength
            }
        }
        pose.clip = current
        pose.time = timeIn(current, currentTime, currentIsOneShot || holding)
        if (previous >= 0) {
            pose.previousClip = previous
            pose.previousTime = timeIn(previous, previousTime, hold = previousFrozen)
            pose.blend = smoothstep(fadeElapsed / fadeSeconds)
        } else {
            pose.previousClip = -1
            pose.previousTime = 0f
            pose.blend = 1f
        }
        return pose
    }

    private fun transitionTo(clip: Int, restart: Boolean) {
        if (clip == current && !restart) return
        if (current >= 0 && fadeSeconds > 0f) {
            previous = current
            // A one-shot freezes on its current frame while fading out; a loop keeps moving.
            previousFrozen = currentIsOneShot
            previousTime = if (currentIsOneShot) {
                timeIn(current, currentTime.coerceAtMost(oneShotLength), hold = true)
            } else {
                currentTime
            }
            fadeElapsed = 0f
        }
        current = clip
        currentTime = 0f
        currentIsOneShot = false
        holding = false
    }

    private fun valid(clip: Int) = clip in clipDurations.indices && clipDurations[clip] > 0f

    /** Loops wrap; a one-shot at or past its end holds its last frame instead of snapping to 0. */
    private fun timeIn(clip: Int, time: Float, hold: Boolean): Float {
        val duration = clipDurations[clip]
        if (duration <= 0f) return 0f
        if (hold && time >= duration && time % duration < 1e-3f) return duration * 0.999f
        return time % duration
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
