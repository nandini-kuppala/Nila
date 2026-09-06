package com.nila.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

private fun JSONArray?.toStringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

/** One retrievable document, with the caution that must travel with it. */
data class KnowledgeDoc(
    val id: String,
    val collection: String,
    val topic: String,
    val title: String,
    val text: String,
    val source: String,
    val caution: String,
    val keywords: String,
    val risk: String? = null,
    /** "baby", "mother" or "both" -- who this guidance is for. */
    val audience: String = "baby",
    /** Age window in months this applies to; null means unbounded. */
    val minAgeMonths: Int? = null,
    val maxAgeMonths: Int? = null,
    /** Populated for medicines: the fields the interaction agent reasons over. */
    val generic: String = "",
    val aliases: List<String> = emptyList(),
    val avoidIf: List<String> = emptyList(),
    val interactsWith: List<String> = emptyList(),
    val contextNote: String = "",
    /** Medicine description with the risk label stripped, for composed prose. */
    val detail: String = "",
) {
    /**
     * The searchable fields, with the weight each carries.
     *
     * A term in the title is far stronger evidence than the same term buried in
     * a paragraph. Flattening all three into one string made "why does my baby
     * cry so much" rank the red-flags document above the one actually titled
     * "How much crying is normal", because red-flags mentions crying in passing
     * and happens to be shorter.
     */
    val fields: List<Pair<String, Int>>
        get() = listOf(title to 4, keywords to 3, text to 1)
}

data class Retrieved(val doc: KnowledgeDoc, val score: Double)

/**
 * BM25 retrieval over the bundled corpus.
 *
 * A neural embedder would be the fashionable choice, but for 39 curated
 * documents with a heavily domain-specific vocabulary -- brand names, symptom
 * words -- lexical matching is both more accurate and more predictable, and it
 * ships without a 100 MB embedding model or a warm-up cost. "Is combiflam safe"
 * needs to match the Combiflam entry exactly, every time, which is a job BM25
 * does better than cosine similarity over a general-purpose vector space.
 *
 * Everything is loaded once at startup and stays in memory: 24 KB.
 */
