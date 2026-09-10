package com.nila.audio

import android.content.Context
import android.util.Log
import com.nila.ml.Accelerator
import com.nila.ml.LatencyStats
import com.nila.ml.TfliteRunner
import java.io.Closeable

/**
 * Acoustic description of a cry episode.
 *
 * Note what this carries and what it does not. Duration, loudness, trend and
 * detector confidence are measurements. [hypothesis] is explicitly a hypothesis
 * with a validation number attached, and the UI is required to present it as
 * one -- see [ReasonHypothesis.trustworthy].
 */
data class CryEvidence(
    val durationSeconds: Int,
    val peakDbfs: Float,
    val trend: Trend,
    val detectorConfidence: Float,
    val envelope: List<Int>,
    val hypothesis: ReasonHypothesis?,
)

/**
 * Output of the reason head, carrying its own validation result.
 *
 * [trustworthy] is false whenever the model's held-out, subject-wise macro AUC
 * failed to clear chance. It is wired into the type rather than left to the UI
 * to remember, because the whole failure mode in this product category is a
 * confident label with nothing behind it.
 */
data class ReasonHypothesis(
    val label: String,
    val confidence: Float,
    val runnerUp: String?,
    val runnerUpConfidence: Float,
    val subjectWiseAuc: Float,
    val trustworthy: Boolean,
    /**
     * How many analysis windows were averaged to get here.
     *
     * On screen, because one window is 0.96 s of audio and the forest returns a
     * different answer on many of them. A label backed by fifteen windows is a
     * different object from a label backed by one, and the number is the only
     * way a reader can tell them apart.
     */
    val windowsAveraged: Int = 1,
) {
    /** True when the top two classes are close enough that the ranking is noise. */
    val ambiguous: Boolean get() = confidence - runnerUpConfidence < 0.15f
}

/**
 * The audio pipeline: one frontend, two heads, hysteresis in between.
 *
 * Runs entirely on the calling thread so the caller controls scheduling; the
 * service drives it from a single dispatcher and nothing here is shared.
 */
