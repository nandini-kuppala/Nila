package com.nila.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.nila.data.Severity

/**
 * A severity, resolved into the three colours a card actually needs.
 *
 * This exists because the previous arrangement had a hole in it that only
 * appeared in dark mode. Severity was two loose colours -- a foreground and a
 * pale background -- and every caller then picked its own body-text colour,
 * almost always `MaterialTheme.colorScheme.onSurface`. In the light scheme that
 * is near-black on pale green and reads fine. In the dark scheme it is
 * near-white on pale green, and the medicine verdict a parent had just
 * photographed a strip to get was invisible.
 *
 * Three colours, together, from one call. A caller cannot take the background
 * without also taking the text colour that goes on it.
 */
data class SeverityColors(
    /** The label and any icon: the loudest thing on the card. */
    val accent: Color,
    /** The card's own ground. */
    val container: Color,
    /** Body text on [container]. */
    val onContainer: Color,
) {
    /** Slightly recessed body text, for a caption on the same ground. */
    val onContainerMuted: Color get() = onContainer.copy(alpha = 0.72f)
}

/**
 * The palette itself, with no Compose runtime attached.
 *
 * Separated from [severityColors] so the pairings can be tested on the JVM.
 * The bug this replaced was not a wrong colour, it was a *pairing* that no
 * screenshot in the light theme could reveal, so the pairs are now asserted
 * against a contrast threshold rather than eyeballed.
 */
object SeverityPalette {

    fun light(severity: Severity): SeverityColors = when (severity) {
        Severity.NOTE ->
            SeverityColors(SeverityNote, Neutral95, OnSeverityLight)
        Severity.ATTENTION ->
            SeverityColors(SeverityAttention, SeverityAttentionBg, OnSeverityLight)
        Severity.URGENT, Severity.CRITICAL ->
            SeverityColors(SeverityUrgent, SeverityUrgentBg, OnSeverityLight)
    }

    fun dark(severity: Severity): SeverityColors = when (severity) {
        Severity.NOTE ->
            SeverityColors(Color(0xFFB6C2BD), Color(0xFF1C2120), OnSeverityDark)
        Severity.ATTENTION ->
            SeverityColors(SeverityAttentionDark, SeverityAttentionBgDark, OnSeverityDark)
        Severity.URGENT, Severity.CRITICAL ->
            SeverityColors(SeverityUrgentDark, SeverityUrgentBgDark, OnSeverityDark)
    }

    /** The all-clear pair, which is not a [Severity] level of its own. */
    val calmLight = SeverityColors(SeverityCalm, SeverityCalmBg, OnSeverityLight)
    val calmDark = SeverityColors(SeverityCalmDark, SeverityCalmBgDark, OnSeverityDark)

    fun of(severity: Severity, dark: Boolean): SeverityColors =
        if (dark) dark(severity) else light(severity)

    fun calm(dark: Boolean): SeverityColors = if (dark) calmDark else calmLight
}

/**
 * Colours for [severity], for the theme currently in force.
 *
 * `isSystemInDarkTheme()` rather than reading the scheme, because the scheme
 * has no severity roles to read -- Material's `error` role is one colour pair
 * and there are four levels here, three of which are not errors.
 */
@Composable
@ReadOnlyComposable
fun severityColors(severity: Severity): SeverityColors =
    SeverityPalette.of(severity, isSystemInDarkTheme())

/** The all-clear pair, for the theme currently in force. */
@Composable
@ReadOnlyComposable
fun calmColors(): SeverityColors = SeverityPalette.calm(isSystemInDarkTheme())
