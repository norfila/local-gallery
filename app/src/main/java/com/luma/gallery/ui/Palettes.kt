package com.luma.gallery.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.luma.gallery.data.ColorPalette

/*
 * The colours Gallery can wear (Settings / Appearance / Colors). Sage is Gallery's own and the default; System
 * takes Android's colours from Android 12 on; the others are Material tonal palettes.
 *
 * A palette is five ramps from dark to light: three accents, the greys, and the tinted greys. They were made with
 * Material's colour tool (@material/material-color-utilities, TonalPalette.fromHueAndChroma): chroma 36 for
 * primary, 16 for secondary, 24 at hue + 60 for tertiary, 6 for the greys and 8 for the tinted greys, Material's
 * "tonal spot" recipe, the one Android uses for wallpaper colours. Gray uses 12, 8, 16, 2 and 2 at its own hue.
 * Only the tones the colour roles below are made from are kept; 0 would be black and 100 is white.
 *
 * Gallery then moves a few roles (galleryRoles): its cards are surfaceVariant one step above the page, its
 * secondary text is quieter and its lines finer than Material's defaults, so every palette has Sage's proportions.
 */

/** Android has colours of its own to give, taken from the wallpaper or chosen in its settings, from version 12 on. */
private val systemColorsAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** The palettes that can be chosen on this phone. */
internal val availablePalettes: List<ColorPalette>
    get() = if (systemColorsAvailable) ColorPalette.entries else ColorPalette.entries - ColorPalette.SYSTEM

/** The palette in effect for a stored choice: where Android has no colours to give, Sage. */
internal fun ColorPalette.inEffect(): ColorPalette =
    if (this == ColorPalette.SYSTEM && !systemColorsAvailable) ColorPalette.SAGE else this

/**
 * The colours the app wears with this palette in light or [dark] mode; Settings uses it to draw the choices too.
 * With [pureBlack], the page is black in dark mode.
 */
internal fun ColorPalette.colorScheme(context: Context, dark: Boolean, pureBlack: Boolean = false): ColorScheme =
    // The version is asked here as well, in the form the compiler's API check understands.
    if (inEffect() == ColorPalette.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).galleryRoles(dark).black(dark && pureBlack)
    else ownColorScheme(dark, pureBlack)

/** The app's own colours for this palette (Sage stands in for System), without needing Android's. */
internal fun ColorPalette.ownColorScheme(dark: Boolean, pureBlack: Boolean = false): ColorScheme {
    val own = if (this == ColorPalette.SYSTEM) ColorPalette.SAGE else this
    val scheme = own.materialScheme(dark).galleryRoles(dark)
    return (if (own == ColorPalette.SAGE) scheme.sage(dark) else scheme).black(dark && pureBlack)
}

/** Pure black: the page, and what lies flat on it, is black; cards, menus and bars keep their raised tones. */
private fun ColorScheme.black(on: Boolean): ColorScheme = if (!on) this else copy(
    background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
)

private fun ColorPalette.materialScheme(dark: Boolean): ColorScheme = if (dark) darkScheme() else lightScheme()

/** Cards one step above the page, quieter secondary text and finer lines than Material's defaults, as in Sage. */
private fun ColorScheme.galleryRoles(dark: Boolean): ColorScheme = if (dark) copy(
    surfaceVariant = surfaceContainerHigh,
    onSurfaceVariant = lerp(outline, onSurfaceVariant, .5f),
    outline = lerp(outlineVariant, outline, .33f),
    outlineVariant = surfaceContainerHighest,
) else copy(
    surfaceVariant = surfaceContainer,
    onSurfaceVariant = outline,
    outline = outlineVariant,
    outlineVariant = lerp(outlineVariant, surface, .5f),
)

