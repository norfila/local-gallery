package com.luma.gallery.image

import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.util.Size
import androidx.annotation.RequiresApi
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.ceil
import kotlin.math.max

/**
 * Grid images use MediaStore's persisted thumbnails instead of reopening every original.
 * Coil still owns size-aware memory caching, lifecycle cancellation, and older-device decoding.
 */
internal class MediaStoreThumbnailFetcher(
    private val uri: Uri,
    private val options: Options,
    private val size: Size,
    private val permits: Semaphore,
) : Fetcher {
    @RequiresApi(29)
    override suspend fun fetch(): FetchResult? = permits.withPermit {
        try {
            val bitmap = coroutineScope {
                val signal = CancellationSignal()
                // Awaiting a child lets cancellation signal the provider even while its blocking
                // call is still running. Rapid scrolling cancels thumbnails that leave the grid.
                val loading = async(Dispatchers.IO) {
                    options.context.contentResolver.loadThumbnail(uri, size, signal)
                }
                try {
                    loading.await()
                } catch (cancelled: CancellationException) {
                    signal.cancel()
                    throw cancelled
                }
            }
            DrawableResult(BitmapDrawable(options.context.resources, bitmap), isSampled = true, dataSource = DataSource.DISK)
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            // Some providers/devices cannot generate a thumbnail. Returning null lets Coil's
            // normal image/video fetcher decode the original at the requested view size.
            null
        }
    }

    class Factory : Fetcher.Factory<Uri> {
        // Avoid launching dozens of video-frame/thumbnail requests during a quick fling.
        private val permits = Semaphore(3)

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (Build.VERSION.SDK_INT < 29 || data.scheme != "content" ||
                (data.authority != MediaStore.AUTHORITY && !DocumentsContract.isDocumentUri(options.context, data)) ||
                options.parameters.value<Boolean>(MEDIASTORE_THUMBNAIL) != true) return null
            val width = (options.size.width as? Dimension.Pixels)?.px ?: return null
            val height = (options.size.height as? Dimension.Pixels)?.px ?: return null
            // loadThumbnail fits inside its bounding box. Ask for enough pixels to cover a
            // cropped tile, otherwise a 160x107 landscape preview cannot satisfy a 160x160
            // FILL request and Coil rereads it on every return. A square bound also covers
            // EXIF rotations, while the long-edge cap limits work on extreme panoramas.
            val ratio = options.parameters.value<Double>(THUMBNAIL_ASPECT_RATIO) ?: 0.0
            val longEdge = if (ratio > 0 && ratio.isFinite()) {
                max(max(width, height).toDouble(), max(height * ratio, width / ratio))
            } else max(width, height) * 2.0
            val bound = ceil(longEdge).toInt().coerceIn(1, 2048)
            return MediaStoreThumbnailFetcher(data, options, Size(bound, bound), permits)
        }
    }
}
