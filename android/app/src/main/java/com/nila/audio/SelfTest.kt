package com.nila.audio

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Proves the whole audio path works, in about a second.
 *
 * This exists for two reasons. It is onboarding -- a parent who has just
 * installed a baby monitor deserves to see it work before trusting it with a
 * night. And it is the opening move of a demo: run it, and the audience has
 * watched the system detect a cry and report its own latency before anyone has
 * said a word about how it works.
 *
 * It runs the bundled clip through the real detector on the real accelerator.
 * Nothing about the path is mocked.
 */
class SelfTest(private val context: Context) {

    data class Result(
        val detected: Boolean,
        val peakProbability: Float,
        val patchesAnalysed: Int,
        val totalMs: Long,
        val perPatchMs: Double,
        val accelerator: String,
        val error: String? = null,
    ) {
        /** One line, for a screen that has no room for a paragraph. */
        val shortLine: String get() = when {
            error != null -> "Test failed: $error"
            detected -> "Detected at ${(peakProbability * 100).toInt()}% in ${totalMs}ms " +
                "on $accelerator."
            else -> "Not detected. Peak ${(peakProbability * 100).toInt()}%."
        }

        val summary: String get() = when {
            error != null -> "Self-test failed: $error"
            detected -> "Detected the cry at ${(peakProbability * 100).toInt()}% " +
                "confidence in ${totalMs}ms, running on $accelerator."
            else -> "Did not detect the test cry. Peak confidence was " +
                "${(peakProbability * 100).toInt()}%."
        }
    }

    companion object {
        private const val TAG = "SelfTest"
        private const val ASSET = "demo_cry.wav"
        private const val THRESHOLD = 0.62f
    }

    fun run(engine: CryEngine? = null): Result {
        var owned: CryEngine? = null
        return try {
            val active = engine ?: CryEngine(context).also { owned = it }

            val wav = WavReader.fromAsset(context, ASSET)
            val samples = WavReader.resample(
                wav.samples, wav.sampleRate, LogMelFrontend.SAMPLE_RATE
            )

            val started = SystemClock.elapsedRealtime()
            val peak = active.analyseClip(samples)
            val elapsed = SystemClock.elapsedRealtime() - started

            val patches = maxOf(
                1,
                (samples.size - LogMelFrontend.PATCH_SAMPLES) /
                    (LogMelFrontend.PATCH_SAMPLES / 2) + 1
            )

            Result(
                detected = peak >= THRESHOLD,
                peakProbability = peak,
                patchesAnalysed = patches,
                totalMs = elapsed,
                perPatchMs = active.detectorLatency.meanMs,
                accelerator = active.accelerator.name,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "self-test failed", t)
            Result(false, 0f, 0, 0, 0.0, "-", t.message ?: t::class.java.simpleName)
        } finally {
            owned?.close()
        }
    }
}
