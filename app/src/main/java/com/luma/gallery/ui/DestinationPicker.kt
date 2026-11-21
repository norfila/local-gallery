package com.luma.gallery.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.luma.gallery.R
import com.luma.gallery.data.Album
import com.luma.gallery.data.Photo

/**
 * A full page for choosing where selected items go. It looks like the Albums or Folders page, with covers to
 * recognise a destination by, but it is only a picker: the cross (or Back) returns to where the user was.
 */
@Composable
internal fun DestinationPage(title: String, tag: String, onClose: () -> Unit, closeEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val dark = LocalGalleryDarkTheme.current
    Dialog(onDismissRequest = { if (closeEnabled) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The page paints behind the system bars like the rest of the app, so their icons follow its appearance.
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Surface(Modifier.fillMaxSize().testTag(tag), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, enabled = closeEnabled) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel)) }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                content()
            }
        }
    }
}

/** The first tile of a destination page: make a new album or folder for the items instead of picking one. */
@Composable
internal fun NewDestinationTile(label: String, aspectRatio: Float, cornerRadius: Int, enabled: Boolean = true, labelInset: Dp = 0.dp,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.clickable(enabled = enabled, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspectRatio).border(1.5.dp, colors.outlineVariant, RoundedCornerShape(cornerRadius.dp)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Add, null, Modifier.size(34.dp), tint = colors.primary)
        }
        // [labelInset] starts the label on the same line as the names of the tiles beside it.
        Text(label, Modifier.padding(start = labelInset, end = labelInset, top = 10.dp), style = MaterialTheme.typography.titleMedium,
            color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Adding to an album only links the photos, so a tap on an album does it at once. */
@Composable
internal fun AlbumDestinationPage(albums: List<Album>, photos: List<Photo>, count: Int, onChoose: (Album) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    DestinationPage(pluralStringResource(R.plurals.album_add_count, count, count), "album-destination-page", onDismiss) {
        Text(stringResource(R.string.album_destination_hint), Modifier.padding(start = 22.dp, end = 22.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(columns = GridCells.Adaptive(155.dp), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "new") {
                NewDestinationTile(stringResource(R.string.album_create_new), 1.03f, 22, modifier = Modifier.testTag("album-destination-new")) { creating = true }
            }
            items(albums, key = { it.id }) { album -> AlbumTile(album, photos, Modifier.testTag("album-destination-${album.id}")) { onChoose(album) } }
        }
    }
    if (creating) AlbumNameDialog(stringResource(R.string.album_new_title), stringResource(R.string.album_create_and_add),
        onDismiss = { creating = false }, onSave = onCreate)
}
