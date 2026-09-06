package com.nila.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The geometry under the text detector, tested without a device.
 *
 * Worth its own suite because the first version shipped a convex hull that
 * indexed past the start of its own chain, and every detection on the phone
 * died with an out-of-bounds inside the OCR thread -- which surfaced as
 * "couldn't read the label", indistinguishable from a blurry photo.
 */
class GeometryTest {

    private fun approx(expected: Double, actual: Double, tolerance: Double = 1e-6) =
        assertTrue("expected $expected, got $actual", abs(expected - actual) < tolerance)

    @Test
    fun hullOfASquareIsItsFourCorners() {
        val points = buildList {
            for (x in 0..10) for (y in 0..10) add(Pt(x.toDouble(), y.toDouble()))
        }
        val hull = Geometry.convexHull(points)
        assertEquals(4, hull.size)
        assertEquals(100.0, Geometry.polygonArea(hull), 1e-6)
    }

    @Test
    fun hullHandlesCollinearAndDuplicatePoints() {
        val points = listOf(
            Pt(0.0, 0.0), Pt(0.0, 0.0), Pt(1.0, 0.0),
            Pt(2.0, 0.0), Pt(2.0, 2.0), Pt(0.0, 2.0),
        )
        val hull = Geometry.convexHull(points)
        assertEquals(4, hull.size)
        assertEquals(4.0, Geometry.polygonArea(hull), 1e-6)
    }

    @Test
    fun hullOfTwoPointsDoesNotCrash() {
        assertEquals(2, Geometry.convexHull(listOf(Pt(0.0, 0.0), Pt(1.0, 1.0))).size)
        assertEquals(1, Geometry.convexHull(listOf(Pt(3.0, 3.0))).size)
        assertEquals(0, Geometry.convexHull(emptyList()).size)
    }

    @Test
    fun minAreaRectRecoversAnAxisAlignedBox() {
        val rect = Geometry.minAreaRect(
            listOf(Pt(2.0, 1.0), Pt(12.0, 1.0), Pt(12.0, 5.0), Pt(2.0, 5.0))
        )!!
        approx(40.0, rect.width * rect.height, 1e-6)
        approx(4.0, rect.shortSide, 1e-6)
        approx(7.0, rect.cx, 1e-6)
        approx(3.0, rect.cy, 1e-6)
    }

    /**
     * The case that matters: a strip photographed at an angle.
     *
     * An axis-aligned bounding box around this would have roughly twice the
     * area, most of it background -- which is exactly why ML Kit struggles with
     * the same photograph.
     */
    @Test
    fun minAreaRectRecoversARotatedBox() {
        val angle = 30.0 * PI / 180
        val corners = listOf(
            Pt(-20.0, -3.0), Pt(20.0, -3.0), Pt(20.0, 3.0), Pt(-20.0, 3.0),
        ).map { Pt(it.x * cos(angle) - it.y * sin(angle) + 50,
                   it.x * sin(angle) + it.y * cos(angle) + 40) }

        val rect = Geometry.minAreaRect(corners)!!
        approx(240.0, rect.width * rect.height, 1e-5)
        approx(6.0, rect.shortSide, 1e-6)
        approx(50.0, rect.cx, 1e-5)
        approx(40.0, rect.cy, 1e-5)
    }

    @Test
    fun cornersComeBackInReadingOrder() {
        val corners = RotRect(50.0, 40.0, 40.0, 6.0, 20.0 * PI / 180).corners()
        assertEquals(4, corners.size)
        // Top-left is left of top-right, and above bottom-left.
        assertTrue(corners[0].x < corners[1].x)
        assertTrue(corners[0].y < corners[3].y)
    }

    /**
     * Unclipping grows the box by area x ratio / perimeter on every side.
     *
     * Under-growing clips ascenders and descenders off the crop; over-growing
     * pulls in the line above. Both show up as a recogniser that reads most of
     * a word.
     */
    @Test
    fun unclipGrowsByTheDocumentedDistance() {
        val rect = RotRect(0.0, 0.0, 100.0, 20.0, 0.0)
        val distance = (100.0 * 20.0) * 1.6 / (2 * (100.0 + 20.0))
        val grown = rect.grownBy(distance)
        approx(100.0 + 2 * distance, grown.width)
        approx(20.0 + 2 * distance, grown.height)
    }

    @Test
    fun pointInPolygonAgreesWithTheObviousAnswer() {
        val square = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))
        assertTrue(Geometry.contains(square, 5.0, 5.0))
        assertTrue(!Geometry.contains(square, 15.0, 5.0))
        assertTrue(!Geometry.contains(square, 5.0, -1.0))
    }
}
