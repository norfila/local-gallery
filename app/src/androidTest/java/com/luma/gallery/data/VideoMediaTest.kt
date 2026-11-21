package com.luma.gallery.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoMediaTest {
    @Test fun indexesAndReadsRealVideoIncludingRotationDurationAndCanonicalIdentity() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = createVideoFixture(context)
        try {
            val name = context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)!!.use { cursor -> cursor.moveToFirst(); cursor.getString(0) }
            MediaRepository(context).use { repository ->
                val media = repository.loadPhotos()
                assertEquals("All volume and media collection IDs must be distinct", media.size, media.map { it.id }.toSet().size)
                val photo = media.single { it.name == name }
                assertTrue(photo.isVideo)
                assertTrue(photo.uri.contains("/video/media/"))
                assertTrue(photo.folderKey.endsWith("Movies/GalleryVideoQA/"))
                val metadata = repository.loadMetadata(photo)
                assertTrue(metadata.metadataLoaded)
                assertEquals(48, metadata.width)
                assertEquals(64, metadata.height)
                assertTrue("Duration must come from the actual clip", metadata.durationMillis in 4_000..6_000)
                val cached = repository.loadMetadata(photo)
                assertEquals(metadata.width, cached.width)
                assertEquals(metadata.height, cached.height)
                assertEquals(metadata.durationMillis, cached.durationMillis)
                val alias = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(uri))
                val opened = ExternalPhotoReader(context).read(ExternalPhotoRequest(alias, "video/*"))!!
                assertTrue(opened.isVideo)
                assertEquals(alias.toString(), opened.uri)
                assertEquals(photo.uri, opened.libraryUri)
                assertEquals(metadata.durationMillis, opened.durationMillis)
                val details = PhotoMetadataDetailsReader.load(context, metadata)
                assertNull(details.notice)
                assertTrue(details.categories.single { it.section == MetadataSection.IMAGE }.entries.any { it.label == "Duration" })
            }
        } finally { context.contentResolver.delete(uri, null, null) }
    }
}
