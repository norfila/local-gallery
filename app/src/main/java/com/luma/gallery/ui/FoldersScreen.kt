package com.luma.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.luma.gallery.R
import com.luma.gallery.data.Photo
import com.luma.gallery.data.PhotoLayout
import com.luma.gallery.image.rememberThumbnailRequest

/** Folders as cover tiles or as a table; the storage path is shown inside a folder (info button). */
@Composable
fun FoldersScreen(
    photos: List<Photo>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    layout: PhotoLayout = PhotoLayout.GRID,
    roundedCorners: Boolean = true,
    bottomContentPadding: Dp = 114.dp,
) {
    val folders = remember(photos) { photos.groupBy { it.folderKey }.entries.sortedBy { it.value.first().folder.lowercase() } }
    val list = layout == PhotoLayout.LIST
    val count = if (list) 1 else columns.coerceIn(1, 6)
    val compact = count >= 4
    val gridDescription = if (list) stringResource(R.string.layout_list)
        else stringResource(R.string.grid_state, stringResource(R.string.view_folders), pluralStringResource(R.plurals.column_count, count, count))
    val coverShape = if (roundedCorners) RoundedCornerShape(if (list) 10.dp else 18.dp) else RectangleShape
    LazyVerticalGrid(columns = GridCells.Fixed(count), modifier = modifier.fillMaxSize().testTag("folder-grid")
        .semantics { stateDescription = gridDescription },
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (list) 8.dp else 16.dp, bottom = bottomContentPadding),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (list) 2.dp else 20.dp)) {
        items(folders, key = { it.key }) { (key, contents) ->
            if (list) FolderRow(key, contents, coverShape, onOpen)
            else Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable { onOpen(key) }.testTag("folder-$key")) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.15f).clip(coverShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    AsyncImage(rememberThumbnailRequest(contents.first()), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Surface(Modifier.align(Alignment.BottomStart).padding(if (compact) 4.dp else 9.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = RoundedCornerShape(if (compact) 8.dp else 11.dp)) {
                        Row(Modifier.padding(horizontal = if (compact) 6.dp else 9.dp, vertical = if (compact) 4.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!compact) {
                                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("${contents.size}", style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(contents.first().folder, Modifier.padding(horizontal = if (compact) 4.dp else 8.dp).padding(top = 9.dp, bottom = 12.dp),
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FolderRow(key: String, contents: List<Photo>, coverShape: androidx.compose.ui.graphics.Shape, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(key) }.testTag("folder-$key").padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(56.dp).clip(coverShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(rememberThumbnailRequest(contents.first()), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(contents.first().folder, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.item_count, contents.size, contents.size), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
