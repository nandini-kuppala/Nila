package com.nila.assistant.agents

import com.nila.assistant.KnowledgeDoc
import com.nila.assistant.QueryRouter
import com.nila.assistant.KnowledgeIndex
import com.nila.data.HealthRecord
import com.nila.data.HealthRecordDao
import com.nila.data.MotherProfile
import com.nila.data.RecordSubject

/**
 * Four specialists and an adjudicator, deciding whether a medicine is safe for
 * a specific breastfeeding mother.
 *
 * Why a pipeline rather than one prompt: each stage has a different failure mode
 * and a different evidence source, and keeping them apart means the UI can show
 * *which* stage was uncertain. "I read the strip confidently but I know nothing
 * about your kidney function" is a far more useful answer than a single
 * confidence number over the whole question.
 *
 * The stages are rule-based and run entirely offline. The language model, when
 * one is installed, writes the final summary from the findings the stages
 * produced -- it never contributes a fact of its own. That ordering is the point:
 * a 0.5B model must not be the thing that decides whether a drug is safe.
 */

/** One stage's output. Shown to the user, so it must be readable, not internal. */
data class AgentStep(
    val agent: String,
    val looked_at: String,
    val finding: String,
    val confidence: Confidence,
    val couldNotCheck: String? = null,
    val flags: List<String> = emptyList(),
) {
    enum class Confidence { HIGH, MEDIUM, LOW, NONE }
}

enum class Verdict(val label: String, val severity: Int) {
    LIKELY_SAFE("Likely safe for you", 0),
    CAUTION("Check with your doctor", 1),
    AVOID("Best avoided", 2),
    UNKNOWN("Not enough information", 3),
}

data class MedicineReviewResult(
    val verdict: Verdict,
    val headline: String,
    val steps: List<AgentStep>,
    val medicine: KnowledgeDoc?,
    val sources: List<String>,
    val cautions: List<String>,
    val summary: String,
    /** Always false. Nothing in this app generates health text. */
    val summaryFromLlm: Boolean,
)

// ---------------------------------------------------------------- 1. history
/**
 * Reads the mother's profile and her stored records, and reports what is known
 * about her that could change a medicine answer.
 */
class HistoryAgent(private val records: HealthRecordDao) {

    data class MotherContext(
        val conditions: List<String>,
        val allergies: List<String>,
        val currentMedicines: List<String>,
        val breastfeeding: Boolean,
        val weeksPostpartum: Int?,
        val recordsConsulted: Int,
        val fromRecords: List<String>,
    )

    suspend fun run(profile: MotherProfile?): Pair<MotherContext, AgentStep> {
        val stored = records.listForSubject(RecordSubject.MOTHER.name)

        val declared = profile?.conditionList.orEmpty()
        val mentioned = mineConditions(stored, declared)

        val context = MotherContext(
            conditions = (declared + mentioned).distinct(),
            allergies = profile?.allergyList.orEmpty(),
            currentMedicines = profile?.medicineList.orEmpty(),
            breastfeeding = profile?.isBreastfeeding ?: true,
            weeksPostpartum = profile?.weeksPostpartum,
            recordsConsulted = stored.size,
            fromRecords = mentioned,
        )

        val known = declared.size + context.allergies.size +
            context.currentMedicines.size + mentioned.size
        val step = AgentStep(
            agent = "History",
            looked_at = "Your profile and ${stored.size} stored record" +
                if (stored.size == 1) "" else "s",
            finding = when {
                known == 0 && stored.isEmpty() ->
                    "Nothing on file yet. I can only give you the general answer."
                known == 0 ->
                    "Read ${stored.size} records but found no conditions, " +
                        "allergies or current medicines to weigh."
                else -> buildString {
                    // Declared and inferred are reported separately. What she
                    // told us and what we guessed from a scanned page do not
                    // carry the same weight, and merging them hides which is
                    // which at exactly the moment that matters.
                    if (declared.isNotEmpty())
                        append("Conditions: ${declared.joinToString(", ")}. ")
                    if (context.allergies.isNotEmpty())
                        append("Allergies: ${context.allergies.joinToString(", ")}. ")
                    if (context.currentMedicines.isNotEmpty())
                        append("Already taking: " +
                            "${context.currentMedicines.joinToString(", ")}. ")
                    if (mentioned.isNotEmpty())
                        append("Also mentioned in your records: " +
                            "${mentioned.joinToString(", ")}.")
                }.trim()
            },
            confidence = when {
                known > 0 -> AgentStep.Confidence.HIGH
                stored.isNotEmpty() -> AgentStep.Confidence.MEDIUM
                else -> AgentStep.Confidence.NONE
            },
            couldNotCheck = if (known == 0)
                "Add your conditions, allergies and current medicines in Health " +
                    "so this check means something" else null,
            flags = context.fromRecords.map { "found \"$it\" in your records" },
        )
        return context to step
    }

