package com.luma.gallery.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.luma.gallery.GalleryViewModel
import com.luma.gallery.MainActivity
import com.luma.gallery.data.*
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Search uses one live query; saved criteria stay separate from the typed search. */
class SearchWorkspaceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "gallery-search-${UUID.randomUUID().toString().take(8)}-"
    private val media = mutableListOf<Uri>()
    private lateinit var model: GalleryViewModel
    private val ratingFavorite = FavoriteFilter(31, "Highly rated", PhotoQuery(rules = listOf(FilterRule(0, MetadataField.RATING, CompareOp.GREATER_OR_EQUAL, 4.0))))
    private val pixelFavorite = FavoriteFilter(32, "Pixel camera", PhotoQuery(camera = "Google Pixel 10"))
    private val canonFavorite = FavoriteFilter(33, "Canon camera", PhotoQuery(camera = "Canon EOS R6"))
    private val gpsFavorite = FavoriteFilter(34, "With GPS", PhotoQuery(hasLocation = true))

    private val fixtures = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val preferences = AppPreferences(context)
            val theme = preferences.themeMode()
            val start = preferences.startPage()
            val favorites = preferences.favoriteFilters()
            val layout = preferences.photoLayout()
            try {
                // Fixtures are found by their thumbnails, so use the grid regardless of the device's saved layout.
                preferences.setPhotoLayout(PhotoLayout.GRID)
                preferences.setThemeMode(ThemeMode.SYSTEM)
                preferences.setStartPage(StartPage.LIBRARY)
                preferences.setFavoriteFilters(listOf(ratingFavorite, pixelFavorite, canonFavorite, gpsFavorite))
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                if (Build.VERSION.SDK_INT >= 33) {
                    automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
                    automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
                } else automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT >= 29) automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
                media += seedImage("high", 5, 100, "Google", "Pixel 10", true)
                media += seedImage("middle", 3, 800, "Google", "Pixel 10", false)
                media += seedImage("unrated", 0, 1600, "Canon", "EOS R6", true)
                base.evaluate()
            } finally {
                media.forEach { context.contentResolver.delete(it, null, null) }
                preferences.setThemeMode(theme)
                preferences.setStartPage(start)
                preferences.setFavoriteFilters(favorites)
                preferences.setPhotoLayout(layout)
            }
        }
    } }
    private val diagnostics = TestRule { base, description -> object : Statement() {
        override fun evaluate() {
            try { base.evaluate() } catch (failure: Throwable) {
                runCatching {
                    capture("search-failure-${description.methodName}")
                    val output = File(context.getExternalFilesDir(null), "qa/search-failure-${description.methodName}.txt")
                    val page = if (compose.onAllNodesWithTag("all-filters-page").fetchSemanticsNodes().isNotEmpty()) "all-filters-page" else "search-workspace"
                    output.writeText(compose.onNodeWithTag(page, useUnmergedTree = true).printToString(maxDepth = 16))
                    if (::model.isInitialized) output.appendText("\nFIXTURES\n" + model.photos.filter { it.name.startsWith(prefix) }.joinToString("\n") { "${it.name}: ${it.camera}, rating=${it.rating}, ISO=${it.iso}, gps=${it.hasLocation}" })
                }
                throw failure
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixtures).around(compose).around(diagnostics)

    @Test fun directlyOpenedFiltersReturnToTheLibraryForBothBackActionsAndKeepTheQuery() {
        openFixtureSearch()
        compose.showSearchResults()
        compose.onNodeWithContentDescription("Metadata filters", substring = true).performClick()
        compose.onNodeWithTag("all-filters-page").assertIsDisplayed()
        compose.onNodeWithText("Text search: $prefix").assertExists()
        addIsoAtMost800()
        expectCount("2 photos")
        compose.onNodeWithContentDescription("Back to previous screen").performClick()
        assertLibraryHasFilteredFixtures()

        compose.onNodeWithContentDescription("Metadata filters", substring = true).performClick()
        expectCount("2 photos")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("all-filters-page").assertIsDisplayed()
        Espresso.pressBack()
        assertLibraryHasFilteredFixtures()

        compose.onNodeWithTag("search-open").performClick()
        compose.onNodeWithTag("search-text").assertTextEquals(prefix)
        hideKeyboardInDialog()
        expectCount("2 photos")
        compose.onNodeWithTag("all-filters-link").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back to search").performClick()
        compose.onNodeWithTag("search-workspace").assertIsDisplayed()
        expectCount("2 photos")
    }

    @Test fun textAndCatalogShareLiveResultsAndBackRetainsBothSelections() {
        openFixtureSearch()
        expectCount("3 photos")
        favorite("With GPS")
        expectCount("2 photos")
        compose.onNodeWithTag("search-often-used").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("all-filters-button").performClick()
        compose.onNodeWithTag("all-filters-page").assertIsDisplayed()
        compose.onNodeWithText("Text search: $prefix").assertExists()
        addIsoAtMost800()
        expectCount("1 photo")
        compose.onNodeWithContentDescription("Back to search").performClick()
        hideKeyboardInDialog()
        compose.onNodeWithTag("search-text").assertTextEquals(prefix)
        expectCount("1 photo")
        capture("search-workspace")
        Espresso.pressBack()
        compose.onNodeWithTag("search-workspace").assertDoesNotExist()
        compose.onNodeWithText("1 PHOTO").assertExists()
        compose.onNodeWithTag("search-open").performClick()
        expectCount("1 photo")
        compose.onNodeWithTag("all-filters-button").performClick()
        compose.onNodeWithTag("filter-category-camera").assertExists()
        compose.onNodeWithTag("selected-filter-rule-1").assertExists()
        capture("search-all-filters")
        Espresso.pressBack()
        hideKeyboardInDialog()
        compose.onNodeWithTag("search-workspace").assertIsDisplayed()
        compose.showSearchResults()
        compose.onNodeWithContentDescription("${prefix}high.jpg").assertExists()
        compose.onNodeWithContentDescription("${prefix}middle.jpg").assertDoesNotExist()
        compose.onNodeWithContentDescription("${prefix}unrated.jpg").assertDoesNotExist()
    }

    @Test fun favoritesAddDeduplicateAndSaveSingleOrCombinedCriteriaWithoutCapturingText() {
        openFixtureSearch()
        favorite("With GPS")
        expectCount("2 photos")
        favorite("Highly rated")
        expectCount("1 photo")
        favorite("Highly rated")
        expectSelectedFilters(2)
        compose.onNodeWithTag("search-text").assertTextEquals(prefix)
        compose.onNodeWithTag("favorite-selected-filters").performScrollTo().performClick()
        saveFavorite("Best with GPS")
        val combined = AppPreferences(context).favoriteFilters().single { it.name == "Best with GPS" }.query
        assertEquals(1, combined.rules.size)
        assertEquals(true, combined.hasLocation)
        assertEquals("", combined.search)

        compose.onNodeWithTag("all-filters-button").performClick()
        openIsoEditor()
        chooseAtMost800()
        compose.onNodeWithContentDescription("Favorite this filter").performClick()
        saveFavorite("Low ISO")
        val single = AppPreferences(context).favoriteFilters().single { it.name == "Low ISO" }.query
        assertEquals(1, single.rules.size)
        assertEquals(MetadataField.ISO, single.rules.single().field)
        assertEquals(CompareOp.LESS_OR_EQUAL, single.rules.single().op)
        assertNull(single.hasLocation)
        assertEquals("", single.search)
        compose.onNodeWithContentDescription("Close filter editor").performClick()
        expectCount("1 photo")
        expectSelectedFilters(2)
        compose.onNodeWithTag("clear-selected-filters").performClick()
        expectCount("3 photos")
        compose.onNodeWithContentDescription("Back to search").performClick()
        compose.showSearchResults()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("search-open").performClick()
        compose.onNodeWithContentDescription("Add favorite Best with GPS").assertExists()
        favorite("Low ISO")
        expectCount("2 photos")
        compose.onNodeWithTag("search-text").assertTextEquals(prefix)
    }

    @Test fun selectedFiltersShowTheirCountAndClearAllAtOnceKeepingTheText() {
        openFixtureSearch()
        compose.onNodeWithTag("search-quick-filters").assertDoesNotExist()
        compose.onNodeWithTag("clear-selected-filters").assertDoesNotExist()
        favorite("With GPS")
        favorite("Pixel camera")
        expectCount("1 photo")
        compose.onNodeWithTag("selected-filters-count").assertContentDescriptionEquals("2 selected filters")
        compose.onNodeWithTag("all-filters-button").assert(hasText("2"))
        capture("search-selected-filters")
        compose.onNodeWithTag("clear-selected-filters").performClick()
        expectCount("3 photos")
        expectSelectedFilters(0)
        compose.onNodeWithTag("selected-filters-count").assertDoesNotExist()
        compose.onNodeWithTag("search-text").assertTextEquals(prefix)
        // All filters has the same header action and no separate footer button.
        favorite("Highly rated")
        compose.onNodeWithTag("all-filters-button").performClick()
        compose.onNodeWithText("Clear filters").assertDoesNotExist()
        compose.onNodeWithTag("clear-selected-filters").performClick()
        expectCount("3 photos")
        compose.onNodeWithText("Text search: $prefix").assertExists()
    }

    @Test fun conflictingFavoriteRequiresAChoiceAndKeepsTypedSearchAndOtherCriteria() {
        openFixtureSearch()
        favorite("With GPS")
        favorite("Pixel camera")
        expectCount("1 photo")
        favorite("Canon camera")
        compose.onNodeWithText("Replace Camera?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        expectCount("1 photo")
        compose.onNodeWithTag("search-text").assertTextEquals(prefix)
        favorite("Canon camera")
        compose.onNodeWithText("Replace").performClick()
        expectCount("1 photo")
        expectSelectedFilters(2)
        compose.showSearchResults()
        compose.onNodeWithContentDescription("${prefix}unrated.jpg").assertExists()
        compose.onNodeWithContentDescription("${prefix}high.jpg").assertDoesNotExist()
        assertFalse(AppPreferences(context).favoriteFilters().any { it.name.isBlank() })
    }

    @Test fun footerReportsPhotoAndVideoCountsWithoutRepeatingTheResultSummary() {
        openFixtureSearch()
        val clip = createVideoFixture(context).also { media += it }
        context.contentResolver.update(clip, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${prefix}clip.mp4")
        }, null, null)
        compose.waitUntil(30_000) { model.photos.count { it.name.startsWith(prefix) && it.metadataLoaded } == 4 }
        expectCount("3 photos · 1 video")
        compose.onNodeWithText("3 photos · 1 video").assertDoesNotExist()
        favorite("With GPS")
        expectCount("2 photos · 1 video")
        compose.onNodeWithTag("all-filters-button").performClick()
        expectCount("2 photos · 1 video")
        capture("search-mixed-media-footer")
        compose.onNodeWithContentDescription("Back to search").performClick()
        compose.onNodeWithTag("search-text").performTextReplacement("${prefix}clip")
        hideKeyboardInDialog()
        expectCount("1 video")
        compose.onNodeWithTag("search-text").performTextReplacement("${prefix}missing")
        hideKeyboardInDialog()
        expectCount("0 photos")
    }

    @Test fun favoritesAreRenamedWithoutARemoveButtonAndDeletedFromTheirManager() {
        openFixtureSearch()
        favorite("Highly rated")
        expectCount("1 photo")
        compose.onNodeWithContentDescription("Manage favorites").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Delete favorite Highly rated").assertIsDisplayed()
        capture("search-favorite-manager")
        compose.onNodeWithContentDescription("Edit favorite Highly rated").performClick()
        compose.onNode(hasText("Remove") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithTag("favorite-name").assert(hasText("Highly rated"))
        capture("search-edit-favorite")
        saveFavorite("Top picks")
        val renamed = AppPreferences(context).favoriteFilters().single { it.id == ratingFavorite.id }
        assertEquals("Top picks", renamed.name)
        assertEquals(ratingFavorite.query, renamed.query)
        compose.onNodeWithContentDescription("Manage favorites").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Delete favorite Top picks").performClick()
        compose.waitUntil(10_000) { AppPreferences(context).favoriteFilters().none { it.id == ratingFavorite.id } }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Add favorite Top picks").assertDoesNotExist()
        expectCount("1 photo")
        compose.showSearchResults()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("search-open").performClick()
        compose.onNodeWithContentDescription("Add favorite Top picks").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add favorite Highly rated").assertDoesNotExist()
        expectCount("1 photo")
    }

    private fun openFixtureSearch() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[GalleryViewModel::class.java] }
        compose.waitUntil(30_000) { model.photos.count { it.name.startsWith(prefix) && it.metadataLoaded } == 3 }
        compose.onNodeWithTag("search-open").performClick()
        compose.onNodeWithTag("search-workspace").assertIsDisplayed()
        compose.onNodeWithTag("search-text").performTextReplacement(prefix)
        capture("search-keyboard")
        hideKeyboardInDialog()
        expectCount("3 photos")
    }

    private fun assertLibraryHasFilteredFixtures() {
        compose.onNodeWithTag("all-filters-page").assertDoesNotExist()
        compose.onNodeWithTag("search-workspace").assertDoesNotExist()
        compose.onNodeWithContentDescription("${prefix}high.jpg").assertExists()
        compose.onNodeWithContentDescription("${prefix}middle.jpg").assertExists()
        compose.onNodeWithContentDescription("${prefix}unrated.jpg").assertDoesNotExist()
    }

    private fun favorite(name: String) = compose.onNodeWithContentDescription("Add favorite $name").performScrollTo().performClick()
    private fun expectCount(label: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("search-result-count") and hasText("Show $label"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("search-result-count", useUnmergedTree = true).assertCountEquals(1)
    }
    private fun expectSelectedFilters(count: Int) {
        compose.onAllNodes(SemanticsMatcher("a selected filter") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("selected-filter-") == true
        }).assertCountEquals(count)
        compose.onNodeWithText("$count filters · text search").assertDoesNotExist()
    }
    private fun openIsoEditor() {
        compose.onNodeWithTag("filter-category-camera").performScrollTo().performClick()
        compose.onNodeWithTag("filter-field-ISO").performScrollTo().performClick()
        compose.onNodeWithTag("filter-editor").assertIsDisplayed()
    }
    private fun chooseAtMost800() {
        compose.onNodeWithTag("filter-editor-comparison").performClick()
        compose.onNodeWithText("≤  Less than or equal to").performClick()
        compose.onNodeWithTag("filter-editor-value").performTextReplacement("800")
        hideKeyboardInDialog()
    }
    private fun addIsoAtMost800() {
        openIsoEditor()
        chooseAtMost800()
        capture("search-criterion-editor")
        compose.onNodeWithTag("filter-editor-apply").performClick()
    }
    private fun saveFavorite(name: String) {
        compose.onNodeWithTag("favorite-name").performTextReplacement(name)
        compose.onNode(hasText("Save favorite") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { AppPreferences(context).favoriteFilters().any { it.name == name } }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val output = File(context.getExternalFilesDir(null), "qa/$name-${if (dark) "dark" else "light"}.png")
        output.parentFile!!.mkdirs()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { image.recycle() }
    }

    private fun seedImage(label: String, rating: Int, iso: Int, make: String, camera: String, gps: Boolean): Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$prefix$label.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.WIDTH, 640); put(MediaStore.Images.Media.HEIGHT, 480)
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GallerySearchQA/"); put(MediaStore.Images.Media.IS_PENDING, 1) }
        })!!
        try {
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (rating >= 4) Color.rgb(107, 148, 126) else Color.rgb(158, 127, 107))
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            resolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_MAKE, make)
                    setAttribute(ExifInterface.TAG_MODEL, camera)
                    setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
                    setAttribute(ExifInterface.TAG_XMP, """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="$rating"/></rdf:RDF></x:xmpmeta>""")
                    if (gps) setLatLong(48.2, 16.37)
                    saveAttributes()
                }
            }
            if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) { resolver.delete(uri, null, null); throw failure }
    }
}
