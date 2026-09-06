package com.nila.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera lane's alert logic, exhaustively.
 *
 * Both failure modes matter and they pull in opposite directions: alerting on a
 * baby who merely turned their head trains people to ignore the app, and not
 * alerting when the face is genuinely covered defeats the point of it.
 */
class FaceRulesTest {

    private fun motion(relative: Float, energy: Float = 0.05f) =
        MotionEnergy.Reading(energy = energy, activeFraction = 0.2f,
                             relativeToBaseline = relative)

    private fun obs(face: Boolean, relative: Float = 1f, baselineReady: Boolean = true) =
        FaceRules.Observation(face, motion(relative), baselineReady)

    private fun feed(rules: FaceRules, n: Int, o: FaceRules.Observation) =
        (1..n).map { rules.update(o) }.last()

    @Test
    fun `starts in the starting state`() {
        val rules = FaceRules()
        assertTrue(rules.update(obs(face = false)) is FaceWatcher.State.Starting)
    }

    @Test
    fun `a face becomes visible after the confirm run`() {
        val rules = FaceRules(presentFramesToClear = 4)
        assertTrue(feed(rules, 4, obs(face = true)) is FaceWatcher.State.FaceVisible)
    }

    @Test
    fun `a brief head turn does not raise an alert`() {
        val rules = FaceRules(missingFramesToAlert = 12)
        feed(rules, 6, obs(face = true))
        // Eleven frames without a face is under the bar. At 5 fps that is about
        // two seconds, which is a baby looking away, not an emergency.
        val state = feed(rules, 11, obs(face = false))
        assertTrue("alerted too early: $state", state !is FaceWatcher.State.FaceMissing)
    }

    @Test
    fun `a sustained loss of the face raises an alert`() {
        val rules = FaceRules(missingFramesToAlert = 12)
        feed(rules, 6, obs(face = true))
        val state = feed(rules, 12, obs(face = false))
        assertTrue("expected FaceMissing, got $state",
                   state is FaceWatcher.State.FaceMissing)
    }

    @Test
    fun `hidden but moving is reported differently from hidden and still`() {
        val moving = FaceRules().also { feed(it, 6, obs(face = true)) }
        val movingState = feed(moving, 14, obs(face = false, relative = 1.2f))
                as FaceWatcher.State.FaceMissing
        assertTrue("a thrashing baby with a covered face is still moving",
                   movingState.stillMoving)

        val quiet = FaceRules().also { feed(it, 6, obs(face = true)) }
        val quietState = feed(quiet, 14, obs(face = false, relative = 0.1f))
                as FaceWatcher.State.FaceMissing
        assertTrue("a covered face with no movement is the worse case",
                   !quietState.stillMoving)
    }

    @Test
    fun `the alert clears once the face comes back`() {
        val rules = FaceRules(missingFramesToAlert = 12, presentFramesToClear = 4)
        feed(rules, 6, obs(face = true))
        assertTrue(feed(rules, 14, obs(face = false)) is FaceWatcher.State.FaceMissing)
        val recovered = feed(rules, 4, obs(face = true))
        assertTrue("should recover, got $recovered",
                   recovered is FaceWatcher.State.FaceVisible)
    }

    @Test
    fun `recovery is quicker to trust than loss`() {
        // Asymmetry is the design: a lingering alert after the situation
        // resolved is how people learn to ignore alerts.
        val rules = FaceRules(missingFramesToAlert = 12, presentFramesToClear = 4)
        assertTrue(12 > 4)
        feed(rules, 6, obs(face = true))
        feed(rules, 14, obs(face = false))
        assertTrue(feed(rules, 4, obs(face = true)) is FaceWatcher.State.FaceVisible)
    }

    @Test
    fun `stillness is not reported before the baseline settles`() {
        val rules = FaceRules(stillnessFramesToAlert = 40)
        feed(rules, 6, obs(face = true))
        // Very quiet, but we have nothing to compare against yet.
        val state = feed(rules, 60, obs(face = true, relative = 0.01f,
                                        baselineReady = false))
        assertTrue("must not cry wolf before a baseline exists: $state",
                   state !is FaceWatcher.State.UnusuallyStill)
    }

    @Test
    fun `sustained stillness is reported once the baseline exists`() {
        val rules = FaceRules(stillnessFramesToAlert = 40)
        feed(rules, 6, obs(face = true))
        val state = feed(rules, 40, obs(face = true, relative = 0.05f))
        assertTrue("expected UnusuallyStill, got $state",
                   state is FaceWatcher.State.UnusuallyStill)
    }

    @Test
    fun `the stillness counter resets when movement returns`() {
        val rules = FaceRules(stillnessFramesToAlert = 40)
        feed(rules, 6, obs(face = true))
        feed(rules, 39, obs(face = true, relative = 0.05f))   // one frame short
        feed(rules, 1, obs(face = true, relative = 1.0f))     // normal movement
        val state = feed(rules, 39, obs(face = true, relative = 0.05f))
        assertTrue("counter should have restarted: $state",
                   state !is FaceWatcher.State.UnusuallyStill)
    }

    @Test
    fun `a missing face outranks stillness`() {
        // Both can be true at once. Not being able to see the face is the more
        // actionable of the two, so it must win.
        val rules = FaceRules(missingFramesToAlert = 12, stillnessFramesToAlert = 40)
        feed(rules, 6, obs(face = true))
        val state = feed(rules, 60, obs(face = false, relative = 0.05f))
        assertTrue("expected FaceMissing to win, got $state",
                   state is FaceWatcher.State.FaceMissing)
    }

    @Test
    fun `reset returns to the starting state`() {
        val rules = FaceRules()
        feed(rules, 20, obs(face = true))
        rules.reset()
        assertTrue(rules.update(obs(face = false)) is FaceWatcher.State.Starting)
    }
}

class MotionEnergyTest {
    @Test
    fun `rms of an empty list is zero`() {
        assertEquals(0f, MotionEnergy.rms(emptyList()), 1e-6f)
    }

    @Test
    fun `rms matches the definition`() {
        // sqrt((9 + 16 + 25 + 36 + 49) / 5) = sqrt(27)
        assertEquals(5.19615f, MotionEnergy.rms(listOf(3f, 4f, 5f, 6f, 7f)), 1e-4f)
    }

    @Test
    fun `rms of a constant series is that constant`() {
        assertEquals(4f, MotionEnergy.rms(List(10) { 4f }), 1e-5f)
    }
}