    companion object {
        /** Terms worth spotting in OCR text because they change drug answers. */
        // Specific phrasings are listed alongside their broader forms on
        // purpose: matching both is what lets the subsumption filter drop the
        // vague one. A finding that reads "iron deficiency anaemia, anaemia"
        // looks padded, and padding makes a safety result less believable.
        private val WATCHED_TERMS = listOf(
            "asthma", "type 2 diabetes", "type 1 diabetes", "gestational diabetes",
            "diabetes", "hypertension", "thyroid", "epilepsy",
            "chronic kidney disease", "kidney disease", "liver disease",
            "iron deficiency anaemia", "iron deficiency anemia", "anaemia", "anemia",
            "peptic ulcer", "ulcer", "penicillin allergy", "migraine",
            "postnatal depression", "depression", "gastritis",
        )

        /**
         * Phrasing that means a document is *ruling out* a condition, not
         * recording it.
         *
         * Without this the naive scan reads "EDINBURGH POSTNATAL DEPRESSION
         * SCALE - below referral threshold" and concludes she has depression.
         * A screening someone passed is the single most common way a keyword
         * scan invents a diagnosis, and telling a clinician she has a condition
         * a document explicitly excluded is worse than missing it.
         */
        private val NEGATING = listOf(
            "below referral", "below threshold", "no evidence", "not indicated",
            "ruled out", "negative", "screening", "screen", "scale", "denies",
            "no history", "nil", "within normal", "pass", "unremarkable",
            "questionnaire", "assessment tool", "score",
        )

        /** Characters either side of a match to inspect for negation. */
        private const val WINDOW = 90

        /**
         * Terms mentioned in documents, minus anything a document is denying
         * and anything the caregiver already declared herself.
         */
        internal fun mineConditions(
            records: List<HealthRecord>,
            declared: List<String>,
        ): List<String> {
            val found = mutableListOf<String>()
            for (record in records) {
                val text = record.searchable.lowercase()
                for (term in WATCHED_TERMS) {
                    var from = text.indexOf(term)
                    while (from >= 0) {
                        val start = (from - WINDOW).coerceAtLeast(0)
                        val end = (from + term.length + WINDOW).coerceAtMost(text.length)
                        val around = text.substring(start, end)
                        if (NEGATING.none { around.contains(it) }) {
                            found += term
                            break
                        }
                        from = text.indexOf(term, from + term.length)
                    }
                }
            }
            // Drop anything already stated, and anything subsumed by a more
            // specific phrase -- "anaemia" beside "iron deficiency anaemia" is
            // noise that makes the finding look padded.
            val all = declared.map { it.lowercase() }
            return found.distinct().filterNot { term ->
                all.any { it.contains(term) } ||
                    found.any { it != term && it.contains(term) }
            }
        }
    }
}

// ------------------------------------------------------------- 2. identify
/** Turns noisy OCR text into a specific medicine, or admits it could not. */
class IdentifyAgent(private val index: KnowledgeIndex) {

    data class Identified(val doc: KnowledgeDoc?, val evidence: String)

