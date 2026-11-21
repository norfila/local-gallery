package com.luma.gallery.ui

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.luma.gallery.R
import com.luma.gallery.data.MetadataDetail
import com.luma.gallery.data.MetadataSection
import com.luma.gallery.data.Photo
import com.luma.gallery.data.PhotoMetadataDetails
import com.luma.gallery.data.PhotoMetadataDetailsReader
import com.luma.gallery.image.rememberThumbnailRequest
import com.luma.gallery.image.thumbnailMemoryCacheKey
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import android.app.WallpaperManager
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ViewerWhite = Color(0xFFF5F3ED)
private val ViewerMuted = Color(0xFFB9BFC5)
private val ViewerAccent = Color(0xFFC8DAB1)
private val ViewerSheet = Color(0xFF151B23)

/**
 * Full-screen viewer modelled on Google Photos and Aves:
 * - a tap shows or hides all controls (and the system bars); the choice stays while swiping,
 * - a slim top bar keeps secondary actions in an overflow menu, the bottom bar keeps the frequent ones,
 * - swiping up reveals the details list below the photo; swiping down closes the viewer.
 */
@Composable
fun PhotoViewer(
    photos: List<Photo>,
    initialUri: String,
    onAddToAlbum: (Photo) -> Unit,
    onDismiss: () -> Unit,
    onEdit: ((Photo) -> Unit)? = null,
    onMove: ((Photo) -> Unit)? = null,
    onDelete: ((Photo) -> Unit)? = null,
) {
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.uri == initialUri }.coerceAtLeast(0)) { photos.size }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var detailsProgress by remember { mutableFloatStateOf(0f) }
    var dismissOffset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    // Set when the phone has no wallpaper crop screen, so the viewer asks where to set it itself.
    var wallpaperChoice by remember { mutableStateOf<Photo?>(null) }
    val photo = photos.getOrNull(pager.currentPage) ?: photos.first()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val video = rememberViewerVideo(context)

    LaunchedEffect(initialUri) {
        val index = photos.indexOfFirst { it.uri == initialUri }
        if (index >= 0 && index != pager.currentPage) pager.scrollToPage(index)
    }
    LaunchedEffect(photos.size) {
        if (pager.currentPage > photos.lastIndex) pager.scrollToPage(photos.lastIndex)
    }
    LaunchedEffect(photo.uri) {
        overflowOpen = false
        video.load(photo.takeIf { it.isVideo })
    }
    // Like Google Photos, controls step aside shortly after a video starts playing.
    LaunchedEffect(chromeVisible, video.playing, detailsProgress == 0f, overflowOpen) {
        if (chromeVisible && video.playing && detailsProgress == 0f && !overflowOpen) {
            delay(3_000)
            chromeVisible = false
        }
    }

    Dialog(
        onDismissRequest = {
            if (detailsProgress > 0f) {
                settleJob?.cancel()
                settleJob = scope.launch { animate(detailsProgress, 0f) { value, _ -> detailsProgress = value } }
            } else currentOnDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window, chromeVisible) {
            val target = window ?: return@LaunchedEffect
            WindowCompat.getInsetsController(target, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (chromeVisible) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().testTag("photo-viewer")) {
            val density = LocalDensity.current
            val sheetHeight = maxHeight * .64f
            val sheetHeightPx = with(density) { sheetHeight.toPx() }
            val dismissThresholdPx = with(density) { 110.dp.toPx() }

            fun animateDetails(target: Float, velocity: Float = 0f) {
                settleJob?.cancel()
                settleJob = scope.launch { animate(detailsProgress, target, velocity) { value, _ -> detailsProgress = value } }
            }
            fun dragDetails(dy: Float): Float {
                val next = (detailsProgress - dy / sheetHeightPx).coerceIn(0f, 1f)
                val consumed = -(next - detailsProgress) * sheetHeightPx
                detailsProgress = next
                return consumed
            }
            var detailsDragStart by remember { mutableFloatStateOf(0f) }
            fun settleDetails(velocityY: Float) {
                val progressVelocity = -velocityY / sheetHeightPx
                animateDetails(detailsSettleTarget(detailsProgress, progressVelocity, opening = detailsDragStart < .5f), progressVelocity)
            }
            val onTap: () -> Unit = { if (detailsProgress > 0f) animateDetails(0f) else chromeVisible = !chromeVisible }
            val chromeAlpha = (1f - dismissOffset / dismissThresholdPx).coerceIn(0f, 1f)

            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - dismissOffset / (dismissThresholdPx * 4)).coerceIn(.25f, 1f))))
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        translationY = dismissOffset - detailsProgress * sheetHeightPx / 2f
                        val shrink = 1f - (dismissOffset / (dismissThresholdPx * 8)).coerceIn(0f, .15f)
                        scaleX = shrink
                        scaleY = shrink
                    }
                    .pointerInput(sheetHeightPx) {
                        // Watch before the pager and the photo (Initial pass) and claim only a clearly
                        // vertical single-finger drag; horizontal swipes and pinches stay with them.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (zoomed) return@awaitEachGesture
                            val slop = viewConfiguration.touchSlop
                            val tracker = VelocityTracker()
                            tracker.addPointerInputChange(down)
                            var total = Offset.Zero
                            var claimed = false
                            var details = false
                            fun apply(dy: Float) {
                                if (details) dragDetails(dy) else dismissOffset = (dismissOffset + dy).coerceAtLeast(0f)
                            }
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed || event.changes.count { it.pressed } > 1) break
                                val delta = change.position - change.previousPosition
                                tracker.addPointerInputChange(change)
                                if (!claimed) {
                                    if (zoomed || change.isConsumed) return@awaitEachGesture
                                    total += delta
                                    if (abs(total.x) > slop && abs(total.x) >= abs(total.y)) return@awaitEachGesture
                                    if (abs(total.y) > slop && abs(total.y) > abs(total.x) * 1.2f) {
                                        claimed = true
                                        settleJob?.cancel()
                                        // Upward motion (or an open sheet) drives details; downward motion closes the viewer.
                                        details = detailsProgress > 0f || total.y < 0f
                                        detailsDragStart = detailsProgress
                                        apply(total.y)
                                        change.consume()
                                    }
                                } else {
                                    apply(delta.y)
                                    change.consume()
                                }
                            }
                            if (!claimed) return@awaitEachGesture
                            val velocity = tracker.calculateVelocity().y
                            when {
                                details -> settleDetails(velocity)
                                shouldDismissViewer(dismissOffset, velocity, dismissThresholdPx) -> currentOnDismiss()
                                else -> scope.launch { animate(dismissOffset, 0f) { value, _ -> dismissOffset = value } }
                            }
                        }
                    },
            ) {
                HorizontalPager(
                    state = pager,
                    key = { photos[it].uri },
                    userScrollEnabled = !zoomed,
                    modifier = Modifier.fillMaxSize(),
                ) { index ->
                    val page = photos[index]
                    val active = index == pager.currentPage
                    if (page.isVideo) {
                        VideoPage(page, active, video, showControls = chromeVisible && detailsProgress == 0f, onTap = onTap)
                        LaunchedEffect(active) { if (active) zoomed = false }
                    } else {
                        ZoomablePhoto(page, active, onZoomChanged = { zoomed = it }, onTap = onTap)
                    }
                }
            }

            // Top bar: back, date and position, share and the overflow menu.
            AnimatedVisibility(
                visible = chromeVisible || detailsProgress > 0f,
                enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 },
                modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = chromeAlpha },
            ) {
                ViewerTopBar(
                    photo = photo, position = pager.currentPage + 1, count = photos.size,
                    overflowOpen = overflowOpen, onOverflow = { overflowOpen = it },
                    onClose = { currentOnDismiss() },
                    onDetails = { overflowOpen = false; animateDetails(1f) },
                    onAddToAlbum = { overflowOpen = false; onAddToAlbum(photo) },
                    onMove = onMove?.takeIf { Uri.parse(photo.libraryUri ?: photo.uri).authority == MediaStore.AUTHORITY }
                        ?.let { move -> { overflowOpen = false; move(photo) } },
                    onOpenWith = { overflowOpen = false; openWith(context, photo) },
                    onSetWallpaper = if (photo.isVideo) null else ({
                        overflowOpen = false
                        if (!openWallpaperCrop(context, photo)) wallpaperChoice = photo
                    }),
                    onUseAs = if (photo.isVideo) null else ({ overflowOpen = false; useAs(context, photo) }),
                )
            }

            // Bottom bar: playback controls for videos, then share, edit, rate and delete.
            AnimatedVisibility(
                visible = chromeVisible && detailsProgress < .02f,
                enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = chromeAlpha },
            ) {
                ViewerBottomBar(
                    video = video.takeIf { photo.isVideo },
                    onShare = { sharePhotos(context, listOf(photo)) },
                    onEdit = onEdit?.takeIf { !photo.isVideo }?.let { edit -> { edit(photo) } },
                    onDelete = onDelete?.let { delete -> { delete(photo) } },
                    onDetails = { animateDetails(1f) },
                )
            }

            if (detailsProgress > 0f) {
                val nestedScroll = remember(sheetHeightPx) {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                            if (available.y < 0 && detailsProgress < 1f) Offset(0f, dragDetails(available.y)) else Offset.Zero

                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (available.y <= 0 || source != NestedScrollSource.UserInput) return Offset.Zero
                            if (detailsProgress >= 1f) detailsDragStart = 1f
                            return Offset(0f, dragDetails(available.y))
                        }

                        override suspend fun onPreFling(available: Velocity): Velocity {
                            if (detailsProgress > 0f && detailsProgress < 1f) { settleDetails(available.y); return available }
                            return Velocity.Zero
                        }
                    }
                }
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetHeight)
                        .graphicsLayer { translationY = (1f - detailsProgress) * sheetHeightPx }
                        .testTag("photo-metadata-sheet"),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = ViewerSheet,
                    contentColor = ViewerWhite,
                ) {
                    DetailsPanel(
                        photo = photo,
                        onClose = { animateDetails(0f) },
                        handleModifier = Modifier.draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { dy -> dragDetails(dy) },
                            onDragStarted = { detailsDragStart = detailsProgress },
                            onDragStopped = { velocity -> settleDetails(velocity) },
                        ),
                        listModifier = Modifier.nestedScroll(nestedScroll),
                    )
                }
            }
        }
    }
    wallpaperChoice?.let { target ->
        WallpaperTargetDialog(onDismiss = { wallpaperChoice = null }, onChoose = { which ->
            wallpaperChoice = null
            scope.launch {
                val done = applyWallpaper(context, target, which)
                Toast.makeText(context, if (done) R.string.wallpaper_done else R.string.wallpaper_failed, Toast.LENGTH_SHORT).show()
            }
        })
    }
}

