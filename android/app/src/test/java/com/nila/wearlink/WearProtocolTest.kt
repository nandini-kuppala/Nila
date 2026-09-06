package com.nila.wearlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone-watch wire format.
 *
 * Two files hold this protocol -- one in each module -- because a shared Gradle
 * module for eighty lines costs more than it saves. The trade is that they can
 * silently diverge, and a divergence here does not crash: the watch simply stops
 * updating, which nobody notices until the night they needed it. So the last
 * test in this file compares the two files directly.
 */
class WearProtocolTest {

    private val sample = WearProtocol.State(
        monitoring = true,
        headline = "Crying for 45s and getting louder",
        cryingSeconds = 45,
        trend = "RISING",
        severity = 3,
    )

    @Test
    fun `a state survives a round trip`() {
        val decoded = WearProtocol.State.decode(sample.encode())
        assertEquals(sample, decoded)
    }

    @Test
    fun `a headline containing the separator is not truncated`() {
        // Real headlines are generated text; assuming they never contain a
        // delimiter is how wire formats lose their last field.
        val awkward = sample.copy(headline = "Tried 2 sounds | still crying")
        val decoded = WearProtocol.State.decode(awkward.encode())!!
        assertTrue(
            "headline lost its tail: '${decoded.headline}'",
            decoded.headline.contains("still crying"),
        )
    }

    @Test
    fun `a malformed message decodes to null rather than nonsense`() {
        // The watch must ignore a bad frame, not render garbage as an alert.
        assertNull(WearProtocol.State.decode(ByteArray(0)))
        assertNull(WearProtocol.State.decode("nonsense".toByteArray()))
        assertNull(WearProtocol.State.decode("1|1|only|three".toByteArray()))
    }

    @Test
    fun `a message from a future version is rejected`() {
        val future = "99|1|10|2|RISING|hello".toByteArray()
        assertNull("must not parse an unknown version",
                   WearProtocol.State.decode(future))
    }

    @Test
    fun `the payload stays far below the data layer limit`() {
        // The Data Layer caps a message at 100 KB; ours should be a rounding
        // error against that even with an unusually long headline.
        val long = sample.copy(headline = "x".repeat(500))
        assertTrue("payload too large: ${long.encode().size}",
                   long.encode().size < 2_000)
    }

    @Test
    fun `severities get distinguishable vibration patterns`() {
        val patterns = (1..4).map { WearProtocol.patternFor(it) }
        // Every level must feel different, or routing alerts to a wrist gains
        // nothing over a single generic buzz.
        for (i in patterns.indices) {
            for (j in i + 1 until patterns.size) {
                assertNotEquals(
                    "levels ${i + 1} and ${j + 1} share a pattern",
                    patterns[i].toList(), patterns[j].toList(),
                )
            }
        }
        // Higher severity should be longer overall -- the one property a wearer
        // can judge without having learned the patterns.
        val totals = patterns.map { it.sum() }
        assertEquals(totals.sortedBy { it }, totals)
    }

    @Test
    fun `an unknown severity falls back to the quietest pattern`() {
        assertArrayEquals(WearProtocol.PATTERN_NOTE, WearProtocol.patternFor(0))
        assertArrayEquals(WearProtocol.PATTERN_CRITICAL, WearProtocol.patternFor(9))
    }

    @Test
    fun `the phone and watch copies of this protocol are identical`() {
        val phone = File("src/main/java/com/nila/wearlink/WearProtocol.kt")
        val watch = File("../wear/src/main/java/com/nila/wear/WearProtocol.kt")
        assertTrue("phone copy missing at ${phone.absolutePath}", phone.exists())
        assertTrue("watch copy missing at ${watch.absolutePath}", watch.exists())

        // Compare the code, ignoring the package line and the doc comment that
        // legitimately differ between the two.
        fun body(f: File) = f.readLines()
            .dropWhile { !it.startsWith("object WearProtocol") }
            .joinToString("\n")

        assertEquals(
            "The two WearProtocol files have diverged. The watch will stop " +
                "updating silently if the encoding differs -- re-copy the file.",
            body(phone), body(watch),
        )
    }
}