    fun run(ocrText: String): Pair<Identified, AgentStep> {
        val cleaned = ocrText.replace(Regex("\\s+"), " ").trim()

        // Not everything typed into a medicine box is a medicine. Saying so is
        // better than running four stages on "Hii" and presenting the result as
        // though a check happened.
        if (cleaned.length < 3 || QueryRouter.route(cleaned) is QueryRouter.Route.Greeting) {
            return Identified(null, cleaned) to AgentStep(
                agent = "Identify",
                looked_at = if (cleaned.isBlank()) "Nothing entered" else "\"$cleaned\"",
                finding = "That does not look like a medicine name.",
                confidence = AgentStep.Confidence.NONE,
                couldNotCheck = "Type the name printed on the strip, or photograph it.",
            )
        }

        val hit = index.findMedicine(cleaned)
        val exact = hit != null && exactlyNamed(hit.doc, cleaned)

        val step = AgentStep(
            agent = "Identify",
            looked_at = if (cleaned.isBlank()) "No readable text"
                        else "\"${cleaned.take(90)}${if (cleaned.length > 90) "..." else ""}\"",
            finding = when {
                hit == null -> "Could not match anything in the medicine list."
                exact -> "Recognised ${hit.doc.generic}."
                // Say so when a spelling was corrected. Silently answering about
                // a different drug than the one someone typed is the worst
                // possible way to be helpful.
                else -> "Read this as ${hit.doc.generic}. If that is not what you " +
                    "have, check the spelling on the packet."
            },
            confidence = when {
                hit == null -> AgentStep.Confidence.NONE
                exact && hit.score >= 10 -> AgentStep.Confidence.HIGH
                exact -> AgentStep.Confidence.MEDIUM
                else -> AgentStep.Confidence.LOW
            },
            couldNotCheck = if (hit == null)
                "Only ${index.medicineCount} common medicines are covered offline. " +
                    "Check the spelling, or ask your pharmacist." else null,
            flags = if (hit != null && !exact) listOf("spelling corrected")
                    else emptyList(),
        )
        return Identified(hit?.doc, cleaned) to step
    }

    /** Did the text contain the name verbatim, or did fuzzy matching rescue it? */
    private fun exactlyNamed(doc: KnowledgeDoc, text: String): Boolean {
        val tokens = KnowledgeIndex.tokenize(text).toSet()
        val names = KnowledgeIndex
            .tokenize(doc.generic + " " + doc.aliases.joinToString(" ")).toSet()
        return tokens.intersect(names).isNotEmpty()
    }
}

// --------------------------------------------------------------- 3. safety
/** Looks up what is known about this medicine during breastfeeding. */
class SafetyAgent {

    fun run(doc: KnowledgeDoc?): Pair<String?, AgentStep> {
        if (doc == null) {
            return null to AgentStep(
                agent = "Breastfeeding safety",
                looked_at = "Nothing to look up",
                finding = "Skipped - no medicine was identified.",
                confidence = AgentStep.Confidence.NONE,
            )
        }
        val risk = doc.risk ?: "caution"
        return risk to AgentStep(
            agent = "Breastfeeding safety",
            looked_at = doc.source,
            finding = doc.text.substringBefore(" Also sold as").trim(),
            confidence = AgentStep.Confidence.HIGH,
            couldNotCheck = "This is about you taking it, not about giving it to " +
                "your baby, and it never covers dose.",
            flags = listOf(
                when (risk) {
                    "compatible" -> "generally compatible"
                    "avoid" -> "flagged avoid"
                    else -> "use with caution"
                }
            ),
        )
    }
}

// ---------------------------------------------------------- 4. interactions
/**
 * The stage that makes this personal.
 *
 * Cross-checks the identified medicine against her declared conditions,
 * allergies and current medicines. This is the part a generic lookup cannot do
 * and the reason the history stage exists.
 */
class InteractionAgent {

    companion object {
        /** Words too common to carry meaning when matching a condition. */
        private val NOISE = setOf("allergy", "allergic", "disease", "disorder",
                                  "problems", "mild", "severe", "chronic", "history",
                                  "of", "and", "the", "to", "a")

        private fun terms(value: String): Set<String> = value
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in NOISE }
            .toSet()

        /**
         * True when two free-text clinical phrases refer to the same thing.
         *
         * Substring containment alone is not enough and gets the direction wrong
         * in exactly the case that matters: a user writing "penicillin" against
         * a corpus entry of "penicillin allergy". Comparing meaningful terms
         * makes the match symmetric, which is what a safety check needs.
         */
        fun overlaps(a: String, b: String): Boolean {
            val ta = terms(a)
            val tb = terms(b)
            if (ta.isEmpty() || tb.isEmpty()) return false
            return ta.any { it in tb }
        }

