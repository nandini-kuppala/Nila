package com.nila

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.assistant.TextScanner
import com.nila.data.BabyProfile
import com.nila.data.HealthRecord
import com.nila.data.MotherProfile
import com.nila.data.NilaDatabase
import com.nila.data.RecordCategory
import com.nila.data.RecordStore
import com.nila.data.RecordSubject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Populate the app with a plausible family.
 *
 * Every document is *rendered and then read back through the real on-device
 * OCR*, rather than having its text inserted directly. That matters: it
 * exercises the same path a photographed prescription takes, and it means the
 * extracted text in the database is whatever ML Kit actually managed to read --
 * including its mistakes. Seeding the text by hand would produce a demo that
 * works and a pipeline that was never tested.
 */
@RunWith(AndroidJUnit4::class)
class SeedRecordsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Doc(
        val subject: RecordSubject,
        val category: RecordCategory,
        val title: String,
        val notes: String,
        val daysAgo: Int,
        val heading: String,
        val lines: List<String>,
    )

    /**
     * A four-month-old and her mother, six months post-delivery.
     *
     * Chosen so the pieces interact: the mother's asthma and penicillin allergy
     * are stated in her profile *and* appear in a scanned prescription, so the
     * history agent can be seen finding them in a document rather than only in a
     * form. The iron deficiency and the salbutamol give the medicine checker
     * something real to cross-reference.
     */
    private val documents = listOf(
        Doc(
            RecordSubject.BABY, RecordCategory.GROWTH,
            "Well-baby visit, 4 months", "Growing along the 50th centile.", 12,
            "PAEDIATRIC WELL-BABY RECORD",
            listOf(
                "Child: Aarya  DOB: 06 May 2026  Age: 4 months",
                "Weight: 6.4 kg   (50th centile)",
                "Length: 62.1 cm  (50th centile)",
                "Head circumference: 40.8 cm",
                "Feeding: exclusively breastfed",
                "Development: social smile present, good head control",
                "Next review: 6 months",
                "Dr S Venkatesh, MD Paediatrics",
            ),
        ),
        Doc(
            RecordSubject.BABY, RecordCategory.VACCINATION,
            "UIP 14-week doses", "Mild fever for a day afterwards, settled.", 26,
            "IMMUNISATION CARD",
            listOf(
                "Child: Aarya   Age at visit: 14 weeks",
                "Pentavalent 3 (DPT-HepB-Hib)  given left thigh",
                "OPV 3   oral",
                "Rotavirus 3   oral",
                "PCV 3   given right thigh",
                "Reaction: low grade fever 24 hours, resolved",
                "Next due: Measles-Rubella at 9 months",
                "Government Primary Health Centre",
            ),
        ),
        Doc(
            RecordSubject.BABY, RecordCategory.SCREENING,
            "Newborn hearing screen", "Passed both ears at discharge.", 118,
            "NEWBORN HEARING SCREENING",
            listOf(
                "Method: Otoacoustic emissions (OAE)",
                "Right ear: PASS",
                "Left ear: PASS",
                "No risk factors identified",
                "No further audiology follow-up required",
            ),
        ),
        Doc(
            RecordSubject.BABY, RecordCategory.ILLNESS,
            "Paediatric visit, cough", "Viral, no antibiotic given.", 5,
            "CLINIC NOTE",
            listOf(
                "Presenting: cough and nasal congestion, 3 days",
                "Temperature 37.4 C, chest clear, feeding well",
                "Impression: viral upper respiratory infection",
                "Advice: saline drops, continue breastfeeding",
                "No antibiotic indicated",
                "Return if breathing becomes laboured or feeding drops",
            ),
        ),
        Doc(
            RecordSubject.MOTHER, RecordCategory.POSTNATAL,
            "Six-week postnatal check", "Cleared, advised iron studies.", 96,
            "POSTNATAL REVIEW",
            listOf(
                "Six weeks post delivery, normal vaginal delivery",
                "Blood pressure 118/76",
                "Perineal healing satisfactory",
                "Reports tiredness and breathlessness on stairs",
                "Known asthma, well controlled",
                "Plan: full blood count and ferritin",
            ),
        ),
        Doc(
            RecordSubject.MOTHER, RecordCategory.LAB_REPORT,
            "CBC and ferritin", "Iron deficiency anaemia confirmed.", 74,
            "LABORATORY REPORT",
            listOf(
                "Haemoglobin: 9.4 g/dL       (Low, ref 12.0-15.0)",
                "MCV: 74 fL                  (Low, ref 80-100)",
                "Ferritin: 8 ng/mL           (Low, ref 15-150)",
                "Impression: iron deficiency anaemia",
                "Suggest oral iron and dietary advice",
            ),
        ),
        Doc(
            RecordSubject.MOTHER, RecordCategory.PRESCRIPTION,
            "Prescription, iron and inhaler", "Repeat in 3 months.", 70,
            "PRESCRIPTION",
            listOf(
                "Ferrous ascorbate with folic acid, once daily after food",
                "Salbutamol inhaler, as required for asthma",
                "Allergy noted: PENICILLIN - do not prescribe",
                "Currently breastfeeding",
                "Review in three months with repeat haemoglobin",
                "Dr M Iyer, MBBS",
            ),
        ),
        Doc(
            RecordSubject.MOTHER, RecordCategory.LACTATION,
            "Lactation consultant", "Latch corrected, pain resolved.", 88,
            "LACTATION SUPPORT NOTE",
            listOf(
                "Reported nipple pain during feeds",
                "Observed feed: shallow latch, corrected",
                "Advised asymmetric latch and varied positions",
                "No signs of mastitis or thrush",
                "Supply adequate, good weight gain in infant",
            ),
        ),
        Doc(
            RecordSubject.MOTHER, RecordCategory.MENTAL_HEALTH,
            "Mood screening", "Low score, review at next visit.", 40,
            "EDINBURGH POSTNATAL DEPRESSION SCALE",
            listOf(
                "Total score: 7 of 30",
                "Item 10 (self-harm): scored 0",
                "Below referral threshold",
                "Reports disturbed sleep related to night feeds",
                "Rescreen at next routine visit",
            ),
        ),
    )

    /** Render a document the way a phone camera would capture a printed page. */
    private fun render(heading: String, lines: List<String>): Bitmap {
        val bitmap = Bitmap.createBitmap(1100, 760, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(252, 252, 250))

        val title = Paint().apply {
            color = Color.BLACK; textSize = 44f; isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val body = Paint().apply {
            color = Color.rgb(20, 20, 20); textSize = 34f; isAntiAlias = true
        }
        val rule = Paint().apply { color = Color.rgb(170, 170, 170); strokeWidth = 2f }

        canvas.drawText(heading, 56f, 92f, title)
        canvas.drawLine(56f, 118f, 1044f, 118f, rule)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, 56f, 186f + i * 54f, body)
        }
        return bitmap
    }

    @Test
    fun seedARealisticFamily() = runBlocking {
        val db = NilaDatabase.get(context)
        val store = RecordStore(context)
        val scanner = TextScanner(context)
        // First scan can race ML Kit's model download; warm it before seeding or
        // the earliest documents come back with no text.
        scanner.warmUp()

        val dayMs = TimeUnit.DAYS.toMillis(1)
        val now = System.currentTimeMillis()

        // Idempotent. Running this twice used to double every record, and the
        // assistant then answered by quoting the same document to you twice --
        // which reads as a broken app rather than a duplicated file.
        db.healthRecords().all().let { }
        listOf(RecordSubject.BABY, RecordSubject.MOTHER).forEach { subject ->
            db.healthRecords().listForSubject(subject.name).forEach { existing ->
                store.delete(existing.filePath)
                db.healthRecords().delete(existing)
            }
        }

        db.baby().upsert(
            BabyProfile(
                name = "Aarya",
                birthDateMs = now - 122 * dayMs,
                language = "en",
                healthNotes = "Exclusively breastfed. No known allergies. " +
                    "Passed newborn hearing screen.",
            )
        )

        db.mother().upsert(
            MotherProfile(
                name = "Nandhini",
                isBreastfeeding = true,
                deliveryDateMs = now - 122 * dayMs,
                conditions = "asthma, iron deficiency anaemia",
                allergies = "penicillin",
                currentMedicines = "ferrous ascorbate, salbutamol inhaler",
                notes = "Normal delivery. Asthma well controlled on a reliever " +
                    "inhaler only.",
            )
        )

        var withText = 0
        documents.forEach { doc ->
            val bitmap = render(doc.heading, doc.lines)
            val file = store.saveImage(bitmap, doc.category.name.lowercase())
            val text = runCatching { scanner.scan(bitmap).text }.getOrDefault("")
            bitmap.recycle()
            if (text.isNotBlank()) withText++

            db.healthRecords().insert(
                HealthRecord(
                    subject = doc.subject.name,
                    category = doc.category.name,
                    title = doc.title,
                    notes = doc.notes,
                    filePath = file.absolutePath,
                    mimeType = "image/jpeg",
                    extractedText = text,
                    recordedAtMs = now - doc.daysAgo * dayMs,
                )
            )
        }
        scanner.close()

        val baby = db.healthRecords().countFor(RecordSubject.BABY.name)
        val mother = db.healthRecords().countFor(RecordSubject.MOTHER.name)
        assertTrue("expected baby records, got $baby", baby >= 4)
        assertTrue("expected mother records, got $mother", mother >= 5)
        assertTrue(
            "OCR read only $withText of ${documents.size} documents",
            withText >= documents.size - 1,
        )

        // The point of OCR-ing at import: the medicine checker can find a
        // contraindication written on a prescription she never typed into a form.
        val penicillin = db.healthRecords()
            .search("PENICILLIN", RecordSubject.MOTHER.name, 5)
        assertTrue(
            "the penicillin allergy on the prescription was not searchable",
            penicillin.isNotEmpty(),
        )
        val asthma = db.healthRecords().search("asthma", RecordSubject.MOTHER.name, 5)
        assertTrue("asthma not searchable in her records", asthma.isNotEmpty())
    }
}
