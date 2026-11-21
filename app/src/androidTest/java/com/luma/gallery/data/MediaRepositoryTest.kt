package com.luma.gallery.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaRepositoryTest {
    @Test fun discoversNestedFoldersAndReadsEmbeddedMetadataWithCacheInvalidation() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val runId = UUID.randomUUID().toString()
        val rootPath = "Pictures/LumaTest-$runId/"
        val parent = createImage(context, rootPath, "parent.jpg", 4)
        val child = createImage(context, "${rootPath}Day1/", "child.jpg", 5)
        try {
            val repository = MediaRepository(context)
            val photos = repository.loadPhotos()
            val photo = photos.firstOrNull { it.name == "parent.jpg" && it.folderKey.endsWith(rootPath) }
            assertNotNull("Own MediaStore image should be discoverable", photo)
            assertTrue(photos.any { it.name == "child.jpg" && it.folderKey.endsWith("${rootPath}Day1/") })
            val metadata = repository.loadMetadata(photo!!)
            assertEquals(4, metadata.embeddedRating)
            assertEquals(4, metadata.rating)
            assertEquals("Luma Test Camera", metadata.camera)
            assertEquals("Test Lens", metadata.lens)
            assertEquals(400, metadata.iso)
            assertTrue(metadata.metadataLoaded)
            val locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (locationAllowed) {
                assertEquals(48.137, metadata.latitude!!, 0.0001)
                assertEquals(11.575, metadata.longitude!!, 0.0001)
            } else {
                assertFalse(metadata.hasLocation)
            }
            writeMetadata(context, parent, 2)
            // The same fingerprint reads the existing cache; a changed fingerprint reparses.
            assertEquals(4, repository.loadMetadata(photo).embeddedRating)
            // Settings' full scan must recover edits even when an editor preserves that fingerprint.
            repository.clearMetadataCache()
            assertEquals(2, repository.loadMetadata(photo).embeddedRating)
            writeMetadata(context, parent, 3)
            assertEquals(2, repository.loadMetadata(photo).embeddedRating)
            assertEquals(3, repository.loadMetadata(photo.copy(dateModified = photo.dateModified + 1)).embeddedRating)
            assertEquals(parent.lastPathSegment, android.net.Uri.parse(photo.uri).lastPathSegment)
            assertTrue(photo.folderKey.endsWith("$rootPath"))
            repository.close()
        } finally {
            resolver.delete(parent, null, null)
            resolver.delete(child, null, null)
        }
    }

    private fun createImage(context: Context, path: String, name: String, rating: Int): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try {
                context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } finally { bitmap.recycle() }
            writeMetadata(context, uri, rating)
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun writeMetadata(context: Context, uri: android.net.Uri, rating: Int) {
        context.contentResolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_XMP, """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="$rating"/></rdf:RDF></x:xmpmeta>""")
                setAttribute(ExifInterface.TAG_MAKE, "Luma")
                setAttribute(ExifInterface.TAG_MODEL, "Test Camera")
                setAttribute(ExifInterface.TAG_LENS_MODEL, "Test Lens")
                setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
                setLatLong(48.137, 11.575)
                saveAttributes()
            }
        }
    }
}