        fun matches(haystack: List<String>, needles: List<String>): List<String> =
            needles.filter { needle -> haystack.any { overlaps(it, needle) } }
    }

    data class Findings(
        val conditionHits: List<String>,
        val allergyHits: List<String>,
        val medicineHits: List<String>,
        val duplicate: Boolean,
    ) {
        val any: Boolean get() =
            conditionHits.isNotEmpty() || allergyHits.isNotEmpty() ||
                medicineHits.isNotEmpty()
    }

    fun run(
        doc: KnowledgeDoc?,
        context: HistoryAgent.MotherContext,
    ): Pair<Findings, AgentStep> {
        if (doc == null) {
            return Findings(emptyList(), emptyList(), emptyList(), false) to AgentStep(
                agent = "Your history",
                looked_at = "Nothing to cross-check",
                finding = "Skipped - no medicine was identified.",
                confidence = AgentStep.Confidence.NONE,
            )
        }

        val conditionHits = matches(context.conditions, doc.avoidIf)

        // Allergies are matched two ways, and both matter. A stated allergy can
        // name the condition the corpus flags ("penicillin" vs "penicillin
        // allergy"), or it can name the drug itself ("amoxicillin"). Missing
        // either is the worst bug this pipeline can have.
        val allergyHits = (
            matches(context.allergies, doc.avoidIf.filter { it.contains("allerg") }) +
                context.allergies.filter { allergy ->
                    overlaps(allergy, doc.generic) ||
                        doc.aliases.any { overlaps(allergy, it) } ||
                        doc.avoidIf.any { overlaps(allergy, it) }
                }
            ).distinct()
        val medicineHits = context.currentMedicines.filter { taking ->
            doc.interactsWith.any { overlaps(taking, it) }
        }
        val duplicate = context.currentMedicines.any { taking ->
            overlaps(taking, doc.generic) || doc.aliases.any { overlaps(taking, it) }
        }

        val findings = Findings(conditionHits, allergyHits.distinct(),
                                medicineHits, duplicate)

        val step = AgentStep(
            agent = "Your history",
            looked_at = "${doc.generic} against " +
                "${context.conditions.size} conditions, " +
                "${context.allergies.size} allergies, " +
                "${context.currentMedicines.size} current medicines",
            finding = when {
                allergyHits.isNotEmpty() ->
                    "STOP - this appears to match an allergy you listed " +
                        "(${allergyHits.joinToString(", ")})."
                conditionHits.isNotEmpty() ->
                    "Flagged: usually avoided with " +
                        "${conditionHits.joinToString(", ")}. ${doc.contextNote}".trim()
                duplicate ->
                    "You may already be taking this - check before adding another."
                medicineHits.isNotEmpty() ->
                    "May interact with ${medicineHits.joinToString(", ")}. " +
                        doc.contextNote
                context.conditions.isEmpty() && context.currentMedicines.isEmpty() ->
                    "Nothing on file to cross-check against."
                else -> "No conflict found with what you have on file."
            },
            confidence = if (context.conditions.isEmpty() &&
                context.currentMedicines.isEmpty() && context.allergies.isEmpty())
                AgentStep.Confidence.NONE else AgentStep.Confidence.HIGH,
            couldNotCheck = "I only know what is in your profile and records. " +
                "Anything you have not written down, I cannot weigh.",
            flags = buildList {
                allergyHits.forEach { add("allergy: $it") }
                conditionHits.forEach { add("condition: $it") }
                medicineHits.forEach { add("interaction: $it") }
                if (duplicate) add("possible duplicate")
            },
        )
        return findings to step
    }
}

// --------------------------------------------------------------- 5. verdict
/**
 * Combines the findings into one answer.
 *
 * The severity ladder is explicit and the worst finding wins -- an allergy match
 * outranks a reassuring safety entry, always. That ordering is the whole reason
 * this is a pipeline and not a single similarity search.
 */
class AdjudicatorAgent {

