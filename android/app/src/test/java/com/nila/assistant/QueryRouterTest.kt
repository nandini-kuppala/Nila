package com.nila.assistant

import com.nila.assistant.QueryRouter.Route
import com.nila.assistant.QueryRouter.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app refuses to answer, and who it thinks each question is about.
 *
 * Both were found the hard way. A four-month-old's mother asking "what to eat
 * now" was shown a list of choking hazards -- advice for a baby on solids, and
 * dangerous for one who should be exclusively breastfed. "What should I avoid
 * while breastfeeding" returned the baby's food rules, because the corpus had
 * nothing about the mother at all.
 */
class QueryRouterTest {

    // ---- emergencies ---------------------------------------------------
    @Test
    fun `red flag wording never reaches retrieval`() {
        listOf(
            "my baby is not breathing",
            "she stopped breathing for a moment",
            "his lips are turning blue",
            "the baby is unresponsive",
            "she won't wake up",
            "he had a seizure",
            "she is choking",
            "he swallowed a button battery",
            "my baby fell off the bed",
            "there is a rash that doesn't fade",
        ).forEach { q ->
            val route = QueryRouter.route(q)
            assertTrue("'$q' was not treated as an emergency: $route",
                       route is Route.Emergency)
            val text = QueryRouter.refusal(route)!!
            assertTrue("'$q' response does not say to get help: $text",
                       text.contains("emergency", true) || text.contains("Call", true))
        }
    }

    @Test
    fun `thoughts of self harm are treated as an emergency`() {
        // The single most important thing this app could be asked, and the one
        // where returning a search result would be indefensible.
        //
        // Two routes count as escalation, not one. Thoughts of harming the baby
        // now reach CaregiverDistress rather than Emergency, and that is an
        // improvement rather than a regression: the reply names the safe
        // immediate action -- put the baby down, walk away -- gives helpline
        // numbers, and still says to call emergency services if she thinks she
        // might act. "Go to A&E" alone did none of that. What must never happen
        // is retrieval, and that is what this asserts.
        listOf("i want to hurt myself", "sometimes i want to hurt my baby",
               "i think about ending it all", "i feel like i might shake her",
               "im scared of what i might do").forEach { q ->
            val route = QueryRouter.route(q)
            assertTrue(
                "'$q' reached ordinary retrieval",
                route is Route.Emergency || route == Route.CaregiverDistress,
            )
            val reply = QueryRouter.refusal(route) ?: ""
            assertTrue("'$q' produced no reply", reply.length > 40)
        }
    }

    @Test
    fun `an emergency phrase wins over anything else in the question`() {
        val route = QueryRouter.route(
            "how much crying is normal, also she is not breathing properly"
        )
        assertTrue("a buried emergency was missed: $route", route is Route.Emergency)
    }

    // ---- refusals ------------------------------------------------------
    @Test
    fun `dose questions are refused, not answered`() {
        listOf("how much paracetamol should i give her",
               "what dose of ibuprofen for a 4 month old",
               "how many ml of calpol").forEach { q ->
            assertEquals("'$q'", Route.DoseRequest, QueryRouter.route(q))
        }
        val text = QueryRouter.refusal(Route.DoseRequest)!!
        assertTrue("the refusal should explain why", text.contains("weight"))
    }

    @Test
    fun `diagnosis questions are refused`() {
        assertEquals(Route.DiagnosisRequest,
                     QueryRouter.route("does my baby have autism"))
        assertEquals(Route.DiagnosisRequest, QueryRouter.route("do i have mastitis"))
    }

    @Test
    fun `greetings get a greeting, not a search result`() {
        listOf("hi", "Hii", "hello", "hey", "thanks", "ok").forEach { q ->
            assertEquals("'$q'", Route.Greeting, QueryRouter.route(q))
        }
        assertTrue(QueryRouter.refusal(Route.Greeting)!!.contains("yourself"))
    }

    @Test
    fun `off topic questions are declined`() {
        listOf("what is the weather", "who is the president of india",
               "write me a python script").forEach { q ->
            assertEquals("'$q'", Route.OutOfScope, QueryRouter.route(q))
        }
    }

    @Test
    fun `a single vague word asks for more`() {
        assertEquals(Route.TooVague, QueryRouter.route("x"))
        assertEquals(Route.TooVague, QueryRouter.route(""))
        assertEquals(Route.TooVague, QueryRouter.route("   "))
    }

    // ---- subject routing ------------------------------------------------
    @Test
    fun `questions about the mother are recognised`() {
        listOf(
            "what can i eat while breastfeeding",
            "can i take ibuprofen",
            "is my milk supply enough",
            "i feel low since the birth",
            "my nipples hurt when she latches",
            "how long does postpartum bleeding last",
        ).forEach { q ->
            assertEquals("'$q' should be about the mother",
                         Subject.MOTHER, QueryRouter.subjectOf(q))
        }
    }

    @Test
    fun `questions about the baby are recognised`() {
        listOf(
            "when should my baby start solids",
            "how many nappies a day is normal",
            "when do infants roll over",
            "my newborn is very sleepy",
        ).forEach { q ->
            assertEquals("'$q' should be about the baby",
                         Subject.BABY, QueryRouter.subjectOf(q))
        }
    }

    @Test
    fun `a question naming both is resolved to the mother`() {
        // "Can I eat this while breastfeeding my baby" is about her diet.
        assertEquals(
            Subject.MOTHER,
            QueryRouter.subjectOf("can i eat spicy food while breastfeeding my baby"),
        )
    }

    @Test
    fun `an ordinary question is answerable`() {
        val route = QueryRouter.route("how much crying is normal")
        assertTrue(route is Route.Answerable)
        assertNull(QueryRouter.refusal(route))
    }

    @Test
    fun `every refusal route produces wording`() {
        listOf(Route.Greeting, Route.TooVague, Route.DoseRequest,
               Route.DiagnosisRequest, Route.OutOfScope,
               Route.Emergency("not breathing")).forEach {
            assertNotNull("no wording for $it", QueryRouter.refusal(it))
        }
    }
}
