package com.merrimentlabs.ducky.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.merrimentlabs.ducky.R

// Figtree ships as one variable font; each weight is that file with its weight axis set, which
// the variation-settings API (still marked experimental) is the only way to ask for.
@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    R.font.figtree, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Interface text: Figtree, bundled as a variable font (SIL Open Font License 1.1). */
val Figtree = FontFamily(figtree(400), figtree(500), figtree(600), figtree(700))

/**
 * The family's type scale, with its line heights, so a 14sp line never sits in a 24sp line box.
 * Roles map onto Material's slots:
 * - headlineSmall: the home screen's title, 28/34 SemiBold
 * - titleLarge: a top bar's title, 22/28 SemiBold
 * - titleMedium: a row title, 16/22 Medium
 * - titleSmall: a section label, 14/20 SemiBold
 * - bodyLarge: body text, 16/24 Regular
 * - bodyMedium: supporting text (a row's subtitle, a hint), 14/20 Regular
 * - bodySmall: fine print (the copyright line), 12/16 Regular
 * - labelLarge: a button, 15/20 SemiBold
 * The clock, the pond and the style previews keep their own faces and sizes.
 */
val DuckyTypography = Typography(
    headlineSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)
