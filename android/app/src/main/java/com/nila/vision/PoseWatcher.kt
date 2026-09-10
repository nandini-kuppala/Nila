package com.nila.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.Closeable

/**
 * MediaPipe Pose Landmarker, wrapped to hand [PoseClassifier] plain landmarks.
 *
 * The full model rather than the lite one: 9 MB in the APK, and on a Snapdragon
 * it costs a couple of milliseconds more per frame at ~5 fps -- nothing against
 * a night of battery, and the accuracy difference matters more here than usual
 * because the subject is an infant and the model was not trained on one.
 *
 * Delegate selection is the same pattern [FaceWatcher] uses, and for the same
 * reason: MediaPipe's GPU delegate constructs happily on hardware where it then
 * throws on the first real frame, so it is verified with an actual inference
 * before being trusted. What is different is that failing here is not fatal --
 * the camera lane degrades to face presence and motion energy, which is exactly
 * what it did before pose existed.
 */
class PoseWatcher(
    context: Context,
    private val config: Config = Config(),
) : Closeable {

    private val appContext = context.applicationContext

    data class Config(
        val modelAsset: String = "pose_landmarker_full.task",
        /**
         * Low, deliberately.
         *
         * The model is being asked whether there is roughly a body and roughly
         * where its trunk is, and [PoseClassifier] discards any frame whose
         * shoulders and hips are not individually visible. A high detection
         * threshold here would reject partly-covered babies -- under a blanket,
         * in a sleeping bag -- which is most of them.
         */
        val minDetectionConfidence: Float = 0.4f,
        val minPresenceConfidence: Float = 0.4f,
        val minTrackingConfidence: Float = 0.4f,
    )

    companion object { private const val TAG = "PoseWatcher" }

    private var landmarker: PoseLandmarker? = null
    private var failureReason: String? = null
    private var triedCpuFallback = false

    @Volatile var delegateName: String = "-"; private set

    private fun build(delegate: Delegate): PoseLandmarker =
        PoseLandmarker.createFromOptions(
            appContext,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(config.modelAsset)
                        .setDelegate(delegate)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(config.minDetectionConfidence)
                .setMinPosePresenceConfidence(config.minPresenceConfidence)
                .setMinTrackingConfidence(config.minTrackingConfidence)
                .setOutputSegmentationMasks(false)
                .build()
        )

    private fun probe(candidate: PoseLandmarker) {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            candidate.detect(BitmapImageBuilder(bitmap).build())
        } finally {
            bitmap.recycle()
        }
    }

    private fun open(): PoseLandmarker? {
        for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
            val candidate = runCatching { build(delegate) }.getOrElse {
                Log.w(TAG, "$delegate could not be created: ${it.message}")
                null
            } ?: continue

            val verified = runCatching { probe(candidate) }
            if (verified.isSuccess) {
                Log.i(TAG, "pose landmarker running on $delegate")
                delegateName = delegate.name
                if (delegate == Delegate.CPU) triedCpuFallback = true
                return candidate
            }

            Log.w(TAG, "$delegate built but failed its first inference " +
                "(${verified.exceptionOrNull()?.message}); falling back")
            runCatching { candidate.close() }
        }
        failureReason = "no delegate could run the pose model"
        Log.e(TAG, failureReason!!)
        return null
    }

    init {
        landmarker = open()
    }

    val isAvailable: Boolean get() = landmarker != null
    val unavailableReason: String? get() = failureReason

    /**
     * One frame, as a posture.
     *
     * Returns [PoseReading.ABSENT] rather than throwing for every failure mode
     * there is -- no model, no body in frame, a trunk too occluded to reason
     * over. The rules treat all three the same way on purpose: what the parent
     * needs to know is that the body cannot be seen, not which layer failed to
     * see it.
     */
    fun analyse(frame: Bitmap): PoseReading {
        val model = landmarker ?: return PoseReading.ABSENT
        val result = try {
            model.detect(BitmapImageBuilder(frame).build())
        } catch (t: Throwable) {
            // A delegate that passed its probe can still fail later: a driver
            // reset, or the GPU taken by something else. Rebuild once on CPU
            // rather than losing the lane for the rest of the night.
            if (!triedCpuFallback) {
                Log.w(TAG, "pose failed at runtime; rebuilding on CPU", t)
                runCatching { model.close() }
                triedCpuFallback = true
                landmarker = runCatching { build(Delegate.CPU) }.getOrNull()
                delegateName = if (landmarker != null) "CPU" else "-"
                failureReason = if (landmarker == null) {
                    t.message ?: "pose detection failed"
                } else null
                return PoseReading.ABSENT
            }
            failureReason = t.message ?: "pose detection failed"
            return PoseReading.ABSENT
        }

        val first = result.landmarks().firstOrNull() ?: return PoseReading.ABSENT
        return PoseClassifier.classify(
            first.map { lm ->
                Landmark(
                    x = lm.x(),
                    y = lm.y(),
                    z = lm.z(),
                    // Visibility is optional in the proto and absent on some
                    // builds. Absent has to read as "no opinion" rather than
                    // as zero, or every landmark fails the visibility gate and
                    // the lane reports a permanently missing baby.
                    visibility = lm.visibility().orElse(1f),
                )
            }
        )
    }

    override fun close() {
        runCatching { landmarker?.close() }
        landmarker = null
    }
}
