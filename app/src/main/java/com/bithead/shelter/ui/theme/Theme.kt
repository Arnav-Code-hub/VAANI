package com.bithead.shelter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bithead.shelter.R

// Stitch's Calm Sanctuary palette: quiet cream surfaces and deep slate sage.
val ShelterInk = Color(0xFFFCF9F3)
val ShelterSurface = Color(0xFFF0EEE8)
val ShelterSurfaceRaised = Color(0xFFEBE8E2)
val ShelterOutline = Color(0xFF717974)

val ShelterBlue = Color(0xFF385E4E)
val ShelterBlueSoft = Color(0xFFA6D0BC)
val ShelterSafe = Color(0xFF204637)
val ShelterSafeSoft = Color(0xFFC2ECD7)
val ShelterDanger = Color(0xFFBA1A1A)
val ShelterDangerSoft = Color(0xFFFFDAD6)
val ShelterAmber = Color(0xFF8D5200)
val ShelterTextDim = Color(0xFF52606A)

private val ShelterColorScheme = lightColorScheme(
    primary = ShelterSafe,
    onPrimary = Color.White,
    primaryContainer = ShelterBlue,
    onPrimaryContainer = ShelterBlueSoft,
    secondary = ShelterTextDim,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E2ED),
    onSecondaryContainer = Color(0xFF3B4952),
    tertiary = Color(0xFF344333),
    onTertiary = Color.White,
    error = ShelterDanger,
    onError = Color.White,
    errorContainer = ShelterDangerSoft,
    onErrorContainer = Color(0xFF93000A),
    background = ShelterInk,
    onBackground = Color(0xFF1C1C18),
    surface = ShelterSurface,
    onSurface = Color(0xFF1C1C18),
    surfaceVariant = Color(0xFFE5E2DC),
    onSurfaceVariant = ShelterTextDim,
    outline = ShelterOutline,
    outlineVariant = Color(0xFFC1C8C3),
)

private val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
)
private val Jakarta = FontFamily(
    Font(R.font.plus_jakarta_sans_400, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_500, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_600, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_700, FontWeight.Bold),
)

private val ShelterTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

@Composable
fun ShelterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShelterColorScheme,
        typography = ShelterTypography,
        content = content,
    )
}
