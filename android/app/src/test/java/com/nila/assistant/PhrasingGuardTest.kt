package com.nila.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checks that let a language model back into the answer path.
 *
 * It was removed after producing, from a record stating a newborn hearing
 * screening was passed:
 *
 *   "The baby has not yet passed the newborn hearing screening, which is a
 *    sign of potential hearing issues."
 *
 * Two things changed before it was allowed back. It is no longer shown health
 * records at all -- it only ever sees corpus passages -- and the grounding
 * check now looks at polarity, not just vocabulary. The old check passed that
 * sentence because every word in it came from the source.
 *
 * These run without a device or a model: [Grounding] is pure.
 */
class PhrasingGuardTest {

    @Test
    fun theSentenceThatRemovedTheFeatureIsRejected() {
        val source = "Newborn hearing screening: passed, both ears. " +
            "Routine review, no concerns raised."
        val generated = "The baby has not passed the newborn hearing screening, " +
            "which is a sign of potential hearing issues."
        assertFalse(
            "the polarity flip that caused the removal is still accepted",
            Grounding.isGrounded(generated, source),
        )
    }

    @Test
    fun aFaithfulRewriteIsAccepted() {
        val source = "Crying peaks at around six to eight weeks of age and " +
            "usually eases by three to four months. Two to three hours a day " +
            "spread across the day is within the normal range for a young infant."
        val generated = "Crying usually peaks around six to eight weeks and " +
            "eases by three to four months. Two to three hours a day spread " +
            "across the day is within the normal range."
        assertTrue(Grounding.isGrounded(generated, source))
    }

    @Test
    fun aNegationInvertedTheOtherWayIsAlsoRejected() {
        val source = "Spicy food, garlic and vegetables like cabbage do not " +
            "need avoiding while breastfeeding."
        val generated = "Spicy food, garlic and vegetables like cabbage need " +
            "avoiding while breastfeeding."
        assertFalse(
            "dropping a negation is the same defect in the other direction",
            Grounding.isGrounded(generated, source),
        )
    }

    @Test
    fun anInventedQuantityIsRejected() {
        val source = "Feed on demand. Most newborns feed frequently through " +
            "the day and night."
        val generated = "Feed on demand, usually every 90 minutes for the " +
            "first 6 weeks."
        assertFalse(Grounding.isGrounded(generated, source))
    }

    @Test
    fun aQuantityCopiedFromTheSourceIsFine() {
        val source = "You need roughly 300-500 extra calories a day while " +
            "breastfeeding, and caffeine is fine in moderation."
        val generated = "You need about 300-500 extra calories a day. " +
            "Caffeine in moderation is fine while breastfeeding."
        assertTrue(Grounding.isGrounded(generated, source))
    }

    @Test
    fun driftingOffTheSourceEntirelyIsRejected() {
        val source = "Tummy time strengthens the neck and shoulders and helps " +
            "prevent a flat spot on the head."
        val generated = "Babies enjoy plenty of interaction with parents, and " +
            "singing together builds a wonderful lifelong bond between you."
        assertFalse(Grounding.isGrounded(generated, source))
    }
}
