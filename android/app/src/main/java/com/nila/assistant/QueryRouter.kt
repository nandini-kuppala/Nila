package com.nila.assistant

/**
 * Decides what a question is before trying to answer it.
 *
 * Retrieval alone answers every question the same way -- it finds the closest
 * passage and returns it, however wrong that is for the question actually asked.
 * That produced two bad answers in testing: a four-month-old's mother asking
 * "what to eat now" was shown a list of choking hazards, and "what should I
 * avoid while breastfeeding" was answered with the baby's food rules.
 *
 * So routing happens first, and it decides three things:
 *
 *   - **Is this an emergency?** Those never go through retrieval at all.
 *   - **Who is it about?** A parent and an infant have different bodies and
 *     different advice, and "I" versus "she" is usually enough to tell.
 *   - **Should we answer at all?** Dosing, diagnosis and off-topic questions get
 *     a straight refusal rather than the nearest passage.
 */
object QueryRouter {

    enum class Subject { BABY, MOTHER, EITHER }

    sealed interface Route {
        /** Red-flag wording. Skips everything and shows what to do now. */
        data class Emergency(val matched: String) : Route
    /**
     * The caregiver is describing an urge to harm the baby, or that they
     * cannot go on.
     *
     * Kept apart from [Emergency] because the response is different: this
     * person needs to hear that the feeling is common, that putting the baby
     * down somewhere safe is the right move, and who to call -- not "go to
     * A&E". Retrieval got "i feel like i might shake the baby" catastrophically
     * wrong (it returned the page on fever), which is the kind of miss that
     * cannot be left to ranking.
     */
    data object CaregiverDistress : Route
        data object Greeting : Route
        data object TooVague : Route
        data object DoseRequest : Route
        data object DiagnosisRequest : Route
        data object OutOfScope : Route
        data class Answerable(val subject: Subject) : Route
    }

    /**
     * Wording that should never be met with a search result.
     *
     * Deliberately broad. A false positive costs a parent five seconds of
     * reading something they did not need; a false negative means an app
     * returning a paragraph about normal crying to somebody whose baby is not
     * breathing.
     */
    private val EMERGENCY = mapOf(
        "not breathing" to "not breathing",
        "stopped breathing" to "not breathing",
        "isn't breathing" to "not breathing",
        "is not breathing" to "not breathing",
        "struggling to breathe" to "breathing difficulty",
        "difficulty breathing" to "breathing difficulty",
        "grunting" to "breathing difficulty",
        "turning blue" to "blue or grey colour",
        "turned blue" to "blue or grey colour",
        "going blue" to "blue or grey colour",
        "gone blue" to "blue or grey colour",
        "went blue" to "blue or grey colour",
        "blue lips" to "blue or grey colour",
        "lips are blue" to "blue or grey colour",
        "grey and floppy" to "blue or grey colour",
        "unresponsive" to "unresponsive",
        "wont wake up" to "unresponsive",
        "won't wake up" to "unresponsive",
        "won't wake" to "unresponsive",
        "wont wake" to "unresponsive",
        "will not wake" to "unresponsive",
        "floppy" to "unresponsive or floppy",
        "limp" to "unresponsive or floppy",
        "lifeless" to "unresponsive or floppy",
        "not responding" to "unresponsive or floppy",
        "wont respond" to "unresponsive or floppy",
        "won't respond" to "unresponsive or floppy",
        "not waking" to "unresponsive or floppy",
        "seizure" to "a seizure",
        "convulsion" to "a seizure",
        "fitting" to "a seizure",
        "choking" to "choking",
        "swallowed a battery" to "a swallowed button battery",
        "button battery" to "a swallowed button battery",
        "swallowed a magnet" to "a swallowed magnet",
        // Any swallowed object. Listing batteries and magnets covered the two
        // that make the news and missed coins, beads, screws and everything
        // else a baby can reach -- "baby swallowed a coin" was answered with
        // general advice about choosing safe toys.
        "swallowed a" to "a swallowed object",
        "swallowed an" to "a swallowed object",
        "swallowed the" to "a swallowed object",
        "swallowed some" to "a swallowed object",
        "has swallowed" to "a swallowed object",
        "fell off" to "a fall",
        "head injury" to "a head injury",
        // Every way a parent describes the glass test, because none of them
        // use the clinical word. "Rash that doesn't fade when pressed" reached
        // ordinary retrieval before these were added.
        "rash that doesn't fade" to "a non-blanching rash",
        "rash that doesnt fade" to "a non-blanching rash",
        "doesn't fade when pressed" to "a non-blanching rash",
        "doesnt fade when pressed" to "a non-blanching rash",
        "does not fade when pressed" to "a non-blanching rash",
        "won't fade when pressed" to "a non-blanching rash",
        "not fade under a glass" to "a non-blanching rash",
        "glass test" to "a non-blanching rash",
        "purple spots" to "a non-blanching rash",
        "non blanching" to "a non-blanching rash",
        "harm myself" to "thoughts of self-harm",
        "hurt myself" to "thoughts of self-harm",
        "hurt my baby" to "thoughts of harm",
        "kill myself" to "thoughts of self-harm",
        "end it all" to "thoughts of self-harm",
        "ending it all" to "thoughts of self-harm",
        "not want to be here" to "thoughts of self-harm",
        "better off without me" to "thoughts of self-harm",
    )

