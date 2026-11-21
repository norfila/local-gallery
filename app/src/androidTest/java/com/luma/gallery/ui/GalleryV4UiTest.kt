package com.luma.gallery.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.luma.gallery.MainActivity
import com.luma.gallery.data.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.util.UUID
import java.io.File

/** Uses the activity and published image/video files to exercise the new gallery controls. */
class GalleryV4UiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val media = mutableListOf<Uri>()
    private val suffix = UUID.randomUUID().toString().take(8)
    private val highName = "gallery-v4-$suffix-high.jpg"
    private val lowName = "gallery-v4-$suffix-low.jpg"
    private val videoName = "gallery-v4-$suffix-clip.mp4"

    // Preferences must be set before the activity starts: rememberSaveable correctly retains
    // an already-open page on configuration changes instead of reopening the start page.
    private val fixtures = TestRule { base, description -> object : Statement() {
        override fun evaluate() {
            val prefs = AppPreferences(context)
            val theme = prefs.themeMode()
            val start = prefs.startPage()
            val columns = prefs.photoColumns()
            val layout = prefs.photoLayout()
            val roundedCorners = prefs.roundedPhotoCorners()
            val favorites = prefs.favoriteFilters()
            try {
                prefs.setThemeMode(ThemeMode.SYSTEM)
                prefs.setStartPage(if (description.methodName.startsWith("configuredStartPage")) StartPage.VIDEOS else StartPage.LIBRARY)
                prefs.setPhotoColumns(3); prefs.setPhotoLayout(PhotoLayout.GRID); prefs.setFavoriteFilters(emptyList())
                prefs.setRoundedPhotoCorners(false)
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                if (Build.VERSION.SDK_INT >= 33) {
                    automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
                    automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
                } else automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT >= 29) automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
                media += seedImage(highName, 5, Color.rgb(106, 145, 126))
                media += seedImage(lowName, 1, Color.rgb(174, 126, 106))
                media += createVideoFixture(context).also { uri ->
                    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, videoName) }, null, null)
                }
                base.evaluate()
            } finally {
                media.forEach { context.contentResolver.delete(it, null, null) }
                media.clear()
                prefs.setThemeMode(theme); prefs.setStartPage(start); prefs.setPhotoColumns(columns); prefs.setPhotoLayout(layout); prefs.setFavoriteFilters(favorites)
                prefs.setRoundedPhotoCorners(roundedCorners)
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixtures).around(compose)

    @Test fun arrangementAndStartPageChoicesPersistAndLayoutCanChangeFromTheLibrary() {
        searchFixtures()
        waitForDescription(highName)
        openSettings()
        choose("start-page-dropdown", "Videos")
        compose.onNodeWithTag("settings-layout-masonry").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("columns-choice-5").performScrollTo().performClick().assertIsSelected()
        assertArrangementPreview("Masonry, 5 columns, square corners")
        compose.onNodeWithTag("rounded-photo-corners").performScrollTo().assertIsOff().performClick().assertIsOn()
        assertArrangementPreview("Masonry, 5 columns, rounded corners")
        capture("layout-settings")
        compose.onNodeWithText("Open Android app settings").assertDoesNotExist()
        // Photo locations are part of Media access; there is no separate GPS row.
        compose.onNodeWithText("Photo GPS metadata").assertDoesNotExist()
        compose.onNodeWithText("Media access").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Albums and app ratings are stored on this device.").assertDoesNotExist()
        compose.onNodeWithText("New, changed, and removed photos update automatically.").assertDoesNotExist()
        compose.onNodeWithText("Gallery does not add or change ratings.", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("About Gallery").performScrollTo().performClick()
        compose.onNodeWithText("Gallery does not add or change ratings.", substring = true).assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText("Gallery does not add or change ratings.", substring = true).assertDoesNotExist()
        // The bundled privacy policy opens from Settings, without its page title or language link.
        compose.onNodeWithTag("settings-privacy").performScrollTo().performClick()
        compose.onNodeWithTag("privacy-policy").assertIsDisplayed()
        compose.onNodeWithText("yggdrasil@tuta.com", substring = true).assertExists()
        // Settings behind the policy names OpenFreeMap too (Online map), so look inside the policy only.
        compose.onNode(hasText("OpenFreeMap", substring = true) and hasAnyAncestor(hasTestTag("privacy-policy"))).assertExists()
        compose.onNodeWithText("Deutsche Fassung", substring = true).assertDoesNotExist()
        capture("privacy-policy")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("privacy-policy").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        assertGrid("Masonry, 5 columns")
        capture("masonry-five-columns")
        compose.runOnIdle {
            AppPreferences(context).let { prefs ->
                assertEquals(StartPage.VIDEOS, prefs.startPage())
                assertEquals(PhotoLayout.MASONRY, prefs.photoLayout())
                assertEquals(5, prefs.photoColumns())
                assertTrue(prefs.roundedPhotoCorners())
            }
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("photo-grid").fetchSemanticsNodes().isNotEmpty() }
        assertGrid("Masonry, 5 columns")
        openSettings()
        compose.onNodeWithTag("start-page-dropdown").assertTextContains("Videos")
        compose.onNodeWithTag("settings-layout-masonry").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("columns-choice-5").performScrollTo().assertIsSelected()
        assertArrangementPreview("Masonry, 5 columns, rounded corners")
        compose.onNodeWithTag("rounded-photo-corners").performScrollTo().assertIsOn()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithTag("arrangement-button").performClick()
        compose.onNodeWithText("Photo arrangement").assertIsDisplayed()
        compose.onNodeWithTag("settings-layout-grid").performClick().assertIsSelected()
        compose.onNodeWithTag("columns-choice-1").performClick().assertIsSelected()
        assertArrangementPreview("Grid, 1 column, rounded corners")
        // The list layout has no per-row choice.
        compose.onNodeWithTag("settings-layout-list").performClick()
        compose.onNodeWithTag("columns-choice-1").assertDoesNotExist()
        assertArrangementPreview("List, rounded corners")
        compose.onNodeWithTag("settings-layout-grid").performClick()
        androidx.test.espresso.Espresso.pressBack()
        assertGrid("Grid, 1 column")
    }

    @Test fun configuredStartPageOpensVideosOnAColdLaunch() {
        searchFixtures()
        waitForDescription(videoName)
        compose.onNodeWithContentDescription(highName).assertDoesNotExist()
        compose.onNodeWithContentDescription(lowName).assertDoesNotExist()
        compose.onNodeWithText("1 ITEM").assertExists()
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        drawerItem("Videos").assertIsSelected()
        androidx.test.espresso.Espresso.pressBack()
    }

    @Test fun photosAndVideosDrawerPagesShowOnlyTheirRespectiveMedia() {
        searchFixtures()
        waitForDescription(highName)
        waitForDescription(videoName)
        compose.onNodeWithText("3 ITEMS").assertExists()
        openPage("Photos")
        searchFixtures()
        waitForDescription(highName)
        compose.onNodeWithContentDescription(lowName).assertExists()
        compose.onNodeWithContentDescription(videoName).assertDoesNotExist()
        compose.onNodeWithText("2 PHOTOS").assertExists()
        openPage("Videos")
        searchFixtures()
        waitForDescription(videoName)
        compose.onNodeWithContentDescription(highName).assertDoesNotExist()
        compose.onNodeWithContentDescription(lowName).assertDoesNotExist()
        compose.onNodeWithText("1 ITEM").assertExists()
        compose.onNodeWithContentDescription(videoName).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("video-viewer-player").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 of 1").assertExists()
        capture("video-viewer")
        compose.onNodeWithContentDescription("Close photo").performClick()
    }

    @Test fun libraryShortcutStripSwitchesRealMediaAndClearsFolderDrilldown() {
        searchFixtures()
        waitForDescription(highName)
        waitForDescription(videoName)
        assertCurrentView("All items")
        compose.onNodeWithText("3 ITEMS").assertExists()
        assertCompactHeader()
        compose.onNodeWithText("No filters").assertDoesNotExist()
        compose.onNodeWithTag("active-filter-chips").assertDoesNotExist()

        bottomNavigation("Library").performClick()
        compose.onNodeWithTag("library-shortcut-strip").assertIsDisplayed()
        compose.onNodeWithTag("library-shortcut-all").assertIsSelected()
        capture("library-shortcut-strip")
        compose.onNodeWithTag("library-shortcut-photos").performClick()
        compose.onNodeWithTag("library-shortcut-strip").assertDoesNotExist()
        assertCurrentView("Photos")
        compose.onNodeWithText("2 PHOTOS").assertExists()
        compose.onNodeWithContentDescription(highName).assertExists()
        compose.onNodeWithContentDescription(lowName).assertExists()
        compose.onNodeWithContentDescription(videoName).assertDoesNotExist()

        bottomNavigation("Library").performClick()
        compose.onNodeWithTag("library-shortcut-photos").assertIsSelected()
        compose.onNodeWithTag("library-shortcut-videos").performClick()
        assertCurrentView("Videos")
        compose.onNodeWithText("1 ITEM").assertExists()
        compose.onNodeWithContentDescription(videoName).assertExists()
        compose.onNodeWithContentDescription(highName).assertDoesNotExist()

        bottomNavigation("Library").performClick()
        compose.onNodeWithTag("library-shortcut-videos").assertIsSelected()
        compose.onNodeWithTag("library-shortcut-folders").performClick()
        assertCurrentView("Folders")
        compose.onNodeWithTag("folder-grid").assertExists()
        compose.onNodeWithText("GalleryV4QA").assertExists()
        compose.onNodeWithText("GalleryVideoQA").assertExists()
        compose.onNodeWithText("GalleryV4QA").performClick()
        compose.onNodeWithContentDescription("Back to folders").assertExists()
        compose.onNodeWithContentDescription(highName).assertExists()
        compose.onNodeWithContentDescription(videoName).assertDoesNotExist()
        compose.onNodeWithContentDescription(highName).performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        // While selecting, the bar at the bottom holds the actions; the navigation returns with the selection's end.
        compose.onNodeWithTag("selection-move").assertIsDisplayed()
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel selection").performClick()
        compose.onNodeWithTag("selection-bar").assertDoesNotExist()

        // Library remains selected inside a folder, so tapping it opens its shortcuts.
        bottomNavigation("Library").performClick()
        compose.onNodeWithTag("library-shortcut-folders").assertIsSelected()
        compose.onNodeWithTag("library-shortcut-all").performClick()
        assertCurrentView("All items")
        compose.onNodeWithContentDescription("Back to folders").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel selection").assertDoesNotExist()
        compose.onNodeWithText("3 ITEMS").assertExists()
        compose.onNodeWithContentDescription(videoName).assertExists()
    }

    @Test fun libraryShortcutsDismissWithoutChangingTheViewAndFirstTapReturnsToAll() {
        searchFixtures()
        waitForDescription(highName)
        chooseLibraryShortcut("photos")
        bottomNavigation("Library").performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("library-shortcut-strip").assertDoesNotExist()
        assertCurrentView("Photos")
        compose.onNodeWithText("2 PHOTOS").assertExists()

        bottomNavigation("Library").performClick()
        bottomNavigation("Library").performClick()
        compose.onNodeWithTag("library-shortcut-strip").assertDoesNotExist()
        assertCurrentView("Photos")

        bottomNavigation("Library").performClick()
        compose.onNodeWithTag("dismiss-library-shortcuts").performTouchInput { click(Offset(width * .5f, height * .25f)) }
        compose.onNodeWithTag("library-shortcut-strip").assertDoesNotExist()
        assertCurrentView("Photos")

        bottomNavigation("Library").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("library-current-view").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-shortcut-strip").assertDoesNotExist()
        assertCurrentView("Photos")

        bottomNavigation("Library").performClick()
        bottomNavigation("Albums").performClick()
        compose.onNodeWithTag("library-shortcut-strip").assertDoesNotExist()
        bottomNavigation("Library").performClick()
        compose.onNodeWithTag("library-shortcut-strip").assertDoesNotExist()
        assertCurrentView("All items")
        searchFixtures()
        waitForDescription(videoName)
        compose.onNodeWithText("3 ITEMS").assertExists()
        bottomNavigation("Library").performClick()
        compose.onNodeWithTag("library-shortcut-all").assertIsSelected()
    }

    @Test fun libraryShortcutsPreserveSearchAndMetadataFilters() {
        searchFixtures()
        waitForDescription(highName)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Reading metadata", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("search-open").performClick()
        addFourStarRule()
        compose.showSearchResults()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("1 PHOTO").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(highName).assertExists()

        chooseLibraryShortcut("photos")
        compose.onNodeWithText("1 PHOTO").assertExists()
        compose.onNodeWithContentDescription(highName).assertExists()
        compose.onNodeWithContentDescription(lowName).assertDoesNotExist()
        compose.onNodeWithText("gallery-v4-$suffix-").assertExists()

        chooseLibraryShortcut("videos")
        compose.onNodeWithContentDescription(videoName).assertDoesNotExist()
        compose.onNodeWithContentDescription(highName).assertDoesNotExist()
        chooseLibraryShortcut("all")
        compose.onNodeWithText("1 PHOTO").assertExists()
        compose.onNodeWithContentDescription(highName).assertExists()
        compose.onNodeWithContentDescription(lowName).assertDoesNotExist()
        compose.onNodeWithText("gallery-v4-$suffix-").assertExists()
    }

    @Test fun starredMetadataFilterBecomesAPersistentShortcutAndCanBeRemoved() {
        searchFixtures()
        waitForDescription(highName)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("3 ITEMS").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-open").performClick()
        addFourStarRule()
        compose.onNodeWithTag("favorite-selected-filters").performScrollTo().performClick()
        compose.onNodeWithTag("favorite-name").performTextReplacement("Best QA")
        compose.onNode(hasText("Save favorite") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { AppPreferences(context).favoriteFilters().any { it.name == "Best QA" } }
        compose.showSearchResults()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("1 PHOTO").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(highName).assertExists()
        compose.onNodeWithContentDescription(lowName).assertDoesNotExist()
        capture("starred-quick-filter")
        compose.onNodeWithTag("active-filter-chips").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove Rating >= 4 stars filter").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("3 ITEMS").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("active-filter-chips").assertDoesNotExist()
        // Removing a criterion retains text; favorites live in the search workspace.
        compose.onNodeWithText("gallery-v4-$suffix-").assertExists()
        compose.onNodeWithText("Best QA").assertDoesNotExist()
        compose.onNodeWithTag("search-open").performClick()
        compose.onNodeWithContentDescription("Add favorite Best QA").performScrollTo().performClick()
        compose.showSearchResults()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 PHOTO").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        searchFixtures()
        compose.onNodeWithTag("search-open").performClick()
        compose.onNodeWithText("Best QA").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add favorite Best QA").performClick()
        // The count runs on a worker; until it is ready the button just says "Show results".
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("search-result-count") and hasText("Show 1 photo"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("search-result-count", useUnmergedTree = true).assertTextEquals("Show 1 photo")
        compose.onNodeWithContentDescription("Manage favorites").performClick()
        compose.onNodeWithContentDescription("Delete favorite Best QA").performClick()
        compose.waitUntil(10_000) { AppPreferences(context).favoriteFilters().none { it.name == "Best QA" } }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Best QA").assertDoesNotExist()
    }

    @Test fun selectingAMonthKeepsTilesStationaryAndSelectsItsRealPhotos() {
        openPage("Photos")
        searchFixtures()
        waitForDescription(highName)
        waitForDescription(lowName)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Reading metadata", substring = true).fetchSemanticsNodes().isEmpty() }
        val before = compose.onNodeWithContentDescription(highName).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription(highName).performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        val during = compose.onNodeWithContentDescription(highName).fetchSemanticsNode().boundsInRoot
        assertEquals("Selection must not move the photo under the finger", before.top, during.top, 1f)
        compose.onNode(hasContentDescription("Select all in", substring = true)).performClick()
        compose.onNodeWithText("2 selected").assertExists()
        compose.onNodeWithContentDescription("Add selection to album").assertIsDisplayed()
        capture("month-selection")
        compose.onNodeWithContentDescription("Cancel selection").performClick()
        compose.onNode(hasContentDescription("Select all in", substring = true)).assertDoesNotExist()
    }

    @Test fun pinchingInTheLibrarySavesItsColumnsAndNavigationFloatsOverTheGrid() {
        searchFixtures()
        waitForDescription(highName)
        val grid = compose.onNodeWithTag("photo-grid").fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue("Photos extend beneath the floating navigation", grid.bottom > navigation.top)
        compose.onNodeWithTag("photo-grid").performTouchInput {
            val midpoint = Offset(centerX, minOf(centerY, width * .45f))
            down(0, midpoint - Offset(width * .30f, 0f))
            down(1, midpoint + Offset(width * .30f, 0f))
            repeat(12) { step ->
                val radius = width * (.30f - .10f * (step + 1) / 12f)
                updatePointerTo(0, midpoint - Offset(radius, 0f))
                updatePointerTo(1, midpoint + Offset(radius, 0f))
                move(delayMillis = 20)
            }
            up(0); up(1)
        }
        assertGrid("Grid, 4 columns")
        compose.runOnIdle { assertEquals(4, AppPreferences(context).photoColumns()) }
        compose.onNodeWithContentDescription("Cancel selection").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close photo").assertDoesNotExist()
        capture("pinch-columns-floating-navigation")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("photo-grid").fetchSemanticsNodes().isNotEmpty() }
        assertGrid("Grid, 4 columns")
    }

    @Test fun sortMenuHighlightsTheCurrentChoiceAndReordersTheRealPhotos() {
        openPage("Photos")
        searchFixtures()
        waitForDescription(highName)
        waitForDescription(lowName)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Reading metadata", substring = true).fetchSemanticsNodes().isEmpty() }
        fun assertFirst(first: String, second: String) {
            val before = compose.onNodeWithContentDescription(first).fetchSemanticsNode().boundsInRoot
            val after = compose.onNodeWithContentDescription(second).fetchSemanticsNode().boundsInRoot
            assertTrue("$first should precede $second", before.top < after.top || (before.top == after.top && before.left < after.left))
        }
        compose.onNodeWithTag("photo-sort-control").performClick()
        compose.onNodeWithTag("photo-sort-newest").assertIsSelected()
        capture("sort-menu")
        compose.onNodeWithTag("photo-sort-oldest").performClick()
        compose.onNodeWithTag("photo-sort-menu").assertDoesNotExist()
        assertFirst(highName, lowName)
        compose.onNodeWithTag("photo-sort-control").performClick()
        compose.onNodeWithTag("photo-sort-oldest").assertIsSelected()
        compose.onNodeWithTag("photo-sort-newest").performClick()
        assertFirst(lowName, highName)
        compose.onNodeWithTag("photo-sort-control").performClick()
        compose.onNodeWithTag("photo-sort-rating").performClick()
        assertFirst(highName, lowName)
        compose.onNodeWithTag("photo-sort-control").performClick()
        compose.onNodeWithTag("photo-sort-name").performClick()
        assertFirst(highName, lowName)
        compose.onNodeWithTag("photo-sort-control").performClick()
        compose.onNodeWithTag("photo-sort-name").assertIsSelected()
        androidx.test.espresso.Espresso.pressBack()
    }

    private fun choose(tag: String, label: String, scroll: Boolean = true) {
        if (scroll) compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithText(label).performClick()
    }
    private fun assertGrid(description: String) = compose.onNodeWithTag("photo-grid")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
    private fun assertArrangementPreview(description: String) = compose.onNodeWithTag("photo-arrangement-preview")
        .performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
    private fun drawerItem(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("navigation-drawer")))
    private fun bottomNavigation(label: String) = compose.onNode(
        hasText(label) and hasAnyAncestor(hasTestTag("floating-navigation")), useUnmergedTree = true,
    )
    private fun chooseLibraryShortcut(shortcut: String) {
        bottomNavigation("Library").performClick()
        compose.onNodeWithTag("library-shortcut-$shortcut").performClick()
        compose.onNodeWithTag("library-shortcut-strip").assertDoesNotExist()
    }
    private fun assertCurrentView(label: String) = compose.onNodeWithTag("library-current-view").assertTextEquals(label)
    private fun assertCompactHeader() {
        val search = compose.onNodeWithTag("search-open").fetchSemanticsNode().boundsInRoot
        val filters = compose.onNodeWithContentDescription("Metadata filters").fetchSemanticsNode().boundsInRoot
        val view = compose.onNodeWithTag("library-current-view").fetchSemanticsNode().boundsInRoot
        val sort = compose.onNodeWithContentDescription("Sort photos").fetchSemanticsNode().boundsInRoot
        val layout = compose.onNodeWithTag("arrangement-button").fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        assertTrue("Search and filters share one row", search.top < filters.bottom && filters.top < search.bottom)
        assertTrue("View label shares the sorting row", view.center.y >= sort.top && view.center.y <= sort.bottom)
        assertEquals("Sort and layout align in the same row", sort.center.y, layout.center.y, density)
        assertTrue("The unfiltered header stays within two compact rows", layout.bottom - minOf(search.top, filters.top) <= 116f * density)
    }
    private fun openPage(label: String) {
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        drawerItem(label).performClick()
    }
    private fun openSettings() { openPage("Settings"); compose.onNodeWithTag("appearance-dropdown").assertExists() }
    /** Favorites are empty in these tests, so add "Rating ≥ 4" through the Often used rating editor. */
    private fun addFourStarRule() {
        // No Espresso.closeSoftKeyboard(): it waits for the activity window to have focus, which the search
        // workspace dialog holds, and occasionally gave up after 10 s. The steps below tap by semantics.
        compose.onNode(hasText("Rating") and hasAnyAncestor(hasTestTag("search-often-used"))).performScrollTo().performClick()
        compose.onNodeWithTag("rating-chip-4").performClick()
        compose.onNodeWithTag("filter-editor-apply").performClick()
    }
    private fun searchFixtures() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("search-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-open").performClick()
        compose.onNodeWithTag("search-text").performTextReplacement("gallery-v4-$suffix-")
        compose.showSearchResults()
    }
    private fun waitForDescription(name: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(450)
        val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val output = File(context.getExternalFilesDir(null), "qa/$name-${if (dark) "dark" else "light"}.png")
        output.parentFile!!.mkdirs()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }

    private fun seedImage(name: String, rating: Int, color: Int): Uri {
        val resolver = context.contentResolver
        // Publishing a JPEG can replace MediaStore's supplied DATE_TAKEN. Embed distinct
        // capture dates so date-order checks do not depend on creation-time precision.
        val captured = java.time.LocalDate.now().withDayOfMonth(if (rating >= 4) 10 else 11)
            .atTime(12, 0).atZone(java.time.ZoneId.systemDefault())
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, captured.toInstant().toEpochMilli())
            put(MediaStore.Images.Media.WIDTH, 400); put(MediaStore.Images.Media.HEIGHT, 300)
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalleryV4QA/"); put(MediaStore.Images.Media.IS_PENDING, 1) }
        })!!
        try {
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { drawColor(color); drawCircle(290f, 90f, 40f, Paint().apply { this.color = Color.rgb(240, 221, 172) }) }
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            resolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").format(captured))
                    setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, java.time.format.DateTimeFormatter.ofPattern("xxx").format(captured))
                    setAttribute(ExifInterface.TAG_XMP, """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="$rating"/></rdf:RDF></x:xmpmeta>""")
                    saveAttributes()
                }
            }
            if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
    }
}
