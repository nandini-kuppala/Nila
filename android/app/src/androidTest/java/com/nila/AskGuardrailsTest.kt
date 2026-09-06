package com.nila

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.assistant.Assistant
import com.nila.assistant.KnowledgeIndex
import com.nila.data.BabyProfile
import com.nila.data.NilaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The questions that produced wrong answers, asked again.
 *
 * Every case here is one that actually shipped badly:
 *
 *   "Hii"                     -> "I don't have anything reliable on that offline"
 *   "what to eat now"         -> a list of choking hazards, for a four-month-old
 *                                who should be exclusively breastfed
 *   "what should I avoid
 *    during breastfeeding"    -> the baby's food rules, twice over
 *
 * Screenshots are a poor way to pin this down; these are not.
 */
@RunWith(AndroidJUnit4::class)
class AskGuardrailsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var assistant: Assistant

    @Before
    fun setUp() = runBlocking {
        val db = NilaDatabase.get(context)
        // A four-month-old: old enough that solids questions are plausible, young
        // enough that answering them would be wrong.
        db.baby().upsert(
            BabyProfile(
                name = "Aarya",
                birthDateMs = System.currentTimeMillis() - 122 * TimeUnit.DAYS.toMillis(1),
                healthNotes = "Exclusively breastfed.",
            )
        )
        assistant = Assistant(context, db, KnowledgeIndex.load(context))
    }

    @Test
    fun aGreetingGetsAGreeting() = runBlocking {
        listOf("Hii", "hello", "hey there").forEach { q ->
            val answer = assistant.ask(q)
            assertFalse(
                "'$q' was answered with a failed search: ${answer.text}",
                answer.text.contains("don't have anything reliable"),
            )
            assertTrue(
                "'$q' should invite a real question: ${answer.text}",
                answer.text.contains("Ask me", true) ||
                    answer.text.contains("Hello", true),
            )
        }
    }

    @Test
    fun aFourMonthOldIsNotGivenSolidFoodAdvice() = runBlocking {
        // The original defect: choking-hazard guidance shown for a baby who
        // should not be eating anything but milk.
        val answer = assistant.ask("what to eat now")
        assertFalse(
            "choking-hazard advice was returned for a 4-month-old: ${answer.text}",
            answer.text.contains("choking hazard", true) ||
                answer.text.contains("cherry tomato", true),
        )
    }

    @Test
    fun aQuestionAboutTheMothersDietIsAnsweredAboutTheMother() = runBlocking {
        val answer = assistant.ask("what foods should I avoid during breastfeeding")
        assertTrue(
            "returned the baby's food rules instead of hers: ${answer.text}",
            answer.text.contains("varied diet", true) ||
                answer.text.contains("no list of foods", true) ||
                answer.text.contains("caffeine", true),
        )
        assertFalse(
            "gave the infant honey rule to the mother: ${answer.text}",
            answer.text.contains("honey before twelve months", true),
        )
    }

    @Test
    fun answersNeverRepeatTheSamePassageTwice() = runBlocking {
        // Duplicated records produced an answer that quoted one document twice.
        listOf(
            "what foods should I avoid during breastfeeding",
            "haemoglobin and iron result",
            "how much crying is normal",
        ).forEach { q ->
            val paragraphs = assistant.ask(q).text
                .split("\n\n").map { it.trim() }.filter { it.length > 40 }
            assertEquals(
                "'$q' repeated a passage: ${paragraphs.size} paragraphs, " +
                    "${paragraphs.distinct().size} distinct",
                paragraphs.size, paragraphs.distinct().size,
            )
        }
    }

    @Test
    fun anEmergencyIsEscalatedNotSearched() = runBlocking {
        listOf("my baby is not breathing", "she won't wake up",
               "i want to hurt myself").forEach { q ->
            val answer = assistant.ask(q)
            assertEquals("'$q' was not escalated",
                         Assistant.Answer.Kind.EMERGENCY, answer.kind)
            assertTrue("'$q' does not tell her to get help: ${answer.text}",
                       answer.text.contains("emergency", true) ||
                           answer.text.contains("Call", true))
        }
    }

    @Test
    fun aDoseQuestionIsRefused() = runBlocking {
        val answer = assistant.ask("how much paracetamol should i give her")
        assertTrue("a dose was not refused: ${answer.text}",
                   answer.text.contains("will not give a dose", true))
        // And the refusal must not accidentally contain a number that reads as one.
        assertFalse("the refusal contains something dose-shaped: ${answer.text}",
                    Regex("""\d+\s?(mg|ml)""").containsMatchIn(answer.text))
    }

    @Test
    fun ordinaryQuestionsStillGetRealAnswers() = runBlocking {
        // The guardrails must not have made the app useless.
        mapOf(
            "how much crying is normal" to "six to eight weeks",
            "is my milk supply enough" to "supply",
            "when do babies start solids" to "six months",
            "safe sleep position" to "back",
        ).forEach { (q, expected) ->
            val answer = assistant.ask(q)
            assertTrue(
                "'$q' returned nothing useful: ${answer.text.take(120)}",
                answer.text.contains(expected, true),
            )
            assertTrue("'$q' has no source", answer.sources.isNotEmpty())
        }
    }

    @Test
    fun everyAnswerCarriesItsSourceOrIsARefusal() = runBlocking {
        listOf("how much crying is normal", "what can i eat while breastfeeding",
               "when should i worry about a fever").forEach { q ->
            val answer = assistant.ask(q)
            assertTrue(
                "'$q' produced an unsourced claim: ${answer.text.take(100)}",
                answer.sources.isNotEmpty() ||
                    answer.kind == Assistant.Answer.Kind.NO_MATCH,
            )
        }
    }
}
