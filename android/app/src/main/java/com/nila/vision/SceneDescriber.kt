package com.nila.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File

/**
 * Looks at one frame from the cot and says what it sees.
 *
 * FastVLM-0.5B, running through LiteRT-LM on this phone. Apple's FastViTHD
 * encoder is the reason this is usable at all: 0.55 s to first token on a GPU
 * backend, against several seconds for a SigLIP-based model of the same size.
 *
 * ### Why it is asked a form and not "describe this image"
 *
 * An open-ended caption from a 0.5B model is fluent and unfalsifiable, and this
 * is a camera pointed at a sleeping infant -- the worst possible place for
 * confident invention. So the prompt asks for four fixed fields with closed
 * vocabularies, and anything outside those vocabularies is discarded rather
 * than shown. That turns the model from a narrator into a classifier that
 * happens to be able to explain itself, and a wrong answer becomes a wrong
 * *category* the parent can see and disagree with.
 *
 * ### Why it does not run continuously
 *
 * 1.7 GB resident. The always-on watch stays what it was -- face presence and
 * frame-difference motion, a few megabytes, all night -- and this is called on
 * a trigger: the parent taps to look, or the cheap watch has flagged something
 * and wants a second opinion. The engine is built on demand and released after,
 * because a phone that is holding 1.7 GB at 3am is a phone whose monitor gets
 * killed.
 *
 * ### What it must never be used for
 *
 * Breathing, heart rate, oxygen, sleep staging, or any statement about whether
 * a baby is alive or well. It reports posture and visibility, which is what a
 * camera can actually establish. See the FDA's warning letter to Owlet for what
 * happens to products that blur that line.
 */
