package com.luma.gallery.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HiddenMediaSourceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val authority = "com.luma.gallery.test.hidden"
    private val base = Uri.parse("content://$authority.control")
    private val preferences = AppPreferences(context)
    private var previousEnabled = false
    private var previousRoots = emptyList<String>()
    private var fixtureSeeded = false

    @Before fun seed() {
        fixtureSeeded = false
        previousEnabled = preferences.showHiddenMedia()
        previousRoots = preferences.hiddenMediaFolders()
        preferences.setShowHiddenMedia(false)
        preferences.setHiddenMediaFolders(emptyList())
        seedImage(4)
    }

    @After fun restore() {
        preferences.setShowHiddenMedia(previousEnabled)
        preferences.setHiddenMediaFolders(previousRoots)
        if (fixtureSeeded) resolver.call(base, "revoke", null, null)
    }

    @Test fun walksDotAndNoMediaDirectoriesWithoutDuplicatesOrDatabaseFiles() = runBlocking {
        val photos = HiddenMediaSource(context).loadPhotos(listOf(tree("dot"), tree("root"), tree("root"), tree("denied")), emptyList())
        assertEquals(7, photos.size)
        assertEquals(7, photos.map { it.uri }.distinct().size)
        assertTrue(photos.any { it.folder == ".thumbnails" && it.name == "preview.jpg" })
        assertTrue(photos.any { it.folder == "Private" && it.name == "photo.jpg" })
        assertTrue(photos.any { it.name == ".cover.jpg" })
        assertTrue(photos.any { it.name == "generic.JPG" && it.mimeType == "image/jpeg" })
        assertTrue("Header-only probe recognizes thumbnails without a normal extension", photos.any { it.name == "preview.thumb" && it.mimeType == "image/jpeg" })
        assertEquals(1, photos.count { it.isVideo })
        assertFalse(photos.any { it.name == ".nomedia" || it.name.startsWith(".thumbdata") || it.name == "fake.jpg" })
        if (Build.VERSION.SDK_INT >= 29) assertTrue(photos.all { DocumentsContract.getTreeDocumentId(Uri.parse(it.uri)) == "root" })
        assertTrue(photos.all { it.libraryUri == null })
    }

    @Test fun knownDocumentUriIsNotAddedAgainAndUnavailableGrantDoesNotBlockAnother() = runBlocking {
        val source = HiddenMediaSource(context)
        val initial = source.loadPhotos(listOf(tree("root")), emptyList())
        val indexed = listOf(initial.single { it.name == "visible.jpg" })
        val added = source.loadPhotos(listOf(tree("denied"), tree("root")), indexed)
        assertEquals(6, added.size)
        assertFalse(added.any { it.uri == indexed.single().uri })
        resolver.call(base, "revoke", null, null)
        assertTrue(source.loadPhotos(listOf(tree("root")), indexed).isEmpty())
    }

    @Test fun settingControlsUnindexedSourcesAndRevokedFolderPreservesNormalLibrary() = runBlocking {
        val normal = createIndexedImage()
        try {
            preferences.setHiddenMediaFolders(listOf(tree("root")))
            MediaRepository(context).use { repository ->
                val library = repository.loadPhotos()
                assertTrue(library.any { it.uri == normal.toString() && !it.hidden })
                assertFalse(library.any { Uri.parse(it.uri).authority == authority })
                assertTrue("Nothing hidden is loaded while the Hidden page is off", repository.loadHiddenPhotos(library).isEmpty())
                preferences.setShowHiddenMedia(true)
                val hidden = repository.loadHiddenPhotos(library)
                assertEquals(7, hidden.count { Uri.parse(it.uri).authority == authority })
                assertTrue("Unindexed media is marked for the Hidden page", hidden.all { it.hidden })
                assertFalse(hidden.any { it.uri == normal.toString() })
                assertFalse("The library query never returns picker folders", repository.loadPhotos().any { Uri.parse(it.uri).authority == authority })
                resolver.call(base, "revoke", null, null)
                val revoked = repository.loadPhotos()
                assertTrue(revoked.any { it.uri == normal.toString() })
                assertFalse(repository.loadHiddenPhotos(revoked).any { Uri.parse(it.uri).authority == authority })
                preferences.setShowHiddenMedia(false)
                assertTrue(repository.loadHiddenPhotos(repository.loadPhotos()).isEmpty())
            }
        } finally { resolver.delete(normal, null, null) }
    }

    @Test fun readsDocumentMetadataDimensionsAndCaptureDateWithCacheInvalidation() = runBlocking {
        val photo = HiddenMediaSource(context).loadPhotos(listOf(tree("root")), emptyList()).single { it.name == "preview.jpg" }
        assertEquals(0, photo.width)
        MediaRepository(context).use { repository ->
            repository.clearMetadataCache()
            val metadata = repository.loadMetadata(photo)
            assertEquals(4, metadata.embeddedRating)
            assertEquals("Hidden Test Camera", metadata.camera)
            assertEquals(8, metadata.width)
            assertEquals(12, metadata.height)
            assertTrue(metadata.dateTaken < photo.dateTaken)
            assertTrue(metadata.metadataLoaded)
            if (Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                assertFalse("Library metadata respects its location access setting", metadata.hasLocation)
            }
            val cached = repository.loadMetadata(photo)
            assertEquals(metadata, cached)
            seedImage(2)
            assertEquals(4, repository.loadMetadata(photo).embeddedRating)
            repository.clearMetadataCache()
            assertEquals(2, repository.loadMetadata(photo).embeddedRating)
            seedImage(3)
            assertEquals(3, repository.loadMetadata(photo.copy(dateModified = photo.dateModified + 1)).embeddedRating)
        }
    }

    private fun tree(id: String): String = DocumentsContract.buildTreeDocumentUri(authority, id).toString()

    private fun seedImage(rating: Int) {
        val file = File.createTempFile("hidden-media-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } } finally { bitmap.recycle() }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_MAKE, "Hidden")
                setAttribute(ExifInterface.TAG_MODEL, "Test Camera")
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:01 12:30:00")
                setAttribute(ExifInterface.TAG_XMP, """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="$rating"/></rdf:RDF></x:xmpmeta>""")
                setLatLong(48.137, 11.575)
                saveAttributes()
            }
            resolver.call(base, "seed", context.packageName, Bundle().apply { putByteArray("bytes", file.readBytes()) })
            fixtureSeeded = true
        } finally { file.delete() }
    }

    private fun createIndexedImage(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "hidden-source-normal-${UUID.randomUUID()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalleryHiddenMediaTest/")
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)!!
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try { resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } } finally { bitmap.recycle() }
        return uri
    }
}
