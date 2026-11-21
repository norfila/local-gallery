package com.luma.gallery.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.luma.gallery.data.Photo
import com.luma.gallery.data.PhotoLayout
import com.luma.gallery.data.ThemeMode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PhotoGridTest {
    @get:Rule val compose = createComposeRule()
    private val october = ZonedDateTime.parse("2026-10-15T12:00:00Z").toInstant().toEpochMilli()
    private fun photo(id: Int, width: Int = 100, height: Int = 100, time: Long = october) =
        Photo(id.toLong(), "content://grid-test/$id", "Grid photo $id", "Test", "Test/", time, width = width, height = height)

    @Test fun fiveColumnsAreSquareAndMasonryUsesEachPhotosAspectRatio() {
        val photos = (0..9).map { photo(it, width = if (it == 0) 50 else 200, height = 100) }
        val layout = mutableStateOf(PhotoLayout.GRID)
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) {
            PhotoGrid(photos, emptySet(), {}, {}, Modifier.fillMaxSize(), groupByMonth = false, columns = 5, layout = layout.value)
        } }
        val first = compose.onNodeWithTag("photo-tile-0").fetchSemanticsNode().boundsInRoot
        val fifth = compose.onNodeWithTag("photo-tile-4").fetchSemanticsNode().boundsInRoot
        val sixth = compose.onNodeWithTag("photo-tile-5").fetchSemanticsNode().boundsInRoot
        assertTrue("Five photos share the first row", kotlin.math.abs(first.top - fifth.top) < 1f)
        assertTrue("The sixth photo begins another row", sixth.top > first.bottom)
        assertEquals(first.width, first.height, 1f)
        compose.runOnIdle { layout.value = PhotoLayout.MASONRY }
        val portrait = compose.onNodeWithTag("photo-tile-0").fetchSemanticsNode().boundsInRoot
        val landscape = compose.onNodeWithTag("photo-tile-1").fetchSemanticsNode().boundsInRoot
        assertEquals(portrait.width * 2, portrait.height, 2f)
        assertEquals(landscape.width / 2, landscape.height, 2f)
    }

    @Test fun monthCheckboxSelectsOnlyThatMonthAndKeepsOtherMonths() {
        val photos = listOf(photo(0), photo(1), photo(2, time = october - 31L * 24 * 60 * 60 * 1000))
        val picked = mutableStateOf(setOf(photos[0].uri, photos[2].uri))
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) {
            PhotoGrid(photos, picked.value, {}, {}, Modifier.fillMaxSize(), onSelectionChange = { picked.value = it })
        } }
        val month = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(ZonedDateTime.parse("2026-10-15T12:00:00Z"))
        compose.onNodeWithTag("select-month-$month").performClick()
        compose.runOnIdle { assertEquals(photos.map(Photo::uri).toSet(), picked.value) }
        compose.onNodeWithTag("select-month-$month").performClick()
        compose.runOnIdle { assertEquals(setOf(photos[2].uri), picked.value) }
    }

    @Test fun longPressDragSelectsAContinuousRangeAndReversingRestoresTheTail() {
        val photos = (0..11).map { photo(it) }
        val picked = mutableStateOf(emptySet<String>())
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) {
            Box(Modifier.fillMaxSize()) {
                PhotoGrid(photos, picked.value, {}, {}, Modifier.fillMaxSize(), groupByMonth = false,
                    onSelectionChange = { picked.value = it })
            }
        } }
        dragAcrossTiles(0, 5, 2)
        compose.runOnIdle { assertEquals(photos.take(3).map(Photo::uri).toSet(), picked.value) }
        // Starting on a selected tile changes the gesture to removal; reversal restores the tail.
        dragAcrossTiles(0, 2, 1)
        compose.runOnIdle { assertEquals(setOf(photos[2].uri), picked.value) }
    }

    @Test fun selectingIsFeltAsOnePulseThenALightTickPerTileAndPerTap() {
        val photos = (0..11).map { photo(it) }
        val picked = mutableStateOf(emptySet<String>())
        val felt = mutableListOf<HapticFeedbackType>()
        val recorder = object : HapticFeedback { override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { felt += hapticFeedbackType } }
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) { CompositionLocalProvider(LocalHapticFeedback provides recorder) {
            PhotoGrid(photos, picked.value, { photo ->
                if (picked.value.isNotEmpty()) picked.value = if (photo.uri in picked.value) picked.value - photo.uri else picked.value + photo.uri
            }, {}, Modifier.fillMaxSize(), groupByMonth = false, onSelectionChange = { picked.value = it })
        } } }
        // A tap that opens a photo is not a selection, so nothing is felt.
        compose.onNodeWithTag("photo-tile-3").performClick()
        compose.runOnIdle { assertTrue(felt.isEmpty()) }

        dragAcrossTiles(0, 5, 2)
        compose.runOnIdle {
            assertEquals(photos.take(3).map(Photo::uri).toSet(), picked.value)
            assertEquals("The long press that starts a selection is one firm pulse", HapticFeedbackType.LongPress, felt.first())
            val ticks = felt.drop(1)
            assertTrue("Every tile the drag reaches or leaves is a light tick: $felt", ticks.size >= 2 && ticks.all { it == HapticFeedbackType.TextHandleMove })
            assertTrue("Staying on a tile is felt once, not on every movement: $felt", ticks.size <= 8)
        }
        compose.runOnIdle { felt.clear() }
        compose.onNodeWithTag("photo-tile-7").performClick()
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.TextHandleMove), felt) }
    }

    @Test fun activeSelectionAcceptsAnImmediateHorizontalSwipe() {
        val photos = (0..8).map { photo(it) }
        val picked = mutableStateOf(setOf(photos[8].uri))
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) {
            PhotoGrid(photos, picked.value, {}, {}, Modifier.fillMaxSize(), groupByMonth = false,
                onSelectionChange = { picked.value = it })
        } }
        val origin = compose.onNodeWithTag("photo-grid").fetchSemanticsNode().boundsInRoot.topLeft
        val from = compose.onNodeWithTag("photo-tile-0").fetchSemanticsNode().boundsInRoot.center - origin
        val to = compose.onNodeWithTag("photo-tile-2").fetchSemanticsNode().boundsInRoot.center - origin
        compose.onNodeWithTag("photo-grid").performTouchInput { swipe(from, to, durationMillis = 180) }
        compose.runOnIdle { assertEquals(setOf(photos[0].uri, photos[1].uri, photos[2].uri, photos[8].uri), picked.value) }
    }

    @Test fun stationaryLongPressDoesNotAlsoClickAndNextTapAddsToSelection() {
        val photos = (0..5).map { photo(it) }
        val picked = mutableStateOf(emptySet<String>())
        var clicks = 0
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) {
            PhotoGrid(photos, picked.value, { photo ->
                clicks++
                picked.value = if (photo.uri in picked.value) picked.value - photo.uri else picked.value + photo.uri
            }, {}, Modifier.fillMaxSize(), groupByMonth = false, onSelectionChange = { picked.value = it })
        } }
        compose.onNodeWithTag("photo-tile-0").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(setOf(photos[0].uri), picked.value)
            assertEquals("Releasing a long press must not generate a click", 0, clicks)
        }
        compose.onNodeWithTag("photo-tile-1").performClick()
        compose.runOnIdle {
            assertEquals(setOf(photos[0].uri, photos[1].uri), picked.value)
            assertEquals(1, clicks)
        }
    }

    @Test fun activeSelectionStillAllowsVerticalScrolling() {
        val photos = (0..59).map { photo(it) }
        val initial = setOf(photos[0].uri)
        val picked = mutableStateOf(initial)
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) {
            PhotoGrid(photos, picked.value, {}, {}, Modifier.fillMaxSize(), groupByMonth = false,
                onSelectionChange = { picked.value = it })
        } }
        compose.onNodeWithTag("photo-grid").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(initial, picked.value) }
        compose.onNodeWithTag("photo-tile-0").assertDoesNotExist()
    }

    @Test fun masonryLongPressDragUsesVisualTilePositions() {
        val photos = (0..11).map { photo(it, width = if (it % 2 == 0) 50 else 200) }
        val picked = mutableStateOf(emptySet<String>())
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) {
            PhotoGrid(photos, picked.value, {}, {}, Modifier.fillMaxSize(), groupByMonth = false,
                layout = PhotoLayout.MASONRY, onSelectionChange = { picked.value = it })
        } }
        dragAcrossTiles(0, 4, 2)
        compose.runOnIdle { assertEquals(photos.take(3).map(Photo::uri).toSet(), picked.value) }
    }

    @Test fun pinchingChangesGridDensityWithoutOpeningOrSelectingPhotos() {
        val photos = (0..59).map { photo(it) }
        val columns = mutableStateOf(3)
        val picked = mutableStateOf(emptySet<String>())
        var clicks = 0
        var longClicks = 0
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) {
            PhotoGrid(photos, picked.value, { clicks++ }, { longClicks++ }, Modifier.fillMaxSize(),
                groupByMonth = false, columns = columns.value,
                onSelectionChange = { picked.value = it }, onColumnsChange = { columns.value = it })
        } }
        val originalWidth = compose.onNodeWithTag("photo-tile-0").fetchSemanticsNode().boundsInRoot.width

        pinchColumns(.30f, .20f)
        var compactColumns = 0
        compose.runOnIdle {
            compactColumns = columns.value
            assertTrue("Pinching inward fits more photos across the screen", compactColumns > 3)
            assertEquals(emptySet<String>(), picked.value)
            assertEquals(0, clicks)
            assertEquals(0, longClicks)
        }
        val compactWidth = compose.onNodeWithTag("photo-tile-0").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Photos become visibly smaller", compactWidth < originalWidth)

        pinchColumns(.20f, .30f)
        compose.runOnIdle {
            assertTrue("Spreading fingers shows fewer, larger photos", columns.value < compactColumns)
            assertEquals(emptySet<String>(), picked.value)
            assertEquals(0, clicks)
            assertEquals(0, longClicks)
        }
        assertTrue(compose.onNodeWithTag("photo-tile-0").fetchSemanticsNode().boundsInRoot.width > compactWidth)
        compose.onNodeWithTag("photo-tile-0").performTouchInput { click() }
        compose.runOnIdle { assertEquals("An ordinary tap still opens a photo after zooming", 1, clicks) }
    }

    @Test fun onePinchCanCrossSeveralColumnsAndRepeatedPinchesStayWithinLimits() {
        val photos = (0..59).map { photo(it) }
        val columns = mutableStateOf(3)
        val changes = mutableListOf<Int>()
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) {
            PhotoGrid(photos, emptySet(), {}, {}, Modifier.fillMaxSize(), groupByMonth = false,
                columns = columns.value, onColumnsChange = { columns.value = it; changes += it })
        } }
        pinchColumns(.40f, .06f)
        compose.runOnIdle { assertEquals("A continuous inward pinch reaches six columns", 6, columns.value) }
        pinchColumns(.40f, .06f)
        compose.runOnIdle { assertEquals("Further inward pinching stays at the densest layout", 6, columns.value) }

        pinchColumns(.06f, .40f)
        compose.runOnIdle { assertEquals("A continuous outward pinch reaches one column", 1, columns.value) }
        pinchColumns(.06f, .40f)
        compose.runOnIdle {
            assertEquals("Further outward pinching stays at one column", 1, columns.value)
            assertTrue("No out-of-range preference is emitted", changes.isNotEmpty() && changes.all { it in 1..6 })
        }
        val first = compose.onNodeWithTag("photo-tile-0").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("photo-tile-1").fetchSemanticsNode().boundsInRoot
        assertTrue("The final layout actually has one photo per row", second.top > first.bottom)
    }

    @Test fun pinchingMasonryKeepsTheSelectionAndPhotoProportions() {
        val photos = (0..29).map { photo(it, width = if (it % 2 == 0) 50 else 200) }
        val columns = mutableStateOf(3)
        val initial = setOf(photos[0].uri, photos[5].uri)
        val picked = mutableStateOf(initial)
        var clicks = 0
        var longClicks = 0
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) {
            PhotoGrid(photos, picked.value, { clicks++ }, { longClicks++ }, Modifier.fillMaxSize(),
                groupByMonth = false, columns = columns.value, layout = PhotoLayout.MASONRY,
                onSelectionChange = { picked.value = it }, onColumnsChange = { columns.value = it })
        } }
        val originalWidth = compose.onNodeWithTag("photo-tile-0").fetchSemanticsNode().boundsInRoot.width
        pinchColumns(.30f, .20f)
        var compactColumns = 0
        compose.runOnIdle {
            compactColumns = columns.value
            assertTrue(compactColumns > 3)
            assertEquals("A pinch never paints a selection range", initial, picked.value)
            assertEquals(0, clicks)
            assertEquals(0, longClicks)
        }
        val portrait = compose.onNodeWithTag("photo-tile-0").fetchSemanticsNode().boundsInRoot
        assertTrue(portrait.width < originalWidth)
        assertEquals("Masonry keeps the portrait's aspect ratio", portrait.width * 2, portrait.height, 2f)
        pinchColumns(.20f, .30f)
        compose.runOnIdle {
            assertTrue(columns.value < compactColumns)
            assertEquals(initial, picked.value)
            assertEquals(0, clicks)
            assertEquals(0, longClicks)
        }
    }

    /** Fractions specify each finger's distance from the midpoint, relative to grid width. */
    private fun pinchColumns(startRadius: Float, endRadius: Float) {
        compose.onNodeWithTag("photo-grid").performTouchInput {
            val midpoint = Offset(centerX, minOf(centerY, width * .45f))
            down(0, midpoint - Offset(width * startRadius, 0f))
            down(1, midpoint + Offset(width * startRadius, 0f))
            repeat(12) { step ->
                val radius = width * (startRadius + (endRadius - startRadius) * (step + 1) / 12f)
                updatePointerTo(0, midpoint - Offset(radius, 0f))
                updatePointerTo(1, midpoint + Offset(radius, 0f))
                move(delayMillis = 20)
            }
            up(0)
            up(1)
        }
    }

    private fun dragAcrossTiles(start: Int, far: Int, back: Int) {
        val origin = compose.onNodeWithTag("photo-grid").fetchSemanticsNode().boundsInRoot.topLeft
        fun point(index: Int): Offset = compose.onNodeWithTag("photo-tile-$index").fetchSemanticsNode().boundsInRoot.center - origin
        val from = point(start)
        val through = point(far)
        val to = point(back)
        compose.onNodeWithTag("photo-grid").performTouchInput {
            down(from)
            advanceEventTime(700)
            moveTo(through, delayMillis = 100)
            moveTo(to, delayMillis = 100)
            up()
        }
    }
}