class SceneDescriber(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "SceneDescriber"

        const val FILE_NAME = "fastvlm-0.5b.litertlm"

        /**
         * A Gemma 3n bundle, if the user has one.
         *
         * MediaPipe's LLM Inference API has had working vision modality for
         * longer than LiteRT-LM has been published, so this is the path that
         * runs today. Gemma 3n is gated behind a manual licence acceptance on
         * HuggingFace, which is why it cannot be the one-tap download -- but if
         * a file is there, it is used in preference.
         */
        val MEDIAPIPE_CANDIDATES = listOf(
            "gemma-3n-e2b-it-int4.task",
            "gemma-3n-e4b-it-int4.task",
            "gemma-3-4b-it-int4.task",
        )

        /**
         * Apple's FastVLM, converted for LiteRT. Ungated, unlike Gemma 3n,
         * which needs a licence acceptance no app can perform on a user's
         * behalf.
         */
        const val URL = "https://huggingface.co/litert-community/FastVLM-0.5B/" +
            "resolve/main/FastVLM-0.5B.litertlm"

        const val APPROX_BYTES = 1156L * 1024 * 1024

        /**
         * The question.
         *
         * Fixed fields, closed vocabularies, one free sentence at the end for
         * the parent to read. Written as a form because a 0.5B model asked an
         * open question about a nursery will answer it beautifully and wrongly.
         */
        private val PROMPT = """
            This is a photo from a baby monitor. Fill in this form about the
            baby. Use only the words offered. Do not add anything else.

            POSITION: on back / on front / on side / sitting / standing / not visible
            FACE: clear / covered / turned away / not visible
            CONCERN: none / face covered / out of cot / tangled in bedding / cannot tell
            DOING: one short sentence about what the baby is doing
        """.trimIndent()

        /** Frames are downscaled before encoding; the encoder works at 336px. */
        private const val FRAME_EDGE = 672

    /**
     * Read the form back.
     *
     * Missing POSITION is treated as a failed read rather than as "not
     * visible": a model that ignored the form entirely has told us nothing, and
     * showing "Not visible" would be inventing a finding out of its silence.
     */
    fun parse(reply: String, elapsedMs: Long): Observation? {
        fun field(name: String): String? = Regex(
            "$name\\s*[:\\-]\\s*(.+)", RegexOption.IGNORE_CASE
        ).find(reply)?.groupValues?.get(1)?.trim()?.lowercase()?.take(120)

        val position = field("POSITION") ?: return null
        val face = field("FACE") ?: ""
        val concern = field("CONCERN") ?: ""
        val doing = field("DOING")
            ?.replaceFirstChar { it.uppercase() }
            ?.take(140)
            ?: ""

        return Observation(
            position = Position.parse(position),
            face = Face.parse(face),
            concern = Concern.parse(concern),
            doing = doing,
            elapsedMs = elapsedMs,
        )
    }
    }

    /**
     * What the model reported, after the free text has been thrown away and
     * only recognised categories kept.
     */
    data class Observation(
        val position: Position,
        val face: Face,
        val concern: Concern,
        /** One sentence, shown to the parent. Never parsed, never acted on. */
        val doing: String,
        val elapsedMs: Long,
    ) {
        /** True when the frame is worth waking someone for. */
        val isConcerning: Boolean
            get() = concern != Concern.NONE && concern != Concern.CANNOT_TELL

        /** One line for a notification. */
        val headline: String get() = when (concern) {
            Concern.FACE_COVERED -> "The camera cannot see your baby's face"
            Concern.OUT_OF_COT -> "Your baby may be out of the cot"
            Concern.TANGLED -> "Your baby may be tangled in bedding"
            else -> summary
        }

        val summary: String get() = buildString {
            append(position.label)
            if (face != Face.CLEAR && face != Face.NOT_VISIBLE) {
                append(", face ").append(face.label)
            }
        }
    }

    enum class Position(val label: String) {
        ON_BACK("On their back"), ON_FRONT("On their front"),
        ON_SIDE("On their side"), SITTING("Sitting up"),
        STANDING("Standing"), NOT_VISIBLE("Not visible");

        companion object {
            fun parse(text: String) = when {
                "back" in text -> ON_BACK
                "front" in text || "tummy" in text || "stomach" in text -> ON_FRONT
                "side" in text -> ON_SIDE
                "sitting" in text || "sat" in text -> SITTING
                "standing" in text -> STANDING
                else -> NOT_VISIBLE
            }
        }
    }

    enum class Face(val label: String) {
        CLEAR("clear"), COVERED("covered"),
        TURNED_AWAY("turned away"), NOT_VISIBLE("not visible");

        companion object {
            fun parse(text: String) = when {
                "covered" in text || "blanket" in text -> COVERED
                "turned" in text || "away" in text -> TURNED_AWAY
                "clear" in text || "visible" in text && "not" !in text -> CLEAR
                else -> NOT_VISIBLE
            }
        }
    }

    enum class Concern {
        NONE, FACE_COVERED, OUT_OF_COT, TANGLED, CANNOT_TELL;

        companion object {
            fun parse(text: String) = when {
                "none" in text -> NONE
                "covered" in text -> FACE_COVERED
                "out of" in text || "climb" in text -> OUT_OF_COT
                "tangle" in text -> TANGLED
                else -> CANNOT_TELL
            }
        }
    }

    @Volatile private var engine: Engine? = null
    @Volatile private var mediapipe: com.google.mediapipe.tasks.genai.llminference.LlmInference? = null
    @Volatile private var failure: String? = null

    private fun mediapipeModel(): File? = MEDIAPIPE_CANDIDATES
        .map { File(context.getExternalFilesDir("models"), it) }
        .firstOrNull { it.exists() && it.length() > 100_000_000 }

    /**
     * LiteRT-LM declares minSdk 31 and is admitted into the build below that,
     * so this is the guard that keeps the promise. Checked before any class
     * from the library is loaded, because the failure below 31 would otherwise
     * be a NoClassDefFoundError at the moment a parent taps "Look now".
     */
    val isSupported: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    val isInstalled: Boolean get() = isSupported &&
        (mediapipeModel() != null ||
            modelFile().let { it.exists() && it.length() > 100_000_000 })
    val isLoaded: Boolean get() = engine != null

    fun describe(): String = when {
        !isSupported -> "Needs Android 12 or newer"
        !isInstalled -> "Not installed"
        mediapipe != null -> "${mediapipeModel()?.name}, loaded"
        engine != null -> "FastVLM-0.5B, loaded"
        failure != null -> "Failed to load: $failure"
        else -> "Installed, loads when you ask it to look"
    }

    private fun modelFile() = File(context.getExternalFilesDir("models"), FILE_NAME)

    /**
     * Build the engine. Seconds, and about 1.7 GB -- call it on a background
     * thread and call [close] when the screen goes away.
     */
    @Synchronized
    fun load(): Boolean {
        if (engine != null || mediapipe != null) return true
        if (!isSupported || !isInstalled) return false

        // MediaPipe first when a bundle is present: its vision modality works
        // with the runtime that is actually published, where LiteRT-LM's does
        // not yet. See the note on loadLiteRtLm.
        mediapipeModel()?.let { return loadMediaPipe(it) }

        return try {
            val started = System.currentTimeMillis()
            engine = Engine(
                EngineConfig(
                    modelPath = modelFile().absolutePath,
                    // The text side is small; the image encoder is the part
                    // that wants a GPU, and it is where the latency lives.
                    backend = Backend.CPU,
                    visionBackend = Backend.GPU,
                    // Deliberately not audioBackend. FastVLM is vision-only,
                    // and naming an audio backend makes LiteRT-LM go looking
                    // for a TF_LITE_AUDIO_ENCODER_HW section that is not in
                    // the file -- the engine then fails to build at all.
                    maxNumTokens = 1024,
                    cacheDir = context.cacheDir.absolutePath,
                )
            ).also { it.initialize() }
            Log.i(TAG, "loaded in ${System.currentTimeMillis() - started}ms")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            failure = t.message ?: t::class.java.simpleName
            engine = null
            false
        }
    }

    private fun loadMediaPipe(model: File): Boolean = try {
        val started = System.currentTimeMillis()
        mediapipe = com.google.mediapipe.tasks.genai.llminference.LlmInference
            .createFromOptions(
                context,
                com.google.mediapipe.tasks.genai.llminference.LlmInference
                    .LlmInferenceOptions.builder()
                    .setModelPath(model.absolutePath)
                    .setMaxTokens(1024)
                    .setMaxNumImages(1)
                    .build(),
            )
        Log.i(TAG, "MediaPipe ${model.name} loaded in " +
            "${System.currentTimeMillis() - started}ms")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "MediaPipe load failed", t)
        failure = t.message ?: t::class.java.simpleName
        mediapipe = null
        false
    }

    private fun lookWithMediaPipe(jpeg: ByteArray, frame: Bitmap): String {
        val engine = mediapipe!!
        return com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
            .createFromOptions(
                engine,
                com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
                    .LlmInferenceSessionOptions.builder()
                    .setTemperature(0.1f)
                    .setTopK(20)
                    .setGraphOptions(
                        com.google.mediapipe.tasks.genai.llminference.GraphOptions
                            .builder()
                            .setEnableVisionModality(true)
                            .build()
                    )
                    .build(),
            ).use { session ->
                session.addQueryChunk(PROMPT)
                session.addImage(
                    com.google.mediapipe.framework.image.BitmapImageBuilder(frame).build()
                )
                session.generateResponse()
            }
    }

    /**
     * Look at one frame.
     *
     * Blocking: `sendMessage` runs the whole prefill and decode on the calling
     * thread, so this belongs on a background dispatcher.
     *
     * @return null when the model is unsupported, not installed, fails, or
     *   answers in a shape that cannot be read -- and the caller then shows
     *   nothing rather than a guess.
     */
    fun look(frame: Bitmap): Observation? {
        val active = engine ?: if (load()) engine else null
        active ?: return null

        return try {
            val started = System.currentTimeMillis()

            // Bytes rather than a file. The frame is a picture of someone's
            // baby, and not writing it to disk at all is a stronger guarantee
            // than writing it and deleting it.
            val jpeg = ByteArrayOutputStream().use { buffer ->
                scaled(frame).use { it.value.compress(Bitmap.CompressFormat.JPEG, 88, buffer) }
                buffer.toByteArray()
            }

            // Session, not Conversation.
            //
            // Conversation refuses this model with "Unsupported model type":
            // it expects a registered chat template, and the FastVLM bundle
            // does not carry one. Session is the layer underneath, so the
            // template is written out here instead -- FastVLM is Qwen2-based,
            // hence the im_start markers.
            if (mediapipe != null) {
                val reply = lookWithMediaPipe(jpeg, frame)
                val elapsed = System.currentTimeMillis() - started
                Log.i(TAG, "reply in ${elapsed}ms: " +
                    reply.replace("\n", " | ").take(160))
                return parse(reply, elapsed)
            }

            val reply = active.createSession().use { session ->
                session.generateContent(
                    listOf(
                        InputData.Text("<|im_start|>user\n"),
                        InputData.Image(jpeg),
                        InputData.Text("\n$PROMPT<|im_end|>\n<|im_start|>assistant\n"),
                    )
                )
            }

            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "reply in ${elapsed}ms: ${reply.replace("\n", " | ").take(160)}")
            parse(reply, elapsed)
        } catch (t: Throwable) {
            Log.e(TAG, "look failed", t)
            null
        }
    }

    /** Downscale before encoding. A 12-megapixel frame is all cost and no signal. */
    private fun scaled(frame: Bitmap): Derived {
        val longest = maxOf(frame.width, frame.height)
        if (longest <= FRAME_EDGE) return Derived(frame, owned = false)
        val ratio = FRAME_EDGE.toFloat() / longest
        return Derived(
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * ratio).toInt().coerceAtLeast(1),
                (frame.height * ratio).toInt().coerceAtLeast(1),
                true,
            ),
            owned = true,
        )
    }

    private class Derived(val value: Bitmap, val owned: Boolean) : Closeable {
        override fun close() { if (owned) value.recycle() }
    }

    override fun close() {
        runCatching { engine?.close() }
        runCatching { mediapipe?.close() }
        engine = null
        mediapipe = null
    }
}
