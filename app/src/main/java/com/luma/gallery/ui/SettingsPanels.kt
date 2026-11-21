@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.luma.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.gallery.R
import com.luma.gallery.data.*
import com.luma.gallery.widget.AlbumWidgetSize

/** The same preview and controls as Settings / Photos, available from the library header. */
@Composable
internal fun PhotoLayoutDialog(
    columns: Int,
    layout: PhotoLayout,
    roundedCorners: Boolean,
    onColumns: (Int) -> Unit,
    onLayout: (PhotoLayout) -> Unit,
    onRoundedCorners: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Pinching changes columns, so the tip is only useful for grid and masonry.
    ArrangementSheet(R.string.arrangement_title, "photo-layout-sheet", onDismiss, hint = R.string.arrangement_hint.takeIf { layout != PhotoLayout.LIST },
        preview = { PhotoArrangementPreview(columns, layout, roundedCorners, Modifier.padding(14.dp)) }) {
        LayoutChoiceRow(layout, onLayout, PhotoLayout.entries)
        if (layout != PhotoLayout.LIST) {
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ColumnChoiceRow(stringResource(R.string.settings_photos_per_row), columns, "columns-choice", onColumns)
        }
        HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow(stringResource(R.string.settings_rounded_corners), Icons.Outlined.RoundedCorner, roundedCorners,
            "arrangement-rounded-corners", onRoundedCorners)
    }
}

/** Folder counterpart: grid or list, folders per row and corner style (Masonry has no meaning for folders). */
@Composable
internal fun FolderLayoutDialog(
    columns: Int,
    layout: PhotoLayout,
    roundedCorners: Boolean,
    onColumns: (Int) -> Unit,
    onLayout: (PhotoLayout) -> Unit,
    onRoundedCorners: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ArrangementSheet(R.string.folder_arrangement_title, "folder-layout-sheet", onDismiss, hint = null,
        preview = { FolderArrangementPreview(columns, layout, roundedCorners, Modifier.padding(14.dp)) }) {
        FolderArrangementControls(columns, layout, roundedCorners, onColumns, onLayout, onRoundedCorners)
    }
}

@Composable
internal fun FolderArrangementControls(
    columns: Int,
    layout: PhotoLayout,
    roundedCorners: Boolean,
    onColumns: (Int) -> Unit,
    onLayout: (PhotoLayout) -> Unit,
    onRoundedCorners: (Boolean) -> Unit,
) {
    LayoutChoiceRow(layout, onLayout, PhotoLayout.folderChoices, tagPrefix = "folder-layout")
    if (layout != PhotoLayout.LIST) {
        HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
        ColumnChoiceRow(stringResource(R.string.settings_folders_per_row), columns, "folder-columns-choice", onColumns, Icons.Outlined.FolderOpen)
    }
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
    SwitchRow(stringResource(R.string.settings_rounded_folder_corners), Icons.Outlined.RoundedCorner, roundedCorners,
        "rounded-folder-corners", onRoundedCorners)
}

@Composable
private fun ArrangementSheet(title: Int, tag: String, onDismiss: () -> Unit, hint: Int?, preview: @Composable () -> Unit, controls: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, modifier = Modifier.testTag(tag)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant) { preview() }
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Column(content = controls) }
            if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun PhotoLayout.icon(): ImageVector = when (this) {
    PhotoLayout.GRID -> Icons.Outlined.GridView
    PhotoLayout.MASONRY -> Icons.Outlined.Dashboard
    PhotoLayout.LIST -> Icons.AutoMirrored.Outlined.ViewList
}

/** Layout options as a full-width segmented choice with icons. */
@Composable
internal fun LayoutChoiceRow(layout: PhotoLayout, onLayout: (PhotoLayout) -> Unit, options: List<PhotoLayout> = PhotoLayout.entries,
    tagPrefix: String = "settings-layout") {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Dashboard, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.settings_layout), style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth().padding(start = 29.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            .padding(3.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { option ->
                val chosen = layout == option
                val color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                Row(Modifier.weight(1f).clip(CircleShape).background(if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .selectable(chosen, role = Role.RadioButton, onClick = { onLayout(option) })
                    .testTag("$tagPrefix-${option.name.lowercase()}").heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
                    Icon(option.icon(), null, Modifier.size(16.dp), tint = color)
                    Text(stringResource(option.labelRes), color = color, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/** One tap per column count, instead of a dropdown. */
@Composable
internal fun ColumnChoiceRow(label: String, columns: Int, tag: String, onColumns: (Int) -> Unit, icon: ImageVector = Icons.Outlined.ViewColumn) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(pluralStringResource(R.plurals.per_row, columns, columns), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(start = 29.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..6).forEach { count ->
                val chosen = count == columns
                val description = pluralStringResource(R.plurals.per_row, count, count)
                Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(CircleShape)
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                    .selectable(chosen, role = Role.RadioButton, onClick = { onColumns(count) })
                    .semantics { contentDescription = description }
                    .testTag("$tag-$count"), contentAlignment = Alignment.Center) {
                    Text("$count", style = MaterialTheme.typography.labelLarge,
                        color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun SwitchRow(label: String, icon: ImageVector, checked: Boolean, tag: String, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().testTag(tag).toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onCheckedChange = null, modifier = Modifier.size(width = 52.dp, height = 32.dp),
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .48f),
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ))
    }
}

@Composable
private fun SettingsAction(title: String, subtitle: String?, icon: ImageVector, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .45f)) },
        supportingContent = subtitle?.let { { Text(it) } }, leadingContent = { Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick))
}

@Composable
internal fun AlbumOptionsSheet(album: Album, onDismiss: () -> Unit, onRename: () -> Unit, onCover: () -> Unit,
    onFolders: () -> Unit, onWidget: () -> Unit, onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.album_settings_title), style = MaterialTheme.typography.titleLarge)
            Text(album.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            SettingsAction(stringResource(R.string.album_rename), null, Icons.Outlined.Edit, onClick = onRename)
            SettingsAction(stringResource(R.string.album_choose_thumbnail), null, Icons.Outlined.Image, onClick = onCover)
            SettingsAction(stringResource(R.string.album_linked_folders), pluralStringResource(R.plurals.folder_count, album.folderKeys.size, album.folderKeys.size),
                Icons.Outlined.FolderOpen, onClick = onFolders)
            SettingsAction(stringResource(R.string.album_add_widget), stringResource(R.string.album_add_widget_subtitle), Icons.Outlined.Widgets, onClick = onWidget)
            SettingsAction(stringResource(R.string.album_delete), stringResource(R.string.album_delete_subtitle), Icons.Outlined.DeleteOutline, destructive = true, onClick = onDelete)
        }
    }
}

@Composable
internal fun WidgetSizeDialog(onDismiss: () -> Unit, onChoose: (AlbumWidgetSize) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.widget_size_title)) }, containerColor = MaterialTheme.colorScheme.surface,
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.widget_size_message))
            AlbumWidgetSize.entries.chunked(2).forEach { sizes -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                sizes.forEach { size -> OutlinedButton(onClick = { onChoose(size) }, modifier = Modifier.weight(1f)) { Text(size.label) } }
            } }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
