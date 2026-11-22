package com.luma.gallery.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.luma.gallery.data.ColorPalette
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every palette must keep text readable where the app puts it: on the page, on cards, in menus (also on the
 * highlighted current choice), in dialogs and in the undo note. WCAG contrast: 4.5 for text, 3 for the quieter
 * secondary text and accents that Gallery uses at medium weight.
 */
class PaletteContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance() + .05, b.luminance() + .05).sortedDescending()
        return (light / dark).toDouble()
    }

    private val pairs: List<Triple<String, (ColorScheme) -> Pair<Color, Color>, Double>> = listOf(
        Triple("onSurface on surface", { it.onSurface to it.surface }, 4.5),
        Triple("onSurface on card (surfaceVariant)", { it.onSurface to it.surfaceVariant }, 4.5),
        Triple("onSurface on menu (surfaceContainer)", { it.onSurface to it.surfaceContainer }, 4.5),
        Triple("onSurface on dialog (surfaceContainerHigh)", { it.onSurface to it.surfaceContainerHigh }, 4.5),
        // Text on the highlight (the current choice in menus, selected rows) always uses onPrimaryContainer, never
        // onSurface: Android's wallpaper colours may make primaryContainer light in dark mode (seen on a Pixel).
        Triple("onPrimaryContainer on primaryContainer", { it.onPrimaryContainer to it.primaryContainer }, 4.5),
        Triple("onSecondaryContainer on secondaryContainer", { it.onSecondaryContainer to it.secondaryContainer }, 4.5),
        Triple("onPrimary on primary", { it.onPrimary to it.primary }, 4.5),
        Triple("inverseOnSurface on inverseSurface", { it.inverseOnSurface to it.inverseSurface }, 4.5),
        Triple("onSurfaceVariant on surface", { it.onSurfaceVariant to it.surface }, 3.0),
        Triple("onSurfaceVariant on card", { it.onSurfaceVariant to it.surfaceVariant }, 3.0),
        Triple("primary on surface", { it.primary to it.surface }, 3.0),
        Triple("primary on card (dropdown value)", { it.primary to it.surfaceVariant }, 3.0),
        Triple("inversePrimary on inverseSurface", { it.inversePrimary to it.inverseSurface }, 3.0),
        Triple("error on surface", { it.error to it.surface }, 3.0),
    )

    @Test fun everyPaletteKeepsTextReadableInLightAndDark() {
        val failures = buildList {
            // Light, dark, and dark with a pure black page.
            for (palette in ColorPalette.entries) for ((dark, black) in listOf(false to false, true to false, true to true)) {
                val scheme = palette.ownColorScheme(dark, black)
                for ((name, colors, minimum) in pairs) {
                    val (text, background) = colors(scheme)
                    val ratio = contrast(text, background)
                    val mode = if (black) "pure black" else if (dark) "dark" else "light"
                    if (ratio < minimum) add("$palette $mode: $name = ${"%.2f".format(ratio)} (< $minimum)")
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