    private val GREETINGS = setOf(
        "hi", "hii", "hiii", "hey", "hello", "helo", "yo", "hai",
        "good morning", "good evening", "good night", "thanks", "thank you",
        "ok", "okay", "test", "testing", "namaste", "vanakkam",
    )

    /** First person about her own body: the strongest maternal signal there is. */
    private val MOTHER_WORDS = setOf(
        "mastitis", "engorged", "engorgement", "latch", "postnatal", "postpartum",
        "lochia", "caesarean", "csection", "perineum", "episiotomy", "nipple",
        "nipples", "supply", "lactation", "stitches",
    )

    private val MOTHER_MARKERS = listOf(
        "can i take", "can i eat", "can i drink", "should i take", "should i eat",
        "am i", "my diet", "my milk", "my supply", "my breast", "my nipple",
        "my mood", "my period", "my stitches", "my recovery", "my body",
        "while breastfeeding", "when breastfeeding", "during breastfeeding",
        "while nursing", "breastfeeding mother", "for me", "i feel", "i am",
        "mastitis", "engorged", "latch", "postnatal", "postpartum", "lochia",
        "c-section", "caesarean", "perineum", "episiotomy", "let down",
    )

    /**
     * Multi-word phrases, matched as substrings.
     */
    private val BABY_PHRASES = listOf(
        "my baby", "the baby", "my son", "my daughter", "my child",
        "my little one", "my kid",
        // Singular as well as plural: "my 4 month old" is how people write it,
        // and matching only "months old" missed every one of them.
        "months old", "month old", "weeks old", "week old", "year old",
        "tummy time", "wet nappies",
    )

    /**
     * First-person phrases that describe the speaker's own condition.
     *
     * The distinction that matters is between "I feel..." and "should I give
     * ...". Both are first person; only the first is a question about her.
     */
    /**
     * The baby doing something, rather than being mentioned.
     *
     * Separates "I'm worried my baby has a rash" -- a question about the baby --
     * from "I don't feel bonded with my baby", which is not.
     */
    private val BABY_SUBJECT = listOf(
        "my baby is", "my baby has", "my baby wont", "my baby won't",
        "my baby does", "my baby keeps", "my baby cries", "my baby sleeps",
        "the baby is", "the baby has", "she is", "she has", "he is", "he has",
        "she wont", "he wont", "she keeps", "he keeps", "is my baby",
        "does my baby", "has my baby",
    )

    private val SELF_STATE = listOf(
        "i feel", "i felt", "i dont feel", "i don't feel", "i never feel",
        "i am", "i'm", "im", "i cry", "i've been",
        "ive been", "i have been", "i keep", "i get", "i worry", "i worried",
        "i can't", "i cant", "i haven't", "i havent", "i don't know why",
        "i dont know why", "i think i", "my own", "for me", "about me",
        "my mood", "my body", "my breast", "my breasts", "my nipples",
        "my milk", "my supply", "my stitches", "my recovery", "my sleep",
        "my diet", "myself",
    )

    /**
     * Single words, matched against tokens rather than as substrings.
     *
     * This distinction is not pedantry. Matching "he " as a substring makes
     * "since the birth" a question about the baby, because "the " contains
     * "he ". That silently routed a mother asking about her own mood to the
     * infant corpus.
     */
    private val BABY_WORDS = setOf(
        "she", "he", "her", "him", "hers", "baby", "babies", "infant", "infants",
        "newborn", "newborns", "toddler",
        "nappy", "nappies", "diaper", "diapers", "weaning", "solids", "formula",
        "vaccination", "vaccinations", "immunisation", "milestone", "milestones",
        "teething", "crawling", "rolling", "cot", "crib",
    )

