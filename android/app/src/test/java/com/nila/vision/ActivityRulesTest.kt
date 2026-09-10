package com.nila.vision

import com.nila.data.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the camera lane may raise an alarm.
 *
 * This is the lane most likely to cry wolf: a baby turning over, a blanket over
 * the lens and a phone knocked by a cot rail all look alarming from a single
 * frame. An alarm that fires wrongly twice gets the whole feature switched off,
 * so what these tests defend is mostly the *negative* -- that nothing fires on
 * one frame, during calibration, or on a view we know to be unreliable.
 */
class ActivityRulesTest {

    private val config = ActivityRules.Config()

    private fun reading(
        posture: Posture,
        cx: Float = 0.5f,
        cy: Float = 0.5f,
        torso: Float = 0.30f,
        present: Boolean = true,
        faceVisible: Boolean = posture == Posture.ON_BACK,
    ) = PoseReading(
        present = present,
        posture = posture,
        cx = cx, cy = cy,
        torsoLength = torso,
        faceVisible = faceVisible,
        confidence = 0.9f,
        tiltDegrees = if (posture == Posture.ON_BACK) 85f else 10f,
    )

    private fun observe(
        pose: PoseReading,
        faceFound: Boolean = pose.faceVisible,
        relativeMotion: Float = 1f,
        zone: SafeZone = SafeZone.DEFAULT,
    ) = ActivityRules.Observation(
        pose = pose,
        faceFound = faceFound,
        motion = MotionEnergy.Reading(0.05f, 0.1f, relativeMotion),
        baselineReady = true,
        zone = zone,
    )

    /** Push the same frame through often enough for a rule to believe it. */
    private fun ActivityRules.settle(
        observation: ActivityRules.Observation,
        frames: Int = 40,
    ): ActivityRules.Assessment {
        var last = update(observation)
        repeat(frames - 1) { last = update(observation) }
        return last
    }

    @Test
    fun `nothing fires while the baseline is still being taken`() {
        val rules = ActivityRules()
        val first = rules.update(observe(reading(Posture.ON_FRONT)))
        assertEquals(ActivityRules.Event.CALIBRATING, first.primary)
        assertFalse(rules.calibrated)
    }

    /**
     * One frame of a strange posture is a landmark that wandered, not a baby
     * who moved. The pose model is being asked coarse questions precisely
     * because it cannot be trusted frame to frame on an infant.
     */
    @Test
    fun `a single frame of prone does not raise anything`() {
        val rules = ActivityRules()
        // Calibrate on a settled baby first.
        rules.settle(observe(reading(Posture.ON_BACK)), config.calibrationFrames + 2)
        val one = rules.update(observe(reading(Posture.ON_FRONT)))
        assertTrue(
            "one frame must not produce a roll alert",
            ActivityRules.Event.ROLLED_TO_FRONT !in one.events,
        )
    }

    @Test
    fun `sustained prone is urgent`() {
        val rules = ActivityRules()
        rules.settle(observe(reading(Posture.ON_BACK)), config.calibrationFrames + 2)
        val assessment = rules.settle(observe(reading(Posture.ON_FRONT)))
        assertEquals(ActivityRules.Event.ROLLED_TO_FRONT, assessment.primary)
        assertEquals(Severity.URGENT, assessment.severity)
    }

    @Test
    fun `standing up is urgent`() {
        val rules = ActivityRules()
        rules.settle(observe(reading(Posture.ON_BACK)), config.calibrationFrames + 2)
        val assessment = rules.settle(observe(reading(Posture.UPRIGHT)))
        assertTrue(ActivityRules.Event.STANDING_UP in assessment.events)
        assertEquals(Severity.URGENT, assessment.severity)
    }

    @Test
    fun `rolling onto the side is attention, not urgent`() {
        val rules = ActivityRules()
        rules.settle(observe(reading(Posture.ON_BACK)), config.calibrationFrames + 2)
        val assessment = rules.settle(observe(reading(Posture.ON_SIDE)))
        assertEquals(ActivityRules.Event.ON_SIDE, assessment.primary)
        assertEquals(Severity.ATTENTION, assessment.severity)
    }

    /** Prone plus travel across the frame. Medium, because it is normal. */
    @Test
    fun `crawling is prone plus sustained travel`() {
        val rules = ActivityRules()
        // A wide zone, so this tests travel and not the boundary.
        val zone = SafeZone(0.02f, 0.02f, 0.98f, 0.98f, configured = true)
        rules.settle(observe(reading(Posture.ON_FRONT), zone = zone),
                     config.calibrationFrames + 2)
        var assessment = rules.update(observe(reading(Posture.ON_FRONT), zone = zone))
        // Walk the centroid steadily across the frame, comfortably past the
        // travel threshold over the trailing window.
        var x = 0.15f
        repeat(20) {
            x += 0.04f
            assessment = rules.update(
                observe(reading(Posture.ON_FRONT, cx = x), zone = zone)
            )
        }
        assertTrue(
            "expected crawling in ${assessment.events}",
            ActivityRules.Event.CRAWLING in assessment.events,
        )
        assertEquals(Severity.ATTENTION, ActivityRules.Event.CRAWLING.severity)
    }

    @Test
    fun `a baby who stays put is not crawling`() {
        val rules = ActivityRules()
        val assessment = rules.settle(observe(reading(Posture.ON_FRONT)), 60)
        assertFalse(ActivityRules.Event.CRAWLING in assessment.events)
    }

