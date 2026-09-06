package com.nila.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The 456 numbers the cause classifier is trained on.
 *
 * This is the Kotlin half of `ml/src/reason_features.py`, and the two are
 * pinned together by a golden fixture the way [LogMelFrontend] already is --
 * everything here is derived from that frontend, so the whole vector inherits
 * a transform that is already known to agree across the two languages rather
 * than introducing a second chance to disagree about a windowing convention.
 *
 *     mel      64 bands  x {mean, std}                       128
 *     mfcc     40 coeffs x {mean, std, skew, kurtosis}        160
 *     mfcc'    40 x {mean, std}                                80
 *     mfcc''   40 x {mean, std}                                80
 *     zcr      {mean, std, max, min}                            4
 *     rms      {mean, std, max, min}                            4
 */
object ReasonFeatures {

    const val N_MFCC = 40
    const val SIZE = 2 * LogMelFrontend.N_MELS + 4 * N_MFCC + 2 * N_MFCC +
        2 * N_MFCC + 4 + 4

    /**
     * Orthonormal DCT-II basis, built once.
     *
     * 40 x 64 doubles. Rebuilding it per call would dominate the cost of the
     * whole extraction, which runs on every classified window.
     */
    private val dctBasis: Array<DoubleArray> by lazy {
        val n = LogMelFrontend.N_MELS
        Array(N_MFCC) { k ->
            val scale = if (k == 0) sqrt(1.0 / n) else sqrt(2.0 / n)
            DoubleArray(n) { i -> cos(PI * (2 * i + 1) * k / (2.0 * n)) * scale }
        }
    }

    /**
     * @param wave  mono float PCM at [LogMelFrontend.SAMPLE_RATE]
     * @param out   [SIZE] floats, overwritten
     */
    fun extract(wave: FloatArray, offset: Int, length: Int, out: FloatArray) {
        require(out.size >= SIZE) { "need $SIZE floats, got ${out.size}" }

        val frames = LogMelFrontend.frameCount(length)
        if (frames < 1) { out.fill(0f); return }

        val mel = FloatArray(frames * LogMelFrontend.N_MELS)
        LogMelFrontend.logMel(wave, offset, length, mel)

        val mfcc = Array(frames) { t ->
            DoubleArray(N_MFCC) { k ->
                var sum = 0.0
                val base = t * LogMelFrontend.N_MELS
                val row = dctBasis[k]
                for (i in 0 until LogMelFrontend.N_MELS) sum += mel[base + i] * row[i]
                sum
            }
        }
        val d1 = delta(mfcc)
        val d2 = delta(d1)

        var w = 0
        // --- mel bands
        val column = DoubleArray(frames)
        for (band in 0 until LogMelFrontend.N_MELS) {
            for (t in 0 until frames) column[t] = mel[t * LogMelFrontend.N_MELS + band].toDouble()
            out[w++] = mean(column).toFloat()
            out[w++] = std(column).toFloat()
        }
        // --- mfcc and its two derivatives
        for (k in 0 until N_MFCC) {
            for (t in 0 until frames) column[t] = mfcc[t][k]
            val m = mean(column)
            val s = std(column)
            out[w++] = m.toFloat()
            out[w++] = s.toFloat()
            out[w++] = skew(column, m, s).toFloat()
            out[w++] = kurtosis(column, m, s).toFloat()
        }
        for (source in listOf(d1, d2)) {
            for (k in 0 until N_MFCC) {
                for (t in 0 until frames) column[t] = source[t][k]
                out[w++] = mean(column).toFloat()
                out[w++] = std(column).toFloat()
            }
        }

        // --- time-domain, framed exactly like the spectrogram
        val zcr = DoubleArray(frames)
        val rms = DoubleArray(frames)
        for (t in 0 until frames) {
            val start = offset + t * LogMelFrontend.HOP_LENGTH
            var crossings = 0
            var energy = 0.0
            var previous = wave[start] < 0f
            for (i in 0 until LogMelFrontend.WIN_LENGTH) {
                val v = wave[start + i]
                energy += v.toDouble() * v
                val negative = v < 0f
                if (i > 0 && negative != previous) crossings++
                previous = negative
            }
            zcr[t] = crossings.toDouble() / (LogMelFrontend.WIN_LENGTH - 1)
            rms[t] = sqrt(energy / LogMelFrontend.WIN_LENGTH)
        }
        for (series in listOf(zcr, rms)) {
            out[w++] = mean(series).toFloat()
            out[w++] = std(series).toFloat()
            out[w++] = series.max().toFloat()
            out[w++] = series.min().toFloat()
        }
    }

    /** Centred first difference along time, edges replicated. */
    private fun delta(x: Array<DoubleArray>): Array<DoubleArray> {
        val frames = x.size
        val width = if (frames > 0) x[0].size else 0
        val out = Array(frames) { DoubleArray(width) }
        if (frames < 3) return out
        for (t in 1 until frames - 1) {
            for (k in 0 until width) out[t][k] = (x[t + 1][k] - x[t - 1][k]) * 0.5
        }
        for (k in 0 until width) {
            out[0][k] = out[1][k]
            out[frames - 1][k] = out[frames - 2][k]
        }
        return out
    }

    private fun mean(x: DoubleArray): Double {
        var sum = 0.0
        for (v in x) sum += v
        return sum / x.size
    }

    /** Population standard deviation, matching numpy's default. */
    private fun std(x: DoubleArray): Double {
        val m = mean(x)
        var sum = 0.0
        for (v in x) { val d = v - m; sum += d * d }
        return sqrt(sum / x.size)
    }

    /**
     * Fisher-Pearson skewness and excess kurtosis, as scipy computes them.
     *
     * The degenerate cases are pinned to what scipy returns for a constant
     * series -- 0 and -3 -- rather than to NaN, because a NaN reaching the
     * forest turns every comparison against it into a silent left-branch.
     */
    private fun skew(x: DoubleArray, m: Double, s: Double): Double {
        if (s < 1e-12) return 0.0
        var sum = 0.0
        for (v in x) { val d = (v - m) / s; sum += d * d * d }
        return sum / x.size
    }

    private fun kurtosis(x: DoubleArray, m: Double, s: Double): Double {
        if (s < 1e-12) return -3.0
        var sum = 0.0
        for (v in x) { val d = (v - m) / s; sum += d * d * d * d }
        return sum / x.size - 3.0
    }
}
