package com.nila.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import java.io.Closeable

/**
 * Watches whether the baby's face is visible, and how much the scene is moving.
 *
 * Face detection rather than pose estimation, and the choice is evidence-based:
 * pose models are trained on adult body proportions and measurably fail on
 * infants -- the research community built a separate dataset (SyRIP) and a
 * domain-adapted model precisely because of this. Face detectors do not have
 * that problem, because a face is a face.
 *
 * It also yields a better alert. "I can't see your baby's face" is a single rule
 * that covers rolling prone, a blanket over the face, and the camera being
 * knocked or blocked -- three real hazards -- and it never claims to know which
 * one occurred. Combined with motion energy, the system can distinguish a face
 * hidden while the baby is still moving from a face hidden and gone quiet.
 */
class FaceWatcher(
    context: Context,
    private val config: Config = Config(),
) : Closeable {

    private val appContext = context.applicationContext

    data class Config(
        val modelAsset: String = "blaze_face_short_range.tflite",
        val minDetectionConfidence: Float = 0.45f,
        /** Consecutive frames before we believe the face is gone. */
        val missingFramesToAlert: Int = 12,
        /** Consecutive frames before we believe it is back. Lower: recovery
         *  should be quicker to trust than loss, or the UI flaps. */
        val presentFramesToClear: Int = 4,
        val stillnessRelativeThreshold: Float = 0.22f,
        val stillnessFramesToAlert: Int = 40,
    )

    companion object { private const val TAG = "FaceWatcher" }

    sealed interface State {
        data object Starting : State
        data class FaceVisible(val confidence: Float, val motion: Float) : State
        data class FaceMissing(val forFrames: Int, val stillMoving: Boolean) : State
        data class UnusuallyStill(val forFrames: Int) : State
        data class Unavailable(val reason: String) : State
    }

    private val motion = MotionEnergy()
    private val rules = FaceRules(
        missingFramesToAlert = config.missingFramesToAlert,
        presentFramesToClear = config.presentFramesToClear,
        stillnessRelativeThreshold = config.stillnessRelativeThreshold,
        stillnessFramesToAlert = config.stillnessFramesToAlert,
    )

    private var detector: FaceDetector? = null
    private var failureReason: String? = null
    private var triedCpuFallback = false

    /**
     * The delegate is verified with a real inference, not just constructed.
     *
     * MediaPipe's GPU delegate builds happily on hardware where it then throws
     * GL_INVALID_ENUM on the first frame -- emulators and some real drivers
     * both do this. Constructing successfully proves nothing, so we push a
     * probe bitmap through before trusting it.
     */
    private fun build(delegate: Delegate): FaceDetector =
        FaceDetector.createFromOptions(
            appContext,
            FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(config.modelAsset)
                        .setDelegate(delegate)
                        .build()
                )
                .setMinDetectionConfidence(config.minDetectionConfidence)
                .setRunningMode(RunningMode.IMAGE)
                .build()
        )

    private fun probe(candidate: FaceDetector) {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            candidate.detect(BitmapImageBuilder(bitmap).build())
        } finally {
            bitmap.recycle()
        }
    }

    private fun open(): FaceDetector? {
        // GPU first: it is the delegate MediaPipe exposes for vision, and the
        // NPU is already busy with the audio lane.
        for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
            val candidate = runCatching { build(delegate) }.getOrElse {
                Log.w(TAG, "$delegate could not be created: ${it.message}")
                null
            } ?: continue

            val verified = runCatching { probe(candidate) }
            if (verified.isSuccess) {
                Log.i(TAG, "face detector running on $delegate")
                if (delegate == Delegate.CPU) triedCpuFallback = true
                return candidate
            }

            Log.w(TAG, "$delegate built but failed its first inference " +
                "(${verified.exceptionOrNull()?.message}); falling back")
            runCatching { candidate.close() }
        }
        failureReason = "no delegate could run the face model"
        Log.e(TAG, failureReason!!)
        return null
    }

    init {
        detector = open()
    }

    var lastMotion: MotionEnergy.Reading = MotionEnergy.Reading(0f, 0f, 1f)
        private set

    fun analyse(frame: Bitmap): State {
        lastMotion = motion.update(frame)
        val det = detector ?: return State.Unavailable(failureReason ?: "unavailable")

        val faceFound = try {
            det.detect(BitmapImageBuilder(frame).build()).detections().isNotEmpty()
        } catch (t: Throwable) {
            // A delegate that passed its probe can still fail later -- a driver
            // reset, or the GPU being taken by something else. Rebuild once on
            // CPU rather than going dark for the rest of the night.
            if (!triedCpuFallback) {
                Log.w(TAG, "detection failed at runtime; rebuilding on CPU", t)
                runCatching { det.close() }
                triedCpuFallback = true
                detector = runCatching { build(Delegate.CPU) }.getOrNull()
                failureReason = if (detector == null) {
                    t.message ?: "detection failed"
                } else null
                return State.Starting
            }
            failureReason = t.message ?: "detection failed"
            return State.Unavailable(failureReason!!)
        }

        return rules.update(
            FaceRules.Observation(
                faceFound = faceFound,
                motion = lastMotion,
                baselineReady = motion.baselineReady,
            )
        )
    }

    fun reset() {
        motion.reset()
        rules.reset()
    }

    val isAvailable: Boolean get() = detector != null

    override fun close() {
        runCatching { detector?.close() }
    }
}
