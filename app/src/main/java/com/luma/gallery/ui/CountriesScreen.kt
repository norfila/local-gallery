package com.luma.gallery.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.luma.gallery.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import com.luma.gallery.data.CountryGroupKind
import com.luma.gallery.data.CountryPhotoGroup
import com.luma.gallery.data.CountryResolver
import com.luma.gallery.data.Photo
import com.luma.gallery.data.PhotoLayout
import com.luma.gallery.data.groupPhotosByCountry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CountriesScreen(
    photos: List<Photo>,
    hasLocationPermission: Boolean,
    onRequestLocation: () -> Unit,
    onPhotoClick: (Photo) -> Unit,
    modifier: Modifier = Modifier,
    onPhotoGroupClick: ((Photo, List<Photo>) -> Unit)? = null,
    backHandlingEnabled: Boolean = true,
    columns: Int = 3,
    layout: PhotoLayout = PhotoLayout.GRID,
    selectedUris: Set<String> = emptySet(),
    onSelectionChange: ((Set<String>) -> Unit)? = null,
    onColumnsChange: ((Int) -> Unit)? = null,
    bottomContentPadding: Dp = 24.dp,
) {
    val context = LocalContext.current.applicationContext
    val resources = LocalResources.current
    val resolver = remember(context) { CountryResolver(context) }
    val groups by produceState<List<CountryPhotoGroup>?>(null, photos, hasLocationPermission, resources) {
        value = if (!hasLocationPermission) null else withContext(Dispatchers.Default) {
            // Country names follow the phone's language; collation keeps accented names in order.
            val collator = java.text.Collator.getInstance()
            groupPhotosByCountry(photos, resolver::resolve).map { group ->
                group.copy(title = when (group.kind) {
                    CountryGroupKind.COUNTRY -> group.key.takeIf { it.length == 2 }
                        ?.let { java.util.Locale("", it).getDisplayCountry().takeIf { name -> name.isNotBlank() && name != it } } ?: group.title
                    CountryGroupKind.NO_LOCATION -> resources.getString(R.string.countries_no_location)
                    CountryGroupKind.NOT_SCANNED -> resources.getString(R.string.countries_not_scanned)
                    CountryGroupKind.UNMATCHED -> resources.getString(R.string.countries_unmatched)
                })
            }.sortedWith(compareBy<CountryPhotoGroup> { it.kind.ordinal }.thenComparator { a, b -> collator.compare(a.title, b.title) })
        }
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = groups?.firstOrNull { it.key == selectedKey }
    BackHandler(enabled = selected != null && backHandlingEnabled) { selectedKey = null }

    Column(modifier.fillMaxSize().testTag("countries-screen")) {
        if (selected != null && hasLocationPermission) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedKey = null }, modifier = Modifier.testTag("countries-back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.countries_all))
                }
                Text(selected.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val explanation = when (selected.kind) {
                CountryGroupKind.NO_LOCATION -> stringResource(R.string.countries_no_location_message)
                CountryGroupKind.NOT_SCANNED -> stringResource(R.string.countries_not_scanned_message)
                CountryGroupKind.UNMATCHED -> stringResource(R.string.countries_unmatched_message)
                CountryGroupKind.COUNTRY -> null
            }
            if (explanation != null) Text(explanation, Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val openPhoto: (Photo) -> Unit = { photo ->
                onPhotoGroupClick?.invoke(photo, selected.photos) ?: onPhotoClick(photo)
            }
            PhotoGrid(selected.photos, selectedUris, openPhoto, { photo ->
                onSelectionChange?.invoke(if (photo.uri in selectedUris) selectedUris - photo.uri else selectedUris + photo.uri) ?: openPhoto(photo)
            }, Modifier.weight(1f), columns = columns, layout = layout, onSelectionChange = onSelectionChange,
                onColumnsChange = onColumnsChange, bottomContentPadding = bottomContentPadding)
        } else {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp)) {
                Text(stringResource(R.string.nav_countries), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.countries_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                !hasLocationPermission -> EmptyState(
                    Icons.Outlined.Public,
                    stringResource(R.string.map_unlock_action),
                    stringResource(R.string.countries_permission_message),
                    stringResource(R.string.map_unlock_action),
                    onRequestLocation,
                )
                groups == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.testTag("countries-loading"))
                }
                photos.isEmpty() -> EmptyState(Icons.Outlined.Public, stringResource(R.string.countries_empty_title), stringResource(R.string.countries_empty_message))
                else -> {
                    Text(
                        stringResource(R.string.countries_offline_note),
                        Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        modifier = Modifier.weight(1f).testTag("countries-grid"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomContentPadding),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(groups.orEmpty(), key = { it.key }) { group ->
                            CountryTile(group, onClick = { selectedKey = group.key })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryTile(group: CountryPhotoGroup, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).testTag("country-${group.key}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(.9f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(group.photos.first().uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.BottomStart).padding(9.dp).testTag("country-count-${group.key}"),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = RoundedCornerShape(11.dp)) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (group.kind == CountryGroupKind.COUNTRY) Icons.Outlined.Flag else Icons.Outlined.LocationOff, null,
                        Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("${group.photos.size}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        // Inset from the tile's rounded corners so long names wrap instead of being clipped.
        Text(group.title, Modifier.padding(start = 6.dp, end = 6.dp, top = 9.dp, bottom = 10.dp), style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 1)
    }
}

