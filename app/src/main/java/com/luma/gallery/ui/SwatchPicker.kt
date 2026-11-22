package com.luma.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val SwatchSize = 40.dp

/** A swatch with room around it for the ring that marks the chosen one. */
private val CellSize = 50.dp

/**
 * The one picker for a colour: round swatches in rows of [columns], spread over the full width. The chosen one is
 * ringed and carries a check in a badge on its edge, so the check stays readable on pale, dark and grey swatches.
 * [swatch] draws an option inside its circle, [name] says it for screen readers, [tag] names it for tests.
 */
@Composable
internal fun <T> SwatchPicker(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    name: @Composable (T) -> String,
    tag: (T) -> String,
    modifier: Modifier = Modifier,
    columns: Int = 5,
    swatch: @Composable (T) -> Unit,
) {
    Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { option ->
                    SwatchCell(option == selected, name(option), tag(option), onClick = { onSelected(option) }) { swatch(option) }
                }
                // A short last row keeps its swatches in the columns of the rows above.
                repeat(columns - row.size) { Spacer(Modifier.size(CellSize)) }
            }
        }
    }
}

@Composable
private fun SwatchCell(selected: Boolean, name: String, tag: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    val onRing = MaterialTheme.colorScheme.surface
    Box(Modifier.size(CellSize)) {
        Box(
            Modifier.size(CellSize).clip(CircleShape)
                .border(2.dp, if (selected) ring else Color.Transparent, CircleShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = name }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(SwatchSize).clip(CircleShape)) { content() }
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(ring).border(2.dp, onRing, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Check, null, Modifier.size(12.dp), tint = onRing)
            }
        }
    }
}
