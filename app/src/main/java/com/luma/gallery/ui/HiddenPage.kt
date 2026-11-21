@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.luma.gallery.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.luma.gallery.GalleryViewModel
import com.luma.gallery.R
import com.luma.gallery.data.AllFilesAccess
import kotlinx.coroutines.launch

/** Ways into hidden media: Android's folder picker and its all files access setting. */
internal class HiddenAccessActions(
    val chooseFolder: (initial: Uri?) -> Unit,
    val openAllFilesSettings: () -> Unit,
    /** Changes after a folder is added or the app resumes, so folder availability is checked again. */
    val revision: Int,
)

@Composable
internal fun rememberHiddenAccessActions(model: GalleryViewModel, snackbar: SnackbarHostState): HiddenAccessActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                model.addHiddenMediaFolder(uri.toString())
                revision++
            } catch (_: SecurityException) {
                scope.launch { snackbar.showSnackbar(resources.getString(R.string.hidden_folder_not_saved)) }
            }
        }
    }
    return HiddenAccessActions(
        chooseFolder = { initial ->
            try {
                picker.launch(initial)
            } catch (_: ActivityNotFoundException) {
                scope.launch { snackbar.showSnackbar(resources.getString(R.string.hidden_picker_unavailable)) }
            }
        },
        openAllFilesSettings = {
            val opened = AllFilesAccess.settingsIntents(context).any { intent -> runCatching { context.startActivity(intent) }.isSuccess }
            if (!opened) scope.launch { snackbar.showSnackbar(resources.getString(R.string.hidden_all_files_unavailable)) }
        },
        revision = revision,
    )
}

/** First visit without any access: the two ways in, each with one button. */
@Composable
internal fun HiddenSetup(actions: HiddenAccessActions, onStorageAccess: () -> Unit, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 120.dp + bottomInset)
        .testTag("hidden-setup"), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(44.dp), tint = colors.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.hidden_setup_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.hidden_setup_message), style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        val systemSetting = AllFilesAccess.needsSystemSettings
        if (AllFilesAccess.offered) {
            HiddenSetupOption(stringResource(if (systemSetting) R.string.hidden_all_files else R.string.hidden_storage_access),
                stringResource(R.string.hidden_all_files_explanation)) {
                Button(onClick = if (systemSetting) actions.openAllFilesSettings else onStorageAccess, modifier = Modifier.testTag("allow-all-files")) {
                    Text(stringResource(if (systemSetting) R.string.hidden_all_files_allow else R.string.hidden_storage_allow))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        HiddenSetupOption(stringResource(R.string.hidden_specific_folders), stringResource(if (AllFilesAccess.offered)
            R.string.hidden_specific_folders_explanation else R.string.hidden_folders_only_explanation)) {
            OutlinedButton(onClick = { actions.chooseFolder(null) }, modifier = Modifier.testTag("hidden-choose-folder")) {
                Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.hidden_choose_folder))
            }
        }
    }
}

@Composable
private fun HiddenSetupOption(title: String, message: String, action: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterEnd) { action() }
        }
    }
}

/** With access but nothing to show: still searching, or nothing found. */
@Composable
internal fun HiddenEmpty(scanning: Boolean, onManage: () -> Unit) {
    if (scanning) Column(Modifier.fillMaxWidth().padding(30.dp).testTag("hidden-scanning"), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.hidden_scanning), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else EmptyState(Icons.Outlined.VisibilityOff, stringResource(R.string.hidden_empty_title),
        stringResource(R.string.hidden_empty_message), stringResource(R.string.hidden_manage), onManage)
}

/** All files access and the chosen folders, opened from the Hidden page header. */
@Composable
internal fun HiddenAccessSheet(model: GalleryViewModel, allFilesAccess: Boolean, onDismiss: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberHiddenAccessActions(model, snackbar)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, modifier = Modifier.testTag("hidden-access-sheet")) {
        Box {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
                val title = stringResource(R.string.hidden_access_title)
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderSpecial, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(title, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.settings_close_info, title)) }
                }
                Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.fillMaxWidth()) { HiddenMediaAccess(model, allFilesAccess, actions) }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}
