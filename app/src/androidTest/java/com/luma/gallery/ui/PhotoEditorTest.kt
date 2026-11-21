package com.luma.gallery.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.luma.gallery.data.Photo
import com.luma.gallery.data.ThemeMode
import com.luma.gallery.image.centeredCrop
import com.luma.gallery.image.EditGeometry
import com.luma.gallery.image.InkPoint
import com.luma.gallery.image.InkStroke
import com.luma.gallery.image.PhotoEdits
import com.luma.gallery.image.loadEditingBitmap
import com.luma.gallery.image.renderPhotoEdits
import com.luma.gallery.image.saveEditedCopy
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PhotoEditorTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun penStaysOnItsSourcePixelsWhenRotatedAndCenterCropped() {
        val source = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val ink = InkStroke(listOf(InkPoint(.25f, .25f), InkPoint(.25f, .75f)), Color.RED, .05f)
        val rotated = renderPhotoEdits(source, PhotoEdits(quarterTurns = 1, strokes = listOf(ink)))
        assertEquals(200, rotated.width)
        assertEquals(300, rotated.height)
        assertEquals(Color.RED, rotated.getPixel(100, 75))
        assertEquals(Color.BLUE, rotated.getPixel(100, 225))
        val crop = renderPhotoEdits(source, PhotoEdits(quarterTurns = 1, crop = centeredCrop(1f, 200f, 300f), strokes = listOf(ink)))
        assertEquals(200, crop.width)
        assertEquals(200, crop.height)
        assertEquals(Color.RED, crop.getPixel(100, 25))
        assertEquals(Color.BLUE, source.getPixel(75, 100))
        val geometry = EditGeometry(300, 200, PhotoEdits(quarterTurns = 1, crop = centeredCrop(1f, 200f, 300f)))
        val viewport = geometry.viewport(500f, 300f)
        val mapped = geometry.sourcePoint(viewport.centerX(), viewport.top + viewport.height() * .125f, viewport)
        assertEquals(.25f, mapped.x, .001f)
        assertEquals(.5f, mapped.y, .001f)
        rotated.recycle(); crop.recycle(); source.recycle()
    }

    @Test fun largeExifRotatedJpegIsOrientedAndBoundedWithoutChangingSource() = runBlocking {
        val original = sourceFile(5000, 1000, rotated = true)
        try {
            val before = original.readBytes()
            val bitmap = loadEditingBitmap(context, Uri.fromFile(original))
            assertTrue("EXIF rotation is applied", bitmap.height > bitmap.width)
            assertTrue("Working image is bounded", max(bitmap.width, bitmap.height) <= 4096)
            assertArrayEquals(before, original.readBytes())
            bitmap.recycle()
        } finally { original.delete() }
    }

    @Test fun savePublishesSeparateJpegWithCropInkAndCaptureMetadata() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val original = sourceFile(300, 200)
        var saved: Uri? = null
        try {
            val before = original.readBytes()
            val photo = photo(original)
            val bitmap = loadEditingBitmap(context, Uri.parse(photo.uri))
            saved = saveEditedCopy(context, photo, bitmap, PhotoEdits(crop = centeredCrop(1f, 300f, 200f),
                strokes = listOf(InkStroke(listOf(InkPoint(.5f, .5f)), Color.RED, .2f))))
            assertNotEquals(Uri.parse(photo.uri), saved)
            context.contentResolver.query(saved!!, arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.MIME_TYPE), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals("Pictures/Gallery/", cursor.getString(1))
                assertEquals("image/jpeg", cursor.getString(2))
            }
            val decoded = context.contentResolver.openInputStream(saved!!).use { BitmapFactory.decodeStream(it) }!!
            assertEquals(200, decoded.width)
            assertEquals(200, decoded.height)
            val center = decoded.getPixel(100, 100)
            assertTrue(Color.red(center) > 220 && Color.blue(center) < 30)
            context.contentResolver.openInputStream(saved!!).use { stream ->
                val exif = ExifInterface(stream!!)
                assertEquals("Editor test camera", exif.getAttribute(ExifInterface.TAG_MODEL))
                assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            }
            assertArrayEquals(before, original.readBytes())
            decoded.recycle(); bitmap.recycle()
        } finally {
            saved?.let { context.contentResolver.delete(it, null, null) }
            original.delete()
        }
    }

    @Test fun originalUriIsNeverAcceptedAsAnExportTarget() = runBlocking {
        val original = sourceFile(100, 100)
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            val before = original.readBytes()
            var rejected = false
            try { saveEditedCopy(context, photo(original), bitmap, PhotoEdits(), Uri.fromFile(original)) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertArrayEquals(before, original.readBytes())
        } finally { bitmap.recycle(); original.delete() }
    }

    @Test fun aliasOfOriginalFileIsRejectedBeforeTruncationOrCleanup() = runBlocking {
        val original = sourceFile(100, 100)
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            val before = original.readBytes()
            val alias = Uri.fromFile(original).buildUpon().fragment("different-uri-same-file").build()
            assertNotEquals(Uri.fromFile(original), alias)
            var rejected = false
            try { saveEditedCopy(context, photo(original), bitmap, PhotoEdits(), alias) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertTrue(original.exists())
            assertArrayEquals(before, original.readBytes())
        } finally { bitmap.recycle(); original.delete() }
    }

    @Test fun drawingUndoRotationAndSaveCopyWorkInTheEditor() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val original = sourceFile(300, 200)
        val before = original.readBytes()
        val saved = AtomicReference<Uri?>(null)
        try {
            compose.setContent { GalleryTheme(ThemeMode.SYSTEM) { PhotoEditor(photo(original), {}, { saved.set(it) }) } }
            compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "editor-canvas")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("editor-canvas").performTouchInput {
                swipe(center - Offset(width * .1f, 0f), center + Offset(width * .1f, 0f), durationMillis = 250)
            }
            compose.onNodeWithTag("editor-canvas").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 pen strokes, 0 degrees, not cropped"))
            compose.onNodeWithTag("editor-undo").performClick()
            compose.onNodeWithTag("editor-canvas").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0 pen strokes, 0 degrees, not cropped"))
            compose.onNodeWithTag("editor-rotate").performClick()
            compose.onNodeWithTag("editor-tool-crop").performClick()
            compose.onNodeWithTag("editor-ratio-SQUARE").performClick()
            compose.onNodeWithTag("editor-canvas").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0 pen strokes, 90 degrees, cropped to 200 × 200 px"))
            captureEditor()
            compose.onNodeWithTag("editor-save-copy").assertIsEnabled().performClick()
            compose.waitUntil(15_000) { saved.get() != null }
            assertArrayEquals(before, original.readBytes())
            assertFalse("Original stays present", !original.exists())
        } finally {
            saved.get()?.let { context.contentResolver.delete(it, null, null) }
            original.delete()
        }
    }

    @Test fun cropFrameIsDraggedFreelyLockedToARatioAndReset() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val original = sourceFile(300, 200)
        val saved = AtomicReference<Uri?>(null)
        try {
            compose.setContent { GalleryTheme(ThemeMode.SYSTEM) { PhotoEditor(photo(original), {}, { saved.set(it) }) } }
            compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "editor-canvas")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("editor-tool-crop").performClick()
            // Drag the top-left corner 30 % inward: 300 × 200 becomes 210 × 140.
            compose.onNodeWithTag("editor-canvas").performTouchInput {
                val viewport = EditGeometry(300, 200, PhotoEdits()).viewport(width.toFloat(), height.toFloat())
                val corner = Offset(viewport.left + 2f, viewport.top + 2f)
                swipe(corner, corner + Offset(viewport.width() * .3f, viewport.height() * .3f), durationMillis = 300)
            }
            compose.onNodeWithTag("editor-canvas").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0 pen strokes, 0 degrees, cropped to 210 × 140 px"))
            captureEditor()
            compose.onNodeWithTag("editor-ratio-SQUARE").performClick()
            compose.onNodeWithTag("editor-canvas").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0 pen strokes, 0 degrees, cropped to 200 × 200 px"))
            compose.onNodeWithTag("editor-crop-reset").performClick()
            compose.onNodeWithTag("editor-canvas").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0 pen strokes, 0 degrees, not cropped"))
            // Undo brings the free crop back, and the saved copy has exactly that size.
            compose.onNodeWithTag("editor-undo").performClick()
            compose.onNodeWithTag("editor-undo").performClick()
            compose.onNodeWithTag("editor-canvas").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0 pen strokes, 0 degrees, cropped to 210 × 140 px"))
            compose.onNodeWithTag("editor-save-copy").assertIsEnabled().performClick()
            compose.waitUntil(15_000) { saved.get() != null }
            val decoded = context.contentResolver.openInputStream(saved.get()!!).use { BitmapFactory.decodeStream(it) }!!
            assertEquals(210, decoded.width)
            assertEquals(140, decoded.height)
            decoded.recycle()
        } finally {
            saved.get()?.let { context.contentResolver.delete(it, null, null) }
            original.delete()
        }
    }

    @Test fun closingWithUnsavedEditsAsksFirstAndBackDoesNotLoseThem() {
        val original = sourceFile(300, 200)
        val dismissed = AtomicReference(false)
        try {
            compose.setContent { GalleryTheme(ThemeMode.SYSTEM) { PhotoEditor(photo(original), { dismissed.set(true) }, {}) } }
            compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "editor-canvas")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("editor-canvas").performTouchInput {
                swipe(center - Offset(width * .1f, 0f), center + Offset(width * .1f, 0f), durationMillis = 250)
            }
            compose.onNodeWithTag("editor-close").performClick()
            compose.onNodeWithTag("editor-discard-dialog").assertExists()
            compose.onNodeWithTag("editor-keep").performClick()
            compose.onNodeWithTag("editor-discard-dialog").assertDoesNotExist()
            compose.onNodeWithTag("editor-canvas").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 pen strokes, 0 degrees, not cropped"))
            assertFalse(dismissed.get())
            // The system back gesture asks too, instead of silently dropping the drawing.
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithTag("editor-discard-dialog").assertExists()
            compose.onNodeWithTag("editor-discard").performClick()
            compose.waitForIdle()
            assertTrue(dismissed.get())
        } finally { original.delete() }
    }

    @Test fun closingWithoutEditsDoesNotAsk() {
        val original = sourceFile(300, 200)
        val dismissed = AtomicReference(false)
        try {
            compose.setContent { GalleryTheme(ThemeMode.SYSTEM) { PhotoEditor(photo(original), { dismissed.set(true) }, {}) } }
            compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "editor-canvas")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("editor-close").performClick()
            compose.waitForIdle()
            assertTrue(dismissed.get())
            compose.onNodeWithTag("editor-discard-dialog").assertDoesNotExist()
        } finally { original.delete() }
    }

    private fun photo(file: File) = Photo(123L, Uri.fromFile(file).toString(), file.name, "Test", "Test/", 1_700_000_000_000)

    private fun captureEditor() {
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val output = File(context.getExternalFilesDir(null), "qa/photo-editor-${if (dark) "dark" else "light"}.png")
        output.parentFile!!.mkdirs()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { output.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screenshot.recycle() }
    }

    private fun sourceFile(width: Int, height: Int, rotated: Boolean = false): File {
        val file = File.createTempFile("gallery-editor-", ".jpg", context.cacheDir)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawColor(Color.BLUE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
        }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MODEL, "Editor test camera")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:30 12:00:00")
            if (rotated) setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return file
    }
}
