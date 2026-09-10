package com.nila.monitor

import com.nila.audio.CryEvidence
import com.nila.audio.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation ladder decides when a parent gets woken. Both failure modes
 * are bad in different ways -- waking someone who did not need waking, and not
 * waking someone who did -- so the boundaries are pinned by tests.
 */
class EscalationTest {

    private fun evidence(
        seconds: Int,
        trend: Trend = Trend.STEADY,
        envelope: List<Int> = List(seconds.coerceAtLeast(1)) { 60 },
    ) = CryEvidence(
        durationSeconds = seconds,
        peakDbfs = -20f,
        trend = trend,
        detectorConfidence = 0.9f,
        envelope = envelope,
        hypothesis = null,
    )

    @Test
    fun `a short fuss is logged and nothing else`() {
        val e = Escalation()
        assertTrue(e.next(evidence(5), true) is Escalation.Decision.LogNote)
        assertTrue(e.next(evidence(8), true) is Escalation.Decision.Wait)
        assertEquals(0, e.attemptCount)
    }

    /**
     * Windows as the service delivers them: one every 480 ms, so a rung that
     * fires on the same second as another still gets its own call. Tests that
     * jumped straight from 5 s to 25 s in one call were fine until the ladder
     * gained a rung, and then they were testing call ordering rather than
     * timing.
     */
    private fun pump(
        e: Escalation,
        toSeconds: Int,
        fromSeconds: Int = 0,
        trend: Trend = Trend.STEADY,
        sootherAvailable: Boolean = true,
        onDecision: (Int, Escalation.Decision) -> Unit = { _, _ -> },
    ) {
        for (s in fromSeconds..toSeconds) {
            onDecision(s, e.next(evidence(s, trend), sootherAvailable))
        }
    }

    @Test
    fun `a sustained cry triggers a soother before a parent is woken`() {
        val e = Escalation()
        var soothedAt = -1
        pump(e, toSeconds = 25) { s, d ->
            if (d is Escalation.Decision.PlaySoother && soothedAt < 0) soothedAt = s
        }
        assertTrue("expected a soother by 25s, got $soothedAt", soothedAt in 20..25)
        assertTrue(!e.hasEscalated)
    }

    @Test
    fun `the reason is read at twenty seconds, not at four`() {
        val e = Escalation()
        var readAt = -1
        pump(e, toSeconds = 40) { s, d ->
            if (d is Escalation.Decision.ReadReason && readAt < 0) readAt = s
        }
        assertEquals("the cause rung is due at 20s", 20, readAt)
    }

    @Test
    fun `the reason is read once per episode`() {
        val e = Escalation()
        var reads = 0
        pump(e, toSeconds = 89) { _, d ->
            if (d is Escalation.Decision.ReadReason) reads++
        }
        assertEquals(1, reads)
    }

    @Test
    fun `the episode closes at three minutes and only once`() {
        val e = Escalation()
        var closes = 0
        var closedAt = -1
        pump(e, toSeconds = 240) { s, d ->
            if (d is Escalation.Decision.CloseEpisode) {
                closes++
                if (closedAt < 0) closedAt = s
            }
        }
        assertEquals("closed exactly once", 1, closes)
        assertEquals("closed at the three-minute rung", 180, closedAt)
        assertTrue(e.hasClosed)
    }

    /**
     * The close rung has to outrank the "already escalated" short circuit. It
     * did not, in the first version of this: an episode that woke somebody at
     * ninety seconds returned Wait forever and never produced a summary, so
     * the clip and the steps were written and then never shown to anyone.
     */
    @Test
    fun `an episode that woke somebody still closes`() {
        val e = Escalation()
        var closed = false
        pump(e, toSeconds = 200) { _, d ->
            if (d is Escalation.Decision.CloseEpisode) closed = true
        }
        assertTrue("escalated first", e.hasEscalated)
        assertTrue("an escalated episode must still close", closed)
    }

