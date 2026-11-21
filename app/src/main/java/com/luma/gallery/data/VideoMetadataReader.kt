package com.luma.gallery.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Reads container headers only; never decodes a full clip during the library scan. */
internal object VideoMetadataReader {
    fun read(context: Context, uri: Uri, includeLocation: Boolean): VideoMetadata? {
        val original = if (includeLocation && uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= 29) {
            MediaStore.setRequireOriginal(uri)
        } else uri
        return readSource(context, original, includeLocation) ?: if (original != uri) readSource(context, uri, false) else null
    }

    private fun readSource(context: Context, uri: Uri, includeLocation: Boolean): VideoMetadata? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun value(key: Int): String? = retriever.extractMetadata(key)?.trim()?.takeIf(String::isNotEmpty)
            val width = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val height = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (width == 0 || height == 0) return@runCatching null
            val rotation = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            VideoMetadata(
                mimeType = value(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.takeIf { it.startsWith("video/") } ?: "video/mp4",
                durationMillis = value(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                width = if (rotation % 180 == 0) width else height,
                height = if (rotation % 180 == 0) height else width,
                rotation = rotation,
                dateTaken = parseVideoDate(value(MediaMetadataRetriever.METADATA_KEY_DATE)),
                coordinates = if (includeLocation) parseVideoLocation(value(MediaMetadataRetriever.METADATA_KEY_LOCATION)) else null,
                bitrate = value(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 },
                frameRate = value(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 },
                hasAudio = value(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } finally {
            retriever.release()
        }
    }.getOrNull()
}

internal data class VideoMetadata(
    val mimeType: String,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val dateTaken: Long?,
    val coordinates: Pair<Double, Double>?,
    val bitrate: Long?,
    val frameRate: Double?,
    val hasAudio: Boolean,
) {
    fun applyTo(photo: Photo): Photo = photo.copy(
        mimeType = mimeType,
        durationMillis = durationMillis.takeIf { it > 0 } ?: photo.durationMillis,
        width = width,
        height = height,
        dateTaken = dateTaken ?: photo.dateTaken,
        latitude = coordinates?.first,
        longitude = coordinates?.second,
    )
}

/** Android's MP4 extractor exposes decimal ISO 6709 coordinates such as +48.1370+011.5750/. */
internal fun parseVideoLocation(value: String?): Pair<Double, Double>? {
    val match = Regex("^([+-]\\d{2}(?:\\.\\d+)?)([+-]\\d{3}(?:\\.\\d+)?)(?:[+-]\\d+(?:\\.\\d+)?)?/?$")
        .matchEntire(value.orEmpty().trim()) ?: return null
    val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
    val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
    return if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) latitude to longitude else null
}

internal fun parseVideoDate(value: String?): Long? {
    value ?: return null
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.takeIf { it > 0 }?.let { return it }
    // MP4 creation dates are UTC. A date without its complete time is too ambiguous to replace MediaStore's date.
    for (pattern in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'")) {
        runCatching { LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern)).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrNull()?.takeIf { it > 0 }?.let { return it }
    }
    return null
}
