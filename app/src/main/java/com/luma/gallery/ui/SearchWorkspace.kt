@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.luma.gallery.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.luma.gallery.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.luma.gallery.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SearchCount(val photos: Int, val videos: Int)

@Composable
private fun SearchCount.button(): String {
    val photoText = pluralStringResource(R.plurals.photo_count, photos, photos)
    val videoText = pluralStringResource(R.plurals.video_count, videos, videos)
    val label = when {
        videos == 0 -> photoText
        photos == 0 -> videoText
        else -> stringResource(R.string.album_summary, photoText, videoText)
    }
    return stringResource(R.string.search_show_results_count, label)
}

/** filterConflicts() reports categories by their English key; show them in the user's language. */
@Composable
internal fun conflictLabel(key: String): String = stringResource(when (key) {
    "Camera" -> R.string.meta_camera
    "Folder" -> R.string.meta_folder
    else -> R.string.section_location
})

internal val PhotoQuerySaver = listSaver<PhotoQuery, String>(
    save = { query -> listOf(query.search, query.hasLocation?.toString().orEmpty(), query.camera.orEmpty(), query.folderKey.orEmpty()) +
        query.rules.flatMap { listOf(it.id.toString(), it.field.name, it.op.name, it.value.toString()) } },
    restore = { saved -> runCatching {
        require(saved.size >= 4 && (saved.size - 4) % 4 == 0)
        PhotoQuery(search = saved[0], hasLocation = saved[1].toBooleanStrictOrNull(), camera = saved[2].ifEmpty { null }, folderKey = saved[3].ifEmpty { null },
            rules = saved.drop(4).chunked(4).map { FilterRule(it[0].toLong(), MetadataField.valueOf(it[1]), CompareOp.valueOf(it[2]), it[3].toDouble()) })
    }.getOrDefault(PhotoQuery()) },
)

