package com.nila.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * One pose landmark, normalised to the frame.
 *
 * A plain data class rather than MediaPipe's own type so everything below can
 * be tested on the JVM with hand-written skeletons. The geometry is where the
 * bugs are, and it needs neither a camera nor a baby to exercise.
 */
data class Landmark(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val visibility: Float = 1f,
)

/** How the baby is lying or sitting, as far as a single camera can tell. */
enum class Posture {
    UNKNOWN,
    /** Lying, face towards the camera. The position a baby should sleep in. */
    ON_BACK,
    /** Lying, face away or down. A roll to prone. */
    ON_FRONT,
    ON_SIDE,
    SITTING,
    /** Standing or pulling up -- in a cot, climbing. */
    UPRIGHT,
}

/**
 * Everything one frame of pose says, reduced to the few numbers the rules need.
 */
data class PoseReading(
    val present: Boolean,
    val posture: Posture,
    /** Body centre, normalised 0..1 in frame coordinates. */
    val cx: Float,
    val cy: Float,
    /**
     * Shoulder-to-hip distance, normalised.
     *
     * The distance proxy. It is not a length in centimetres and this code never
     * pretends otherwise -- turning it into one needs the camera's field of view
     * and the baby's actual torso length, and getting either wrong produces a
     * confident number that is simply false. What it supports is a comparison
     * against the same baby in the same cot a minute ago, which is the question
     * a monitor is actually asked.
     */
    val torsoLength: Float,
    /** True when the facial landmarks are visible, which is a proxy for face-up. */
    val faceVisible: Boolean,
    /** Mean visibility of the landmarks the classification relied on. */
    val confidence: Float,
    /** Degrees the torso is off image-vertical, 0 = upright, 90 = flat. */
    val tiltDegrees: Float,
) {
    companion object {
        val ABSENT = PoseReading(
            present = false, posture = Posture.UNKNOWN, cx = 0.5f, cy = 0.5f,
            torsoLength = 0f, faceVisible = false, confidence = 0f, tiltDegrees = 0f,
        )
    }
}

/**
 * Landmarks to a posture.
 *
 * ### Why pose here at all, when the face detector was chosen over it
 *
 * [FaceWatcher] deliberately does not estimate pose, and the reasoning still
 * holds: pose models are trained on adult body proportions and measurably fail
 * on infants, which is why the research community built SyRIP and a
 * domain-adapted model rather than using an off-the-shelf one.
 *
 * That argument is against trusting pose for anything fine-grained -- joint
 * angles, keypoint precision, anything that would need the model to be right
 * about an infant's proportions. It is not an argument against the two coarse
 * questions asked here: is the torso roughly flat or roughly upright, and has
 * the body's centre moved across the frame. Both survive a model that places
 * every landmark somewhat wrong, as long as it places them consistently wrong.
 *
 * So the thresholds below are deliberately wide, every rule needs several
 * consecutive frames to fire (see [ActivityRules]), and face presence remains
 * the primary signal. Pose is what turns "I can't see the face" into "rolled
 * onto their front" -- a strictly more useful sentence, and one that degrades
 * to the old one when the pose model has nothing.
 */
object PoseClassifier {

    // MediaPipe Pose Landmarker indices.
    const val NOSE = 0
    const val LEFT_EYE = 2
    const val RIGHT_EYE = 5
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28

    /** Below this a landmark is a guess and is not reasoned over. */
    const val MIN_VISIBILITY = 0.45f

    /** Torso this far off vertical counts as lying down. */
    const val LYING_TILT_DEGREES = 52f

    /** Torso within this of vertical counts as sitting or standing. */
    const val UPRIGHT_TILT_DEGREES = 32f

    /**
     * Shoulder span, relative to torso length, below which the body is edge-on.
     *
     * One shoulder occluding the other is the clearest single cue that a baby
     * is on their side, and it does not depend on the model getting infant
     * proportions right -- only on it finding both shoulders.
     *
     * Measured across the torso rather than across the image. Measuring it as
     * a horizontal distance looked equivalent and was not: a baby lying with
     * their head to the left of frame -- which is most babies, in most cots,
     * seen by a phone on a shelf -- has their shoulders separated vertically,
     * so a horizontal span reads as zero and every one of them was reported as
     * lying on their side.
     */
    const val SIDE_SPAN_RATIO = 0.42f

    /**
     * How far the knees continue past the hips, along the torso, to be standing.
     *
     * Also measured along the torso rather than down the image, and for the
     * same reason. Legs extending away from the hips is standing; knees folded
     * back towards the shoulders is sitting.
     */
    const val STANDING_LEG_RATIO = 0.45f

