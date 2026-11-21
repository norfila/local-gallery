package com.luma.gallery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.luma.gallery.R
import com.luma.gallery.data.PhotoLayout

/** A lightweight arrangement sample that never reads photos from the library. */
@Composable
internal fun PhotoArrangementPreview(
    columns: Int,
    layout: PhotoLayout,
    roundedCorners: Boolean,
    modifier: Modifier = Modifier,
) {
    val columnCount = columns.coerceIn(1, 6)
    val corners = stringResource(if (roundedCorners) R.string.corners_rounded else R.string.corners_square)
    val description = if (layout == PhotoLayout.LIST) stringResource(R.string.arrangement_list_state, stringResource(layout.labelRes), corners)
        else stringResource(R.string.arrangement_state, stringResource(layout.labelRes),
            pluralStringResource(R.plurals.column_count, columnCount, columnCount), corners)
    val previewDescription = stringResource(R.string.arrangement_preview_description, description)
    val palettes = previewPalettes()
    val lines = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier.fillMaxWidth()
            .height(if (layout == PhotoLayout.GRID) 68.dp else 82.dp)
            .testTag("photo-arrangement-preview")
            .semantics {
                role = Role.Image
                contentDescription = previewDescription
                stateDescription = description
            },
    ) {
        if (layout == PhotoLayout.LIST) {
            drawSampleRows(roundedCorners, palettes, lines)
            return@Canvas
        }
        val gap = 4.dp.toPx()
        val availableTileWidth = ((size.width - gap * (columnCount - 1)) / columnCount).coerceAtLeast(0f)
        val tileWidth = if (layout == PhotoLayout.GRID) minOf(availableTileWidth, size.height) else availableTileWidth
        val leftInset = (size.width - (tileWidth * columnCount + gap * (columnCount - 1))) / 2f
        repeat(columnCount) { column ->
            val firstHeight = if (layout == PhotoLayout.GRID) {
                tileWidth
            } else {
                (size.height - gap) * listOf(.62f, .38f, .52f)[column % 3]
            }
            repeat(if (layout == PhotoLayout.GRID) 1 else 2) { row ->
                val top = if (layout == PhotoLayout.GRID) (size.height - tileWidth) / 2f else if (row == 0) 0f else firstHeight + gap
                val tileHeight = if (row == 0) firstHeight else size.height - top
                val tileSize = Size(tileWidth, tileHeight)
                val radius = if (roundedCorners) 12.dp.toPx().coerceAtMost(minOf(tileWidth, tileHeight) / 2f) else 0f
                val clip = Path().apply {
                    addRoundRect(RoundRect(Rect(Offset.Zero, tileSize), CornerRadius(radius)))
                }
                translate(left = leftInset + column * (tileWidth + gap), top = top) {
                    clipPath(clip) {
                        drawSampleLandscape(tileSize, palettes[(column + row * columnCount) % palettes.size])
                    }
                }
            }
        }
    }
}

@Composable
private fun previewPalettes(): List<List<Color>> {
    val surface = MaterialTheme.colorScheme.surface
    val tintAmount = if (surface.luminance() < .5f) .58f else .96f
    return listOf(
        listOf(Color(0xFFC8D9CC), Color(0xFFA6C1B2), Color(0xFF648574), Color(0xFF385C4D)),
        listOf(Color(0xFFE3D0AE), Color(0xFFCEB08F), Color(0xFFB49170), Color(0xFF806346)),
        listOf(Color(0xFFD0DEE5), Color(0xFFAFC7D5), Color(0xFF7F9EAD), Color(0xFF4F6D7C)),
    ).map { palette -> palette.map { lerp(surface, it, tintAmount) } }
}

