package com.nila.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Retrieval tests against the real shipped corpus.
 *
 * These matter more than they look. The corpus answers questions about medicine
 * and infant health, so a query silently matching the wrong document is a
 * correctness bug with consequences, not a relevance nit.
 */
class KnowledgeIndexTest {

    private lateinit var index: KnowledgeIndex
    private lateinit var docs: List<KnowledgeDoc>

    @Before
    fun setUp() {
        val json = javaClass.classLoader!!.getResourceAsStream("knowledge.json")!!
            .bufferedReader().use { it.readText() }
        val array = JSONObject(json).getJSONArray("documents")
        docs = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            // The medicine fields are parsed here too. Omitting them made
            // findMedicine silently match nothing, and the test passed anyway
            // until fuzzy matching started relying on them.
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
                audience = o.optString("audience", "baby"),
            )
        }
        index = KnowledgeIndex.build(docs)
    }

    @Test
    fun `corpus loads`() {
        assertTrue("expected a populated corpus, got ${index.size}", index.size >= 30)
    }

    @Test
    fun `every document carries a source`() {
        // An answer without a source is not something to show a parent.
        val sourceless = docs.filter { it.source.isBlank() }.map { it.id }
        assertTrue("documents missing a source: $sourceless", sourceless.isEmpty())
    }

    @Test
    fun `every medicine entry carries a caution and a risk level`() {
        val medicines = docs.filter { it.collection == "medicines" }
        assertTrue("expected medicine entries", medicines.size >= 15)
        medicines.forEach {
            assertTrue("${it.id} has no caution", it.caution.isNotBlank())
            assertTrue("${it.id} has no risk level",
                       it.risk in setOf("compatible", "caution", "avoid"))
        }
    }

    @Test
    fun `no medicine entry states a dose`() {
        // Dosing depends on weight and formulation. An offline app must never
        // guess at one, so the corpus is checked for numbers followed by units.
        val doseLike = Regex("""\b\d+(\.\d+)?\s?(mg|ml|mcg|g)\b""", RegexOption.IGNORE_CASE)
        val offenders = docs.filter { it.collection == "medicines" }
            .filter { doseLike.containsMatchIn(it.text) }
            .map { it.id }
        assertTrue("medicine entries appear to contain a dose: $offenders",
                   offenders.isEmpty())
    }

    @Test
    fun `brand names resolve to the right medicine`() {
        // Indian brand names are what people actually type or photograph.
        val cases = mapOf(
            "crocin" to "paracetamol",
            "dolo 650" to "paracetamol",
            "combiflam" to "ibuprofen",
            "is augmentin safe" to "amoxicillin-clavulanate",
            "zyrtec" to "cetirizine",
        )
        cases.forEach { (query, expected) ->
            val hit = index.findMedicine(query)
            assertNotNull("no medicine matched '$query'", hit)
            assertTrue(
                "'$query' matched ${hit!!.doc.id}, expected to contain '$expected'",
                hit.doc.id.contains(expected),
            )
        }
    }

    @Test
    fun `noisy ocr text still resolves the medicine`() {
        // What a strip photo actually yields: dosage, manufacturer, batch text.
        val ocr = "CROCIN 650 Tablets IP Paracetamol Tablets IP " +
            "GlaxoSmithKline Batch No AB1234 Mfg 01/2026 Exp 12/2028"
        val hit = index.findMedicine(ocr)
        assertNotNull("noisy OCR failed to resolve", hit)
        assertTrue(hit!!.doc.id.contains("paracetamol"))
    }

    @Test
    fun `unrelated text does not match a medicine`() {
        // A false match here would tell someone a random object is a drug.
        assertNull(index.findMedicine("the cat sat on the mat"))
        assertNull(index.findMedicine(""))
        assertNull(index.findMedicine("hello world 12345"))
    }

    @Test
    fun `dangerous medicines are marked avoid`() {
        listOf("codeine", "tramadol", "aspirin").forEach { name ->
            val hit = index.findMedicine(name)
            assertNotNull("$name missing from corpus", hit)
            assertEquals("$name should be flagged avoid", "avoid", hit!!.doc.risk)
        }
    }

    @Test
    fun `guidance queries retrieve the intended topic`() {
        val cases = mapOf(
            "how much crying is normal" to "crying",
            "when can I give solid food" to "feeding",
            "is honey ok" to "feeding",
            "safe sleep position" to "sleep",
            "high temperature newborn" to "health",
            "is this toy safe" to "safety",
        )
        cases.forEach { (query, topic) ->
            val hits = index.search(query, limit = 3, collection = "guidance")
            assertTrue("no guidance hits for '$query'", hits.isNotEmpty())
            assertTrue(
                "'$query' returned ${hits.map { it.doc.topic }}, expected '$topic'",
                hits.any { it.doc.topic == topic },
            )
        }
    }

    @Test
    fun `results are ordered by score`() {
        val scores = index.search("breastfeeding paracetamol safe", limit = 5)
            .map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun `stopword-only queries return nothing rather than noise`() {
        assertTrue(index.search("the and of is").isEmpty())
    }

    @Test
    fun `collection filter is respected`() {
        val hits = index.search("safe", limit = 10, collection = "medicines")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.doc.collection == "medicines" })
    }
}
