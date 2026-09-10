package com.nila.vision

import com.nila.data.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two lanes, combined.
 *
 * A baby who rolls over in their sleep is a notification. A baby who rolls over
 * *and starts crying* has probably fallen or got stuck, and that is a different
 * phone call -- catching the difference is the whole reason the camera service
 * subscribes to the microphone one.
 *
 * The other half of this is restraint, and it is the half that decides whether
 * the feature survives contact with a real night. Nothing may fire during
 * calibration, on a settled baby, on a view we know to be unreliable, or twice
 * for the same thing.
 */
class WatchAlertTest {

    private fun assessment(
        vararg events: ActivityRules.Event,
    ) = ActivityRules.Assessment(
        events = events.toList().sortedByDescending { it.severity.level },
        posture = Posture.ON_BACK,
        distanceRatio = 1f,
        travel = 0f,
        zoneOvershoot = 0f,
    )

    private fun state(
        vararg events: ActivityRules.Event,
        crying: Boolean = false,
        cameraMoved: Boolean = false,
        running: Boolean = true,
    ) = WatchState(
        running = running,
        assessment = if (events.isEmpty()) null else assessment(*events),
        cryingNow = crying,
        cameraMoved = cameraMoved,
    )

    @Test
    fun `a settled baby is never an alert`() {
        assertNull(WatchAlert.decide(state(ActivityRules.Event.SETTLED)))
        assertNull(WatchAlert.decide(state(ActivityRules.Event.ON_BACK)))
        assertNull(WatchAlert.decide(state(ActivityRules.Event.FACE_ONLY)))
    }

    @Test
    fun `nothing fires before the baseline exists`() {
        assertNull(WatchAlert.decide(state(ActivityRules.Event.CALIBRATING)))
        assertNull(WatchAlert.decide(state()))
    }

    @Test
    fun `a stopped watch cannot alert`() {
        assertNull(
            WatchAlert.decide(state(ActivityRules.Event.ROLLED_TO_FRONT, running = false))
        )
    }

    @Test
    fun `rolling prone wakes somebody`() {
        val decision = WatchAlert.decide(state(ActivityRules.Event.ROLLED_TO_FRONT))
        assertNotNull(decision)
        assertEquals(Severity.URGENT, decision!!.severity)
        assertEquals("Safety alert", decision.title)
        assertTrue(decision.body.contains("front", ignoreCase = true))
    }

    /**
     * Crawling on its own is a normal thing a baby does. Waking a parent for it
     * is the behaviour that teaches people to ignore the alerts that matter.
     */
    @Test
    fun `crawling alone stays on the screen`() {
        assertNull(WatchAlert.decide(state(ActivityRules.Event.CRAWLING)))
    }

    /** The fusion case. Crawling and crying together is a fall until proven otherwise. */
    @Test
    fun `crawling plus crying does wake somebody`() {
        val decision =
            WatchAlert.decide(state(ActivityRules.Event.CRAWLING, crying = true))
        assertNotNull(decision)
        assertEquals(Severity.URGENT, decision!!.severity)
        assertEquals("Your baby needs you", decision.title)
        assertTrue(decision.body.contains("crying"))
    }

    @Test
    fun `an urgent event plus crying is critical`() {
        val decision =
            WatchAlert.decide(state(ActivityRules.Event.ROLLED_TO_FRONT, crying = true))
        assertEquals(Severity.CRITICAL, decision!!.severity)
    }

    @Test
    fun `severity promotion is visible on the state itself`() {
        assertEquals(
            Severity.ATTENTION,
            state(ActivityRules.Event.CRAWLING).severity,
        )
        assertEquals(
            Severity.URGENT,
            state(ActivityRules.Event.CRAWLING, crying = true).severity,
        )
        assertEquals(
            Severity.CRITICAL,
            state(ActivityRules.Event.LEFT_SAFE_ZONE, crying = true).severity,
        )
    }

    /**
     * A view we cannot trust cannot raise an alarm about the baby. The knock
     * is worth one quiet notification of its own, because a baby monitor
     * pointed at a wall is worse than one switched off -- it looks like it is
     * working.
     */
    @Test
    fun `a knocked phone suppresses the vision verdict and reports itself`() {
        val decision = WatchAlert.decide(
            state(ActivityRules.Event.ROLLED_TO_FRONT, cameraMoved = true)
        )
        assertNotNull(decision)
        assertEquals(Severity.ATTENTION, decision!!.severity)
        assertEquals(ActivityRules.Event.CAMERA_MOVED, decision.event)
        assertEquals("Check the camera", decision.title)
        assertTrue(
            "must not claim the baby rolled over on a view it cannot trust",
            !decision.body.contains("front", ignoreCase = true),
        )
    }

    @Test
    fun `a knocked phone does not become critical just because the baby is crying`() {
        val state = state(
            ActivityRules.Event.ROLLED_TO_FRONT, crying = true, cameraMoved = true
        )
        assertEquals(Severity.ATTENTION, state.severity)
    }

    @Test
    fun `the same event does not repeat inside the suppression window`() {
        val s = state(ActivityRules.Event.ROLLED_TO_FRONT)
        assertNotNull(WatchAlert.decide(s, sinceSameEventMs = Long.MAX_VALUE))
        assertNull(WatchAlert.decide(s, sinceSameEventMs = 10_000))
        assertNotNull(
            WatchAlert.decide(s, sinceSameEventMs = WatchAlert.REPEAT_SUPPRESSION_MS + 1)
        )
    }

    @Test
    fun `the camera-moved alert has its own suppression clock`() {
        val s = state(ActivityRules.Event.ROLLED_TO_FRONT, cameraMoved = true)
        // The vision event has never alerted, but the knock just did.
        assertNull(
            WatchAlert.decide(s, sinceSameEventMs = Long.MAX_VALUE,
                              sinceCameraMovedMs = 5_000)
        )
    }

    @Test
    fun `an alert carries the caveat for what it is claiming`() {
        val out = WatchAlert.decide(state(ActivityRules.Event.OUT_OF_VIEW))!!
        assertTrue(
            "out of view must admit it cannot tell a blanket from an empty room",
            out.body.contains("blanket", ignoreCase = true),
        )
    }

    @Test
    fun `every description reaching a lock screen is non-empty`() {
        ActivityRules.Event.entries
            .filter { it.severity.level >= Severity.URGENT.level }
            .forEach { event ->
                val decision = WatchAlert.decide(state(event))
                assertNotNull("$event produced no alert", decision)
                assertTrue("$event has an empty body", decision!!.body.length > 5)
            }
    }
}
