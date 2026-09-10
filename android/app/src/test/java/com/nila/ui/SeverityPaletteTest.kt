package com.nila.ui

import androidx.compose.ui.graphics.Color
import com.nila.data.Severity
import com.nila.ui.theme.SeverityColors
import com.nila.ui.theme.SeverityPalette
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * That every severity card is readable, in both themes.
 *
 * This is here because of a bug no light-theme screenshot could have shown. The
 * severity palette was light-only -- a pale green, amber and pink -- and each
 * card then chose its own body-text colour, in practice
 * `MaterialTheme.colorScheme.onSurface`. In the dark scheme that resolves to a
 * near-white, so the medicine verdict a parent had photographed a strip to get
 * was white text on pale green: rendered, correct and unreadable. The same bug
 * silently hid every warning box in the app, including the one that says the
 * microphone has died.
 *
 * A pairing cannot be eyeballed into correctness in a theme nobody screenshots,
 * so it is measured. WCAG contrast ratios, on the pairs the code actually uses.
 */
class SeverityPaletteTest {

    /** WCAG 2.1 relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** AA for body text. The text on these cards is the answer, not decoration. */
    private val bodyMinimum = 4.5

    /**
     * AA for large text. The accent is only ever used for a bold uppercase
     * label, which the standard treats as large.
     */
    private val labelMinimum = 3.0

    private fun allPairs(): List<Pair<String, SeverityColors>> = buildList {
        Severity.entries.forEach { severity ->
            add("light/${severity.name}" to SeverityPalette.light(severity))
            add("dark/${severity.name}" to SeverityPalette.dark(severity))
        }
        add("light/CALM" to SeverityPalette.calm(dark = false))
        add("dark/CALM" to SeverityPalette.calm(dark = true))
    }

    @Test
    fun `body text clears AA on every severity ground`() {
        allPairs().forEach { (name, colors) ->
            val ratio = contrast(colors.onContainer, colors.container)
            assertTrue(
                "$name: body text is %.2f:1 against its own background, needs %.1f"
                    .format(ratio, bodyMinimum),
                ratio >= bodyMinimum,
            )
        }
    }

    @Test
    fun `the accent label clears AA-large on every severity ground`() {
        allPairs().forEach { (name, colors) ->
            val ratio = contrast(colors.accent, colors.container)
            assertTrue(
                "$name: label is %.2f:1 against its own background, needs %.1f"
                    .format(ratio, labelMinimum),
                ratio >= labelMinimum,
            )
        }
    }

    /**
     * The exact failure that shipped. `onSurface` in the dark scheme is
     * Neutral90, and putting it on the light containers is what made the
     * verdict invisible -- so it is pinned as something that must *not* pass.
     */
    @Test
    fun `the shipped bug would fail this test`() {
        val darkOnSurface = Color(0xFFE1E3E1)
        val lightCalmGround = SeverityPalette.calm(dark = false).container
        val ratio = contrast(darkOnSurface, lightCalmGround)
        assertTrue(
            "this pairing was the bug; it must not clear AA (was %.2f:1)"
                .format(ratio),
            ratio < bodyMinimum,
        )
    }

    /** A dark card must not be a pale card. It is read in a dark nursery. */
    @Test
    fun `dark grounds are actually dark`() {
        Severity.entries.forEach { severity ->
            val ground = SeverityPalette.dark(severity).container
            assertTrue(
                "dark/${severity.name} ground is too bright for a night feed",
                luminance(ground) < 0.1,
            )
        }
        assertTrue(luminance(SeverityPalette.calm(dark = true).container) < 0.1)
    }

    @Test
    fun `each severity is visually distinct from the others`() {
        val accents = listOf(
            SeverityPalette.light(Severity.ATTENTION).accent,
            SeverityPalette.light(Severity.URGENT).accent,
            SeverityPalette.calm(dark = false).accent,
        )
        // Colour is never the only carrier in this app -- every chip also has a
        // label -- but two severities that look identical make the label do all
        // the work, which defeats the point of having a colour at all.
        accents.forEachIndexed { i, a ->
            accents.drop(i + 1).forEach { b -> assertNotEquals(a, b) }
        }
    }

    @Test
    fun `urgent and critical share a palette`() {
        // Four levels, three grounds. Critical is urgent with a different
        // vibration pattern, not a different colour.
        assertTrue(
            SeverityPalette.light(Severity.URGENT) ==
                SeverityPalette.light(Severity.CRITICAL)
        )
        assertTrue(
            SeverityPalette.dark(Severity.URGENT) ==
                SeverityPalette.dark(Severity.CRITICAL)
        )
    }
}
