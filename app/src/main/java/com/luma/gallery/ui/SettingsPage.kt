@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.luma.gallery.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.luma.gallery.BuildConfig
import com.luma.gallery.GalleryViewModel
import com.luma.gallery.R
import com.luma.gallery.data.AllFilesAccess
import com.luma.gallery.data.AppLanguage
import com.luma.gallery.data.MediaManagement
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.platform.LocalUriHandler
import com.luma.gallery.data.StartPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsInfo(val titleRes: Int) { ABOUT(R.string.settings_about), MEDIA(R.string.settings_media_access) }

/** The app's public source code (GPL-3.0-or-later). */
private const val SOURCE_CODE_URL = "https://github.com/norfila/local-gallery"

@Composable
internal fun GallerySettingsPage(
    model: GalleryViewModel,
    fullAccess: Boolean,
    photoAccess: Boolean,
    location: Boolean,
    allFilesAccess: Boolean,
    snackbar: SnackbarHostState,
    onScan: () -> Unit,
    onPhotos: () -> Unit,
    onLocation: () -> Unit,
    onDismiss: () -> Unit,
) {
    var info by rememberSaveable { mutableStateOf<SettingsInfo?>(null) }
    var privacyOpen by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val websiteUrl = stringResource(R.string.website_url)
    val noBrowser = stringResource(R.string.link_open_unavailable)
    // A phone without a browser has nothing to open the link with; say so instead of failing silently.
    fun openLink(url: String) {
        runCatching { uriHandler.openUri(url) }.onFailure { scope.launch { snackbar.showSnackbar(noBrowser) } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val dark = LocalGalleryDarkTheme.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Surface(Modifier.fillMaxSize().testTag("settings-page"), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                        Text(stringResource(R.string.nav_settings), Modifier.padding(start = 3.dp), style = MaterialTheme.typography.titleLarge)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 20.dp)) {
                        SettingsSection(stringResource(R.string.settings_section_general)) {
                            // Read whenever Settings is built: choosing a language rebuilds the screen in it.
                            val language = remember { AppLanguage.current() }
                            CompactSettingsDropdown(stringResource(R.string.settings_language), Icons.Outlined.Translate, language,
                                AppLanguage.entries.map { it to stringResource(it.labelRes) }, "language-dropdown") { chosen ->
                                if (chosen != language) chosen.apply()
                            }
                            SettingsRowDivider()
                            CompactSettingsDropdown(stringResource(R.string.settings_start_page), Icons.Outlined.Home, model.startPage,
                                StartPage.entries.map { it to stringResource(it.labelRes) }, "start-page-dropdown", model::changeStartPage)
                            // Tablets always turn freely; the choice only matters on phones.
                            if (LocalConfiguration.current.smallestScreenWidthDp < 600) {
                                SettingsRowDivider()
                                SwitchRow(stringResource(R.string.settings_rotate_viewer_only), Icons.Outlined.ScreenRotation, model.rotateOnlyInViewer,
                                    "rotate-viewer-only", model::changeRotateOnlyInViewer, subtitle = stringResource(R.string.settings_rotate_viewer_only_subtitle))
                            }
                        }
                        // Photo and folder arrangement live on their pages (the button beside the item count), where
                        // the change can be seen; Settings keeps what applies to the whole app.
                        SettingsSection(stringResource(R.string.settings_appearance)) {
                            AppearanceSection(model.themeMode, model.colorPalette, model.pureBlack, model::changeThemeMode, model::changeColorPalette,
                                model::changePureBlack)
                        }
                        SettingsSection(stringResource(R.string.settings_section_library)) {
                            SettingsPageAction(stringResource(R.string.settings_scan), Icons.Outlined.DocumentScanner,
                                subtitle = if (model.indexing) stringResource(R.string.reading_metadata, model.loaded, model.photos.size)
                                    else stringResource(R.string.settings_scan_subtitle),
                                enabled = !model.indexing, loading = model.indexing, onClick = onScan)
                            SettingsRowDivider()
                            HiddenMediaSettings(model, allFilesAccess)
                            SettingsRowDivider()
                            // Photo locations are part of photo access (one Android prompt), so they share this row.
                            SettingsPageAction(stringResource(R.string.settings_media_access), Icons.Outlined.PhotoLibrary,
                                value = stringResource(if (fullAccess) R.string.access_full else if (photoAccess) R.string.access_limited else R.string.access_none),
                                subtitle = if (photoAccess && !location) stringResource(R.string.media_locations_missing) else null,
                                onClick = { info = SettingsInfo.MEDIA })
                            MediaManagementSettings()
                        }
                        SettingsSection(stringResource(R.string.settings_section_map)) {
                            SwitchRow(stringResource(R.string.settings_online_map), Icons.Outlined.Map, model.onlineMap == true,
                                "online-map", model::changeOnlineMap, subtitle = stringResource(R.string.settings_online_map_subtitle))
                        }
                        SettingsFooterLink(stringResource(R.string.settings_about), Icons.Outlined.Info, BuildConfig.VERSION_NAME) { info = SettingsInfo.ABOUT }
                        // Plain links to the developer's page and the public source; nothing to download or buy there.
                        SettingsFooterLink(stringResource(R.string.settings_website), Icons.Outlined.Language,
                            stringResource(R.string.settings_website_value), tag = "settings-website") { openLink(websiteUrl) }
                        SettingsFooterLink(stringResource(R.string.settings_source_code), Icons.Outlined.Code,
                            stringResource(R.string.settings_source_code_value), tag = "settings-source-code") { openLink(SOURCE_CODE_URL) }
                        SettingsFooterLink(stringResource(R.string.settings_privacy), Icons.Outlined.PrivacyTip, tag = "settings-privacy") { privacyOpen = true }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).padding(16.dp))
            }
        }
        if (privacyOpen) PrivacyPolicyPage { privacyOpen = false }
        info?.let { section ->
            SettingsInfoSheet(section, model.photos.size, fullAccess, photoAccess, location,
                onDismiss = { info = null }, onPhotos = { info = null; onPhotos() }, onLocation = { info = null; onLocation() })
        }
    }
}

