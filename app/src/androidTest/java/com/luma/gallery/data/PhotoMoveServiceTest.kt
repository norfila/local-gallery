package com.luma.gallery.data

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoMoveServiceTest {
    @Test fun moveRetainsUriAndOriginalBytesAndDoesNotOverwriteSameNamedFile() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val directory = "Pictures/GalleryMoveTest-${UUID.randomUUID()}/"
        val created = mutableListOf<Uri>()
        fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(color)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
                bitmap.recycle()
            }
            output.toByteArray()
        }
        fun create(path: String, bytes: ByteArray): Uri {
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "same-name.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, path)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            created += uri
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        try {
            val sourceBytes = jpeg(android.graphics.Color.RED)
            val existingBytes = jpeg(android.graphics.Color.BLUE)
            val source = create("${directory}Source/", sourceBytes)
            val existing = create("${directory}Target/", existingBytes)
            val photo = Photo(1, source.toString(), "same-name.jpg", "Source", "external_primary:${directory}Source/", 0)
            val service = PhotoMoveService(context)

            assertEquals(PhotoMoveResult.Moved, service.move(photo, "${directory}Target/"))
            val location = service.location(photo)
            assertEquals("${directory}Target/", location.relativePath)
            assertNotEquals("same-name.jpg", location.displayName)
            assertArrayEquals(sourceBytes, resolver.openInputStream(source)!!.use { it.readBytes() })
            assertArrayEquals(existingBytes, resolver.openInputStream(existing)!!.use { it.readBytes() })
            assertEquals(PhotoMoveResult.Moved, service.move(photo, "${directory}Target/"))
            assertArrayEquals(sourceBytes, resolver.openInputStream(source)!!.use { it.readBytes() })
        } finally {
            created.forEach { resolver.delete(it, null, null) }
        }
    }

    /** Android itself, not only the path rules, must accept a video beside a photo in Pictures. */
    @Test fun aPhotoAndAVideoMoveTogetherIntoOneNewPicturesFolder() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val name = "GalleryMixedMove-${UUID.randomUUID()}"
        val created = mutableListOf<Uri>()
        try {
            val clip = createVideoFixture(context).also { created += it }
            val video = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, android.content.ContentUris.parseId(clip))
            val videoBytes = resolver.openInputStream(video)!!.use { it.readBytes() }
            val image = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$name/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })).also { created += it }
            resolver.openOutputStream(image)!!.use { output ->
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { it.compress(Bitmap.CompressFormat.JPEG, 92, output); it.recycle() }
            }
            resolver.update(image, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val items = listOf(
                Photo(1, image.toString(), "$name.jpg", name, "external_primary:DCIM/$name/", 0),
                Photo(2, video.toString(), "clip.mp4", "GalleryVideoQA", "external_primary:Movies/GalleryVideoQA/", 0, mimeType = "video/mp4"),
            )
            val service = PhotoMoveService(context)
            val target = PhotoMovePaths.newFolder(name, items)
            assertEquals("Pictures/$name/", target)

            assertEquals(PhotoBatchMoveResult.Done(moved = 2, failed = 0, firstFailure = null), service.moveAll(items, target!!))
            items.forEach { assertEquals(target, service.location(it).relativePath) }
            assertArrayEquals(videoBytes, resolver.openInputStream(video)!!.use { it.readBytes() })
            // The other way round stays closed: a photo is not sent to Movies, and the video alone still is.
            assertEquals(PhotoBatchMoveResult.Done(moved = 1, failed = 1, firstFailure = com.luma.gallery.R.string.move_incompatible_folder),
                service.moveAll(items, "Movies/$name/"))
            assertEquals(target, service.location(items[0]).relativePath)
            assertEquals("Movies/$name/", service.location(items[1]).relativePath)
        } finally {
            created.forEach { resolver.delete(it, null, null) }
        }
    }

    @Test fun invalidDestinationIsRejectedBeforeProviderAccess() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Validation happens before accessing the URI; no user media is needed for this boundary check.
        val photo = Photo(1, "content://media/external_primary/images/media/0", "fixture.jpg", "Source", "external_primary:Pictures/Source/", 0)
        assertTrue(PhotoMoveService(context).move(photo, "Pictures/../DCIM/") is PhotoMoveResult.Failure)
    }
}
