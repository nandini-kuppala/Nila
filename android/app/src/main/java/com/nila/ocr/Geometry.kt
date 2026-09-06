package com.nila.ocr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point in image space. Doubles, because the box corners are sub-pixel. */
data class Pt(val x: Double, val y: Double)

/**
 * A rotated rectangle, in the same parameterisation OpenCV's minAreaRect uses:
 * a centre, a size and an angle.
 *
 * Kept as a rect rather than as four loose corners because both operations that
 * follow -- unclipping and cropping -- are exact on a rectangle and only
 * approximate on a general quadrilateral.
 */
data class RotRect(
    val cx: Double,
    val cy: Double,
    val width: Double,
    val height: Double,
    val angleRad: Double,
) {
    val shortSide: Double get() = minOf(width, height)

    /** Corners, in the order the DB post-process expects: TL, TR, BR, BL. */
    fun corners(): List<Pt> {
        val c = cos(angleRad)
        val s = sin(angleRad)
        val hw = width / 2
        val hh = height / 2
        val raw = listOf(
            Pt(-hw, -hh), Pt(hw, -hh), Pt(hw, hh), Pt(-hw, hh),
        ).map { Pt(cx + it.x * c - it.y * s, cy + it.x * s + it.y * c) }
        return orderCorners(raw)
    }

    /**
     * Grow the rectangle outward by [distance] on every side.
     *
     * This is the unclip step. PaddleOCR runs a Clipper polygon offset with a
     * round join; on a convex rectangle the result's own minimum-area rectangle
     * is exactly this one, so the expensive general offset is not needed.
     */
    fun grownBy(distance: Double) =
        copy(width = width + 2 * distance, height = height + 2 * distance)

    companion object {
        /**
         * Sort four corners into TL, TR, BR, BL.
         *
         * Same rule as PaddleOCR's get_mini_boxes: sort by x, then decide within
         * each pair by y. It is not the general convex ordering -- it is chosen
         * so that corner 0 to corner 1 runs along the *reading* direction, which
         * is what makes the crop come out upright.
         */
        fun orderCorners(points: List<Pt>): List<Pt> {
            val byX = points.sortedBy { it.x }
            val (i1, i4) = if (byX[1].y > byX[0].y) 0 to 1 else 1 to 0
            val (i2, i3) = if (byX[3].y > byX[2].y) 2 to 3 else 3 to 2
            return listOf(byX[i1], byX[i2], byX[i3], byX[i4])
        }
    }
}

object Geometry {

    /**
     * Convex hull by Andrew's monotone chain.
     *
     * The hull is all that minimum-area-rectangle needs, which is why the
     * detector can label connected components instead of tracing contours: two
     * different boundary traces of the same blob have the same hull.
     */
    fun convexHull(points: List<Pt>): List<Pt> {
        val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return sorted

        // Each half-chain drops its own last point before they are joined:
        // that point is the first point of the other chain, and keeping both
        // leaves a duplicate corner that the rotating calipers then treats as a
        // zero-length edge.
        fun chain(source: List<Pt>): MutableList<Pt> {
            val out = ArrayList<Pt>(source.size)
            for (p in source) {
                while (out.size >= 2 &&
                    cross(out[out.size - 2], out[out.size - 1], p) <= 0
                ) {
                    out.removeAt(out.size - 1)
                }
                out.add(p)
            }
            out.removeAt(out.size - 1)
            return out
        }

        return chain(sorted) + chain(sorted.asReversed())
    }

    private fun cross(o: Pt, a: Pt, b: Pt): Double =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /**
     * Minimum-area enclosing rectangle, by rotating calipers.
     *
     * The optimal rectangle always has one side flush with a hull edge, so
     * testing every edge is exhaustive rather than a heuristic.
     */
    fun minAreaRect(points: List<Pt>): RotRect? {
        val hull = convexHull(points)
        if (hull.isEmpty()) return null
        if (hull.size == 1) return RotRect(hull[0].x, hull[0].y, 0.0, 0.0, 0.0)

        var best: RotRect? = null
        var bestArea = Double.MAX_VALUE

        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len < 1e-9) continue
            val ux = dx / len
            val uy = dy / len

            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (p in hull) {
                val u = p.x * ux + p.y * uy
                val v = -p.x * uy + p.y * ux
                if (u < minU) minU = u; if (u > maxU) maxU = u
                if (v < minV) minV = v; if (v > maxV) maxV = v
            }

            val w = maxU - minU
            val h = maxV - minV
            val area = w * h
            if (area < bestArea) {
                bestArea = area
                val mu = (minU + maxU) / 2
                val mv = (minV + maxV) / 2
                // Rotate the centre back out of the edge-aligned frame.
                best = RotRect(
                    cx = mu * ux - mv * uy,
                    cy = mu * uy + mv * ux,
                    width = w,
                    height = h,
                    angleRad = kotlin.math.atan2(uy, ux),
                )
            }
        }
        return best
    }

    /** Shoelace area of a closed polygon. */
    fun polygonArea(points: List<Pt>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2
    }

    fun polygonPerimeter(points: List<Pt>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += hypot(b.x - a.x, b.y - a.y)
        }
        return sum
    }

    /** Even-odd point-in-polygon, used to average the probability map inside a box. */
    fun contains(polygon: List<Pt>, x: Double, y: Double): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.y > y) != (pj.y > y) &&
                x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
