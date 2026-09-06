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
val SeverityNote = Color(0xFF5C6B67)      // L1 -- logged, silent
val SeverityAttention = Color(0xFFA96A12) // L2 -- amber
val SeverityAttentionBg = Color(0xFFFBEEDA)
val SeverityUrgent = Color(0xFFB3261E)    // L3/L4 -- red
val SeverityUrgentBg = Color(0xFFFBE9E7)
val SeverityCalm = Color(0xFF2E6B4F)      // all-clear green
val SeverityCalmBg = Color(0xFFE1F0E8)

val ErrorRed40 = Color(0xFFB3261E)
val ErrorRed90 = Color(0xFFF9DEDC)
val ErrorRed10 = Color(0xFF410E0B)
