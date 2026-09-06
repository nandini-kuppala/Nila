package com.nila.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Getting a photographed medicine strip into a state OCR can actually read.
 *
 * This exists because a real Calpol box came back unreadable while a cleaner
 * photo of the same medicine worked. The difference was not the text -- it was
 * a diagonal watermark, low contrast between silver foil and white print, and
 * the name occupying a small part of a large frame.
 *
 * The reference implementation this replaces used PaddleOCR server-side with
 * angle classification, which handles that natively. ML Kit runs on the phone
 * and does not, so the image has to be prepared for it instead. Each step below
 * targets a specific way a strip photo defeats a recogniser:
 *
 *   upscale        small text needs roughly 16px of height to be read at all
 *   grey + stretch foil-on-white and print-on-silver are low-contrast
 *   sharpen        phone cameras soften small print
 *   rotate         a strip photographed sideways is common and cheap to retry
 */
object ImagePrep {

    /** Below this, ML Kit's detector starts missing small print entirely. */
    private const val MIN_EDGE = 1400
    /** Above this the recogniser gets slower with no accuracy gain. */
    private const val MAX_EDGE = 2600

    /**
     * Scale so the long edge lands in a range the recogniser likes.
     *
     * Upscaling adds no information, but ML Kit's text detector has a minimum
     * height it can resolve, and interpolated pixels are enough to clear it.
     */
    fun normaliseSize(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height)
        val scale = when {
            longest < MIN_EDGE -> MIN_EDGE.toFloat() / longest
            longest > MAX_EDGE -> MAX_EDGE.toFloat() / longest
            else -> return source
        }
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Greyscale with a percentile contrast stretch.
     *
     * Percentiles rather than min/max: a single specular highlight off foil, or
     * one black pixel, would otherwise anchor the range and leave the actual
     * print compressed into a few levels. Clipping the extreme 2% throws away
     * the outliers and spends the full range on the text.
     */
    fun enhance(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val histogram = IntArray(256)
        val grey = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val g = (
                77 * ((p shr 16) and 0xFF) +
                    150 * ((p shr 8) and 0xFF) +
                    29 * (p and 0xFF)
                ) shr 8
            grey[i] = g.toByte()
            histogram[g]++
        }

        val clip = (pixels.size * 0.02).toInt().coerceAtLeast(1)
        var low = 0
        var acc = 0
        while (low < 255 && acc + histogram[low] < clip) { acc += histogram[low]; low++ }
        var high = 255
        acc = 0
        while (high > 0 && acc + histogram[high] < clip) { acc += histogram[high]; high-- }
        if (high - low < 16) { low = 0; high = 255 }   // near-flat image, leave it

        val span = (high - low).coerceAtLeast(1)
        val lut = IntArray(256) { v ->
            (((v - low) * 255) / span).coerceIn(0, 255)
        }
        for (i in pixels.indices) {
            val v = lut[grey[i].toInt() and 0xFF]
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Unsharp mask, done as a separable box blur subtracted from the original.
     *
     * A true Gaussian would be marginally better and several times slower; on
     * print this size the difference is invisible and the speed is not.
     */
    fun sharpen(source: Bitmap, amount: Float = 0.8f): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val grey = IntArray(pixels.size) { pixels[it] and 0xFF }
        val blurred = boxBlur(grey, w, h, radius = 2)

        for (i in pixels.indices) {
            val v = (grey[i] + amount * (grey[i] - blurred[i])).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val horizontal = IntArray(src.size)
        val window = radius * 2 + 1
        for (y in 0 until h) {
            var sum = 0
            val row = y * w
            for (x in -radius..radius) sum += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                horizontal[row + x] = sum / window
                sum -= src[row + (x - radius).coerceIn(0, w - 1)]
                sum += src[row + (x + radius + 1).coerceIn(0, w - 1)]
            }
        }
        val out = IntArray(src.size)
        for (x in 0 until w) {
            var sum = 0
            for (y in -radius..radius) sum += horizontal[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / window
                sum -= horizontal[(y - radius).coerceIn(0, h - 1) * w + x]
                sum += horizontal[(y + radius + 1).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    fun rotate(source: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * The centre of the frame, enlarged.
     *
     * People centre the thing they are photographing, and the name is usually
     * the largest print on it. Cropping in and upscaling gives the recogniser a
     * second look at the most likely text with more pixels per character --
     * which is often what rescues a box shot from across a table.
     */
    fun centreCrop(source: Bitmap, fraction: Float = 0.62f): Bitmap {
        val cw = (source.width * fraction).roundToInt().coerceAtLeast(1)
        val ch = (source.height * fraction).roundToInt().coerceAtLeast(1)
        val x = (source.width - cw) / 2
        val y = (source.height - ch) / 2
        val cropped = Bitmap.createBitmap(source, x, y, cw, ch)
        return normaliseSize(cropped)
    }

    /** Free a derived bitmap without ever recycling the caller's original. */
    fun recycleIfDerived(derived: Bitmap, original: Bitmap) {
        if (derived !== original && !derived.isRecycled) derived.recycle()
    }
}
