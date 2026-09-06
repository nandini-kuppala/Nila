package com.nila.ocr

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Turns the detector's probability map into text-line boxes.
 *
 * This is Differentiable Binarization's post-process, ported from PaddleOCR:
 * threshold the map, group what is left into blobs, wrap each blob in its
 * minimum-area rectangle, score it against the map, and grow it slightly so the
 * box contains the ascenders and descenders the segmentation trims off.
 *
 * PaddleOCR traces contours with OpenCV. There is no OpenCV here and adding it
 * for one call would cost more than the whole OCR stack, so blobs are found by
 * connected-component labelling instead. That is not an approximation: a
 * minimum-area rectangle depends only on the convex hull, and a component and
 * its traced outer contour have the same hull. It also drops interior holes,
 * which the contour version has to filter out afterwards.
 */
object DbPostProcess {

    data class Box(val corners: List<Pt>, val score: Double)

    /**
     * @param prob   the sigmoid output, row-major, [height] x [width]
     * @param scaleX map box coordinates back to the original image
     */
    fun boxesFrom(
        prob: FloatArray,
        width: Int,
        height: Int,
        scaleX: Double,
        scaleY: Double,
        srcWidth: Int,
        srcHeight: Int,
        thresh: Float = 0.3f,
        boxThresh: Double = 0.5,
        unclipRatio: Double = 1.6,
        maxCandidates: Int = 1000,
        useDilation: Boolean = true,
    ): List<Box> {
        val mask = binarise(prob, width, height, thresh, useDilation)
        val components = label(mask, width, height, maxCandidates)

        val out = ArrayList<Box>(components.size)
        for (component in components) {
            val rect = Geometry.minAreaRect(component) ?: continue
            if (rect.shortSide < MIN_SIZE) continue

            val score = boxScore(prob, width, height, rect.corners())
            if (score < boxThresh) continue

            val grown = unclip(rect, unclipRatio) ?: continue
            if (grown.shortSide < MIN_SIZE + 2) continue

            val corners = grown.corners().map { p ->
                Pt(
                    (p.x * scaleX).coerceIn(0.0, srcWidth.toDouble()),
                    (p.y * scaleY).coerceIn(0.0, srcHeight.toDouble()),
                )
            }
            out += Box(corners, score)
        }
        return sortForReading(out)
    }

    private const val MIN_SIZE = 3

    /**
     * Threshold, then a 2x2 dilation.
     *
     * The dilation is what joins the strokes of one word into a single blob.
     * Without it a light-on-dark foil strip fragments into one component per
     * letter and the recogniser gets fed individual characters.
     *
     * Anchored at the top-left rather than the centre, matching OpenCV's rule
     * for even-sized kernels -- an off-by-one here shifts every box by a pixel.
     */
    private fun binarise(
        prob: FloatArray,
        width: Int,
        height: Int,
        thresh: Float,
        dilate: Boolean,
    ): BooleanArray {
        val raw = BooleanArray(width * height)
        for (i in raw.indices) raw[i] = prob[i] > thresh
        if (!dilate) return raw

        val out = BooleanArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            val nextRow = if (y + 1 < height) row + width else row
            for (x in 0 until width) {
                val xr = if (x + 1 < width) x + 1 else x
                out[row + x] = raw[row + x] || raw[row + xr] ||
                    raw[nextRow + x] || raw[nextRow + xr]
            }
        }
        return out
    }

    /**
     * Eight-connected components, iteratively so a full-width text line cannot
     * blow the stack.
     *
     * Only boundary pixels are kept. The hull is all that follows, and a blob
     * covering a whole line of text is tens of thousands of pixels of which a
     * few hundred are on the edge.
     */
    private fun label(
        mask: BooleanArray,
        width: Int,
        height: Int,
        maxCandidates: Int,
    ): List<List<Pt>> {
        val seen = BooleanArray(mask.size)
        val components = ArrayList<List<Pt>>()
        val stack = ArrayList<Int>()

        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            if (components.size >= maxCandidates) break

            val boundary = ArrayList<Pt>()
            stack.clear()
            stack.add(start)
            seen[start] = true

            while (stack.isNotEmpty()) {
                val index = stack.removeAt(stack.size - 1)
                val x = index % width
                val y = index / width

                var isBoundary = false
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) { isBoundary = true; continue }
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= width) { isBoundary = true; continue }
                        val n = ny * width + nx
                        if (!mask[n]) {
                            isBoundary = true
                        } else if (!seen[n]) {
                            seen[n] = true
                            stack.add(n)
                        }
                    }
                }
                if (isBoundary) boundary.add(Pt(x.toDouble(), y.toDouble()))
            }

            if (boundary.size >= 4) components.add(boundary)
        }
        return components
    }

    /**
     * Mean probability inside the box -- PaddleOCR's "fast" scoring.
     *
     * A high mean means the segmentation was confident across the whole line
     * rather than at one bright spot, which is what separates real text from a
     * speckle of noise that happened to clear the threshold.
     */
    private fun boxScore(
        prob: FloatArray,
        width: Int,
        height: Int,
        corners: List<Pt>,
    ): Double {
        val xMin = floor(corners.minOf { it.x }).toInt().coerceIn(0, width - 1)
        val xMax = ceil(corners.maxOf { it.x }).toInt().coerceIn(0, width - 1)
        val yMin = floor(corners.minOf { it.y }).toInt().coerceIn(0, height - 1)
        val yMax = ceil(corners.maxOf { it.y }).toInt().coerceIn(0, height - 1)

        var sum = 0.0
        var count = 0
        for (y in yMin..yMax) {
            for (x in xMin..xMax) {
                if (Geometry.contains(corners, x.toDouble(), y.toDouble())) {
                    sum += prob[y * width + x]
                    count++
                }
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun unclip(rect: RotRect, ratio: Double): RotRect? {
        val corners = rect.corners()
        val area = Geometry.polygonArea(corners)
        val perimeter = Geometry.polygonPerimeter(corners)
        if (perimeter < 1e-6) return null
        return rect.grownBy(area * ratio / perimeter)
    }

    /**
     * Top to bottom, then left to right, with a 10-pixel tolerance on the row.
     *
     * The tolerance is the whole point: two boxes on the same printed line
     * rarely share a y coordinate exactly, and sorting strictly by y interleaves
     * the columns of a two-column label into nonsense.
     */
    private fun sortForReading(boxes: List<Box>): List<Box> {
        val sorted = boxes.sortedWith(
            compareBy({ it.corners[0].y }, { it.corners[0].x })
        ).toMutableList()

        for (i in 0 until sorted.size - 1) {
            for (j in i downTo 0) {
                val a = sorted[j]
                val b = sorted[j + 1]
                if (kotlin.math.abs(b.corners[0].y - a.corners[0].y) < 10 &&
                    b.corners[0].x < a.corners[0].x
                ) {
                    sorted[j] = b
                    sorted[j + 1] = a
                } else break
            }
        }
        return sorted
    }
}
