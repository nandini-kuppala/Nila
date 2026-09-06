package com.nila.vision

/**
 * The alert logic for the camera lane, separated from the camera.
 *
 * Split out so the part that decides whether to wake somebody can be tested
 * exhaustively on the JVM, in milliseconds, without a device or a face. What is
 * left inside [FaceWatcher] is one question -- "did BlazeFace find a face in
 * this bitmap" -- which genuinely needs a real phone pointed at a real cot.
 *
 * The two counters are asymmetric on purpose. Believing the face is gone should
 * be slow, because a baby turning its head is not an emergency. Believing it is
 * back should be quick, because an alert that lingers after the situation
 * resolved trains people to ignore alerts.
 */
class FaceRules(
    private val missingFramesToAlert: Int = 12,
    private val presentFramesToClear: Int = 4,
    private val stillnessRelativeThreshold: Float = 0.22f,
    private val stillnessFramesToAlert: Int = 40,
) {
    private var missingRun = 0
    private var presentRun = 0
    private var stillRun = 0
    private var everSeenFace = false

    /** Frame-level input, so the rules never touch a bitmap. */
    data class Observation(
        val faceFound: Boolean,
        val motion: MotionEnergy.Reading,
        val baselineReady: Boolean,
    )

    fun reset() {
        missingRun = 0
        presentRun = 0
        stillRun = 0
        everSeenFace = false
    }

    fun update(observation: Observation): FaceWatcher.State {
        if (observation.faceFound) {
            presentRun++
            if (presentRun >= presentFramesToClear) {
                missingRun = 0
                everSeenFace = true
            }
        } else {
            missingRun++
            presentRun = 0
        }

        // Stillness only counts once the scene baseline has settled. Without
        // this guard the first half-minute after arming always looks abnormally
        // quiet, because there is nothing yet to be quiet relative to.
        stillRun = if (observation.baselineReady &&
            observation.motion.relativeToBaseline < stillnessRelativeThreshold
        ) stillRun + 1 else 0

        return when {
            missingRun >= missingFramesToAlert -> FaceWatcher.State.FaceMissing(
                forFrames = missingRun,
                // Distinguishing "hidden but still moving" from "hidden and gone
                // quiet" is most of the value here -- they are very different
                // situations and only one of them is urgent.
                stillMoving = observation.motion.relativeToBaseline > 0.6f,
            )
            stillRun >= stillnessFramesToAlert -> FaceWatcher.State.UnusuallyStill(stillRun)
            observation.faceFound -> FaceWatcher.State.FaceVisible(
                confidence = 1f,
                motion = observation.motion.energy,
            )
            everSeenFace -> FaceWatcher.State.FaceVisible(
                confidence = 0.5f,
                motion = observation.motion.energy,
            )
            else -> FaceWatcher.State.Starting
        }
    }
}
