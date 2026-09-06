package com.nila.assistant

import com.nila.assistant.agents.HistoryAgent
import com.nila.data.HealthRecord
import com.nila.data.RecordCategory
import com.nila.data.RecordSubject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading conditions out of scanned documents, without inventing them.
 *
 * This exists because of a real defect: a mother whose records contained an
 * Edinburgh Postnatal Depression Scale -- scored *below* the referral threshold
 * -- was reported to have depression, because a keyword scan saw the word in the
 * form's own title. Telling a clinician someone has a condition a document
 * explicitly excluded is worse than missing it entirely, and it is the failure
 * mode every naive text scan of medical records has.
 */
class HistoryMiningTest {

    private fun record(
        title: String,
        text: String,
        category: RecordCategory = RecordCategory.LAB_REPORT,
    ) = HealthRecord(
        subject = RecordSubject.MOTHER.name,
        category = category.name,
        title = title,
        notes = "",
        extractedText = text,
        recordedAtMs = 0L,
    )

    @Test
    fun `a screening scored below threshold is not a diagnosis`() {
        val records = listOf(
            record(
                "Mood screening",
                "EDINBURGH POSTNATAL DEPRESSION SCALE Total score: 7 of 30 " +
                    "Item 10 scored 0 Below referral threshold Rescreen at next visit",
                RecordCategory.MENTAL_HEALTH,
            )
        )
        val mined = HistoryAgent.mineConditions(records, emptyList())
        assertFalse("a passed screening became a diagnosis: $mined",
                    mined.contains("depression"))
    }

    @Test
    fun `a negative result is not a diagnosis`() {
        val records = listOf(
            record("Screen", "Gestational diabetes screening: negative. " +
                "No evidence of diabetes."),
            record("Renal", "Kidney disease ruled out. Creatinine within normal limits."),
        )
        val mined = HistoryAgent.mineConditions(records, emptyList())
        assertFalse("negative screen became a diagnosis: $mined",
                    mined.contains("diabetes"))
        assertFalse("excluded condition became a diagnosis: $mined",
                    mined.contains("kidney disease"))
    }

    @Test
    fun `a condition actually recorded is found`() {
        // The feature has to still work: the whole point is catching what she
        // did not think to type in.
        val records = listOf(
            record(
                "Prescription",
                "Salbutamol inhaler as required for asthma. " +
                    "Allergy noted: PENICILLIN - do not prescribe.",
                RecordCategory.PRESCRIPTION,
            )
        )
        val mined = HistoryAgent.mineConditions(records, emptyList())
        assertTrue("asthma on a prescription was missed: $mined",
                   mined.contains("asthma"))
    }

    @Test
    fun `terms already declared are not repeated`() {
        val records = listOf(record("Note", "Known asthma, well controlled."))
        val mined = HistoryAgent.mineConditions(records, listOf("asthma"))
        assertTrue("declared condition was echoed back: $mined", mined.isEmpty())
    }

    @Test
    fun `a broader term is dropped when a more specific one is present`() {
        // "anaemia" beside "iron deficiency anaemia" is padding, and padding in
        // a safety finding makes it look less trustworthy, not more.
        val records = listOf(
            record("Labs", "Impression: iron deficiency anaemia. Ferritin 8 ng/mL. " +
                "Anaemia confirmed.")
        )
        val mined = HistoryAgent.mineConditions(records, emptyList())
        assertFalse("redundant broader term kept: $mined", mined.contains("anaemia"))
    }

    @Test
    fun `no records yields nothing rather than guesses`() {
        assertTrue(HistoryAgent.mineConditions(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `a condition mentioned twice, once negated, is still found`() {
        // A screening that ruled it out last year and a diagnosis this year must
        // resolve to "she has it" -- the scan keeps looking past a negated hit.
        val records = listOf(
            record("Old screen", "Asthma screening: negative."),
            record("Clinic note", "Diagnosed with asthma, started on a reliever inhaler."),
        )
        val mined = HistoryAgent.mineConditions(records, emptyList())
        assertTrue("a later diagnosis was masked by an earlier negative: $mined",
                   mined.contains("asthma"))
    }
}