    private val DOSE = listOf(
        "how much should i give", "how many ml", "how many mg", "what dose",
        "what dosage", "how much paracetamol", "how much ibuprofen",
        "how many drops", "correct dose", "right dose", "dosage for",
        "dose for", "dose of", "how much medicine", "how much syrup",
    )

    private val DIAGNOSIS = listOf(
        "does my baby have", "do i have", "is it cancer", "is this autism",
        "diagnose", "what disease", "what condition does",
    )

    /**
     * Plainly nothing to do with an infant or a new parent.
     *
     * Split by how each is matched, and the split is load-bearing: "code" as a
     * substring is inside "codeine", so asking whether codeine is safe while
     * breastfeeding was refused as off-topic. Single words are matched against
     * whole tokens; only genuine multi-word phrases are matched as substrings.
     */
    private val OFF_TOPIC_WORDS = setOf(
        "weather", "football", "cricket", "stock", "stocks", "bitcoin", "crypto",
        "movie", "movies", "code", "python", "javascript", "kotlin", "sql",
        "election", "horoscope",
        // Sport and entertainment, because they are the first thing anyone
        // reaches for when testing whether an assistant knows its limits --
        // and because retrieval cannot catch them. "What's the score in the
        // India match" shares exactly one word with the corpus, "India", and
        // it happens to sit in a document title where it scores highly.
        "score", "scores", "match", "matches", "ipl", "tournament", "league",
        "song", "lyrics", "actor", "actress", "netflix", "recipe", "restaurant",
    )

    private val OFF_TOPIC_PHRASES = listOf(
        "cricket score", "recipe for cake", "who is the president",
        "capital of", "translate this", "write me a", "write a poem",
        "tell me a joke",
    )

    /**
     * Single words that are a question only in the asker's head.
     *
     * "help" on its own is the commonest of these and used to reach retrieval,
     * where it matched nothing in particular and produced a confident-looking
     * answer about whatever shared the word.
     */
    private val VAGUE = setOf(
        "help", "info", "information", "question", "advice", "tell", "what",
        "why", "how", "please", "hmm", "test", "testing", "asd", "abc",
    )

    /**
     * Phrases that name an emergency word without describing one.
     *
     * "What foods are a choking risk" is a sensible question about weaning that
     * was being answered with "call an ambulance". Asking which toys are a
     * hazard is the same shape. The word is present; the emergency is not.
     */
    private val HYPOTHETICAL = listOf(
        "choking hazard", "choking risk", "choking hazards", "choking risks",
        "risk of choking", "hazard", "which foods", "what foods", "what food",
        "foods are", "avoid to prevent", "prevent choking", "how do i know if a",
        "is a toy", "toy is", "reduce the risk", "signs of",
    )

    /**
     * Someone telling us they might hurt the baby, or cannot carry on.
     *
     * Checked before everything, including emergencies, because the wording
     * overlaps with both and the supportive answer is the right one either way.
     */
    private val DISTRESS = listOf(
        "shake the baby", "shake her", "shake him", "hurt the baby",
        "hurt my baby", "harm my baby", "harm the baby", "hit the baby",
        "throw the baby", "drop the baby on purpose", "might hurt",
        "cant do this anymore", "can't do this anymore", "cant cope anymore",
        "want to run away", "regret having", "resent the baby",
        "scared of what i might do", "scared i will hurt",
    )

    /**
     * Named conditions, for the attribution rule below.
     *
     * The list is short and clinical on purpose: it exists to catch "is this
     * eczema", not to refuse every sentence containing a body part.
     */
    private val CONDITIONS = setOf(
        "autism", "eczema", "allergy", "allergic", "reflux", "meningitis",
        "asthma", "jaundice", "infection", "pneumonia", "sepsis", "adhd",
        "depression", "thrush", "epilepsy", "cancer", "diabetes", "anaemia",
        "anemia", "intolerance", "disorder", "syndrome", "disease",
    )

    /**
     * Phrases that attribute a condition to someone.
     *
     * "What is colic" must still be answered; "is this colic" must not. The
     * difference is not the noun, it is whether the question asks us to apply
     * the label to a particular child.
     */
    private val ATTRIBUTION = listOf(
        "is this", "is it", "could this be", "could it be", "could she have",
        "could he have", "does she have", "does he have", "do i have",
        "has she got", "has he got", "do you think its", "do you think it is",
        "sounds like", "looks like", "am i", "is my baby",
    )

