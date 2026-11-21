@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.luma.gallery.ui

import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.luma.gallery.R
import com.luma.gallery.data.Photo
import com.luma.gallery.data.PhotoLayout
import com.luma.gallery.image.rememberThumbnailRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PhotoGridContent(
    val sections: Map<String, List<Photo>> = emptyMap(),
    val uris: List<String> = emptyList(),
    val indices: Map<String, Int> = emptyMap(),
)

/** A range is always applied to the selection at gesture start, so reversing restores it. */
internal class PhotoSelectionDrag(
    private val uris: List<String>,
    private val anchor: Int,
    private val initialSelection: Set<String>,
) {
    private val selecting = uris[anchor] !in initialSelection

    fun selectionAt(index: Int): Set<String> {
        val range = uris.subList(minOf(anchor, index), maxOf(anchor, index) + 1).toSet()
        return if (selecting) initialSelection + range else initialSelection - range
    }
}

@Composable
internal fun PhotoGrid(
    photos: List<Photo>, selected: Set<String>, onClick: (Photo) -> Unit, onLongClick: (Photo) -> Unit,
    modifier: Modifier = Modifier, groupByMonth: Boolean = true,
    columns: Int = 3, layout: PhotoLayout = PhotoLayout.GRID,
    onSelectionChange: ((Set<String>) -> Unit)? = null,
    onColumnsChange: ((Int) -> Unit)? = null,
    bottomContentPadding: Dp = 18.dp,
    badge: ((Photo) -> String?)? = null,
) {
    val monthFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy").withZone(ZoneId.systemDefault()) }
    val unknownDate = stringResource(R.string.grid_date_unavailable)
    val content by produceState(initialValue = PhotoGridContent(), photos, groupByMonth, monthFormat, unknownDate) {
        value = withContext(Dispatchers.Default) {
            val sections = if (groupByMonth) photos.groupBy {
                if (it.dateTaken > 0) monthFormat.format(Instant.ofEpochMilli(it.dateTaken)) else unknownDate
            } else linkedMapOf("" to photos)
            val uris = sections.values.flatMap { entries -> entries.map(Photo::uri) }
            PhotoGridContent(sections, uris, uris.withIndex().associate { it.value to it.index })
        }
    }
    val sections = content.sections
    val orderedUris = content.uris
    val indices = content.indices
    val gridState = rememberLazyGridState()
    val masonryState = rememberLazyStaggeredGridState()
    val currentSelection by rememberUpdatedState(selected)
    val changeSelection by rememberUpdatedState(onSelectionChange)
    val currentColumns by rememberUpdatedState(columns.coerceIn(1, 6))
    val changeColumns by rememberUpdatedState(onColumnsChange)
    var drag by remember { mutableStateOf<PhotoSelectionDrag?>(null) }
    // Selecting is felt as well as seen: a firm pulse when a long press starts it, a light tick for every tile a
    // drag adds or removes, for a tap while selecting and for a month's checkbox. The phone's own touch-feedback
    // setting decides whether anything is felt.
    val haptics = LocalHapticFeedback.current
    var dragIndex by remember { mutableIntStateOf(-1) }
    fun dragTo(index: Int) {
        val range = drag ?: return
        if (index == dragIndex) return
        dragIndex = index
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        changeSelection?.invoke(range.selectionAt(index))
    }
    val selecting = selected.isNotEmpty()
    val click: (Photo) -> Unit = { photo -> if (selecting) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick(photo) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val scrollStep = with(density) { 18.dp.toPx() }

    fun hitIndex(position: Offset): Int? {
        if (layout == PhotoLayout.MASONRY) {
            return masonryState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key in indices && Rect(item.offset.x.toFloat(), item.offset.y.toFloat(),
                    (item.offset.x + item.size.width).toFloat(), (item.offset.y + item.size.height).toFloat()).contains(position)
            }?.let { indices[it.key] }
        }
        return gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key in indices && Rect(item.offset.x.toFloat(), item.offset.y.toFloat(),
                (item.offset.x + item.size.width).toFloat(), (item.offset.y + item.size.height).toFloat()).contains(position)
        }?.let { indices[it.key] }
    }

    LaunchedEffect(drag != null) {
        while (drag != null) {
            withFrameNanos { }
            val amount = when {
                pointer.y < edge -> -scrollStep * (1f - (pointer.y / edge).coerceIn(0f, 1f))
                pointer.y > viewportHeight - edge -> scrollStep * (1f - ((viewportHeight - pointer.y) / edge).coerceIn(0f, 1f))
                else -> 0f
            }
            if (amount != 0f) {
                if (layout == PhotoLayout.MASONRY) masonryState.scrollBy(amount) else gridState.scrollBy(amount)
                hitIndex(pointer)?.let(::dragTo)
            }
        }
    }

    // Observe before scrolling/selection. Once a second finger joins, this gesture belongs
    // to density adjustment until both fingers lift, even when a column limit is reached.
    // Do not key this handler on columns: reflow must not cancel an ongoing pinch.
    val list = layout == PhotoLayout.LIST
    val gridColumns = if (list) 1 else columns.coerceIn(1, 6)
    val densityModifier = if (onColumnsChange == null || list) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinching = false
            var zoom = 1f
            var gestureColumns = currentColumns
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed > 1) {
                    pinching = true
                    val scale = event.calculateZoom()
                    if (scale.isFinite() && scale > 0f) zoom = (zoom * scale).coerceIn(.05f, 20f)
                    while (zoom <= 1f / 1.25f) {
                        val next = (gestureColumns + 1).coerceAtMost(6)
                        if (next != gestureColumns) { gestureColumns = next; changeColumns?.invoke(next) }
                        zoom *= 1.25f
                    }
                    while (zoom >= 1.25f) {
                        val next = (gestureColumns - 1).coerceAtLeast(1)
                        if (next != gestureColumns) { gestureColumns = next; changeColumns?.invoke(next) }
                        zoom /= 1.25f
                    }
                }
                if (pinching) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
    val selectionModifier = if (onSelectionChange == null) Modifier else Modifier.pointerInput(orderedUris, layout, columns) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val anchor = hitIndex(down.position) ?: return@awaitEachGesture
            // Snapshot at DOWN: beginning selection must not turn this same gesture into removal.
            val initialSelection = currentSelection
            var latest = down
            val recognized = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull false
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull false
                    latest = change
                    if (!change.pressed || change.isConsumed) return@withTimeoutOrNull false
                    val movement = change.position - down.position
                    if (movement.getDistance() > viewConfiguration.touchSlop) {
                        // Painting is immediate in selection mode. An ordinary vertical swipe
                        // keeps scrolling; holding first also permits vertical range selection.
                        return@withTimeoutOrNull initialSelection.isNotEmpty() && abs(movement.x) > abs(movement.y)
                    }
                    val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                    if (finalEvent.changes.any { it.id == down.id && it.isConsumed }) {
                        return@withTimeoutOrNull false
                    }
                }
            }
            if (recognized == false) return@awaitEachGesture

            viewportHeight = size.height
            pointer = latest.position
            val range = PhotoSelectionDrag(orderedUris, anchor, initialSelection)
            drag = range
            latest.consume()
            dragIndex = hitIndex(pointer) ?: anchor
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            changeSelection?.invoke(range.selectionAt(dragIndex))
            try {
                while (true) {
                    // Claim motion before the grid's scroll handler and consume UP even for a
                    // stationary long press, so the tile cannot subsequently deliver a click.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                    change.consume()
                    if (!change.pressed) break
                    pointer = change.position
                    hitIndex(pointer)?.let(::dragTo)
                }
            } finally { drag = null }
        }
    }
    val gridDescription = if (list) stringResource(layout.labelRes) else stringResource(R.string.grid_state, stringResource(layout.labelRes),
        pluralStringResource(R.plurals.column_count, gridColumns, gridColumns))
    val gridModifier = modifier.fillMaxWidth().then(densityModifier).then(selectionModifier).testTag("photo-grid")
        .semantics { stateDescription = gridDescription }
    val padding = PaddingValues(start = 12.dp, end = 12.dp, bottom = bottomContentPadding)
    if (layout == PhotoLayout.MASONRY) {
        LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(columns.coerceIn(1, 6)), state = masonryState,
            modifier = gridModifier, contentPadding = padding, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalItemSpacing = 5.dp) {
            sections.forEach { (month, entries) ->
                if (month.isNotEmpty()) item(key = "header-$month", span = StaggeredGridItemSpan.FullLine, contentType = "month") {
                    PhotoMonthHeader(month, entries, selected, onSelectionChange)
                }
                items(entries, key = Photo::uri, contentType = { "photo" }) { photo ->
                    PhotoTile(photo, photo.uri in selected, selected.isNotEmpty(), layout,
                        { click(photo) }, { onLongClick(photo) }, onSelectionChange != null, badge?.invoke(photo))
                }
            }
        }
    } else {
        // The list is a one-column grid, so range selection and auto-scroll work unchanged.
        LazyVerticalGrid(columns = GridCells.Fixed(gridColumns), state = gridState,
            modifier = gridModifier, contentPadding = padding, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(if (list) 2.dp else 5.dp)) {
            sections.forEach { (month, entries) ->
                if (month.isNotEmpty()) item(key = "header-$month", span = { GridItemSpan(maxLineSpan) }, contentType = "month") {
                    PhotoMonthHeader(month, entries, selected, onSelectionChange)
                }
                items(entries, key = Photo::uri, contentType = { if (list) "row" else "photo" }) { photo ->
                    if (list) PhotoListRow(photo, photo.uri in selected, selected.isNotEmpty(),
                        { click(photo) }, { onLongClick(photo) }, onSelectionChange != null, badge?.invoke(photo))
                    else PhotoTile(photo, photo.uri in selected, selected.isNotEmpty(), layout,
                        { click(photo) }, { onLongClick(photo) }, onSelectionChange != null, badge?.invoke(photo))
                }
            }
        }
    }
}

