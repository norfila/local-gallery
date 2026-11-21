package com.luma.gallery.image

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The app's own image loader, as an opened photo uses it: an animated file plays, a still one stays a bitmap. */
@RunWith(AndroidJUnit4::class)
class AnimatedImageTest {
    // Three 8 x 8 frames (red, green, blue), 120 ms each, looping.
    private val gif = "R0lGODlhCAAIAIEAANwoKAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQADAAAACwAAAAACAAIAAAIDwABCBxIsKDBgwgTKkwYEAAh+QQBDAAB" +
        "ACwAAAAACAAIAIEooDwAAAAAAAAAAAAIDwABCBxIsKDBgwgTKkwYEAAh+QQBDAABACwAAAAACAAIAIEoPMgAAAAAAAAAAAAIDwABCBxIsKDBgwgTKkwYEAA7"

    @Test fun aGifLoadsAsAnAnimationAndAJpegAsAStillBitmap() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val animatedFile = File(context.cacheDir, "animated-image-test.gif").apply { writeBytes(Base64.decode(gif, Base64.DEFAULT)) }
        val stillFile = File(context.cacheDir, "animated-image-test.jpg").apply {
            outputStream().use { output ->
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { it.compress(Bitmap.CompressFormat.JPEG, 90, output); it.recycle() }
            }
        }
        try {
            val loader = context.imageLoader
            val animated = loader.execute(ImageRequest.Builder(context).data(animatedFile).build())
            assertTrue("The GIF must load: $animated", animated is SuccessResult)
            assertTrue("A GIF plays instead of showing its first frame: ${animated.drawable}", animated.drawable is Animatable)
            val still = loader.execute(ImageRequest.Builder(context).data(stillFile).build())
            assertTrue("A JPEG stays an ordinary bitmap: ${still.drawable}", still.drawable is BitmapDrawable)
        } finally {
            animatedFile.delete()
            stillFile.delete()
        }
    }
}