class KnowledgeIndex private constructor(
    private val docs: List<KnowledgeDoc>,
    private val postings: Map<String, List<Pair<Int, Int>>>,   // term -> (docIdx, tf)
    private val docLengths: IntArray,
    private val averageLength: Double,
) {
    companion object {
        private const val K1 = 1.4
        private const val B = 0.72

        const val GUIDANCE = "guidance"

        /**
         * Below this BM25 score the best hit is a coincidence.
         *
         * BM25 returns something whenever any term matched anything, so
         * "what's the score in the india match" came back with a confident page
         * about postnatal recovery on the strength of one shared word.
         * Set low, because the coverage test in [retrieveGuidance] is what
         * actually separates a real answer from a coincidence. An absolute
         * floor high enough to reject "India" at 7.3 would have thrown away
         * every correct answer in the corpus.
         */
        const val MIN_RELEVANCE = 2.8

        /** Common words that would otherwise dominate a short medical query. */
        private val STOPWORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "do",
            "does", "for", "from", "has", "have", "how", "i", "if", "in", "is",
            "it", "my", "of", "on", "or", "should", "that", "the", "this", "to",
            "was", "what", "when", "which", "while", "who", "why", "with", "you",
            "your", "baby", "babies",
            // Pronouns carry no topic. They were counted as query content and
            // inflated the coverage threshold below, so "she hates being on her
            // front" looked like a four-word question when it is a two-word one.
            "she", "he", "her", "him", "his", "hers", "they", "them", "their",
            "we", "us", "our", "me", "mine", "there", "here",
            // Contractions typed without the apostrophe, which is how they
            // arrive at 3am.
            "dont", "doesnt", "didnt", "havent", "hasnt", "cant", "wont",
            "isnt", "arent", "wasnt", "im", "ive", "id", "ill", "its", "thats",
            "whats", "hes", "shes", "theyre", "youre", "wouldnt", "couldnt",
            "shouldnt", "ok", "okay", "get", "got", "make", "makes", "want",
            // "It takes very little" in the caregiver document was matching
            // "how long should a feed take".
            "take", "takes", "taking", "taken",
        )

        /**
         * Words that mean the same thing to a parent but not to a lexical index.
         *
         * Deliberately short and one-directional: each entry maps a word people
         * type to the word the corpus uses. A large automatic thesaurus would
         * blur documents into each other -- with 46 entries the whole point is
         * that "mastitis" retrieves exactly one of them.
         */
        private val SYNONYMS = mapOf(
            "mom" to "mother", "mum" to "mother", "mummy" to "mother",
            "mama" to "mother", "amma" to "mother",
            "diaper" to "nappy", "diapers" to "nappy",
            "pacifier" to "dummy",
            "immunization" to "vaccine", "immunisation" to "vaccine",
            "vaccination" to "vaccine", "vaccinations" to "vaccine",
            "jab" to "vaccine", "jabs" to "vaccine", "shots" to "vaccine",
            "hungry" to "hunger", "starving" to "hunger",
            "poop" to "stool", "poo" to "stool", "poops" to "stool",
            "puke" to "vomit", "sick" to "vomit",
            "pediatrician" to "doctor", "paediatrician" to "doctor",
            "gp" to "doctor", "hospital" to "emergency", "casualty" to "emergency",
            "stroller" to "pram", "buggy" to "pram",
            "weaning" to "solids", "wean" to "solids",
            "nursing" to "breastfeeding", "nurse" to "breastfeeding",
            "colicky" to "colic",
            "calm" to "settle", "soothe" to "settle", "soothing" to "settle",
            "comfort" to "settle", "quieten" to "settle",
            "cope" to "overwhelmed", "coping" to "overwhelmed",
            "exhausted" to "overwhelmed", "shattered" to "overwhelmed",
            "belly" to "tummy", "stomach" to "tummy",
            "milk" to "breastmilk",
            "injection" to "vaccine", "injections" to "vaccine",
            // Irregular verbs the suffix stripper cannot reach. "I haven't
            // slept in weeks" shares no term with a document about sleep
            // without this.
            "slept" to "sleep", "sleeping" to "sleep",
            "fed" to "feed", "ate" to "eat", "eaten" to "eat",
            "woke" to "wake", "woken" to "wake", "awake" to "wake",
            "gave" to "give", "given" to "give",
            "felt" to "feel", "fell" to "fall",
        )

        fun tokenize(text: String): List<String> = text
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in STOPWORDS }
            .map { stem(SYNONYMS[it] ?: it) }

        /**
         * A deliberately small suffix stripper.
         *
         * Without it, "dehydrated" and "dehydration" are unrelated terms and a
         * parent asking the first gets nothing from a document titled the
         * second. That was not a ranking problem in the evaluation -- it
         * returned *no documents at all*, which the app then honestly reported
         * as knowing nothing.
         *
         * Full Porter stemming is more than this corpus needs and mangles the
         * clinical vocabulary it exists to match ("mastitis" must not become
         * "mastiti" and collide with something else). These five rules cover
         * the endings that actually appear in typed questions.
         */
        internal fun stem(word: String): String {
            if (word.length <= 2) return word
            var w = word

            // Plurals and third person. "ss" is left alone so "illness" and
            // "less" survive intact.
            w = when {
                w.endsWith("sses") -> w.dropLast(2)
                w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "i"
                w.endsWith("ss") -> w
                w.endsWith("s") && w.length > 3 -> w.dropLast(1)
                else -> w
            }

            // Gerund and past tense.
            if (w.endsWith("ing") && w.length > 5) w = w.dropLast(3)
            else if (w.endsWith("ed") && w.length > 4) w = w.dropLast(2)

            // "stopping" -> "stopp" -> "stop". Doubled l/s/z are real endings
            // ("still", "fuss"), so they stay.
            if (w.length > 3 && w[w.length - 1] == w[w.length - 2] &&
                w.last() !in "lsz"
            ) w = w.dropLast(1)

            // "dehydration" -> "dehydrat", which is what "dehydrated" becomes.
            if (w.endsWith("ation") && w.length > 6) w = w.dropLast(3)

            // "smile" and "smiling" both reduce to "smil".
            if (w.endsWith("e") && w.length > 3) w = w.dropLast(1)

            // "cry", "cries" and "crying" all reduce to "cri".
            if (w.endsWith("y") && w.length > 2) w = w.dropLast(1) + "i"

            return w
        }

        fun load(context: Context, asset: String = "knowledge.json"): KnowledgeIndex {
            val json = context.assets.open(asset).bufferedReader().use { it.readText() }
            val array = JSONObject(json).getJSONArray("documents")

            val docs = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
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
                    aliases = o.optJSONArray("aliases").toStringList(),
                    avoidIf = o.optJSONArray("avoid_if").toStringList(),
                    interactsWith = o.optJSONArray("interacts_with").toStringList(),
                    contextNote = o.optString("context_note"),
                    detail = o.optString("detail"),
                    audience = o.optString("audience", "baby"),
                    minAgeMonths = if (o.isNull("min_age")) null else o.optInt("min_age"),
                    maxAgeMonths = if (o.isNull("max_age")) null else o.optInt("max_age"),
                )
            }
            return build(docs)
        }

        fun build(docs: List<KnowledgeDoc>): KnowledgeIndex {
            val postings = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
            val lengths = IntArray(docs.size)

            docs.forEachIndexed { idx, doc ->
                val weighted = mutableMapOf<String, Int>()
                var length = 0
                for ((field, weight) in doc.fields) {
                    for (term in tokenize(field)) {
                        weighted[term] = (weighted[term] ?: 0) + weight
                        length += weight
                    }
                }
                lengths[idx] = length
                weighted.forEach { (term, tf) ->
                    postings.getOrPut(term) { mutableListOf() }.add(idx to tf)
                }
            }

            return KnowledgeIndex(
                docs = docs,
                postings = postings,
                docLengths = lengths,
                averageLength = lengths.average().takeIf { it > 0 } ?: 1.0,
            )
        }
    }

    val size: Int get() = docs.size
    val allDocs: List<KnowledgeDoc> get() = docs
    val medicineCount: Int get() = docs.count { it.collection == "medicines" }

    fun medicineByGeneric(generic: String): KnowledgeDoc? = docs.firstOrNull {
        it.collection == "medicines" && it.generic.equals(generic, ignoreCase = true)
    }

    /**
     * Retrieval filtered to who is asking and how old the baby is.
     *
     * Age gating is not a nicety. Asking "what should she eat" about a
     * four-month-old and getting a list of choking hazards is advice for a baby
     * on solids, and acting on it would be actively harmful to one who should
     * still be exclusively breastfed.
     */
    fun search(
        query: String,
        limit: Int = 4,
        collection: String? = null,
        audience: String? = null,
        babyAgeMonths: Int? = null,
    ): List<Retrieved> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()

        val scores = HashMap<Int, Double>()
        val n = docs.size.toDouble()

        for (term in terms) {
            val posting = postings[term] ?: continue
            val df = posting.size.toDouble()
            // BM25 IDF, floored at zero so a term appearing in most documents
            // cannot push a score negative.
            val idf = maxOf(0.0, ln(1.0 + (n - df + 0.5) / (df + 0.5)))

            for ((idx, tf) in posting) {
                val doc = docs[idx]
                if (collection != null && doc.collection != collection) continue
                if (audience != null && doc.collection == "guidance" &&
                    doc.audience != audience && doc.audience != "both"
                ) continue
                if (babyAgeMonths != null && doc.collection == "guidance" &&
                    doc.audience == "baby"
                ) {
                    if (doc.minAgeMonths != null && babyAgeMonths < doc.minAgeMonths) continue
                    if (doc.maxAgeMonths != null && babyAgeMonths > doc.maxAgeMonths) continue
                }
                val len = docLengths[idx].toDouble()
                val norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * len / averageLength))
                scores[idx] = (scores[idx] ?: 0.0) + idf * norm
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { Retrieved(docs[it.key], it.value) }
    }

    /**
     * The guidance-retrieval policy, in one place.
     *
     * Extracted from [com.nila.assistant.Assistant] so the evaluation harness
     * measures what the app does rather than an approximation of it. The first
     * version of that harness called [search] directly and scored medicine
     * documents the app would never have shown -- it was reporting on a
     * pipeline that did not exist.
     *
     * Three attempts, narrowing conditions, then a relevance floor:
     *  - the right audience and the right age window
     *  - any audience, in case the subject was misread
     *  - any age, because a partly applicable answer beats silence
     *  - and nothing at all if the best hit is a coincidence
     */
    fun retrieveGuidance(
        query: String,
        audience: String?,
        babyAgeMonths: Int?,
        limit: Int = 3,
        minRelevance: Double = MIN_RELEVANCE,
    ): List<Retrieved> {
        var hits = search(query, limit, collection = GUIDANCE,
                          audience = audience, babyAgeMonths = babyAgeMonths)
        if (hits.isEmpty() && audience != null) {
            hits = search(query, limit, collection = GUIDANCE,
                          babyAgeMonths = babyAgeMonths)
        }
        if (hits.isEmpty()) hits = search(query, limit, collection = GUIDANCE)

        val top = hits.firstOrNull() ?: return emptyList()
        if (top.score < minRelevance) return emptyList()
        if (!coversEnoughOf(query, top.doc)) return emptyList()
        return hits
    }

    /**
     * Did the winning document match more than one thing the question was about?
     *
     * The score alone cannot tell. "What's the score in the India match"
     * retrieved the immunisation page at 7.3 -- a confident-looking number
     * produced entirely by the word "India" appearing in its title. A rare term
     * in a weighted field outranks several common ones, which is usually what
     * you want and is exactly wrong when the rare term is incidental.
     *
     * So a multi-word question has to overlap the document in at least two
     * distinct terms. A one- or two-word question is exempt, because "colic"
     * genuinely is the whole query.
     */
    private fun coversEnoughOf(query: String, doc: KnowledgeDoc): Boolean {
        val queryTerms = tokenize(query).toSet()
        // Three content words or fewer is a specific question -- "are wooden
        // toys better", "what is colic" -- and one strong match is the whole
        // of it. The rule only bites on longer questions, where a single
        // incidental overlap really is a coincidence.
        if (queryTerms.size < 4) return true
        val docTerms = doc.fields.flatMapTo(HashSet()) { tokenize(it.first) }
        return queryTerms.count { it in docTerms } >= 2
    }

    /**
     * Find a medicine by name or brand alias, tolerating misspelling.
     *
     * Two passes, in order of confidence.
     *
     * **Exact tokens first.** OCR of a strip yields a dozen tokens of dosage and
     * manufacturer text around the one word that matters, so every token is
     * checked against every medicine name rather than scoring the query whole.
     *
     * **Then edit distance.** People type "amoxicilin", "paracetmol",
     * "ibuprofin"; OCR turns rn into m and l into 1. Refusing those is the app
     * being pedantic at the exact moment someone wants an answer. The allowance
     * scales with word length -- one edit for a short name, up to three for a
     * long one -- because a single edit on a five-letter word can genuinely be a
     * different drug, while three edits on "amoxicillin" cannot.
     */
    fun findMedicine(text: String): Retrieved? {
        val tokens = tokenize(text).toSet()
        if (tokens.isEmpty()) return null

        val medicines = docs.filter { it.collection == "medicines" }

        var best: Retrieved? = null
        for (doc in medicines) {
            val names = nameTokens(doc)
            val overlap = names.intersect(tokens)
            if (overlap.isEmpty()) continue
            // Longer matched names are stronger evidence than short ones --
            // "pan" is a common substring, "pantoprazole" is not.
            val score = overlap.sumOf { it.length.toDouble() }
            if (best == null || score > best.score) best = Retrieved(doc, score)
        }
        best?.takeIf { it.score >= 4.0 }?.let { return it }

        // Nothing matched exactly. Try again against OCR-normalised tokens,
        // allowing for typos, character confusions and trailing junk.
        val normalised = tokens.map { normaliseOcr(it) }.filter { it.length >= 5 }

        var fuzzy: Retrieved? = null
        for (doc in medicines) {
            for (name in nameTokens(doc)) {
                if (name.length < 5) continue          // too short to fuzz safely
                val budget = allowedEdits(name)
                for (token in normalised) {
                    val score = matchScore(token, name, budget) ?: continue
                    if (fuzzy == null || score > fuzzy.score) {
                        fuzzy = Retrieved(doc, score)
                    }
                }
            }
        }
        // 4.0 admits a one-edit correction on a six-letter brand like "crocin";
        // raising it locked those out. The per-candidate scoring in matchScore
        // is what keeps this honest, not the floor.
        return fuzzy?.takeIf { it.score >= 4.0 }
    }

    /**
     * How well one OCR token matches one medicine name, or null for no match.
     *
     * Two ways to match, because OCR fails in two different ways. Whole-token
     * edit distance handles substitutions -- a misread character, a typo. Prefix
     * matching handles the other case: strips print the name flush against a
     * strength, and the recogniser runs them together, so "IBUPROFEN 400" comes
     * back as one token "ibuprofen400". Requiring the whole token to match would
     * throw that away for the sake of three digits.
     */
    private fun matchScore(token: String, name: String, budget: Int): Double? {
        if (kotlin.math.abs(token.length - name.length) <= budget) {
            val distance = editDistance(token, name, budget)
            if (distance <= budget) return name.length.toDouble() - distance * 1.5
        }

        // A long name at the start of a longer token: the strength or dosage got
        // glued on. Only for names long enough that a shared prefix is real
        // evidence rather than coincidence.
        if (name.length >= 6 && token.length > name.length) {
            val head = token.take(name.length)
            val distance = editDistance(head, name, 1)
            if (distance <= 1) return name.length.toDouble() - distance * 1.5 - 0.5
        }
        return null
    }

    /**
     * Undo the character substitutions OCR reliably makes.
     *
     * Digits standing in for letters are the common ones on foil and small
     * print: a capital I read as 1, O as 0, S as 5. Applied only in the fuzzy
     * pass -- the exact pass must keep real digits intact, because a strength is
     * sometimes the only thing distinguishing two products.
     */
    internal fun normaliseOcr(token: String): String = token
        .lowercase()
        .map { c ->
            when (c) {
                '1' -> 'i'
                '0' -> 'o'
                '5' -> 's'
                '8' -> 'b'
                '9' -> 'g'
                '2' -> 'z'
                else -> c
            }
        }
        .joinToString("")

    private fun nameTokens(doc: KnowledgeDoc): Set<String> =
        tokenize(doc.generic + " " + doc.aliases.joinToString(" ")).toSet()

    private fun allowedEdits(name: String) = when {
        name.length >= 10 -> 3
        name.length >= 7 -> 2
        else -> 1
    }

    /**
     * Levenshtein distance, abandoned early once it exceeds [limit].
     *
     * Bounded because this runs across every alias of every medicine for every
     * token of an OCR blob, and the answer past the limit is never used.
     */
    internal fun editDistance(a: String, b: String, limit: Int): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
                if (current[j] < rowMin) rowMin = current[j]
            }
            if (rowMin > limit) return limit + 1
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }

    fun byTopic(topic: String): List<KnowledgeDoc> = docs.filter { it.topic == topic }
}
