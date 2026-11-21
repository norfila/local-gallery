package com.luma.gallery.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.luma.gallery.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luma.gallery.data.*

/** Search stays in its own field and is not counted as a metadata filter. */
internal fun PhotoQuery.activeFilterCount(): Int = rules.size +
    (if (hasLocation != null) 1 else 0) +
    (if (camera != null) 1 else 0) +
    (if (folderKey != null) 1 else 0)

private data class ActiveFilterCriterion(val key: String, val label: String, val without: PhotoQuery)

@Composable
internal fun ActiveFilterChips(query: PhotoQuery, onQuery: (PhotoQuery) -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val criteria = remember(query, resources) {
        buildList {
            query.rules.forEachIndexed { index, rule ->
                add(ActiveFilterCriterion("rule-$index", PhotoQuery(rules = listOf(rule)).filterDescription(resources),
                    query.copy(rules = query.rules.filterIndexed { ruleIndex, _ -> ruleIndex != index })))
            }
            query.hasLocation?.let {
                add(ActiveFilterCriterion("location", resources.getString(if (it) R.string.quick_with_gps else R.string.filter_without_gps), query.copy(hasLocation = null)))
            }
            query.camera?.let {
                add(ActiveFilterCriterion("camera", resources.getString(R.string.filter_camera_chip, it), query.copy(camera = null)))
            }
            query.folderKey?.let {
                val name = PhotoQuery(folderKey = it).filterDescription(resources).ifBlank { resources.getString(R.string.filter_storage_root) }
                add(ActiveFilterCriterion("folder", resources.getString(R.string.filter_folder_chip, name), query.copy(folderKey = null)))
            }
        }
    }
    if (criteria.isEmpty()) return

    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(criteria, key = { it.key }) { criterion ->
            val removeLabel = stringResource(R.string.filter_remove, criterion.label)
            InputChip(
                selected = true,
                shape = CircleShape,
                onClick = { onQuery(criterion.without) },
                label = { Text(criterion.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Outlined.Close, null, Modifier.size(15.dp)) },
                modifier = Modifier.widthIn(max = 280.dp).semantics { contentDescription = removeLabel },
            )
        }
    }
}
