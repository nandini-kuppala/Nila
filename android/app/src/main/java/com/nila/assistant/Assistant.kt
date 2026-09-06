package com.nila.assistant

import android.content.Context
import com.nila.audio.ModelCard
import com.nila.data.BabyProfile
import com.nila.data.CareKind
import com.nila.data.CareRecord
import com.nila.data.EventRecord
import com.nila.data.NilaDatabase
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Answers questions using retrieval over the bundled corpus plus this baby's log.
 *
 * The design decision worth defending: this composes an answer from retrieved,
 * sourced passages rather than generating free text. A generative model that
 * hallucinates a dose or invents a reassurance in a medical context is not an
 * acceptable failure mode for something a parent consults at 2am, and a small
 * on-device model is exactly the kind that does that.
 *
 * There was a 0.5B model here, rewriting these retrieved passages into nicer
 * prose. It is gone, and the app no longer ships one at all. Twice it produced
 * text that was fluent and false -- softening a do-not-take verdict for a
 * penicillin-allergic mother, and inventing a failed newborn hearing screening
 * from a record saying the opposite -- and the grounding check could not catch
 * either, because both were assembled entirely from words in the source.
 *
 * Removing it took 25 MB of native library and about 210 MB of resident memory
 * out of the app, for a feature that was making the answers worse.
 */
