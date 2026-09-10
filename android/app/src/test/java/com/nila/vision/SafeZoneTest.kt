package com.nila.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zone a parent drags over the cot.
 *
 * Most of what is defended here is the result of a drag going wrong. A
 * rectangle can be inverted, dragged off the frame, or collapsed to nothing,
 * and a zone of zero area reports every baby as outside it -- an alarm that
 * fires constantly and gets the whole feature turned off.
 */
class SafeZoneTest {

    @Test
    fun `the default is the inner seventy percent and is not configured`() {
        val zone = SafeZone.DEFAULT
        assertEquals(0.15f, zone.left, 1e-5f)
        assertEquals(0.85f, zone.right, 1e-5f)
        assertEquals(0.7f, zone.width, 1e-5f)
        assertFalse("the default must announce itself as unset", zone.configured)
    }

    @Test
    fun `an inverted drag is straightened out`() {
        val zone = SafeZone(0.8f, 0.9f, 0.2f, 0.3f).normalised()
        assertTrue(zone.left < zone.right)
        assertTrue(zone.top < zone.bottom)
    }

    @Test
    fun `a drag outside the frame is clamped into it`() {
        val zone = SafeZone(-0.4f, -0.2f, 1.6f, 1.3f).normalised()
        assertTrue(zone.left >= 0f && zone.right <= 1f)
        assertTrue(zone.top >= 0f && zone.bottom <= 1f)
    }

    @Test
    fun `a collapsed zone is given a minimum size`() {
        val zone = SafeZone(0.5f, 0.5f, 0.5f, 0.5f).normalised()
        assertTrue("width ${zone.width}", zone.width >= SafeZone.MIN_SIDE - 1e-5f)
        assertTrue("height ${zone.height}", zone.height >= SafeZone.MIN_SIDE - 1e-5f)
        assertTrue(zone.left >= 0f && zone.right <= 1f)
    }

    @Test
    fun `a collapsed zone at the frame edge stays inside the frame`() {
        val zone = SafeZone(1f, 1f, 1f, 1f).normalised()
        assertTrue(zone.right <= 1f)
        assertTrue(zone.bottom <= 1f)
        assertTrue(zone.width >= SafeZone.MIN_SIDE - 1e-5f)
    }

    @Test
    fun `containment is inclusive of the boundary`() {
        val zone = SafeZone(0.2f, 0.2f, 0.8f, 0.8f)
        assertTrue(zone.contains(0.5f, 0.5f))
        assertTrue(zone.contains(0.2f, 0.2f))
        assertFalse(zone.contains(0.19f, 0.5f))
        assertFalse(zone.contains(0.5f, 0.81f))
    }

    /** A shoulder over the line and a baby across the room are different alerts. */
    @Test
    fun `overshoot grows with how far outside the point is`() {
        val zone = SafeZone(0.4f, 0.4f, 0.6f, 0.6f)
        assertEquals(0f, zone.overshoot(0.5f, 0.5f), 1e-5f)
        val slight = zone.overshoot(0.62f, 0.5f)
        val far = zone.overshoot(0.95f, 0.5f)
        assertTrue(slight > 0f)
        assertTrue("$far should exceed $slight", far > slight)
    }

    @Test
    fun `a saved zone survives a round trip and remembers being configured`() {
        val zone = SafeZone(0.22f, 0.31f, 0.77f, 0.68f, configured = true)
        val back = SafeZone.decode(zone.encode())
        assertEquals(zone.left, back.left, 1e-4f)
        assertEquals(zone.bottom, back.bottom, 1e-4f)
        assertTrue(back.configured)
    }

    @Test
    fun `garbage decodes to the default rather than throwing`() {
        assertEquals(SafeZone.DEFAULT, SafeZone.decode(null))
        assertEquals(SafeZone.DEFAULT, SafeZone.decode(""))
        assertEquals(SafeZone.DEFAULT, SafeZone.decode("0.1,0.2"))
        assertEquals(SafeZone.DEFAULT, SafeZone.decode("left,top,right,bottom"))
    }
}
