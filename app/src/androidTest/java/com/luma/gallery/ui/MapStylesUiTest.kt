package com.luma.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.compose.ui.unit.dp
import com.luma.gallery.data.Photo
import com.luma.gallery.data.ThemeMode
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Uses native online maps, including a real satellite tile, and checks that their GPS pins work. */
class MapStylesUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousStyle: String? = null
    private val photo = Photo(
        id = 1,
        uri = "content://com.luma.gallery.test/map-photo/1",
        name = "map-pin-test.jpg",
        folder = "Map test",
        folderKey = "map-test/",
        dateTaken = 0,
        latitude = 52.52,
        longitude = 13.405,
        metadataLoaded = true,
    )

    @Before fun resetMapChoice() {
        val prefs = context.getSharedPreferences("luma-map", Context.MODE_PRIVATE)
        previousStyle = prefs.getString("basemap", null)
        prefs.edit().remove("basemap").commit()
    }

    @After fun restoreMapChoice() {
        val editor = context.getSharedPreferences("luma-map", Context.MODE_PRIVATE).edit()
        previousStyle?.let { editor.putString("basemap", it) } ?: editor.remove("basemap")
        editor.commit()
    }

    @Test fun shieldButtonExplainsTheMapAndTurnsItOff() {
        val choice = AtomicReference<Boolean?>(null)
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) { PhotoMapScreen(listOf(photo), true, {}, {}, onlineMap = true, onOnlineMapChange = { choice.set(it) }) } }
        // The question before loading already explained the map, so the note no longer opens by itself.
        compose.onNodeWithTag("map-privacy-note").assertDoesNotExist()
        compose.onNodeWithTag("map-privacy-info").performClick()
        compose.onNodeWithTag("map-privacy-note").assertExists()
        compose.onNodeWithText("Photo locations and the map").assertExists()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithTag("map-privacy-note").assertDoesNotExist()
        compose.onNodeWithTag("map-privacy-info").performClick()
        compose.onNodeWithText("Turn off online map").performClick()
        assertEquals(false, choice.get())
    }

    @Test fun simpleAndSatelliteKeepThePhotoPinInteractive() {
        val openedPhoto = AtomicReference<String?>(null)
        compose.setContent {
            GalleryTheme(ThemeMode.LIGHT) {
                PhotoMapScreen(
                    photos = listOf(photo),
                    hasLocationPermission = true,
                    onRequestLocationPermission = {},
                    onPhotoClick = { openedPhoto.set(it.uri) },
                    onlineMap = true,
                    onOnlineMapChange = {},
                )
            }
        }
        compose.onNodeWithText("Your photo atlas").assertDoesNotExist()
        compose.onNodeWithTag("map-style-simple").assertIsSelected()
        awaitRenderedMap()
        compose.onNodeWithTag("photo-map").performTouchInput { click(center) }
        compose.waitUntil(5_000) { openedPhoto.get() == photo.uri }
        capture("map-simple-light")

        openedPhoto.set(null)
        compose.onNodeWithTag("map-style-satellite").performClick()
        compose.onNodeWithTag("map-style-satellite").assertIsSelected()
        awaitRenderedMap()
        compose.onNodeWithText(SATELLITE_CREDIT).assertExists()
        compose.onNodeWithText(SATELLITE_DATA_CREDIT).assertExists()
        compose.onNodeWithText("1 photo on the map").assertExists()
        compose.onNodeWithTag("photo-map").performTouchInput { click(center) }
        compose.waitUntil(5_000) { openedPhoto.get() == photo.uri }
        capture("map-satellite-light")
    }

    @Test fun selectedStyleSurvivesLeavingTheMap() {
        val showMap = mutableStateOf(true)
        compose.setContent {
            GalleryTheme(ThemeMode.LIGHT) {
                if (showMap.value) {
                    PhotoMapScreen(listOf(photo), true, {}, {}, onlineMap = true, onOnlineMapChange = {})
                }
            }
        }
        compose.onNodeWithTag("map-style-satellite").performClick()
        compose.runOnIdle { showMap.value = false }
        compose.waitForIdle()
        compose.runOnIdle { showMap.value = true }
        compose.onNodeWithTag("map-style-satellite").assertIsSelected()
    }

    @Test fun maximizeRetainsCameraAndInteractivePhotoPinThenBackRestoresEmbeddedMap() {
        val openedPhoto = AtomicReference<String?>(null)
        compose.setContent {
            GalleryTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxWidth().height(460.dp)) {
                    PhotoMapScreen(listOf(photo), true, {}, { openedPhoto.set(it.uri) }, onlineMap = true, onOnlineMapChange = {},
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 102.dp))
                }
            }
        }
        awaitRenderedMap()
        val embeddedHeight = compose.onNodeWithTag("photo-map").fetchSemanticsNode().boundsInRoot.height
        val nativeMap = AtomicReference<MapLibreMap>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            fun findMap(view: View): MapView? {
                if (view is MapView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) {
                    findMap(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            val mapView = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstNotNullOf { findMap(it.window.decorView) }
            mapView.getMapAsync {
                it.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(52.522, 13.410), 14.0))
                nativeMap.set(it)
            }
        }
        compose.waitUntil(5_000) { nativeMap.get() != null }
        compose.onNodeWithContentDescription("Maximize map").performClick()
        compose.onNodeWithTag("full-screen-map").assertIsDisplayed()
        compose.onNodeWithContentDescription("Restore map").assertIsDisplayed()
        compose.waitForIdle()
        SystemClock.sleep(450)
        val fullHeight = compose.onNodeWithTag("photo-map").fetchSemanticsNode().boundsInRoot.height
        assertTrue("A maximized map must leave the small embedded viewport", fullHeight > embeddedHeight)
        val mapBounds = compose.onNodeWithTag("photo-map").fetchSemanticsNode().boundsInRoot
        val windowBounds = compose.onNodeWithTag("full-screen-map").fetchSemanticsNode().boundsInRoot
        assertEquals("Embedded navigation spacing must not shrink the full-screen map", windowBounds, mapBounds)
        instrumentation.runOnMainSync {
            val camera = nativeMap.get().cameraPosition
            assertEquals(52.522, camera.target!!.latitude, 0.00001)
            assertEquals(13.410, camera.target!!.longitude, 0.00001)
            assertEquals(14.0, camera.zoom, 0.01)
            nativeMap.get().moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(photo.latitude!!, photo.longitude!!), 14.0))
        }
        compose.onNodeWithTag("photo-map").performTouchInput { click(center) }
        compose.waitUntil(5_000) { openedPhoto.get() == photo.uri }
        capture("map-maximized")
        Espresso.pressBack()
        compose.onNodeWithTag("full-screen-map").assertDoesNotExist()
        compose.onNodeWithContentDescription("Maximize map").assertIsDisplayed()
        instrumentation.runOnMainSync {
            assertEquals(14.0, nativeMap.get().cameraPosition.zoom, 0.01)
        }
    }

    private fun awaitRenderedMap() {
        compose.waitUntil(35_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Map ready"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val output = File(context.getExternalFilesDir(null), "qa/$name.png")
        output.parentFile!!.mkdirs()
        try {
            output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            screenshot.recycle()
        }
    }
}
