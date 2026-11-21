package com.luma.gallery.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.luma.gallery.MainActivity
import com.luma.gallery.data.ExternalPhotoContract
import com.luma.gallery.data.metadataFixtureUri
import com.luma.gallery.data.seedMetadataFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run this class after revoking photo/location permissions from adb, before instrumentation starts.
 * Runtime revocation inside a test kills the instrumentation process. Already-authorized suites
 * skip these cases, leaving their permission state untouched.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ExternalPhotoUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun standardViewShowsPhotoAndMetadataWithoutLibraryAccess() = verifyExternalViewer(Intent.ACTION_VIEW)

    @Test fun cameraReviewShowsPhotoAndMetadataWithoutLibraryAccess() = verifyExternalViewer(ExternalPhotoContract.REVIEW_ACTION)

    private fun verifyExternalViewer(action: String) {
        assumeTrue("Run this class separately with photo and location permissions denied before instrumentation starts", permissions().none(::granted))
        seedMetadataFixture(context)
        // Start from a new install's state: the Hidden page is off.
        val preferences = com.luma.gallery.data.AppPreferences(context)
        val previousHidden = preferences.showHiddenMedia()
        preferences.setShowHiddenMedia(false)
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            setDataAndType(metadataFixtureUri, "image/jpeg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            finishMainActivities()
            instrumentation.startActivitySync(intent)
            instrumentation.waitForIdleSync()
            waitForViewer()
            assertPermissionsRemainDenied()
            compose.onNodeWithTag("viewer-more").performClick()
            compose.onNodeWithTag("viewer-menu-details").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("photo-details-list").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Location").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("photo-details-list").performScrollToNode(hasContentDescription("Expand Location"))
            compose.onNodeWithContentDescription("Expand Location").performClick()
            compose.onNodeWithTag("photo-details-list").performScrollToNode(hasText("Coordinates"))
            compose.onNodeWithText("Coordinates").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close details").performClick()
            // An unconsumed incoming image is restored when the Activity is recreated.
            recreateMainActivity()
            waitForViewer()
            assertPermissionsRemainDenied()
            compose.onNodeWithContentDescription("Close photo").performClick()
            compose.onNodeWithText("Open my library").assertIsDisplayed()
            val foreground = requireNotNull(resumedMainActivity())
            instrumentation.runOnMainSync {
                assertNull(foreground.intent.data)
                assertEquals(Intent.ACTION_MAIN, foreground.intent.action)
            }
            // Dismissal consumes the request, so rotation must not reopen it.
            recreateMainActivity()
            compose.onNodeWithText("Open my library").assertIsDisplayed()
            compose.onNodeWithTag("photo-viewer-image").assertDoesNotExist()
            assertPermissionsRemainDenied()
        } finally {
            finishMainActivities()
            preferences.setShowHiddenMedia(previousHidden)
        }
    }

    private fun resumedMainActivity(): MainActivity? {
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull { !it.isFinishing && !it.isDestroyed }
        }
        return activity
    }

    private fun recreateMainActivity() {
        val previous = requireNotNull(resumedMainActivity()) { "Gallery must be resumed before recreation" }
        instrumentation.runOnMainSync { previous.recreate() }
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val replacement = resumedMainActivity()
            if (replacement != null && replacement !== previous) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("A new MainActivity instance did not resume after recreation")
    }

    private fun finishMainActivities() {
        // The incoming Intent is consumed on dismissal. Observe real lifecycle instances instead
        // of matching an ActivityScenario to an Intent whose action/data legitimately changed.
        instrumentation.runOnMainSync {
            Stage.values().filter { it != Stage.DESTROYED }.flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }.filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun waitForViewer() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("photo-viewer-image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("photo-viewer-image").assertIsDisplayed()
        // The top bar shows the date; the file name is the image's description and appears in Details.
        compose.onNodeWithContentDescription("metadata-fixture.jpg").assertExists()
    }

    private fun permissions(): List<String> = buildList {
        add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    private fun assertPermissionsRemainDenied() = permissions().forEach { assertFalse("Opening a shared photo must not grant $it", granted(it)) }
}
