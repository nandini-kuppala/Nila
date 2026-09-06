package com.nila.ocr

import android.graphics.Bitmap

/**
 * A plain packed-ARGB pixel buffer.
 *
 * The OCR pipeline crops, rotates and rescales an image several times per
 * photograph. Doing that with [Bitmap] means a Java object and a native
 * allocation for every intermediate, plus the recycling discipline to match;
 * this is one int array and no lifecycle at all.
 */
class Rgb(val width: Int, val height: Int, fill: Int = 0) {

    val pixels = IntArray(width * height).also { if (fill != 0) it.fill(fill) }

    companion object {
        fun from(bitmap: Bitmap): Rgb {
            val out = Rgb(bitmap.width, bitmap.height)
            bitmap.getPixels(out.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return out
        }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    /** Bilinear resample. Nearest-neighbour here visibly costs recognition accuracy. */
    fun resized(newWidth: Int, newHeight: Int): Rgb {
        if (newWidth == width && newHeight == height) return this
        val out = Rgb(newWidth, newHeight)
        val sx = width.toDouble() / newWidth
        val sy = height.toDouble() / newHeight
        for (y in 0 until newHeight) {
            val srcY = (y + 0.5) * sy - 0.5
            for (x in 0 until newWidth) {
                out.setBilinear(x, y, this, (x + 0.5) * sx - 0.5, srcY)
            }
        }
        return out
    }

    /** Sample [source] at a fractional position and write it to (x, y) here. */
    fun setBilinear(x: Int, y: Int, source: Rgb, sourceX: Double, sourceY: Double) {
        val x0 = kotlin.math.floor(sourceX).toInt()
        val y0 = kotlin.math.floor(sourceY).toInt()
        val fx = sourceX - x0
        val fy = sourceY - y0

        // Clamp rather than wrap or blacken. A box that the unclip step pushed a
        // pixel past the edge of the photo should read as more of the edge, not
        // as a black border the recogniser mistakes for a stroke.
        val xa = x0.coerceIn(0, source.width - 1)
        val xb = (x0 + 1).coerceIn(0, source.width - 1)
        val ya = y0.coerceIn(0, source.height - 1)
        val yb = (y0 + 1).coerceIn(0, source.height - 1)

        val p00 = source[xa, ya]; val p10 = source[xb, ya]
        val p01 = source[xa, yb]; val p11 = source[xb, yb]

        var packed = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val c00 = (p00 shr shift) and 0xFF
            val c10 = (p10 shr shift) and 0xFF
            val c01 = (p01 shr shift) and 0xFF
            val c11 = (p11 shr shift) and 0xFF
            val top = c00 + (c10 - c00) * fx
            val bottom = c01 + (c11 - c01) * fx
            val value = (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
            packed = packed or (value shl shift)
        }
        pixels[y * width + x] = packed
    }

    /** Counter-clockwise, matching numpy's rot90 -- the reference uses that one. */
    fun rotate90(): Rgb {
        val out = Rgb(height, width)
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                out.pixels[y * out.width + x] = this[width - 1 - y, x]
            }
        }
        return out
    }

    fun rotate180(): Rgb {
        val out = Rgb(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out.pixels[y * width + x] = this[width - 1 - x, height - 1 - y]
            }
        }
        return out
    }

    fun blit(source: Rgb, atX: Int, atY: Int) {
        val copyWidth = minOf(source.width, width - atX)
        for (y in 0 until minOf(source.height, height - atY)) {
            System.arraycopy(
                source.pixels, y * source.width,
                pixels, (atY + y) * width + atX,
                copyWidth,
            )
        }
    }
}
