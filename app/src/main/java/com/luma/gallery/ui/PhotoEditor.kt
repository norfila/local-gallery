package com.luma.gallery.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.luma.gallery.R
import com.luma.gallery.image.EditException
import com.luma.gallery.data.UserMessageException
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.luma.gallery.data.Photo
import com.luma.gallery.image.CropRatio
import com.luma.gallery.image.CropRect
import com.luma.gallery.image.centeredCrop
import com.luma.gallery.image.cropHandleAt
import com.luma.gallery.image.dragCrop
import com.luma.gallery.image.EditGeometry
import com.luma.gallery.image.InkStroke
import com.luma.gallery.image.PhotoEdits
import com.luma.gallery.image.drawPhotoEdits
import com.luma.gallery.image.editedCopyName
import com.luma.gallery.image.loadEditingBitmap
import com.luma.gallery.image.saveEditedCopy
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class EditorTool { PEN, CROP }

/** Edits an oriented working image and always exports a separate JPEG. */
@Composable
fun PhotoEditor(photo: Photo, onDismiss: () -> Unit, onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val currentOnSaved by rememberUpdatedState(onSaved)
    var bitmap by remember(photo.uri) { mutableStateOf<Bitmap?>(null) }
    var loadError by remember(photo.uri) { mutableStateOf<String?>(null) }
    var saveError by remember(photo.uri) { mutableStateOf<String?>(null) }
    var busy by remember(photo.uri) { mutableStateOf(false) }
    var edits by remember(photo.uri) { mutableStateOf(PhotoEdits()) }
    var undo by remember(photo.uri) { mutableStateOf<List<PhotoEdits>>(emptyList()) }
    var activeStroke by remember(photo.uri) { mutableStateOf<InkStroke?>(null) }
    var color by remember { mutableIntStateOf(0xFFF44336.toInt()) }
    var penWidth by remember { mutableFloatStateOf(6f) }
    var pendingEdits by remember { mutableStateOf<PhotoEdits?>(null) }
    var tool by remember(photo.uri) { mutableStateOf(EditorTool.PEN) }
    // The crop while a handle is dragged; it becomes an undoable edit when the finger lifts.
    var liveCrop by remember(photo.uri) { mutableStateOf<CropRect?>(null) }
    val density = LocalDensity.current
    val penPixels = with(density) { penWidth.dp.toPx() }
    val touchSlop = with(density) { 24.dp.toPx() }
    val smallestCrop = with(density) { 48.dp.toPx() }
    val controlsHeight = (LocalConfiguration.current.screenHeightDp.dp * .45f).coerceAtMost(310.dp)
    val canvasDescription = stringResource(R.string.editor_canvas)
    val turnedSize = bitmap?.let { EditGeometry(it.width, it.height, edits) }?.let { it.rotatedWidth to it.rotatedHeight }
    val cropState = if (edits.crop.isFull || turnedSize == null) stringResource(R.string.editor_crop_none)
        else stringResource(R.string.editor_crop_size, (edits.crop.width * turnedSize.first).roundToInt(), (edits.crop.height * turnedSize.second).roundToInt())
    val canvasState = stringResource(R.string.editor_canvas_state, edits.strokes.size, edits.quarterTurns * 90, cropState)
    val penWidthLabel = stringResource(R.string.editor_pen_width)

    LaunchedEffect(photo.uri) {
        try {
            if (photo.isVideo) loadError = resources.getString(R.string.edit_error_video)
            else bitmap = loadEditingBitmap(context, Uri.parse(photo.uri))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loadError = resources.getString((failure as? EditException)?.messageRes ?: R.string.edit_error_open) }
    }

    fun change(next: PhotoEdits) {
        if (next == edits) return
        undo = (undo + edits).takeLast(40)
        edits = next
        saveError = null
    }

    fun save(document: Uri?, snapshot: PhotoEdits) {
        val source = bitmap ?: return
        busy = true
        saveError = null
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val uri = saveEditedCopy(context, photo, source, snapshot, document)
                busy = false
                currentOnSaved(uri)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                busy = false
                saveError = resources.getString((failure as? EditException)?.messageRes ?: (failure as? UserMessageException)?.messageRes ?: R.string.edit_error_save)
            }
        }
    }

    val createCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val snapshot = pendingEdits
        pendingEdits = null
        if (uri == null || snapshot == null) busy = false else save(uri, snapshot)
    }

    // Back or ✕ with unsaved edits asks first: a slip into the system back gesture must not lose work.
    var confirmDiscard by remember(photo.uri) { mutableStateOf(false) }
    fun close() {
        if (busy) return
        if (edits != PhotoEdits()) confirmDiscard = true else onDismiss()
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
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
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).testTag("photo-editor")) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::close, enabled = !busy, modifier = Modifier.testTag("editor-close")) { Icon(Icons.Outlined.Close, stringResource(R.string.editor_close)) }
                    Text(stringResource(R.string.editor_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 29) save(null, edits) else {
                            pendingEdits = edits
                            busy = true
                            saveError = null
                            createCopy.launch(editedCopyName(photo))
                        }
                    }, enabled = bitmap != null && !busy && activeStroke == null, modifier = Modifier.testTag("editor-save-copy")) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(if (busy) R.string.editor_saving else R.string.editor_save_copy))
                    }
                }
                // Keep the photo, its crop corners and pen strokes clear of the system's edge back gesture.
                Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal)).padding(12.dp), contentAlignment = Alignment.Center) {
                    val image = bitmap
                    if (image != null && tool == EditorTool.CROP) {
                        val crop = liveCrop ?: edits.crop
                        // The whole turned photo, so the frame can grow back over parts that are cropped away.
                        val whole = edits.copy(crop = CropRect())
                        Canvas(Modifier.fillMaxSize().testTag("editor-canvas")
                            .semantics {
                                contentDescription = canvasDescription
                                stateDescription = canvasState
                            }
                            .pointerInput(image, edits, busy) {
                                if (!busy) awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val geometry = EditGeometry(image.width, image.height, whole)
                                    val viewport = geometry.viewport(size.width.toFloat(), size.height.toFloat())
                                    val handle = cropHandleAt(edits.crop, (down.position.x - viewport.left) / viewport.width(),
                                        (down.position.y - viewport.top) / viewport.height(), touchSlop / viewport.width(), touchSlop / viewport.height())
                                        ?: return@awaitEachGesture
                                    val ratio = edits.ratio.pixelRatio(geometry.rotatedWidth, geometry.rotatedHeight)
                                    val minPixels = smallestCrop * geometry.rotatedWidth / viewport.width()
                                    down.consume()
                                    try {
                                        do {
                                            val event = awaitPointerEvent()
                                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                            pointer.consume()
                                            if (pointer.pressed) liveCrop = dragCrop(edits.crop, handle,
                                                (pointer.position.x - down.position.x) / viewport.width(),
                                                (pointer.position.y - down.position.y) / viewport.height(),
                                                ratio, geometry.rotatedWidth, geometry.rotatedHeight, minPixels)
                                        } while (event.changes.any { it.id == down.id && it.pressed })
                                        liveCrop?.let { change(edits.copy(crop = it)) }
                                    } finally { liveCrop = null }
                                }
                            }) {
                            val viewport = EditGeometry(image.width, image.height, whole).viewport(size.width, size.height)
                            drawPhotoEdits(drawContext.canvas.nativeCanvas, image, whole, viewport)
                            drawCropFrame(viewport.left, viewport.top, viewport.width(), viewport.height(), crop, density.density)
                        }
                    } else if (image != null) {
                        val shownEdits = activeStroke?.let { edits.copy(strokes = edits.strokes + it) } ?: edits
                        Canvas(Modifier.fillMaxSize().testTag("editor-canvas")
                            .semantics {
                                contentDescription = canvasDescription
                                stateDescription = canvasState
                            }
                            .pointerInput(image, edits.quarterTurns, edits.crop, color, penPixels, busy) {
                                if (!busy) awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val geometry = EditGeometry(image.width, image.height, edits)
                                    val viewport = geometry.viewport(size.width.toFloat(), size.height.toFloat())
                                    if (!viewport.contains(down.position.x, down.position.y)) return@awaitEachGesture
                                    val width = penPixels * geometry.crop.width() / viewport.width() / min(image.width, image.height)
                                    var stroke = InkStroke(listOf(geometry.sourcePoint(down.position.x, down.position.y, viewport)), color, width)
                                    activeStroke = stroke
                                    down.consume()
                                    try {
                                        do {
                                            val event = awaitPointerEvent()
                                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                            pointer.consume()
                                            if (pointer.pressed) {
                                                val point = geometry.sourcePoint(pointer.position.x, pointer.position.y, viewport)
                                                if (point != stroke.points.last()) {
                                                    stroke = stroke.copy(points = stroke.points + point)
                                                    activeStroke = stroke
                                                }
                                            }
                                        } while (event.changes.any { it.id == down.id && it.pressed })
                                        change(edits.copy(strokes = edits.strokes + stroke))
                                    } finally { activeStroke = null }
                                }
                            }) {
                            val target = EditGeometry(image.width, image.height, shownEdits).viewport(size.width, size.height)
                            drawPhotoEdits(drawContext.canvas.nativeCanvas, image, shownEdits, target)
                        }
                    } else if (loadError != null) {
                        Text(loadError.orEmpty(), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                    } else CircularProgressIndicator()
                }
                if (saveError != null) Text(saveError.orEmpty(), Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

                Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = tool == EditorTool.PEN, onClick = { tool = EditorTool.PEN },
                            label = { Text(stringResource(R.string.editor_pen)) }, enabled = !busy, modifier = Modifier.testTag("editor-tool-pen"))
                        FilterChip(selected = tool == EditorTool.CROP, onClick = { tool = EditorTool.CROP },
                            label = { Text(stringResource(R.string.editor_crop)) }, enabled = bitmap != null && !busy, modifier = Modifier.testTag("editor-tool-crop"))
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            edits = undo.last(); undo = undo.dropLast(1); saveError = null
                        }, enabled = undo.isNotEmpty() && !busy, modifier = Modifier.testTag("editor-undo")) { Icon(Icons.Outlined.Undo, stringResource(R.string.editor_undo)) }
                        if (tool == EditorTool.PEN) IconButton(onClick = { change(edits.copy(strokes = emptyList())) }, enabled = edits.strokes.isNotEmpty() && !busy,
                            modifier = Modifier.testTag("editor-clear-ink")) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.editor_clear)) }
                        IconButton(onClick = { change(edits.turned()) }, enabled = bitmap != null && !busy,
                            modifier = Modifier.testTag("editor-rotate")) { Icon(Icons.Outlined.RotateRight, stringResource(R.string.editor_rotate)) }
                    }
                    if (tool == EditorTool.PEN) PenOptions(color, { color = it }, penWidth, { penWidth = it }, penWidthLabel, !busy)
                    else {
                        Text(stringResource(R.string.editor_crop_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CropRatio.entries.forEach { ratio ->
                                FilterChip(selected = edits.ratio == ratio, onClick = {
                                    val size = turnedSize ?: return@FilterChip
                                    // Locking a shape starts from the largest centred crop; Free keeps the current one.
                                    val crop = if (ratio == CropRatio.FREE) edits.crop else centeredCrop(ratio.pixelRatio(size.first, size.second), size.first, size.second)
                                    change(edits.copy(ratio = ratio, crop = crop))
                                }, label = { Text(stringResource(ratio.labelRes)) }, enabled = bitmap != null && !busy, modifier = Modifier.testTag("editor-ratio-${ratio.name}"))
                            }
                        }
                        TextButton(onClick = { change(edits.copy(crop = CropRect(), ratio = CropRatio.FREE)) },
                            enabled = (!edits.crop.isFull || edits.ratio != CropRatio.FREE) && !busy, modifier = Modifier.testTag("editor-crop-reset")) {
                            Text(stringResource(R.string.editor_crop_reset))
                        }
                    }
                    Text(stringResource(if (Build.VERSION.SDK_INT >= 29) R.string.editor_output_hint else R.string.editor_output_hint_legacy),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, modifier = Modifier.testTag("editor-discard-dialog"),
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_message)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }, modifier = Modifier.testTag("editor-discard")) { Text(stringResource(R.string.editor_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.testTag("editor-keep")) { Text(stringResource(R.string.editor_keep)) } })
    }
}