@Composable
private fun PhotoMonthHeader(month: String, entries: List<Photo>, selected: Set<String>, onSelectionChange: ((Set<String>) -> Unit)?) {
    // 12 dp grid padding + 10 dp: the month starts on the same 22 dp line as the header rows above the grid.
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(month, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text("${entries.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (selected.isNotEmpty() && onSelectionChange != null) {
            val uris = entries.map(Photo::uri).toSet()
            val count = uris.count { it in selected }
            val state = when (count) { 0 -> ToggleableState.Off; uris.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
            val selectAll = stringResource(R.string.grid_select_month, month)
            val selectionState = stringResource(R.string.grid_month_selection, count, uris.size, month)
            val haptics = LocalHapticFeedback.current
            TriStateCheckbox(state, onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelectionChange(if (count == uris.size) selected - uris else selected + uris)
            },
                modifier = Modifier.testTag("select-month-$month").semantics {
                    contentDescription = selectAll
                    stateDescription = selectionState
                })
        }
    }
}

@Composable
private fun PhotoTile(photo: Photo, selected: Boolean, selecting: Boolean, layout: PhotoLayout,
    onClick: () -> Unit, onLongClick: () -> Unit, parentHandlesLongPress: Boolean, badge: String? = null) {
    val ratio = if (layout == PhotoLayout.MASONRY && photo.width > 0 && photo.height > 0)
        (photo.width.toFloat() / photo.height).coerceIn(.35f, 3f) else 1f
    val shape = if (LocalRoundedPhotoCorners.current) RoundedCornerShape(12.dp) else RectangleShape
    val selectLabel = stringResource(R.string.grid_select_item, photo.name)
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
        .testTag("photo-tile-${photo.id}").semantics {
            this.selected = selected
            if (parentHandlesLongPress) onLongClick(selectLabel) { onLongClick(); true }
        }
        .combinedClickable(onClick = onClick, onLongClick = if (parentHandlesLongPress) null else onLongClick)) {
        AsyncImage(rememberThumbnailRequest(photo), contentDescription = photo.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .55f)), startY = 150f)))
        if (selected) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .3f)))
        if (badge != null) Text(badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp))
        if (selecting) Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, stringResource(if (selected) R.string.grid_selected else R.string.grid_not_selected),
            tint = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp))
        // A fixed-height row with a tight line height keeps play, rating and GPS on one baseline in every tile.
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(7.dp).height(17.dp), verticalAlignment = Alignment.CenterVertically) {
            if (photo.mimeType.startsWith("video/")) Icon(Icons.Outlined.PlayArrow, stringResource(R.string.grid_video), tint = Color.White, modifier = Modifier.size(17.dp))
            if (photo.rating > 0) {
                Icon(Icons.Outlined.Star, null, tint = Color(0xFFF2E6B8), modifier = Modifier.size(12.dp))
                Text(" ${photo.rating}", color = Color.White, fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("tile-rating"))
            }
            Spacer(Modifier.weight(1f))
            if (photo.hasLocation) Icon(Icons.Outlined.Place, stringResource(R.string.grid_has_gps), tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(13.dp))
        }
    }
}

