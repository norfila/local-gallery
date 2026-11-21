package com.luma.gallery.ui

import android.Manifest
import android.app.UiAutomation
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import com.luma.gallery.MainActivity
import com.luma.gallery.data.AppPreferences
import com.luma.gallery.data.StartPage
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** On short screens the library header and the floating navigation make room while the photos scroll. */
class ScrollAwayBarsUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefix = "gallery-bars-${UUID.randomUUID().toString().take(8)}"
    private val images = mutableListOf<Uri>()

    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val preferences = AppPreferences(context)
            val start = preferences.startPage()
            val rotate = preferences.rotateOnlyInViewer()
            try {
                preferences.setStartPage(StartPage.LIBRARY)
                preferences.setRotateOnlyInViewer(false)
                val automation = instrumentation.uiAutomation
                if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
                else automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
                repeat(24) { images += insertImage(it) }
                base.evaluate()
            } finally {
                instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
                instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
                images.forEach { context.contentResolver.delete(it, null, null) }
                preferences.setStartPage(start)
                preferences.setRotateOnlyInViewer(rotate)
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixture).around(compose)

    @Test fun tallScreensKeepTheirBarsWhileScrolling() {
        assumeTrue(context.resources.configuration.screenHeightDp >= 480)
        waitForPhotos()
        compose.onNodeWithTag("photo-grid").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("Search must stay", shown("library-search"))
        assertTrue("Navigation must stay", shown("floating-navigation"))
    }

    @Test fun shortScreensHideTheBarsWhileScrollingOnAndBringThemBackOnAScrollBack() {
        turnSideways()
        waitForPhotos()
        assertTrue(shown("library-search") && shown("floating-navigation"))
        compose.onNodeWithTag("photo-grid").performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { !shown("library-search") && !shown("floating-navigation") && !shown("library-context-row") }
        compose.onNodeWithTag("photo-grid").performTouchInput { swipeDown(startY = centerY - 60f, endY = centerY + 60f) }
        compose.waitUntil(5_000) { shown("library-search") && shown("floating-navigation") }
    }

    @Test fun selectingPhotosKeepsTheBarsInPlace() {
        turnSideways()
        waitForPhotos()
        compose.onNodeWithTag("photo-grid").performTouchInput { longClick(center) }
        compose.waitUntil(5_000) { shown("selection-share") }
        compose.onNodeWithTag("photo-grid").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("Selection actions must stay", shown("selection-share"))
        // The selection bar stands in the navigation's place until the selection ends.
        assertTrue("Selection bar must stay", shown("selection-bar"))
        assertFalse("Navigation steps aside while selecting", shown("floating-navigation"))
    }

    private fun turnSideways() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        compose.waitUntil(10_000) { compose.activity.resources.configuration.screenWidthDp > compose.activity.resources.configuration.screenHeightDp }
        compose.waitForIdle()
        assumeTrue("Only short screens hide their bars", compose.activity.resources.configuration.screenHeightDp < 480)
    }

    private fun waitForPhotos() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(prefix, substring = true).fetchSemanticsNodes().size >= 3
        }
        compose.waitForIdle()
    }

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun insertImage(index: Int): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$prefix-$index.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis() - index * 60_000L)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalleryBarsQA/")
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values)!!
        val bitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(40 + index * 8, 110, 160 - index * 4))
        }
        try { context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } } finally { bitmap.recycle() }
        return uri
    }
}
