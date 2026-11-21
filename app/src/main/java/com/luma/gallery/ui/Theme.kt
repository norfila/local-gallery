package com.luma.gallery.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.luma.gallery.data.ThemeMode

val LocalGalleryDarkTheme = staticCompositionLocalOf { false }
internal val LocalRoundedPhotoCorners = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Color(0xFF43614E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E8CB), onPrimaryContainer = Color(0xFF233C2A),
    secondary = Color(0xFF626B5B), secondaryContainer = Color(0xFFE3E9D9),
    background = Color(0xFFF7F8F2), onBackground = Color(0xFF1E2B23),
    surface = Color(0xFFF7F8F2), onSurface = Color(0xFF1E2B23),
    surfaceVariant = Color(0xFFEAEDE3), onSurfaceVariant = Color(0xFF71796C),
    outline = Color(0xFFB5BDB0), outlineVariant = Color(0xFFDFE4D8),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFC1DCAB), onPrimary = Color(0xFF253C25),
    primaryContainer = Color(0xFF334C36), onPrimaryContainer = Color(0xFFD7ECC9),
    secondary = Color(0xFFB9C6AE), secondaryContainer = Color(0xFF2B382C),
    background = Color(0xFF111713), onBackground = Color(0xFFE9EDE3),
    surface = Color(0xFF111713), onSurface = Color(0xFFE9EDE3),
    surfaceVariant = Color(0xFF212B23), onSurfaceVariant = Color(0xFFA4AF9F),
    outline = Color(0xFF566251), outlineVariant = Color(0xFF2F3B30),
)

@Composable
fun GalleryTheme(mode: ThemeMode = ThemeMode.SYSTEM, roundedPhotoCorners: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalGalleryDarkTheme provides dark, LocalRoundedPhotoCorners provides roundedPhotoCorners) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-1).sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp),
            titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Medium),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
        ), content = content,
    )
    }
}
