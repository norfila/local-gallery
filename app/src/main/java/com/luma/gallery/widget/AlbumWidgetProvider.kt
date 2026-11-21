package com.luma.gallery.widget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import android.os.SystemClock
import com.luma.gallery.MainActivity
import com.luma.gallery.R
import com.luma.gallery.data.Album
import com.luma.gallery.data.AppPreferences
import com.luma.gallery.data.LibraryStore
import com.luma.gallery.data.MediaRepository
import com.luma.gallery.data.Photo
import com.luma.gallery.data.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

open class AlbumWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> updateAsync(context, ids(context))
            ACTION_PINNED -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val token = intent.data?.takeIf { it.scheme == "gallery-widget" && it.host == "pin" }?.lastPathSegment
                val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)
                if (token != null && info?.provider == ComponentName(context, javaClass)) {
                    AlbumWidgetStore(context).consumePin(token)?.let { albumId ->
                        AlbumWidgetStore(context).save(widgetId, albumId)
                        updateAsync(context, intArrayOf(widgetId))
                    }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAsync(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        updateAsync(context, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = AlbumWidgetStore(context)
        appWidgetIds.forEach(store::remove)
        store.prunePins()
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val store = AlbumWidgetStore(context)
        oldWidgetIds.zip(newWidgetIds).forEach { (old, new) -> store.move(old, new) }
        updateAsync(context, newWidgetIds)
    }

    private fun updateAsync(context: Context, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_500) { updateWidgets(context.applicationContext, widgetIds) }
            } catch (_: CancellationException) {
                // Android gives receivers a bounded lifetime; a later refresh retries.
            } catch (_: Exception) {
                // A deleted host instance or unavailable media provider must not crash Gallery.
            } finally { pending?.finish() }
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.luma.gallery.widget.REFRESH"
        private const val ACTION_PINNED = "com.luma.gallery.widget.PINNED"
        private val updateMutex = Mutex()

        private fun ids(context: Context): IntArray {
            val manager = AppWidgetManager.getInstance(context)
            return AlbumWidgetSize.entries.flatMap { size ->
                manager.getAppWidgetIds(size.component(context)).asIterable()
            }.toIntArray()
        }

        fun refreshAll(context: Context) {
            context.sendBroadcast(Intent(context, AlbumWidgetProvider::class.java).setAction(ACTION_REFRESH))
        }

        /** The launcher confirms placement; the success callback supplies the real instance ID. */
        fun pinAlbum(context: Context, albumId: Long, size: AlbumWidgetSize = AlbumWidgetSize.ONE_BY_ONE): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            if (albumId <= 0 || !manager.isRequestPinAppWidgetSupported) return false
            val store = AlbumWidgetStore(context)
            val token = store.newPin(albumId)
            val callback = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, size.providerClass).setAction(ACTION_PINNED)
                    .setData(Uri.parse("gallery-widget://pin/$token")),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
            )
            return try {
                manager.requestPinAppWidget(size.component(context), null, callback)
                    .also { if (!it) { callback.cancel(); store.consumePin(token) } }
            } catch (_: IllegalStateException) {
                callback.cancel(); store.consumePin(token); false
            } catch (_: SecurityException) {
                callback.cancel(); store.consumePin(token); false
            }
        }

        /** The app hands over its freshly scanned library, so a refresh does not scan storage a second time. */
        @Volatile private var librarySnapshot: Pair<Long, List<Photo>>? = null

        fun shareLibrary(photos: List<Photo>) {
            librarySnapshot = SystemClock.elapsedRealtime() to photos
        }

        private fun recentLibrary(): List<Photo>? = librarySnapshot
            ?.takeIf { SystemClock.elapsedRealtime() - it.first < 5 * 60 * 1000L }?.second

        internal suspend fun updateWidgets(context: Context, widgetIds: IntArray) = updateMutex.withLock {
            val manager = AppWidgetManager.getInstance(context)
            val store = AlbumWidgetStore(context)
            val albums = runCatching { LibraryStore(context).use { it.albums() } }.getOrNull()
            val permission = hasPhotoAccess(context)
            val photos = if (!permission) null
                else recentLibrary() ?: runCatching { MediaRepository(context).use { it.loadPhotos() } }.getOrNull()
            // "System" uses night-qualified resources, so the launcher switches light and dark without Gallery running.
            val palette = when (AppPreferences(context).themeMode()) {
                ThemeMode.DARK -> WidgetPalette(R.drawable.widget_album_background, R.drawable.widget_album_image_background, R.drawable.widget_album_icon)
                ThemeMode.LIGHT -> WidgetPalette(R.drawable.widget_album_background_light, R.drawable.widget_album_image_background_light, R.drawable.widget_album_icon_light)
                ThemeMode.SYSTEM -> WidgetPalette(R.drawable.widget_album_background_auto, R.drawable.widget_album_image_background_auto, R.drawable.widget_album_icon_auto)
            }
            widgetIds.forEach { widgetId ->
                val albumId = store.albumId(widgetId)
                val album = albums?.firstOrNull { it.id == albumId }
                val state = snapshot(albumId, album, photos, permission, albums != null)
                val title = state.title.resolve(context.resources)
                val detail = state.detail.resolve(context.resources)
                val views = RemoteViews(context.packageName, R.layout.widget_album)
                views.setInt(R.id.widget_album_root, "setBackgroundResource", palette.surface)
                views.setInt(R.id.widget_album_image, "setBackgroundResource", palette.image)
                views.setInt(R.id.widget_album_cover, "setBackgroundResource", palette.image)
                views.setImageViewResource(R.id.widget_album_placeholder, palette.icon)
                // A consistent dark scrim keeps a cover's title readable in either app theme.
                views.setTextColor(R.id.widget_album_title, 0xFFFFFFFF.toInt())
                views.setTextColor(R.id.widget_album_count, 0xFFEAF0E4.toInt())
                val options = manager.getAppWidgetOptions(widgetId)
                val compact = compactWidget(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 72),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72),
                )
                views.setTextViewTextSize(R.id.widget_album_title, TypedValue.COMPLEX_UNIT_SP, if (compact) 12f else 16f)
                views.setViewVisibility(R.id.widget_album_count, if (compact) View.GONE else View.VISIBLE)
                views.setTextViewText(R.id.widget_album_title, title)
                views.setTextViewText(R.id.widget_album_count, detail)
                views.setContentDescription(R.id.widget_album_root, context.getString(R.string.widget_open_album, title, detail))
                val cover = state.coverUri?.let { thumbnail(context, it) }?.let { source ->
                    val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
                    val widthDp = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 72).coerceAtLeast(40)
                    val heightDp = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72).coerceAtLeast(40)
                    val density = context.resources.displayMetrics.density
                    // Bound RemoteViews bitmap memory even if a launcher supplies enormous sizes.
                    val scale = min(1f, 512f / (max(widthDp, heightDp) * density))
                    roundedWidgetCover(source, (widthDp * density * scale).roundToInt(), (heightDp * density * scale).roundToInt(), 24 * density * scale)
                }
                views.setImageViewBitmap(R.id.widget_album_cover, cover)
                views.setViewVisibility(R.id.widget_album_placeholder, if (cover == null) View.VISIBLE else View.GONE)
                // Without a usable album, a tap goes straight to choosing one for this widget.
                val open = if (album == null && albums != null) {
                    Intent(context, AlbumWidgetConfigureActivity::class.java)
                        .setData(Uri.parse("gallery-widget://configure/$widgetId"))
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                } else Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("gallery-widget://album/$widgetId/${albumId ?: 0}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (album != null) open.putExtra(WidgetContract.EXTRA_ALBUM_ID, album.id)
                val click = PendingIntent.getActivity(context, widgetId, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_album_root, click)
                manager.updateAppWidget(widgetId, views)
            }
        }

        private fun hasPhotoAccess(context: Context): Boolean {
            fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            return when {
                Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
                else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        private fun thumbnail(context: Context, value: String): Bitmap? = runCatching {
            val uri = Uri.parse(value)
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(360, 360), null)
            else if (context.contentResolver.getType(uri)?.startsWith("video/") == true) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
                    } else {
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                            val scale = min(1f, 360f / max(frame.width, frame.height))
                            if (scale == 1f) frame else {
                                Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt().coerceAtLeast(1),
                                    (frame.height * scale).roundToInt().coerceAtLeast(1), true).also { frame.recycle() }
                            }
                        }
                    }
                } finally { retriever.release() }
            }
            else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 720 || bounds.outHeight / inSampleSize > 720) inSampleSize *= 2
                }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }
        }.getOrNull()
    }
}

