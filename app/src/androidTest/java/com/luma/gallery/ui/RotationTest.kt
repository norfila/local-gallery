package com.luma.gallery.ui

import android.Manifest
import android.app.Activity
import android.app.UiAutomation
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.luma.gallery.GalleryViewModel
import com.luma.gallery.MainActivity
import com.luma.gallery.data.AppPreferences
import com.luma.gallery.data.StartPage
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Turning the phone keeps the open photo; the library can stay upright while photos turn. */
class RotationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val name = "gallery-rotation-${UUID.randomUUID().toString().take(8)}.jpg"
    private var image: Uri? = null

    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val preferences = AppPreferences(context)
            val start = preferences.startPage()
            val rotate = preferences.rotateOnlyInViewer()
            try {
                preferences.setStartPage(StartPage.LIBRARY)
                preferences.setRotateOnlyInViewer(true)
                val automation = instrumentation.uiAutomation
                if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
                else automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
                image = insertImage()
                base.evaluate()
            } finally {
                instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
                instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
                image?.let { context.contentResolver.delete(it, null, null) }
                preferences.setStartPage(start)
                preferences.setRotateOnlyInViewer(rotate)
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixture).around(compose)

    @Test fun openPhotoStaysOpenWhenThePhoneTurnsWithoutRebuildingTheScreen() {
        openPhoto()
        val before: Activity = compose.activity
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        assertViewerShows()
        assertSame("Turning must not rebuild the activity", before, compose.activity)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        assertViewerShows()
    }

    @Test fun libraryStaysUprightWhilePhotosTurnUnlessTheSettingIsOff() {
        if (context.resources.configuration.smallestScreenWidthDp >= 600) return // Tablets always turn freely.
        waitForPhoto()
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, compose.activity.requestedOrientation)
        openPhoto(alreadyListed = true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
        compose.onNodeWithContentDescription("Close photo").performClick()
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, compose.activity.requestedOrientation)
        compose.runOnIdle { ViewModelProvider(compose.activity)[GalleryViewModel::class.java].changeRotateOnlyInViewer(false) }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
    }

    @Test fun openPhotoReturnsAfterTheActivityIsRebuilt() {
        openPhoto()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { viewerShowing() }
        assertViewerShows()
    }

    private fun waitForPhoto() {
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openPhoto(alreadyListed: Boolean = false) {
        if (!alreadyListed) waitForPhoto()
        compose.onNodeWithContentDescription(name).performClick()
        compose.waitUntil(10_000) { viewerShowing() }
    }

    private fun viewerShowing() = compose.onAllNodes(hasTestTag("photo-viewer-image") and hasAnyDescendant(hasContentDescription(name)))
        .fetchSemanticsNodes().isNotEmpty()

    private fun assertViewerShows() = compose.waitUntil(5_000) { viewerShowing() }

    private fun insertImage(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalleryRotationQA/")
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values)!!
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { Canvas(this).drawColor(Color.rgb(70, 120, 90)) }
        try { context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } } finally { bitmap.recycle() }
        return uri
    }
}