    fun classify(landmarks: List<Landmark>): PoseReading {
        if (landmarks.size <= RIGHT_ANKLE) return PoseReading.ABSENT

        val ls = landmarks[LEFT_SHOULDER]
        val rs = landmarks[RIGHT_SHOULDER]
        val lh = landmarks[LEFT_HIP]
        val rh = landmarks[RIGHT_HIP]

        // The trunk is the whole basis of this. Without both shoulders and both
        // hips there is no torso vector, and guessing one from a single side is
        // how a baby on their side gets reported as standing up.
        val trunk = listOf(ls, rs, lh, rh)
        if (trunk.any { it.visibility < MIN_VISIBILITY }) return PoseReading.ABSENT

        val shoulderX = (ls.x + rs.x) / 2f
        val shoulderY = (ls.y + rs.y) / 2f
        val hipX = (lh.x + rh.x) / 2f
        val hipY = (lh.y + rh.y) / 2f

        val torsoLength = hypot(hipX - shoulderX, hipY - shoulderY)
        if (torsoLength < 1e-4f) return PoseReading.ABSENT

        // Angle of the trunk away from image-vertical, folded into 0..90 so it
        // does not matter which end is up or which way the cot faces.
        val tilt = Math.toDegrees(
            abs(atan2((hipX - shoulderX).toDouble(), (hipY - shoulderY).toDouble()))
        ).toFloat().let { if (it > 90f) 180f - it else it }

        // Unit vector along the torso, shoulders to hips, and the axis at
        // right angles to it. Everything below is projected onto these rather
        // than onto the image axes, so the answer does not depend on which way
        // the cot happens to face the phone.
        val tx = (hipX - shoulderX) / torsoLength
        val ty = (hipY - shoulderY) / torsoLength
        val px = -ty
        val py = tx

        val shoulderSpan = abs((ls.x - rs.x) * px + (ls.y - rs.y) * py)
        val spanRatio = shoulderSpan / torsoLength

        val face = listOf(landmarks[NOSE], landmarks[LEFT_EYE], landmarks[RIGHT_EYE])
        val faceVisible = landmarks[NOSE].visibility >= MIN_VISIBILITY &&
            face.count { it.visibility >= MIN_VISIBILITY } >= 2

        val posture = when {
            tilt >= LYING_TILT_DEGREES -> when {
                spanRatio < SIDE_SPAN_RATIO -> Posture.ON_SIDE
                faceVisible -> Posture.ON_BACK
                // Flat, both shoulders square to the camera, and no face in
                // sight. That is a baby who has rolled onto their front, and it
                // is the event this whole class exists to name.
                else -> Posture.ON_FRONT
            }
            tilt <= UPRIGHT_TILT_DEGREES ->
                if (standing(landmarks, hipX, hipY, tx, ty, torsoLength))
                    Posture.UPRIGHT
                else Posture.SITTING
            else -> Posture.UNKNOWN
        }

        return PoseReading(
            present = true,
            posture = posture,
            cx = (shoulderX + hipX) / 2f,
            cy = (shoulderY + hipY) / 2f,
            torsoLength = torsoLength,
            faceVisible = faceVisible,
            confidence = trunk.map { it.visibility }.average().toFloat(),
            tiltDegrees = tilt,
        )
    }

    /**
     * Legs extending on past the hips, with the trunk upright.
     *
     * Distinguishes a baby standing from one sitting, which matters because
     * only one of them is trying to get out of a cot. Sitting is the safer
     * default when the knees cannot be seen: it is the quieter alert, and an
     * upright baby whose legs are hidden behind a cot bumper is far more often
     * sitting than climbing.
     */
    private fun standing(
        landmarks: List<Landmark>,
        hipX: Float,
        hipY: Float,
        tx: Float,
        ty: Float,
        torsoLength: Float,
    ): Boolean {
        val knees = listOf(landmarks[LEFT_KNEE], landmarks[RIGHT_KNEE])
            .filter { it.visibility >= MIN_VISIBILITY }
        if (knees.isEmpty()) return false
        val kneeX = knees.map { it.x }.average().toFloat()
        val kneeY = knees.map { it.y }.average().toFloat()
        // Displacement from the hips, projected onto the shoulders-to-hips
        // direction. Positive means the legs carry on away from the trunk.
        val along = ((kneeX - hipX) * tx + (kneeY - hipY) * ty) / torsoLength
        return along > STANDING_LEG_RATIO
    }

    /** Straight-line distance between two normalised points. */
    fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float =
        sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
}
