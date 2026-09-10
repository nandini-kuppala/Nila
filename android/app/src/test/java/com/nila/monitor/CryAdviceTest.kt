package com.nila.monitor

import com.nila.assistant.Corpus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advice shown at the ninety-second verdict, checked against the corpus
 * that actually ships.
 *
 * Two things are being defended. Every class the classifier can emit must have
 * something to say, or a real episode reaches its most important moment with an
 * empty card. And nothing here may read as a diagnosis -- the classifier scored
 * 0.444 subject-wise macro AUC, and the whole failure mode of this product
 * category is a confident label with nothing behind it.
 */
class CryAdviceTest {

    private fun advice(label: String) =
        CryAdvice.forLabel(label) { id -> Corpus.index.byId(id) }

    /** The forest's exported class names. */
    private val modelLabels =
        listOf("belly_pain", "burping", "discomfort", "hungry", "tired")

    @Test
    fun `every class the model can emit has advice`() {
        modelLabels.forEach { label ->
            assertNotNull("no advice for '$label'", advice(label))
        }
    }

    @Test
    fun `the mapping covers exactly the model's classes`() {
        assertEquals(modelLabels.toSet(), CryAdvice.labels)
    }

    @Test
    fun `every headline hedges`() {
        modelLabels.forEach { label ->
            val headline = advice(label)!!.headline
            assertTrue(
                "'$headline' states a cause as fact",
                headline.contains("probably", ignoreCase = true),
            )
        }
    }

    @Test
    fun `every entry offers something concrete to do`() {
        modelLabels.forEach { label ->
            val steps = advice(label)!!.steps
            assertTrue("'$label' has no steps", steps.size >= 2)
            assertTrue("'$label' has a blank step", steps.none { it.isBlank() })
        }
    }

    /**
     * The guidance is the corpus entry, character for character. Nothing
     * rewrites it, shortens it or generates around it -- the same rule that
     * took the language model off the answer path after it inverted a hearing
     * screening result.
     */
    @Test
    fun `guidance is the corpus text verbatim`() {
        modelLabels.forEach { label ->
            val a = advice(label)!!
            val matching = Corpus.docs.filter { it.text == a.guidance }
            assertTrue(
                "guidance for '$label' is not any corpus entry's text",
                matching.isNotEmpty(),
            )
        }
    }

    @Test
    fun `every entry names its source`() {
        modelLabels.forEach { label ->
            assertTrue("'$label' has no source", advice(label)!!.source.isNotBlank())
        }
    }

    /**
     * The two causes with a red flag behind them have to carry it. A pain cry
     * that is actually an obstruction, and overheating, are the two places
     * where following the soothing advice and nothing else could do harm.
     */
    @Test
    fun `belly pain and discomfort carry their cautions`() {
        assertTrue(advice("belly_pain")!!.caution.isNotBlank())
        assertTrue(advice("discomfort")!!.caution.isNotBlank())
    }

    @Test
    fun `an unknown label gets nothing rather than something generic`() {
        assertNull(advice("teething"))
        assertNull(advice(""))
    }

    /**
     * These entries answer a classifier, not a person, and they are worded that
     * way -- which makes them strong keyword matches for questions they are the
     * wrong answer to. Keeping them out of general retrieval is what stopped
     * "baby is very sleepy and not feeding" returning tiredness advice instead
     * of the red-flags entry.
     */
    @Test
    fun `cause entries never win a typed question`() {
        val queries = listOf(
            "she cries after every feed",
            "baby is very sleepy and not feeding",
            "why is my baby crying",
            "how do i settle my baby",
            "my baby has a fever",
        )
        queries.forEach { q ->
            val hits = Corpus.index.search(q, limit = 5)
            assertTrue(
                "'$q' returned a lookup-only entry: ${hits.map { it.doc.id }}",
                hits.none { it.doc.collection == "causes" },
            )
        }
    }

    @Test
    fun `cause entries are still reachable when asked for by collection`() {
        val hits = Corpus.index.search("trapped wind", limit = 5, collection = "causes")
        assertTrue("expected the causes collection to be searchable on request",
                   hits.isNotEmpty())
        assertTrue(hits.all { it.doc.collection == "causes" })
    }
}
