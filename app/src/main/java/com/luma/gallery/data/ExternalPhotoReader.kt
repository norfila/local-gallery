package com.luma.gallery.data

import com.luma.gallery.R
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.util.Locale
import kotlin.math.absoluteValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ExternalPhotoRequest(val uri: Uri, val declaredMimeType: String?)

/** Ordinary camera review and Open with requests. Secure/lock-screen review is deliberately separate. */
object ExternalPhotoContract {
    const val REVIEW_ACTION = "android.provider.action.REVIEW"

    fun request(intent: Intent?): ExternalPhotoRequest? {
        intent ?: return null
        if (intent.action != Intent.ACTION_VIEW && intent.action != REVIEW_ACTION) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return null
        return ExternalPhotoRequest(uri, intent.type)
    }
}

/** Reads only the URI supplied by the sender; no library permission or persistent URI grant is needed. */
class ExternalPhotoReader(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun read(request: ExternalPhotoRequest): Photo? = withContext(Dispatchers.IO) {
        try {
            coroutineContext.ensureActive()
            val uri = request.uri
            if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return@withContext null
            val providerMime = runCatching { resolver.getType(uri) }.getOrNull()?.normalizedMime()
            val declaredMime = request.declaredMimeType?.normalizedMime()
            // Generic document providers often return application/octet-stream. Validate those by decoding.
            if (providerMime != null && providerMime != "application/octet-stream" && !providerMime.isVisualMime()) return@withContext null
            if (providerMime == null && declaredMime != null && declaredMime != "application/octet-stream" && !declaredMime.isVisualMime()) return@withContext null
            val videoRequest = providerMime?.startsWith("video/") == true || (providerMime == null && declaredMime?.startsWith("video/") == true)
            if (videoRequest) return@withContext readVideo(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val stream = resolver.openInputStream(uri) ?: return@withContext null
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType?.startsWith("image/") != true) {
                // A generic document provider may share a valid clip without a useful MIME type.
                return@withContext if (providerMime == null || providerMime == "application/octet-stream") readVideo(uri) else null
            }
            coroutineContext.ensureActive()
            val info = queryInfo(uri, false)
            val exif = readExif(uri)
            val embeddedRating = exif?.getAttributeBytes(ExifInterface.TAG_XMP)?.let(MetadataRating::fromXmp)
                ?: runCatching { resolver.openInputStream(uri)?.use(MetadataRating::fromContainer) }.getOrNull() ?: 0
            val make = exif?.getAttribute(ExifInterface.TAG_MAKE).clean()
            val model = exif?.getAttribute(ExifInterface.TAG_MODEL).clean()
            val coordinates = runCatching { exif?.latLong }.getOrNull()?.takeIf {
                it.size >= 2 && it[0].isFinite() && it[1].isFinite() && it[0] in -90.0..90.0 && it[1] in -180.0..180.0
            }
            Photo(
                id = -uri.toString().hashCode().toLong().absoluteValue - 1,
                uri = uri.toString(),
                name = info.name.ifBlank { appContext.getString(R.string.external_opened_photo) },
                folder = appContext.getString(R.string.external_opened_photo),
                folderKey = "external:",
                dateTaken = info.dateTaken,
                dateModified = info.dateModified,
                width = bounds.outWidth,
                height = bounds.outHeight,
                size = info.size,
                mimeType = bounds.outMimeType.orEmpty(),
                rating = embeddedRating,
                embeddedRating = embeddedRating,
                camera = if (model.startsWith(make, ignoreCase = true)) model else listOf(make, model).filter(String::isNotBlank).joinToString(" "),
                lens = exif?.getAttribute(ExifInterface.TAG_LENS_MODEL).clean(),
                iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 },
                aperture = exif.positiveDouble(ExifInterface.TAG_F_NUMBER),
                focalLength = exif.positiveDouble(ExifInterface.TAG_FOCAL_LENGTH),
                exposureSeconds = exif.positiveDouble(ExifInterface.TAG_EXPOSURE_TIME),
                latitude = coordinates?.get(0),
                longitude = coordinates?.get(1),
                metadataLoaded = true,
                libraryUri = info.libraryUri,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Includes revoked temporary grants, missing files and unavailable/cloud providers.
            null
        }
    }

    private fun readVideo(uri: Uri): Photo? {
        val locationAllowed = uri.authority != MediaStore.AUTHORITY || Build.VERSION.SDK_INT < 29 ||
            appContext.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val metadata = VideoMetadataReader.read(appContext, uri, locationAllowed) ?: return null
        val info = queryInfo(uri, true)
        return metadata.applyTo(Photo(
            id = -uri.toString().hashCode().toLong().absoluteValue - 1,
            uri = uri.toString(),
            name = info.name.ifBlank { appContext.getString(R.string.external_opened_video) },
            folder = appContext.getString(R.string.external_opened_video),
            folderKey = "external:",
            dateTaken = info.dateTaken,
            dateModified = info.dateModified,
            size = info.size,
            mimeType = metadata.mimeType,
            metadataLoaded = true,
            libraryUri = info.libraryUri,
        ))
    }

    private fun queryInfo(uri: Uri, video: Boolean): SourceInfo = runCatching {
        // Ask MediaStore for identity columns explicitly. Other providers may expose only
        // OpenableColumns; preserve their exact URI and avoid assuming they are MediaStore rows.
        val projection = if (uri.authority == MediaStore.AUTHORITY) buildList {
            add(OpenableColumns.DISPLAY_NAME)
            add(OpenableColumns.SIZE)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_MODIFIED)
            add(MediaStore.Images.Media._ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.VOLUME_NAME)
        }.toTypedArray() else null
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use SourceInfo()
            SourceInfo(
                name = cursor.text(OpenableColumns.DISPLAY_NAME),
                size = cursor.number(OpenableColumns.SIZE).coerceAtLeast(0),
                dateTaken = cursor.number(MediaStore.Images.Media.DATE_TAKEN).coerceAtLeast(0),
                dateModified = cursor.number(MediaStore.Images.Media.DATE_MODIFIED).coerceAtLeast(0),
                libraryUri = runCatching { libraryUri(uri, cursor, video) }.getOrNull(),
            )
        } ?: SourceInfo()
    }.getOrDefault(SourceInfo())

    /** A stable identity for local ratings/albums, never a replacement for the granted stream URI. */
    private fun libraryUri(source: Uri, cursor: Cursor, video: Boolean): String? {
        if (source.authority != MediaStore.AUTHORITY) return null
        val id = cursor.number(MediaStore.Images.Media._ID).takeIf { it > 0 } ?: return null
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val volume = cursor.text(MediaStore.MediaColumns.VOLUME_NAME)
            if (volume !in MediaStore.getExternalVolumeNames(appContext)) return null
            if (video) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
        } else {
            if (source.pathSegments.firstOrNull() != "external") return null
            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(collection, id).toString()
    }

    private fun readExif(uri: Uri): ExifInterface? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) }
    }.getOrNull() ?: runCatching {
        resolver.openInputStream(uri)?.use(::ExifInterface)
    }.getOrNull()

    private fun String.normalizedMime() = substringBefore(';').trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank)
    private fun String.isVisualMime() = startsWith("image/") || startsWith("video/")
    private fun String?.clean() = this?.trim()?.trim('\u0000').orEmpty()
    private fun ExifInterface?.positiveDouble(tag: String) = this?.getAttributeDouble(tag, Double.NaN)?.takeIf { it.isFinite() && it > 0 }
    private fun Cursor.text(column: String) = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString).orEmpty()
    private fun Cursor.number(column: String) = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L
    private data class SourceInfo(
        val name: String = "", val size: Long = 0, val dateTaken: Long = 0,
        val dateModified: Long = 0, val libraryUri: String? = null,
    )
}
