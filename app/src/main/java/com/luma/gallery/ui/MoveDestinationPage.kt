package com.luma.gallery.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.luma.gallery.R
import com.luma.gallery.data.Photo
import com.luma.gallery.data.PhotoBatchMoveResult
import com.luma.gallery.data.PhotoMoveLocation
import com.luma.gallery.data.PhotoMovePaths
import com.luma.gallery.data.PhotoMoveService
import com.luma.gallery.data.userMessage
import com.luma.gallery.image.rememberThumbnailRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Moves one item (from the viewer) or a whole selection: a page of folders with their covers, like the Folders
 * page. A tap on a folder moves the items there, as a tap on an album adds them; the caller offers to undo it. The
 * page closes as soon as anything has moved and reports through [onMoved] how many items moved and how many
 * stayed, and whether Android's approval window interrupted it; it stays open, with the reason, when nothing did.
 */
@Composable
fun MoveDestinationPage(photos: List<Photo>, availablePhotos: List<Photo>, onDismiss: () -> Unit,
    onMoved: (moved: Int, failed: Int, androidAsked: Boolean) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val service = remember(context) { PhotoMoveService(context) }
    val scope = rememberCoroutineScope()
    val single = photos.singleOrNull()
    val key = single?.uri ?: photos.size
    // One item is looked up, so a file that is gone or was opened from another app is refused before a folder is
    // picked. A selection comes from the library, whose folder keys already hold each item's volume and path.
    var location by remember(key) { mutableStateOf<PhotoMoveLocation?>(null) }
    val currentPath = if (single != null) location?.relativePath
        else remember(photos) { photos.map { it.folderKey.substringAfter(':') }.distinct().singleOrNull() }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var loading by remember(key) { mutableStateOf(single != null) }
    var busy by remember { mutableStateOf(false) }
    var creating by rememberSaveable(key) { mutableStateOf(false) }
    var folderName by rememberSaveable(key) { mutableStateOf("") }
    var pendingDestination by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var requestedSender by remember { mutableStateOf<android.content.IntentSender?>(null) }
    var requestedUris by remember { mutableStateOf(emptySet<String>()) }
    var consented by remember { mutableStateOf(emptySet<String>()) }
    val currentOnMoved by rememberUpdatedState(onMoved)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    fun performMove(destination: String) {
        busy = true
        error = null
        scope.launch(Dispatchers.Main.immediate) {
            when (val result = service.moveAll(photos, destination, consented)) {
                is PhotoBatchMoveResult.NeedsPermission -> {
                    pendingDestination = destination
                    requestedUris = result.uris
                    requestedSender = result.intentSender
                }
                is PhotoBatchMoveResult.Done -> {
                    busy = false
                    pendingDestination = null
                    if (result.moved > 0) { currentOnMoved(result.moved, result.failed, consented.isNotEmpty()); currentOnDismiss() }
                    else error = resources.getString(result.firstFailure ?: R.string.move_not_possible)
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val destination = pendingDestination
        pendingDestination = null
        if (result.resultCode == Activity.RESULT_OK && destination != null) { consented = consented + requestedUris; performMove(destination) }
        else { busy = false; error = resources.getString(R.string.move_cancelled) }
    }
    LaunchedEffect(requestedSender) {
        val sender = requestedSender ?: return@LaunchedEffect
        requestedSender = null
        try { permissionLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
        catch (_: Exception) { pendingDestination = null; busy = false; error = resources.getString(R.string.move_request_failed) }
    }
    LaunchedEffect(key) {
        val photo = single ?: return@LaunchedEffect
        try { location = service.location(photo) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.userMessage(resources, R.string.move_not_possible) }
        finally { loading = false }
    }
    val ready = !loading && (single == null || location != null)
    val destinations = remember(location, availablePhotos, photos) {
        if (single == null) PhotoMovePaths.sharedDestinations(availablePhotos, photos)
        else location?.let { PhotoMovePaths.destinations(availablePhotos, it.volume, it.relativePath, single.isVideo) }.orEmpty()
    }
    val mixed = remember(photos) { photos.any { it.isVideo } && photos.any { !it.isVideo } }
    // Covers and counts come from what the library holds in each folder, on the volumes the items are on.
    val volumes = remember(location, photos) { location?.let { setOf(it.volume) } ?: photos.mapTo(hashSetOf()) { it.folderKey.substringBefore(':', "") } }
    val folderContents = remember(availablePhotos, volumes) {
        availablePhotos.filter { !it.hidden && it.folderKey.substringBefore(':', "") in volumes }.groupBy { it.folderKey.substringAfter(':') }
    }
    val newFolder = PhotoMovePaths.newFolder(folderName, photos)

    DestinationPage(stringResource(R.string.viewer_move), "move-photo-dialog", onDismiss, closeEnabled = !busy) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(single?.name ?: pluralStringResource(R.plurals.item_count, photos.size, photos.size), maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("move-subject"))
            currentPath?.let { Text(stringResource(R.string.move_from, it.trimEnd('/')), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (mixed) Text(stringResource(R.string.move_mixed_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("move-mixed-note"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("move-error")) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ready) LazyVerticalGrid(columns = GridCells.Adaptive(155.dp), modifier = Modifier.weight(1f).testTag("move-folder-grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "new") {
                NewDestinationTile(stringResource(R.string.move_new_folder), 1.15f, 18, enabled = !busy, labelInset = 8.dp, modifier = Modifier.testTag("move-new-folder")) {
                    error = null; creating = true
                }
            }
            items(destinations, key = { it }) { path ->
                FolderDestinationTile(path, folderContents[path].orEmpty(), enabled = !busy) { performMove(path) }
            }
        } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { if (loading) CircularProgressIndicator() }
    }
    // A new folder is named in a small dialog; its Move button moves the items into it.
    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text(stringResource(R.string.move_new_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(folderName, { folderName = it }, label = { Text(stringResource(R.string.move_folder_name)) }, singleLine = true,
                    isError = folderName.isNotBlank() && newFolder == null,
                    supportingText = { Text(stringResource(R.string.move_folder_in, PhotoMovePaths.newFolderRoot(photos))) },
                    modifier = Modifier.fillMaxWidth().testTag("move-folder-name"))
                if (newFolder != null && newFolder == currentPath) Text(stringResource(R.string.move_same_folder), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { newFolder?.let { creating = false; performMove(it) } },
            enabled = newFolder != null && newFolder != currentPath, modifier = Modifier.testTag("confirm-new-folder")) { Text(stringResource(R.string.move_action)) } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.action_cancel)) } },
        modifier = Modifier.testTag("move-new-folder-dialog"),
    )
}

/** A folder to move into: its cover and count when the library has media there, its name, and its path below. */
@Composable
private fun FolderDestinationTile(path: String, contents: List<Photo>, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag("move-folder-$path")) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.15f).clip(RoundedCornerShape(18.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            val cover = contents.firstOrNull()
            if (cover != null) AsyncImage(rememberThumbnailRequest(cover), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Outlined.FolderOpen, null, tint = colors.primary, modifier = Modifier.size(40.dp))
            if (contents.isNotEmpty()) Surface(Modifier.align(Alignment.BottomStart).padding(9.dp), color = colors.surface.copy(alpha = .94f), shape = RoundedCornerShape(11.dp)) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), tint = colors.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("${contents.size}", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
        val name = path.trimEnd('/').substringAfterLast('/')
        Text(name, Modifier.padding(start = 8.dp, end = 8.dp, top = 9.dp),
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Names repeat (Camera, Screenshots), so the path tells two folders apart; a top-level folder is its own path.
        if (path.trimEnd('/') != name) Text(path.trimEnd('/'), Modifier.padding(start = 8.dp, end = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
    }
}
