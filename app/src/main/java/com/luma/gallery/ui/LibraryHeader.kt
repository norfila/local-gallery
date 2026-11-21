package com.luma.gallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.gallery.R
import com.luma.gallery.data.PhotoQuery

@Composable
internal fun CompactSearchAndFilter(
    query: PhotoQuery,
    onQuery: (PhotoQuery) -> Unit,
    onFilter: () -> Unit,
    onMenu: () -> Unit,
    showFilter: Boolean = true,
    placeholder: String = stringResource(R.string.search_placeholder),
    onSearchOpen: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("library-search"),
        shape = CircleShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Menu, stringResource(R.string.nav_open_menu), Modifier.size(21.dp), tint = colors.onSurfaceVariant)
            }
            val searchMedia = stringResource(R.string.search_media)
            val searchAlbums = stringResource(R.string.search_albums_description)
            if (onSearchOpen != null) Box(
                Modifier.weight(1f).heightIn(min = 48.dp).testTag("search-open")
                    .clickable(onClick = onSearchOpen).semantics { contentDescription = searchMedia },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(query.search.ifEmpty { placeholder }, style = MaterialTheme.typography.bodyMedium,
                    color = if (query.search.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else BasicTextField(
                value = query.search,
                onValueChange = { onQuery(query.copy(search = it)) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                    contentDescription = if (showFilter) searchMedia else searchAlbums
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (query.search.isEmpty()) Text(placeholder, fontSize = 14.sp, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        field()
                    }
                },
            )
            if (query.search.isNotEmpty()) IconButton(onClick = { onQuery(query.copy(search = "")) }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, stringResource(R.string.search_clear), Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
            if (showFilter) {
                val count = query.activeFilterCount()
                // 48 dp field: a 2 dp end inset leaves the same gap beside the count pill as above and below it.
                SearchFieldFilterButton(count, onFilter,
                    if (count > 0) pluralStringResource(R.plurals.filters_active, count, count) else stringResource(R.string.filters_metadata),
                    Modifier.padding(end = 2.dp).testTag("library-filter-button"))
            }
        }
    }
}

/**
 * The filter entry inside a search field. With active filters it becomes a pill holding the icon and the count, so
 * nothing overlaps the field's border; the pill is 36 dp tall and centered in the 48 dp touch target.
 */
@Composable
internal fun SearchFieldFilterButton(count: Int, onClick: () -> Unit, description: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    if (count == 0) IconButton(onClick = onClick, modifier = modifier.size(44.dp)) {
        Icon(Icons.Outlined.Tune, description, Modifier.size(21.dp), tint = colors.onSurfaceVariant)
    } else Surface(onClick = onClick, modifier = modifier.semantics { contentDescription = description }, shape = CircleShape,
        color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Row(Modifier.height(36.dp).padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
            Text("$count", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun CompactLibraryContext(
    view: String?,
    count: String,
    order: PhotoOrder,
    onOrderChange: (PhotoOrder) -> Unit,
    onLayout: () -> Unit,
    showSort: Boolean = true,
    onManageHidden: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("library-context-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (LocalDensity.current.fontScale > 1.3f) {
            Column(Modifier.weight(1f)) {
                if (view != null) ContextViewLabel(view)
                ContextCountLabel(count)
            }
        } else Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (view != null) ContextViewLabel(view, Modifier.widthIn(max = 100.dp))
            ContextCountLabel(count, Modifier.weight(1f))
        }
        if (showSort) PhotoSortControl(order, onOrderChange, compact = true)
        IconButton(onClick = onLayout, modifier = Modifier.size(44.dp).testTag("arrangement-button")) {
            Icon(Icons.Outlined.DashboardCustomize, stringResource(if (showSort) R.string.arrangement_title else R.string.folder_arrangement_title),
                Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onManageHidden != null) IconButton(onClick = onManageHidden, modifier = Modifier.size(44.dp).testTag("hidden-manage")) {
            Icon(Icons.Outlined.FolderSpecial, stringResource(R.string.hidden_manage), Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ContextViewLabel(view: String, modifier: Modifier = Modifier) {
    Text(view, modifier.testTag("library-current-view"), style = MaterialTheme.typography.labelLarge,
        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ContextCountLabel(count: String, modifier: Modifier = Modifier) {
    Text(count, modifier, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