/**
 * Android's window before every move and delete can be switched off on Android 12+, but only in Android's own
 * settings; this row shows the state and opens that page. The state is read again on returning to the app.
 */
@Composable
private fun MediaManagementSettings() {
    if (!MediaManagement.supported) return
    val context = LocalContext.current
    var granted by remember { mutableStateOf(MediaManagement.granted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = MediaManagement.granted(context)
        onPauseOrDispose { }
    }
    SettingsRowDivider()
    SettingsPageAction(stringResource(R.string.settings_media_management), Icons.Outlined.DoneAll,
        value = stringResource(if (granted) R.string.access_allowed else R.string.access_not_allowed),
        subtitle = stringResource(if (granted) R.string.settings_media_management_on else R.string.settings_media_management_off),
        tag = "media-management-access",
        onClick = { MediaManagement.settingsIntents(context).firstOrNull { intent -> runCatching { context.startActivity(intent) }.isSuccess } })
}

/** Settings only switches the Hidden page on; its access is set up on the page itself. */
@Composable
private fun HiddenMediaSettings(model: GalleryViewModel, allFilesAccess: Boolean) {
    SwitchRow(stringResource(R.string.hidden_show), Icons.Outlined.VisibilityOff, model.showHiddenMedia, "show-hidden-media",
        model::changeShowHiddenMedia, subtitle = stringResource(R.string.hidden_show_subtitle))
    // Thumbnail caches come only from the storage scan, which needs all files (or, before Android 11, storage) access.
    if (!model.showHiddenMedia || !AllFilesAccess.offered || !AllFilesAccess.needsSystemSettings && !allFilesAccess) return
    SettingsRowDivider()
    if (allFilesAccess) SwitchRow(stringResource(R.string.hidden_thumbnail_caches), Icons.Outlined.PhotoSizeSelectSmall, model.includeThumbnailCaches,
        "hidden-thumbnail-caches", model::changeIncludeThumbnailCaches, subtitle = stringResource(R.string.hidden_thumbnail_caches_subtitle))
    else SettingsPageAction(stringResource(R.string.hidden_thumbnail_caches), Icons.Outlined.PhotoSizeSelectSmall,
        subtitle = stringResource(R.string.hidden_thumbnail_caches_needs_access), onClick = {}, enabled = false)
}

/**
 * Hidden media access, shown on the Hidden page: with all files access Gallery scans every storage
 * volume for .nomedia and dot folders by itself. Folders granted in Android's picker are an alternative.
 */
@Composable
internal fun HiddenMediaAccess(model: GalleryViewModel, allFilesAccess: Boolean, actions: HiddenAccessActions) {
    val context = LocalContext.current
    val systemAccess = AllFilesAccess.offered
    if (systemAccess && AllFilesAccess.needsSystemSettings) {
        SettingsPageAction(stringResource(R.string.hidden_all_files), Icons.Outlined.FolderSpecial,
            value = stringResource(if (allFilesAccess) R.string.access_allowed else R.string.access_not_allowed),
            subtitle = stringResource(if (allFilesAccess) R.string.hidden_all_files_on else R.string.hidden_all_files_off),
            onClick = actions.openAllFilesSettings, tag = "hidden-all-files-access")
        if (!allFilesAccess) {
            Surface(Modifier.fillMaxWidth().padding(start = 44.dp, end = 14.dp, bottom = 10.dp), shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.hidden_all_files_explanation), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = actions.openAllFilesSettings, modifier = Modifier.align(Alignment.End).testTag("allow-all-files")) {
                        Text(stringResource(R.string.hidden_all_files_allow))
                    }
                }
            }
        }
    } else if (systemAccess) {
        SettingsPageAction(stringResource(R.string.hidden_storage_access), Icons.Outlined.FolderSpecial,
            value = stringResource(if (allFilesAccess) R.string.access_allowed else R.string.access_not_allowed),
            subtitle = stringResource(R.string.hidden_storage_access_subtitle), onClick = {}, enabled = false)
    }
    if (systemAccess) SettingsRowDivider()
    Text(stringResource(R.string.hidden_specific_folders), Modifier.padding(start = 44.dp, end = 14.dp, top = 10.dp),
        style = MaterialTheme.typography.labelLarge)
    Text(stringResource(when {
        !systemAccess -> R.string.hidden_folders_only_explanation
        allFilesAccess -> R.string.hidden_specific_folders_optional
        else -> R.string.hidden_specific_folders_explanation
    }),
        Modifier.padding(start = 44.dp, end = 14.dp, top = 2.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().testTag("add-hidden-media-folder").clickable { actions.chooseFolder(null) }
        .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.hidden_add_folder), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val fallbackName = stringResource(R.string.hidden_media_folder)
    model.hiddenMediaFolders.forEach { uriString ->
        key(uriString) {
            val fallback = remember(uriString) { hiddenMediaFolderFallback(uriString, fallbackName) }
            val folder by produceState(HiddenMediaFolderInfo(fallback, null), uriString, actions.revision) {
                value = withContext(Dispatchers.IO) {
                    try {
                        val tree = Uri.parse(uriString)
                        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                        context.contentResolver.query(document,
                            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                            null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) HiddenMediaFolderInfo(
                                cursor.getString(0)?.takeIf { it.isNotBlank() } ?: fallback,
                                cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR,
                            ) else HiddenMediaFolderInfo(fallback, false)
                        } ?: HiddenMediaFolderInfo(fallback, false)
                    } catch (_: Exception) {
                        HiddenMediaFolderInfo(fallback, false)
                    }
                }
            }
            SettingsRowDivider()
            Row(Modifier.fillMaxWidth().testTag("hidden-media-folder-${uriString.hashCode()}")
                .clickable(enabled = folder.available == false, onClickLabel = stringResource(R.string.hidden_folder_allow_again)) {
                    actions.chooseFolder(Uri.parse(uriString))
                }.heightIn(min = 64.dp).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Icon(if (folder.available == false) Icons.Outlined.FolderOff else Icons.Outlined.FolderOpen,
                    null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(folder.name, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(when (folder.available) {
                        true -> R.string.hidden_folder_included
                        false -> R.string.hidden_folder_unavailable
                        null -> R.string.hidden_folder_checking
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { model.removeHiddenMediaFolder(uriString) }) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.hidden_folder_forget, folder.name), Modifier.size(20.dp))
                }
            }
        }
    }
}

