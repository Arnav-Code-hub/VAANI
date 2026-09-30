package com.bithead.shelter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bithead.shelter.R

// "Low-Glow Architectural Brutalism" (Stitch dark Bauhaus). Flat tonal steps,
// 1px structural borders, muted industrial accents, no pure white.
val ShelterInk = Color(0xFF0F0F11)            // Level 0 canvas
val ShelterSurface = Color(0xFF1A1A1D)        // Level 1 panels
val ShelterSurfaceRaised = Color(0xFF212124)  // Level 2 raised panels
val ShelterSurfaceActive = Color(0xFF28282D)  // Level 3 active surfaces
val ShelterBorder = Color(0xFF2A2A2E)         // structural 1px partitions
val ShelterOutline = Color(0xFF3A3A40)         // focused / emphasised borders
val ShelterBone = Color(0xFFE8E6E0)            // primary text, never pure white
val ShelterTextDim = Color(0xFF9A9A9E)         // metadata
val ShelterCarbon = Color(0xFF5A5A60)          // dormant markings

// Armed / primary: desaturated industrial gold.
val ShelterSafe = Color(0xFFC9A227)
val ShelterSafeSoft = Color(0xFF282210)        // tinted gold badge fill
val ShelterSafeInk = Color(0xFFD4AA30)         // gold text and icons on dark
val ShelterOnGold = Color(0xFF0D0D0F)          // text on filled gold

// Alert / SOS: desaturated crimson.
val ShelterDanger = Color(0xFF8F2D2D)
val ShelterDangerSoft = Color(0xFF2A1212)
val ShelterDangerInk = Color(0xFFE57373)

// Interactive / info: steel teal.
val ShelterBlue = Color(0xFF3D6B7D)
val ShelterBlueSoft = Color(0xFF7AA2B2)
val ShelterBlueTint = Color(0xFF122026)

val ShelterAmber = ShelterSafeInk
val ShelterAmberInk = ShelterSafeInk

private val ShelterColorScheme = darkColorScheme(
    primary = ShelterSafeInk,
    onPrimary = ShelterOnGold,
    primaryContainer = ShelterSafe,
    onPrimaryContainer = ShelterOnGold,
    secondary = ShelterBlueSoft,
    onSecondary = ShelterOnGold,
    secondaryContainer = ShelterSafe,
    onSecondaryContainer = ShelterOnGold,
    tertiary = ShelterBlueSoft,
    onTertiary = ShelterOnGold,
    error = ShelterDangerInk,
    onError = ShelterOnGold,
    errorContainer = ShelterDanger,
    onErrorContainer = ShelterBone,
    background = ShelterInk,
    onBackground = ShelterBone,
    surface = ShelterSurface,
    onSurface = ShelterBone,
    surfaceVariant = ShelterSurfaceRaised,
    onSurfaceVariant = ShelterTextDim,
    surfaceContainerHighest = ShelterSurfaceRaised,
    surfaceContainerHigh = ShelterSurfaceRaised,
    surfaceContainer = ShelterSurface,
    surfaceContainerLow = ShelterSurface,
    outline = ShelterOutline,
    outlineVariant = ShelterBorder,
)

// space_grotesk.ttf and jetbrains_mono.ttf are variable fonts; without an
// explicit wght axis every weight renders at the file default (too light).
@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight) =
    Font(resId, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

private val SpaceGrotesk = FontFamily(
    variableFont(R.font.space_grotesk, FontWeight.Normal),
    variableFont(R.font.space_grotesk, FontWeight.Medium),
    variableFont(R.font.space_grotesk, FontWeight.SemiBold),
    variableFont(R.font.space_grotesk, FontWeight.Bold),
)
private val JetBrainsMono = FontFamily(
    variableFont(R.font.jetbrains_mono, FontWeight.Normal),
    variableFont(R.font.jetbrains_mono, FontWeight.Medium),
    variableFont(R.font.jetbrains_mono, FontWeight.SemiBold),
    variableFont(R.font.jetbrains_mono, FontWeight.Bold),
)

// Space Grotesk for interface and body, JetBrains Mono for telemetry labels.
private val ShelterTypography = Typography(
    headlineLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.02).em),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.01).em),
    titleSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.01.em),
    labelLarge = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.04.em),
    labelMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.06.em),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
)

// Tight 4px geometry everywhere; pills are drawn explicitly for badges only.
private val ShelterShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

/**
 * Devanagari variant. Space Grotesk and JetBrains Mono have no Hindi glyphs, so
 * Hindi text falls back to the system font; tracking designed for Latin breaks
 * conjuncts (negative spacing can even split a word across lines) and the
 * monospace space glyph leaves wide gaps between Hindi words. Letter spacing
 * is removed and labels use the system font, which carries Devanagari.
 */
private val DevanagariTypography = ShelterTypography.run {
    fun TextStyle.flat() = copy(letterSpacing = 0.sp)
    fun TextStyle.label() = copy(letterSpacing = 0.sp, fontFamily = FontFamily.Default)
    copy(
        headlineLarge = headlineLarge.flat(), headlineMedium = headlineMedium.flat(), headlineSmall = headlineSmall.flat(),
        titleLarge = titleLarge.flat(), titleMedium = titleMedium.flat(), titleSmall = titleSmall.flat(),
        bodyLarge = bodyLarge.flat(), bodyMedium = bodyMedium.flat(), bodySmall = bodySmall.flat(),
        labelLarge = labelLarge.label(), labelMedium = labelMedium.label(), labelSmall = labelSmall.label(),
    )
}

@Composable
fun ShelterTheme(content: @Composable () -> Unit) {
    val hindi = LocalConfiguration.current.locales[0].language == "hi"
    MaterialTheme(
        colorScheme = ShelterColorScheme,
        typography = if (hindi) DevanagariTypography else ShelterTypography,
        shapes = ShelterShapes,
        content = content,
    )
}
