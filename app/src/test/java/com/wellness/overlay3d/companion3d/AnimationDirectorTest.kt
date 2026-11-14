package com.wellness.overlay3d.companion3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationDirectorTest {
    private val idle = 0
    private val wave = 1
    private val altIdle = 2
    private val brokenClip = 3

    private fun director() = AnimationDirector(floatArrayOf(2f, 1f, 1.5f, 0f), fadeSeconds = 0.5f)

    private fun AnimationDirector.step(seconds: Float) = update(seconds)!!

    @Test fun loopClipPlaysAndWrapsAround() {
        val director = director()
        assertNull("nothing to pose before a clip is chosen", director.update(0.1f))
        director.setLoop(idle)
        repeat(8) { director.update(0.25f) }
        val pose = director.step(0.25f)
        assertEquals(idle, pose.clip)
        assertEquals(0.25f, pose.time, 1e-4f)
        assertEquals(-1, pose.previousClip)
        assertEquals(1f, pose.blend, 0f)
        assertFalse(director.isPlayingOneShot)
    }

    @Test fun oneShotPlaysOnceThenFadesBackToTheLoopFromItsLastFrame() {
        val director = director()
        director.setLoop(idle)
        director.update(0.25f)
        listOf(-1, brokenClip, 4).forEach { assertFalse("clip $it", director.playOnce(it)) }
        assertEquals(idle, director.currentClip)

        assertTrue(director.playOnce(wave))
        repeat(2) { director.update(0.25f) }
        var pose = director.step(0.25f)
        assertTrue(director.isPlayingOneShot)
        assertEquals(wave, pose.clip)
        assertEquals(0.75f, pose.time, 1e-4f)

        pose = director.step(0.25f)
        assertFalse(director.isPlayingOneShot)
        assertEquals(idle, pose.clip)
        assertEquals(0f, pose.time, 1e-4f)
        assertEquals(wave, pose.previousClip)
        assertEquals("the wave holds its final frame instead of snapping back", 1f, pose.previousTime, 0.01f)
        assertEquals(0f, pose.blend, 1e-4f)

        pose = director.step(0.25f)
        assertEquals(1f, pose.previousTime, 0.01f)
        pose = director.step(0.25f)
        assertEquals(idle, pose.clip)
        assertEquals(-1, pose.previousClip)
        assertEquals(1f, pose.blend, 0f)
    }

    @Test fun crossfadeWeightRisesFromZeroToOneWhileTheOldLoopKeepsMoving() {
        val director = director()
        director.setLoop(idle)
        director.update(0.25f)
        director.setLoop(altIdle)
        val blends = mutableListOf<Float>()
        val previousTimes = mutableListOf<Float>()
        repeat(3) {
            val pose = director.step(0.125f)
            assertEquals(altIdle, pose.clip)
            assertEquals(idle, pose.previousClip)
            blends += pose.blend
            previousTimes += pose.previousTime
        }
        assertTrue("$blends", blends.all { it > 0f && it < 1f })
        assertTrue("$blends", blends.zipWithNext().all { (a, b) -> b > a })
        assertTrue("$previousTimes", previousTimes.zipWithNext().all { (a, b) -> b > a })

        val pose = director.step(0.125f)
        assertEquals(altIdle, pose.clip)
        assertEquals(0.5f, pose.time, 1e-4f)
        assertEquals(-1, pose.previousClip)
        assertEquals(1f, pose.blend, 0f)
    }

    @Test fun loopChosenDuringARepeatedGestureStartsOnlyWhenTheGestureEnds() {
        val director = director()
        director.setLoop(idle)
        director.update(0.25f)
        assertTrue(director.playOnce(wave, repeats = 2))
        director.setLoop(altIdle)
        assertEquals(wave, director.currentClip)

        repeat(4) { director.update(0.25f) }
        var pose = director.step(0.25f)
        assertEquals("second wave has started", 0.25f, pose.time, 1e-4f)
        repeat(2) { director.update(0.25f) }
        assertTrue(director.isPlayingOneShot)
        assertEquals(wave, director.currentClip)

        pose = director.step(0.25f)
        assertEquals(altIdle, pose.clip)
        assertEquals(wave, pose.previousClip)
        assertFalse(director.isPlayingOneShot)
    }

    @Test fun settleDropsTheGestureAndCrossfadeAtOnce() {
        val director = director()
        director.setLoop(idle)
        director.update(0.25f)
        director.playOnce(wave)
        director.update(0.125f)
        director.settle()
        assertFalse(director.isPlayingOneShot)
        val pose = director.step(0f)
        assertEquals(idle, pose.clip)
        assertEquals(0f, pose.time, 0f)
        assertEquals(-1, pose.previousClip)
        assertEquals(1f, pose.blend, 0f)
    }

    @Test fun gestureWithoutALoopEndsAndHoldsItsLastFrame() {
        val director = director()
        assertTrue(director.playOnce(wave))
        repeat(6) { director.update(0.25f) }
        val pose = director.step(0.25f)
        assertFalse("a later tap must be able to start the gesture again", director.isPlayingOneShot)
        assertEquals(wave, pose.clip)
        assertEquals(1f, pose.time, 0.01f)
        assertTrue(director.playOnce(wave))
        assertEquals(0f, director.step(0f).time, 0f)
    }

    @Test fun modelWithoutClipsNoOpsAndNeverPoses() {
        val director = AnimationDirector(FloatArray(0))
        director.setLoop(0)
        assertFalse(director.playOnce(0))
        director.settle()
        assertNull(director.update(0.016f))
        assertEquals(-1, director.currentClip)
        assertFalse(director.isPlayingOneShot)
    }
}
