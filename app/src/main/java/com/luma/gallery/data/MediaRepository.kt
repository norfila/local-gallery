package com.luma.gallery.data

import com.luma.gallery.R
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** All operations read originals; ratings created in the app are stored separately by LibraryStore. */
class MediaRepository(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val cache = MetadataCache(appContext)
    private val preferences = AppPreferences(appContext)

    /**
     * Query the system's recursive media index, including removable storage volumes. Indexed items
     * in dot folders are included, marked hidden, only while the Hidden page is on.
     */
    suspend fun loadPhotos(): List<Photo> = withContext(Dispatchers.IO) {
        val showHidden = preferences.showHiddenMedia()
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(appContext).sorted().flatMap { volume -> listOf(
                MediaCollection(volume, MediaStore.Images.Media.getContentUri(volume), false),
                MediaCollection(volume, MediaStore.Video.Media.getContentUri(volume), true),
            ) }
        } else listOf(
            MediaCollection("external", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false),
            MediaCollection("external", MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true),
        )
        val commonProjection = mutableListOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.MIME_TYPE,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.RELATIVE_PATH)
            else {
                @Suppress("DEPRECATION")
                add(MediaStore.Images.Media.DATA)
            }
        }
        val selection = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "${MediaStore.Images.Media.IS_PENDING} = 0 AND ${MediaStore.Images.Media.IS_TRASHED} = 0"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> "${MediaStore.Images.Media.IS_PENDING} = 0"
            else -> null
        }
        val photos = ArrayList<Photo>()
        for ((volume, collection, video) in volumes) {
            coroutineContext.ensureActive()
            val projection = (commonProjection + if (video) listOf(MediaStore.Video.Media.DURATION) else emptyList()).toTypedArray()
            // Android permits granting images and videos independently, including selected media.
            val cursor = try {
                resolver.query(collection, projection, selection, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")
            } catch (_: SecurityException) { null }
            cursor?.use { cursor ->
                val columns = projection.associateWith(cursor::getColumnIndex)
                fun number(column: String): Long = columns[column]?.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong) ?: 0L
                fun text(column: String): String = columns[column]?.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString).orEmpty()
                while (cursor.moveToNext()) {
                    if (photos.size % 128 == 0) coroutineContext.ensureActive()
                    val id = number(MediaStore.Images.Media._ID)
                    val bucket = text(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        text(MediaStore.Images.Media.RELATIVE_PATH).replace('\\', '/')
                    } else {
                        @Suppress("DEPRECATION")
                        File(text(MediaStore.Images.Media.DATA)).parent.orEmpty().replace('\\', '/')
                    }
                    val path = relativePath.trimEnd('/')
                    val name = text(MediaStore.Images.Media.DISPLAY_NAME).ifBlank { "${if (video) "Video" else "Photo"} $id" }
                    val hidden = isHiddenMediaPath(path, name)
                    if (hidden && !showHidden) continue
                    val folderKey = "$volume:${path.ifBlank { "bucket/${number(MediaStore.Images.Media.BUCKET_ID)}" }}/"
                    val dateModified = number(MediaStore.Images.Media.DATE_MODIFIED)
                    photos += Photo(
                        // MediaStore row IDs can repeat on another storage volume. The URI is
                        // the persistent identity; this numeric ID is unique within this snapshot.
                        id = photos.size.toLong() + 1,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        name = name,
                        folder = path.substringAfterLast('/').ifBlank { bucket.ifBlank { appContext.getString(R.string.storage_fallback) } },
                        folderKey = folderKey,
                        dateTaken = number(MediaStore.Images.Media.DATE_TAKEN).takeIf { it > 0 }
                            ?: (number(MediaStore.Images.Media.DATE_ADDED).takeIf { it > 0 } ?: dateModified) * 1000L,
                        dateModified = dateModified,
                        width = number(MediaStore.Images.Media.WIDTH).toInt().coerceAtLeast(0),
                        height = number(MediaStore.Images.Media.HEIGHT).toInt().coerceAtLeast(0),
                        size = number(MediaStore.Images.Media.SIZE),
                        mimeType = text(MediaStore.Images.Media.MIME_TYPE).ifBlank { if (video) "video/mp4" else "image/jpeg" },
                        durationMillis = if (video) number(MediaStore.Video.Media.DURATION).coerceAtLeast(0) else 0,
                        hidden = hidden,
                    )
                }
            }
        }
        photos.sortedWith(compareByDescending<Photo> { it.dateTaken }.thenBy { it.uri })
    }

    /**
     * Media Android does not index, for the Hidden page: a direct file scan (with all files access)
     * and folders granted in the picker. Empty while the Hidden page is off.
     */
    suspend fun loadHiddenPhotos(indexed: List<Photo>, force: Boolean = false): List<Photo> = withContext(Dispatchers.IO) {
        if (!preferences.showHiddenMedia()) return@withContext emptyList()
        val scanned = HiddenFileScanner.loadPhotos(appContext, indexed, force)
        scanned + HiddenMediaSource(appContext).loadPhotos(preferences.hiddenMediaFolders(), indexed + scanned)
    }

    /** Enrich a row lazily. A corrupt or unsupported image remains visible in the gallery. */
    suspend fun loadMetadata(photo: Photo): Photo = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val locationAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        cache.find(photo, locationAllowed)?.let { return@withContext it }
        if (photo.isVideo) {
            val video = VideoMetadataReader.read(appContext, Uri.parse(photo.uri), locationAllowed)
            val enriched = video?.applyTo(photo)?.copy(metadataLoaded = true) ?: photo.copy(metadataLoaded = true)
            if (video != null) cache.save(enriched, locationAllowed)
            return@withContext enriched
        }
        val uri = Uri.parse(photo.uri)
        val originalUri = if (locationAllowed && uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.setRequireOriginal(uri)
        } else uri

        // Some providers do not expose an original even when location access was granted.
        var originalOpened = locationAllowed
        val exif = readExif(originalUri) ?: if (originalUri != uri) {
            originalOpened = false
            readExif(uri)
        } else null
        val xmpRating = exif?.getAttributeBytes(ExifInterface.TAG_XMP)?.let(MetadataRating::fromXmp)
        val legacyRating = if (xmpRating == null) readLegacyRating(uri, photo.mimeType) else null
        val rating = xmpRating ?: legacyRating ?: 0
        val coordinates = if (originalOpened) {
            runCatching { exif?.latLong }.getOrNull()?.takeIf {
                it.size >= 2 && it[0].isFinite() && it[1].isFinite() && it[0] in -90.0..90.0 && it[1] in -180.0..180.0
            }
        } else null
        val make = exif?.getAttribute(ExifInterface.TAG_MAKE).clean()
        val model = exif?.getAttribute(ExifInterface.TAG_MODEL).clean()
        val camera = if (model.startsWith(make, ignoreCase = true)) model else listOf(make, model).filter(String::isNotBlank).joinToString(" ")
        val dimensions = if (photo.width <= 0 || photo.height <= 0) readDimensions(uri, exif) else null
        val captureDate = if (uri.authority != MediaStore.AUTHORITY) exifCaptureDate(exif) else null
        val enriched = photo.copy(
            width = dimensions?.first ?: photo.width,
            height = dimensions?.second ?: photo.height,
            dateTaken = captureDate ?: photo.dateTaken,
            rating = rating,
            embeddedRating = rating,
            camera = camera,
            lens = exif?.getAttribute(ExifInterface.TAG_LENS_MODEL).clean(),
            iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 },
            aperture = exif.positiveDouble(ExifInterface.TAG_F_NUMBER),
            focalLength = exif.positiveDouble(ExifInterface.TAG_FOCAL_LENGTH),
            exposureSeconds = exif.positiveDouble(ExifInterface.TAG_EXPOSURE_TIME),
            latitude = coordinates?.get(0),
            longitude = coordinates?.get(1),
            metadataLoaded = true,
        )
        // Do not persist a transient provider/open failure. Retry on the next library refresh.
        if (exif != null || legacyRating != null || dimensions != null) cache.save(enriched, locationAllowed)
        enriched
    }

    /** Header-only dimensions keep unindexed images out of full-size bitmap decoding. */
    private fun readDimensions(uri: Uri, exif: ExifInterface?): Pair<Int, Int>? {
        var width = exif?.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)?.takeIf { it > 0 }
            ?: exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) ?: 0
        var height = exif?.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)?.takeIf { it > 0 }
            ?: exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) ?: 0
        if (width <= 0 || height <= 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
            width = bounds.outWidth
            height = bounds.outHeight
        }
        if (width <= 0 || height <= 0) return null
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        return if (orientation in setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)) height to width else width to height
    }

    private fun exifCaptureDate(exif: ExifInterface?): Long? {
        val value = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif?.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        return runCatching {
            LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().takeIf { it > 0 }
        }.getOrNull()
    }

    private fun readExif(uri: Uri): ExifInterface? = runCatching {
        // A descriptor lets ExifInterface seek past image data instead of buffering it.
        resolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) }
    }.getOrNull() ?: runCatching {
        // Cloud and document providers may expose a stream without a seekable descriptor.
        resolver.openInputStream(uri)?.use(::ExifInterface)
    }.getOrNull()

    suspend fun clearMetadataCache() = withContext(Dispatchers.IO) { cache.clear() }

    override fun close() { cache.close() }

    private fun readLegacyRating(uri: Uri, mimeType: String): Int? {
        val containerRating = runCatching { resolver.openInputStream(uri)?.use(MetadataRating::fromContainer) }.getOrNull()
        if (containerRating != null) return containerRating
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mimeType !in setOf("image/heic", "image/heif", "image/avif")) return null
        // The platform locates the bounded EXIF block inside ISO base media containers.
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, uri)
                val offset = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_EXIF_OFFSET)?.toLongOrNull()
                val length = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_EXIF_LENGTH)?.toIntOrNull()
                if (offset == null || offset < 0 || length == null || length !in 8..MetadataRating.MAX_METADATA_BYTES) return@runCatching null
                resolver.openInputStream(uri)?.use { input ->
                    with(MetadataRating) {
                        if (!input.skipFully(offset)) return@use null
                    }
                    val bytes = ByteArray(length)
                    var count = 0
                    while (count < length) {
                        val read = input.read(bytes, count, length - count)
                        if (read <= 0) return@use null
                        count += read
                    }
                    MetadataRating.fromExifBlock(bytes)
                }
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    private fun String?.clean(): String = this?.trim()?.trim('\u0000').orEmpty()
    private fun ExifInterface?.positiveDouble(tag: String): Double? = this?.getAttributeDouble(tag, Double.NaN)
        ?.takeIf { it.isFinite() && it > 0 }

    private data class MediaCollection(val volume: String, val uri: Uri, val video: Boolean)
}

