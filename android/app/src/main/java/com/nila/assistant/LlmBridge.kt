package com.nila.assistant

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.Closeable
import java.io.File

/**
 * A small local model, used to *phrase* an answer retrieval has already found.
 *
 * It supplies no facts. Retrieval picks the passages; this turns them into two
 * or three sentences that answer the actual question, instead of two paragraphs
 * of reference text a parent has to read twice at 3am.
 *
 * ### What it is not allowed to see
 *
 * This was live once and was removed after it invented a failed newborn hearing
 * screening. It had been handed the mother's own health records as context,
 * found one saying the screening was *passed*, and inverted it.
 *
 * So the prompt built here contains exactly two things: the retrieved passages
 * and the question. No health records, no care log, no baby profile. A model
 * that never sees a record cannot invert one, and that is a stronger guarantee
 * than any amount of prompt instruction.
 *
 * ### What is checked afterwards
 *
 * Prompt discipline is necessary and not sufficient, so [isGrounded] rejects
 * output that invents a quantity, drifts off the source vocabulary, or -- the
 * failure that mattered -- flips the polarity of a sentence it is paraphrasing.
 * A rejected rewrite costs nothing: the caller keeps the retrieved text, which
 * was always a complete, sourced answer.
 */
class LlmBridge private constructor(
    private val context: Context,
    private val modelPath: String?,
) : Closeable {

    companion object {
        private const val TAG = "LlmBridge"

        /** Where install-model.sh and the in-app downloader put the weights. */
        private val CANDIDATES = listOf(
            "qwen2.5-0.5b-instruct.task",
            "gemma3-1b-it.task",
            "gemma-3n-E2B-it.litertlm",
        )

        /** Long enough for the retrieved passages plus a short answer. */
        private const val MAX_TOKENS = 1024

        /** The answer is a rephrasing, not an essay. */
        private const val MAX_WORDS = 70

        fun tryLoad(context: Context): LlmBridge {
            val dir = context.getExternalFilesDir("models")
            val found = CANDIDATES
                .map { File(dir, it) }
                .firstOrNull { it.exists() && it.length() > 1_000_000 }
            return LlmBridge(context.applicationContext, found?.absolutePath)
        }

        /** True when a model file is present, whether or not it has loaded yet. */
        fun isInstalled(context: Context): Boolean {
            val dir = context.getExternalFilesDir("models")
            return CANDIDATES.any {
                File(dir, it).let { f -> f.exists() && f.length() > 1_000_000 }
            }
        }
    }

    @Volatile private var engine: LlmInference? = null
    @Volatile private var failure: String? = null
    @Volatile private var attempted = false

    val isReady: Boolean get() = engine != null

    fun describe(): String = when {
        modelPath == null -> "Not installed - answers are retrieved text only"
        engine != null -> "${File(modelPath).name}, phrasing answers on this phone"
        failure != null -> "Failed to load: $failure"
        else -> "Installed, not loaded yet"
    }

    /**
     * Build the engine. Blocking and slow -- maps the weights and builds an
     * XNNPack cache -- so callers keep it off any thread that matters.
     */
    @Synchronized
    fun load(): Boolean {
        if (engine != null) return true
        if (attempted && failure != null) return false
        attempted = true
        val path = modelPath ?: return false
        return try {
            val started = System.currentTimeMillis()
            engine = LlmInference.createFromOptions(
                context,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path)
                    .setMaxTokens(MAX_TOKENS)
                    .setMaxTopK(40)
                    .build(),
            )
            Log.i(TAG, "loaded in ${System.currentTimeMillis() - started}ms")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            failure = t.message ?: t::class.java.simpleName
            engine = null
            false
        }
    }

    /**
     * Phrase [passages] as an answer to [question], or return null.
     *
     * Null on every failure path -- no model, generation error, timeout, or a
     * rewrite that failed grounding -- and the caller then shows the retrieved
     * text unchanged.
     */
    fun phrase(question: String, passages: List<String>, language: String): String? {
        val active = engine ?: return null
        if (passages.isEmpty()) return null

        val source = passages.joinToString("\n")
        val started = System.currentTimeMillis()
        val raw = try {
            LlmInferenceSession.createFromOptions(
                active,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    // Low temperature: this is a rephrasing task, and sampling
                    // creatively from a 0.5B model on health text is exactly
                    // how it starts inventing.
                    .setTemperature(0.1f)
                    .setTopK(20)
                    .build(),
            ).use { session ->
                session.addQueryChunk(buildPrompt(question, passages, language))
                session.generateResponse()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "generation failed", t)
            return null
        }

        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "generated ${raw.length} chars in ${elapsed}ms")

        val cleaned = sanitise(raw) ?: run {
            Log.w(TAG, "rejected: unusable shape")
            return null
        }
        // A small model asked to shorten something often hands it straight
        // back. That is harmless but pointless, and swapping identical text in
        // under the reader makes the screen flicker for nothing.
        if (cleaned.length > source.length * 0.85) {
            Log.i(TAG, "rejected: no shorter than the source")
            return null
        }
        if (!Grounding.isGrounded(cleaned, source)) return null
        Log.i(TAG, "phrased ${source.length} chars down to ${cleaned.length}")
        return cleaned
    }

    /**
     * The prompt.
     *
     * Written as notes-plus-question rather than a chat turn, because a 0.5B
     * instruct model handed a conversational frame starts having a conversation
     * -- greeting, offering follow-ups, asking how the baby is doing. The
     * constraints are stated as prohibitions with reasons, which these models
     * follow noticeably better than bare rules.
     */
    private fun buildPrompt(
        question: String,
        passages: List<String>,
        language: String,
    ): String = buildString {
        appendLine("Answer a parent's question using only the notes below.")
        appendLine()
        appendLine("NOTES:")
        passages.take(2).forEachIndexed { i, p ->
            appendLine("${i + 1}. ${p.trim()}")
        }
        appendLine()
        appendLine("QUESTION: ${question.trim()}")
        appendLine()
        appendLine("Rules:")
        appendLine("- Answer the QUESTION in ONE or TWO sentences. Never more.")
        appendLine("- Say only what the NOTES say. Add nothing of your own.")
        appendLine("- Never state a number, dose, age or time that is not in the NOTES.")
        appendLine("- Never negate something the NOTES state, or state something the NOTES negate.")
        appendLine("- Do not copy the NOTES. Answer the question the parent asked.")
        appendLine("- No lists, no headings, no greeting, no sign-off.")
        if (language != "en") {
            appendLine("- Reply in the language with code '$language'.")
        }
        appendLine()
        append("ANSWER:")
    }

    /**
     * Strip the scaffolding these models add around an answer.
     */
    private fun sanitise(raw: String): String? {
        var text = raw.trim()
        listOf("ANSWER:", "Answer:", "ANSWER", "Rules:", "NOTES:").forEach {
            if (text.startsWith(it)) text = text.removePrefix(it).trim()
        }
        // It sometimes continues past the answer into a fresh prompt.
        listOf("\nQUESTION:", "\nNOTES:", "\nRules:", "\nQ:").forEach {
            val cut = text.indexOf(it)
            if (cut > 0) text = text.take(cut)
        }
        text = text.replace(Regex("^[-*]\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        if (text.length < 25) return null
        if (text.split(Regex("\\s+")).size > MAX_WORDS * 2) return null
        return text
    }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}

/**
 * Whether a rewrite says only what its source said.
 *
 * Lifted out of [LlmBridge] because it is pure, and because it is the part
 * that has to be testable without a phone or a half-gigabyte model. Everything
 * it rejects, it logs a reason for.
 */
object Grounding {

    private const val TAG = "Grounding"

    /**
     * Does the rewrite say only what the source said?
     */
    fun isGrounded(generated: String, source: String): Boolean {
        val lower = generated.lowercase()
        val src = source.lowercase()

        // 1. Invented quantities. A number with a unit that is nowhere in the
        //    source is the most dangerous single thing this can produce.
        val quantity = Regex(
            """\b\d+(?:\.\d+)?\s?(?:mg|ml|mcg|g|kg|hours?|hrs?|days?|weeks?|months?|years?|times?|%)\b"""
        )
        val invented = quantity.findAll(lower)
            .map { it.value.replace(" ", "") }
            .filter { !src.replace(" ", "").contains(it) }
            .toList()
        if (invented.isNotEmpty()) {
            Log.w(TAG, "rejected: invented quantities $invented")
            return false
        }

        // 2. Vocabulary drift.
        val out = contentWords(generated)
        if (out.isEmpty()) return false
        val overlap = out.count { it in contentWords(source) }.toDouble() / out.size
        if (overlap < 0.5) {
            Log.w(TAG, "rejected: ${(overlap * 100).toInt()}% of content words " +
                "come from the source")
            return false
        }

        // 3. Polarity. The failure that took this feature out of the app was a
        //    sentence built entirely from source words that said the opposite
        //    of the source. Vocabulary overlap is blind to it by construction,
        //    so each output sentence is matched to the source sentence it most
        //    resembles and the two are required to agree about negation.
        for (sentence in sentences(generated)) {
            val words = contentWords(sentence)
            if (words.size < 4) continue
            val best = sentences(source).maxByOrNull { overlapOf(words, it) } ?: continue
            if (overlapOf(words, best) >= 0.6 &&
                isNegated(sentence) != isNegated(best)
            ) {
                Log.w(TAG, "rejected: polarity flip on '${sentence.take(60)}'")
                return false
            }
        }
        return true
    }

    private val stop = setOf(
        "the", "and", "for", "you", "your", "that", "this", "with", "are", "was",
        "were", "have", "has", "had", "can", "but", "she", "her", "his", "him",
        "they", "them", "there", "then", "from", "into", "about", "would",
        "could", "should", "very", "will", "what", "when", "which", "their",
    )

    private fun contentWords(text: String): Set<String> = text.lowercase()
        .split(Regex("[^a-z]+"))
        .filter { it.length > 3 && it !in stop }
        .toSet()

    /**
     * How much two sentences say the same thing, as a fraction of the shorter.
     *
     * Measuring against the output alone was the bug: the invented sentence
     * "the baby has not passed the newborn hearing screening, which is a sign
     * of potential hearing issues" adds enough words of its own that it covers
     * only half of itself, and the polarity check skipped it. Against the six
     * content words of the source sentence it is paraphrasing, the overlap is
     * two thirds -- which is what "this sentence is a rewrite of that one"
     * actually means.
     */
    private fun overlapOf(words: Set<String>, candidate: String): Double {
        val other = contentWords(candidate)
        if (other.isEmpty() || words.isEmpty()) return 0.0
        val shared = words.count { it in other }.toDouble()
        return maxOf(shared / words.size, shared / other.size)
    }

    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

    private val negations = setOf(
        "not", "no", "never", "cannot", "cant", "dont", "doesnt", "didnt",
        "isnt", "arent", "wasnt", "wont", "shouldnt", "without", "neither",
        "nor", "none", "nothing",
        // "avoid" and "rarely" were here and came out again: they are ordinary
        // content words in this corpus ("foods to avoid before one year"), and
        // treating them as negation markers rejected faithful rewrites.
    )

    private fun isNegated(sentence: String): Boolean = sentence.lowercase()
        .replace("'", "")
        .split(Regex("[^a-z]+"))
        .any { it in negations }
    }
