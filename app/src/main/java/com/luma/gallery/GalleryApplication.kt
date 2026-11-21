package com.luma.gallery

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import com.luma.gallery.image.MediaStoreThumbnailFetcher

/** One bounded thumbnail loader is shared by every photo/video grid. */
class GalleryApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(MediaStoreThumbnailFetcher.Factory())
            add(VideoFrameDecoder.Factory())
            // Animated images play where the file itself is shown (an opened photo). Android 9+ also plays
            // animated WebP; older versions play GIFs only. Grid thumbnails come from MediaStore and stay still.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
