package com.luma.gallery.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.luma.gallery.R
import com.luma.gallery.data.MediaBin
import com.luma.gallery.data.Photo
import kotlinx.coroutines.launch

/**
 * One entry point for deleting from the grid or the viewer. Android 11+ moves library items to
 * the system bin after its own confirmation. Items that cannot be binned (older Android, hidden
 * files read outside MediaStore) are deleted permanently after Gallery's confirmation.
 */
@Composable
internal fun rememberDeleteAction(onDeleted: (Set<String>) -> Unit, onMessage: (String) -> Unit): (List<Photo>) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val bin = remember(context) { MediaBin(context) }
    val scope = rememberCoroutineScope()
    val currentOnDeleted by rememberUpdatedState(onDeleted)
    val currentOnMessage by rememberUpdatedState(onMessage)
    var confirming by remember { mutableStateOf<List<Photo>?>(null) }
    var binning by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var permanent by remember { mutableStateOf<List<Photo>>(emptyList()) }
    val notDeleted = { count: Int -> resources.getQuantityString(R.plurals.delete_failed, count, count) }

    fun deletePermanent(binned: Set<String>) {
        val targets = permanent
        permanent = emptyList()
        if (targets.isEmpty()) { if (binned.isNotEmpty()) currentOnDeleted(binned); return }
        scope.launch {
            val deleted = bin.deletePermanently(targets)
            currentOnDeleted(binned + deleted)
            if (deleted.size < targets.size) currentOnMessage(notDeleted(targets.size - deleted.size))
        }
    }

    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) deletePermanent(emptySet())
        else { permanent = emptyList(); currentOnMessage(resources.getString(R.string.delete_permission_denied)) }
    }

    fun continuePermanent(binned: Set<String>) {
        val needsWrite = Build.VERSION.SDK_INT < Build.VERSION_CODES.R && permanent.any { bin.mediaStoreUri(it) != null } &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsWrite) writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else deletePermanent(binned)
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val binned = binning.mapTo(HashSet()) { it.uri }
        binning = emptyList()
        if (result.resultCode == Activity.RESULT_OK) continuePermanent(binned) else permanent = emptyList()
    }

    fun start(binnable: List<Photo>) {
        if (binnable.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { continuePermanent(emptySet()); return }
        binning = binnable
        try {
            consent.launch(IntentSenderRequest.Builder(bin.trashRequest(binnable)).build())
        } catch (_: Exception) {
            binning = emptyList(); permanent = emptyList()
            currentOnMessage(resources.getString(R.string.delete_unavailable))
        }
    }

    confirming?.let { photos ->
        val count = photos.size
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(pluralStringResource(R.plurals.delete_permanently_title, count, count)) },
            text = { Text(stringResource(if (MediaBin.supported) R.string.delete_permanently_hidden_message else R.string.delete_permanently_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    start(binning)
                }, modifier = Modifier.testTag("confirm-delete")) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null; binning = emptyList(); permanent = emptyList() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    return remember(bin) {
        { photos: List<Photo> ->
            if (photos.isNotEmpty()) {
                val binnable = photos.filter(bin::canBin)
                permanent = photos.filterNot(bin::canBin)
                if (permanent.isEmpty()) start(binnable)
                else { binning = binnable; confirming = permanent }
            }
        }
    }
}
