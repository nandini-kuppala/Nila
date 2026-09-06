package com.nila.vision

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How much the scene is moving, from frame differencing on a downscaled grey image.
 *
 * Deliberately not a model. A 32x24 luma grid differenced frame to frame answers
 * "is this baby moving" accurately enough for the two questions that matter --
 * has it gone unusually still, and is it thrashing -- at a cost that lets the
 * camera lane run all night. Anything heavier would be spending battery to
 * produce a number we would then threshold anyway.
 *
 * The grid is coarse on purpose: at 32x24 a rising chest is a few cells of
 * change, while sensor noise averages out.
 */
class MotionEnergy(
    private val gridW: Int = 32,
    private val gridH: Int = 24,
) {
    private var previous: FloatArray? = null
    private val scratch = IntArray(gridW * gridH)

    /** Recent energies, for the baseline that "unusually still" is measured against. */
    private val history = ArrayDeque<Float>()
    private val historyLimit = 240      // ~2 minutes at 2 fps

    data class Reading(
        /** Mean absolute luma change per cell, 0..1. */
        val energy: Float,
        /** Fraction of cells that changed beyond the noise floor. */
        val activeFraction: Float,
        /** Energy relative to this scene's own recent median. 1.0 == typical. */
        val relativeToBaseline: Float,
    )

    fun reset() {
        previous = null
        history.clear()
    }

    fun update(frame: Bitmap): Reading {
        val small = Bitmap.createScaledBitmap(frame, gridW, gridH, true)
        small.getPixels(scratch, 0, gridW, 0, 0, gridW, gridH)
        if (small !== frame) small.recycle()

        val luma = FloatArray(gridW * gridH) { i ->
            val p = scratch[i]
            // Rec. 601 luma. Cheaper than a colour-space conversion and the
            // absolute scale does not matter, only the frame-to-frame delta.
            (0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)) / 255f
        }

        val prev = previous
        previous = luma
        if (prev == null) return Reading(0f, 0f, 1f)

        var sum = 0f
        var active = 0
        val noiseFloor = 0.045f
        for (i in luma.indices) {
            val d = abs(luma[i] - prev[i])
            sum += d
            if (d > noiseFloor) active++
        }

        val energy = sum / luma.size
        val activeFraction = active.toFloat() / luma.size

        history.addLast(energy)
        while (history.size > historyLimit) history.removeFirst()

        val baseline = medianOf(history)
        val relative = if (baseline > 1e-5f) energy / baseline else 1f

        return Reading(energy, activeFraction, relative)
    }

    /** True once we have seen enough of this scene for the baseline to mean anything. */
    val baselineReady: Boolean get() = history.size >= 30

    private fun medianOf(values: Collection<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        /** RMS of a reading list, used by the endurance report. */
        fun rms(values: List<Float>): Float =
            if (values.isEmpty()) 0f
            else sqrt(values.sumOf { (it * it).toDouble() }.toFloat() / values.size)
    }
}
