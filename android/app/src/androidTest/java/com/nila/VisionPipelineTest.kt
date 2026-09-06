package com.nila

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.vision.FaceWatcher
import com.nila.vision.MotionEnergy
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The vision lane on a real device, minus the one thing a synthetic frame
 * cannot supply.
 *
 * Motion energy is fully exercised here with rendered frames. The face detector
 * is checked for loading and for not hallucinating a face in a blank room --
 * whether BlazeFace finds a *real infant* still needs a phone pointed at a cot,
 * and no test here pretends otherwise.
 */
@RunWith(AndroidJUnit4::class)
class VisionPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun frame(blobX: Float, blobY: Float = 200f, radius: Float = 60f): Bitmap {
        val bitmap = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(40, 40, 46))
        canvas.drawCircle(blobX, blobY, radius,
                          Paint().apply { color = Color.rgb(210, 205, 195) })
        return bitmap
    }

    @Test
    fun motionEnergyIsNearZeroForAStaticScene() {
        val motion = MotionEnergy()
        motion.update(frame(200f))
        repeat(5) {
            val reading = motion.update(frame(200f))
            assertTrue("a still scene should read near zero, got ${reading.energy}",
                       reading.energy < 0.01f)
        }
    }

    @Test
    fun motionEnergyRisesWhenTheSceneMoves() {
        val motion = MotionEnergy()
        motion.update(frame(120f))
        val moved = motion.update(frame(300f))
        assertTrue("a large displacement should register, got ${moved.energy}",
                   moved.energy > 0.01f)
        assertTrue("cells should be flagged active, got ${moved.activeFraction}",
                   moved.activeFraction > 0.02f)
    }

    @Test
    fun theBaselineNeedsEnoughHistoryBeforeItIsTrusted() {
        val motion = MotionEnergy()
        assertTrue("baseline claimed too early", !motion.baselineReady)
        repeat(40) { motion.update(frame(100f + it * 4f)) }
        assertTrue("baseline never became ready", motion.baselineReady)
    }

    @Test
    fun resetClearsTheBaseline() {
        val motion = MotionEnergy()
        repeat(40) { motion.update(frame(100f + it * 4f)) }
        motion.reset()
        assertTrue("reset left the baseline in place", !motion.baselineReady)
    }

    @Test
    fun theFaceDetectorLoadsAndFindsNoFaceInAnEmptyRoom() {
        val watcher = FaceWatcher(context)
        try {
            assertTrue("face model failed to load", watcher.isAvailable)
            val states = (1..6).map { watcher.analyse(frame(200f)) }
            assertTrue(
                "detector hallucinated a face in a blank frame: $states",
                states.none { it is FaceWatcher.State.FaceVisible },
            )
        } finally {
            watcher.close()
        }
    }

    @Test
    fun aWorkingDelegateIsSelectedRatherThanAssumed() {
        // A delegate that constructs can still throw on its first inference.
        // If that is not caught, every frame returns Unavailable and the camera
        // lane silently does nothing -- which is exactly what happened on this
        // emulator before the probe was added.
        val watcher = FaceWatcher(context)
        try {
            val states = (1..6).map { watcher.analyse(frame(200f)) }
            assertTrue(
                "no delegate could actually run a frame: $states",
                states.none { it is FaceWatcher.State.Unavailable },
            )
        } finally {
            watcher.close()
        }
    }

    @Test
    fun aPersistentlyFacelessSceneEventuallyAlerts() {
        val watcher = FaceWatcher(context)
        try {
            val states = (1..20).map { watcher.analyse(frame(200f)) }
            assertTrue(
                "never reached FaceMissing after 20 faceless frames. States: " +
                    states.map { it::class.java.simpleName },
                states.any { it is FaceWatcher.State.FaceMissing },
            )
        } finally {
            watcher.close()
        }
    }
}