/** A single live query backs both search suggestions and the complete filter catalog. */
@Composable
internal fun GallerySearchWorkspace(
    query: PhotoQuery,
    photos: List<Photo>,
    onQuery: (PhotoQuery) -> Unit,
    favorites: List<FavoriteFilter>,
    onSaveFavorite: (String, PhotoQuery) -> Unit,
    onRenameFavorite: (Long, String) -> Unit,
    onRemoveFavorite: (Long) -> Unit,
    onDismiss: () -> Unit,
    initialAllFilters: Boolean = false,
) {
    var allFilters by rememberSaveable { mutableStateOf(initialAllFilters) }
    var returnToSearch by rememberSaveable { mutableStateOf(!initialAllFilters) }
    var editor by remember { mutableStateOf<FilterTarget?>(null) }
    var favoriteDraft by remember { mutableStateOf<PhotoQuery?>(null) }
    var manageFavorites by remember { mutableStateOf(false) }
    var conflictingFavorite by remember { mutableStateOf<FavoriteFilter?>(null) }
    val count by produceState<SearchCount?>(null, query, photos) {
        value = withContext(Dispatchers.Default) {
            val matching = query.apply(photos)
            val videos = matching.count { it.isVideo }
            SearchCount(matching.size - videos, videos)
        }
    }
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun close() { focus.clearFocus(); keyboard?.hide(); onDismiss() }
    fun showAll() { focus.clearFocus(); keyboard?.hide(); returnToSearch = true; allFilters = true }
    fun back() { if (allFilters && returnToSearch) allFilters = false else close() }
    val applyFavorite: (FavoriteFilter) -> Unit = remember(query, onQuery) { { favorite ->
        if (query.filterConflicts(favorite.query).isNotEmpty()) conflictingFavorite = favorite
        else onQuery(query.addFilterCriteria(favorite.query))
    } }

    Dialog(
        onDismissRequest = { back() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
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
        Surface(Modifier.fillMaxSize().testTag(if (allFilters) "all-filters-page" else "search-workspace"), color = colors.surface) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                    if (allFilters) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { back() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(if (returnToSearch) R.string.search_back_to_search else R.string.search_back_previous))
                            }
                            Text(stringResource(R.string.search_all_filters), style = MaterialTheme.typography.titleLarge)
                        }
                    } else {
                        SearchWorkspaceField(query, onQuery, { close() }, { showAll() }, autofocus = !initialAllFilters)
                    }
                    if (allFilters) {
                        Column(Modifier.weight(1f)) {
                            if (query.search.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.search_text_label, query.search), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                IconButton(onClick = { onQuery(query.copy(search = "")) }) { Icon(Icons.Outlined.Close, stringResource(R.string.search_clear_text), Modifier.size(18.dp)) }
                            }
                            MetadataFilterCatalog(query, photos, onQuery, favorites, { favoriteDraft = it }, Modifier.weight(1f))
                        }
                    } else {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                            SelectedSearchFilters(query, photos, onQuery, favorites, { favoriteDraft = it }, { editor = it })
                            SearchSectionHeading(stringResource(R.string.search_favorites)) {
                                IconButton(onClick = { keyboard?.hide(); manageFavorites = true }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.search_manage_favorites), Modifier.size(20.dp))
                                }
                            }
                            FavoriteSearchChoices(query, favorites, applyFavorite)
                            Spacer(Modifier.height(15.dp))
                            SearchSectionHeading(stringResource(R.string.search_often_used))
                            Row(Modifier.fillMaxWidth().testTag("search-often-used"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CommonFilterCategory(stringResource(R.string.field_rating), Icons.Outlined.StarOutline, Modifier.weight(1f)) { editor = FilterTarget.Numeric(MetadataField.RATING, query.rules.firstOrNull { it.field == MetadataField.RATING }?.id) }
                                CommonFilterCategory(stringResource(R.string.meta_camera), Icons.Outlined.CameraAlt, Modifier.weight(1f)) { editor = FilterTarget.Camera }
                                CommonFilterCategory(stringResource(R.string.filter_date), Icons.Outlined.CalendarMonth, Modifier.weight(1f)) { editor = FilterTarget.Numeric(MetadataField.DATE_TAKEN, query.rules.firstOrNull { it.field == MetadataField.DATE_TAKEN }?.id) }
                            }
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { showAll() }, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("all-filters-link"),
                                border = BorderStroke(1.dp, colors.outlineVariant), colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)) {
                                Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                                Text(stringResource(R.string.search_all_filters), Modifier.weight(1f).padding(start = 10.dp))
                                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SearchResultsFooter(count?.button() ?: stringResource(R.string.search_show_results), onShow = { close() })
                }
            }
        }

        editor?.let { target -> MetadataCriterionDialog(target, query, photos, onQuery, favorites, { favoriteDraft = it }, { editor = null }) }
        favoriteDraft?.let { draft ->
            val existing = favorites.firstOrNull { it.query.filterKey() == draft.filterKey() }
            SearchFavoriteNameDialog(draft, existing, { favoriteDraft = null }, { name ->
                if (existing == null) onSaveFavorite(name, draft.copy(search = "")) else onRenameFavorite(existing.id, name)
                favoriteDraft = null
            })
        }
        if (manageFavorites) SearchFavoriteManager(favorites, { manageFavorites = false; applyFavorite(it) }, { manageFavorites = false; favoriteDraft = it.query }, onRemoveFavorite, { manageFavorites = false })
        conflictingFavorite?.let { favorite ->
            val conflicts = query.filterConflicts(favorite.query).map { conflictLabel(it) }.joinToString(" & ")
            AlertDialog(onDismissRequest = { conflictingFavorite = null }, title = { Text(stringResource(R.string.search_replace_title, conflicts)) }, containerColor = colors.surface,
                text = { Text(stringResource(R.string.search_replace_message, favorite.name, conflicts)) },
                confirmButton = { TextButton(onClick = { onQuery(query.addFilterCriteria(favorite.query, replaceConflicts = true)); conflictingFavorite = null }) { Text(stringResource(R.string.search_replace)) } },
                dismissButton = { TextButton(onClick = { conflictingFavorite = null }) { Text(stringResource(R.string.action_cancel)) } })
        }
    }
}

