package com.luma.gallery.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.luma.gallery.data.ColorPalette
import com.luma.gallery.data.ThemeMode

val LocalGalleryDarkTheme = staticCompositionLocalOf { false }
internal val LocalRoundedPhotoCorners = staticCompositionLocalOf { false }

/** The accent on the photo viewer's always-dark controls: the palette's dark-mode primary. */
internal val LocalViewerAccent = staticCompositionLocalOf { Color(0xFFC1DCAB) }

@Composable
fun GalleryTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    palette: ColorPalette = ColorPalette.SAGE,
    pureBlack: Boolean = false,
    roundedPhotoCorners: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    // Not remembered: System colours follow the wallpaper, which can change while the app runs; building is cheap.
    val colors = palette.colorScheme(context, dark, pureBlack)
    val viewerAccent = if (dark) colors.primary else palette.colorScheme(context, dark = true).primary
    CompositionLocalProvider(LocalGalleryDarkTheme provides dark, LocalRoundedPhotoCorners provides roundedPhotoCorners,
        LocalViewerAccent provides viewerAccent) {
    MaterialTheme(
        colorScheme = colors,
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
