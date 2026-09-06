package com.nila

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.assistant.Assistant
import com.nila.assistant.KnowledgeIndex
import com.nila.assistant.agents.MedicineReviewPipeline
import com.nila.data.NilaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Answers arrive immediately.
 *
 * This exists because the same mistake shipped twice: first the medicine review
 * blocked on generation, then -- after that was fixed -- the Ask screen was
 * found doing exactly the same thing. On modest hardware a 0.5B model took tens
 * of seconds for its first call, which turned a working feature into a hung
 * screen with a spinner.
 *
 * There is no model in the app any more, so the budget below is now enormous
 * headroom rather than a race. The test stays because the rule it encodes --
 * retrieval produces a complete, sourced answer and returns it -- is what
 * anything reintroducing generation would break, and it would break here rather
 * than in front of a user.
 */
@RunWith(AndroidJUnit4::class)
class ResponsivenessTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Generous, deliberately.
     *
     * The point is not to measure retrieval speed -- it is microseconds. It is
     * to be far below the seconds-to-minutes a blocking model call costs, so the
     * test fails unambiguously if one is reintroduced.
     */
    private val budgetMs = 3_000L

    @Test
    fun askReturnsWithoutWaitingOnTheModel() = runBlocking {
        val db = NilaDatabase.get(context)
        val index = KnowledgeIndex.load(context)
        val assistant = Assistant(context, db, index)

        run {
            listOf(
                "How much crying is normal?",
                "Is paracetamol safe while breastfeeding?",
                "What foods should I avoid before one year?",
            ).forEach { question ->
                var answer: Assistant.Answer? = null
                val elapsed = measureTimeMillis { answer = assistant.ask(question) }
                assertNotNull("no answer for '$question'", answer)
                assertTrue("empty answer for '$question'", answer!!.text.length > 20)
                assertTrue(
                    "'$question' took ${elapsed}ms - something is blocking on the " +
                        "language model again",
                    elapsed < budgetMs,
                )
            }
        }
    }

    @Test
    fun theMedicineVerdictReturnsWithoutWaitingOnTheModel() = runBlocking {
        val db = NilaDatabase.get(context)
        val index = KnowledgeIndex.load(context)
        val pipeline = MedicineReviewPipeline(index, db.healthRecords())

        run {
            val elapsed = measureTimeMillis {
                val result = pipeline.review("ibuprofen 400", null, "en")
                assertNotNull("no verdict", result.verdict)
                assertTrue("no summary", result.summary.length > 20)
                assertTrue("expected the full agent trace", result.steps.size >= 4)
                assertTrue(
                    "the verdict must be rule-based, not generated",
                    !result.summaryFromLlm,
                )
            }
            assertTrue(
                "the verdict took ${elapsed}ms - it is blocking on the model again",
                elapsed < budgetMs,
            )
        }
    }

    @Test
    fun aVerdictNeverRepeatsItsOwnRiskLabel() = runBlocking {
        // The headline already states whether it is safe; the detail repeating
        // that verbatim reads as a stutter and was a real defect.
        val db = NilaDatabase.get(context)
        val index = KnowledgeIndex.load(context)
        val pipeline = MedicineReviewPipeline(index, db.healthRecords())

        listOf("ibuprofen", "paracetamol", "codeine").forEach { name ->
            val summary = pipeline.review(name, null, "en").summary
            val opening = summary.substringBefore(". ").trim()
            val rest = summary.removePrefix(opening).trim()
            assertTrue(
                "'$name' repeats its opening clause: $summary",
                !rest.contains(opening, ignoreCase = true),
            )
        }
    }
}
