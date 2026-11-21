package com.luma.gallery.image

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import com.luma.gallery.data.Photo

internal const val MEDIASTORE_THUMBNAIL = "gallery#media-store-thumbnail"
internal const val THUMBNAIL_ASPECT_RATIO = "gallery#thumbnail-aspect-ratio"

internal fun thumbnailMemoryCacheKey(photo: Photo): String =
    "thumbnail-v1:${photo.uri}:${photo.dateModified}:${photo.size}"

/** Keep thumbnails separate from full-screen images and invalidate them when a file changes. */
internal fun thumbnailRequest(context: Context, photo: Photo): ImageRequest = ImageRequest.Builder(context)
    .data(photo.uri)
    .setParameter(MEDIASTORE_THUMBNAIL, true)
    .setParameter(THUMBNAIL_ASPECT_RATIO, if (photo.width > 0 && photo.height > 0) photo.width.toDouble() / photo.height else 0.0)
    .memoryCacheKey(thumbnailMemoryCacheKey(photo))
    // Many simultaneous fades keep redrawing the grid while a fast fling is already busy.
    .crossfade(false)
    .build()

@Composable
internal fun rememberThumbnailRequest(photo: Photo): ImageRequest {
    val context = LocalContext.current
    return remember(context, photo.uri, photo.dateModified, photo.size, photo.width, photo.height) { thumbnailRequest(context, photo) }
}