    @Test
    fun `leaving the safe zone is urgent`() {
        val rules = ActivityRules()
        val zone = SafeZone(0.3f, 0.3f, 0.7f, 0.7f, configured = true)
        rules.settle(observe(reading(Posture.ON_BACK), zone = zone),
                     config.calibrationFrames + 2)
        val assessment = rules.settle(
            observe(reading(Posture.ON_BACK, cx = 0.95f, cy = 0.5f), zone = zone),
            frames = config.zoneFramesToAlert + 4,
        )
        assertTrue(ActivityRules.Event.LEFT_SAFE_ZONE in assessment.events)
        assertEquals(Severity.URGENT, assessment.severity)
        assertTrue("expected an overshoot", assessment.zoneOvershoot > 0f)
    }

    @Test
    fun `inside the zone reports no overshoot`() {
        val rules = ActivityRules()
        val zone = SafeZone(0.2f, 0.2f, 0.8f, 0.8f, configured = true)
        val assessment = rules.settle(
            observe(reading(Posture.ON_BACK, cx = 0.5f, cy = 0.5f), zone = zone), 30
        )
        assertEquals(0f, assessment.zoneOvershoot, 1e-5f)
        assertFalse(ActivityRules.Event.LEFT_SAFE_ZONE in assessment.events)
    }

    /**
     * Distance is a comparison against where the baby was when the watch
     * started, and the direction has to be right: a smaller torso is further
     * away, so the ratio goes up.
     */
    @Test
    fun `a shrinking torso reads as moving away`() {
        val rules = ActivityRules()
        rules.settle(observe(reading(Posture.ON_BACK, torso = 0.30f)),
                     config.calibrationFrames + 2)
        val away = rules.settle(
            observe(reading(Posture.ON_BACK, torso = 0.20f)),
            frames = config.distanceFramesToBelieve + 4,
        )
        assertTrue("ratio should exceed 1: ${away.distanceRatio}", away.distanceRatio > 1.4f)
        assertTrue(ActivityRules.Event.MOVED_AWAY in away.events)
    }

    @Test
    fun `far away is urgent and replaces moved away`() {
        val rules = ActivityRules()
        rules.settle(observe(reading(Posture.ON_BACK, torso = 0.30f)),
                     config.calibrationFrames + 2)
        val far = rules.settle(
            observe(reading(Posture.ON_BACK, torso = 0.12f)),
            frames = config.distanceFramesToBelieve + 4,
        )
        assertTrue(ActivityRules.Event.FAR_FROM_START in far.events)
        assertFalse(
            "the two distance events must not both fire",
            ActivityRules.Event.MOVED_AWAY in far.events,
        )
    }

    @Test
    fun `no body and no face becomes out of view`() {
        val rules = ActivityRules()
        val assessment = rules.settle(
            observe(reading(Posture.UNKNOWN, present = false), faceFound = false),
            frames = config.absentFramesToAlert + 4,
        )
        assertEquals(ActivityRules.Event.OUT_OF_VIEW, assessment.primary)
        assertEquals(Severity.URGENT, assessment.severity)
    }

    /**
     * A face with no body is the common case for a swaddled baby, or one whose
     * legs are under a blanket. It is not an alarm.
     */
    @Test
    fun `no body but a visible face is not an alarm`() {
        val rules = ActivityRules()
        val assessment = rules.settle(
            observe(reading(Posture.UNKNOWN, present = false), faceFound = true),
            frames = config.absentFramesToAlert + 10,
        )
        assertEquals(ActivityRules.Event.FACE_ONLY, assessment.primary)
        assertEquals(Severity.NOTE, assessment.severity)
    }

    /**
     * Two facts at once. A baby who has rolled prone *and* crossed the cot line
     * is not one event with the other discarded, which is what the old
     * single-state watch had to do.
     */
    @Test
    fun `several events are reported together, worst first`() {
        val rules = ActivityRules()
        val zone = SafeZone(0.3f, 0.3f, 0.7f, 0.7f, configured = true)
        rules.settle(observe(reading(Posture.ON_BACK), zone = zone),
                     config.calibrationFrames + 2)
        val assessment = rules.settle(
            observe(reading(Posture.ON_FRONT, cx = 0.95f), zone = zone), 40
        )
        assertTrue(ActivityRules.Event.ROLLED_TO_FRONT in assessment.events)
        assertTrue(ActivityRules.Event.LEFT_SAFE_ZONE in assessment.events)
        assertEquals(Severity.URGENT, assessment.severity)
        val levels = assessment.events.map { it.severity.level }
        assertEquals("events must be sorted worst first",
                     levels.sortedDescending(), levels)
    }

    @Test
    fun `going still is attention`() {
        val rules = ActivityRules()
        val assessment = rules.settle(
            observe(reading(Posture.ON_BACK), relativeMotion = 0.05f),
            frames = config.stillnessFramesToAlert + 5,
        )
        assertTrue(ActivityRules.Event.UNUSUALLY_STILL in assessment.events)
    }

    @Test
    fun `reset forgets the baseline and the postures`() {
        val rules = ActivityRules()
        rules.settle(observe(reading(Posture.ON_FRONT)), 40)
        assertTrue(rules.calibrated)
        rules.reset()
        assertFalse(rules.calibrated)
        assertEquals(
            ActivityRules.Event.CALIBRATING,
            rules.update(observe(reading(Posture.ON_FRONT))).primary,
        )
    }

    @Test
    fun `every event has a sentence and the risky ones have a caveat`() {
        ActivityRules.Event.entries.forEach { event ->
            assertTrue("$event has no description",
                       ActivityRules.describe(event).isNotBlank())
        }
        assertNotNull(ActivityRules.caveat(ActivityRules.Event.OUT_OF_VIEW))
        assertNotNull(ActivityRules.caveat(ActivityRules.Event.FAR_FROM_START))
        assertNotNull(ActivityRules.caveat(ActivityRules.Event.ROLLED_TO_FRONT))
    }
}