/** A table-like row: thumbnail, name, date and file details, with rating and GPS at the end. */
@Composable
private fun PhotoListRow(photo: Photo, selected: Boolean, selecting: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, parentHandlesLongPress: Boolean, badge: String? = null) {
    val context = LocalContext.current
    val shape = if (LocalRoundedPhotoCorners.current) RoundedCornerShape(10.dp) else RectangleShape
    val selectLabel = stringResource(R.string.grid_select_item, photo.name)
    val details = remember(photo.uri, photo.dateTaken, photo.size, photo.width, photo.height, photo.durationMillis) {
        listOfNotNull(
            photo.dateTaken.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
            photo.durationMillis.takeIf { photo.isVideo && it > 0 }?.let(::formatPlaybackTime),
            "${photo.width} × ${photo.height}".takeIf { photo.width > 0 && photo.height > 0 },
            photo.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
        ).joinToString(" · ")
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .testTag("photo-tile-${photo.id}").semantics {
            this.selected = selected
            if (parentHandlesLongPress) onLongClick(selectLabel) { onLongClick(); true }
        }
        .combinedClickable(onClick = onClick, onLongClick = if (parentHandlesLongPress) null else onLongClick)
        .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(60.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            AsyncImage(rememberThumbnailRequest(photo), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (photo.isVideo) Icon(Icons.Outlined.PlayArrow, stringResource(R.string.grid_video), tint = Color.White,
                modifier = Modifier.size(22.dp).background(Color.Black.copy(alpha = .45f), RoundedCornerShape(11.dp)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(photo.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (photo.rating > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Text("${photo.rating}", style = MaterialTheme.typography.labelMedium)
        }
        if (photo.hasLocation) Icon(Icons.Outlined.Place, stringResource(R.string.grid_has_gps), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        if (selecting) Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            stringResource(if (selected) R.string.grid_selected else R.string.grid_not_selected),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}
