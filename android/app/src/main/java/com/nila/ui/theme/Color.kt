package com.nila.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nila's palette.
 *
 * Two decisions drive everything here, and both are defensible out loud:
 *
 * 1. A desaturated teal carries the brand. Nursery pink and baby blue read as a
 *    toy, and this app tells people about safety events and medicine. Teal sits
 *    in the same family as clinical software without the coldness of pure blue.
 *
 * 2. Severity colour is reserved. Amber and red appear only at their severity
 *    level and never as decoration -- the moment a warning red is used for an
 *    accent stripe it stops meaning anything at three in the morning.
 *
 * Values follow Material 3 tonal-palette roles so the scheme stays coherent if
 * anyone regenerates it, and every foreground/background pair below clears
 * WCAG AA for body text.
 */

// ---- primary: teal ------------------------------------------------------
val Teal10 = Color(0xFF00201C)
val Teal20 = Color(0xFF00382F)
val Teal30 = Color(0xFF005046)
val Teal40 = Color(0xFF0F6B60)
val Teal80 = Color(0xFF6FDBC9)
val Teal90 = Color(0xFF8CF8E5)
val Teal95 = Color(0xFFB6FFF2)

// ---- secondary: warm stone ---------------------------------------------
val Stone10 = Color(0xFF16211E)
val Stone20 = Color(0xFF2B3733)
val Stone30 = Color(0xFF414D49)
val Stone40 = Color(0xFF596561)
val Stone80 = Color(0xFFBFCBC6)
val Stone90 = Color(0xFFDBE7E2)

// ---- tertiary: muted indigo, for informational accents ------------------
val Indigo10 = Color(0xFF0B1D33)
val Indigo30 = Color(0xFF294964)
val Indigo40 = Color(0xFF3F607C)
val Indigo80 = Color(0xFFA8C8E8)
val Indigo90 = Color(0xFFCBE4FF)

// ---- neutrals: very slightly green-biased, never pure grey ---------------
val Neutral10 = Color(0xFF191C1B)
val Neutral20 = Color(0xFF2E3130)
val Neutral40 = Color(0xFF5C5F5E)
val Neutral60 = Color(0xFF8A8F8D)
val Neutral90 = Color(0xFFE1E3E1)
val Neutral95 = Color(0xFFEFF1EF)
val Neutral98 = Color(0xFFF7FAF8)
val Neutral99 = Color(0xFFFBFDFB)

// ---- semantic severity. Not part of the accent system. ------------------
//
// Two palettes, because one is a bug. These were light-only, and every card
// that used them paired a hardcoded pale background with body text coloured
// `onSurface` -- which the dark scheme flips to near-white. The result on a
// phone in dark mode was a medicine verdict whose headline and summary were
// white on pale green: present, correct, and unreadable. Anything with a fixed
// background needs a fixed foreground to go on it, so each pair below ships
// with the text colour that belongs on it.
val SeverityNote = Color(0xFF5C6B67)      // L1 -- logged, silent
val SeverityAttention = Color(0xFFA96A12) // L2 -- amber
val SeverityAttentionBg = Color(0xFFFBEEDA)
val SeverityUrgent = Color(0xFFB3261E)    // L3/L4 -- red
val SeverityUrgentBg = Color(0xFFFBE9E7)
val SeverityCalm = Color(0xFF2E6B4F)      // all-clear green
val SeverityCalmBg = Color(0xFFE1F0E8)

/**
 * Body text that belongs on the pale backgrounds above.
 *
 * Near-black rather than the scheme's `onSurface`, deliberately: the surface
 * under it is a fixed colour, so the text on it has to be too.
 */
val OnSeverityLight = Color(0xFF1A1C1B)

// ---- the same severities, for a dark scheme -----------------------------
//
// Deep, desaturated grounds rather than the light ones dimmed. A pale card on
// a near-black screen is a torch in a dark nursery at 3am, which is the exact
// situation this app is used in.
val SeverityAttentionDark = Color(0xFFF5C066)
val SeverityAttentionBgDark = Color(0xFF3A2A0C)
val SeverityUrgentDark = Color(0xFFF2B8B5)
val SeverityUrgentBgDark = Color(0xFF3D1512)
val SeverityCalmDark = Color(0xFF7ED3A8)
val SeverityCalmBgDark = Color(0xFF11291D)

/** Body text for the dark grounds above. */
val OnSeverityDark = Color(0xFFE8EAE9)

val ErrorRed40 = Color(0xFFB3261E)
val ErrorRed90 = Color(0xFFF9DEDC)
val ErrorRed10 = Color(0xFF410E0B)

// ---- chart hues. Also not part of the severity system. -------------------
//
// The dashboard plots three things at once -- sleep, feeds and crying -- and a
// reader has to tell them apart at a glance in both themes. None of them may
// borrow amber or red: crying is the *subject* of this app, not an alarm, and
// the moment a chart draws six hours of ordinary crying in the same red as a
// safety event, the red stops meaning safety event. So the plum below is
// deliberately a colour the app uses nowhere else, and nothing in the severity
// ladder can ever be mistaken for it.
//
// Each pair is chosen so a 3dp bar of it is distinguishable from the other two
// for the common forms of colour blindness -- they differ in lightness as well
// as hue -- and every one of them clears 3:1 against its own theme's surface.
val ChartSleepLight = Color(0xFF3F607C)   // indigo, the night lane
val ChartSleepDark = Color(0xFFA8C8E8)
val ChartFeedLight = Color(0xFF0F6B60)    // the brand teal
val ChartFeedDark = Color(0xFF6FDBC9)
val ChartCryLight = Color(0xFF7B4B86)     // plum: crying, never a warning
val ChartCryDark = Color(0xFFD9AEE4)

/** A fourth, for nappies on the rhythm track when it is asked for. */
val ChartDiaperLight = Color(0xFF8A6A3B)
val ChartDiaperDark = Color(0xFFDDBE8F)

/**
 * The wedges of the cause donut, in the order the causes are ranked.
 *
 * Five reason classes and an "unclear" bucket, so six. They walk around the
 * hue circle rather than down one ramp because these are categories with no
 * order -- a sequential ramp would imply belly pain is more of something than
 * tiredness is.
 */
val CauseWheelLight = listOf(
    Color(0xFF7B4B86), // plum
    Color(0xFF0F6B60), // teal
    Color(0xFF3F607C), // indigo
    Color(0xFF8A6A3B), // ochre
    Color(0xFF5E7C4A), // moss
    Color(0xFF8A8F8D), // the unclear bucket, deliberately grey
)
val CauseWheelDark = listOf(
    Color(0xFFD9AEE4),
    Color(0xFF6FDBC9),
    Color(0xFFA8C8E8),
    Color(0xFFDDBE8F),
    Color(0xFFB2CE9C),
    Color(0xFF8A8F8D),
)
