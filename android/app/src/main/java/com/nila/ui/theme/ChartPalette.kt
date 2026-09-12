package com.nila.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The colours the dashboard plots with, resolved for the theme in force.
 *
 * Kept out of [androidx.compose.material3.ColorScheme] on purpose. Material's
 * roles describe *emphasis* -- primary, secondary, tertiary -- and a chart
 * needs identity instead: sleep has to be the same colour on the rhythm track,
 * in the weekly bars and in the legend, and nothing about sleep is more
 * emphasised than feeding. Borrowing `secondary` for one series and `tertiary`
 * for another gets the reader a chart whose colours shuffle the moment anyone
 * touches the scheme.
 *
 * Same discipline as [SeverityColors]: a caller takes a colour and the text
 * colour that belongs on it together, so no chart can end up with a label the
 * dark theme has quietly turned white on white.
 */
data class ChartPalette(
    val sleep: Color,
    val feed: Color,
    val cry: Color,
    val diaper: Color,
    /** Wedge colours for the cause donut, in rank order. */
    val causes: List<Color>,
) {
    /** The soft fill a band uses when it is the ground rather than the mark. */
    fun wash(color: Color): Color = color.copy(alpha = 0.18f)

    /** Cause [index], wrapping rather than running out. */
    fun cause(index: Int): Color = causes[index % causes.size]
}

private val Light = ChartPalette(
    sleep = ChartSleepLight,
    feed = ChartFeedLight,
    cry = ChartCryLight,
    diaper = ChartDiaperLight,
    causes = CauseWheelLight,
)

private val Dark = ChartPalette(
    sleep = ChartSleepDark,
    feed = ChartFeedDark,
    cry = ChartCryDark,
    diaper = ChartDiaperDark,
    causes = CauseWheelDark,
)

/**
 * The chart colours for the theme currently in force.
 *
 * [LocalNilaDarkTheme] rather than the system setting, so a donut drawn in an
 * app the user has switched to light does not keep the pale dark-theme wedges
 * that were chosen to sit on a near-black ground.
 */
@Composable
@ReadOnlyComposable
fun chartPalette(): ChartPalette =
    if (LocalNilaDarkTheme.current) Dark else Light
