package com.luma.gallery.data

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalPhotoReaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun acceptsOrdinaryPhotoIntentsAndRejectsUnsupportedActionsAndUriSchemes() {
        val uri = Uri.parse("content://test.photos/one")
        assertNotNull(ExternalPhotoContract.request(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")))
        assertNotNull(ExternalPhotoContract.request(Intent(ExternalPhotoContract.REVIEW_ACTION).setDataAndType(uri, "image/jpeg")))
        assertNull(ExternalPhotoContract.request(Intent("android.provider.action.REVIEW_SECURE").setDataAndType(uri, "image/jpeg")))
        assertNull(ExternalPhotoContract.request(Intent(Intent.ACTION_SEND).setDataAndType(uri, "image/jpeg")))
        assertNull(ExternalPhotoContract.request(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.org/photo.jpg"), "image/jpeg")))
        assertNull(ExternalPhotoContract.request(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/photo.jpg"), "image/jpeg")))
        assertNull(ExternalPhotoContract.request(Intent(Intent.ACTION_VIEW)))
    }

    @Test fun readsSinglePhotoAndMetadataWithoutRequiringLibraryQueryOrPersistingAccess() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val uri = insertImage()
        try {
            val photo = ExternalPhotoReader(context).read(ExternalPhotoRequest(uri, "image/*"))
            assertNotNull(photo)
            assertEquals(uri.toString(), photo!!.uri)
            assertEquals("external:", photo.folderKey)
            assertEquals(20, photo.width)
            assertEquals(30, photo.height)
            assertEquals("image/jpeg", photo.mimeType)
            assertEquals(4, photo.rating)
            assertEquals(4, photo.embeddedRating)
            assertEquals("Gallery External Test", photo.camera)
            assertTrue(photo.metadataLoaded)
            assertTrue(photo.id < 0)
            assertTrue(photo.size > 0)
            assertTrue(photo.name.startsWith("external-"))
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun corruptPhotoAndUnreadableUriFailWithoutCrashing() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val uri = insertImage()
        try {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write("This is not an image".toByteArray()) }
            val reader = ExternalPhotoReader(context)
            assertNull(reader.read(ExternalPhotoRequest(uri, "image/jpeg")))
            assertNull(reader.read(ExternalPhotoRequest(Uri.parse("content://missing.photo.provider/one"), "image/jpeg")))
            assertNull(reader.read(ExternalPhotoRequest(Uri.parse("https://example.org/photo.jpg"), "image/jpeg")))
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun aggregateMediaStoreAliasKeepsGrantedUriAndMatchesLibraryIdentity() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val inserted = insertImage()
        try {
            val alias = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(inserted))
            val opened = ExternalPhotoReader(context).read(ExternalPhotoRequest(alias, "image/jpeg"))!!
            val indexed = MediaRepository(context).use { repository ->
                repository.loadPhotos().single { it.name == opened.name }
            }
            assertEquals("Stream access must continue using the caller's actual granted URI", alias.toString(), opened.uri)
            assertNotEquals("The aggregate alias differs from the per-volume library URI", alias.toString(), indexed.uri)
            assertEquals("Ratings and album membership can use the repository's canonical identity", indexed.uri, opened.libraryUri)
        } finally {
            context.contentResolver.delete(inserted, null, null)
        }
    }

    @Test fun nonMediaStoreProviderRemainsTransientWithoutLibraryIdentity() = runBlocking {
        seedMetadataFixture(context)
        val opened = ExternalPhotoReader(context).read(ExternalPhotoRequest(metadataFixtureUri, "image/jpeg"))!!
        assertEquals(metadataFixtureUri.toString(), opened.uri)
        assertNull(opened.libraryUri)
    }

    private fun insertImage(): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "external-${UUID.randomUUID()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalleryExternalTest/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
            try {
                resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } finally { bitmap.recycle() }
            resolver.openFileDescriptor(uri, "rw")!!.use {
                ExifInterface(it.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_MAKE, "Gallery")
                    setAttribute(ExifInterface.TAG_MODEL, "External Test")
                    setAttribute(ExifInterface.TAG_XMP, """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="4"/></rdf:RDF></x:xmpmeta>""")
                    saveAttributes()
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }
}
