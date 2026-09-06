package com.nila

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.audio.CryEngine
import com.nila.audio.LogMelFrontend
import com.nila.audio.WavReader
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the detector fire on the sounds the app itself plays?
 *
 * It has to be asked, because the soother comes out of the same phone's speaker
 * a metre from the same phone's microphone. If white noise scores as a cry, the
 * ladder feeds itself: cry, play a sound, hear the sound, call it a continuing
 * cry, escalate. The parent gets woken by the app's own attempt to avoid waking
 * them.
 */
@RunWith(AndroidJUnit4::class)
class SootherFeedbackTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun peakScore(asset: String): Float {
        val engine = CryEngine(context)
        return try {
            val wav = WavReader.fromAsset(context, asset)
            val samples = WavReader.resample(
                wav.samples, wav.sampleRate, LogMelFrontend.SAMPLE_RATE
            )
            engine.analyseClip(samples)
        } finally {
            engine.close()
        }
    }

    /**
     * The exit threshold, not the entry one.
     *
     * A soother that scores below 0.62 cannot start an episode, but the state
     * machine only *leaves* an episode below 0.42 -- so a sound landing between
     * the two would keep an already-running episode alive indefinitely while it
     * played, and the ladder would escalate on its own noise.
     */
    @Test
    fun theSoothersScoreBelowTheEpisodeExitThreshold() {
        listOf(
            "soothe_white_noise.wav",
            "soothe_shush.wav",
            "soothe_heartbeat.wav",
        ).forEach { asset ->
            val peak = peakScore(asset)
            android.util.Log.i("SootherFeedback", "$asset peak=$peak")
            assertTrue(
                "$asset scored ${"%.3f".format(peak)}, above the 0.42 exit " +
                    "threshold -- an episode could never end while it played",
                peak < 0.42f,
            )
        }
    }

    @Test
    fun theSoothersDoNotScoreAsACry() {
        listOf(
            "soothe_white_noise.wav",
            "soothe_shush.wav",
            "soothe_heartbeat.wav",
        ).forEach { asset ->
            val peak = peakScore(asset)
            assertTrue(
                "$asset scored ${"%.3f".format(peak)} -- the detector would hear " +
                    "the app's own soother as a cry and never let the episode end",
                peak < 0.62f,
            )
        }
    }

    /** The control: the bundled cry must still score high, or the test proves nothing. */
    @Test
    fun theTestCryStillScoresHigh() {
        assertTrue(peakScore("demo_cry.wav") >= 0.62f)
    }
}