class Assistant(
    private val context: Context,
    private val db: NilaDatabase,
    private val index: KnowledgeIndex,
    private val llm: LlmBridge? = null,
) {

    data class Answer(
        val text: String,
        val sources: List<String>,
        val cautions: List<String>,
        val context: List<String>,
        val kind: Kind,
        /**
         * Always false. No health-facing answer is generated -- see the note in
         * [com.nila.ui.AppState.ask]. Kept on the type so that if generation
         * is ever reintroduced, the UI has somewhere to say so.
         */
        val refinedByLlm: Boolean = false,
        /** Carried so a later refinement pass can rebuild the prompt. */
        val question: String = "",
        val logContext: List<String> = emptyList(),
        /** The retrieved passages, for the phrasing pass. Corpus text only. */
        val passages: List<String> = emptyList(),
    ) {
        enum class Kind { GUIDANCE, MEDICINE, LOG, NO_MATCH, EMERGENCY }
    }

    suspend fun ask(question: String, language: String = "en"): Answer {
        // Routing before retrieval. Some questions must never be answered with
        // the nearest matching passage.
        val route = QueryRouter.route(question)
        QueryRouter.refusal(route)?.let { text ->
            return Answer(
                text = text,
                sources = emptyList(),
                cautions = emptyList(),
                context = emptyList(),
                // Distress is styled like an emergency because it is one for
                // the person asking, even though the response is support
                // rather than an ambulance.
                kind = when (route) {
                    is QueryRouter.Route.Emergency,
                    QueryRouter.Route.CaregiverDistress -> Answer.Kind.EMERGENCY
                    else -> Answer.Kind.NO_MATCH
                },
                question = question,
            )
        }
        val subject = (route as QueryRouter.Route.Answerable).subject

        val logContext = buildLogContext()

        // Her own filed documents are searched before the general corpus. If a
        // lab report or a prescription in the shelf answers this, that answer is
        // about her specifically and beats anything generic.
        val fromRecords = searchRecords(question, subject)

        // A question about this baby's own day is answered from the log, not the
        // corpus. "When did she last feed" has one correct answer and it is not
        // in a knowledge base.
        logAnswer(question, logContext)?.let { return it }

        val medicine = index.findMedicine(question)
        if (medicine != null && looksLikeMedicineQuestion(question)) {
            return medicineAnswer(medicine, logContext, language)
        }

        // A question about the future is not filtered by present age.
        val babyAge = if (QueryRouter.isForwardLooking(question)) null
                      else db.baby().get()?.ageMonths
        val audience = when (subject) {
            QueryRouter.Subject.MOTHER -> "mother"
            QueryRouter.Subject.BABY -> "baby"
            QueryRouter.Subject.EITHER -> null
        }

        // Medicine entries are reached by naming a medicine, never by general
        // similarity -- they were outranking the documents that actually answer
        // the question. "How can I increase my milk supply" returned
        // domperidone, a prescription galactagogue, above the page on supply.
        val hits = index.retrieveGuidance(question, audience, babyAge)

        if (hits.isEmpty() && fromRecords.isEmpty()) {
            return Answer(
                text = "I don't have anything reliable on that offline. For anything " +
                    "urgent or unusual, contact your doctor rather than guessing.",
                sources = emptyList(),
                cautions = emptyList(),
                context = logContext,
                kind = Answer.Kind.NO_MATCH,
            )
        }

        // The answer first, her own documents after it.
        //
        // Records used to be prepended, and anything quoted at the top of an
        // answer reads as *being* the answer -- so "what should I eat while
        // breastfeeding" opened with a prescription for iron and an inhaler,
        // which mentions breastfeeding and answers nothing. Below the guidance
        // and under its own heading, the same record is useful context.
        val body = buildString {
            hits.forEachIndexed { i, hit ->
                if (i > 0) append("\n\n")
                append(hit.doc.text)
            }
            if (fromRecords.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("From your own records, which may be relevant:")
                fromRecords.forEach { record ->
                    append("\n\n")
                    append(record.title)
                    append(" - ")
                    append(record.extractedText.ifBlank { record.notes }.take(300))
                }
            }
        }

        // No language model in this path. Retrieval already produced a complete,
        // sourced answer; making the user wait on generation to see it was the
        // bug, not a feature. [refine] upgrades the prose afterwards.
        return Answer(
            text = body,
            sources = fromRecords.map { "Your record: ${it.title}" } +
                hits.map { "${it.doc.title} - ${it.doc.source}" },
            cautions = hits.mapNotNull { it.doc.caution.takeIf(String::isNotBlank) },
            context = logContext,
            kind = Answer.Kind.GUIDANCE,
            refinedByLlm = false,
            question = question,
            logContext = logContext,
            // Only the corpus passages, and never the records. The model is
            // handed these to phrase; see LlmBridge for why the list is so
            // carefully limited.
            passages = hits.map { it.doc.text },
        )
    }

    /**
     * Ask the model to say the same thing in fewer words.
     *
     * Returns null unless a model is loaded and its output passes grounding, so
     * the caller can treat this as a pure upgrade: keep what you have if it
     * gives you nothing. Never used for medicine verdicts.
     */
    fun phrase(answer: Answer, language: String): String? {
        if (answer.kind != Answer.Kind.GUIDANCE) return null
        val engine = llm ?: return null
        if (!engine.isReady) return null
        return engine.phrase(answer.question, answer.passages, language)
    }

    /** Answer for a photographed medicine strip, given OCR text. */
    suspend fun identifyMedicine(ocrText: String, language: String = "en"): Answer {
        val hit = index.findMedicine(ocrText)
            ?: return Answer(
                text = "I couldn't recognise a medicine name in that photo. Try a " +
                    "straighter shot of the printed name, or type it instead.",
                sources = emptyList(),
                cautions = emptyList(),
                context = emptyList(),
                kind = Answer.Kind.NO_MATCH,
            )
        return medicineAnswer(hit, buildLogContext(), language)
    }

    private suspend fun medicineAnswer(
        hit: Retrieved,
        logContext: List<String>,
        language: String,
    ): Answer {
        val doc = hit.doc
        return Answer(
            text = doc.text,
            sources = listOf("${doc.title} - ${doc.source}"),
            cautions = listOf(doc.caution),
            context = logContext,
            kind = Answer.Kind.MEDICINE,
            refinedByLlm = false,
            question = "Is this safe while breastfeeding?",
            logContext = logContext,
        )
    }

    /**
     * Is this question about the medicine that was named in it?
     *
     * [KnowledgeIndex.findMedicine] only fires on an actual drug name or brand
     * alias scoring above its own floor, so a hit is already strong evidence.
     * This is the second half: someone can mention a medicine while asking
     * something else about it, and the corpus entry answers exactly one
     * question -- whether a breastfeeding parent can take it.
     */
    private fun looksLikeMedicineQuestion(question: String): Boolean {
        val q = question.lowercase()
        return listOf("safe", "safety", "take", "taking", "took", "medicine",
                      "medication", "tablet", "tablets", "breastfeed",
                      "breastfeeding", "nursing", "feeding", "drug", "pill",
                      "capsule", "syrup", "ok to", "okay to", "can i", "should i",
                      "allowed", "avoid", "while")
            .any { it in q }
    }

    /**
     * Questions the log can answer exactly.
     *
     * Matched by keyword rather than by a model, because getting "when did she
     * last feed" wrong is worse than not answering it.
     */
    private suspend fun logAnswer(question: String, logContext: List<String>): Answer? {
        val q = question.lowercase()
        val asksLast = "last" in q || "when" in q || "how long" in q
        if (!asksLast) return null

        val kind = when {
            "feed" in q || "fed" in q || "milk" in q -> CareKind.FEED
            "nappy" in q || "diaper" in q || "change" in q -> CareKind.DIAPER
            "sleep" in q || "slept" in q || "nap" in q -> CareKind.SLEEP_START
            else -> return null
        }

        val record = db.care().latestOf(kind.name) ?: return Answer(
            text = "Nothing logged for that yet. Once you start tapping the quick " +
                "buttons on the home screen, I can answer this exactly.",
            sources = emptyList(), cautions = emptyList(),
            context = logContext, kind = Answer.Kind.LOG,
        )

        return Answer(
            text = "Last ${kind.name.lowercase().replace('_', ' ')} was " +
                "${humanAgo(record.atMs)} ago, at ${clockTime(record.atMs)}.",
            sources = listOf("Your own log"),
            cautions = emptyList(),
            context = logContext,
            kind = Answer.Kind.LOG,
        )
    }

    /**
     * Facts about this baby's day, in plain language.
     *
     * This is what separates a useful answer from a search result. "Three hours
     * since the last feed" cannot come from any corpus -- it comes from the log,
     * and it is the reason the quick-log buttons exist.
     */
    suspend fun buildLogContext(): List<String> {
        val out = mutableListOf<String>()
        val now = System.currentTimeMillis()

        db.baby().get()?.let { baby ->
            if (baby.birthDateMs > 0) out += "Baby is ${baby.ageMonths} months old"
            if (baby.healthNotes.isNotBlank()) out += "Noted: ${baby.healthNotes}"
        }

        listOf(
            CareKind.FEED to "Last feed",
            CareKind.DIAPER to "Last nappy change",
            CareKind.SLEEP_END to "Woke",
        ).forEach { (kind, label) ->
            db.care().latestOf(kind.name)?.let { out += "$label ${humanAgo(it.atMs)} ago" }
        }

        val dayAgo = now - TimeUnit.DAYS.toMillis(1)
        val cryingSeconds = db.events().cryingSecondsBetween(dayAgo, now)
        val episodes = db.events().cryEpisodesBetween(dayAgo, now)
        if (episodes > 0) {
            out += "$episodes crying episodes in the last 24 hours, " +
                "${cryingSeconds / 60} minutes in total"
        }
        return out
    }

    /**
     * Search the family's own filed documents.
     *
     * Deliberately conservative: only reasonably long query terms are used, so
     * an incidental word does not surface an unrelated lab report and make the
     * answer look authoritative about something it is not.
     */
    /**
     * Words that appear in every medical document and identify none of them.
     *
     * "How much crying is normal" was opening with her own six-week postnatal
     * check, because that report contains the word "normal" -- as every report
     * does. A record quoted at the top of an answer reads as being *about* the
     * question, so surfacing the wrong one is worse than surfacing none.
     */
    private val GENERIC_RECORD_WORDS = setOf(
        "normal", "check", "result", "results", "level", "levels", "report",
        "review", "visit", "value", "range", "within", "test", "tests",
        "patient", "advice", "advised", "follow", "history", "noted", "given",
        "months", "weeks", "months old", "clinic", "hospital", "doctor",
    )

    /**
     * Search the family's own filed documents.
     *
     * Two-term coverage, the same rule guidance retrieval uses: a record has to
     * match more than one distinctive thing about the question before it is
     * quoted. One shared word is a coincidence, and a coincidence presented as
     * "from your records" is worse than a generic answer.
     */
    private suspend fun searchRecords(
        question: String,
        subject: QueryRouter.Subject,
    ): List<com.nila.data.HealthRecord> {
        val terms = KnowledgeIndex.tokenize(question)
            .filter { it.length >= 5 && it !in GENERIC_RECORD_WORDS }
            .distinct()
        if (terms.isEmpty()) return emptyList()

        val whose = when (subject) {
            QueryRouter.Subject.MOTHER -> com.nila.data.RecordSubject.MOTHER.name
            QueryRouter.Subject.BABY -> com.nila.data.RecordSubject.BABY.name
            QueryRouter.Subject.EITHER -> null
        }

        val byId = mutableMapOf<Long, com.nila.data.HealthRecord>()
        val hitCount = mutableMapOf<Long, Int>()
        for (term in terms.take(4)) {
            db.healthRecords().search(term, whose, 3).forEach {
                byId[it.id] = it
                hitCount[it.id] = (hitCount[it.id] ?: 0) + 1
            }
        }

        val needed = if (terms.size >= 3) 2 else 1

        // Deduplicate by content, not just id. Importing the same document twice
        // is easy to do and produces an answer that repeats itself verbatim,
        // which reads as a broken app rather than a duplicated file.
        val seenText = mutableSetOf<String>()
        return byId.values
            .filter { (hitCount[it.id] ?: 0) >= needed }
            .sortedByDescending { hitCount[it.id] ?: 0 }
            .filter { seenText.add(it.extractedText.take(200).ifBlank { it.title }) }
            .take(2)
    }

    /** The reliability statement the UI is required to show alongside a cause. */
    fun modelHonesty(): String = ModelCard.honestSummary

    private fun humanAgo(thenMs: Long): String {
        val minutes = ((System.currentTimeMillis() - thenMs) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> "less than a minute"
            minutes < 60 -> "$minutes minutes"
            minutes < 60 * 24 -> {
                val h = minutes / 60
                val m = minutes % 60
                if (m == 0L) "${h}h" else "${h}h ${m}m"
            }
            else -> "${minutes / (60 * 24)} days"
        }
    }

    private fun clockTime(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(ms))
}
