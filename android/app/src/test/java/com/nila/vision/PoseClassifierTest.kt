package com.nila.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Landmarks to a posture, on hand-built skeletons.
 *
 * The geometry is where this can go wrong, and it needs neither a camera nor a
 * baby to exercise. The cases below are the four positions that carry different
 * consequences -- on the back, on the front, on the side, and standing -- plus
 * the degenerate inputs that arrive constantly in a real nursery: a body half
 * out of frame, a trunk behind a blanket, a model that returned nothing.
 */
class PoseClassifierTest {

    /**
     * A skeleton, built by placing the four trunk points directly.
     *
     * @param faceVisible whether the nose and eyes are reported visible, which
     * is what distinguishes lying face-up from lying face-down.
     */
    private fun skeleton(
        shoulderL: Pair<Float, Float>,
        shoulderR: Pair<Float, Float>,
        hipL: Pair<Float, Float>,
        hipR: Pair<Float, Float>,
        kneeL: Pair<Float, Float> = 0.5f to 0.9f,
        kneeR: Pair<Float, Float> = 0.5f to 0.9f,
        faceVisible: Boolean = true,
        visibility: Float = 0.9f,
    ): List<Landmark> {
        val faceVis = if (faceVisible) 0.9f else 0.1f
        return List(33) { i ->
            when (i) {
                PoseClassifier.NOSE -> Landmark(0.5f, 0.3f, visibility = faceVis)
                PoseClassifier.LEFT_EYE -> Landmark(0.48f, 0.29f, visibility = faceVis)
                PoseClassifier.RIGHT_EYE -> Landmark(0.52f, 0.29f, visibility = faceVis)
                PoseClassifier.LEFT_SHOULDER ->
                    Landmark(shoulderL.first, shoulderL.second, visibility = visibility)
                PoseClassifier.RIGHT_SHOULDER ->
                    Landmark(shoulderR.first, shoulderR.second, visibility = visibility)
                PoseClassifier.LEFT_HIP ->
                    Landmark(hipL.first, hipL.second, visibility = visibility)
                PoseClassifier.RIGHT_HIP ->
                    Landmark(hipR.first, hipR.second, visibility = visibility)
                PoseClassifier.LEFT_KNEE ->
                    Landmark(kneeL.first, kneeL.second, visibility = visibility)
                PoseClassifier.RIGHT_KNEE ->
                    Landmark(kneeR.first, kneeR.second, visibility = visibility)
                else -> Landmark(0.5f, 0.5f, visibility = visibility)
            }
        }
    }

    /** Lying across the frame, shoulders square to the camera, face in view. */
    private fun onBack(faceVisible: Boolean = true) = skeleton(
        shoulderL = 0.35f to 0.45f,
        shoulderR = 0.35f to 0.65f,
        hipL = 0.68f to 0.47f,
        hipR = 0.68f to 0.63f,
        faceVisible = faceVisible,
    )

    @Test
    fun `flat with the face visible is on the back`() {
        val reading = PoseClassifier.classify(onBack())
        assertTrue(reading.present)
        assertEquals(Posture.ON_BACK, reading.posture)
        assertTrue(reading.faceVisible)
    }

    /**
     * The event the whole class exists to name: flat, both shoulders square to
     * the camera, and no face anywhere. That is a baby who has rolled prone.
     */
    @Test
    fun `flat with no face is on the front`() {
        val reading = PoseClassifier.classify(onBack(faceVisible = false))
        assertEquals(Posture.ON_FRONT, reading.posture)
        assertFalse(reading.faceVisible)
    }

    /** One shoulder occluding the other collapses the span: edge-on. */
    @Test
    fun `flat with the shoulders stacked is on the side`() {
        val reading = PoseClassifier.classify(
            skeleton(
                shoulderL = 0.35f to 0.50f,
                shoulderR = 0.36f to 0.52f,
                hipL = 0.70f to 0.50f,
                hipR = 0.71f to 0.52f,
                faceVisible = false,
            )
        )
        assertEquals(Posture.ON_SIDE, reading.posture)
    }

    /**
     * Trunk running down the image, knees folded back up towards it. Sitting is
     * also the fallback when the knees cannot be seen at all, which is the
     * common case behind a cot bumper -- and the safer of the two, because it
     * is the quieter alert.
     */
    @Test
    fun `a vertical trunk with the knees folded up is sitting`() {
        val reading = PoseClassifier.classify(
            skeleton(
                shoulderL = 0.42f to 0.30f,
                shoulderR = 0.58f to 0.30f,
                hipL = 0.44f to 0.62f,
                hipR = 0.56f to 0.62f,
                kneeL = 0.44f to 0.55f,
                kneeR = 0.56f to 0.55f,
            )
        )
        assertEquals(Posture.SITTING, reading.posture)
    }

    @Test
    fun `a vertical trunk with the legs carrying on below is upright`() {
        val reading = PoseClassifier.classify(
            skeleton(
                shoulderL = 0.42f to 0.20f,
                shoulderR = 0.58f to 0.20f,
                hipL = 0.44f to 0.52f,
                hipR = 0.56f to 0.52f,
                kneeL = 0.45f to 0.78f,
                kneeR = 0.55f to 0.78f,
            )
        )
        assertEquals(Posture.UPRIGHT, reading.posture)
    }