    fun run(
        doc: KnowledgeDoc?,
        risk: String?,
        interactions: InteractionAgent.Findings,
        context: HistoryAgent.MotherContext,
        language: String,
        steps: List<AgentStep>,
    ): MedicineReviewResult {
        val verdict = when {
            doc == null -> Verdict.UNKNOWN
            interactions.allergyHits.isNotEmpty() -> Verdict.AVOID
            risk == "avoid" -> Verdict.AVOID
            interactions.conditionHits.isNotEmpty() -> Verdict.CAUTION
            interactions.medicineHits.isNotEmpty() || interactions.duplicate ->
                Verdict.CAUTION
            risk == "caution" -> Verdict.CAUTION
            risk == "compatible" && !context.breastfeeding -> Verdict.LIKELY_SAFE
            risk == "compatible" -> Verdict.LIKELY_SAFE
            else -> Verdict.UNKNOWN
        }

        val headline = when (verdict) {
            Verdict.AVOID -> if (interactions.allergyHits.isNotEmpty())
                "Do not take this - it matches an allergy you listed"
            else "Best avoided while breastfeeding"
            Verdict.CAUTION -> "Ask your doctor before taking this"
            Verdict.LIKELY_SAFE -> "Generally considered compatible with breastfeeding"
            Verdict.UNKNOWN -> "I could not check this one"
        }

        // Order and suppression both matter here. A parent who has just read
        // "do not take this" must not then read "generally compatible with
        // breastfeeding" in the same paragraph -- the general entry is true but
        // irrelevant once a personal contraindication has fired, and printing it
        // anyway reads as the app contradicting itself.
        val personal = steps
            .firstOrNull { it.agent == "Your history" && it.flags.isNotEmpty() }
            ?.finding
        // The detail without the risk label: the headline has already said
        // whether it is safe, and repeating that verbatim reads as a stutter.
        val generalEntry = doc?.detail?.takeIf { it.isNotBlank() }
            ?: doc?.text?.substringBefore(" Also sold as")?.trim()
        val contradicts = verdict == Verdict.AVOID || verdict == Verdict.CAUTION

        val fallback = buildString {
            append(headline)
            append(". ")
            personal?.let { append(it).append(" ") }
            if (generalEntry != null && !(contradicts && personal != null)) {
                append(generalEntry).append(" ")
            } else if (generalEntry != null) {
                append("The general guidance for ${doc?.generic ?: "this medicine"} " +
                    "is different, but your own history takes priority. ")
            }
            append("Confirm with your doctor or pharmacist.")
        }.trim()

        return MedicineReviewResult(
            verdict = verdict,
            headline = headline,
            steps = steps,
            medicine = doc,
            sources = listOfNotNull(doc?.let { "${it.title} - ${it.source}" }),
            cautions = listOfNotNull(doc?.caution?.takeIf { it.isNotBlank() }),
            summary = fallback,
            summaryFromLlm = false,
        )
    }

    companion object {
        /**
         * The prompt used to rewrite an already-decided verdict.
         *
         * Framed as a completion rather than a list of orders: a 0.5B model
         * handed imperative instructions tends to repeat them back as if they
         * were the answer.
         */
        fun refinementPrompt(result: MedicineReviewResult, language: String): String =
            buildString {
                appendLine("Notes from a medicine safety check for a " +
                    "breastfeeding mother:")
                result.steps.forEach { appendLine("${it.agent}: ${it.finding}") }
                appendLine("Overall: ${result.headline}")
                appendLine()
                appendLine(
                    "A midwife explains this to her in two or three plain " +
                        "sentences${if (language == "en") "" else " in $language"}, " +
                        "without mentioning any dose:"
                )
                appendLine()
            }
    }
}

/** Runs the five stages in order and returns the full trace. */
/**
 * Runs the stages in order.
 *
 * The verdict is produced by rules alone and returns immediately. The language
 * model is not in this path at all, and there is no longer one in the app to
 * put here. A small model was tried and produced invented causal claims and a
 * softened contradiction of its own AVOID verdict; the verdict text is
 * assembled from retrieved, sourced facts and needs no help.
 */
class MedicineReviewPipeline(
    private val index: KnowledgeIndex,
    private val records: HealthRecordDao,
) {
    private val history = HistoryAgent(records)
    private val identify = IdentifyAgent(index)
    private val safety = SafetyAgent()
    private val interactions = InteractionAgent()
    private val adjudicator = AdjudicatorAgent()

    suspend fun review(
        ocrText: String,
        profile: MotherProfile?,
        language: String = "en",
    ): MedicineReviewResult {
        val steps = mutableListOf<AgentStep>()

        val (context, historyStep) = history.run(profile)
        steps += historyStep

        val (identified, identifyStep) = identify.run(ocrText)
        steps += identifyStep

        val (risk, safetyStep) = safety.run(identified.doc)
        steps += safetyStep

        val (interactionFindings, interactionStep) =
            interactions.run(identified.doc, context)
        steps += interactionStep

        return adjudicator.run(identified.doc, risk, interactionFindings,
                               context, language, steps)
    }

}