/** Three table rows: a small thumbnail followed by a name and a details line. */
private fun DrawScope.drawSampleRows(roundedCorners: Boolean, palettes: List<List<Color>>, lines: Color) {
    val rows = 3
    val gap = 5.dp.toPx()
    val rowHeight = (size.height - gap * (rows - 1)) / rows
    repeat(rows) { row ->
        val top = row * (rowHeight + gap)
        val thumb = Size(rowHeight, rowHeight)
        val radius = if (roundedCorners) 6.dp.toPx() else 0f
        translate(left = 0f, top = top) {
            clipPath(Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, thumb), CornerRadius(radius))) }) {
                drawSampleLandscape(thumb, palettes[row % palettes.size])
            }
        }
        val textLeft = rowHeight + 10.dp.toPx()
        val bar = 6.dp.toPx()
        drawRoundRect(lines.copy(alpha = .55f), Offset(textLeft, top + rowHeight * .22f), Size((size.width - textLeft) * (.55f - row * .08f), bar), CornerRadius(bar / 2))
        drawRoundRect(lines.copy(alpha = .28f), Offset(textLeft, top + rowHeight * .6f), Size((size.width - textLeft) * .38f, bar * .8f), CornerRadius(bar / 2))
    }
}

/** Folder sample: covers with a name bar below each, or the same table rows as the photo list. */
@Composable
internal fun FolderArrangementPreview(
    columns: Int,
    layout: PhotoLayout,
    roundedCorners: Boolean,
    modifier: Modifier = Modifier,
) {
    val columnCount = columns.coerceIn(1, 6)
    val corners = stringResource(if (roundedCorners) R.string.corners_rounded else R.string.corners_square)
    val description = if (layout == PhotoLayout.LIST) stringResource(R.string.arrangement_list_state, stringResource(layout.labelRes), corners)
        else stringResource(R.string.arrangement_state, stringResource(layout.labelRes),
            pluralStringResource(R.plurals.column_count, columnCount, columnCount), corners)
    val previewDescription = stringResource(R.string.folder_arrangement_preview_description, description)
    val palettes = previewPalettes()
    val lines = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier.fillMaxWidth().height(if (layout == PhotoLayout.LIST) 82.dp else 92.dp)
            .testTag("folder-arrangement-preview")
            .semantics {
                role = Role.Image
                contentDescription = previewDescription
                stateDescription = description
            },
    ) {
        if (layout == PhotoLayout.LIST) {
            drawSampleRows(roundedCorners, palettes, lines)
            return@Canvas
        }
        val gap = 6.dp.toPx()
        val labelSpace = 16.dp.toPx()
        val tileWidth = minOf((size.width - gap * (columnCount - 1)) / columnCount, (size.height - labelSpace) / .87f)
        val tileHeight = tileWidth * .87f
        val leftInset = (size.width - (tileWidth * columnCount + gap * (columnCount - 1))) / 2f
        val radius = if (roundedCorners) 10.dp.toPx().coerceAtMost(tileWidth / 4f) else 0f
        repeat(columnCount) { column ->
            val left = leftInset + column * (tileWidth + gap)
            val tile = Size(tileWidth, tileHeight)
            translate(left = left, top = 0f) {
                clipPath(Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, tile), CornerRadius(radius))) }) {
                    drawSampleLandscape(tile, palettes[column % palettes.size])
                }
            }
            val bar = 5.dp.toPx()
            drawRoundRect(lines.copy(alpha = .5f), Offset(left + 2.dp.toPx(), tileHeight + 6.dp.toPx()), Size(tileWidth * .62f, bar), CornerRadius(bar / 2))
        }
    }
}

private fun DrawScope.drawSampleLandscape(tileSize: Size, colors: List<Color>) {
    val width = tileSize.width
    val height = tileSize.height
    drawRect(
        Brush.linearGradient(colors.take(2), end = Offset(width, height)),
        size = tileSize,
    )
    drawPath(
        Path().apply {
            moveTo(0f, height * .83f)
            lineTo(width * .24f, height * .32f)
            lineTo(width * .53f, height * .78f)
            lineTo(width * .78f, height * .44f)
            lineTo(width, height * .79f)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        },
        colors[2],
    )
    drawPath(
        Path().apply {
            moveTo(0f, height * .95f)
            lineTo(width * .33f, height * .63f)
            lineTo(width * .61f, height * .92f)
            lineTo(width * .84f, height * .71f)
            lineTo(width, height * .92f)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        },
        colors[3],
    )
}