@Composable
private fun PenOptions(color: Int, onColor: (Int) -> Unit, penWidth: Float, onWidth: (Float) -> Unit, widthLabel: String, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(R.string.pen_white to 0xFFFFFFFF.toInt(), R.string.pen_black to 0xFF151515.toInt(), R.string.pen_red to 0xFFF44336.toInt(),
            R.string.pen_orange to 0xFFFFB300.toInt(), R.string.pen_green to 0xFF43A047.toInt(), R.string.pen_blue to 0xFF1E88E5.toInt(), R.string.pen_pink to 0xFFE040FB.toInt()).forEach { (nameRes, value) ->
            val penName = stringResource(nameRes)
            val penState = stringResource(if (color == value) R.string.grid_selected else R.string.grid_not_selected)
            IconButton(onClick = { onColor(value) }, enabled = enabled, modifier = Modifier.semantics {
                contentDescription = penName; stateDescription = penState
            }) {
                Box(Modifier.size(27.dp).background(Color(value), CircleShape)
                    .border(if (color == value) 3.dp else 1.dp, if (color == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.editor_width), style = MaterialTheme.typography.labelMedium)
        Slider(value = penWidth, onValueChange = onWidth, valueRange = 2f..24f,
            enabled = enabled, modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = widthLabel })
        Text("${penWidth.toInt()}", style = MaterialTheme.typography.labelMedium)
    }
}

/** Darkens everything outside the crop and draws its frame, a rule-of-thirds grid and the corner handles. */
private fun DrawScope.drawCropFrame(left: Float, top: Float, width: Float, height: Float, crop: CropRect, density: Float) {
    val l = left + crop.left * width
    val t = top + crop.top * height
    val r = left + crop.right * width
    val b = top + crop.bottom * height
    val shade = Color.Black.copy(alpha = .55f)
    drawRect(shade, Offset(left, top), Size(width, t - top))
    drawRect(shade, Offset(left, b), Size(width, top + height - b))
    drawRect(shade, Offset(left, t), Size(l - left, b - t))
    drawRect(shade, Offset(r, t), Size(left + width - r, b - t))
    val grid = Color.White.copy(alpha = .45f)
    for (i in 1..2) {
        val x = l + (r - l) * i / 3f
        val y = t + (b - t) * i / 3f
        drawLine(grid, Offset(x, t), Offset(x, b), strokeWidth = density)
        drawLine(grid, Offset(l, y), Offset(r, y), strokeWidth = density)
    }
    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 1.5f * density))
    val arm = min(22f * density, min(r - l, b - t) / 3f)
    val thick = 4f * density
    listOf(Offset(l, t) to Offset(1f, 1f), Offset(r, t) to Offset(-1f, 1f), Offset(l, b) to Offset(1f, -1f), Offset(r, b) to Offset(-1f, -1f)).forEach { (corner, inward) ->
        drawLine(Color.White, corner, corner + Offset(arm * inward.x, 0f), strokeWidth = thick)
        drawLine(Color.White, corner, corner + Offset(0f, arm * inward.y), strokeWidth = thick)
    }
}