/** Sage: Gallery's hand-picked colours, unchanged since the first version; the green palette fills the other roles. */
private fun ColorScheme.sage(dark: Boolean): ColorScheme = if (dark) copy(
    primary = Color(0xFFC1DCAB), onPrimary = Color(0xFF253C25),
    primaryContainer = Color(0xFF334C36), onPrimaryContainer = Color(0xFFD7ECC9),
    secondary = Color(0xFFB9C6AE), secondaryContainer = Color(0xFF2B382C),
    background = Color(0xFF111713), onBackground = Color(0xFFE9EDE3),
    surface = Color(0xFF111713), onSurface = Color(0xFFE9EDE3),
    surfaceVariant = Color(0xFF212B23), onSurfaceVariant = Color(0xFFA4AF9F),
    outline = Color(0xFF566251), outlineVariant = Color(0xFF2F3B30),
) else copy(
    primary = Color(0xFF43614E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E8CB), onPrimaryContainer = Color(0xFF233C2A),
    secondary = Color(0xFF626B5B), secondaryContainer = Color(0xFFE3E9D9),
    background = Color(0xFFF7F8F2), onBackground = Color(0xFF1E2B23),
    surface = Color(0xFFF7F8F2), onSurface = Color(0xFF1E2B23),
    surfaceVariant = Color(0xFFEAEDE3), onSurfaceVariant = Color(0xFF71796C),
    outline = Color(0xFFB5BDB0), outlineVariant = Color(0xFFDFE4D8),
)

private val AccentSteps = intArrayOf(10, 20, 30, 40, 80, 90, 100)
private val GreySteps = intArrayOf(4, 6, 10, 12, 17, 20, 22, 24, 87, 90, 92, 94, 95, 96, 98, 100)
private val TintedSteps = intArrayOf(30, 50, 60, 80, 90)

/** The ramps of one palette, each holding the tones listed in the steps above, in that order. */
private class Tones(
    val primary: LongArray,
    val secondary: LongArray,
    val tertiary: LongArray,
    val neutral: LongArray,
    val neutralVariant: LongArray,
)

private fun LongArray.accent(tone: Int) = Color(this[AccentSteps.indexOf(tone)])
private fun LongArray.grey(tone: Int) = Color(this[GreySteps.indexOf(tone)])
private fun LongArray.tinted(tone: Int) = Color(this[TintedSteps.indexOf(tone)])

/** The palette's colours for light mode, with each role at the tone Material gives it. */
private fun ColorPalette.lightScheme(): ColorScheme = with(tonesOf(this)) {
    lightColorScheme(
        primary = primary.accent(40),
        onPrimary = primary.accent(100),
        primaryContainer = primary.accent(90),
        onPrimaryContainer = primary.accent(10),
        inversePrimary = primary.accent(80),
        secondary = secondary.accent(40),
        onSecondary = secondary.accent(100),
        secondaryContainer = secondary.accent(90),
        onSecondaryContainer = secondary.accent(10),
        tertiary = tertiary.accent(40),
        onTertiary = tertiary.accent(100),
        tertiaryContainer = tertiary.accent(90),
        onTertiaryContainer = tertiary.accent(10),
        background = neutral.grey(98),
        onBackground = neutral.grey(10),
        surface = neutral.grey(98),
        onSurface = neutral.grey(10),
        surfaceVariant = neutralVariant.tinted(90),
        onSurfaceVariant = neutralVariant.tinted(30),
        surfaceTint = primary.accent(40),
        inverseSurface = neutral.grey(20),
        inverseOnSurface = neutral.grey(95),
        outline = neutralVariant.tinted(50),
        outlineVariant = neutralVariant.tinted(80),
        surfaceBright = neutral.grey(98),
        surfaceDim = neutral.grey(87),
        surfaceContainerLowest = neutral.grey(100),
        surfaceContainerLow = neutral.grey(96),
        surfaceContainer = neutral.grey(94),
        surfaceContainerHigh = neutral.grey(92),
        surfaceContainerHighest = neutral.grey(90),
    )
}

/** The palette's colours for dark mode, with each role at the tone Material gives it. */
private fun ColorPalette.darkScheme(): ColorScheme = with(tonesOf(this)) {
    darkColorScheme(
        primary = primary.accent(80),
        onPrimary = primary.accent(20),
        primaryContainer = primary.accent(30),
        onPrimaryContainer = primary.accent(90),
        inversePrimary = primary.accent(40),
        secondary = secondary.accent(80),
        onSecondary = secondary.accent(20),
        secondaryContainer = secondary.accent(30),
        onSecondaryContainer = secondary.accent(90),
        tertiary = tertiary.accent(80),
        onTertiary = tertiary.accent(20),
        tertiaryContainer = tertiary.accent(30),
        onTertiaryContainer = tertiary.accent(90),
        background = neutral.grey(6),
        onBackground = neutral.grey(90),
        surface = neutral.grey(6),
        onSurface = neutral.grey(90),
        surfaceVariant = neutralVariant.tinted(30),
        onSurfaceVariant = neutralVariant.tinted(80),
        surfaceTint = primary.accent(80),
        inverseSurface = neutral.grey(90),
        inverseOnSurface = neutral.grey(20),
        outline = neutralVariant.tinted(60),
        outlineVariant = neutralVariant.tinted(30),
        surfaceBright = neutral.grey(24),
        surfaceDim = neutral.grey(6),
        surfaceContainerLowest = neutral.grey(4),
        surfaceContainerLow = neutral.grey(10),
        surfaceContainer = neutral.grey(12),
        surfaceContainerHigh = neutral.grey(17),
        surfaceContainerHighest = neutral.grey(22),
    )
}