    @Test
    fun `an upright trunk with no visible knees falls back to sitting`() {
        val hidden = skeleton(
            shoulderL = 0.42f to 0.25f,
            shoulderR = 0.58f to 0.25f,
            hipL = 0.44f to 0.58f,
            hipR = 0.56f to 0.58f,
        ).toMutableList()
        hidden[PoseClassifier.LEFT_KNEE] = Landmark(0.45f, 0.8f, visibility = 0.05f)
        hidden[PoseClassifier.RIGHT_KNEE] = Landmark(0.55f, 0.8f, visibility = 0.05f)
        assertEquals(Posture.SITTING, PoseClassifier.classify(hidden).posture)
    }

    /**
     * A baby lying with their head to the left of frame is the normal case for
     * a phone on a shelf beside a cot, and their shoulders are then separated
     * *vertically*. Measuring the shoulder span across the image instead of
     * across the torso reported every one of them as lying on their side --
     * so the same lying pose is checked at several angles here.
     */
    @Test
    fun `a lying pose reads the same at any angle across the frame`() {
        // Torso from (0.35,0.5) outwards at 90, 70 and 55 degrees off vertical,
        // with the shoulders always square across it.
        val angles = listOf(90.0, 70.0, 55.0)
        angles.forEach { deg ->
            val rad = Math.toRadians(deg)
            val tx = kotlin.math.sin(rad).toFloat()
            val ty = kotlin.math.cos(rad).toFloat()
            val px = -ty
            val py = tx
            val sx = 0.4f
            val sy = 0.4f
            val len = 0.30f
            val half = 0.13f
            val reading = PoseClassifier.classify(
                skeleton(
                    shoulderL = (sx + px * half) to (sy + py * half),
                    shoulderR = (sx - px * half) to (sy - py * half),
                    hipL = (sx + tx * len + px * half * 0.8f) to
                        (sy + ty * len + py * half * 0.8f),
                    hipR = (sx + tx * len - px * half * 0.8f) to
                        (sy + ty * len - py * half * 0.8f),
                )
            )
            assertEquals(
                "at $deg degrees off vertical",
                Posture.ON_BACK, reading.posture,
            )
        }
    }

    /**
     * A limit, pinned so nobody later mistakes it for a bug.
     *
     * A camera looking straight down the length of a cot sees a lying baby's
     * trunk running down the image, which is geometrically identical to a
     * standing one. No single view can separate those, and the app must not
     * pretend otherwise -- the fix is where the phone is placed, which is what
     * the watch screen tells the parent.
     */
    @Test
    fun `a trunk down the image reads as upright, which is the known limit`() {
        val reading = PoseClassifier.classify(
            skeleton(
                shoulderL = 0.42f to 0.35f,
                shoulderR = 0.58f to 0.35f,
                hipL = 0.44f to 0.68f,
                hipR = 0.56f to 0.68f,
                kneeL = 0.45f to 0.9f,
                kneeR = 0.55f to 0.9f,
            )
        )
        assertEquals(Posture.UPRIGHT, reading.posture)
    }

    /**
     * Half a trunk is not a trunk. Guessing a torso vector from one visible
     * side is how a baby lying on their side gets reported as standing up,
     * which is an urgent alert for a baby who is asleep.
     */
    @Test
    fun `a trunk that is not fully visible is absent, not guessed`() {
        val partial = onBack().toMutableList()
        partial[PoseClassifier.RIGHT_HIP] = Landmark(0.68f, 0.63f, visibility = 0.1f)
        val reading = PoseClassifier.classify(partial)
        assertFalse(reading.present)
        assertEquals(Posture.UNKNOWN, reading.posture)
    }

    @Test
    fun `an empty or short landmark list is absent`() {
        assertFalse(PoseClassifier.classify(emptyList()).present)
        assertFalse(PoseClassifier.classify(List(5) { Landmark(0.5f, 0.5f) }).present)
    }

    /** A degenerate trunk would divide by zero on its way to a tilt. */
    @Test
    fun `a zero-length trunk is absent rather than a crash`() {
        val collapsed = skeleton(
            shoulderL = 0.5f to 0.5f, shoulderR = 0.5f to 0.5f,
            hipL = 0.5f to 0.5f, hipR = 0.5f to 0.5f,
        )
        assertFalse(PoseClassifier.classify(collapsed).present)
    }

    @Test
    fun `tilt is folded so cot orientation does not matter`() {
        val headLeft = PoseClassifier.classify(onBack())
        val headRight = PoseClassifier.classify(
            skeleton(
                shoulderL = 0.68f to 0.45f,
                shoulderR = 0.68f to 0.65f,
                hipL = 0.35f to 0.47f,
                hipR = 0.35f to 0.63f,
            )
        )
        assertEquals(headLeft.posture, headRight.posture)
        assertEquals(headLeft.tiltDegrees, headRight.tiltDegrees, 2f)
    }

    /**
     * The distance proxy has to shrink as the baby moves away, because the
     * whole comparison in [ActivityRules] is built on that direction.
     */
    @Test
    fun `torso length shrinks with distance`() {
        val near = PoseClassifier.classify(onBack()).torsoLength
        val far = PoseClassifier.classify(
            skeleton(
                shoulderL = 0.48f to 0.49f,
                shoulderR = 0.48f to 0.55f,
                hipL = 0.60f to 0.50f,
                hipR = 0.60f to 0.54f,
            )
        ).torsoLength
        assertTrue("expected a smaller torso further away ($near vs $far)", far < near)
    }

    /**
     * Absent visibility must read as "no opinion", not as zero. Some MediaPipe
     * builds omit the field entirely, and treating that as invisible makes
     * every landmark fail the gate and the lane report a permanently missing
     * baby.
     */
    @Test
    fun `landmarks default to visible`() {
        assertEquals(1f, Landmark(0.5f, 0.5f).visibility, 1e-6f)
    }
}
