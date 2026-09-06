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

    @Test
    fun `a sustained cry triggers a soother before a parent is woken`() {
        val e = Escalation()
        e.next(evidence(5), true)
        val decision = e.next(evidence(25), true)
        assertTrue("expected a soother at 25s, got $decision",
                   decision is Escalation.Decision.PlaySoother)
        assertTrue(!e.hasEscalated)
    }

    @Test
    fun `the soother is verified rather than assumed to have worked`() {
        val e = Escalation()
        e.next(evidence(5), true)
        e.next(evidence(25), true)
        val decision = e.next(evidence(62), true)
        assertTrue("expected verification, got $decision",
                   decision is Escalation.Decision.VerifySoother)
    }

    @Test
    fun `a rising cry after an attempt escalates`() {
        val e = Escalation()
        e.next(evidence(5), true)
        e.next(evidence(25), true)
        e.next(evidence(62), true)                       // verify
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
}
