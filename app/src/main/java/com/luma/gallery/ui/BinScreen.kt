package com.luma.gallery.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.luma.gallery.R
import com.luma.gallery.data.BinItem
import com.luma.gallery.data.MediaBin
import com.luma.gallery.data.PhotoLayout
import kotlin.math.ceil
import kotlin.math.max

/** Android's system bin (Android 11+). Tap items to select them, then restore or delete forever. */
@Composable
internal fun BinScreen(columns: Int, onColumnsChange: (Int) -> Unit, onChanged: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val bin = remember(context) { MediaBin(context) }
    var revision by remember { mutableIntStateOf(0) }
    val items by produceState<List<BinItem>?>(null, revision) { value = bin.load() }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val currentOnChanged by rememberUpdatedState(onChanged)
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) { selected = emptySet(); revision++; currentOnChanged() }
    }
    fun launch(request: () -> android.content.IntentSender) {
        try { consent.launch(IntentSenderRequest.Builder(request()).build()) }
        catch (_: Exception) { message = resources.getString(R.string.delete_unavailable) }
    }
    val all = items.orEmpty()
    val chosen = all.filter { it.photo.uri in selected }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(selected.isEmpty()) { onBack() }

    Box(modifier.fillMaxSize().testTag("bin-screen")) {
        Column(Modifier.fillMaxSize()) {
            // 6 dp, the button's 12 dp and the 4 dp inside the icon: the arrow (and, within a dp, the cross that takes
            // its place) starts on the 22 dp line of the text below.
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected.isEmpty()) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                    Text(stringResource(R.string.bin_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (all.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        TextButton(onClick = { confirmEmpty = true }, modifier = Modifier.testTag("empty-bin")) { Text(stringResource(R.string.bin_empty_action)) }
                    }
                } else {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, stringResource(R.string.selection_cancel)) }
                    Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        TextButton(onClick = { launch { bin.restoreRequest(chosen) } }, modifier = Modifier.testTag("bin-restore")) {
                            Icon(Icons.Outlined.RestoreFromTrash, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.bin_restore))
                        }
                        TextButton(onClick = { launch { bin.deleteForeverRequest(chosen) } }, modifier = Modifier.testTag("bin-delete-forever"),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Outlined.DeleteForever, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.bin_delete_forever))
                        }
                    }
                }
            }
            Text(stringResource(R.string.bin_explanation), Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                all.isEmpty() -> EmptyState(Icons.Outlined.DeleteOutline, stringResource(R.string.bin_empty_title), stringResource(R.string.bin_empty_message))
                else -> {
                    val now = System.currentTimeMillis()
                    val byUri = all.associateBy { it.photo.uri }
                    PhotoGrid(all.map { it.photo }, selected,
                        onClick = { photo -> selected = selected.toggle(photo.uri) },
                        onLongClick = { photo -> selected = selected.toggle(photo.uri) },
                        modifier = Modifier.weight(1f), groupByMonth = false, columns = columns, layout = PhotoLayout.GRID,
                        onSelectionChange = { selected = it }, onColumnsChange = onColumnsChange, bottomContentPadding = 24.dp,
                        badge = { photo ->
                            val expires = byUri[photo.uri]?.expiresAtMillis ?: 0L
                            if (expires <= 0) null else {
                                val days = max(0, ceil((expires - now) / 86_400_000.0).toInt())
                                resources.getQuantityString(R.plurals.bin_days_left, days, days)
                            }
                        })
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text(stringResource(R.string.bin_empty_confirm_title)) },
        text = { Text(pluralStringResource(R.plurals.bin_empty_confirm_message, all.size, all.size)) },
        confirmButton = {
            TextButton(onClick = {
                confirmEmpty = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) launch { bin.deleteForeverRequest(all) }
            }) {
                Text(stringResource(R.string.bin_empty_action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun Set<String>.toggle(uri: String) = if (uri in this) this - uri else this + uri