/** Where to put the wallpaper, for phones without Android's own crop-and-set screen. */
@Composable
private fun WallpaperTargetDialog(onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.wallpaper_target_title)) },
        text = {
            Column(Modifier.testTag("wallpaper-targets")) {
                listOf(R.string.wallpaper_home to WallpaperManager.FLAG_SYSTEM, R.string.wallpaper_lock to WallpaperManager.FLAG_LOCK,
                    R.string.wallpaper_both to (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)).forEach { (label, which) ->
                    TextButton(onClick = { onChoose(which) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label), Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

/**
 * Snap to open or closed. A flick decides first; otherwise a short drag (a quarter of the panel)
 * is enough to open the details, and closing likewise needs only a quarter of the way down.
 */
internal fun detailsSettleTarget(progress: Float, progressVelocity: Float, opening: Boolean): Float = when {
    progressVelocity > .5f -> 1f
    progressVelocity < -.5f -> 0f
    opening -> if (progress >= .25f) 1f else 0f
    else -> if (progress > .75f) 1f else 0f
}

/** A long enough pull or a quick downward fling closes the viewer. */
internal fun shouldDismissViewer(offset: Float, velocityY: Float, threshold: Float): Boolean =
    offset >= threshold || (offset > threshold / 4 && velocityY > 1_800f)

@Composable
private fun ViewerTopBar(
    photo: Photo, position: Int, count: Int,
    overflowOpen: Boolean, onOverflow: (Boolean) -> Unit,
    onClose: () -> Unit, onDetails: () -> Unit, onAddToAlbum: () -> Unit,
    onMove: (() -> Unit)?, onOpenWith: () -> Unit, onSetWallpaper: (() -> Unit)?, onUseAs: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.viewer_close), tint = ViewerWhite) }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(viewerDate(photo), color = ViewerWhite, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.viewer_position, position, count), color = ViewerMuted, style = MaterialTheme.typography.labelSmall)
        }
        Box {
            IconButton(onClick = { onOverflow(true) }, modifier = Modifier.testTag("viewer-more")) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.viewer_more), tint = ViewerWhite)
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { onOverflow(false) }, shape = RoundedCornerShape(18.dp)) {
                ViewerMenuItem(R.string.viewer_details, Icons.Outlined.Info, "viewer-menu-details", onDetails)
                ViewerMenuItem(R.string.viewer_add_to_album, Icons.Outlined.AddPhotoAlternate, "viewer-menu-album", onAddToAlbum)
                onMove?.let { ViewerMenuItem(R.string.viewer_move, Icons.Outlined.DriveFileMove, "viewer-menu-move", it) }
                ViewerMenuItem(R.string.viewer_open_with, Icons.AutoMirrored.Outlined.OpenInNew, "viewer-menu-open-with", onOpenWith)
                onSetWallpaper?.let { ViewerMenuItem(R.string.viewer_set_wallpaper, Icons.Outlined.Wallpaper, "viewer-menu-wallpaper", it) }
                onUseAs?.let { ViewerMenuItem(R.string.viewer_use_as, Icons.Outlined.AssignmentInd, "viewer-menu-use-as", it) }
            }
        }
    }
}

