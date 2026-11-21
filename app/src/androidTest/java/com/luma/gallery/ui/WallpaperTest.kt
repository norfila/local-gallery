package com.luma.gallery.ui

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.luma.gallery.data.Photo
import com.luma.gallery.data.ThemeMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "Set as wallpaper" is offered for photos only, and the built-in fallback really sets it. */
class WallpaperTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun viewerOffersWallpaperForPhotosButNotForVideos() {
        val file = sourceFile()
        try {
            val photo = Photo(1, Uri.fromFile(file).toString(), file.name, "Test", "Test/", 1_700_000_000_000)
            val video = photo.copy(id = 2, uri = "content://com.luma.gallery.test/none.mp4", name = "clip.mp4", mimeType = "video/mp4")
            compose.setContent { GalleryTheme(ThemeMode.SYSTEM) { PhotoViewer(listOf(photo, video), photo.uri, {}, {}) } }
            compose.onNodeWithTag("viewer-more").performClick()
            compose.onNodeWithTag("viewer-menu-wallpaper").assertExists()
            compose.onNodeWithTag("viewer-menu-use-as").assertExists()
        } finally { file.delete() }
    }

    @Test fun directWallpaperIsSetFromThePhoto() = runBlocking {
        val file = sourceFile()
        val manager = WallpaperManager.getInstance(context)
        try {
            val before = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            val photo = Photo(1, Uri.fromFile(file).toString(), file.name, "Test", "Test/", 1_700_000_000_000)
            assertTrue(applyWallpaper(context, photo, WallpaperManager.FLAG_SYSTEM))
            assertNotEquals(before, manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM))
        } finally {
            runCatching { manager.clear(WallpaperManager.FLAG_SYSTEM) }
            file.delete()
        }
    }

    private fun sourceFile(): File {
        val file = File.createTempFile("gallery-wallpaper-", ".jpg", context.cacheDir)
        Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawColor(Color.rgb(46, 87, 61))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
        }
        return file
    }
}
