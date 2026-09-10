package com.nila.vision

import com.nila.data.Severity

/**
 * When the camera lane is allowed to wake somebody, and what it says.
 *
 * Separated from the service so the decision can be tested on the JVM without a
 * camera, a notification manager or a clock. The thing being defended is a
 * balance: this is the lane most likely to cry wolf -- a baby turning over, a
 * blanket over the lens, a phone knocked by a cot rail all look alarming -- and
 * an alarm that fires wrongly twice gets the whole feature switched off.
 */
object WatchAlert {

    data class Decision(
        val title: String,
        val body: String,
        val severity: Severity,
        /** The event this was raised for, so repeats can be recognised. */
        val event: ActivityRules.Event,
    )

    /** Don't say the same thing twice inside this. */
    const val REPEAT_SUPPRESSION_MS = 60_000L

    /**
     * @param sinceSameEventMs how long since this exact event last alerted, or
     * [Long.MAX_VALUE] if it never has.
     * @return null when nothing should be sent.
     */
    fun decide(
        state: WatchState,
        sinceSameEventMs: Long = Long.MAX_VALUE,
        sinceCameraMovedMs: Long = Long.MAX_VALUE,
    ): Decision? {
        if (!state.running) return null
        val assessment = state.assessment ?: return null
        val event = assessment.primary

        // Still working out what normal looks like. Alerting during
        // calibration is how a monitor greets being switched on with an alarm.
        if (event == ActivityRules.Event.CALIBRATING) return null
        if (event == ActivityRules.Event.SETTLED ||
            event == ActivityRules.Event.ON_BACK ||
            event == ActivityRules.Event.FACE_ONLY
        ) return null

        // The camera's view is suspect, so nothing it thinks it saw is
        // evidence. The move itself is worth one quiet notification, because a
        // baby monitor pointed at a wall is worse than one switched off -- it
        // looks like it is working.
        if (state.cameraMoved) {
            if (sinceCameraMovedMs < REPEAT_SUPPRESSION_MS) return null
            return Decision(
                title = "Check the camera",
                body = "The phone was moved, so the view may no longer show the cot.",
                severity = Severity.ATTENTION,
                event = ActivityRules.Event.CAMERA_MOVED,
            )
        }

        val severity = state.severity

        // Below urgent, the screen and the log are enough. Waking a parent for
        // a baby who has rolled onto their side is the behaviour that trains
        // people to ignore the ones that matter -- unless it is happening
        // alongside a cry, which [WatchState.severity] has already promoted.
        if (severity.level < Severity.URGENT.level) return null
        if (sinceSameEventMs < REPEAT_SUPPRESSION_MS) return null

        return Decision(
            title = if (state.cryingNow) "Your baby needs you" else "Safety alert",
            body = buildString {
                append(ActivityRules.describe(event))
                if (state.cryingNow) append(", and crying")
                append(".")
                ActivityRules.caveat(event)?.let { append(" "); append(it) }
            },
            severity = severity,
            event = event,
        )
    }
}
