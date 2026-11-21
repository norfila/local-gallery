package com.luma.gallery.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.decode.DataSource
import coil.imageLoader
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.luma.gallery.GalleryViewModel
import com.luma.gallery.MainActivity
import com.luma.gallery.data.AppPreferences
import com.luma.gallery.data.LibraryStore
import com.luma.gallery.data.PhotoLayout
import com.luma.gallery.data.StartPage
import com.luma.gallery.image.thumbnailRequest
import java.time.Instant
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Real decoded media, rather than fake URIs: covers scrolling and refresh at library scale.
 * Frame timing belongs in a release-build benchmark on a physical device, so this test
 * deliberately checks correctness and cache use without an emulator-specific FPS limit.
 */
@RunWith(AndroidJUnit4::class)
class PhotoGridScrollTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val media = mutableListOf<Uri>()
    private val suffix = UUID.randomUUID().toString().take(8)
    private val prefix = "gallery-scroll-$suffix-"
    private val count = 180
    private lateinit var model: GalleryViewModel

    // Publish before activity launch so all rows are present when the initial scan starts.
    // The outer rule also restores preferences and deletes only this run's own media.
    private val fixtures = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            val preferences = AppPreferences(context)
            val start = preferences.startPage()
            val layout = preferences.photoLayout()
            val columns = preferences.photoColumns()
            val favorites = preferences.favoriteFilters()
            try {
                preferences.setStartPage(StartPage.LIBRARY)
                preferences.setPhotoLayout(PhotoLayout.GRID)
                preferences.setPhotoColumns(3)
                preferences.setFavoriteFilters(emptyList())
                grantMediaPermissions()
                createImages()
                context.imageLoader.memoryCache?.clear()
                base.evaluate()
            } finally {
                if (media.isNotEmpty()) {
                    LibraryStore(context).use { it.setRating(media.first().toString(), null) }
                }
                media.forEach { context.contentResolver.delete(it, null, null) }
                media.clear()
                preferences.setStartPage(start)
                preferences.setPhotoLayout(layout)
                preferences.setPhotoColumns(columns)
                preferences.setFavoriteFilters(favorites)
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixtures).around(compose)

    @Test fun realThumbnailsScrollInBothLayoutsAndARescanPreservesRatingsAndSelection() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[GalleryViewModel::class.java] }
        waitForCompleteScan()
        compose.onNodeWithTag("search-open").performClick()
        compose.onNodeWithTag("search-text").performTextInput(prefix)
        compose.showSearchResults()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("$count PHOTOS").fetchSemanticsNodes().isNotEmpty()
        }

        // The same versioned requests used by tiles must decode and be reusable from memory.
        // Include the rotated, high-resolution JPEG, as well as portrait and landscape images.
        // MediaStore may replace DATE_TAKEN while publishing JPEG metadata. Choose the
        // large fixture by identity rather than relying on the provider's final ordering.
        val largePhoto = model.photos.single { it.name == firstName() }
        val samples = listOf(largePhoto) + model.photos.filter {
            it.name.startsWith(prefix) && it.uri != largePhoto.uri
        }.take(4)
        assertEquals("The large original must be included in thumbnail coverage", media.first().toString(), samples.first().uri)
        assertTrue("The high-resolution original must exercise a file larger than 10 MB: ${samples.first().size} bytes",
            samples.first().size > 10_000_000L)
        runBlocking {
            for (photo in samples) {
                // Match AsyncImagePainter's request adaptation for ContentScale.Crop. A manual
                // size with AUTOMATIC precision would otherwise require exact dimensions.
                val request = thumbnailRequest(context, photo).newBuilder().size(160, 160)
                    .precision(Precision.INEXACT).scale(Scale.FILL).build()
                val decoded = context.imageLoader.execute(request)
                assertTrue("A real MediaStore thumbnail decodes: ${photo.name}", decoded is SuccessResult)
                val warm = context.imageLoader.execute(request)
                assertTrue("A repeated thumbnail request succeeds", warm is SuccessResult)
                val bitmap = ((decoded as SuccessResult).drawable as? BitmapDrawable)?.bitmap
                assertEquals("Crop request 160x160, decoded ${bitmap?.width}x${bitmap?.height}, first source ${decoded.dataSource}: ${photo.name}",
                    DataSource.MEMORY_CACHE, (warm as SuccessResult).dataSource)
                val cached = (warm.drawable as? BitmapDrawable)?.bitmap
                assertTrue("The cached bitmap must cover a 160x160 crop without upscaling: ${cached?.width}x${cached?.height}",
                    cached != null && cached.width >= 160 && cached.height >= 160)
                val changed = thumbnailRequest(context, photo.copy(dateModified = photo.dateModified + 1))
                assertNotEquals("An edited original must have a different thumbnail cache key", request.memoryCacheKey, changed.memoryCacheKey)
            }
        }

        exerciseColdAndWarmScrolling("Grid")
        compose.onNodeWithTag("arrangement-button").performClick()
        compose.onNodeWithTag("settings-layout-masonry").performClick()
        androidx.test.espresso.Espresso.pressBack()
        exerciseColdAndWarmScrolling("Masonry")

        val firstUri = media.first().toString()
        val original = model.photos.first { it.uri == firstUri }
        assertEquals(0, original.embeddedRating)
        writeMetadata(media.first(), rating = 5, rotated = true)
        compose.runOnIdle { model.rescan() }
        // A rescan picks up the rating written into the file; there is no separate app rating any more.
        compose.waitUntil(90_000) {
            !model.indexing && model.photos.firstOrNull { it.uri == firstUri }?.let {
                it.metadataLoaded && it.embeddedRating == 5 && it.rating == 5
            } == true && model.photos.count { it.name.startsWith(prefix) && it.metadataLoaded } == count
        }
        assertEquals("A full metadata refresh must retain every fixture", count, model.photos.count { it.name.startsWith(prefix) })
        compose.onNodeWithText("$count PHOTOS").assertExists()
        scrollTo(firstName())
        compose.onNodeWithContentDescription(firstName()).performClick()
        waitForViewer(firstName())
        compose.onNodeWithTag("viewer-details-handle").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("details-rating").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("5 / 5 · stored in the file").assertExists()
        compose.onNodeWithContentDescription("Close details").performClick()
        compose.onNodeWithContentDescription("Close photo").performClick()
    }

    private fun exerciseColdAndWarmScrolling(layout: String) {
        val ordered = model.photos.filter { it.name.startsWith(prefix) }.sortedByDescending { it.dateTaken }
        val startName = ordered.first().name
        val endName = ordered.last().name
        compose.onNodeWithTag("photo-grid").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "$layout, 3 columns"))
        scrollTo(startName)
        repeat(6) { compose.onNodeWithTag("photo-grid").performTouchInput { swipeUp() } }
        compose.onNodeWithContentDescription(startName).assertDoesNotExist()
        scrollTo(endName)
        compose.onNodeWithContentDescription(endName).performClick()
        waitForViewer(endName)
        compose.onNodeWithContentDescription("Close photo").performClick()

        // Revisit the same pages with populated thumbnail caches.
        scrollTo(startName)
        repeat(5) { compose.onNodeWithTag("photo-grid").performTouchInput { swipeUp() } }
        repeat(3) { compose.onNodeWithTag("photo-grid").performTouchInput { swipeDown() } }
        scrollTo(startName)
        compose.onNodeWithContentDescription(startName).performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithTag("photo-grid").performTouchInput { swipeUp() }
        compose.onNodeWithText("1 selected").assertExists()
        compose.runOnIdle { assertEquals(1, model.photos.count { it.uri == media.first().toString() }) }
        compose.onNodeWithContentDescription("Cancel selection").performClick()
    }

    private fun scrollTo(name: String) {
        compose.onNodeWithTag("photo-grid").performScrollToNode(hasContentDescription(name))
        compose.onNodeWithContentDescription(name).assertIsDisplayed()
    }

    private fun waitForViewer(name: String) {
        try {
            // Model indexing and the background list transformation can finish before the
            // next viewer composition. Wait for the viewer's image of this item instead of sleeping.
            compose.waitUntil(20_000) {
                compose.onAllNodes(hasTestTag("photo-viewer-image") and hasAnyDescendant(hasContentDescription(name)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Exception) {
            runCatching {
                val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
                // The viewer is its own window, so there can be several roots; record each of them.
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                File(directory, "scroll-viewer-timeout-$suffix.txt").writeText(roots.printToString(maxDepth = 16))
                roots.fetchSemanticsNodes().indices.forEach { index ->
                    File(directory, "scroll-viewer-timeout-$suffix-$index.png").outputStream().use {
                        roots[index].captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                Log.e("GalleryScrollTest", "Viewer did not open for $name. Diagnostics: ${directory.absolutePath}", failure)
            }.onFailure { Log.e("GalleryScrollTest", "Could not capture viewer timeout diagnostics", it) }
            throw failure
        }
        compose.onNode(hasTestTag("photo-viewer-image") and hasAnyDescendant(hasContentDescription(name))).assertExists()
    }

    private fun waitForCompleteScan() {
        compose.waitUntil(90_000) {
            !model.indexing && model.photos.count { it.name.startsWith(prefix) && it.metadataLoaded } == count
        }
    }

    private fun firstName() = "${prefix}000.jpg"

    private fun grantMediaPermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
            automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
        } else automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    private fun createImages() {
        // Use the concrete volume queried by MediaRepository. The aggregate "external"
        // collection may return a different URI for the same underlying MediaStore row.
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val landscape = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val portrait = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val date = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
        try {
            repeat(count) { index ->
                val bitmap = if (index % 2 == 0) landscape else portrait
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$prefix${index.toString().padStart(3, '0')}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalleryScrollQA-$suffix/")
                    put(MediaStore.Images.Media.DATE_TAKEN, date - index * 6L * 60 * 60 * 1000)
                    put(MediaStore.Images.Media.WIDTH, if (index == 0) 4096 else bitmap.width)
                    put(MediaStore.Images.Media.HEIGHT, if (index == 0) 3072 else bitmap.height)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = requireNotNull(context.contentResolver.insert(collection, values))
                media += uri
                writePixels(uri, index, bitmap, paint)
                writeMetadata(uri, index % 6, rotated = index == 0)
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
        } finally {
            landscape.recycle()
            portrait.recycle()
        }
    }

    private fun writePixels(uri: Uri, index: Int, reusable: Bitmap, paint: Paint) {
        val bitmap = if (index == 0) largeTexturedBitmap() else reusable
        try {
            if (index != 0) {
                val canvas = Canvas(bitmap)
                val hue = (index * 29 % 360).toFloat()
                paint.shader = LinearGradient(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(),
                    Color.HSVToColor(floatArrayOf(hue, .45f, .95f)),
                    Color.HSVToColor(floatArrayOf((hue + 70) % 360, .75f, .35f)), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
                paint.shader = null
                paint.color = Color.WHITE
                paint.textSize = 120f
                canvas.drawText(index.toString(), 75f, 200f, paint)
            }
            requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, if (index == 0) 100 else 85, it))
            }
        } finally {
            // The only large allocation is released before EXIF work and activity launch.
            if (index == 0) bitmap.recycle()
        }
    }

    private fun largeTexturedBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(4096, 3072, Bitmap.Config.ARGB_8888)
        // Deterministic texture resists JPEG compression. Only one 16 KiB pixel row is
        // allocated alongside the 48 MiB bitmap, rather than another full image array.
        val row = IntArray(bitmap.width)
        var random = 0x51A7C0DE
        for (y in 0 until bitmap.height) {
            for (x in row.indices) {
                random = random xor (random shl 13)
                random = random xor (random ushr 17)
                random = random xor (random shl 5)
                row[x] = 0xFF000000.toInt() or (random and 0x00FFFFFF)
            }
            bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
        return bitmap
    }

    private fun writeMetadata(uri: Uri, rating: Int, rotated: Boolean) {
        requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw")).use { descriptor ->
            ExifInterface(descriptor.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_XMP, """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="$rating"/></rdf:RDF></x:xmpmeta>""")
                setAttribute(ExifInterface.TAG_MAKE, "Gallery")
                setAttribute(ExifInterface.TAG_MODEL, "Scroll fixture")
                setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
                setAttribute(ExifInterface.TAG_ORIENTATION, (if (rotated) ExifInterface.ORIENTATION_ROTATE_90 else ExifInterface.ORIENTATION_NORMAL).toString())
                saveAttributes()
            }
        }
    }
}
