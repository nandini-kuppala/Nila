package com.nila.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hysteresis is the difference between a monitor and a fire alarm, so it
 * gets tested rather than tuned by feel.
 */
class CryStateMachineTest {

    private fun feed(sm: CryStateMachine, probs: List<Float>, startMs: Long = 0L) =
        probs.mapIndexed { i, p -> sm.update(p, -30f, startMs + i * 480L) }

    @Test
    fun `a single loud window does not start an episode`() {
        val sm = CryStateMachine()
        val results = feed(sm, listOf(0.95f, 0.1f, 0.1f, 0.1f))
        assertTrue(results.all { it is CryStateMachine.Transition.Quiet })
        assertNull(sm.episode)
    }

    @Test
    fun `three of five positive windows starts an episode`() {
        val sm = CryStateMachine()
        val results = feed(sm, listOf(0.9f, 0.2f, 0.9f, 0.9f))
        assertTrue(results.any { it is CryStateMachine.Transition.Started })
        assertNotNull(sm.episode)
    }

    @Test
    fun `a brief pause does not end an episode`() {
        val sm = CryStateMachine()
        feed(sm, listOf(0.9f, 0.9f, 0.9f))
        assertNotNull(sm.episode)

        // A baby drawing breath: a few quiet windows mid-cry.
        val results = feed(sm, listOf(0.1f, 0.1f, 0.1f), startMs = 2000L)
        assertTrue(results.none { it is CryStateMachine.Transition.Ended })
        assertNotNull(sm.episode)
    }

    @Test
    fun `sustained quiet ends the episode`() {
        val sm = CryStateMachine()
        feed(sm, listOf(0.9f, 0.9f, 0.9f))
        val results = feed(sm, List(7) { 0.05f }, startMs = 2000L)
        assertTrue(results.any { it is CryStateMachine.Transition.Ended })
        assertNull(sm.episode)
    }

    @Test
    fun `exit threshold is lower than entry threshold`() {
        val sm = CryStateMachine()
        feed(sm, listOf(0.9f, 0.9f, 0.9f))
        // 0.5 is below the 0.62 entry bar but above the 0.42 exit bar, so an
        // episode already running should survive it.
        val results = feed(sm, List(8) { 0.5f }, startMs = 2000L)
        assertTrue(results.none { it is CryStateMachine.Transition.Ended })
    }
}

class CryEpisodeTest {

    @Test
    fun `rising envelope is reported as rising`() {
        val ep = CryEpisode(startedAtMs = 0L)
        repeat(20) { ep.record(-50f + it * 1.0f, 0.9f) }
        assertEquals(Trend.RISING, ep.trend())
    }

    @Test
    fun `falling envelope is reported as settling`() {
        val ep = CryEpisode(startedAtMs = 0L)
        repeat(20) { ep.record(-20f - it * 1.0f, 0.9f) }
        assertEquals(Trend.SETTLING, ep.trend())
    }

    @Test
    fun `noisy but flat envelope is reported as steady`() {
        val ep = CryEpisode(startedAtMs = 0L)
        val jitter = listOf(0f, 0.6f, -0.5f, 0.3f, -0.4f, 0.2f, -0.2f, 0.5f, -0.3f, 0.1f)
        repeat(2) { r -> jitter.forEach { ep.record(-35f + it, 0.9f) } }
        assertEquals(Trend.STEADY, ep.trend())
    }

    @Test
    fun `envelope normalises into the sparkline range`() {
        val ep = CryEpisode(startedAtMs = 0L)
        ep.record(-60f, 0.9f)
        ep.record(-30f, 0.9f)
        ep.record(0f, 0.9f)
        assertEquals(listOf(0, 50, 100), ep.normalisedEnvelope())
    }

    /**
     * Duration comes from the audio clock, not the wall clock.
     *
     * Two things depend on this. A phone that falls behind on analysis must not
     * report a duration for audio nobody has looked at yet; and the demo mode
     * replays a recorded cry faster than real time, which only works because the
     * escalation ladder counts window timestamps rather than seconds elapsed.
     */
    @Test
    fun durationFollowsTheWindowTimestampsNotTheWallClock() {
        val machine = CryStateMachine()
        var clock = 1_000_000L
        var episode: CryEpisode? = null

        repeat(30) {
            clock += 480
            when (val t = machine.update(0.95f, -20f, clock)) {
                is CryStateMachine.Transition.Started -> episode = t.episode
                is CryStateMachine.Transition.Continued -> episode = t.episode
                else -> Unit
            }
        }

        // 30 windows at 480 ms is a little over 14 s of audio, played out here
        // in microseconds of real time.
        val seconds = episode!!.durationSeconds
        assertTrue("expected ~13s of audio, got ${seconds}s", seconds in 11..14)
    }
}