@Composable
private fun ViewerMenuItem(label: Int, icon: ImageVector, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick, leadingIcon = { Icon(icon, null) }, modifier = Modifier.testTag(tag))
}

@Composable
private fun ViewerBottomBar(
    video: ViewerVideo?,
    onShare: () -> Unit, onEdit: (() -> Unit)?, onDelete: (() -> Unit)?, onDetails: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .8f))))
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (video != null) VideoControls(video)
        // A small handle hints at the swipe-up details, and opens them with a tap.
        Box(Modifier.clip(CircleShape).clickable(onClick = onDetails).padding(horizontal = 18.dp, vertical = 2.dp).testTag("viewer-details-handle")) {
            Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.details_show), tint = ViewerMuted, modifier = Modifier.size(22.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ViewerAction(Icons.Outlined.Share, R.string.viewer_share, "viewer-share", onShare)
            if (onEdit != null) ViewerAction(Icons.Outlined.Edit, R.string.viewer_edit, "viewer-edit", onEdit)
            if (onDelete != null) ViewerAction(Icons.Outlined.DeleteOutline, R.string.action_delete, "viewer-delete", onDelete)
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, labelRes: Int, tag: String, onClick: () -> Unit, label: String? = null, tint: Color = ViewerWhite) {
    val text = label ?: stringResource(labelRes)
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).widthIn(min = 64.dp).padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(tag).semantics { contentDescription = text },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        Text(text, color = ViewerWhite, fontSize = 11.sp, maxLines = 1)
    }
}