    /** Any way of asking for an amount to administer. */
    private val DOSE_WORDS = setOf(
        "dose", "doses", "dosage", "dosages", "dosing", "posology",
    )


    /**
     * Phrase matching that respects word boundaries.
     *
     * Every list in this file was matched with plain `contains`, and that is a
     * trap this file has now fallen into twice. The first time, "he " inside
     * "the birth" routed a mother's question about her own mood to the infant
     * corpus. The second, "im " inside "calm **him** down" made a question
     * about settling a baby look like a question about her.
     *
     * Both queries and phrases are normalised the same way -- apostrophes
     * dropped, everything else collapsed to single spaces, padded at both ends
     * -- so "won't wake" and "wont wake" are one entry's worth of matching and
     * a phrase can only match whole words.
     */
    private fun normalise(text: String): String =
        " " + text.lowercase().replace("'", "")
            .replace(Regex("[^a-z0-9]+"), " ").trim() + " "

    private fun String.hasPhrase(phrase: String): Boolean =
        contains(normalise(phrase))

    fun route(rawQuestion: String): Route {
        val raw = rawQuestion.trim().lowercase()
        if (raw.isBlank()) return Route.TooVague
        val q = normalise(raw)

        if (DISTRESS.any { q.hasPhrase(it) }) return Route.CaregiverDistress

        // Emergencies first, before anything can shorten the path -- but only
        // when the phrase describes something happening rather than something
        // being asked about.
        if (HYPOTHETICAL.none { q.hasPhrase(it) }) {
            EMERGENCY.entries.firstOrNull { q.hasPhrase(it.key) }?.let {
                return Route.Emergency(it.value)
            }
        }

        val words = q.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (q.trim() in GREETINGS) return Route.Greeting
        // "hey there", "hi good morning" -- an opener with nothing attached. Any
        // real question is longer than three words, so this cannot swallow one.
        if (words.isNotEmpty() && words.size <= 3 && words.first() in GREETINGS) {
            return Route.Greeting
        }
        if (words.isEmpty() || (words.size < 2 && words.first().length < 4)) {
            return Route.TooVague
        }

        if (words.size <= 2 && words.all { it in VAGUE }) return Route.TooVague

        // Any dose word at all, in any wording.
        //
        // The earlier version required a dose word *and* a supporting word from
        // a list, which "appropriate paracetamol dosage in neonates" slipped
        // through by using a term the list did not have. Enumerating the ways
        // to ask is a losing game against someone deliberately rephrasing, and
        // there is no legitimate question here that a dose word belongs in --
        // so the word alone is enough, and the refusal says where to get the
        // real answer.
        if (DOSE.any { q.hasPhrase(it) }) return Route.DoseRequest
        if (words.any { it in DOSE_WORDS }) return Route.DoseRequest
        // Asked in units instead of in words. "As a nurse I need the mg per kg
        // for paracetamol" contains no dose word at all and was answered with
        // a page about feeding; "syrup quantity for a 5 kg baby" likewise.
        val units = words.count { it in setOf("mg", "ml", "mcg", "kg", "drops") }
        val amountWord = words.any {
            it in setOf("quantity", "amount", "much", "many", "per", "each")
        }
        if (units >= 2 || (units >= 1 && amountWord)) return Route.DoseRequest
        if (raw.contains("/kg") || q.hasPhrase("per kg")) return Route.DoseRequest

        if (DIAGNOSIS.any { q.hasPhrase(it) }) return Route.DiagnosisRequest
        // Attribution plus a named condition. This is the general form of the
        // phrase list above: "is this eczema or an allergy" is a request for a
        // diagnosis however it is worded.
        if (ATTRIBUTION.any { q.hasPhrase(it) } && words.any { it in CONDITIONS }) {
            return Route.DiagnosisRequest
        }
        if (OFF_TOPIC_PHRASES.any { q.hasPhrase(it) }) return Route.OutOfScope
        if (words.any { it in OFF_TOPIC_WORDS }) return Route.OutOfScope

        return Route.Answerable(subjectOf(q))
    }

