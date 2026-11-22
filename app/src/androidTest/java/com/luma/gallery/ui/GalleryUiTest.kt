package com.luma.gallery.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.luma.gallery.MainActivity
import com.luma.gallery.data.LibraryStore
import com.luma.gallery.data.AppPreferences
import com.luma.gallery.data.ColorPalette
import com.luma.gallery.data.ThemeMode
import com.luma.gallery.data.StartPage
import com.luma.gallery.data.PhotoLayout
import com.luma.gallery.data.FavoriteFilterCodec
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp

/** Exercises real MediaStore photos through the activity, gallery, viewer, filters and albums. */
class GalleryUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    private val preferencesRule = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val preferences = AppPreferences(context)
            val originalTheme = preferences.themeMode()
            val originalPalette = preferences.colorPalette()
            val originalPureBlack = preferences.pureBlack()
            val originalStartPage = preferences.startPage()
            val originalLayout = preferences.photoLayout()
            val originalColumns = preferences.photoColumns()
            val originalFolderLayout = preferences.folderLayout()
            val originalFavorites = preferences.favoriteFilters()
            // The ViewModel reads these during activity launch, before @Before runs.
            preferences.setThemeMode(ThemeMode.SYSTEM)
            preferences.setColorPalette(ColorPalette.SAGE)
            preferences.setPureBlack(false)
            preferences.setStartPage(StartPage.LIBRARY)
            preferences.setPhotoLayout(PhotoLayout.GRID)
            preferences.setPhotoColumns(3)
            preferences.setFolderLayout(PhotoLayout.GRID)
            preferences.setFavoriteFilters(FavoriteFilterCodec.defaults())
            val originalOnlineMap = preferences.onlineMap()
            preferences.setOnlineMap(true)
            try { base.evaluate() } finally {
                if (originalOnlineMap == null) preferences.forgetOnlineMapChoice() else preferences.setOnlineMap(originalOnlineMap)
                preferences.setThemeMode(originalTheme)
                preferences.setColorPalette(originalPalette)
                preferences.setPureBlack(originalPureBlack)
                preferences.setStartPage(originalStartPage)
                preferences.setPhotoLayout(originalLayout)
                preferences.setPhotoColumns(originalColumns)
                preferences.setFolderLayout(originalFolderLayout)
                preferences.setFavoriteFilters(originalFavorites)
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(preferencesRule).around(compose)
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val images = mutableListOf<Uri>()
    private val albumName = "Gallery QA collection"
    @Before fun seedLibrary() {
        context.getSharedPreferences("luma-map", Context.MODE_PRIVATE).edit().putString("basemap", "SIMPLE").commit()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        else automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 29) automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
        images += seedImage("gallery-qa-alpine.jpg", 5, 0)
        images += seedImage("gallery-qa-coast.jpg", 2, 1)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("gallery-qa-alpine.jpg").fetchSemanticsNodes().isNotEmpty() }
        searchQaPhotos()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("2 PHOTOS").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanLibrary() {
        LibraryStore(context).use { store ->
            store.albums().filter { it.name == albumName }.forEach { store.deleteAlbum(it.id) }
            images.forEach { store.setRating(it.toString(), null) }
        }
        images.forEach { context.contentResolver.delete(it, null, null) }
    }

    @Test fun embeddedRatingFiltersViewerAndVirtualAlbumsWorkTogether() {
        // Wait until GPS/rating indexing has reached the first photo.
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Has GPS location").fetchSemanticsNodes().isNotEmpty() }
        capture("library")
        applyFourStarFavorite()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 PHOTO").fetchSemanticsNodes().isNotEmpty() }
        // The rating and the GPS pin share one line at the bottom of the tile.
        val rating = compose.onNodeWithTag("tile-rating", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val gps = compose.onNodeWithContentDescription("Has GPS location", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(gps.center.y, rating.center.y, 3f)
        // The library header shows the active filter as a pill with its count.
        compose.onNodeWithTag("library-filter-button").assert(hasText("1"))
        compose.onNodeWithContentDescription("gallery-qa-alpine.jpg").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("viewer-more").fetchSemanticsNodes().isNotEmpty() }
        // Gallery no longer sets its own ratings; the file's rating is shown read-only in the details.
        compose.onNodeWithTag("viewer-rate").assertDoesNotExist()
        capture("viewer")
        compose.onNodeWithTag("viewer-details-handle").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("details-rating").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("5 / 5 · stored in the file").assertExists()
        compose.onNodeWithContentDescription("Close details").performClick()
        compose.onNodeWithTag("viewer-more").performClick()
        compose.onNodeWithTag("viewer-menu-album").performClick()
        compose.onNodeWithText("Create new album").performClick()
        compose.onNode(hasSetTextAction() and hasText("Album name")).performTextInput(albumName)
        compose.onNodeWithText("Create & add").performClick()
        compose.waitUntil(10_000) { LibraryStore(context).use { store -> store.albums().any { it.name == albumName && images[0].toString() in it.photoUris } } }
        compose.onNodeWithContentDescription("Close photo").performClick()
        bottomNavigation("Albums").performClick()
        compose.onNodeWithText(albumName).performClick()
        compose.onNodeWithContentDescription("gallery-qa-alpine.jpg").assertExists()
        capture("album")
    }

    @Test fun searchFiltersAndMapAreReachable() {
        compose.onNodeWithTag("search-open").performClick()
        compose.onNodeWithTag("all-filters-button").performClick()
        compose.onNodeWithTag("all-filters-page").assertIsDisplayed()
        capture("filters")
        androidx.test.espresso.Espresso.pressBack()
        chooseFourStarFavorite()
        compose.showSearchResults()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("1 PHOTO").fetchSemanticsNodes().isNotEmpty() }
        bottomNavigation("Map").performClick()
        compose.onNodeWithText("Your photo atlas").assertDoesNotExist()
        compose.waitUntil(35_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Map ready")).fetchSemanticsNodes().isNotEmpty() }
        capture("map")
        // The embedded map is a card set apart from the search field and aligned with its edges.
        val search = compose.onNodeWithTag("library-search").fetchSemanticsNode().boundsInRoot
        val map = compose.onNodeWithTag("photo-map").fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("The map must not touch the search field", map.top - search.bottom >= 12.dp.toPx())
            assertEquals(search.left, map.left, 1.dp.toPx())
            assertEquals(search.right, map.right, 1.dp.toPx())
        }
        compose.onNodeWithTag("photo-map").performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer-more").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun foldersOpenTheirPhotosAndSwipeUpOpensMetadata() {
        bottomNavigation("Library").performClick()
        compose.onNodeWithTag("library-shortcut-folders").performClick()
        compose.onNodeWithTag("folder-grid").assertExists()
        compose.onNodeWithText("Gallery QA").performClick()
        compose.onNodeWithContentDescription("Back to folders").assertExists()
        compose.onNodeWithContentDescription("gallery-qa-alpine.jpg").performClick()
        android.os.SystemClock.sleep(450)
        // Swiping up reveals the details below the photo.
        compose.onNodeWithTag("photo-viewer-image").performTouchInput { swipeUp(startY = height * .75f, endY = height * .25f) }
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Photo details").fetchSemanticsNodes().isNotEmpty() }
        // Every category starts collapsed.
        compose.onNodeWithTag("photo-details-list").performScrollToNode(hasContentDescription("Expand Exposure"))
        compose.onNodeWithText("Photo details").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Collapse", substring = true).assertCountEquals(0)
        compose.onNodeWithText("ISO").assertDoesNotExist()
        capture("metadata-collapsed")
        compose.onNodeWithContentDescription("Expand Exposure").performClick()
        compose.onNodeWithTag("photo-details-list").performScrollToNode(hasText("ISO"))
        compose.onNodeWithText("ISO").assertExists()
        capture("metadata-exposure")
        compose.onNodeWithContentDescription("Close details").performClick()
        compose.onNodeWithContentDescription("Close photo").performClick()
        compose.onNodeWithContentDescription("Back to folders").performClick()
        capture("folders")
    }

    @Test fun appearanceChoicesApplyAndPersistWithoutAnAppTitle() {
        compose.onNodeWithText("luma").assertDoesNotExist()
        compose.onNodeWithText("Your library.").assertDoesNotExist()
        openSettings()
        compose.onNodeWithTag("appearance-dark").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("gallery-shell").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Dark appearance"))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("gallery-shell").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Dark appearance"))
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("gallery-qa-alpine.jpg").fetchSemanticsNodes().isNotEmpty() }
        searchQaPhotos()
        capture("theme-dark")
        openSettings()
        compose.onNodeWithTag("appearance-light").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("gallery-shell").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Light appearance"))
        capture("theme-light")
        openSettings()
        compose.onNodeWithTag("appearance-system").performScrollTo().performClick()
        // Colours: the choice applies at once, survives a restart and can go back to Sage.
        compose.onNodeWithTag("palette-sage").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("palette-blue").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(ColorPalette.BLUE, AppPreferences(context).colorPalette()) }
        capture("settings-blue")
        compose.activityRule.scenario.recreate()
        openSettings()
        compose.onNodeWithTag("palette-blue").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("palette-sage").performScrollTo().performClick().assertIsSelected()
        // Pure black: switched on and off again, and remembered.
        compose.onNodeWithTag("pure-black").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(AppPreferences(context).pureBlack()) }
        compose.onNodeWithTag("pure-black").performClick().assertIsOff()
        capture("settings")
        compose.onNodeWithContentDescription("Back").performClick()
    }

    @Test fun swipeDrawerOpensCountriesAndCountryPhotosStayTogether() {
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Has GPS location").fetchSemanticsNodes().size >= 2 }
        compose.onNodeWithTag("gallery-shell").performTouchInput {
            swipe(start = Offset(width * .04f, height * .4f), end = Offset(width * .85f, height * .4f), durationMillis = 450)
        }
        compose.onNodeWithTag("navigation-drawer").assertIsDisplayed()
        capture("navigation-drawer")
        drawerItem("Countries").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("countries-screen").fetchSemanticsNodes().isNotEmpty() }
        // Switching destinations clears the previous query, so restrict the new view again.
        searchQaPhotos()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("country-AT").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("country-AT").performClick()
        compose.onNodeWithText("Austria").assertExists()
        compose.onNodeWithContentDescription("gallery-qa-alpine.jpg").assertExists()
        compose.onNodeWithContentDescription("gallery-qa-coast.jpg").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithTag("navigation-drawer").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("navigation-drawer").assertIsNotDisplayed()
        // Android Back closes the drawer without leaving the selected country.
        compose.onNodeWithTag("countries-back").assertIsDisplayed()
        compose.onNodeWithText("Austria").assertExists()
        compose.onNodeWithContentDescription("gallery-qa-alpine.jpg").assertExists()
        compose.onNodeWithContentDescription("gallery-qa-coast.jpg").assertDoesNotExist()
        capture("country-austria")
        compose.onNodeWithContentDescription("gallery-qa-alpine.jpg").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("viewer-more").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 of 1").assertExists()
        compose.onNodeWithTag("photo-viewer-image").performTouchInput { swipeLeft() }
        // The viewer must not leave Austria and advance to the German coast fixture.
        compose.onNodeWithText("1 of 1").assertExists()
        compose.onAllNodesWithContentDescription("gallery-qa-coast.jpg").assertCountEquals(0)
        compose.onNodeWithContentDescription("Close photo").performClick()
        compose.onNodeWithTag("countries-back").performClick()
        compose.onNodeWithTag("country-DE").assertExists()
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        drawerItem("Library").performClick()
        searchQaPhotos()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("2 PHOTOS").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun albumThumbnailCanBeChosenAndResetAndBothChoicesPersist() {
        compose.onNodeWithContentDescription("gallery-qa-alpine.jpg").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("gallery-qa-coast.jpg").performClick()
        compose.onNodeWithContentDescription("Add selection to album").performClick()
        compose.onNodeWithText("Create new album").performClick()
        compose.onNode(hasSetTextAction() and hasText("Album name")).performTextInput(albumName)
        compose.onNodeWithText("Create & add").performClick()
        compose.waitUntil(10_000) {
            LibraryStore(context).use { store -> store.albums().any { it.name == albumName && it.photoUris.containsAll(images.map(Uri::toString)) } }
        }
        // Choosing an existing album happens on a page of album tiles: one tap adds and closes the page.
        compose.onNodeWithContentDescription("gallery-qa-alpine.jpg").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Add selection to album").performClick()
        compose.onNodeWithTag("album-destination-page").assertIsDisplayed()
        compose.onNodeWithTag("album-destination-new").assertIsDisplayed()
        capture("album-destination")
        compose.onNode(hasText(albumName) and hasAnyAncestor(hasTestTag("album-destination-page"))).performClick()
        compose.onNodeWithTag("album-destination-page").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel selection").assertDoesNotExist()
        bottomNavigation("Albums").performClick()
        compose.onNodeWithText(albumName).performClick()
        // Inside an album the one album action is "From album"; "To album" belongs to every other page.
        compose.onNodeWithContentDescription("gallery-qa-coast.jpg").performTouchInput { longClick() }
        compose.onNodeWithTag("selection-remove").assertIsDisplayed()
        compose.onNodeWithTag("selection-album").assertDoesNotExist()
        capture("album-selection")
        compose.onNodeWithContentDescription("Cancel selection").performClick()
        compose.onNodeWithContentDescription("Album options").performClick()
        compose.onNodeWithText("Add home-screen widget").performClick()
        compose.onNodeWithText("Album widget size").assertIsDisplayed()
        capture("widget-sizes")
        compose.onNodeWithText("Cancel").performClick()
        openThumbnailPicker()
        compose.onNode(hasContentDescription("gallery-qa-coast.jpg") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { LibraryStore(context).use { it.albums().first { album -> album.name == albumName }.coverUri == images[1].toString() } }
        compose.onNodeWithContentDescription("Back to albums").performClick()
        capture("album-custom-cover")
        compose.onNodeWithText(albumName).performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Album options").fetchSemanticsNodes().isNotEmpty() }
        LibraryStore(context).use { assertEquals(images[1].toString(), it.albums().first { album -> album.name == albumName }.coverUri) }
        openThumbnailPicker()
        compose.onNode(hasContentDescription("gallery-qa-coast.jpg") and hasAnyAncestor(isDialog())).assert(hasContentDescription("Selected"))
        capture("album-cover-picker")
        compose.onNodeWithText("Reset automatic cover").performClick()
        compose.waitUntil(10_000) { LibraryStore(context).use { it.albums().first { album -> album.name == albumName }.coverUri == null } }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Album options").fetchSemanticsNodes().isNotEmpty() }
        LibraryStore(context).use { assertNull(it.albums().first { album -> album.name == albumName }.coverUri) }
        openThumbnailPicker()
        compose.onNodeWithContentDescription("Selected").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel thumbnail selection").performClick()
    }

    @Test fun foregroundLibraryAutomaticallyAddsAndRemovesMediaStoreImages() {
        compose.onNodeWithContentDescription("Refresh library").assertDoesNotExist()
        compose.onNodeWithText("2 PHOTOS").assertExists()
        val added = seedImage("gallery-qa-new.jpg", 4, 2)
        images += added
        // No refresh, activity recreation, or navigation: the foreground MediaStore observer
        // must pick up this newly published image and retain the current search.
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("gallery-qa-new.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("3 PHOTOS").fetchSemanticsNodes().isNotEmpty() }
        capture("library-auto-refresh")
        context.contentResolver.delete(added, null, null)
        images.remove(added)
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("gallery-qa-new.jpg").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("2 PHOTOS").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun drawerItem(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("navigation-drawer")))

    private fun bottomNavigation(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("floating-navigation")), useUnmergedTree = true)

    private fun openSettings() {
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        drawerItem("Settings").performClick()
        compose.onNodeWithTag("language-dropdown").assertExists()
    }

    private fun searchQaPhotos() {
        compose.onNodeWithTag("search-open").performClick()
        compose.onNodeWithTag("search-text").performTextReplacement("gallery-qa-")
        compose.showSearchResults()
    }

    private fun chooseFourStarFavorite() {
        compose.onNode(hasText("4+ stars") and hasAnyAncestor(hasTestTag("search-favorites")))
            .performScrollTo().performClick()
    }

    private fun applyFourStarFavorite() {
        compose.onNodeWithTag("search-open").performClick()
        chooseFourStarFavorite()
        compose.showSearchResults()
    }

    private fun openThumbnailPicker() {
        compose.onNodeWithContentDescription("Album options").performClick()
        capture("album-settings")
        compose.onNodeWithText("Choose album thumbnail").performClick()
        compose.onNodeWithText("Album thumbnail").assertExists()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Compose settles before Android finishes animating dialog windows.
        android.os.SystemClock.sleep(450)
        val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val output = File(context.getExternalFilesDir(null), "qa/$name-${if (dark) "dark" else "light"}.png")
        output.parentFile!!.mkdirs()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }

    private fun seedImage(name: String, rating: Int, variant: Int): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gallery QA/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis() - variant * 86_400_000L)
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)!!
        val bitmap = Bitmap.createBitmap(900, 1100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, 1100f,
            if (variant == 0) intArrayOf(Color.rgb(111, 161, 174), Color.rgb(230, 225, 189)) else intArrayOf(Color.rgb(223, 171, 130), Color.rgb(89, 154, 169)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 900f, 1100f, paint)
        paint.shader = null
        paint.color = Color.rgb(247, 233, 184)
        canvas.drawCircle(680f, 230f, 75f, paint)
        for (layer in 0..3) {
            paint.color = if (variant == 0) Color.rgb(95 - layer * 15, 128 - layer * 15, 117 - layer * 9) else Color.rgb(77 - layer * 10, 130 - layer * 10, 133 - layer * 8)
            val path = Path().apply {
                moveTo(0f, 650f + layer * 120)
                lineTo(200f + layer * 35, 350f + layer * 140)
                lineTo(470f, 650f + layer * 90)
                lineTo(740f - layer * 45, 410f + layer * 125)
                lineTo(900f, 610f + layer * 130)
                lineTo(900f, 1100f); lineTo(0f, 1100f); close()
            }
            canvas.drawPath(path, paint)
        }
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        resolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_XMP, """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="$rating"/></rdf:RDF></x:xmpmeta>""")
                setAttribute(ExifInterface.TAG_MAKE, "Gallery")
                setAttribute(ExifInterface.TAG_MODEL, "Test Camera")
                setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")
                setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "200")
                setLatLong(47.2692 + variant, 11.4041 + variant)
                saveAttributes()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}

