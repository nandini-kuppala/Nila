package com.nila.monitor

import com.nila.assistant.KnowledgeDoc

/**
 * What to try, for the cause the classifier named.
 *
 * The rule the rest of this app follows applies here too: the words a parent
 * reads come out of the corpus, with the source attached, and nothing generates
 * them. So each of the five classes points at one curated entry, and this class
 * does the pointing and nothing else -- it holds no guidance text of its own.
 *
 * The one sentence authored here is the headline, and it is authored so that it
 * cannot be read as a diagnosis: every one of them says *probably*. The
 * classifier did not clear chance on infants it had never heard
 * (0.444 subject-wise macro AUC), and a screen that says "your baby is hungry"
 * on the back of that number is the exact claim this project exists to refuse.
 *
 * Pure Kotlin and lookup-injected, so the mapping is testable on the JVM
 * against the real shipped corpus rather than a fixture that can drift from it.
 */
object CryAdvice {

    /** Class name from the forest, to the corpus entry that answers it. */
    private val ENTRY_BY_LABEL = mapOf(
        "hungry" to "cause-hungry",
        "tired" to "cause-tired",
        "belly_pain" to "cause-belly-pain",
        "burping" to "cause-burping",
        "discomfort" to "cause-discomfort",
    )

    /**
     * How the cause reads in a sentence.
     *
     * `belly_pain` is a class name; "of belly pain" is the fragment that makes
     * an English sentence out of it. Kept separate from the label so the class
     * names can stay whatever sklearn sorted them into.
     */
    private val PHRASE_BY_LABEL = mapOf(
        "hungry" to "they are hungry",
        "tired" to "they are overtired",
        "belly_pain" to "of belly pain",
        "burping" to "of trapped wind",
        "discomfort" to "something is uncomfortable",
    )

    data class Advice(
        val label: String,
        /** One hedged sentence, large and bold, for the ninety-second verdict. */
        val headline: String,
        /** The corpus entry, verbatim. */
        val guidance: String,
        /** Concrete things to try, in the order the entry lists them. */
        val steps: List<String>,
        /** Non-empty when the entry carries a safety caveat. Always shown. */
        val caution: String,
        val source: String,
        val title: String,
    )

    /**
     * @param lookup resolves a corpus id, normally `KnowledgeIndex::byId`.
     * @return null for a label with no entry, which is a corpus bug rather than
     * something to paper over with generic advice.
     */
    fun forLabel(label: String, lookup: (String) -> KnowledgeDoc?): Advice? {
        val doc = ENTRY_BY_LABEL[label]?.let(lookup) ?: return null
        val phrase = PHRASE_BY_LABEL[label] ?: "of ${label.replace('_', ' ')}"
        return Advice(
            label = label,
            headline = "Your baby is probably crying because $phrase.",
            guidance = doc.text,
            steps = doc.detail.split('|').map { it.trim() }.filter { it.isNotEmpty() },
            caution = doc.caution,
            source = doc.source,
            title = doc.title,
        )
    }

    /** The labels this mapping covers, for the test that pins them to the corpus. */
    val labels: Set<String> get() = ENTRY_BY_LABEL.keys

    /** `belly_pain` is a class name; "belly pain" is what a person reads. */
    fun plain(label: String): String = label.replace('_', ' ')
}
