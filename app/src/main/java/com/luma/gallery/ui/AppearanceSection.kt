package com.luma.gallery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.luma.gallery.R
import com.luma.gallery.data.ColorPalette
import com.luma.gallery.data.ThemeMode

/** The two fixed modes first, then the one that changes between them. */
private val ModeOrder = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)

/**
 * How the app looks, for the inside of the Appearance card. The mode is three small pictures of the library and
 * the colours are round swatches; every picture and swatch is drawn in the colours it stands for, so a choice is
 * seen rather than read. The pictures follow the chosen colours. Tapping applies a choice at once.
 */
@Composable
internal fun AppearanceSection(
    mode: ThemeMode,
    palette: ColorPalette,
    pureBlack: Boolean,
    onMode: (ThemeMode) -> Unit,
    onPalette: (ColorPalette) -> Unit,
    onPureBlack: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val current = palette.inEffect()
    val light = current.colorScheme(context, dark = false)
    val dark = current.colorScheme(context, dark = true, pureBlack = pureBlack)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SubHeading(stringResource(R.string.settings_mode), Modifier.padding(start = 4.dp))
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeOrder.forEach { option ->
                ModeTile(option, option == mode, light, dark, onClick = { onMode(option) }, modifier = Modifier.weight(1f))
            }
        }
    }
    // Only dark mode has a page to make black; the Dark and System pictures show it.
    SwitchRow(stringResource(R.string.settings_pure_black), Icons.Outlined.DarkMode, pureBlack, "pure-black", onPureBlack)
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SubHeading(stringResource(R.string.settings_colors), Modifier.weight(1f))
            Text(stringResource(current.labelRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        // Each swatch in the tones its palette has in the mode the app is in now.
        val inDark = LocalGalleryDarkTheme.current
        SwatchPicker(
            options = availablePalettes,
            selected = current,
            onSelected = onPalette,
            name = { stringResource(it.labelRes) },
            tag = { "palette-${it.name.lowercase()}" },
        ) { option -> PaletteSwatch(option.colorScheme(context, inDark), fromWallpaper = option == ColorPalette.SYSTEM) }
    }
}

/** The small grey title over a group of choices inside the card. */
@Composable
private fun SubHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The right half of whatever it clips, for the System picture. */
private val RightHalf = GenericShape { size, _ -> addRect(Rect(size.width / 2f, 0f, size.width, size.height)) }

/** One mode as a small picture of the library above its name. The chosen one is ringed and carries a check. */
@Composable
private fun ModeTile(mode: ThemeMode, selected: Boolean, light: ColorScheme, dark: ColorScheme, onClick: () -> Unit, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.primary
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("appearance-${mode.name.lowercase()}")
            .padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box {
            Box(Modifier.fillMaxWidth().border(2.dp, if (selected) ring else Color.Transparent, RoundedCornerShape(18.dp)).padding(4.dp)) {
                val screen = RoundedCornerShape(14.dp)
                Box(Modifier.fillMaxWidth().height(88.dp).clip(screen).border(1.dp, MaterialTheme.colorScheme.outlineVariant, screen)) {
                    when (mode) {
                        ThemeMode.LIGHT -> LibraryPicture(light)
                        ThemeMode.DARK -> LibraryPicture(dark)
                        ThemeMode.SYSTEM -> {
                            LibraryPicture(light)
                            LibraryPicture(dark, Modifier.clip(RightHalf))
                        }
                    }
                }
            }
            if (selected) {
                Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(ring), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Text(
            stringResource(mode.labelRes),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) ring else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** The library in miniature: the search field, a few photos and the floating navigation, in [colors]. */
@Composable
private fun LibraryPicture(colors: ColorScheme, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(colors.background).padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().height(9.dp).clip(CircleShape).border(1.dp, colors.outline, CircleShape))
        val photos = listOf(colors.primaryContainer, colors.secondaryContainer, colors.surfaceVariant)
        repeat(2) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { column ->
                    Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(3.dp)).background(photos[(row + column) % photos.size]))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(.7f).height(10.dp).clip(CircleShape).background(colors.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(.34f).fillMaxHeight().clip(CircleShape).background(colors.primaryContainer))
        }
    }
}

/**
 * A palette as a swatch: its main colour on the top half, the tinted container and the third accent below. The
 * System palette carries a wallpaper mark, since that is where its colours come from.
 */
@Composable
private fun PaletteSwatch(colors: ColorScheme, fromWallpaper: Boolean) {
    val edge = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(colors.primary, startAngle = 180f, sweepAngle = 180f, useCenter = true)
            drawArc(colors.tertiary, startAngle = 0f, sweepAngle = 90f, useCenter = true)
            drawArc(colors.secondaryContainer, startAngle = 90f, sweepAngle = 90f, useCenter = true)
            // A fine edge, so a pale swatch does not melt into the card behind it.
            val stroke = 1.dp.toPx()
            drawCircle(edge, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
        }
        if (fromWallpaper) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Wallpaper, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