    @Test
    fun `a parent is woken at ninety seconds, not a hundred`() {
        val e = Escalation()
        var escalatedAt = -1
        pump(e, toSeconds = 120) { s, d ->
            if (d is Escalation.Decision.Escalate && escalatedAt < 0) escalatedAt = s
        }
        assertEquals(90, escalatedAt)
    }

    @Test
    fun `the soother is verified rather than assumed to have worked`() {
        val e = Escalation()
        var verifiedAt = -1
        pump(e, toSeconds = 62) { s, d ->
            if (d is Escalation.Decision.VerifySoother && verifiedAt < 0) verifiedAt = s
        }
        assertTrue("expected verification by 62s, got $verifiedAt", verifiedAt > 0)
    }

    @Test
    fun `a rising cry after an attempt escalates`() {
        val e = Escalation()
        pump(e, toSeconds = 62)                          // soothe, then verify
        val decision = e.next(evidence(70, Trend.RISING), true)
        assertTrue("expected escalation, got $decision",
                   decision is Escalation.Decision.Escalate)
    }

    @Test
    fun `the hard ceiling escalates regardless of trend`() {
        val e = Escalation()
        e.next(evidence(5), true)
        val decision = e.next(evidence(120, Trend.SETTLING), true)
        assertTrue("a two-minute cry must reach a parent, got $decision",
                   decision is Escalation.Decision.Escalate)
    }

    @Test
    fun `with no soother available it still escalates on time`() {
        val e = Escalation()
        e.next(evidence(5), false)
        var escalated = false
        for (s in listOf(30, 60, 90, 105)) {
            if (e.next(evidence(s), false) is Escalation.Decision.Escalate) escalated = true
        }
        assertTrue("must escalate even with nothing to play", escalated)
    }

    @Test
    fun `it does not escalate twice`() {
        val e = Escalation()
        e.next(evidence(5), true)
        e.next(evidence(120), true)
        repeat(5) {
            assertTrue(e.next(evidence(130), true) is Escalation.Decision.Wait)
        }
    }

    @Test
    fun `settling is judged from the envelope, not asserted`() {
        val e = Escalation()
        assertTrue(e.judgeSettled(evidence(40, Trend.SETTLING)))

        // A big drop from the peak counts even if the fitted trend is flat.
        val dropped = evidence(40, Trend.STEADY,
                               envelope = listOf(90, 88, 85, 80, 40, 35, 30, 28, 25, 22))
        assertTrue(e.judgeSettled(dropped))

        val stillLoud = evidence(40, Trend.STEADY, envelope = List(10) { 88 })
        assertTrue(!e.judgeSettled(stillLoud))
    }

    @Test
    fun `reset clears state between episodes`() {
        val e = Escalation()
        e.next(evidence(5), true)
        e.next(evidence(120), true)
        assertTrue(e.hasEscalated)
        e.reset()
        assertTrue(!e.hasEscalated)
        assertEquals(0, e.attemptCount)
        assertTrue(e.next(evidence(5), true) is Escalation.Decision.LogNote)
    }

    /**
     * The demo replays a looped clip, which reads as a steady cry rather than a
     * rising one -- so it takes the two-attempt path, and that is what makes
     * "white noise, then the caregiver's own recording" visible on screen.
     * If this shape ever changes the demo silently becomes a one-sound demo.
     */
    @Test
    fun `a steady cry gets two sounds before anyone is woken`() {
        val e = Escalation()
        val played = mutableListOf<Int>()
        var escalatedAt = -1

        // One window every 480 ms, as the service feeds them.
        var seconds = 0
        while (seconds <= 115 && escalatedAt < 0) {
            when (val d = e.next(evidence(seconds, Trend.STEADY), true)) {
                is Escalation.Decision.PlaySoother -> played += d.attempt
                is Escalation.Decision.Escalate -> escalatedAt = seconds
                else -> Unit
            }
            seconds++
        }

        assertEquals("expected two soothing attempts", listOf(1, 2), played)
        assertTrue("expected an alert after both sounds, got $escalatedAt",
                   escalatedAt in 56..115)
    }
}
