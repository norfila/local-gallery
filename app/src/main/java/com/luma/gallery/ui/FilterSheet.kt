@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.luma.gallery.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.luma.gallery.R
import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.luma.gallery.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared editor targets for the search workspace and complete filter catalog. */
internal sealed interface FilterTarget {
    data class Numeric(val field: MetadataField, val ruleId: Long? = null) : FilterTarget
    data object Camera : FilterTarget
    data object Folder : FilterTarget
    data object Location : FilterTarget
}

private data class RuleDraft(val id: Long, val field: MetadataField, val op: CompareOp, val text: String) {
    fun value(): Double? = if (field == MetadataField.DATE_TAKEN) {
        if (!text.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) null else try {
            LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli().toDouble()
        } catch (_: DateTimeParseException) { null }
    } else text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    fun error(): Int? {
        val parsed = value() ?: return if (field == MetadataField.DATE_TAKEN) R.string.filter_error_date else R.string.filter_error_number
        return when (field) {
            MetadataField.RATING -> if (parsed !in 0.0..5.0 || parsed % 1.0 != 0.0) R.string.filter_error_rating else null
            MetadataField.ISO -> if (parsed < 1 || parsed % 1.0 != 0.0) R.string.filter_error_iso else null
            MetadataField.WIDTH, MetadataField.HEIGHT -> if (parsed <= 0 || parsed % 1.0 != 0.0) R.string.filter_error_pixels else null
            MetadataField.DATE_TAKEN -> null
            else -> if (parsed <= 0) R.string.filter_error_positive else null
        }
    }
    fun rule(): FilterRule? = if (error() == null) value()?.let { FilterRule(id, field, op, it) } else null
}

@Composable
internal fun FilterFavoriteButton(query: PhotoQuery, favorites: List<FavoriteFilter>, onFavorite: (PhotoQuery) -> Unit, modifier: Modifier = Modifier) {
    val starred = favorites.any { it.query.filterKey() == query.filterKey() }
    IconButton(onClick = { onFavorite(query.copy(search = "")) }, enabled = query.hasFilterCriteria(), modifier = modifier) {
        Icon(if (starred) Icons.Outlined.Star else Icons.Outlined.StarOutline, stringResource(R.string.filter_favorite_selected), tint = MaterialTheme.colorScheme.primary)
    }
}

private data class SelectedCriterion(val id: String, val label: String, val target: FilterTarget, val remove: PhotoQuery)

private fun selectedCriteria(query: PhotoQuery, photos: List<Photo>, resources: Resources): List<SelectedCriterion> = buildList {
    query.rules.forEach { rule -> add(SelectedCriterion("rule-${rule.id}", ruleLabel(rule, resources), FilterTarget.Numeric(rule.field, rule.id), query.copy(rules = query.rules.filterNot { it.id == rule.id }))) }
    query.camera?.let { add(SelectedCriterion("camera", resources.getString(R.string.filter_camera_chip, it), FilterTarget.Camera, query.copy(camera = null))) }
    query.hasLocation?.let { add(SelectedCriterion("location", resources.getString(if (it) R.string.quick_with_gps else R.string.filter_without_gps), FilterTarget.Location, query.copy(hasLocation = null))) }
    query.folderKey?.let { key ->
        val label = photos.firstOrNull { it.folderKey == key }?.folder ?: key.substringAfter(':').trimEnd('/').substringAfterLast('/')
        add(SelectedCriterion("folder", resources.getString(R.string.filter_folder_chip, label), FilterTarget.Folder, query.copy(folderKey = null)))
    }
}