private data class HiddenMediaFolderInfo(val name: String, val available: Boolean?)

private fun hiddenMediaFolderFallback(uriString: String, fallback: String): String = runCatching {
    DocumentsContract.getTreeDocumentId(Uri.parse(uriString)).substringAfterLast(':').trimEnd('/').substringAfterLast('/')
        .takeIf { it.isNotBlank() } ?: fallback
}.getOrDefault(fallback)

/** A quiet row below the settings groups, for About and the privacy policy. */
@Composable
private fun SettingsFooterLink(label: String, icon: ImageVector, value: String? = null, tag: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }.then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .heightIn(min = 52.dp).padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge,
        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(Modifier.padding(start = 44.dp, end = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * A choice among a few options as a row: icon, title, and the current value with a small arrow at its end. The
 * whole row is the target, so it opens wherever it is tapped; the list drops down under the value.
 */
@Composable
private fun <T> CompactSettingsDropdown(
    label: String,
    icon: ImageVector,
    value: T,
    options: List<Pair<T, String>>,
    tag: String,
    onChoose: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val currentLabel = options.first { it.first == value }.second
    val stacked = LocalDensity.current.fontScale > 1.3f
    Row(
        Modifier.fillMaxWidth().testTag(tag)
            .clickable(role = Role.DropdownList, onClickLabel = label) { expanded = true }
            .semantics { contentDescription = label; stateDescription = currentLabel }
            .heightIn(min = 56.dp).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
        @Composable fun ValueMenu() {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(currentLabel, style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    Icon(Icons.Filled.ArrowDropDown, null, tint = colors.primary)
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(min = 176.dp, max = 264.dp),
                    shape = RoundedCornerShape(20.dp), containerColor = colors.surface, tonalElevation = 0.dp, shadowElevation = 2.dp,
                    border = BorderStroke(1.dp, colors.outlineVariant)) {
                    options.forEach { (option, name) ->
                        Surface(Modifier.padding(horizontal = 6.dp), shape = RoundedCornerShape(14.dp),
                            color = if (option == value) colors.primaryContainer else Color.Transparent) {
                            // Text on the highlight uses its own colour: Android's colours only promise contrast for that pair.
                            val onChoice = if (option == value) colors.onPrimaryContainer else colors.onSurface
                            DropdownMenuItem(text = { Text(name, style = MaterialTheme.typography.bodyMedium) },
                                onClick = { expanded = false; onChoose(option) },
                                colors = MenuDefaults.itemColors(textColor = onChoice, trailingIconColor = onChoice),
                                trailingIcon = { if (option == value) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) })
                        }
                    }
                }
            }
        }
        if (stacked) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            ValueMenu()
        } else {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            ValueMenu()
        }
    }
}

