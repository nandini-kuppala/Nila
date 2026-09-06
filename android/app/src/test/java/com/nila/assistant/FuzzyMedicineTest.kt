package com.nila.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Misspelt and mis-scanned medicine names.
 *
 * People type "amoxicilin" and OCR turns rn into m. Refusing those is the app
 * being pedantic at the moment someone actually wants an answer -- but matching
 * too loosely means answering about a different drug, which is worse. These
 * tests pin both edges.
 */
class FuzzyMedicineTest {

    private lateinit var index: KnowledgeIndex

    @Before
    fun setUp() {
        val json = File("src/main/assets/knowledge.json").readText()
        val array = JSONObject(json).getJSONArray("documents")
        val docs = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun list(name: String) = o.optJSONArray(name)?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList()
            KnowledgeDoc(
                id = o.getString("id"),
                collection = o.getString("collection"),
                topic = o.getString("topic"),
                title = o.getString("title"),
                text = o.getString("text"),
                source = o.getString("source"),
                caution = o.optString("caution", ""),
                keywords = o.optString("keywords", ""),
                risk = o.optString("risk").takeIf { it.isNotEmpty() },
                generic = o.optString("generic"),
                aliases = list("aliases"),
                avoidIf = list("avoid_if"),
                interactsWith = list("interacts_with"),
                contextNote = o.optString("context_note"),
                detail = o.optString("detail"),
            )
        }
        index = KnowledgeIndex.build(docs)
    }

    private fun resolves(query: String, expect: String) {
        val hit = index.findMedicine(query)
        assertNotNull("'$query' did not resolve at all", hit)
        assertTrue(
            "'$query' resolved to ${hit!!.doc.generic}, expected $expect",
            hit.doc.generic.equals(expect, ignoreCase = true) ||
                hit.doc.id.contains(expect.lowercase()),
        )
    }

    @Test
    fun `exact names still work`() {
        resolves("amoxicillin", "Amoxicillin")
        resolves("paracetamol", "Paracetamol")
        resolves("ibuprofen", "Ibuprofen")
    }

    @Test
    fun `common misspellings resolve`() {
        resolves("amoxicilin", "Amoxicillin")
        resolves("amoxycillin", "Amoxicillin")
        resolves("paracetmol", "Paracetamol")
        resolves("paracetamal", "Paracetamol")
        resolves("ibuprofin", "Ibuprofen")
        resolves("ibuprofen", "Ibuprofen")
        resolves("azithromicin", "Azithromycin")
        resolves("metronidazol", "Metronidazole")
    }

    @Test
    fun `misspelt brand names resolve`() {
        resolves("combiflame", "Ibuprofen")
        resolves("crocine", "Paracetamol")
        resolves("augmentine", "Amoxicillin-clavulanate")
    }

    @Test
    fun `ocr character confusions resolve`() {
        // l/1, o/0 and rn/m are the classic scanner slips.
        resolves("paracetamo1", "Paracetamol")
        resolves("ibupr0fen", "Ibuprofen")
    }

    @Test
    fun `a different drug is not silently substituted`() {
        // The failure that matters: two real medicines a few edits apart must
        // never be confused for one another.
        val hit = index.findMedicine("tramadol")
        assertNotNull(hit)
        assertTrue("tramadol resolved to ${hit!!.doc.generic}",
                   hit.doc.generic.equals("Tramadol", ignoreCase = true))
    }

    @Test
    fun `non-medicines still resolve to nothing`() {
        listOf("hii", "hello", "corn flakes", "the cat sat on the mat",
               "12345", "").forEach {
            assertNull("'$it' matched a medicine", index.findMedicine(it))
        }
    }

    @Test
    fun `short words are not fuzzed into medicines`() {
        // Fuzzing four-letter words would match almost anything.
        listOf("pan", "iron", "milk", "cold", "pain").forEach {
            val hit = index.findMedicine(it)
            assertTrue(
                "'$it' fuzzed into ${hit?.doc?.generic}",
                hit == null || hit.doc.generic.contains(it, ignoreCase = true),
            )
        }
    }

    @Test
    fun `edit distance abandons early past its limit`() {
        assertEquals(0, index.editDistance("abc", "abc", 2))
        assertEquals(1, index.editDistance("abc", "abd", 2))
        assertTrue(index.editDistance("abcdef", "zzzzzz", 2) > 2)
    }
}
