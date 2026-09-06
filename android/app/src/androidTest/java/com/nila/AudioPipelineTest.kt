package com.nila

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.audio.CryEngine
import com.nila.audio.LogMelFrontend
import com.nila.audio.SelfTest
import com.nila.audio.WavReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The audio lane against the real TFLite graph, on the real device.
 *
 * Everything above this is covered on the JVM. What only a device can answer is
 * whether the shipped .tflite files load, whether the delegate chain resolves,
 * and whether the numbers on the phone match the numbers from evaluation.
 */
@RunWith(AndroidJUnit4::class)
class AudioPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun selfTestDetectsTheBundledCry() {
        val result = SelfTest(context).run()
        assertTrue("self-test errored: ${result.error}", result.error == null)
        assertTrue(
            "expected detection, peak was ${result.peakProbability}",
            result.detected,
        )
        assertTrue(
            "confidence unexpectedly low: ${result.peakProbability}",
            result.peakProbability > 0.9f,
        )
        assertTrue("no accelerator reported", result.accelerator.isNotBlank())
    }

    @Test
    fun inferenceIsFastEnoughToRunAllNight() {
        // The budget that matters: one 0.96 s window must cost far less than
        // 0.96 s of compute, or continuous monitoring is not viable.
        val result = SelfTest(context).run()
        assertTrue("self-test errored: ${result.error}", result.error == null)
        assertTrue(
            "per-window latency ${result.perPatchMs}ms is too close to real time",
            result.perPatchMs < 200.0,
        )
    }

    @Test
    fun silenceDoesNotLookLikeACry() {
        // The single most important negative. A monitor that fires on an empty
        // room is worse than no monitor.
        val engine = CryEngine(context)
        try {
            val silence = FloatArray(LogMelFrontend.SAMPLE_RATE * 3)
            assertTrue(
                "silence scored ${engine.analyseClip(silence)}",
                engine.analyseClip(silence) < 0.5f,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun broadbandNoiseDoesNotLookLikeACry() {
        val engine = CryEngine(context)
        try {
            val rng = java.util.Random(7)
            val noise = FloatArray(LogMelFrontend.SAMPLE_RATE * 3) {
                (rng.nextGaussian() * 0.25).toFloat()
            }
            assertTrue(
                "white noise scored ${engine.analyseClip(noise)}",
                engine.analyseClip(noise) < 0.62f,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun theBundledSoothingSoundsDecode() {
        listOf("soothe_white_noise.wav", "soothe_shush.wav",
               "soothe_heartbeat.wav", "demo_cry.wav").forEach { asset ->
            val wave = WavReader.fromAsset(context, asset)
            assertTrue("$asset decoded empty", wave.samples.isNotEmpty())
            assertTrue("$asset has an implausible sample rate: ${wave.sampleRate}",
                       wave.sampleRate in 8_000..48_000)
            val peak = wave.samples.maxOf { abs(it) }
            assertTrue("$asset is silent", peak > 0.01f)
            assertTrue("$asset clips at $peak", peak <= 1.0f)
        }
    }

    @Test
    fun resamplingPreservesDurationAndLevel() {
        val wave = WavReader.fromAsset(context, "demo_cry.wav")
        val resampled = WavReader.resample(
            wave.samples, wave.sampleRate, LogMelFrontend.SAMPLE_RATE
        )
        val expected = wave.samples.size.toDouble() *
            LogMelFrontend.SAMPLE_RATE / wave.sampleRate
        assertEquals(expected, resampled.size.toDouble(), expected * 0.01)
        assertTrue("resampling silenced the clip",
                   resampled.maxOf { abs(it) } > 0.01f)
    }
}