class CryEngine(
    context: Context,
    private val config: Config = Config(),
) : Closeable {

    data class Config(
        val detectorAsset: String = "detect_mel.tflite",
        /**
         * The cause classifier: the RandomForest from Why-is-my-Baby-Crying,
         * retrained by that project's own pipeline and exported whole. It
         * replaced a small CNN trained here, because the forest is the model
         * that pipeline produces and reproducing its behaviour matters more
         * than the architecture.
         */
        val classifierAsset: String = "reason_forest.json",
        val silenceGateDbfs: Float = -55f,
        /**
         * Skip the reason head until the episode has enough audio to describe.
         *
         * Twenty seconds, matching the rung of the escalation ladder where the
         * app stops observing and starts acting. It used to be four, which
         * meant the cause was published from the first few windows of a cry --
         * the least representative audio in the episode, and early enough that
         * a fuss which settled on its own still got a label attached to it.
         */
        val classifyAfterSeconds: Int = 20,
        /**
         * How often to run the forest once past that point.
         *
         * Frequent, because the answers are averaged rather than replaced: a
         * classifier this uncertain window to window is better summarised by
         * fifteen votes than by whichever one happened to land last.
         */
        val classifyEverySeconds: Int = 3,
    )

    companion object {
        private const val TAG = "CryEngine"
        /**
         * Fallback order only. The real labels come from the exported model,
         * because a forest's class order is whatever sklearn sorted them into
         * and hard-coding a guess is how a "hungry" prediction gets displayed
         * as "tired".
         */
        private val REASON_LABELS =
            arrayOf("belly_pain", "burping", "discomfort", "hungry", "tired")
    }

    private val patch = FloatArray(LogMelFrontend.PATCH_FRAMES * LogMelFrontend.N_MELS)
    private val detectOut = FloatArray(2)
    private var reasonOut = FloatArray(REASON_LABELS.size)

    private val detector = TfliteRunner.fromAsset(context, config.detectorAsset,
                                                  preferred = Accelerator.NNAPI)
    private val classifier =
        com.nila.ml.RandomForestModel.fromAsset(context, config.classifierAsset)

    /** Reused across windows: 456 floats allocated once, not per classification. */
    private val reasonFeatures = FloatArray(ReasonFeatures.SIZE)

    private val state = CryStateMachine()
    private var lastClassifyMs = 0L
    private var lastHypothesis: ReasonHypothesis? = null

    /**
     * Running total of class probabilities across this episode's windows.
     *
     * Averaging is the whole point. A single window's argmax flips between
     * classes as the cry changes, which produced episodes that announced three
     * different causes on three different surfaces. Summing here and taking the
     * argmax of the mean gives one answer per episode that is also the answer
     * most of the audio supports -- and it is honest about how thin the
     * evidence is, because [ReasonHypothesis.windowsAveraged] rides along.
     */
    private var reasonSum = FloatArray(REASON_LABELS.size)
    private var reasonVotes = 0

    /** Validation metrics, read from ModelCard so the UI can show its own limits. */
    var reasonAuc: Float = ModelCard.REASON_SUBJECT_WISE_AUC
    var reasonTrustworthy: Boolean = ModelCard.REASON_TRUSTWORTHY

    val detectorLatency: LatencyStats get() = detector.latency()
    /** How many trees are walked per classification, for the settings screen. */
    val classifierTrees: Int get() = classifier?.treeCount ?: 0
    val accelerator: Accelerator get() = detector.accelerator

    /** Result of processing one analysis window. */
    sealed interface Result {
        data object Silent : Result
        data class Quiet(val cryProbability: Float) : Result
        data class Started(val evidence: CryEvidence) : Result
        data class Ongoing(val evidence: CryEvidence) : Result
        data class Ended(val evidence: CryEvidence) : Result
    }

    fun process(window: AudioCapture.Window): Result {
        // Below the gate the room is quiet. Skipping inference here is most of
        // the reason this can run all night on a battery.
        if (window.dbfs < config.silenceGateDbfs) {
            val ended = state.update(0f, window.dbfs, window.timestampMs)
            return if (ended is CryStateMachine.Transition.Ended) {
                Result.Ended(evidenceFor(ended.episode))
            } else {
                Result.Silent
            }
        }

        LogMelFrontend.logMel(window.samples, 0, window.samples.size, patch)
        detector.run(patch, detectOut)
        val cryProbability = detectOut[1]

        return when (val t = state.update(cryProbability, window.dbfs, window.timestampMs)) {
            is CryStateMachine.Transition.Quiet -> Result.Quiet(cryProbability)
            is CryStateMachine.Transition.Started -> {
                clearReason()
                Result.Started(evidenceFor(t.episode))
            }
            is CryStateMachine.Transition.Continued -> {
                maybeClassify(t.episode, window.timestampMs, window)
                Result.Ongoing(evidenceFor(t.episode))
            }
            is CryStateMachine.Transition.Ended -> Result.Ended(evidenceFor(t.episode))
        }
    }

    private fun maybeClassify(
        episode: CryEpisode,
        nowMs: Long,
        window: AudioCapture.Window,
    ) {
        val head = classifier ?: return
        if (episode.durationSeconds < config.classifyAfterSeconds) return
        if (nowMs - lastClassifyMs < config.classifyEverySeconds * 1000L) return

        lastClassifyMs = nowMs
        val labels = head.classes
        if (reasonOut.size != labels.size) reasonOut = FloatArray(labels.size)
        if (reasonSum.size != labels.size) reasonSum = FloatArray(labels.size)

        // The forest is trained on statistics over a whole window rather than
        // on the patch the detector sees, so the features come from the raw
        // samples, not from the log-mel buffer the detector just overwrote.
        ReasonFeatures.extract(window.samples, 0, window.samples.size, reasonFeatures)
        head.predict(reasonFeatures, reasonOut)

        for (i in reasonOut.indices) reasonSum[i] += reasonOut[i]
        reasonVotes++

        val order = reasonSum.indices.sortedByDescending { reasonSum[it] }
        lastHypothesis = ReasonHypothesis(
            label = labels[order[0]],
            confidence = reasonSum[order[0]] / reasonVotes,
            runnerUp = order.getOrNull(1)?.let { labels[it] },
            runnerUpConfidence =
                order.getOrNull(1)?.let { reasonSum[it] / reasonVotes } ?: 0f,
            subjectWiseAuc = reasonAuc,
            trustworthy = reasonTrustworthy,
            windowsAveraged = reasonVotes,
        )
    }

    /** Forget this episode's votes. Called when one starts and when one ends. */
    private fun clearReason() {
        lastHypothesis = null
        lastClassifyMs = 0L
        reasonSum.fill(0f)
        reasonVotes = 0
    }

    private fun evidenceFor(episode: CryEpisode) = CryEvidence(
        durationSeconds = episode.durationSeconds,
        peakDbfs = episode.peakDbfs,
        trend = episode.trend(),
        detectorConfidence = episode.meanConfidence,
        envelope = episode.normalisedEnvelope(),
        hypothesis = lastHypothesis,
    )

    /** Feed a decoded clip through the pipeline. Used by the built-in self-test. */
    fun analyseClip(samples: FloatArray): Float {
        var best = 0f
        var offset = 0
        while (offset + LogMelFrontend.PATCH_SAMPLES <= samples.size) {
            LogMelFrontend.patch(samples, offset, patch)
            detector.run(patch, detectOut)
            if (detectOut[1] > best) best = detectOut[1]
            offset += LogMelFrontend.PATCH_SAMPLES / 2
        }
        return best
    }

    fun reset() {
        state.reset()
        clearReason()
    }

    override fun close() {
        detector.close()
    }
}