private data class WidgetPalette(val surface: Int, val image: Int, val icon: Int)

/** Widget text stays as resources until it is drawn, so it follows the phone's language. */
internal sealed interface WidgetLabel {
    data class Text(val value: String) : WidgetLabel
    data class Res(@StringRes val id: Int) : WidgetLabel
    data class Plural(@PluralsRes val id: Int, val count: Int) : WidgetLabel

    fun resolve(resources: Resources): String = when (this) {
        is Text -> value
        is Res -> resources.getString(id)
        is Plural -> resources.getQuantityString(id, count, count)
    }
}

internal data class AlbumWidgetSnapshot(val title: WidgetLabel, val detail: WidgetLabel, val coverUri: String? = null)

/** Count exactly the same linked-photo/folder union as the album screen, without duplicates. */
internal fun snapshot(albumId: Long?, album: Album?, photos: List<Photo>?, permission: Boolean, albumsLoaded: Boolean = true): AlbumWidgetSnapshot {
    if (albumId == null) return AlbumWidgetSnapshot(WidgetLabel.Res(R.string.widget_choose_album), WidgetLabel.Res(R.string.widget_choose_album_detail))
    if (!albumsLoaded) return AlbumWidgetSnapshot(WidgetLabel.Res(R.string.widget_album_unavailable), WidgetLabel.Res(R.string.widget_tap_to_open))
    if (album == null) return AlbumWidgetSnapshot(WidgetLabel.Res(R.string.widget_album_removed), WidgetLabel.Res(R.string.widget_album_removed_detail))
    val name = WidgetLabel.Text(album.name)
    if (!permission) return AlbumWidgetSnapshot(name, WidgetLabel.Res(R.string.widget_allow_access))
    if (photos == null) return AlbumWidgetSnapshot(name, WidgetLabel.Res(R.string.widget_tap_to_refresh))
    // Hidden media never reaches the home screen, even when it was added to the album.
    val members = album.photos(photos).filterNot { it.hidden }
    val videos = members.count { it.isVideo }
    val detail = when {
        members.isEmpty() -> WidgetLabel.Res(R.string.widget_no_photos)
        videos == 0 -> WidgetLabel.Plural(R.plurals.photo_count, members.size)
        videos == members.size -> WidgetLabel.Plural(R.plurals.video_count, members.size)
        else -> WidgetLabel.Plural(R.plurals.item_count, members.size)
    }
    return AlbumWidgetSnapshot(name, detail, album.coverPhoto(members)?.uri)
}

/** A one-cell widget prioritizes its cover and a single readable album label. */
internal fun compactWidget(minWidthDp: Int, minHeightDp: Int): Boolean = minWidthDp < 110 || minHeightDp < 110

/**
 * Clip pixels before sending them to RemoteViews: XML clipToOutline is unavailable on
 * Android 8–11. FIT_XY preserves this mask when a launcher adjusts the widget's bounds.
 */
internal fun roundedWidgetCover(source: Bitmap, width: Int, height: Int, radius: Float): Bitmap {
    val targetWidth = width.coerceAtLeast(1)
    val targetHeight = height.coerceAtLeast(1)
    val readable = if (source.config == Bitmap.Config.HARDWARE) requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) else source
    val scale = max(targetWidth.toFloat() / readable.width, targetHeight.toFloat() / readable.height)
    val shader = BitmapShader(readable, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate((targetWidth - readable.width * scale) / 2, (targetHeight - readable.height * scale) / 2)
        })
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
    val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val corner = radius.coerceIn(0f, min(targetWidth, targetHeight) / 2f)
    Canvas(output).drawRoundRect(RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()), corner, corner, paint)
    return output
}
