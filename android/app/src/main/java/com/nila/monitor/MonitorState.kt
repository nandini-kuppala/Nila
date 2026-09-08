package com.nila.monitor

import com.nila.audio.CryEvidence
import com.nila.data.Severity

/** What the guardian is doing right now, as the UI needs to see it. */
data class MonitorState(
    val running: Boolean = false,
    val armedSinceMs: Long = 0L,
    val phase: Phase = Phase.Idle,
    val currentEvidence: CryEvidence? = null,
    val lastSoother: String? = null,
    val eventsTonight: Int = 0,
    val cameraActive: Boolean = false,
    val faceVisible: Boolean = true,
    val fault: String? = null,
    val detectorLatencyMs: Double = 0.0,
    val accelerator: String = "-",
    val lastCryProbability: Float = 0f,
    /**
     * Loudness of the last analysed window, in dBFS.
     *
     * On screen because "Listening" on its own is indistinguishable from a dead
     * microphone -- which is exactly what an emulator with no audio input, or a
     * phone whose mic another app has grabbed, looks like. A number that moves
     * when you clap is the difference between a monitor you trust and one you
     * hope is working.
     */
    val inputDbfs: Float = SILENCE_DBFS,
    /** Consecutive analysed windows with no sound in them at all. */
    val silentWindows: Int = 0,
    /**
     * True while a recorded clip is being fed through the pipeline instead of
     * the microphone. Surfaced prominently: a demo that cannot be told apart
     * from the real thing is a dishonest demo.
     */
    val simulated: Boolean = false,
    /**
     * What Nila has done about this cry, newest last.
     *
     * The whole product argument is that the app acts before it wakes anyone,
     * and until this existed the only evidence of that on screen was the status
     * line changing to "Playing White noise" and then changing back.
     */
    val actions: List<String> = emptyList(),
    /**
     * True once a demo cry has run to the end and its summary is being held on
     * screen.
     *
     * The demo used to tear itself down the moment the episode closed, which
     * meant the account of what Nila did -- the whole point of running it --
     * vanished at the exact moment somebody wanted to read it. Now it stays up
     * until the reader dismisses it.
     */
    val demoComplete: Boolean = false,
    /**
     * The sound coming out of the speaker right now, if any.
     *
     * Separate from [lastSoother], which records what was tried. This one is
     * what the UI animates -- a sound that is playing should look like it is
     * playing, or a demo of it is indistinguishable from a caption.
     */
    val nowPlaying: String? = null,
    /** True when [nowPlaying] is a recording somebody made, not a built-in. */
    val nowPlayingIsVoice: Boolean = false,
) {

    /**
     * Long enough that a genuinely quiet nursery does not trigger it, short
     * enough to catch a broken microphone before a whole night is lost.
     * 125 windows at 480 ms is a minute.
     */
    val microphoneLooksDead: Boolean
        get() = running && !simulated && silentWindows >= 125

    /**
     * The graduated response, as a state rather than a pile of booleans.
     *
     * Written this way because the escalation ladder is the product: most
     * wakings should never get past Settling, and being able to see at a glance
     * which rung the system is on is what makes the behaviour explainable to
     * the person relying on it.
     */
    sealed interface Phase {
        data object Idle : Phase
        data object Listening : Phase
        data class CryDetected(val seconds: Int) : Phase
        data class Settling(val soother: String, val seconds: Int) : Phase
        data class Verifying(val soother: String) : Phase
        data class Escalated(val reason: String, val severity: Severity) : Phase
        data class Safety(val reason: String) : Phase
        data object DemoFinished : Phase
    }

    /** Where the last window sat between silence and clipping, as 0..1. */
    val inputLevel: Float
        get() = ((inputDbfs - SILENCE_DBFS) / -SILENCE_DBFS).coerceIn(0f, 1f)

    val statusLine: String get() = when (val p = phase) {
        Phase.Idle -> "Not monitoring"
        Phase.Listening -> "Listening"
        is Phase.CryDetected -> "Crying for ${p.seconds}s"
        is Phase.Settling -> "Playing ${p.soother}"
        is Phase.Verifying -> "Checking whether ${p.soother} helped"
        is Phase.Escalated -> p.reason
        is Phase.Safety -> p.reason
        Phase.DemoFinished -> "Demo finished"
    }

    companion object {
        /** Quieter than this and the frontend is looking at the noise floor. */
        const val SILENCE_DBFS = -80f

        /**
         * Below this, nothing is reaching the microphone.
         *
         * Not -80: a muted input is rarely digital silence. The Android
         * emulator with no host audio routed sits at about -77 dBFS of dither,
         * which a strict silence test reads as a healthy signal and never
         * warns about -- the exact case this check exists for. A real nursery
         * at night measures around -50, and the pipeline's own silence gate is
         * -55, so -70 is far below any room and still above a dead line.
         */
        const val DEAD_INPUT_DBFS = -70f
    }
}
