package com.nila

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.assistant.Assistant
import com.nila.assistant.KnowledgeIndex
import com.nila.assistant.agents.MedicineReviewPipeline
import com.nila.data.NilaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What an answer is allowed to be.
 *
 * The model is back, phrasing retrieved passages -- so "every answer is
 * verbatim corpus text" is no longer the rule. Three rules replace it, and each
 * one is a bug this app has actually had:
 *
 *  * A medicine verdict is never generated. A 0.5B model softened a do-not-take
 *    for a penicillin-allergic mother, and nothing has changed about how likely
 *    that is to happen again.
 *  * Every guidance answer names its sources, phrased or not, so the reader can
 *    check what it was built from.
 *  * The answer arrives from retrieval first. If phrasing is slow or fails, the
 *    retrieved text is what the user sees.
 */
@RunWith(AndroidJUnit4::class)
class AnswerIntegrityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assistant() = Assistant(
        context, NilaDatabase.get(context), KnowledgeIndex.load(context),
    )

    @Test
    fun medicineVerdictsAreNeverGenerated() = runBlocking {
        val pipeline = MedicineReviewPipeline(
            KnowledgeIndex.load(context), NilaDatabase.get(context).healthRecords(),
        )
        listOf("amoxicillin", "codeine", "ibuprofen").forEach { name ->
            val result = pipeline.review(name, null, "en")
            assertFalse("$name verdict was generated", result.summaryFromLlm)
            assertTrue("$name has no verdict", result.summary.length > 20)
        }
    }

    @Test
    fun everyGuidanceAnswerNamesItsSources() = runBlocking {
        val assistant = assistant()
        listOf(
            "how much crying is normal",
            "what should i eat while breastfeeding",
            "when can i start solid food",
        ).forEach { question ->
            val answer = assistant.ask(question)
            assertTrue(
                "'$question' produced no source",
                answer.sources.isNotEmpty(),
            )
            assertTrue(
                "'$question' kept no passages for phrasing",
                answer.passages.isNotEmpty(),
            )
        }
    }

    /**
     * The passages handed to the model are corpus text and nothing else.
     *
     * This is the structural half of the fix that let generation back in: the
     * hallucination that removed it was built out of a health record, and a
     * model that is never shown one cannot invert one.
     */
    @Test
    fun onlyCorpusTextIsEverHandedToTheModel() = runBlocking {
        val index = KnowledgeIndex.load(context)
        val answer = assistant().ask("i feel low since the birth")
        val corpus = index.allDocs.map { it.text }.toSet()
        answer.passages.forEach {
            assertTrue(
                "a passage handed to the model is not corpus text: ${it.take(70)}",
                it in corpus,
            )
        }
    }

    @Test
    fun anAnswerArrivesWithoutWaitingForPhrasing() = runBlocking {
        val assistant = assistant()
        val started = System.currentTimeMillis()
        val answer = assistant.ask("how much crying is normal")
        val elapsed = System.currentTimeMillis() - started
        assertTrue("empty answer", answer.text.length > 20)
        assertTrue(
            "retrieval took ${elapsed}ms - something is blocking on the model",
            elapsed < 3_000,
        )
        assertFalse("ask() phrased inline instead of returning first",
                    answer.refinedByLlm)
    }
}
