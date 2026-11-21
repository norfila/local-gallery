package com.luma.gallery.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import com.luma.gallery.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal enum class PhotoOrder(val labelRes: Int, val shortRes: Int, val icon: ImageVector) {
    NEWEST(R.string.sort_newest, R.string.sort_newest_short, Icons.Outlined.ArrowDownward),
    OLDEST(R.string.sort_oldest, R.string.sort_oldest_short, Icons.Outlined.ArrowUpward),
    RATING(R.string.sort_rating, R.string.sort_rating_short, Icons.Outlined.StarOutline),
    NAME(R.string.sort_name, R.string.sort_name_short, Icons.Outlined.SortByAlpha),
}

@Composable
internal fun PhotoSortControl(
    currentOrder: PhotoOrder,
    onOrderChange: (PhotoOrder) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "sort-menu-arrow")
    val colors = MaterialTheme.colorScheme
    val sortLabel = stringResource(R.string.sort_description)
    val currentLabel = stringResource(currentOrder.labelRes)
    val triggerModifier = Modifier.testTag("photo-sort-control").semantics {
        contentDescription = sortLabel
        stateDescription = currentLabel
    }

    Box(modifier) {
        if (compact) {
            Row(
                modifier = triggerModifier
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Sort, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Text(
                    text = stringResource(currentOrder.shortRes),
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Surface(
                onClick = { expanded = !expanded },
                modifier = triggerModifier,
                shape = RoundedCornerShape(50),
                color = colors.surfaceVariant,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.outlineVariant),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Row(
                    Modifier.heightIn(min = 40.dp).padding(start = 12.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Sort, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                    Text(currentLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp).rotate(arrowRotation),
                        tint = colors.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(224.dp).testTag("photo-sort-menu"),
            shape = RoundedCornerShape(20.dp),
            containerColor = colors.surface,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            PhotoOrder.entries.forEach { order ->
                val isSelected = currentOrder == order
                Surface(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) colors.primaryContainer else Color.Transparent,
                    contentColor = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(order.labelRes), style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            expanded = false
                            onOrderChange(order)
                        },
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .testTag("photo-sort-${order.name.lowercase(Locale.ROOT)}")
                            .semantics { selected = isSelected },
                        leadingIcon = {
                            Icon(order.icon, null, Modifier.size(20.dp),
                                tint = if (isSelected) colors.onPrimaryContainer else colors.onSurfaceVariant)
                        },
                        trailingIcon = {
                            if (isSelected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp),
                                tint = colors.onPrimaryContainer)
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        colors = MenuDefaults.itemColors(
                            textColor = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
                        ),
                    )
                }
            }
        }
    }
}