private fun viewerDate(photo: Photo): String = if (photo.dateTaken > 0) {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(photo.dateTaken))
} else photo.name

/** One shared Media3 player for the viewer; only the current page shows it. */
@Stable
private class ViewerVideo(val player: ExoPlayer) {
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var rendered by mutableStateOf(false)
    var durationMs by mutableLongStateOf(0L)
    var muted by mutableStateOf(false)
    private var currentUri: String? = null

    fun load(photo: Photo?) {
        if (photo?.uri == currentUri) return
        currentUri = photo?.uri
        rendered = false
        failed = false
        durationMs = photo?.durationMillis ?: 0L
        if (photo == null) {
            player.stop()
            player.clearMediaItems()
            return
        }
        player.setMediaItem(MediaItem.fromUri(photo.uri))
        player.prepare()
        player.playWhenReady = true
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
    }
}

@Composable
private fun rememberViewerVideo(context: Context): ViewerVideo {
    val video = remember { ViewerVideo(ExoPlayer.Builder(context.applicationContext).build()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(video, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { video.playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                video.buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) video.player.duration.takeIf { it > 0 }?.let { video.durationMs = it }
            }
            override fun onPlayerError(error: PlaybackException) { video.failed = true }
            override fun onRenderedFirstFrame() { video.rendered = true }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) video.player.pause() }
        video.player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            video.player.removeListener(listener)
            video.player.release()
        }
    }
    return video
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(photo: Photo, active: Boolean, video: ViewerVideo, showControls: Boolean, onTap: () -> Unit) {
    val currentOnTap by rememberUpdatedState(onTap)
    val state = when {
        active && video.failed -> stringResource(R.string.video_state_unavailable)
        active && video.playing && !video.rendered -> stringResource(R.string.video_state_buffering)
        active && video.playing -> stringResource(R.string.video_state_playing)
        active && video.buffering -> stringResource(R.string.video_state_loading)
        else -> stringResource(R.string.video_state_paused)
    }
    Box(
        Modifier.fillMaxSize().testTag("video-viewer-player").semantics { stateDescription = state }
            .pointerInput(photo.uri) { detectTapGestures(onTap = { currentOnTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = { it.player = video.player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The thumbnail covers the surface until the first frame arrives, so swiping never flashes black.
        if (!active || !video.rendered) {
            AsyncImage(rememberThumbnailRequest(photo), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        when {
            active && video.failed -> Text(stringResource(R.string.video_unavailable), color = ViewerWhite,
                modifier = Modifier.background(Color.Black.copy(alpha = .6f), RoundedCornerShape(12.dp)).padding(12.dp))
            active && video.buffering && !video.rendered -> CircularProgressIndicator(color = ViewerAccent)
            active && (showControls || !video.playing) -> IconButton(
                onClick = { video.toggle() },
                modifier = Modifier.size(68.dp).background(Color.Black.copy(alpha = .45f), CircleShape),
            ) {
                Icon(if (video.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    stringResource(if (video.playing) R.string.video_pause else R.string.video_play), tint = ViewerWhite, modifier = Modifier.size(38.dp))
            }
            !active -> Icon(Icons.Outlined.PlayCircle, null, tint = ViewerWhite.copy(alpha = .85f), modifier = Modifier.size(56.dp))
        }
    }
}

@Composable
private fun VideoControls(video: ViewerVideo) {
    var position by remember { mutableLongStateOf(video.player.currentPosition) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(video) {
        while (true) {
            if (dragging == null) position = video.player.currentPosition
            delay(200)
        }
    }
    val duration = video.durationMs.coerceAtLeast(1L)
    val fraction = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f)
    val seekLabel = stringResource(R.string.video_seek)
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { video.toggle() }) {
            Icon(if (video.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                stringResource(if (video.playing) R.string.video_pause else R.string.video_play), tint = ViewerWhite)
        }
        Text(formatPlaybackTime((fraction * duration).toLong()), color = ViewerWhite, fontSize = 12.sp)
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { video.player.seekTo((it * duration).toLong()); position = (it * duration).toLong() }
                dragging = null
            },
            colors = SliderDefaults.colors(thumbColor = ViewerWhite, activeTrackColor = ViewerAccent, inactiveTrackColor = ViewerMuted.copy(alpha = .35f)),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("video-seek").semantics { contentDescription = seekLabel },
        )
        Text(formatPlaybackTime(video.durationMs), color = ViewerMuted, fontSize = 12.sp)
        IconButton(onClick = { video.toggleMute() }) {
            Icon(if (video.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                stringResource(if (video.muted) R.string.video_unmute else R.string.video_mute), tint = ViewerWhite)
        }
    }
}

internal fun formatPlaybackTime(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) / 1000)
    val hours = seconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun ZoomablePhoto(photo: Photo, active: Boolean, onZoomChanged: (Boolean) -> Unit, onTap: () -> Unit) {
    val context = LocalContext.current
    val request = remember(context, photo.uri, photo.dateModified, photo.size) {
        ImageRequest.Builder(context)
            .data(photo.uri)
            .memoryCacheKey("viewer-v1:${photo.uri}:${photo.dateModified}:${photo.size}")
            // Reuse the grid's already decoded bitmap immediately, while Coil loads the
            // original at viewer size. This does not start a second thumbnail request.
            .placeholderMemoryCacheKey(thumbnailMemoryCacheKey(photo))
            .crossfade(false)
            .build()
    }
    var scale by remember(photo.uri) { mutableFloatStateOf(1f) }
    var offset by remember(photo.uri) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var loading by remember(photo.uri) { mutableStateOf(true) }
    var failed by remember(photo.uri) { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)

    fun boundedOffset(candidate: Offset, zoom: Float): Offset {
        val maxX = max(0f, size.width * (zoom - 1f) / 2f)
        val maxY = max(0f, size.height * (zoom - 1f) / 2f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }
    LaunchedEffect(active, scale > 1f) {
        if (active) currentOnZoomChanged(scale > 1f)
    }
    // A page is reset after leaving it, keeping a fresh swipe sequence predictable.
    LaunchedEffect(active) {
        if (!active) { scale = 1f; offset = Offset.Zero }
    }

    Box(
        modifier = Modifier.fillMaxSize().testTag("photo-viewer-image").clipToBounds().onSizeChanged { size = it }
            .pointerInput(photo.uri) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = boundedOffset((center - tap) * (scale - 1), scale)
                        }
                    },
                )
            }
            .pointerInput(photo.uri) {
                // Pinch at any time; pan only while zoomed. Single-finger swipes stay with the pager and the viewer.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (multiTouch || scale > 1f) {
                            val previousScale = scale
                            val nextScale = (previousScale * event.calculateZoom()).coerceIn(1f, 5f)
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val focalPoint = if (centroid == Offset.Unspecified) Offset.Zero else centroid - center
                            val ratio = nextScale / previousScale
                            offset = boundedOffset((offset - focalPoint) * ratio + focalPoint + event.calculatePan(), nextScale)
                            scale = nextScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = photo.name,
            contentScale = ContentScale.Fit,
            onLoading = { loading = it.painter == null; failed = false },
            onSuccess = { loading = false; failed = false },
            onError = { loading = false; failed = true },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
        if (loading) CircularProgressIndicator(color = ViewerAccent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        if (failed) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Info, null, tint = ViewerMuted, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.viewer_photo_unavailable), color = ViewerWhite, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.viewer_photo_unavailable_message), color = ViewerMuted,
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}


@Composable
private fun DetailsPanel(
    photo: Photo, onClose: () -> Unit,
    handleModifier: Modifier, listModifier: Modifier,
) {
    val context = LocalContext.current
    val details by produceState<PhotoMetadataDetails?>(null, photo.uri, photo.dateModified, photo.size) {
        value = null
        value = PhotoMetadataDetailsReader.load(context, photo)
    }
    // Every category starts collapsed; the summary above them covers date, place and rating.
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
        Column(handleModifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(ViewerMuted.copy(alpha = .5f)))
            }
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (photo.isVideo) R.string.details_video_title else R.string.details_photo_title),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(photo.name, color = ViewerMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.details_close), tint = ViewerWhite) }
            }
        }
        LazyColumn(listModifier.fillMaxWidth().weight(1f).testTag("photo-details-list")) {
            item(key = "summary") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (photo.dateTaken > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.CalendarToday, null, tint = ViewerMuted, modifier = Modifier.size(18.dp))
                        Text(DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(photo.dateTaken)),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    val latitude = photo.latitude
                    val longitude = photo.longitude
                    if (latitude != null && longitude != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Place, null, tint = ViewerMuted, modifier = Modifier.size(18.dp))
                        Text("%.5f, %.5f".format(latitude, longitude), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { openLocation(context, latitude, longitude) }, colors = ButtonDefaults.textButtonColors(contentColor = ViewerAccent)) {
                            Text(stringResource(R.string.details_open_map))
                        }
                    }
                }
            }
            item(key = "rating") {
                if (photo.embeddedRating > 0) {
                    val ratingLabel = stringResource(R.string.details_rating_value, photo.embeddedRating)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp).semantics(mergeDescendants = true) {}
                        .testTag("details-rating"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row {
                            (1..5).forEach { star ->
                                Icon(if (photo.embeddedRating >= star) Icons.Filled.Star else Icons.Outlined.StarBorder, null,
                                    tint = if (photo.embeddedRating >= star) ViewerAccent else ViewerMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                        Text(ratingLabel, style = MaterialTheme.typography.bodyMedium, color = ViewerMuted)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 22.dp, vertical = 6.dp), color = Color.White.copy(alpha = .08f))
            }
            val loaded = details
            if (loaded == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ViewerAccent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
            } else {
                loaded.notice?.let { notice ->
                    item(key = "notice") { Text(notice, Modifier.padding(horizontal = 22.dp, vertical = 10.dp), color = ViewerMuted, style = MaterialTheme.typography.bodySmall) }
                }
                loaded.categories.forEach { category ->
                    val open = category.section.name in expanded
                    item(key = "category-${category.section.name}") {
                        val title = stringResource(category.section.titleRes)
                        val stateLabel = stringResource(if (open) R.string.details_expanded else R.string.details_collapsed)
                        Row(
                            Modifier.fillMaxWidth().semantics { stateDescription = stateLabel }.clickable {
                                expanded = if (open) expanded - category.section.name else expanded + category.section.name
                            }.padding(horizontal = 22.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ViewerWhite)
                            Text(category.entries.size.toString(), color = ViewerMuted, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(10.dp))
                            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                stringResource(if (open) R.string.details_collapse else R.string.details_expand, title), tint = ViewerMuted)
                        }
                    }
                    if (open) {
                        if (category.entries.isEmpty()) {
                            item(key = "empty-${category.section.name}") {
                                Text(stringResource(R.string.details_no_metadata), Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
                                    color = ViewerMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        itemsIndexed(category.entries, key = { index, _ -> "${category.section.name}-$index" }) { _, entry ->
                            MetadataValue(entry)
                        }
                    }
                    item(key = "divider-${category.section.name}") {
                        HorizontalDivider(Modifier.padding(horizontal = 22.dp), color = Color.White.copy(alpha = .08f))
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun MetadataValue(entry: MetadataDetail) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember(entry) { mutableStateOf(false) }
    val longValue = entry.value.length > 300
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.label, Modifier.weight(1f), color = ViewerMuted, style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { clipboard.setText(AnnotatedString(entry.value)) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ContentCopy, stringResource(R.string.details_copy, entry.label), tint = ViewerMuted, modifier = Modifier.size(16.dp))
            }
        }
        SelectionContainer {
            Text(if (longValue && !expanded) entry.value.take(300) + "…" else entry.value,
                color = ViewerWhite, style = MaterialTheme.typography.bodySmall)
        }
        if (longValue) {
            TextButton(onClick = { expanded = !expanded }, colors = ButtonDefaults.textButtonColors(contentColor = ViewerAccent)) {
                Text(stringResource(if (expanded) R.string.details_show_less else R.string.details_show_all))
            }
        }
    }
}
