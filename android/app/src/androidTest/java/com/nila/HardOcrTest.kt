package com.nila

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.assistant.KnowledgeIndex
import com.nila.assistant.TextScanner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/**
 * The photographs that actually defeat OCR.
 *
 * Built from a real failure: a Calpol box came back "could not check this one"
 * while a cleaner shot of the same medicine worked. The difference was a
 * diagonal supplier watermark, low contrast between silver foil and white
 * print, and the name occupying a small part of a large frame.
 *
 * Each case here reproduces one of those conditions. The single-pass baseline
 * is measured alongside, so the preprocessing has to earn its place rather than
 * just be asserted to help.
 */
@RunWith(AndroidJUnit4::class)
class HardOcrTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scanner: TextScanner
    private lateinit var index: KnowledgeIndex

    @Before
    fun setUp() = runBlocking {
        scanner = TextScanner(context)
        scanner.warmUp()
        index = KnowledgeIndex.load(context)
    }

    /** A watermarked box shot: the name small, a supplier URL across it. */
    private fun watermarkedBox(): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(246, 246, 248))

        // The box: a modest region of a large frame, as in a stock photo.
        val boxPaint = Paint().apply { color = Color.rgb(238, 232, 244) }
        canvas.drawRect(300f, 250f, 900f, 560f, boxPaint)

        val small = Paint().apply {
            color = Color.rgb(48, 40, 60); textSize = 26f; isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("PARACETAMOL FAST RELEASE", 320f, 320f, small)
        canvas.drawText("TABLETS 500 MG", 320f, 356f, small)
        val brand = Paint().apply {
            color = Color.rgb(30, 25, 40); textSize = 40f; isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Calpol 500", 320f, 420f, brand)

        // The watermark: large, diagonal, higher contrast than the print.
        canvas.save()
        canvas.rotate(-18f, 600f, 400f)
        canvas.drawText(
            "www.medsavehealth.in",
            180f, 420f,
            Paint().apply {
                color = Color.argb(150, 90, 90, 100); textSize = 62f
                isAntiAlias = true
            },
        )
        canvas.restore()
        return bitmap
    }

    /** Low contrast: white print on silver foil. */
    private fun lowContrastFoil(): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(196, 198, 202))
        val faint = Paint().apply {
            color = Color.rgb(228, 230, 234); textSize = 44f; isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("IBUPROFEN 400 mg", 70f, 200f, faint)
        canvas.drawText("Tablets IP", 70f, 270f, faint)
        return bitmap
    }

    /** A strip photographed sideways. */
    private fun sideways(): Bitmap {
        val upright = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)
        Canvas(upright).apply {
            drawColor(Color.WHITE)
            drawText(
                "AMOXICILLIN 250 mg Capsules IP", 60f, 220f,
                Paint().apply {
                    color = Color.BLACK; textSize = 52f; isAntiAlias = true
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                },
            )
        }
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height,
                                   matrix, true)
    }

    /**
     * Make a rendered image behave like a photograph.
     *
     * Crisp synthetic text is read perfectly by any recogniser, so testing
     * against it proves nothing about the images people actually take. Real
     * photos arrive small, soft, compressed and noisy, and it is the
     * combination that defeats OCR rather than any one of them.
     */
    private fun degrade(source: Bitmap, scale: Float = 0.34f, quality: Int = 32): Bitmap {
        // Distance: the text ends up a handful of pixels tall.
        val small = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )

        // Sensor noise.
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val rng = java.util.Random(11)
        for (i in pixels.indices) {
            val n = (rng.nextGaussian() * 11).toInt()
            val p = pixels[i]
            fun ch(shift: Int) = (((p shr shift) and 0xFF) + n).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        val noisy = Bitmap.createBitmap(pixels, small.width, small.height,
                                        Bitmap.Config.ARGB_8888)
        small.recycle()

        // JPEG artefacts, which is what actually arrives from a camera or a
        // messaging app.
        val bytes = java.io.ByteArrayOutputStream()
        noisy.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
        noisy.recycle()
        val raw = bytes.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
    }

    /** One pass over the raw bitmap, i.e. what the scanner used to do. */
    private suspend fun singlePass(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resume("") }
        }

    private fun resolves(text: String, expected: String): Boolean {
        val hit = index.findMedicine(text)
        return hit != null && hit.doc.generic.equals(expected, ignoreCase = true)
    }

    @Test
    fun aWatermarkedBoxResolves() = runBlocking {
        val bitmap = degrade(watermarkedBox())
        val baseline = singlePass(bitmap)
        val scan = scanner.scan(bitmap)

        assertTrue(
            "watermarked box did not resolve. Read: '${scan.text.take(160)}'",
            resolves(scan.text, "Paracetamol"),
        )
        // The watermark must not be what survived into the text.
        assertTrue(
            "the supplier watermark leaked into the result: ${scan.text}",
            !scan.text.contains("medsavehealth", true),
        )
        println("WATERMARK baseline='${baseline.replace("\n", " ").take(90)}' " +
            "multipass='${scan.text.take(90)}' passes=${scan.passes}")
    }

    @Test
    fun lowContrastFoilResolves() = runBlocking {
        val bitmap = degrade(lowContrastFoil())
        val baseline = singlePass(bitmap)
        val scan = scanner.scan(bitmap)
        assertTrue(
            "low-contrast foil did not resolve. Read: '${scan.text.take(160)}'",
            resolves(scan.text, "Ibuprofen"),
        )
        println("FOIL baseline='${baseline.replace("\n", " ").take(90)}' " +
            "multipass='${scan.text.take(90)}' passes=${scan.passes}")
    }

    @Test
    fun aSidewaysStripResolves() = runBlocking {
        val bitmap = degrade(sideways())
        val baseline = singlePass(bitmap)
        val scan = scanner.scan(bitmap)
        assertTrue(
            "sideways strip did not resolve. Read: '${scan.text.take(160)}'",
            resolves(scan.text, "Amoxicillin"),
        )
        println("SIDEWAYS baseline='${baseline.replace("\n", " ").take(90)}' " +
            "multipass='${scan.text.take(90)}' passes=${scan.passes}")
    }

    @Test
    fun aCleanImageStillCostsOnePass() = runBlocking {
        // The escalation must not tax the common case.
        val bitmap = Bitmap.createBitmap(1500, 500, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(
                "PARACETAMOL Tablets IP 500 mg", 60f, 260f,
                Paint().apply {
                    color = Color.BLACK; textSize = 64f; isAntiAlias = true
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                },
            )
        }
        val scan = scanner.scan(bitmap)
        assertTrue("clean image took ${scan.passes} passes", scan.passes == 1)
        assertNotNull(index.findMedicine(scan.text))
    }
}