@Composable
internal fun SelectedSearchFilters(
    query: PhotoQuery, photos: List<Photo>, onQuery: (PhotoQuery) -> Unit,
    favorites: List<FavoriteFilter>, onFavorite: (PhotoQuery) -> Unit, onEdit: (FilterTarget) -> Unit,
    modifier: Modifier = Modifier, full: Boolean = false,
) {
    val resources = LocalResources.current
    val selected = remember(query, photos, resources) { selectedCriteria(query, photos, resources) }
    Column(modifier.testTag("search-selected-filters"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The count sits with the title as a small pill; the icon actions stay together on the right.
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.filter_selected), style = MaterialTheme.typography.titleSmall)
            if (selected.isNotEmpty()) {
                val countLabel = pluralStringResource(R.plurals.filters_selected, selected.size, selected.size)
                Surface(Modifier.testTag("selected-filters-count").clearAndSetSemantics { contentDescription = countLabel }, shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Text("${selected.size}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.weight(1f))
            if (selected.isNotEmpty()) {
                FilterFavoriteButton(query, favorites, onFavorite, Modifier.size(40.dp).testTag("favorite-selected-filters"))
                IconButton(onClick = { onQuery(PhotoQuery(search = query.search)) }, modifier = Modifier.size(40.dp).testTag("clear-selected-filters")) {
                    Icon(Icons.Outlined.FilterAltOff, stringResource(R.string.filters_clear_all), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (selected.isEmpty()) Text(stringResource(R.string.filter_none_selected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            selected.forEach { criterion -> SelectedCriterionCard(criterion, { onEdit(criterion.target) }, { onQuery(criterion.remove) }, full) }
        }
        if (full && selected.isNotEmpty()) Text(stringResource(R.string.filter_all_must_match), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SelectedCriterionCard(criterion: SelectedCriterion, onEdit: () -> Unit, onRemove: () -> Unit, full: Boolean) {
    // Keep the pill visually aligned with favorite chips. Foundation expands small clickable
    // targets to 48 dp; the trailing action keeps that full width so the actions remain distinct.
    val editLabel = stringResource(R.string.filter_edit, criterion.label)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(onClick = onEdit, modifier = Modifier.widthIn(max = if (full) 420.dp else 300.dp)
            .semantics { contentDescription = editLabel }.testTag("selected-filter-${criterion.id}"),
            shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Row(Modifier.heightIn(min = 40.dp).padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(criterion.label, Modifier.weight(1f, fill = false).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onRemove, modifier = Modifier.size(width = 48.dp, height = 40.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.filter_remove, criterion.label), Modifier.size(16.dp))
                }
            }
        }
    }
}

private data class FilterCategory(val id: String, val labelRes: Int, val icon: ImageVector, val hintRes: Int, val fields: List<FilterTarget>)

@Composable
internal fun MetadataFilterCatalog(query: PhotoQuery, photos: List<Photo>, onQuery: (PhotoQuery) -> Unit, favorites: List<FavoriteFilter>, onFavorite: (PhotoQuery) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<FilterTarget?>(null) }
    val resources = LocalResources.current
    val categories = remember { listOf(
        FilterCategory("rating", R.string.field_rating, Icons.Outlined.StarOutline, R.string.filter_category_rating_hint, listOf(FilterTarget.Numeric(MetadataField.RATING))),
        FilterCategory("date", R.string.filter_date, Icons.Outlined.CalendarMonth, R.string.filter_category_date_hint, listOf(FilterTarget.Numeric(MetadataField.DATE_TAKEN))),
        FilterCategory("camera", R.string.filter_category_camera, Icons.Outlined.CameraAlt, R.string.filter_category_camera_hint, listOf(FilterTarget.Camera, FilterTarget.Numeric(MetadataField.ISO), FilterTarget.Numeric(MetadataField.APERTURE), FilterTarget.Numeric(MetadataField.FOCAL_LENGTH))),
        FilterCategory("location", R.string.section_location, Icons.Outlined.LocationOn, R.string.filter_category_location_hint, listOf(FilterTarget.Location)),
        FilterCategory("size", R.string.filter_category_size, Icons.Outlined.AspectRatio, R.string.filter_category_size_hint, listOf(FilterTarget.Numeric(MetadataField.MEGAPIXELS), FilterTarget.Numeric(MetadataField.WIDTH), FilterTarget.Numeric(MetadataField.HEIGHT))),
        FilterCategory("files", R.string.filter_category_files, Icons.Outlined.FolderOpen, R.string.filter_category_files_hint, listOf(FilterTarget.Folder, FilterTarget.Numeric(MetadataField.FILE_SIZE_MB))),
    ) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SelectedSearchFilters(query, photos, onQuery, favorites, onFavorite, { editor = it }, full = true)
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(stringResource(R.string.filter_add_filters), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            categories.forEach { category ->
                val open = expanded == category.id
                val categoryLabel = stringResource(category.labelRes)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(23.dp)) {
                    Column {
                        Row(Modifier.fillMaxWidth().testTag("filter-category-${category.id}").clickable { expanded = if (open) null else category.id }.heightIn(min = 76.dp).padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(category.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(categoryLabel, style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(category.hintRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, stringResource(if (open) R.string.details_collapse else R.string.details_expand, categoryLabel), Modifier.size(22.dp))
                        }
                        if (open) {
                            HorizontalDivider(Modifier.padding(horizontal = 15.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            category.fields.forEach { target ->
                                val current = when (target) {
                                    is FilterTarget.Numeric -> query.rules.filter { it.field == target.field }.takeIf { it.isNotEmpty() }?.joinToString(" · ") { ruleLabel(it, resources) }
                                    FilterTarget.Camera -> query.camera
                                    FilterTarget.Folder -> query.folderKey?.let { key -> photos.firstOrNull { it.folderKey == key }?.folder ?: key }
                                    FilterTarget.Location -> query.hasLocation?.let { resources.getString(if (it) R.string.quick_with_gps else R.string.filter_without_gps) }
                                }
                                Row(Modifier.fillMaxWidth().testTag("filter-field-${targetId(target)}").clickable { editor = target }.heightIn(min = 60.dp).padding(start = 49.dp, end = 15.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(targetLabel(target, resources), style = MaterialTheme.typography.bodyLarge)
                                        if (current != null) Text(current, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Icon(Icons.Outlined.Add, stringResource(R.string.filter_add, targetLabel(target, resources)), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.filter_unknown_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
    }
    editor?.let { target -> MetadataCriterionDialog(target, query, photos, onQuery, favorites, onFavorite, { editor = null }) }
}

@Composable
internal fun MetadataCriterionDialog(target: FilterTarget, query: PhotoQuery, photos: List<Photo>, onQuery: (PhotoQuery) -> Unit, favorites: List<FavoriteFilter>, onFavorite: (PhotoQuery) -> Unit, onDismiss: () -> Unit) {
    val numeric = target as? FilterTarget.Numeric
    val original = numeric?.ruleId?.let { id -> query.rules.firstOrNull { it.id == id } }
    var replacingId by remember(target) { mutableStateOf(original?.id) }
    var draft by remember(target) { mutableStateOf(original?.toDraft() ?: RuleDraft(nextRuleId(query), numeric?.field ?: MetadataField.RATING, CompareOp.GREATER_OR_EQUAL, defaultValue(numeric?.field ?: MetadataField.RATING))) }
    var selected by remember(target) { mutableStateOf(when (target) { FilterTarget.Camera -> query.camera; FilterTarget.Folder -> query.folderKey; FilterTarget.Location -> query.hasLocation?.toString(); is FilterTarget.Numeric -> null }) }
    var choiceSearch by remember(target) { mutableStateOf("") }
    val single = if (numeric != null) draft.rule()?.let { PhotoQuery(rules = listOf(it)) } else when (target) {
        FilterTarget.Camera -> selected?.let { PhotoQuery(camera = it) }
        FilterTarget.Folder -> selected?.let { PhotoQuery(folderKey = it) }
        FilterTarget.Location -> selected?.let { PhotoQuery(hasLocation = it.toBooleanStrict()) }
        else -> null
    }
    val candidate = if (numeric != null) draft.rule()?.let { rule -> query.copy(rules = if (replacingId == null) query.rules + rule else query.rules.map { if (it.id == replacingId) rule else it }) } else when (target) {
        FilterTarget.Camera -> query.copy(camera = selected)
        FilterTarget.Folder -> query.copy(folderKey = selected)
        FilterTarget.Location -> query.copy(hasLocation = selected?.toBooleanStrict())
        else -> null
    }
    val matching by produceState<Int?>(null, candidate, photos) {
        value = null
        value = withContext(Dispatchers.Default) { candidate?.apply(photos)?.size }
    }
    val editingExisting = when (target) {
        is FilterTarget.Numeric -> replacingId != null
        FilterTarget.Camera -> query.camera != null
        FilterTarget.Folder -> query.folderKey != null
        FilterTarget.Location -> query.hasLocation != null
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .9f).dp
    val resources = LocalResources.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(14.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("filter-editor"), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 22.dp, top = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(targetLabel(target, resources), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { single?.let(onFavorite) }, enabled = single != null) {
                            Icon(if (single != null && favorites.any { it.query.filterKey() == single.filterKey() }) Icons.Outlined.Star else Icons.Outlined.StarOutline, stringResource(R.string.filter_favorite_this), tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.filter_close_editor)) }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (numeric != null) {
                            ComparisonChoice(draft.field, draft.op, { draft = draft.copy(op = it) })
                            if (draft.field == MetadataField.RATING) {
                                // All six choices share one row; very large text falls back to wrapping.
                                val choose: (Int) -> Unit = { stars -> draft = draft.copy(text = stars.toString()) }
                                val chosen = draft.text.toDoubleOrNull()
                                if (LocalDensity.current.fontScale > 1.3f) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    (0..5).forEach { RatingChoiceChip(it, chosen == it.toDouble(), choose, Modifier.widthIn(min = 56.dp)) }
                                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    (0..5).forEach { RatingChoiceChip(it, chosen == it.toDouble(), choose, Modifier.weight(1f)) }
                                }
                            }
                            OutlinedTextField(
                                value = draft.text, onValueChange = { draft = draft.copy(text = it) }, modifier = Modifier.fillMaxWidth().testTag("filter-editor-value"),
                                label = { Text(if (draft.field == MetadataField.DATE_TAKEN) stringResource(R.string.filter_date_value)
                                    else draft.field.unitText(resources).takeIf { it.isNotBlank() }?.let { stringResource(R.string.filter_value_unit, it) } ?: stringResource(R.string.filter_value)) },
                                singleLine = true, isError = draft.error() != null, shape = RoundedCornerShape(24.dp),
                                supportingText = { Text(stringResource(draft.error() ?: when (draft.field) {
                                    MetadataField.DATE_TAKEN -> R.string.filter_hint_date
                                    MetadataField.RATING -> R.string.filter_hint_rating
                                    MetadataField.FILE_SIZE_MB -> R.string.filter_hint_size
                                    else -> R.string.filter_all_must_match
                                })) },
                                keyboardOptions = KeyboardOptions(keyboardType = if (draft.field == MetadataField.DATE_TAKEN) KeyboardType.Text else KeyboardType.Decimal, imeAction = ImeAction.Done),
                            )
                            if (replacingId != null) TextButton(onClick = { replacingId = null; draft = RuleDraft(nextRuleId(query), draft.field, CompareOp.LESS_OR_EQUAL, draft.text) }, modifier = Modifier.testTag("filter-add-another-condition")) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.filter_add_condition))
                            }
                        } else {
                            val choices = remember(target, photos, resources) { when (target) {
                                FilterTarget.Camera -> photos.map { it.camera }.filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).map { it to it }
                                FilterTarget.Folder -> photos.filter { it.folderKey.isNotBlank() }.distinctBy { it.folderKey }.sortedBy { it.folder.lowercase(Locale.ROOT) }.map { it.folderKey to it.folder }
                                FilterTarget.Location -> listOf("true" to resources.getString(R.string.quick_with_gps), "false" to resources.getString(R.string.filter_without_gps))
                                else -> emptyList()
                            } }
                            if (target != FilterTarget.Location) OutlinedTextField(choiceSearch, { choiceSearch = it }, Modifier.fillMaxWidth().testTag("filter-choice-search"), label = { Text(stringResource(if (target == FilterTarget.Camera) R.string.filter_search_cameras else R.string.filter_search_folders)) }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) })
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                CriterionChoiceRow(stringResource(when (target) { FilterTarget.Camera -> R.string.filter_all_cameras; FilterTarget.Folder -> R.string.filter_all_folders; else -> R.string.filter_any_location }), selected == null, { selected = null })
                                val displayed = choices.filter { it.second.contains(choiceSearch, ignoreCase = true) }
                                displayed.forEach { (key, label) -> CriterionChoiceRow(label, selected == key, { selected = key }) }
                                if (displayed.isEmpty()) Text(stringResource(if (choices.isNotEmpty()) R.string.filter_no_matching else if (target == FilterTarget.Camera) R.string.filter_no_cameras else R.string.filter_no_folders), Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp)) {
                        val hasVideos = remember(photos) { photos.any { it.isVideo } }
                        val resultLabel = matching?.let { pluralStringResource(if (hasVideos) R.plurals.item_count else R.plurals.photo_count, it, it) }
                            ?: stringResource(if (candidate != null) R.string.filter_counting else R.string.filter_check_value)
                        val applyButton: @Composable () -> Unit = {
                            Button(onClick = { candidate?.let(onQuery); onDismiss() }, enabled = candidate != null, modifier = Modifier.testTag("filter-editor-apply")) { Text(stringResource(if (editingExisting) R.string.filter_update else R.string.filter_add_action)) }
                        }
                        if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(resultLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                                applyButton()
                            }
                        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(resultLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                            applyButton()
                        }
                    }
                }
            }
        }
    }
}

/** A compact chip: the number with a star icon, narrow enough for 0–5 to share one row. */
@Composable
private fun RatingChoiceChip(stars: Int, selected: Boolean, onChoose: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = pluralStringResource(R.plurals.rating_stars, stars, stars)
    Surface(selected = selected, onClick = { onChoose(stars) }, modifier = modifier.testTag("rating-chip-$stars"), shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.secondaryContainer else colors.surface, contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant)) {
        // Read as "4 stars" rather than "4"; the surface keeps the selection state and click action.
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 4.dp).clearAndSetSemantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text("$stars", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Outlined.Star, null, Modifier.size(14.dp))
        }
    }
}

@Composable
private fun CriterionChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(23.dp)) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).heightIn(min = 52.dp).padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioButton(selected, onClick = null)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ComparisonChoice(field: MetadataField, selected: CompareOp, onSelect: (CompareOp) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(stringResource(R.string.filter_comparison), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("filter-editor-comparison"), shape = RoundedCornerShape(23.dp), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.heightIn(min = 54.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(operatorLabel(selected, field), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.filter_choose_comparison), Modifier.size(20.dp))
                }
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                CompareOp.entries.forEach { op -> DropdownMenuItem(text = { Text(operatorLabel(op, field)) }, onClick = { expanded = false; onSelect(op) }, leadingIcon = { if (op == selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) }
            }
        }
    }
}

/** Compatibility entry point; search embeds the catalog directly. */
@Composable
fun MetadataFilterSheet(query: PhotoQuery, photos: List<Photo>, onApply: (PhotoQuery) -> Unit, onDismiss: () -> Unit, favorites: List<FavoriteFilter> = emptyList(), onToggleFavorite: ((PhotoQuery) -> Unit)? = null, onManageFavorites: () -> Unit = {}) {
    var current by remember(query) { mutableStateOf(query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding()) {
            Text(stringResource(R.string.search_all_filters), Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            MetadataFilterCatalog(current, photos, { current = it }, favorites, { onToggleFavorite?.invoke(it) }, Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { current = PhotoQuery(search = current.search) }) { Text(stringResource(R.string.filters_clear)) }
                Spacer(Modifier.weight(1f)); Button(onClick = { onApply(current) }) { Text(stringResource(R.string.filter_apply)) }
            }
        }
    }
}

private fun targetId(target: FilterTarget): String = when (target) { is FilterTarget.Numeric -> target.field.name; FilterTarget.Camera -> "CAMERA"; FilterTarget.Folder -> "FOLDER"; FilterTarget.Location -> "LOCATION" }
private fun targetLabel(target: FilterTarget, resources: Resources): String = resources.getString(when (target) {
    is FilterTarget.Numeric -> target.field.labelRes; FilterTarget.Camera -> R.string.meta_camera; FilterTarget.Folder -> R.string.meta_folder; FilterTarget.Location -> R.string.section_location
})
private fun nextRuleId(query: PhotoQuery): Long = (query.rules.maxOfOrNull { it.id } ?: 0L) + 1L
private fun ruleLabel(rule: FilterRule, resources: Resources): String = PhotoQuery(rules = listOf(rule)).filterDescription(resources)
private fun FilterRule.toDraft() = RuleDraft(id, field, op, if (field == MetadataField.DATE_TAKEN) Instant.ofEpochMilli(value.toLong()).atZone(ZoneId.systemDefault()).toLocalDate().toString() else if (value % 1.0 == 0.0) value.toLong().toString() else value.toString())
private fun defaultValue(field: MetadataField): String = when (field) {
    MetadataField.RATING -> "4"; MetadataField.ISO -> "100"; MetadataField.APERTURE -> "2.8"; MetadataField.FOCAL_LENGTH -> "35"
    MetadataField.MEGAPIXELS -> "12"; MetadataField.FILE_SIZE_MB -> "5"; MetadataField.WIDTH -> "4000"; MetadataField.HEIGHT -> "3000"
    MetadataField.DATE_TAKEN -> LocalDate.now().toString()
}
@Composable
private fun operatorLabel(op: CompareOp, field: MetadataField): String {
    val symbol = when (op) {
        CompareOp.LESS -> "<"; CompareOp.LESS_OR_EQUAL -> "≤"; CompareOp.EQUAL -> "="
        CompareOp.NOT_EQUAL -> "≠"; CompareOp.GREATER_OR_EQUAL -> "≥"; CompareOp.GREATER -> ">"
    }
    val words = stringResource(if (field == MetadataField.DATE_TAKEN) when (op) {
        CompareOp.LESS -> R.string.op_before; CompareOp.LESS_OR_EQUAL -> R.string.op_on_or_before; CompareOp.EQUAL -> R.string.op_on
        CompareOp.NOT_EQUAL -> R.string.op_not_on; CompareOp.GREATER_OR_EQUAL -> R.string.op_on_or_after; CompareOp.GREATER -> R.string.op_after
    } else when (op) {
        CompareOp.LESS -> R.string.op_less; CompareOp.LESS_OR_EQUAL -> R.string.op_less_or_equal; CompareOp.EQUAL -> R.string.op_equal
        CompareOp.NOT_EQUAL -> R.string.op_not_equal; CompareOp.GREATER_OR_EQUAL -> R.string.op_greater_or_equal; CompareOp.GREATER -> R.string.op_greater
    })
    return "$symbol  $words"
}