/** Clearing lives in the selected-filters header, so the footer only shows the results. */
@Composable
private fun SearchResultsFooter(label: String, onShow: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Button(onClick = onShow, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 46.dp).testTag("search-show-results"), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)) {
        Text(label, Modifier.testTag("search-result-count").semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SearchWorkspaceField(query: PhotoQuery, onQuery: (PhotoQuery) -> Unit, onShow: () -> Unit, onAll: () -> Unit, autofocus: Boolean) {
    val colors = MaterialTheme.colorScheme
    val requester = remember { FocusRequester() }
    val searchDescription = stringResource(R.string.search_media)
    Surface(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp), shape = CircleShape,
        color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.heightIn(min = 50.dp).padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShow) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.search_back_to_library), Modifier.size(21.dp), tint = colors.onSurfaceVariant) }
            BasicTextField(query.search, { onQuery(query.copy(search = it)) },
                Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(requester).testTag("search-text")
                    .semantics { contentDescription = searchDescription },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface), singleLine = true,
                cursorBrush = SolidColor(colors.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onShow() }),
                decorationBox = { field -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (query.search.isEmpty()) Text(stringResource(R.string.search_field_placeholder), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                    field()
                } })
            if (query.search.isNotEmpty()) IconButton(onClick = { onQuery(query.copy(search = "")) }, Modifier.size(40.dp)) { Icon(Icons.Outlined.Close, stringResource(R.string.search_clear), Modifier.size(18.dp), tint = colors.onSurfaceVariant) }
            // 50 dp field: a 4 dp end inset leaves the same gap beside the count pill as above and below it.
            SearchFieldFilterButton(query.activeFilterCount(), onAll, stringResource(R.string.search_all_filters),
                Modifier.padding(end = 4.dp).testTag("all-filters-button"))
        }
    }
    LaunchedEffect(Unit) { if (autofocus) requester.requestFocus() }
}

@Composable
private fun SearchSectionHeading(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        action?.invoke()
    }
}

@Composable
private fun FavoriteSearchChoices(query: PhotoQuery, favorites: List<FavoriteFilter>, onApply: (FavoriteFilter) -> Unit) {
    val colors = MaterialTheme.colorScheme
    FlowRow(Modifier.fillMaxWidth().testTag("search-favorites"), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (favorites.isEmpty()) Text(stringResource(R.string.search_favorites_empty), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        favorites.forEach { favorite ->
            val active = query.includesFilter(favorite.query)
            val addLabel = stringResource(R.string.favorite_add, favorite.name)
            Surface(onClick = { onApply(favorite) }, shape = CircleShape,
                color = if (active) colors.primaryContainer else colors.surfaceVariant,
                contentColor = if (active) colors.onPrimaryContainer else colors.onSurface,
                modifier = Modifier.widthIn(max = 300.dp).semantics { contentDescription = addLabel }) {
                Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (active) Icons.Outlined.Check else Icons.Outlined.StarOutline, null, Modifier.size(16.dp))
                    Text(favorite.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (favorite.query.activeFilterCount() > 1) Text("${favorite.query.activeFilterCount()}", style = MaterialTheme.typography.bodySmall,
                        color = if (active) colors.onPrimaryContainer.copy(alpha = .8f) else colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CommonFilterCategory(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(23.dp), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface, modifier = modifier) {
        Column(Modifier.heightIn(min = 70.dp).padding(horizontal = 7.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchFavoriteNameDialog(query: PhotoQuery, existing: FavoriteFilter?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val resources = LocalResources.current
    var name by remember(query.filterKey()) { mutableStateOf(existing?.name ?: query.filterDescription(resources).take(48)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (existing == null) R.string.favorite_save_title else R.string.favorite_edit_title)) }, containerColor = MaterialTheme.colorScheme.surface,
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val n = query.activeFilterCount()
            Text(pluralStringResource(R.plurals.filters_selected, n, n), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(query.filterDescription(resources), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { if (it.length <= 64) name = it }, Modifier.fillMaxWidth().testTag("favorite-name"), label = { Text(stringResource(R.string.favorite_name)) }, singleLine = true, shape = RoundedCornerShape(24.dp))
        } }, confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.favorite_save_title)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun SearchFavoriteManager(favorites: List<FavoriteFilter>, onApply: (FavoriteFilter) -> Unit, onEdit: (FavoriteFilter) -> Unit, onRemove: (Long) -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.search_favorites)) }, containerColor = MaterialTheme.colorScheme.surface,
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (favorites.isEmpty()) Text(stringResource(R.string.search_favorites_dialog_empty))
            favorites.forEach { favorite ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onApply(favorite) }, Modifier.weight(1f)) { Text(favorite.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    IconButton(onClick = { onEdit(favorite) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.favorite_edit, favorite.name), Modifier.size(19.dp)) }
                    IconButton(onClick = { onRemove(favorite.id) }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.favorite_delete, favorite.name), Modifier.size(19.dp)) }
                }
                Text(favorite.query.filterDescription(resources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } })
}
