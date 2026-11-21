package com.luma.gallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class BinItem(val photo: Photo, val expiresAtMillis: Long)

/**
 * Android 11+ keeps a system-wide bin: binned items disappear from every gallery and are
 * deleted permanently after about 30 days. Android asks the user to confirm each change.
 * Older versions, and files Gallery reads outside MediaStore, can only be deleted permanently.
 */
class MediaBin(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** The MediaStore row used for writes; an externally opened item may carry its library identity. */
    fun mediaStoreUri(photo: Photo): Uri? = Uri.parse(photo.libraryUri ?: photo.uri)
        .takeIf { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority == MediaStore.AUTHORITY && it.lastPathSegment?.toLongOrNull() != null }

    fun canBin(photo: Photo): Boolean = supported && mediaStoreUri(photo) != null

    @RequiresApi(Build.VERSION_CODES.R)
    fun trashRequest(photos: List<Photo>): IntentSender =
        MediaStore.createTrashRequest(resolver, photos.mapNotNull(::mediaStoreUri), true).intentSender

    @RequiresApi(Build.VERSION_CODES.R)
    fun restoreRequest(items: List<BinItem>): IntentSender =
        MediaStore.createTrashRequest(resolver, items.map { Uri.parse(it.photo.uri) }, false).intentSender

    @RequiresApi(Build.VERSION_CODES.R)
    fun deleteForeverRequest(items: List<BinItem>): IntentSender =
        MediaStore.createDeleteRequest(resolver, items.map { Uri.parse(it.photo.uri) }).intentSender

    /** Deletes without a bin. Returns the URIs that were removed. */
    suspend fun deletePermanently(photos: List<Photo>): Set<String> = withContext(Dispatchers.IO) {
        val deleted = HashSet<String>()
        for (photo in photos) {
            coroutineContext.ensureActive()
            val removed = try {
                val uri = Uri.parse(photo.uri)
                val mediaUri = mediaStoreUri(photo)
                when {
                    mediaUri != null -> resolver.delete(mediaUri, null, null) > 0
                    uri.scheme == ContentResolver.SCHEME_FILE -> uri.path?.let { File(it).delete() } == true
                    DocumentsContract.isDocumentUri(appContext, uri) -> DocumentsContract.deleteDocument(resolver, uri)
                    else -> false
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { false }
            if (removed) deleted += photo.uri
        }
        deleted
    }

    /** Items currently in Android's bin, soonest expiry first. */
    suspend fun load(): List<BinItem> = withContext(Dispatchers.IO) {
        if (!supported) return@withContext emptyList()
        loadTrashed()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun loadTrashed(): List<BinItem> {
        val items = ArrayList<BinItem>()
        for (volume in MediaStore.getExternalVolumeNames(appContext).sorted()) {
            for (video in listOf(false, true)) {
                coroutineContext.ensureActive()
                val collection = if (video) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
                val projection = buildList {
                    add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME); add(MediaStore.MediaColumns.DATE_EXPIRES)
                    add(MediaStore.MediaColumns.DATE_TAKEN); add(MediaStore.MediaColumns.DATE_MODIFIED); add(MediaStore.MediaColumns.SIZE)
                    add(MediaStore.MediaColumns.MIME_TYPE); add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT)
                    add(MediaStore.MediaColumns.RELATIVE_PATH)
                    if (video) add(MediaStore.MediaColumns.DURATION)
                }.toTypedArray()
                val arguments = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
                val cursor = try { resolver.query(collection, projection, arguments, null) } catch (_: SecurityException) { null }
                cursor?.use {
                    fun number(column: String) = it.getColumnIndex(column).takeIf { index -> index >= 0 && !it.isNull(index) }?.let(it::getLong) ?: 0L
                    fun text(column: String) = it.getColumnIndex(column).takeIf { index -> index >= 0 && !it.isNull(index) }?.let(it::getString).orEmpty()
                    while (it.moveToNext()) {
                        val id = number(MediaStore.MediaColumns._ID)
                        val path = text(MediaStore.MediaColumns.RELATIVE_PATH).trimEnd('/')
                        val modified = number(MediaStore.MediaColumns.DATE_MODIFIED)
                        val photo = Photo(
                            id = items.size.toLong() + 1,
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            name = binDisplayName(text(MediaStore.MediaColumns.DISPLAY_NAME)),
                            folder = path.substringAfterLast('/'),
                            folderKey = "$volume:$path/",
                            dateTaken = number(MediaStore.MediaColumns.DATE_TAKEN).takeIf { taken -> taken > 0 } ?: (modified * 1000L),
                            dateModified = modified,
                            width = number(MediaStore.MediaColumns.WIDTH).toInt(),
                            height = number(MediaStore.MediaColumns.HEIGHT).toInt(),
                            size = number(MediaStore.MediaColumns.SIZE),
                            mimeType = text(MediaStore.MediaColumns.MIME_TYPE).ifBlank { if (video) "video/mp4" else "image/jpeg" },
                            durationMillis = if (video) number(MediaStore.MediaColumns.DURATION) else 0,
                        )
                        items += BinItem(photo, number(MediaStore.MediaColumns.DATE_EXPIRES) * 1000L)
                    }
                }
            }
        }
        return items.sortedWith(compareBy<BinItem> { it.expiresAtMillis }.thenBy { it.photo.uri })
    }

    companion object {
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
}

/** Binned files are renamed to ".trashed-<expiry>-<original name>". */
internal fun binDisplayName(name: String): String = name.replace(Regex("^\\.trashed-\\d+-"), "")
