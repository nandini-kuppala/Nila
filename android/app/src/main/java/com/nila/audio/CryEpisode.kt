package com.nila.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/** Which way the cry is going. This is measured, unlike the reason for it. */
enum class Trend { RISING, STEADY, SETTLING }

/**
 * A cry episode in progress or just finished.
 *
 * The envelope is the honest, useful signal. A parent in the next room wants to
 * know how long it has been going and whether it is building or dying down --
 * both of which we can measure directly. That is a different kind of claim from
 * naming a cause, and it is one the acoustics actually support.
 */
data class CryEpisode(
    val startedAtMs: Long,
    var endedAtMs: Long? = null,
    val envelope: MutableList<Float> = mutableListOf(),   // dBFS, ~1 Hz
    var peakDbfs: Float = -120f,
    var meanConfidence: Float = 0f,
    private var confidenceSum: Float = 0f,
    private var confidenceCount: Int = 0,
) {
    /**
     * Timestamp of the most recent window folded into this episode.
     *
     * Duration is measured against the audio clock rather than the wall clock.
     * Those normally agree, and when they do not it is because analysis fell
     * behind -- a GC pause, a busy phone -- and the wall clock would then report
     * a duration for audio nobody has looked at yet. It also lets a recorded
     * clip be replayed through the pipeline faster than real time without the
     * escalation ladder losing track of how long the cry has been going.
     */
    var lastSeenAtMs: Long = startedAtMs
        private set

    val durationMs: Long
        get() = (endedAtMs ?: lastSeenAtMs) - startedAtMs

    val durationSeconds: Int get() = (durationMs / 1000L).toInt()

    fun record(dbfs: Float, confidence: Float, atMs: Long = lastSeenAtMs) {
        if (atMs > lastSeenAtMs) lastSeenAtMs = atMs
        envelope += dbfs
        if (dbfs > peakDbfs) peakDbfs = dbfs
        confidenceSum += confidence
        confidenceCount++
        meanConfidence = confidenceSum / confidenceCount
    }

    /**
     * Least-squares slope over the last [windowSamples] envelope points.
     *
     * A flat threshold on "is it louder than it was" would flip on every breath.
     * Fitting a line over ~20 s and requiring a minimum slope makes the answer
     * stable enough to show a parent without it flickering.
     */
    fun trend(windowSamples: Int = 20, minSlopeDbPerSample: Float = 0.12f): Trend {
        val tail = envelope.takeLast(windowSamples)
        if (tail.size < 5) return Trend.STEADY

        val n = tail.size
        val meanX = (n - 1) / 2.0f
        val meanY = tail.average().toFloat()
        var num = 0f
        var den = 0f
        tail.forEachIndexed { i, y ->
            val dx = i - meanX
            num += dx * (y - meanY)
            den += dx * dx
        }
        if (den == 0f) return Trend.STEADY

        val slope = num / den
        return when {
            abs(slope) < minSlopeDbPerSample -> Trend.STEADY
            slope > 0 -> Trend.RISING
            else -> Trend.SETTLING
        }
    }

    /** 0..100, for a sparkline. Maps the useful part of the dBFS range. */
    fun normalisedEnvelope(): List<Int> = envelope.map { db ->
        (((db + 60f) / 60f).coerceIn(0f, 1f) * 100f).roundToInt()
    }
}

/**
 * Hysteresis around the detector.
 *
 * The two constants below are the difference between a monitor and a fire
 * alarm. Requiring several positive windows before declaring a cry stops a door
 * slam from firing it, and requiring more negative windows before clearing
 * stops a baby drawing breath from ending the episode. They are deliberately
 * asymmetric: entering is harder than staying.
 */
class CryStateMachine(
    private val enterThreshold: Float = 0.62f,
    private val exitThreshold: Float = 0.42f,
    private val enterVotes: Int = 3,
    private val enterWindow: Int = 5,
    private val exitVotes: Int = 6,
) {
    private val recent = ArrayDeque<Boolean>()
    private var belowRun = 0

    var episode: CryEpisode? = null
        private set

    sealed interface Transition {
        data class Started(val episode: CryEpisode) : Transition
        data class Continued(val episode: CryEpisode) : Transition
        data class Ended(val episode: CryEpisode) : Transition
        data object Quiet : Transition
    }

    fun update(cryProbability: Float, dbfs: Float, nowMs: Long): Transition {
        val active = episode
        val above = cryProbability >= if (active == null) enterThreshold else exitThreshold

        recent.addLast(above)
        while (recent.size > enterWindow) recent.removeFirst()

        if (active == null) {
            val votes = recent.count { it }
            if (recent.size >= enterVotes && votes >= enterVotes) {
                val fresh = CryEpisode(startedAtMs = nowMs)
                fresh.record(dbfs, cryProbability, nowMs)
                episode = fresh
                belowRun = 0
                return Transition.Started(fresh)
            }
            return Transition.Quiet
        }

        active.record(dbfs, cryProbability, nowMs)
        if (above) {
            belowRun = 0
            return Transition.Continued(active)
        }

        belowRun++
        if (belowRun >= exitVotes) {
            active.endedAtMs = nowMs
            episode = null
            belowRun = 0
            recent.clear()
            return Transition.Ended(active)
        }
        return Transition.Continued(active)
    }

    fun reset() {
        recent.clear()
        belowRun = 0
        episode = null
    }
}
