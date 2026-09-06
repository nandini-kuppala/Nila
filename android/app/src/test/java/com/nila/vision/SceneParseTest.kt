package com.nila.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the vision model's answer back.
 *
 * This is the half of the design that makes a 0.5B model pointed at a cot
 * defensible: the prompt asks for a form with closed vocabularies, and anything
 * outside them is discarded rather than shown. So the parser has to be strict
 * about the fields and completely uninterested in the prose around them.
 *
 * The replies below are the shapes a small model actually produces -- extra
 * commentary, markdown bold, a refusal, an empty answer.
 */
class SceneParseTest {

    private fun parse(reply: String) = SceneDescriber.parse(reply, 100L)

    @Test
    fun readsTheFormItAskedFor() {
        val seen = parse(
            """
            POSITION: on back
            FACE: clear
            CONCERN: none
            DOING: The baby is lying still with both arms above their head.
            """.trimIndent()
        )!!
        assertEquals(SceneDescriber.Position.ON_BACK, seen.position)
        assertEquals(SceneDescriber.Face.CLEAR, seen.face)
        assertEquals(SceneDescriber.Concern.NONE, seen.concern)
        assertTrue(seen.doing.startsWith("The baby is lying still"))
        assertFalse(seen.isConcerning)
    }

    @Test
    fun flagsACoveredFace() {
        val seen = parse(
            """
            POSITION: on front
            FACE: covered
            CONCERN: face covered
            DOING: A blanket is over the baby's head.
            """.trimIndent()
        )!!
        assertEquals(SceneDescriber.Position.ON_FRONT, seen.position)
        assertTrue(seen.isConcerning)
        assertTrue(seen.headline.contains("face", ignoreCase = true))
    }

    /** Small models pad the form with commentary. The fields still have to read. */
    @Test
    fun toleratesCommentaryAroundTheForm() {
        val seen = parse(
            """
            Sure! Here is the completed form based on the image:

            **POSITION:** on side
            **FACE:** turned away
            **CONCERN:** none
            **DOING:** The baby appears to be sleeping peacefully.

            Let me know if you would like anything else!
            """.trimIndent()
        )!!
        assertEquals(SceneDescriber.Position.ON_SIDE, seen.position)
        assertEquals(SceneDescriber.Face.TURNED_AWAY, seen.face)
        assertFalse(seen.isConcerning)
    }

    /**
     * A reply that ignored the form told us nothing.
     *
     * Returning "not visible" here would be inventing a finding out of the
     * model's silence, and "the camera cannot see your baby" is exactly the
     * kind of false alarm that gets a monitor switched off.
     */
    @Test
    fun aReplyWithNoFormIsNotAnObservation() {
        assertNull(parse("I'm sorry, I can't help with images of children."))
        assertNull(parse(""))
        assertNull(parse("The image shows a crib in a dimly lit room."))
    }

    /** "Cannot tell" is not a concern. Uncertainty must not page a parent. */
    @Test
    fun uncertaintyDoesNotRaiseAnAlert() {
        val seen = parse(
            """
            POSITION: not visible
            FACE: not visible
            CONCERN: cannot tell
            DOING: The cot is in shadow.
            """.trimIndent()
        )!!
        assertEquals(SceneDescriber.Concern.CANNOT_TELL, seen.concern)
        assertFalse("uncertainty must not alert", seen.isConcerning)
    }

    @Test
    fun freeTextIsCappedSoItCannotFillTheScreen() {
        val seen = parse(
            "POSITION: on back\nFACE: clear\nCONCERN: none\n" +
                "DOING: " + "the baby wriggles ".repeat(40)
        )!!
        assertTrue("free text ran to ${seen.doing.length} chars", seen.doing.length <= 140)
    }
}
