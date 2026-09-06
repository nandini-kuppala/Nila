package com.nila

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.assistant.KnowledgeIndex
import com.nila.assistant.TextScanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scan lane, end to end, on a real device.
 *
 * The camera is the only part faked here, and it is faked by rendering the
 * medicine strip rather than by stubbing the recogniser: ML Kit's real
 * on-device OCR model reads a real bitmap, and its output goes through the real
 * retrieval. That covers everything except pointing a lens at a box, which is
 * the one step a unit test genuinely cannot stand in for.
 */
@RunWith(AndroidJUnit4::class)
class ScanPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scanner: TextScanner
    private lateinit var index: KnowledgeIndex

    @Before
    fun setUp() {
        scanner = TextScanner(context)
        index = KnowledgeIndex.load(context)
    }

    @After
    fun tearDown() = scanner.close()

    /** A plausible strip: brand large, generic smaller, plus packaging noise. */
    private fun renderStrip(
        brand: String,
        generic: String,
        extra: List<String> = listOf("Tablets IP", "Mfd by Acme Pharma Ltd",
                                     "B.No. AZ4417", "Exp 08/2029"),
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, 620, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val big = Paint().apply {
            color = Color.BLACK; textSize = 96f; isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val mid = Paint().apply {
            color = Color.BLACK; textSize = 58f; isAntiAlias = true
        }
        val small = Paint().apply {
            color = Color.DKGRAY; textSize = 40f; isAntiAlias = true
        }

        canvas.drawText(brand, 60f, 140f, big)
        canvas.drawText(generic, 60f, 240f, mid)
        extra.forEachIndexed { i, line ->
            canvas.drawText(line, 60f, 330f + i * 62f, small)
        }
        return bitmap
    }

    @Test
    fun ocrReadsTheStripAndResolvesTheMedicine() = runBlocking {
        val scan = scanner.scan(renderStrip("CROCIN 650", "Paracetamol Tablets IP"))
        assertTrue("OCR returned nothing: '${scan.text}'", scan.text.isNotBlank())

        val hit = index.findMedicine(scan.text)
        assertNotNull("OCR text did not resolve: '${scan.text}'", hit)
        assertTrue(
            "resolved to ${hit!!.doc.id} from '${scan.text}'",
            hit.doc.id.contains("paracetamol"),
        )
        assertTrue("answer has no source", hit.doc.source.isNotBlank())
        assertTrue("answer has no caution", hit.doc.caution.isNotBlank())
    }

    @Test
    fun brandOnlyPackagingStillResolves() = runBlocking {
        // Plenty of Indian packaging leads with the brand and buries the generic.
        val scan = scanner.scan(renderStrip("COMBIFLAM", "Pain relief"))
        val hit = index.findMedicine(scan.text)
        assertNotNull("brand-only strip did not resolve: '${scan.text}'", hit)
        assertTrue(hit!!.doc.id.contains("ibuprofen"))
    }

    @Test
    fun aMedicineWeFlagAsUnsafeIsReportedAsSuch() = runBlocking {
        val scan = scanner.scan(renderStrip("CODEINE", "Codeine Phosphate"))
        val hit = index.findMedicine(scan.text)
        assertNotNull("codeine strip did not resolve: '${scan.text}'", hit)
        assertTrue("codeine must be flagged avoid", hit!!.doc.risk == "avoid")
    }

    @Test
    fun aNonMedicineDoesNotResolveToADrug() = runBlocking {
        // Photographing a cereal box must not produce drug advice.
        val scan = scanner.scan(
            renderStrip("CORN FLAKES", "Breakfast Cereal",
                        listOf("Net weight 475 g", "Best before end 2027"))
        )
        assertNull("a cereal box resolved to a medicine: '${scan.text}'",
                   index.findMedicine(scan.text))
    }

    @Test
    fun aBlankImageYieldsNothingRatherThanAGuess() = runBlocking {
        val blank = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(blank).drawColor(Color.WHITE)
        val scan = scanner.scan(blank)
        assertNull(index.findMedicine(scan.text))
    }
}
