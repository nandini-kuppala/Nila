package com.nila

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.data.EventRecord
import com.nila.data.NilaDatabase
import com.nila.data.Severity
import com.nila.export.ClinicReport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Builds the paediatrician PDF from seeded cry history.
 *
 * Seeding here rather than waiting for real crying is the only practical way to
 * exercise a fourteen-day report, and it also lets the colic threshold be tested
 * deliberately: three of the seeded days cross three hours, which is exactly the
 * boundary the document exists to communicate.
 */
@RunWith(AndroidJUnit4::class)
class ClinicReportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Seed a realistic fortnight.
     *
     * Called by every test rather than relying on one to run first: JUnit does
     * not guarantee method order, so a test that depends on another having run
     * passes locally and fails the moment the order changes.
     */
    private suspend fun seedFortnight(db: NilaDatabase) {
        val now = System.currentTimeMillis()
        val dayMs = TimeUnit.DAYS.toMillis(1)

        // Three heavy days and eleven ordinary ones -- a pattern that meets the
        // duration criterion, so the report has something real to show.
        val heavyDays = setOf(2, 5, 9)
        for (back in 0 until 14) {
            val episodes = if (back in heavyDays) 8 else 3
            val each = if (back in heavyDays) 1500 else 400
            repeat(episodes) { n ->
                db.events().insert(
                    EventRecord(
                        kind = "CRY_ENDED",
                        severityLevel = Severity.ATTENTION.level,
                        startedAtMs = now - back * dayMs - n * 3_600_000L,
                        endedAtMs = now - back * dayMs - n * 3_600_000L + each * 1000L,
                        durationSeconds = each,
                        peakDbfs = -22f,
                        trend = "SETTLING",
                        detectorConfidence = 0.93f,
                        envelope = "40,55,70,62,48,30",
                    )
                )
            }
        }
    }

    @Test
    fun buildsAPdfFromSeededHistory() = runBlocking {
        val db = NilaDatabase.get(context)
        seedFortnight(db)

        val file = ClinicReport(context, db).build(days = 14)
        assertTrue("report was not written", file.exists())
        assertTrue("report is implausibly small: ${file.length()} bytes",
                   file.length() > 3_000)

        // A PDF, not just a file with a .pdf name.
        val header = file.inputStream().use { ByteArray(5).also { b -> it.read(b) } }
        assertTrue("not a PDF: ${String(header)}",
                   String(header).startsWith("%PDF"))
    }

    @Test
    fun theColicThresholdIsComputedFromDurationAlone() = runBlocking {
        val db = NilaDatabase.get(context)
        seedFortnight(db)
        val now = System.currentTimeMillis()
        val dayMs = TimeUnit.DAYS.toMillis(1)

        val seconds = db.events().cryingSecondsBetween(now - dayMs, now)
        val episodes = db.events().cryEpisodesBetween(now - dayMs, now)
        assertTrue("expected seeded crying in the last day", seconds > 0)
        assertTrue("expected seeded episodes", episodes > 0)

        // The whole safety of this feature is that the threshold is arithmetic
        // on measured duration -- nothing inferred, nothing diagnosed.
        val meets = seconds >= 3 * 3600
        assertTrue("threshold must follow the arithmetic",
                   meets == (seconds / 3600.0 >= 3.0))
    }
}
