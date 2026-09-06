package com.nila.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PP-OCRv4, running on this phone.
 *
 * This is the engine the Nurture-Sync pipeline used server-side, brought
 * on-device. It is three models in sequence, and the middle one is the reason
 * it is here at all:
 *
 *  1. **Detection** (DBNet) segments the image into text lines. It finds
 *     *rotated* boxes, so a strip photographed at forty degrees is cropped
 *     upright rather than read through a skewed axis-aligned window.
 *  2. **Angle classification** decides whether each cropped line is upside
 *     down. This is `use_angle_cls=True` in the original pipeline and it is
 *     what makes a torn blister sheet photographed any-which-way readable.
 *  3. **Recognition** (SVTR-LCNet + CTC) reads each line.
 *
 * ML Kit, which the app used alone before this, has no equivalent of step 1's
 * rotation or step 2 at all: it assumes roughly upright text and returns
 * nothing when that assumption breaks. Both engines still run -- see
 * [com.nila.assistant.TextScanner] -- because they fail on different images.
 *
 * Everything is local. The models ship inside the APK; nothing is fetched and
 * no image leaves the device.
 */
class PaddleOcr private constructor(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "PaddleOcr"

        @Volatile private var instance: PaddleOcr? = null

        /**
         * One engine per process.
         *
         * Learned the hard way: a [PaddleOcr] per caller meant a test suite that
         * scanned a dozen images accumulated three ONNX sessions each, reached
         * 764 MB resident and was killed by the low-memory killer mid-run. The
         * weights are read-only and the sessions are thread-safe, so there is no
         * reason for more than one.
         */
        fun shared(context: Context): PaddleOcr =
            instance ?: synchronized(this) {
                instance ?: PaddleOcr(context.applicationContext).also { instance = it }
            }

        private const val DET_ASSET = "ppocr/ppocr_det.onnx"
        private const val CLS_ASSET = "ppocr/ppocr_cls.onnx"
        private const val REC_ASSET = "ppocr/ppocr_rec.onnx"
        private const val KEYS_ASSET = "ppocr/ppocr_keys.txt"

        /** Detector input is padded to a multiple of this. Fixed by the network. */
        private const val STRIDE = 32

        /**
         * Upscale small photos to at least this on the short side.
         *
         * PP-OCR is trained on text of a certain pixel height; a 600px-wide
         * photo of a strip has characters far below it and the detector simply
         * does not fire. Upsampling is not free information, but it puts the
         * strokes back in the size range the network expects.
         */
        private const val MIN_SIDE = 736

        /**
         * And cap the long side. A 12-megapixel photo would otherwise be fed in
         * at full resolution, which on a mid-range phone is tens of seconds and
         * an out-of-memory risk for no accuracy gain.
         */
        private const val MAX_SIDE = 1280

        private const val REC_HEIGHT = 48
        private const val REC_BASE_WIDTH = 320
        private const val CLS_HEIGHT = 48
        private const val CLS_WIDTH = 192
        private const val CLS_THRESHOLD = 0.9f

        /** Below this the recogniser is guessing; the line is dropped. */
        private const val TEXT_SCORE = 0.5f
    }

    data class Line(val text: String, val confidence: Float, val box: List<Pt>)

    data class Result(
        val lines: List<Line>,
        val elapsedMs: Long,
        val boxesFound: Int,
    ) {
        val text: String get() = lines.joinToString(" ") { it.text }
    }

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val options: OrtSession.SessionOptions
        get() = OrtSession.SessionOptions().apply {
            // Two threads. One leaves the detector slower than it needs to be;
            // four contends with the audio thread, which on this app is the one
            // job that must never be starved.
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

    private var detSession: OrtSession? = null
    private var clsSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var charset: List<String> = emptyList()

    @Volatile private var loadFailure: String? = null

    /** True once the three models are resident and ready to run. */
    val isReady: Boolean get() = detSession != null && recSession != null

    fun describe(): String = when {
        isReady -> "PP-OCRv4 (detection + angle + recognition), on this phone"
        loadFailure != null -> "Unavailable: $loadFailure"
        else -> "Not loaded yet"
    }

    /**
     * Load the models.
     *
     * Called off the main thread. Sessions are built from files rather than
     * from byte arrays so the runtime can memory-map the weights instead of
     * holding a second copy on the Java heap -- the recogniser alone is 11 MB.
     */
    @Synchronized
    fun load(): Boolean {
        if (isReady) return true
        if (loadFailure != null) return false
        return try {
            val started = System.currentTimeMillis()
            detSession = env.createSession(materialise(DET_ASSET).absolutePath, options)
            clsSession = runCatching {
                env.createSession(materialise(CLS_ASSET).absolutePath, options)
            }.onFailure {
                // The angle classifier is an optimisation, not a requirement.
                Log.w(TAG, "angle classifier unavailable: ${it.message}")
            }.getOrNull()
            recSession = env.createSession(materialise(REC_ASSET).absolutePath, options)
            charset = loadCharset()
            Log.i(TAG, "loaded in ${System.currentTimeMillis() - started}ms, " +
                "${charset.size} characters")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            loadFailure = t.message ?: t::class.java.simpleName
            close()
            false
        }
    }

    /**
     * The charset is `blank + dictionary + space`, in that order.
     *
     * The trailing space is not decoration: PaddleOCR appends it so the CTC
     * head can emit word gaps, and leaving it off shifts every index by one
     * past the last dictionary entry.
     */
    private fun loadCharset(): List<String> {
        val keys = context.assets.open(KEYS_ASSET).bufferedReader().readLines()
        return buildList(keys.size + 2) {
            add("")
            addAll(keys)
            add(" ")
        }
    }

    private fun materialise(asset: String): File {
        val out = File(context.filesDir, asset.substringAfterLast('/'))
        if (out.exists() && out.length() > 0) return out
        context.assets.open(asset).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    /** Read every line of text in the image. */
    fun read(bitmap: Bitmap): Result {
        if (!isReady && !load()) return Result(emptyList(), 0, 0)
        val started = System.currentTimeMillis()

        val source = Rgb.from(bitmap)
        val boxes = detect(source)

        val lines = ArrayList<Line>(boxes.size)
        for (box in boxes) {
            var crop = cropRotated(source, box.corners) ?: continue
            if (shouldRotate(crop)) crop = crop.rotate180()
            val (text, confidence) = recognise(crop)
            if (text.isBlank() || confidence < TEXT_SCORE) continue
            lines += Line(text, confidence, box.corners)
        }

        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "read ${lines.size}/${boxes.size} boxes in ${elapsed}ms")
        return Result(lines, elapsed, boxes.size)
    }

    // ------------------------------------------------------------ detection

    private fun detect(source: Rgb): List<DbPostProcess.Box> {
        val session = detSession ?: return emptyList()

        val scale = detectionScale(source.width, source.height)
        val width = ((source.width * scale / STRIDE).roundToInt() * STRIDE)
            .coerceAtLeast(STRIDE)
        val height = ((source.height * scale / STRIDE).roundToInt() * STRIDE)
            .coerceAtLeast(STRIDE)

        val resized = source.resized(width, height)
        val input = FloatBuffer.allocate(3 * width * height)
        // BGR, and normalised to [-1, 1]. Both come from the training recipe --
        // OpenCV hands Paddle images in BGR, and feeding RGB here quietly costs
        // a chunk of recall rather than failing outright.
        fill(input, resized, bgr = true, mean = 0.5f, std = 0.5f)

        val prob = FloatArray(width * height)
        OnnxTensor.createTensor(
            env, input, longArrayOf(1, 3, height.toLong(), width.toLong())
        ).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                // Straight out of the native buffer. Asking for `.value` here
                // materialises a boxed Array<Array<Array<FloatArray>>> -- one
                // Java object per output row, which for a 1280-pixel image is a
                // thousand allocations and several megabytes for no benefit.
                (out[0] as OnnxTensor).floatBuffer.get(prob)
            }
        }

        return DbPostProcess.boxesFrom(
            prob = prob,
            width = width,
            height = height,
            scaleX = source.width.toDouble() / width,
            scaleY = source.height.toDouble() / height,
            srcWidth = source.width,
            srcHeight = source.height,
        )
    }

    /**
     * Short side up to [MIN_SIDE], long side down to [MAX_SIDE], with the cap
     * winning when they disagree.
     */
    private fun detectionScale(width: Int, height: Int): Double {
        val shortSide = minOf(width, height).toDouble()
        val longSide = max(width, height).toDouble()
        var scale = if (shortSide < MIN_SIDE) MIN_SIDE / shortSide else 1.0
        if (longSide * scale > MAX_SIDE) scale = MAX_SIDE / longSide
        return scale
    }

    // --------------------------------------------------------- angle + crop

    /**
     * Is this line upside down?
     *
     * The detector finds the box but not which end is the top -- 0 and 180
     * degrees produce identical rectangles. Without this step, half the lines
     * on a strip photographed the "wrong" way up come back as inverted
     * gibberish that no amount of fuzzy matching recovers.
     */
    private fun shouldRotate(crop: Rgb): Boolean {
        val session = clsSession ?: return false
        val prepared = letterbox(crop, CLS_HEIGHT, CLS_WIDTH)
        val input = FloatBuffer.allocate(3 * CLS_HEIGHT * CLS_WIDTH)
        fill(input, prepared, bgr = true, mean = 0.5f, std = 0.5f)

        return OnnxTensor.createTensor(
            env, input,
            longArrayOf(1, 3, CLS_HEIGHT.toLong(), CLS_WIDTH.toLong())
        ).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                val scores = (out[0] as OnnxTensor).floatBuffer
                scores.limit() >= 2 && scores.get(1) > CLS_THRESHOLD
            }
        }
    }

    /**
     * Cut a rotated box out of the photo and lay it flat.
     *
     * The box is a true rectangle, so mapping it to an upright crop is an
     * affine transform -- the general perspective warp PaddleOCR applies
     * reduces to exactly this when the quad has parallel sides.
     */
    private fun cropRotated(source: Rgb, corners: List<Pt>): Rgb? {
        val p0 = corners[0]; val p1 = corners[1]
        val p2 = corners[2]; val p3 = corners[3]

        val cropWidth = max(dist(p0, p1), dist(p2, p3)).toInt()
        val cropHeight = max(dist(p0, p3), dist(p1, p2)).toInt()
        if (cropWidth < 2 || cropHeight < 2) return null

        val out = Rgb(cropWidth, cropHeight)
        val ux = (p1.x - p0.x) / cropWidth
        val uy = (p1.y - p0.y) / cropWidth
        val vx = (p3.x - p0.x) / cropHeight
        val vy = (p3.y - p0.y) / cropHeight

        for (y in 0 until cropHeight) {
            for (x in 0 until cropWidth) {
                val sx = p0.x + x * ux + y * vx
                val sy = p0.y + x * uy + y * vy
                out.setBilinear(x, y, source, sx, sy)
            }
        }

        // A tall, narrow crop is a line of vertical text, or a horizontal one
        // the detector caught side-on. Either way the recogniser wants it wide.
        return if (cropHeight.toDouble() / cropWidth >= 1.5) out.rotate90() else out
    }

    private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    // ---------------------------------------------------------- recognition

    private fun recognise(crop: Rgb): Pair<String, Float> {
        val session = recSession ?: return "" to 0f

        val ratio = crop.width.toDouble() / crop.height
        // PaddleOCR pads every crop out to a common width so a batch can share
        // one tensor. Kept even at batch size one, because the network saw that
        // padding during training and reproducing it is free.
        val paddedWidth = max(REC_BASE_WIDTH, ceil(REC_HEIGHT * ratio).toInt())
        val prepared = letterbox(crop, REC_HEIGHT, paddedWidth)

        val input = FloatBuffer.allocate(3 * REC_HEIGHT * paddedWidth)
        fill(input, prepared, bgr = true, mean = 0.5f, std = 0.5f)

        return OnnxTensor.createTensor(
            env, input,
            longArrayOf(1, 3, REC_HEIGHT.toLong(), paddedWidth.toLong())
        ).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                val tensorOut = out[0] as OnnxTensor
                val shape = tensorOut.info.shape          // [1, timesteps, classes]
                ctcDecode(
                    tensorOut.floatBuffer,
                    steps = shape[1].toInt(),
                    classes = shape[2].toInt(),
                )
            }
        }
    }

    /**
     * Greedy CTC decoding.
     *
     * Take the top class per timestep, drop blanks, collapse runs. Confidence
     * is the mean probability of the timesteps that actually contributed a
     * character -- averaging over the blanks instead would report near-certainty
     * for every crop, since most timesteps in a short word are blank.
     */
    private fun ctcDecode(
        logits: java.nio.FloatBuffer,
        steps: Int,
        classes: Int,
    ): Pair<String, Float> {
        val text = StringBuilder()
        var probSum = 0f
        var kept = 0
        var previous = -1

        for (step in 0 until steps) {
            val base = step * classes
            var best = 0
            var bestScore = logits.get(base)
            for (i in 1 until classes) {
                val v = logits.get(base + i)
                if (v > bestScore) { bestScore = v; best = i }
            }
            if (best != 0 && best != previous) {
                text.append(charset.getOrElse(best) { "" })
                probSum += bestScore
                kept++
            }
            previous = best
        }
        return text.toString().trim() to if (kept == 0) 0f else probSum / kept
    }

    // ------------------------------------------------------------- plumbing

    /** Resize to fit [height] and pad to [width] with mid-grey, as PaddleOCR does. */
    private fun letterbox(crop: Rgb, height: Int, width: Int): Rgb {
        val ratio = crop.width.toDouble() / crop.height
        val scaledWidth = minOf(width, ceil(height * ratio).toInt()).coerceAtLeast(1)
        val scaled = crop.resized(scaledWidth, height)
        if (scaledWidth == width) return scaled

        // 128 is what zero becomes after the (x/255 - 0.5) / 0.5 normalisation,
        // so padding here is exactly the zero-padding the reference does after it.
        val padded = Rgb(width, height, fill = 0xFF808080.toInt())
        padded.blit(scaled, 0, 0)
        return padded
    }

    private fun fill(
        buffer: FloatBuffer,
        image: Rgb,
        bgr: Boolean,
        mean: Float,
        std: Float,
    ) {
        buffer.rewind()
        val planeSize = image.width * image.height
        val order = if (bgr) intArrayOf(0, 8, 16) else intArrayOf(16, 8, 0)
        for (channel in 0 until 3) {
            val shift = order[channel]
            for (i in 0 until planeSize) {
                val value = (image.pixels[i] shr shift) and 0xFF
                buffer.put(channel * planeSize + i, (value / 255f - mean) / std)
            }
        }
        buffer.rewind()
    }

    override fun close() {
        synchronized(PaddleOcr) { if (instance === this) instance = null }
        runCatching { detSession?.close() }
        runCatching { clsSession?.close() }
        runCatching { recSession?.close() }
        detSession = null
        clsSession = null
        recSession = null
    }
}
