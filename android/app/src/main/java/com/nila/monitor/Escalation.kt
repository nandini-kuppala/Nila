package com.nila.monitor

import com.nila.audio.CryEvidence
import com.nila.audio.Trend
import com.nila.data.Severity

/**
 * The graduated response ladder.
 *
 * Every other product in this category ends at "notify". That treats a baby who
 * would have self-settled in forty seconds exactly the same as one who needs a
 * parent, and it is why monitors wake households that did not need waking.
 *
 * So: try a sound, check whether it worked, and only then wake someone. The
 * check is what makes this defensible rather than a gimmick -- the system does
 * not claim the sound helped, it re-reads the envelope and finds out.
 */
class Escalation(private val config: Config = Config()) {

    data class Config(
        /** Below this, a fuss is logged and nothing else happens. */
        val noteSeconds: Int = 12,
        /** Long enough to be a real cry, short enough to act before it escalates. */
        val sootheAfterSeconds: Int = 20,
        /** How long to give a soother before judging it. */
        val verifyAfterSeconds: Int = 35,
        /** Hard ceiling. Past here a parent is woken regardless of trend. */
        val escalateAfterSeconds: Int = 100,
        /** A cry that is still building this long after a soother is not settling. */
        val risingEscalateSeconds: Int = 55,
        val maxSootheAttempts: Int = 2,
    )

    sealed interface Decision {
        data object Wait : Decision
        data object LogNote : Decision
        data class PlaySoother(val attempt: Int) : Decision
        data class VerifySoother(val attempt: Int) : Decision
        data class Escalate(val reason: String, val severity: Severity) : Decision
    }

    private var attempts = 0
    private var sootheStartedAtSeconds = -1
    private var verified = false
    private var escalated = false
    private var notedFuss = false

    fun reset() {
        attempts = 0
        sootheStartedAtSeconds = -1
        verified = false
        escalated = false
        notedFuss = false
    }

    /** Called once per analysis window while an episode is running. */
    fun next(evidence: CryEvidence, sootherAvailable: Boolean): Decision {
        val seconds = evidence.durationSeconds

        if (escalated) return Decision.Wait

        if (seconds < config.noteSeconds) {
            if (!notedFuss) {
                notedFuss = true
                return Decision.LogNote
            }
            return Decision.Wait
        }

        // A soother is running and it is time to judge it.
        if (sootheStartedAtSeconds >= 0 && !verified &&
            seconds - sootheStartedAtSeconds >= config.verifyAfterSeconds
        ) {
            verified = true
            return Decision.VerifySoother(attempts)
        }

        // Hard ceiling, regardless of what the trend says.
        if (seconds >= config.escalateAfterSeconds) {
            escalated = true
            return Decision.Escalate(
                "Crying for ${seconds}s and still going",
                Severity.URGENT,
            )
        }

        // Still building well after we tried something: stop trying.
        if (evidence.trend == Trend.RISING &&
            seconds >= config.risingEscalateSeconds &&
            attempts > 0
        ) {
            escalated = true
            return Decision.Escalate(
                "Crying for ${seconds}s and getting louder",
                Severity.URGENT,
            )
        }

        val readyToSoothe = seconds >= config.sootheAfterSeconds &&
            (sootheStartedAtSeconds < 0 || verified)

        if (readyToSoothe && sootherAvailable && attempts < config.maxSootheAttempts) {
            attempts++
            sootheStartedAtSeconds = seconds
            verified = false
            return Decision.PlaySoother(attempts)
        }

        // Out of things to try.
        if (attempts >= config.maxSootheAttempts && verified) {
            escalated = true
            return Decision.Escalate(
                "Tried $attempts sounds, still crying",
                Severity.URGENT,
            )
        }

        return Decision.Wait
    }

    /**
     * Did the episode settle after the sound?
     *
     * Settling trend, or a meaningful drop from the peak. Both are read off the
     * envelope we were already computing, so verification costs nothing.
     */
    fun judgeSettled(evidence: CryEvidence): Boolean {
        if (evidence.trend == Trend.SETTLING) return true
        val recent = evidence.envelope.takeLast(6)
        if (recent.isEmpty()) return false
        val peak = evidence.envelope.maxOrNull() ?: return false
        return recent.average() < peak - 18
    }

    val attemptCount: Int get() = attempts
    val hasEscalated: Boolean get() = escalated
}
