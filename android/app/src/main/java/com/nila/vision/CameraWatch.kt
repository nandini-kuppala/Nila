package com.nila.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives the camera lane: CameraX frames into [FaceWatcher], results back out.
 *
 * Analysis is throttled to roughly 5 fps rather than run at the camera's native
 * rate. Face presence and motion energy do not get meaningfully better above
 * that, and the difference over an eight-hour night is the difference between a
 * phone that is still watching in the morning and one that is flat.
 */
class CameraWatch(
    private val context: Context,
    private val onState: (Frame) -> Unit,
) {

    /**
     * Everything one analysed frame produced.
     *
     * One callback carrying all of it rather than three, because the rules
     * combine them: "no face" means something different depending on whether
     * there is a body in frame and whether it is flat, and a caller that
     * receives those separately has to reassemble the frame they came from.
     */
    data class Frame(
        val face: FaceWatcher.State,
        val motion: MotionEnergy.Reading,
        val pose: PoseReading,
    )
    /** The last few seconds, kept in memory and only written when a rule fires. */
    val preRoll = PreRoll(context)

    companion object {
        private const val TAG = "CameraWatch"
        private const val TARGET_INTERVAL_MS = 200L      // ~5 fps
        /**
         * Wide enough for both models.
         *
         * 480 was chosen for BlazeFace alone. The pose landmarker wants more of
         * the body resolved than that -- a baby occupying a third of the frame
         * has a torso about 60 px tall at 480, which is where landmark noise
         * starts to swamp the trunk angle. 640 costs about a millisecond more
         * per frame at 5 fps.
         */
        private const val ANALYSIS_WIDTH = 640
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val lastAnalysedAt = AtomicLong(0)
    private var watcher: FaceWatcher? = null
    private var pose: PoseWatcher? = null
    private var provider: ProcessCameraProvider? = null

    /** Which delegate the pose model landed on, for the settings screen. */
    val poseDelegate: String get() = pose?.delegateName ?: "-"
    val poseAvailable: Boolean get() = pose?.isAvailable == true

    @Volatile var framesAnalysed: Long = 0; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var lastPreRoll: java.io.File? = null; private set
    @Volatile private var lastDump: Long = 0L

    fun start(owner: LifecycleOwner, preview: Preview?) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                watcher = FaceWatcher(context)
                pose = PoseWatcher(context)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(executor, ::analyse) }

                cameraProvider.unbindAll()
                val useCases = listOfNotNull(preview, analysis).toTypedArray()
                cameraProvider.bindToLifecycle(
                    owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases
                )
                Log.i(TAG, "camera bound")
            } catch (t: Throwable) {
                lastError = t.message ?: t::class.java.simpleName
                Log.e(TAG, "camera failed to start", t)
                onState(
                    Frame(
                        FaceWatcher.State.Unavailable(lastError!!),
                        MotionEnergy.Reading(0f, 0f, 1f),
                        PoseReading.ABSENT,
                    )
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * The most recent analysed frame, for the vision model.
     *
     * Held as a single copy rather than a queue: the describer takes seconds,
     * and by the time it finishes the interesting frame is the one it started
     * with, not whichever arrived while it was thinking.
     */
    @Volatile private var latestFrame: Bitmap? = null

    /** A copy of the last frame, or null if none has been analysed yet. */
    fun snapshot(): Bitmap? = latestFrame?.copy(Bitmap.Config.ARGB_8888, false)

    /**
     * Put one bitmap through the pipeline as if the camera had produced it.
     *
     * Used by the demo footage. It is the same call `analyse` makes, so the
     * face detector, the motion comparison and the escalation rules cannot
     * tell the difference -- and neither can anyone reviewing the demo.
     */
    fun offerFrame(bitmap: Bitmap) {
        val w = watcher ?: return
        val state = w.analyse(bitmap)
        val reading = pose?.analyse(bitmap) ?: PoseReading.ABSENT
        framesAnalysed++
        latestFrame?.recycle()
        latestFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        onState(Frame(state, w.lastMotion, reading))
    }

    /**
     * Start the watcher without binding a camera.
     *
     * The demo needs the face detector and the motion baseline, and needs the
     * camera to stay off -- on an emulator it would otherwise show a rendered
     * living room underneath a video of a baby.
     */
    fun startHeadless() {
        if (watcher == null) watcher = FaceWatcher(context)
        if (pose == null) pose = PoseWatcher(context)
    }

    private fun analyse(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalysedAt.get() < TARGET_INTERVAL_MS) return
            lastAnalysedAt.set(now)

            val watcher = watcher ?: return
            val bitmap = downscale(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
            val state = watcher.analyse(bitmap)
            // Pose after face, on the same bitmap, on this same background
            // executor. Two models per frame at 5 fps rather than one; both are
            // small, and running them on the same frame is what lets "no face"
            // and "flat torso" be combined into "rolled onto their front".
            val reading = pose?.analyse(bitmap) ?: PoseReading.ABSENT
            framesAnalysed++

            // Buffered before the frame is released, so when a rule fires the
            // seconds leading up to it are already in hand.
            preRoll.offer(bitmap, now)

            // And one kept aside for the vision model, which is asked to look
            // on demand rather than per frame. A copy, because the analysis
            // bitmap is recycled at the end of this block and the describer
            // runs on another thread seconds later.
            latestFrame?.recycle()
            latestFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false)

            bitmap.recycle()

            if (state is FaceWatcher.State.FaceMissing && lastDump == 0L) {
                lastDump = now
                lastPreRoll = preRoll.dump("face_missing")
            } else if (state !is FaceWatcher.State.FaceMissing) {
                lastDump = 0L
            }

            onState(Frame(state, watcher.lastMotion, reading))
        } catch (t: Throwable) {
            lastError = t.message
            Log.w(TAG, "frame analysis failed", t)
        } finally {
            // Must always close, or the pipeline stalls after a few frames.
            proxy.close()
        }
    }

    /** Rotate upright and shrink -- BlazeFace gains nothing from a 4K frame. */
    private fun downscale(source: Bitmap, rotationDegrees: Int): Bitmap {
        val scale = ANALYSIS_WIDTH.toFloat() / source.width.coerceAtLeast(1)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height,
                                   matrix, true)
    }

    fun stop() {
        preRoll.clear()
        runCatching { provider?.unbindAll() }
        runCatching { watcher?.close() }
        runCatching { pose?.close() }
        watcher = null
        pose = null
        provider = null
    }

    fun shutdown() {
        latestFrame?.recycle()
        latestFrame = null
        stop()
        executor.shutdown()
    }
}
