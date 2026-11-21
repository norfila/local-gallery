package com.luma.gallery.ui

import android.content.IntentSender
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.luma.gallery.data.Photo
import com.luma.gallery.data.PhotoBatchMoveResult
import com.luma.gallery.data.PhotoMovePaths
import com.luma.gallery.data.PhotoMoveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a finished move leaves on screen: the message, and where each item came from so it can be put back. */
internal class MoveNote(val message: String, val origins: Map<String, List<Photo>>)

/** Groups items by the folder they are in now, as far as that is a folder a move can return them to. */
internal fun moveOrigins(photos: List<Photo>): Map<String, List<Photo>> = photos
    .mapNotNull { photo -> PhotoMovePaths.normalize(photo.folderKey.substringAfter(':'), photo.isVideo)?.let { it to photo } }
    .groupBy({ it.first }, { it.second })

/**
 * A note with one action at the bottom of the screen, for a few seconds. It is its own small window, so it also
 * shows above an open photo (itself a window above the library, where a snackbar would be hidden). Touches beside
 * it and the Back button go to whatever is underneath.
 */
@Composable
internal fun ActionNote(message: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit, seconds: Int = 7) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) { delay(seconds * 1000L); currentOnDismiss() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                setGravity(Gravity.BOTTOM)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
        }
        Surface(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 100.dp).widthIn(max = 520.dp).testTag("action-note"),
            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
            Row(Modifier.padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f, fill = false).padding(vertical = 14.dp), color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onAction, modifier = Modifier.testTag("action-note-action")) {
                    Text(action, color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        }
    }
}

/**
 * Puts moved items back into the folders they came from. Android's approval for the move normally still holds;
 * if it does not, it is asked for once more, and items it refuses stay where they are and are counted in `failed`.
 */
@Composable
internal fun rememberMoveUndo(onDone: (restored: Int, failed: Int) -> Unit): (Map<String, List<Photo>>) -> Unit {
    val context = LocalContext.current
    val service = remember(context) { PhotoMoveService(context) }
    val scope = rememberCoroutineScope()
    val currentOnDone by rememberUpdatedState(onDone)
    var origins by remember { mutableStateOf<Map<String, List<Photo>>>(emptyMap()) }
    var consented by remember { mutableStateOf(emptySet<String>()) }
    var asked by remember { mutableStateOf(emptySet<String>()) }
    var sender by remember { mutableStateOf<IntentSender?>(null) }

    // Each pass starts over: items already back in place count as moved again without being touched.
    fun run() {
        scope.launch(Dispatchers.Main.immediate) {
            var restored = 0
            var failed = 0
            for ((path, items) in origins) {
                when (val result = service.moveAll(items, path, consented)) {
                    is PhotoBatchMoveResult.NeedsPermission -> { asked = result.uris; sender = result.intentSender; return@launch }
                    is PhotoBatchMoveResult.Done -> { restored += result.moved; failed += result.failed }
                }
            }
            origins = emptyMap()
            consented = emptySet()
            currentOnDone(restored, failed)
        }
    }
    // Whatever the answer, the items asked about are not asked about again: a refusal ends as a failure.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        consented = consented + asked
        run()
    }
    LaunchedEffect(sender) {
        val request = sender ?: return@LaunchedEffect
        sender = null
        try { permission.launch(IntentSenderRequest.Builder(request).build()) }
        catch (_: Exception) { consented = consented + asked; run() }
    }
    return remember { { moved: Map<String, List<Photo>> -> origins = moved; consented = emptySet(); run() } }
}
