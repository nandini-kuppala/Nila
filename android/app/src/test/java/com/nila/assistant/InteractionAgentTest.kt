package com.nila.assistant

import com.nila.assistant.agents.InteractionAgent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clinical phrase matching, tested hard because a miss here is a safety bug.
 *
 * This exists because the first version got the containment direction wrong: a
 * mother who typed "penicillin" was not matched against a corpus entry reading
 * "penicillin allergy", and the app told her amoxicillin was likely safe. The
 * fix compares meaningful terms symmetrically; these tests pin that down.
 */
class InteractionAgentTest {

    @Test
    fun `an allergy matches the corpus phrasing in either direction`() {
        assertTrue(InteractionAgent.overlaps("penicillin", "penicillin allergy"))
        assertTrue(InteractionAgent.overlaps("penicillin allergy", "penicillin"))
    }

    @Test
    fun `an allergy naming the drug itself matches`() {
        assertTrue(InteractionAgent.overlaps("amoxicillin", "Amoxicillin"))
        assertTrue(InteractionAgent.overlaps("allergic to amoxicillin", "amoxicillin"))
    }

    @Test
    fun `real-world phrasing still matches`() {
        val cases = listOf(
            "mild asthma since childhood" to "asthma",
            "type 2 diabetes" to "diabetes",
            "chronic kidney disease" to "kidney disease",
            "history of peptic ulcer" to "peptic ulcer",
            "epilepsy" to "seizure disorder is not this",   // see below
        )
        cases.dropLast(1).forEach { (typed, corpus) ->
            assertTrue("'$typed' should match '$corpus'",
                       InteractionAgent.overlaps(typed, corpus))
        }
    }

    @Test
    fun `unrelated conditions do not match`() {
        // False positives are their own harm: an app that flags everything gets
        // ignored, which costs you the flag that mattered.
        assertFalse(InteractionAgent.overlaps("asthma", "diabetes"))
        assertFalse(InteractionAgent.overlaps("migraine", "kidney disease"))
        assertFalse(InteractionAgent.overlaps("penicillin", "paracetamol"))
        assertFalse(InteractionAgent.overlaps("", "asthma"))
        assertFalse(InteractionAgent.overlaps("asthma", ""))
    }

    @Test
    fun `filler words alone never constitute a match`() {
        // "allergy" and "disease" appear in half the corpus; matching on them
        // would flag every medicine for every user.
        assertFalse(InteractionAgent.overlaps("dust allergy", "penicillin allergy"))
        assertFalse(InteractionAgent.overlaps("thyroid disease", "kidney disease"))
    }

    @Test
    fun `matches finds every needle present in the haystack`() {
        val conditions = listOf("mild asthma", "type 2 diabetes")
        val flags = listOf("asthma", "peptic ulcer", "diabetes")
        val hits = InteractionAgent.matches(conditions, flags)
        assertTrue(hits.contains("asthma"))
        assertTrue(hits.contains("diabetes"))
        assertFalse(hits.contains("peptic ulcer"))
    }
}
