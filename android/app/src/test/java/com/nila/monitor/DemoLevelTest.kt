package com.nila.monitor

import com.nila.audio.CryEpisode
import com.nila.audio.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo's level arc exists to keep the escalation ladder deterministic, and
 * the way it failed was subtle: a ripple whose least-squares slope over the
 * trend estimator's twenty-window fit depended on phase, so the same episode
 * reported "settling" and then "rising" two windows apart, judged white noise a
 * success, and woke the parent before the second sound had been tried.
 *
 * These pin the two properties that has to hold, against the real estimator.
 */
class DemoLevelTest {

    private fun episodeOver(windows: IntRange): CryEpisode {
        val episode = CryEpisode(startedAtMs = 0L)
        for (w in windows) {
            episode.record(DemoLevel.dbfsAt((w * 480L / 1000L).toInt(), w), 0.9f, w * 480L)
        }
        return episode
    }

    @Test
    fun `the plateau reads as steady wherever the estimator's window falls`() {
        // From the end of the rise to past the hard escalation ceiling.
        for (last in 42..240) {
            val trend = episodeOver(0..last).trend()
            assertEquals("window ending at $last read as $trend", Trend.STEADY, trend)
        }
    }

    @Test
    fun `the opening reads as rising, so the demo starts by building`() {
        // Ten windows in: still inside the 14-second climb.
        assertEquals(Trend.RISING, episodeOver(0..20).trend())
    }

    @Test
    fun `the plateau still moves enough to draw a sparkline`() {
        val levels = (40..120).map { DemoLevel.dbfsAt((it * 480L / 1000L).toInt(), it) }
        val swing = levels.max() - levels.min()
        assertTrue("a flat envelope reads as a broken one; swing was $swing dB", swing > 8f)
    }

    @Test
    fun `a plateau this shape is never mistaken for a settled baby`() {
        // What Escalation.judgeSettled asks of the envelope, on the 0-100 scale
        // the UI and the ladder both use.
        val episode = episodeOver(0..200)
        assertTrue(
            "the ripple must not look like an 18-point drop from peak",
            !Escalation().judgeSettled(
                com.nila.audio.CryEvidence(
                    durationSeconds = 96,
                    peakDbfs = episode.peakDbfs,
                    trend = episode.trend(),
                    detectorConfidence = 0.9f,
                    envelope = episode.normalisedEnvelope(),
                    hypothesis = null,
                )
            )
        )
    }
}
