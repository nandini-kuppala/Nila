package com.nila.data

import com.nila.assistant.agents.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stored scan, and the two things the history row reads off it.
 *
 * Sources and cautions are stored newline-joined rather than in their own
 * tables, which is the right trade for at most a handful of short strings per
 * row -- and it puts the parsing somewhere it can go wrong, so it is pinned.
 */
class MedicineScanRecordTest {

    private fun record(
        sources: String? = null,
        cautions: String? = null,
    ) = MedicineScanRecord(
        atMs = 0L,
        name = "paracetamol",
        verdict = Verdict.LIKELY_SAFE.name,
        headline = "Generally considered compatible with breastfeeding",
        summary = "Amounts in breast milk are far below the infant dose.",
        sources = sources,
        cautions = cautions,
    )

    @Test
    fun `lists survive a round trip`() {
        val r = record(sources = "NHS\nLactMed", cautions = "Confirm with your doctor")
        assertEquals(listOf("NHS", "LactMed"), r.sourceList())
        assertEquals(listOf("Confirm with your doctor"), r.cautionList())
    }

    @Test
    fun `an absent field is an empty list, not a crash`() {
        val r = record()
        assertEquals(emptyList<String>(), r.sourceList())
        assertEquals(emptyList<String>(), r.cautionList())
    }

    @Test
    fun `blank entries are dropped rather than shown as empty rows`() {
        val r = record(sources = "NHS\n\n\nLactMed\n")
        assertEquals(listOf("NHS", "LactMed"), r.sourceList())
    }

    /**
     * The verdict is stored as the enum name, not the label.
     *
     * Labels are user-facing text and get reworded. Storing one would mean a
     * copy edit silently changed what a past scan appears to have said, which
     * for an AVOID is the worst kind of drift.
     */
    @Test
    fun `every verdict name round trips`() {
        Verdict.entries.forEach { verdict ->
            val stored = record().copy(verdict = verdict.name)
            assertEquals(verdict, Verdict.valueOf(stored.verdict))
        }
    }

    @Test
    fun `an unrecognised stored verdict does not crash the history row`() {
        // What the screen does: a row written by a future version, or a
        // corrupted one, degrades to "not enough information" rather than
        // taking the list down with it.
        val parsed = runCatching { Verdict.valueOf("PROBABLY_FINE_LOL") }
            .getOrDefault(Verdict.UNKNOWN)
        assertEquals(Verdict.UNKNOWN, parsed)
    }

    @Test
    fun `labels are distinct so a history list is readable`() {
        val labels = Verdict.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.none { it.isBlank() })
    }
}
