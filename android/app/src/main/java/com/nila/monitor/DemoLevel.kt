package com.nila.monitor

import kotlin.math.PI
import kotlin.math.sin

/**
 * The loudness the simulated cry is held at, window by window.
 *
 * The demo replays a short recording on a loop. Looping is what makes the level
 * meaningless: the clip's own envelope repeats every few seconds, and the trend
 * estimator -- a least-squares slope over the last twenty windows -- reads that
 * repetition as rising or settling depending on nothing more than where the loop
 * happened to wrap. The ladder then escalated at an arbitrary point, so the demo
 * told a different story on every run, and usually stopped after one sound.
 *
 * So the demo supplies the level itself. Everything else is real: the audio, the
 * frontend, the detector, the classifier, the hysteresis and every rung of the
 * ladder. How loud a simulated baby is happens to be the one quantity a
 * recording of a *different* baby cannot supply.
 *
 * Lives outside [MonitorService] so the property it exists for -- that the
 * plateau reads as steady no matter where the estimator's window falls -- can be
 * checked by a test rather than by running the demo and hoping.
 */
object DemoLevel {

    /** Where the episode starts, and how long it takes to climb. */
    private const val OPENING_DBFS = -34f
    private const val PLATEAU_DBFS = -26f
    private const val RISE_SECONDS = 14

    /**
     * The breathing, as amplitude in dB against period in analysis windows.
     *
     * Chosen against the trend estimator, not by ear. Fitting a line to twenty
     * windows of a sinusoid leaves a residual slope that depends on phase, and
     * at most periods that residual alone is enough to flip the reported trend
     * -- which is what a single period-ten ripple did, announcing that white
     * noise was working and, two windows later, that the cry was getting louder.
     * Measured across every phase and offset this pair peaks at 0.051 dB of
     * slope, against the estimator's 0.12 dB threshold.
     *
     * Two components rather than one because a single sinusoid draws a visibly
     * synthetic sparkline. Six against eight beat over twenty-four windows,
     * which is long enough to read as a baby rather than a signal generator.
     */
    private val RIPPLE = listOf(4f to 8.0, 2.5f to 6.0)

    /**
     * @param second how far into the episode this window is
     * @param window the window's index in the replay, for the ripple's phase
     */
    fun dbfsAt(second: Int, window: Int): Float {
        val arc = if (second < RISE_SECONDS) {
            OPENING_DBFS + (second.toFloat() / RISE_SECONDS) * (PLATEAU_DBFS - OPENING_DBFS)
        } else {
            PLATEAU_DBFS
        }
        val ripple = RIPPLE.sumOf { (amplitude, period) ->
            amplitude * sin(2.0 * PI * window / period)
        }
        return arc + ripple.toFloat()
    }
}
