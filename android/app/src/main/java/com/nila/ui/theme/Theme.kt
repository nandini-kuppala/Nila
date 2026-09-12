package com.nila.ui.theme

/*
 * The centre of the app's appearance.
 *
 * Four files, one system, and a fixed order of dependency:
 *
 *   Color.kt        every literal colour in the app, named and explained
 *   Theme.kt        this file -- the two schemes, the type scale, and
 *                   [LocalNilaDarkTheme], which decides which scheme is on
 *   Severity.kt     note / attention / urgent, as matched fore-and-background
 *                   trios, resolved against LocalNilaDarkTheme
 *   ChartPalette.kt sleep / feed / cry / cause identity colours, same source
 *
 * Nothing outside this package declares a `Color`, and nothing anywhere calls
 * `isSystemInDarkTheme()` except [NilaTheme]. Both rules exist because the same
 * bug keeps coming back otherwise: a hardcoded pale background paired with a
 * foreground the dark scheme flips to near-white, which renders correctly,
 * screenshots correctly in the light theme, and is invisible at night.
 */

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    secondary = Stone40,
    onSecondary = Color.White,
    secondaryContainer = Stone90,
    onSecondaryContainer = Stone10,
    tertiary = Indigo40,
    onTertiary = Color.White,
    tertiaryContainer = Indigo90,
    onTertiaryContainer = Indigo10,
    error = ErrorRed40,
    onError = Color.White,
    errorContainer = ErrorRed90,
    onErrorContainer = ErrorRed10,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Neutral95,
    onSurfaceVariant = Neutral40,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Neutral98,
    surfaceContainer = Neutral95,
    surfaceContainerHigh = Neutral90,
    outline = Neutral60,
    outlineVariant = Neutral90,
)

/**
 * Dark is supported but deliberately not the default. The app is used in a dark
 * room at night, so an always-on dark theme sounds right -- but the same app is
 * used at noon to scan a medicine strip, and forcing dark there hurts OCR
 * review. We follow the system and make sure both are legible.
 */
private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    secondary = Stone80,
    onSecondary = Stone20,
    secondaryContainer = Stone30,
    onSecondaryContainer = Stone90,
    tertiary = Indigo80,
    onTertiary = Indigo10,
    tertiaryContainer = Indigo30,
    onTertiaryContainer = Indigo90,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    background = Color(0xFF101413),
    onBackground = Neutral90,
    surface = Color(0xFF101413),
    onSurface = Neutral90,
    surfaceVariant = Color(0xFF202523),
    onSurfaceVariant = Color(0xFFBFC9C5),
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = Color(0xFF181C1B),
    surfaceContainer = Color(0xFF1C2120),
    surfaceContainerHigh = Color(0xFF262B29),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
)

/**
 * Type scale.
 *
 * Nothing in the reading path drops below 15sp. The people using this are
 * sleep-deprived, often holding a baby, and reading at arm's length in bad
 * light -- the usual 12sp caption is not readable in those conditions.
 * Numbers that must be compared across rows use monospace figures.
 */
private val NilaTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
    ),
)

/**
 * Whether the *app* is dark, as opposed to whether the phone is.
 *
 * Everything with a colour in it reads this, and nothing reads
 * `isSystemInDarkTheme()` any more. That distinction is the whole reason this
 * exists: the moment the app got a light/dark switch of its own, every palette
 * that asked the system directly -- severity, the charts -- would have kept
 * answering for the phone while the scheme around it answered for the switch.
 * A medicine verdict on a pale amber card, in an app the user had just put into
 * dark mode, with body text the dark scheme had turned white. Exactly the bug
 * [SeverityColors] was built to make impossible, reintroduced by a feature.
 *
 * One source of truth, provided by [NilaTheme], read by [severityColors],
 * [calmColors] and [chartPalette].
 */
val LocalNilaDarkTheme = staticCompositionLocalOf { false }

/**
 * The app's single theme, and the only place a colour scheme is chosen.
 *
 * @param mode what the user asked for; [ThemeMode.SYSTEM] defers to the phone.
 */
@Composable
fun NilaTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalNilaDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colors, typography = NilaTypography, content = content)
    }
}
