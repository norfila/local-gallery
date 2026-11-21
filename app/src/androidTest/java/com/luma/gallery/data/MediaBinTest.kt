package com.luma.gallery.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses photos this test inserts itself: as their owner it may bin them without Android's consent dialog. */
@RunWith(AndroidJUnit4::class)
class MediaBinTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val suffix = UUID.randomUUID().toString().take(8)
    private val created = mutableListOf<Uri>()

    @After fun clean() {
        created.forEach { runCatching { resolver.delete(it, null, null) } }
    }

    @Test fun binnedItemsAreListedWithOriginalNamesAndRemainingTime() = runBlocking {
        assumeTrue(MediaBin.supported)
        val uri = insertPhoto("bin-$suffix.jpg")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
        val item = MediaBin(context).load().firstOrNull { it.photo.uri == uri.toString() }
        assertNotNull("A binned photo must appear in the bin", item)
        assertEquals("bin-$suffix.jpg", item!!.photo.name)
        val days = (item.expiresAtMillis - System.currentTimeMillis()) / 86_400_000.0
        assertTrue("Android keeps binned items for about 30 days, was $days", days in 25.0..31.0)
        // Restored items leave the bin again.
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
        assertFalse(MediaBin(context).load().any { it.photo.uri == uri.toString() })
    }

    @Test fun permanentDeleteRemovesOwnedLibraryItems() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val uri = insertPhoto("delete-$suffix.jpg")
        val photo = Photo(1, uri.toString(), "delete-$suffix.jpg", "GalleryBin", "external_primary:Pictures/GalleryBin-$suffix/", 0)
        assertEquals(setOf(uri.toString()), MediaBin(context).deletePermanently(listOf(photo)))
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null).use { assertEquals(0, it?.count ?: 0) }
    }

    @Test fun binnedFileNamesLoseTheirTrashPrefix() {
        assertEquals("IMG_1.jpg", binDisplayName(".trashed-1730000000-IMG_1.jpg"))
        assertEquals("plain.jpg", binDisplayName("plain.jpg"))
    }

    private fun insertPhoto(name: String): Uri {
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GalleryBin-$suffix/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        created += uri
        requireNotNull(resolver.openOutputStream(uri)).use { output ->
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF4F7D58.toInt()) }.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}
