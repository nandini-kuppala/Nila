package com.nila.monitor

import com.nila.data.Severity

/**
 * The escalation ladder as a structure the UI can draw, rather than a list of
 * sentences it can only print.
 *
 * The ladder was always the product argument, and until this existed the only
 * on-screen evidence of it was a growing list of strings. A parent could read
 * that Nila played white noise; they could not see that the rung existed before
 * it fired, that a verification step was coming, or how far through the episode
 * they were. All three are the difference between "the app said something" and
 * "the app is working through a plan".
 *
 * Kept free of Android and Compose so the whole progression can be tested on
 * the JVM at whatever second of an episode we like, which is where the timing
 * bugs live.
 */
object EpisodePipeline {

    /**
     * One rung. [atSeconds] is when the rung is due, which is what lets the UI
     * draw the rest of the ladder greyed out ahead of time.
     */
    enum class Stage(
        val atSeconds: Int,
        val title: String,
        /** What the rung will do, shown while it is still pending. */
        val pending: String,
    ) {
        HEARD(0, "Heard crying", "Detector crossed its threshold"),
        LOGGED(Escalation.Config().noteSeconds, "Logged the episode",
               "Recorded, if it stops here"),
        CLASSIFIED(Escalation.Config().reasonSeconds, "Read the reason",
                   "Cause classifier runs on 20 s of cry"),
        SOOTHED(Escalation.Config().sootheAfterSeconds, "Played a sound",
                "Tries to settle before waking anyone"),
        VERIFIED(Escalation.Config().sootheAfterSeconds +
                     Escalation.Config().verifyAfterSeconds,
                 "Checked whether it helped",
                 "Re-reads the loudness envelope"),
        VERDICT(Escalation.Config().verdictSeconds, "Woke you, with a reason",
                "If nothing worked by then"),
        CLOSED(Escalation.Config().closeSeconds, "Closed the episode",
               "Clip, steps and reason kept"),
    }

    enum class Status { PENDING, ACTIVE, DONE, SKIPPED }

    /**
     * A rung as drawn. [detail] is what actually happened, and is null until it
     * does -- the UI shows [Stage.pending] in its place, in a muted style.
     */
    data class Step(
        val stage: Stage,
        val status: Status,
        val detail: String? = null,
        /** Episode second the rung actually fired, for the timeline axis. */
        val firedAtSeconds: Int? = null,
        val severity: Severity = Severity.NOTE,
    ) {
        val label: String get() = stage.title
        val body: String get() = detail ?: stage.pending
    }

    /**
     * Build the ladder for an episode [seconds] old, given what has fired.
     *
     * The whole progression is derived rather than accumulated, so a rung
     * cannot be shown out of order, shown twice, or left ACTIVE forever because
     * the transition that was supposed to close it never arrived.
     */
    fun steps(
        seconds: Int,
        fired: Map<Stage, Fired>,
        closed: Boolean = false,
    ): List<Step> = Stage.entries.map { stage ->
        val hit = fired[stage]
        val status = when {
            hit != null && stage == latest(fired) && !closed -> Status.ACTIVE
            hit != null -> Status.DONE
            // Past due and never fired: the episode went another way. A rung
            // that was overtaken has to look different from one still coming,
            // or the ladder reads as broken rather than as not needed.
            closed || seconds > stage.atSeconds + OVERDUE_GRACE_SECONDS ->
                Status.SKIPPED
            else -> Status.PENDING
        }
        Step(
            stage = stage,
            status = status,
            detail = hit?.detail,
            firedAtSeconds = hit?.atSeconds,
            severity = hit?.severity ?: Severity.NOTE,
        )
    }

    /** What a rung recorded when it fired. */
    data class Fired(
        val detail: String,
        val atSeconds: Int,
        val severity: Severity = Severity.NOTE,
    )

    /**
     * How long a rung may be late before it counts as overtaken.
     *
     * Generous, because the rungs are driven by 480 ms analysis windows and by
     * a soother whose verification slides with when it started. A rung marked
     * SKIPPED while it is about to fire is worse than one that looks pending a
     * few seconds too long.
     */
    private const val OVERDUE_GRACE_SECONDS = 8

    private fun latest(fired: Map<Stage, Fired>): Stage? =
        fired.keys.maxByOrNull { it.ordinal }

    /** 0..1 through the ladder, for the progress rail above the steps. */
    fun progress(seconds: Int): Float =
        (seconds.toFloat() / Stage.CLOSED.atSeconds).coerceIn(0f, 1f)
}