@Composable
private fun SettingsPageAction(
    title: String,
    icon: ImageVector,
    value: String? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val stacked = LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (stacked && value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!stacked && value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
        else if (enabled) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsInfoSheet(
    info: SettingsInfo,
    itemCount: Int,
    fullAccess: Boolean,
    photoAccess: Boolean,
    location: Boolean,
    onDismiss: () -> Unit,
    onPhotos: () -> Unit,
    onLocation: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val title = stringResource(info.titleRes)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.settings_close_info, title)) }
            }
            if (info == SettingsInfo.ABOUT) {
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, itemCount), style = MaterialTheme.typography.bodyMedium)
                SettingsInfoText(stringResource(R.string.about_open_source_title), stringResource(R.string.about_open_source_body))
                SettingsInfoText(stringResource(R.string.about_ratings_title), stringResource(R.string.about_ratings_body))
                SettingsInfoText(stringResource(R.string.about_gestures_title), stringResource(R.string.about_gestures_body))
                SettingsInfoText(stringResource(R.string.about_viewer_title), stringResource(R.string.about_viewer_body))
                SettingsInfoText(stringResource(R.string.about_maps_title), stringResource(R.string.about_maps_body))
                SettingsInfoText(stringResource(R.string.map_privacy_title), stringResource(R.string.map_privacy_body))
            } else {
                Text(stringResource(if (fullAccess) R.string.media_info_full else if (photoAccess) R.string.media_info_limited else R.string.media_info_none),
                    style = MaterialTheme.typography.bodyMedium)
                if (!fullAccess) Button(onClick = onPhotos, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(if (photoAccess) R.string.media_allow_more else R.string.media_allow))
                }
                if (photoAccess) {
                    SettingsInfoText(stringResource(R.string.media_locations_title),
                        stringResource(if (location) R.string.gps_info_allowed else R.string.gps_info_missing))
                    if (!location) Button(onClick = onLocation, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.gps_allow)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsInfoText(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
