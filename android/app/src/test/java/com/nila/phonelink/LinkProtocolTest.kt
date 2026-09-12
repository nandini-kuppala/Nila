package com.nila.phonelink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guardian-parent wire format, and the severity mapping it carries.
 *
 * The mapping tests are the point of this file. Everything else in the link is
 * plumbing that fails loudly; deciding that level 3 sounds an alarm and level 2
 * does not is a product judgement that fails *silently* if it drifts -- a phone
 * that quietly stops sounding for urgent events looks exactly like a quiet
 * night.
 */
class LinkProtocolTest {

    private val key = LinkProtocol.deriveKey("424242")

    private val sample = LinkProtocol.Frame(
        kind = LinkProtocol.Kind.ALERT,
        seq = 7,
        severity = 3,
        seconds = 92,
        trend = "RISING",
        monitoring = true,
        sentAtMs = 1_700_000_000_000,
        title = "Your baby needs you",
        body = "Probably tired. Crying for 92 seconds. Tried 2 sounds first.",
    )

    @Test
    fun `a frame survives a round trip`() {
        val decoded = LinkProtocol.decode(LinkProtocol.encode(sample, key), key)
        assertEquals(sample, decoded)
    }

    @Test
    fun `a body containing the separator is not truncated`() {
        // Advice text is generated prose and will eventually contain a pipe.
        val awkward = sample.copy(body = "Tried 2 sounds | still crying")
        val decoded = LinkProtocol.decode(LinkProtocol.encode(awkward, key), key)!!
        assertEquals(awkward.body, decoded.body)
    }

    @Test
    fun `a frame signed with a different key is refused`() {
        // The whole reason the tag exists: without this check, anything on the
        // same Wi-Fi can tell a parent their baby has stopped breathing.
        val other = LinkProtocol.deriveKey("999999")
        assertNull(LinkProtocol.decode(LinkProtocol.encode(sample, other), key))
    }

    @Test
    fun `a tampered frame is refused`() {
        val encoded = LinkProtocol.encode(sample, key)
        // Escalate the severity in transit, leaving the tag alone.
        val forged = encoded.replaceFirst("|3|92|", "|4|92|")
        assertNotEquals(encoded, forged)
        assertNull(LinkProtocol.decode(forged, key))
    }

    @Test
    fun `malformed input decodes to null rather than nonsense`() {
        assertNull(LinkProtocol.decode("", key))
        assertNull(LinkProtocol.decode("nonsense", key))
        assertNull(LinkProtocol.decode("deadbeef:1|ALERT|nope", key))
        assertNull(LinkProtocol.decode(":", key))
    }

    @Test
    fun `a frame from a future version is rejected`() {
        val line = sample.copy().line().replaceFirst("1|", "99|")
        val tagged = LinkProtocol.encode(sample, key).substringBefore(':') + ":" + line
        assertNull(LinkProtocol.decode(tagged, key))
    }

    @Test
    fun `an oversized frame is refused before it is parsed`() {
        val huge = sample.copy(body = "x".repeat(LinkProtocol.MAX_FRAME_BYTES))
        assertNull(LinkProtocol.decode(LinkProtocol.encode(huge, key), key))
    }

    @Test
    fun `the same code derives the same key on both phones`() {
        assertTrue(
            LinkProtocol.deriveKey("123456")
                .contentEquals(LinkProtocol.deriveKey("123456"))
        )
        assertTrue(
            !LinkProtocol.deriveKey("123456")
                .contentEquals(LinkProtocol.deriveKey("123457"))
        )
    }

    // --- the severity mapping -------------------------------------------------

    @Test
    fun `level 1 never reaches the other phone`() {
        // Fussing is not news. A monitor that relays every note is a monitor
        // whose alerts get muted within a week.
        assertEquals(LinkProtocol.Tier.IGNORE, LinkProtocol.tierFor(1))
    }

    @Test
    fun `level 2 notifies without waking the phone`() {
        assertEquals(LinkProtocol.Tier.NOTIFY, LinkProtocol.tierFor(2))
    }

    @Test
    fun `level 3 sounds an alarm`() {
        assertEquals(LinkProtocol.Tier.ALARM, LinkProtocol.tierFor(3))
    }

    @Test
    fun `only level 4 takes the screen`() {
        assertEquals(LinkProtocol.Tier.FULL_SCREEN, LinkProtocol.tierFor(4))
        // If anything below CRITICAL ever starts seizing the lock screen, the
        // user revokes the permission and level 4 stops working too.
        for (level in 0..3) {
            assertNotEquals(
                "level $level must not take the screen",
                LinkProtocol.Tier.FULL_SCREEN,
                LinkProtocol.tierFor(level),
            )
        }
    }

    @Test
    fun `an unknown severity falls silent rather than alarming`() {
        // A future guardian sending a level this parent does not understand
        // should go quiet, not start sounding for something it cannot describe.
        assertEquals(LinkProtocol.Tier.IGNORE, LinkProtocol.tierFor(0))
        assertEquals(LinkProtocol.Tier.IGNORE, LinkProtocol.tierFor(-1))
        assertEquals(LinkProtocol.Tier.IGNORE, LinkProtocol.tierFor(99))
    }

    @Test
    fun `escalating severity never de-escalates the response`() {
        val order = listOf(
            LinkProtocol.Tier.IGNORE,
            LinkProtocol.Tier.NOTIFY,
            LinkProtocol.Tier.ALARM,
            LinkProtocol.Tier.FULL_SCREEN,
        )
        val tiers = (1..4).map { LinkProtocol.tierFor(it) }
        assertEquals(
            "the response must grow monotonically with severity",
            tiers.sortedBy { order.indexOf(it) }, tiers,
        )
    }

    @Test
    fun `the link timeout is under the time it takes to check the cot`() {
        // The build spec asks for ten seconds. Longer and the monitoring phone
        // can be dead for a quarter of a minute while this one looks calm.
        assertTrue(LinkProtocol.LINK_TIMEOUT_MS <= 10_000)
        // And at least three heartbeats, or a single dropped packet cries wolf.
        assertTrue(LinkProtocol.LINK_TIMEOUT_MS >= LinkProtocol.HEARTBEAT_MS * 3)
    }

    @Test
    fun `the link does not collide with the laptop dashboard`() {
        assertNotEquals(com.nila.bridge.DeskBridge.PORT, LinkProtocol.PORT)
    }

    @Test
    fun `a pairing code is always six digits`() {
        listOf(0, 7, 999_999, 1_234_567).forEach {
            val code = LinkProtocol.formatCode(it)
            assertEquals("bad code for $it: $code", 6, code.length)
            assertTrue(code.all(Char::isDigit))
        }
    }
}
