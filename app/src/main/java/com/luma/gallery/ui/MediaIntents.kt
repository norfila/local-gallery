package com.luma.gallery.ui

import android.app.WallpaperManager
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.luma.gallery.R
import com.luma.gallery.data.Photo
import com.luma.gallery.image.centeredCrop
import com.luma.gallery.image.loadEditingBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hidden files found by the file scan use file:// URIs, which Android forbids sharing directly. */
internal fun shareableUri(context: Context, photo: Photo): Uri {
    val uri = Uri.parse(photo.uri)
    if (uri.scheme != ContentResolver.SCHEME_FILE) return uri
    return FileProvider.getUriForFile(context, "${context.packageName}.files", File(requireNotNull(uri.path)))
}

internal fun sharePhotos(context: Context, photos: List<Photo>) {
    if (photos.isEmpty()) return
    runCatching {
        val uris = photos.map { shareableUri(context, it) }
        val type = photos.map { it.mimeType.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.type = if (uris.size == 1) photos.first().mimeType else type
        send.clipData = ClipData.newUri(context.contentResolver, photos.first().name, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_title)))
    }.onFailure { Toast.makeText(context, R.string.share_unavailable, Toast.LENGTH_SHORT).show() }
}

internal fun openWith(context: Context, photo: Photo) {
    runCatching {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(shareableUri(context, photo), photo.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(view, context.getString(R.string.viewer_open_with)))
    }.onFailure { Toast.makeText(context, R.string.open_with_unavailable, Toast.LENGTH_SHORT).show() }
}

/**
 * Opens Android's own crop-and-set wallpaper screen. Many current wallpaper apps no longer answer
 * "Use as" (ACTION_ATTACH_DATA), so this is offered separately. False: the phone has no such screen.
 */
internal fun openWallpaperCrop(context: Context, photo: Photo): Boolean = runCatching {
    val intent = WallpaperManager.getInstance(context).getCropAndSetWallpaperIntent(shareableUri(context, photo))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(intent)
}.isSuccess

/** Sets the wallpaper directly, showing the middle of the photo in the screen's shape. True when it was set. */
internal suspend fun applyWallpaper(context: Context, photo: Photo, which: Int): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap = loadEditingBitmap(context, Uri.parse(photo.uri))
        try {
            val metrics = context.resources.displayMetrics
            val screen = min(metrics.widthPixels, metrics.heightPixels).toFloat() / max(metrics.widthPixels, metrics.heightPixels)
            val crop = centeredCrop(screen, bitmap.width.toFloat(), bitmap.height.toFloat())
            val visible = Rect((crop.left * bitmap.width).roundToInt(), (crop.top * bitmap.height).roundToInt(),
                (crop.right * bitmap.width).roundToInt(), (crop.bottom * bitmap.height).roundToInt())
            WallpaperManager.getInstance(context).setBitmap(bitmap, visible, true, which) != 0
        } finally { bitmap.recycle() }
    }.getOrDefault(false)
}

/** Wallpaper, contact photo and similar targets offered by other apps. */
internal fun useAs(context: Context, photo: Photo) {
    runCatching {
        val attach = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(shareableUri(context, photo), photo.mimeType)
            .putExtra("mimeType", photo.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(attach, context.getString(R.string.viewer_use_as)))
    }.onFailure { Toast.makeText(context, R.string.open_with_unavailable, Toast.LENGTH_SHORT).show() }
}

internal fun openLocation(context: Context, latitude: Double, longitude: Double) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")))
    }.onFailure { Toast.makeText(context, R.string.open_with_unavailable, Toast.LENGTH_SHORT).show() }
}