private fun tonesOf(palette: ColorPalette): Tones = when (palette) {
    ColorPalette.TEAL -> Tones(
        primary = longArrayOf(0xFF002021, 0xFF003738, 0xFF004F51, 0xFF00696B, 0xFF80D4D6, 0xFF9CF1F3, 0xFFFFFFFF),
        secondary = longArrayOf(0xFF041F20, 0xFF1B3435, 0xFF324B4C, 0xFF4A6363, 0xFFB0CCCC, 0xFFCCE8E8, 0xFFFFFFFF),
        tertiary = longArrayOf(0xFF061C36, 0xFF1D314C, 0xFF354863, 0xFF4C5F7C, 0xFFB4C8E9, 0xFFD4E3FF, 0xFFFFFFFF),
        neutral = longArrayOf(
            0xFF090F0F, 0xFF0E1415, 0xFF161D1D, 0xFF1A2121, 0xFF252B2B, 0xFF2B3232, 0xFF2F3636, 0xFF343A3A,
            0xFFD5DBDB, 0xFFDDE4E3, 0xFFE3E9E9, 0xFFE9EFEF, 0xFFECF2F1, 0xFFEFF5F4, 0xFFF4FBFA, 0xFFFFFFFF,
        ),
        neutralVariant = longArrayOf(0xFF3F4949, 0xFF6F7979, 0xFF899392, 0xFFBEC8C8, 0xFFDAE4E4),
    )
    ColorPalette.BLUE -> Tones(
        primary = longArrayOf(0xFF001A40, 0xFF0D2F60, 0xFF294677, 0xFF425E91, 0xFFACC7FF, 0xFFD7E2FF, 0xFFFFFFFF),
        secondary = longArrayOf(0xFF131C2C, 0xFF283041, 0xFF3F4759, 0xFF565E71, 0xFFBEC6DC, 0xFFDAE2F9, 0xFFFFFFFF),
        tertiary = longArrayOf(0xFF29132E, 0xFF3F2844, 0xFF573E5B, 0xFF705574, 0xFFDDBCE0, 0xFFFAD7FC, 0xFFFFFFFF),
        neutral = longArrayOf(
            0xFF0C0E13, 0xFF111318, 0xFF1A1C20, 0xFF1E2025, 0xFF282A2F, 0xFF2E3036, 0xFF33353A, 0xFF37393E,
            0xFFD9D9E0, 0xFFE2E2E9, 0xFFE8E7EE, 0xFFEDEDF4, 0xFFF0F0F7, 0xFFF3F3FA, 0xFFF9F9FF, 0xFFFFFFFF,
        ),
        neutralVariant = longArrayOf(0xFF44474E, 0xFF74777F, 0xFF8E9099, 0xFFC4C6D0, 0xFFE0E2EC),
    )
    ColorPalette.PURPLE -> Tones(
        primary = longArrayOf(0xFF220F46, 0xFF37265C, 0xFF4E3D75, 0xFF67558E, 0xFFD1BCFD, 0xFFEADDFF, 0xFFFFFFFF),
        secondary = longArrayOf(0xFF1E192B, 0xFF332D41, 0xFF4A4358, 0xFF625B70, 0xFFCCC2DB, 0xFFE9DEF8, 0xFFFFFFFF),
        tertiary = longArrayOf(0xFF31101C, 0xFF4A2531, 0xFF643B47, 0xFF7E525F, 0xFFF0B8C6, 0xFFFFD9E2, 0xFFFFFFFF),
        neutral = longArrayOf(
            0xFF0F0D13, 0xFF141218, 0xFF1D1B20, 0xFF211F24, 0xFF2B292F, 0xFF322F35, 0xFF36343A, 0xFF3B383E,
            0xFFDED8E0, 0xFFE7E0E8, 0xFFECE6EE, 0xFFF2ECF4, 0xFFF5EFF7, 0xFFF8F1FA, 0xFFFEF7FF, 0xFFFFFFFF,
        ),
        neutralVariant = longArrayOf(0xFF49454E, 0xFF7A757F, 0xFF948F99, 0xFFCBC4CF, 0xFFE7E0EB),
    )
    ColorPalette.PINK -> Tones(
        primary = longArrayOf(0xFF39071F, 0xFF541D35, 0xFF6F334B, 0xFF8B4A63, 0xFFFFB0CC, 0xFFFFD9E4, 0xFFFFFFFF),
        secondary = longArrayOf(0xFF2A151D, 0xFF422932, 0xFF5A3F48, 0xFF735660, 0xFFE2BDC8, 0xFFFFD9E4, 0xFFFFFFFF),
        tertiary = longArrayOf(0xFF2F1500, 0xFF48290D, 0xFF623F21, 0xFF7D5636, 0xFFF0BC95, 0xFFFFDCC4, 0xFFFFFFFF),
        neutral = longArrayOf(
            0xFF130C0F, 0xFF191114, 0xFF22191C, 0xFF261D20, 0xFF31282A, 0xFF372E31, 0xFF3C3235, 0xFF403739,
            0xFFE6D6D9, 0xFFEEDFE2, 0xFFF4E4E7, 0xFFFAEAED, 0xFFFDEDF0, 0xFFFFF0F3, 0xFFFFF8F8, 0xFFFFFFFF,
        ),
        neutralVariant = longArrayOf(0xFF514347, 0xFF837377, 0xFF9D8C91, 0xFFD5C2C6, 0xFFF2DDE2),
    )
    ColorPalette.RED -> Tones(
        primary = longArrayOf(0xFF3B0907, 0xFF561E19, 0xFF73342D, 0xFF904A43, 0xFFFFB4AB, 0xFFFFDAD5, 0xFFFFFFFF),
        secondary = longArrayOf(0xFF2C1512, 0xFF442926, 0xFF5D3F3C, 0xFF775652, 0xFFE7BDB7, 0xFFFFDAD5, 0xFFFFFFFF),
        tertiary = longArrayOf(0xFF261A00, 0xFF3F2E04, 0xFF574419, 0xFF715B2E, 0xFFE0C38C, 0xFFFDDFA6, 0xFFFFFFFF),
        neutral = longArrayOf(
            0xFF140C0B, 0xFF1A1110, 0xFF231918, 0xFF271D1C, 0xFF322826, 0xFF392E2D, 0xFF3D3231, 0xFF423735,
            0xFFE8D6D4, 0xFFF1DEDC, 0xFFF6E4E2, 0xFFFCEAE7, 0xFFFFEDEA, 0xFFFFF0EE, 0xFFFFF8F7, 0xFFFFFFFF,
        ),
        neutralVariant = longArrayOf(0xFF534341, 0xFF857371, 0xFFA08C8A, 0xFFD8C2BF, 0xFFF5DDDA),
    )
    ColorPalette.ORANGE -> Tones(
        primary = longArrayOf(0xFF2E1500, 0xFF4D2600, 0xFF6C3A06, 0xFF88511E, 0xFFFFB77C, 0xFFFFDCC3, 0xFFFFFFFF),
        secondary = longArrayOf(0xFF2A1707, 0xFF412C1A, 0xFF5A422E, 0xFF745944, 0xFFE3C0A6, 0xFFFFDCC3, 0xFFFFFFFF),
        tertiary = longArrayOf(0xFF191E00, 0xFF2E330D, 0xFF444A21, 0xFF5C6237, 0xFFC5CB96, 0xFFE1E7B0, 0xFFFFFFFF),
        neutral = longArrayOf(
            0xFF140D08, 0xFF19120C, 0xFF221A14, 0xFF261E18, 0xFF312822, 0xFF382F28, 0xFF3C332C, 0xFF413731,
            0xFFE7D7CD, 0xFFEFE0D6, 0xFFF5E5DB, 0xFFFBEBE1, 0xFFFEEEE4, 0xFFFFF1E9, 0xFFFFF8F5, 0xFFFFFFFF,
        ),
        neutralVariant = longArrayOf(0xFF51443B, 0xFF847469, 0xFF9E8E82, 0xFFD6C3B6, 0xFFF3DFD2),
    )
    ColorPalette.OLIVE -> Tones(
        primary = longArrayOf(0xFF201C00, 0xFF383000, 0xFF514700, 0xFF6A5F11, 0xFFD7C770, 0xFFF4E489, 0xFFFFFFFF),
        secondary = longArrayOf(0xFF201C05, 0xFF353117, 0xFF4C472B, 0xFF655F41, 0xFFCFC7A2, 0xFFECE3BD, 0xFFFFFFFF),
        tertiary = longArrayOf(0xFF002112, 0xFF123724, 0xFF2A4E3A, 0xFF426651, 0xFFA8D0B5, 0xFFC3ECD1, 0xFFFFFFFF),
        neutral = longArrayOf(
            0xFF100E07, 0xFF15130C, 0xFF1D1C13, 0xFF222017, 0xFF2C2A21, 0xFF333027, 0xFF37352B, 0xFF3C3930,
            0xFFDFDACC, 0xFFE8E2D4, 0xFFEEE8DA, 0xFFF3EDE0, 0xFFF6F0E2, 0xFFF9F3E5, 0xFFFFF9EB, 0xFFFFFFFF,
        ),
        neutralVariant = longArrayOf(0xFF4A4739, 0xFF7B7768, 0xFF959180, 0xFFCCC6B5, 0xFFE9E2D0),
    )
    // Green carries Sage (and System where Android has no colours to give); Sage then sets its own main roles.
    ColorPalette.SAGE, ColorPalette.SYSTEM -> Tones(
        primary = longArrayOf(0xFF002203, 0xFF0B390F, 0xFF245023, 0xFF3C6839, 0xFFA2D399, 0xFFBDF0B3, 0xFFFFFFFF),
        secondary = longArrayOf(0xFF111F0F, 0xFF253423, 0xFF3B4B38, 0xFF53634F, 0xFFBACCB3, 0xFFD6E8CE, 0xFFFFFFFF),
        tertiary = longArrayOf(0xFF002022, 0xFF00363B, 0xFF1E4D52, 0xFF38656A, 0xFFA0CFD4, 0xFFBCEBF0, 0xFFFFFFFF),
        neutral = longArrayOf(
            0xFF0B0F0A, 0xFF10140F, 0xFF191D17, 0xFF1D211B, 0xFF272B25, 0xFF2D322B, 0xFF323630, 0xFF363A34,
            0xFFD8DBD2, 0xFFE0E4DB, 0xFFE6E9E0, 0xFFECEFE6, 0xFFEFF2E9, 0xFFF1F5EB, 0xFFF7FBF1, 0xFFFFFFFF,
        ),
        neutralVariant = longArrayOf(0xFF424940, 0xFF72796F, 0xFF8C9388, 0xFFC2C8BD, 0xFFDEE5D8),
    )
    ColorPalette.GRAY -> Tones(
        primary = longArrayOf(0xFF141C26, 0xFF29313C, 0xFF3F4753, 0xFF575F6B, 0xFFBFC7D5, 0xFFDBE3F1, 0xFFFFFFFF),
        secondary = longArrayOf(0xFF171C22, 0xFF2C3137, 0xFF42474E, 0xFF5A5F66, 0xFFC3C7CF, 0xFFDFE2EB, 0xFFFFFFFF),
        tertiary = longArrayOf(0xFF0F1C2B, 0xFF253140, 0xFF3B4858, 0xFF535F70, 0xFFBAC8DB, 0xFFD6E3F7, 0xFFFFFFFF),
        neutral = longArrayOf(
            0xFF0E0E0F, 0xFF131314, 0xFF1B1B1D, 0xFF1F1F21, 0xFF2A2A2B, 0xFF303031, 0xFF353536, 0xFF39393A,
            0xFFDBD9DB, 0xFFE4E2E3, 0xFFEAE7E9, 0xFFF0EDEE, 0xFFF2F0F1, 0xFFF5F3F4, 0xFFFBF9FA, 0xFFFFFFFF,
        ),
        neutralVariant = longArrayOf(0xFF474748, 0xFF777778, 0xFF919092, 0xFFC8C6C7, 0xFFE4E2E3),
    )
}
