package com.nila.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the strings the app shows about its own reliability.
 *
 * The unresolved-specifier check exists because this bug already happened: in
 * Kotlin, `.format()` binds to the last string literal in a `+` chain, so a
 * multi-line message renders its earlier "%.2f" placeholders as literal text.
 * It compiles, it does not throw, and it only shows up when someone looks at
 * the screen.
 */
class ModelCardTest {

    private val unresolved = Regex("""%[-+ 0,(#]*\d*(\.\d+)?[a-zA-Z]""")

    @Test
    fun `honest summary has no unresolved format specifiers`() {
        val text = ModelCard.honestSummary
        assertFalse(
            "honestSummary still contains a format specifier: $text",
            unresolved.containsMatchIn(text),
        )
        assertTrue("honestSummary looks empty", text.length > 40)
    }

    @Test
    fun `honest summary reports the measured number`() {
        // Whatever the model scored, the sentence must contain it -- the app is
        // not allowed to describe its reliability without quoting it.
        val auc = "%.2f".format(ModelCard.REASON_SUBJECT_WISE_AUC)
        assertTrue(
            "expected '$auc' in: ${ModelCard.honestSummary}",
            ModelCard.honestSummary.contains(auc),
        )
    }

    @Test
    fun `trustworthiness matches the measured AUC`() {
        // The flag the UI keys off must agree with the number it was derived
        // from, or the app could hide a caveat it still needs.
        val beatsChance = ModelCard.REASON_SUBJECT_WISE_AUC >= 0.60f
        assertTrue(
            "REASON_TRUSTWORTHY=${ModelCard.REASON_TRUSTWORTHY} but AUC is " +
                "${ModelCard.REASON_SUBJECT_WISE_AUC}",
            ModelCard.REASON_TRUSTWORTHY == beatsChance,
        )
    }

    @Test
    fun `detection metrics are in range and better than chance`() {
        assertTrue(ModelCard.DETECT_SUBJECT_WISE_AUC in 0f..1f)
        assertTrue(
            "detector should comfortably beat chance",
            ModelCard.DETECT_SUBJECT_WISE_AUC > 0.80f,
        )
        assertTrue(ModelCard.DETECT_RECALL_CRY in 0f..1f)
        assertTrue(ModelCard.DETECT_TEST_CLIPS > 100)
    }

    @Test
    fun `leakage inflation is the difference it claims to be`() {
        val expected = ModelCard.REASON_LEAKY_AUC - ModelCard.REASON_SUBJECT_WISE_AUC
        assertTrue(
            "LEAKAGE_AUC_INFLATION=${ModelCard.LEAKAGE_AUC_INFLATION} " +
                "but leaky-honest=$expected",
            kotlin.math.abs(ModelCard.LEAKAGE_AUC_INFLATION - expected) < 1e-3f,
        )
    }
}
