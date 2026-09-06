package com.nila

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.ocr.PaddleOcr
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does PP-OCRv4 actually run here, and does it read what ML Kit could not?
 *
 * The previous OCR work could not answer the second half: the synthetic hard
 * images were still legible to ML Kit at baseline, so the improvement was
 * asserted rather than measured. These cases are built the other way round --
 * each one is a condition ML Kit is *architecturally* unable to handle
 * (a rotated line, an upside-down line), so a pass here is attributable.
 */
@RunWith(AndroidJUnit4::class)
class PaddleOcrTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var ocr: PaddleOcr

    @Before
    fun setUp() {
        ocr = PaddleOcr.shared(context)
        assertTrue("PP-OCR models failed to load: ${ocr.describe()}", ocr.load())
    }


    private fun label(text: String, rotation: Float, background: Int, ink: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(900, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint = Paint().apply {
            color = ink
            textSize = 58f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.save()
        canvas.rotate(rotation, 450f, 250f)
        canvas.drawText(text, 150f, 270f, paint)
        canvas.restore()
        return bitmap
    }

    private fun normalise(text: String) =
        text.uppercase().filter { it.isLetterOrDigit() }

    @Test
    fun readsUprightLabel() {
        val result = ocr.read(
            label("IBUPROFEN 400", 0f, Color.WHITE, Color.rgb(20, 20, 30))
        )
        assertTrue(
            "expected IBUPROFEN, got '${result.text}'",
            normalise(result.text).contains("IBUPROFEN"),
        )
    }

    /**
     * Twenty-five degrees off horizontal.
     *
     * This is the case the rotated-box detector exists for. ML Kit's boxes are
     * axis-aligned, so a line at this angle is read through a window containing
     * mostly background.
     */
    @Test
    fun readsRotatedLabel() {
        val result = ocr.read(
            label("AMOXICILLIN", 25f, Color.WHITE, Color.rgb(20, 20, 30))
        )
        assertTrue(
            "expected AMOXICILLIN at 25 degrees, got '${result.text}'",
            normalise(result.text).contains("AMOXICILLIN"),
        )
    }

    /**
     * Upside down -- the angle classifier's whole job.
     *
     * A blister strip has no obvious "up", and a parent holding a torn half of
     * one photographs it whichever way it came out of the packet.
     */
    @Test
    fun readsUpsideDownLabel() {
        val result = ocr.read(
            label("PARACETAMOL", 180f, Color.WHITE, Color.rgb(20, 20, 30))
        )
        assertTrue(
            "expected PARACETAMOL upside down, got '${result.text}'",
            normalise(result.text).contains("PARACETAMOL"),
        )
    }

    /** White print on silver foil: the contrast case. */
    @Test
    fun readsLowContrastFoil() {
        val result = ocr.read(
            label("DOMPERIDONE", 0f, Color.rgb(176, 178, 184), Color.rgb(228, 230, 234))
        )
        assertTrue(
            "expected DOMPERIDONE on foil, got '${result.text}'",
            normalise(result.text).contains("DOMPERIDONE"),
        )
    }
}
