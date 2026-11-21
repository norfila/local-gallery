package com.luma.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.luma.gallery.data.Photo
import com.luma.gallery.data.ThemeMode
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals

class CountriesUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realCountryGroupsOpenTheirOwnPhotoGridAndReturn() {
        val opened = AtomicReference<String?>(null)
        val photos = listOf(
            photo(1, "Berlin.jpg", 52.52, 13.405, Color.rgb(106, 146, 166)),
            photo(2, "Vienna.jpg", 48.2082, 16.3738, Color.rgb(185, 154, 118)),
            photo(3, "California.jpg", 37.7749, -122.4194, Color.rgb(147, 156, 116)),
        )
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) { CountriesScreen(photos, true, {}, { opened.set(it.uri) }) } }
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("country-DE")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("country-AT").assertHasClickAction()
        compose.onNodeWithTag("country-DE").assertHasClickAction()
        compose.onNodeWithTag("country-US").assertHasClickAction()
        capture("countries-light")
        // The photo count sits in a badge on the picture; there is no count line under the name.
        listOf("AT", "DE", "US").forEach { country ->
            compose.onNode(androidx.compose.ui.test.hasText("1") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("country-count-$country")),
                useUnmergedTree = true).assertExists()
        }
        compose.onAllNodesWithText("1 photo").assertCountEquals(0)
        compose.onNodeWithTag("country-DE").performClick()
        compose.onNodeWithText("Germany").assertExists()
        compose.onNodeWithText("Austria").assertDoesNotExist()
        compose.onNodeWithText("United States").assertDoesNotExist()
        compose.onNodeWithContentDescription("Berlin.jpg").performClick()
        compose.runOnIdle { assertEquals(photos.first().uri, opened.get()) }
        compose.onNodeWithTag("countries-back").performClick()
        compose.onNodeWithTag("country-US").assertExists()
        photos.forEach { File(it.uri.removePrefix("file://")).delete() }
    }

    @Test fun locationAccessIsRequiredBeforeShowingCountryAssignments() {
        val requested = AtomicReference(false)
        val hasPermission = mutableStateOf(false)
        val photos = listOf(photo(4, "Hidden.jpg", 52.52, 13.405, Color.DKGRAY))
        compose.setContent {
            GalleryTheme(ThemeMode.DARK) {
                CountriesScreen(photos, hasPermission.value, { requested.set(true) }, {})
            }
        }
        compose.onNodeWithTag("countries-grid").assertDoesNotExist()
        compose.onNode(androidx.compose.ui.test.hasText("Allow photo locations") and androidx.compose.ui.test.hasClickAction()).performClick()
        compose.runOnIdle { assertTrue(requested.get()); hasPermission.value = true }
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("country-DE")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("country-DE").assertExists()
        compose.runOnIdle { hasPermission.value = false }
        compose.onNodeWithTag("countries-grid").assertDoesNotExist()
        photos.forEach { File(it.uri.removePrefix("file://")).delete() }
    }

    private fun photo(id: Long, name: String, latitude: Double, longitude: Double, color: Int): Photo {
        val image = File(context.cacheDir, "country-test-$id.jpg")
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.RGB_565)
        bitmap.eraseColor(color)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Photo(id, "file://${image.absolutePath}", name, "Travel", "DCIM/Travel/", 0, latitude = latitude, longitude = longitude, metadataLoaded = true)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val output = File(context.getExternalFilesDir(null), "qa/$name.png")
        output.parentFile!!.mkdirs()
        try { output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }
}