/** The cache never stores user rating overrides or album membership. */
private class MetadataCache(context: Context) : SQLiteOpenHelper(context, "photo_metadata.db", null, 2) {
    fun clear() { writableDatabase.delete("metadata", null, null) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE metadata (
                uri TEXT PRIMARY KEY, modified INTEGER NOT NULL, size INTEGER NOT NULL,
                location_allowed INTEGER NOT NULL, rating INTEGER NOT NULL,
                camera TEXT NOT NULL, lens TEXT NOT NULL, iso INTEGER, aperture REAL,
                focal_length REAL, exposure REAL, latitude REAL, longitude REAL,
                duration INTEGER NOT NULL DEFAULT 0, width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0, date_taken INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS metadata")
        onCreate(db)
    }

    fun find(photo: Photo, locationAllowed: Boolean): Photo? = runCatching {
        readableDatabase.query(
            "metadata", null, "uri = ? AND modified = ? AND size = ? AND location_allowed = ?",
            arrayOf(photo.uri, photo.dateModified.toString(), photo.size.toString(), if (locationAllowed) "1" else "0"),
            null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val rating = cursor.getInt(cursor.getColumnIndexOrThrow("rating"))
            photo.copy(
                rating = rating, embeddedRating = rating,
                camera = cursor.getString(cursor.getColumnIndexOrThrow("camera")),
                lens = cursor.getString(cursor.getColumnIndexOrThrow("lens")),
                iso = cursor.nullableDouble("iso")?.toInt(), aperture = cursor.nullableDouble("aperture"),
                focalLength = cursor.nullableDouble("focal_length"), exposureSeconds = cursor.nullableDouble("exposure"),
                latitude = cursor.nullableDouble("latitude"), longitude = cursor.nullableDouble("longitude"),
                durationMillis = cursor.getLong(cursor.getColumnIndexOrThrow("duration")).takeIf { it > 0 } ?: photo.durationMillis,
                width = cursor.getInt(cursor.getColumnIndexOrThrow("width")).takeIf { it > 0 } ?: photo.width,
                height = cursor.getInt(cursor.getColumnIndexOrThrow("height")).takeIf { it > 0 } ?: photo.height,
                dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow("date_taken")).takeIf { it > 0 } ?: photo.dateTaken,
                metadataLoaded = true,
            )
        }
    }.getOrNull()

    fun save(photo: Photo, locationAllowed: Boolean) {
        runCatching {
            val values = ContentValues().apply {
                put("uri", photo.uri); put("modified", photo.dateModified); put("size", photo.size)
                put("location_allowed", if (locationAllowed) 1 else 0); put("rating", photo.embeddedRating)
                put("camera", photo.camera); put("lens", photo.lens); put("iso", photo.iso)
                put("aperture", photo.aperture); put("focal_length", photo.focalLength); put("exposure", photo.exposureSeconds)
                put("latitude", photo.latitude); put("longitude", photo.longitude)
                put("duration", photo.durationMillis); put("width", photo.width); put("height", photo.height)
                put("date_taken", photo.dateTaken)
            }
            writableDatabase.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun Cursor.nullableDouble(column: String): Double? = getColumnIndexOrThrow(column).let {
        if (isNull(it)) null else getDouble(it)
    }
}