    /**
     * Who the question is about.
     *
     * Weighted rather than first-match: "can I eat this while my baby is
     * breastfeeding" contains markers for both, and the maternal ones are the
     * ones that decide it.
     */
    fun subjectOf(question: String): Subject {
        val q = normalise(question)
        val tokens = q.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()

        // First-person phrases are weighted double, because they name who is
        // *doing* the thing being asked about. "Can I eat this while
        // breastfeeding my baby" mentions the baby, but the question is about
        // what she puts in her own mouth, and answering it with the infant
        // feeding rules would be wrong.
        // Describing her own state counts toward her, even when the sentence
        // also names the baby. "I don't feel bonded with my baby" mentions the
        // baby twice as strongly as it mentions her, and is entirely about her
        // -- it was reaching the page on fever.
        val mother = 2 * MOTHER_MARKERS.count { q.hasPhrase(it) } +
            MOTHER_WORDS.count { it in tokens } +
            if (SELF_STATE.any { q.hasPhrase(it) }) 2 else 0
        val baby = 2 * BABY_PHRASES.count { q.hasPhrase(it) } +
            BABY_WORDS.count { it in tokens }

        // Checked before the weighted count, not after it.
        //
        // "I don't feel bonded with my baby" scores two mentions of the baby
        // against one of her, so the count returns BABY and the self-state rule
        // below never ran -- the question reached the infant corpus and was
        // answered with the page on fever. A first-person description of her
        // own state settles whose question it is on its own, unless the baby is
        // the one doing something.
        val selfState = SELF_STATE.any { q.hasPhrase(it) }
        val babyIsSubject = BABY_SUBJECT.any { q.hasPhrase(it) }
        if (selfState && !babyIsSubject) return Subject.MOTHER

        if (mother > baby) return Subject.MOTHER
        if (baby > mother) return Subject.BABY

        return Subject.EITHER
    }

    /**
     * Is the question about the future rather than about now?
     *
     * Age filtering exists so a four-month-old's parent is not shown advice for
     * a baby on solids. But "when do babies start solids" is a question *about*
     * that future, and suppressing the answer because it does not apply yet is
     * exactly backwards -- it is the moment the answer is most wanted.
     */
    fun isForwardLooking(question: String): Boolean {
        val q = normalise(question)
        return listOf(
            "when do", "when can", "when should", "when will", "when is it",
            "at what age", "what age", "how old", "how soon", "start giving",
            "ready for", "next stage", "later on",
        ).any { q.hasPhrase(it) }
    }

    /** Wording for the routes that never reach retrieval. */
    fun refusal(route: Route): String? = when (route) {
        Route.CaregiverDistress ->
            "Put the baby down somewhere safe - the cot, on their back - close " +
                "the door, and walk away for a few minutes. A baby left crying " +
                "alone in a cot is safe. A baby who is shaken is not, and it " +
                "takes very little.\n\n" +
                "What you are feeling is common and it is not a sign that you " +
                "are a bad parent. Call someone now: a partner, a relative, a " +
                "friend, your health visitor or doctor. In India, iCall is on " +
                "9152987821 and Tele-MANAS on 14416.\n\n" +
                "If you think you might act on it, treat that as an emergency " +
                "and call your emergency number."
        is Route.Emergency ->
            "This sounds like ${route.matched}. Do not wait for an app.\n\n" +
                "Call your emergency number now, or go to the nearest emergency " +
                "department. If your baby is choking and cannot cry or breathe, " +
                "start choking first aid immediately."
        Route.Greeting ->
            "Hello. Ask me about feeding, sleep, crying, or whether a medicine is " +
                "safe while you are breastfeeding. You can ask about yourself as " +
                "well as your baby."
        Route.TooVague ->
            "Could you say a bit more? A few words about what you want to know " +
                "will get you a better answer than a single one."
        Route.DoseRequest ->
            "I will not give a dose. Doses depend on weight and on the exact " +
                "product, and getting one wrong in an infant is dangerous. Ask your " +
                "pharmacist or doctor, or read the label on the bottle you have.\n\n" +
                "I can tell you whether a medicine is generally compatible with " +
                "breastfeeding - try the Scan tab."
        Route.DiagnosisRequest ->
            "I cannot diagnose anything, and an app that tried would be doing you " +
                "a disservice. I can tell you what is usually normal, and which " +
                "signs are worth a doctor's attention today."
        Route.OutOfScope ->
            "That is outside what I know. I only cover infant care and the health " +
                "of a breastfeeding parent, using information stored on this phone."
        is Route.Answerable -> null
    }
}
