package com.luma.gallery.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.luma.gallery.GalleryViewModel
import com.luma.gallery.MainActivity
import com.luma.gallery.data.AppPreferences
import com.luma.gallery.data.PhotoLayout
import com.luma.gallery.data.StartPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Exercises the real activity against this run's own published JPEG, never existing user files. */
@RunWith(AndroidJUnit4::class)
class PhotoActionsUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString().take(8)
    private val photoName = "gallery-actions-$suffix.jpg"
    private val sourcePath = "Pictures/GalleryActions-$suffix/Source/"
    private val destinationName = "GalleryActions-$suffix-Moved"
    private val destinationPath = "Pictures/$destinationName/"
    // A second photo in its own folder, so a selection can be moved out of two folders at once.
    private val secondName = "gallery-actions-$suffix-second.jpg"
    private val secondPath = "Pictures/GalleryActions-$suffix/Second/"
    private lateinit var second: Uri
    private val media = mutableListOf<Uri>()
    private lateinit var source: Uri
    private lateinit var originalBytes: ByteArray
    private lateinit var model: GalleryViewModel

    private val fixtures = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            val preferences = AppPreferences(context)
            val start = preferences.startPage()
            val columns = preferences.photoColumns()
            val folderColumns = preferences.folderColumns()
            val folderLayout = preferences.folderLayout()
            val roundedFolders = preferences.roundedFolderCorners()
            val layout = preferences.photoLayout()
            val favorites = preferences.favoriteFilters()
            try {
                preferences.setStartPage(StartPage.LIBRARY)
                preferences.setPhotoColumns(3)
                preferences.setFolderColumns(2)
                preferences.setFolderLayout(PhotoLayout.GRID)
                preferences.setRoundedFolderCorners(true)
                preferences.setPhotoLayout(PhotoLayout.GRID)
                preferences.setFavoriteFilters(emptyList())
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                if (Build.VERSION.SDK_INT >= 33) {
                    automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
                    automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
                } else automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
                source = seedPhoto(photoName, sourcePath)
                second = seedPhoto(secondName, secondPath)
                base.evaluate()
            } finally {
                media.forEach { context.contentResolver.delete(it, null, null) }
                media.clear()
                preferences.setStartPage(start)
                preferences.setPhotoColumns(columns)
                preferences.setFolderColumns(folderColumns)
                preferences.setFolderLayout(folderLayout)
                preferences.setRoundedFolderCorners(roundedFolders)
                preferences.setPhotoLayout(layout)
                preferences.setFavoriteFilters(favorites)
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixtures).around(compose)

    @Test fun viewerMoveCanBeCancelledThenMovesOriginalIntoNewFolderWithoutChangingBytesOrUri() {
        openFixture()
        compose.onNodeWithContentDescription(photoName).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("viewer-edit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("viewer-edit").assertIsDisplayed()
        openMoveDialog()
        enterNewDestination()
        capture("move-photo")
        // Cancel in the name dialog returns to the folder page; its cross then leaves without moving anything.
        compose.onNode(hasText("Cancel") and hasAnyAncestor(hasTestTag("move-new-folder-dialog"))).performClick()
        compose.onNodeWithTag("move-new-folder-dialog").assertDoesNotExist()
        compose.onNode(hasContentDescription("Cancel") and hasAnyAncestor(hasTestTag("move-photo-dialog"))).performClick()
        waitForMoveDialogToClose()
        assertEquals(sourcePath, relativePath())
        assertArrayEquals(originalBytes, originalContents())

        openMoveDialog()
        enterNewDestination()
        compose.onNodeWithTag("confirm-new-folder").performClick()
        waitForMoveDialogToClose()
        compose.waitUntil(20_000) {
            model.photos.any { it.uri == source.toString() && it.folderKey == "external_primary:$destinationPath" }
        }
        assertEquals(destinationPath, relativePath())
        assertArrayEquals(originalBytes, originalContents())
        compose.runOnIdle {
            val moved = model.photos.single { it.uri == source.toString() }
            assertEquals(destinationName, moved.folder)
            assertEquals(photoName, moved.name)
        }

        compose.onNodeWithContentDescription("Close photo").performClick()
        libraryFolders()
        compose.onNodeWithTag("folder-external_primary:$destinationPath").assertIsDisplayed()
        compose.onNodeWithText(destinationName).assertExists()
        compose.onNodeWithTag("folder-external_primary:$sourcePath").assertDoesNotExist()
    }

    @Test fun selectionMovesIntoATappedFolderAndUndoPutsEachPhotoBack() {
        openFixture("gallery-actions-$suffix")
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription(secondName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(photoName).performTouchInput { longClick() }
        compose.onNodeWithContentDescription(secondName).performClick()
        compose.onNodeWithText("2 selected").assertExists()
        // The actions sit in a labelled bar where the navigation was.
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        listOf("selection-share", "selection-album", "selection-move", "selection-delete").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onNodeWithTag("selection-remove").assertDoesNotExist()
        capture("selection-bar")

        compose.onNodeWithContentDescription("Move selection to folder").performClick()
        compose.onNodeWithTag("move-subject").assertTextEquals("2 items")
        // Coming from two folders, both stay on offer: part of the selection is not there yet.
        compose.onNodeWithTag("move-folder-$sourcePath").assertExists()
        compose.onNodeWithTag("move-folder-$secondPath").assertExists()
        compose.onNodeWithTag("move-mixed-note").assertDoesNotExist()
        // The page shows the folders that hold media as tiles, with "New folder" first: no empty Pictures or
        // DCIM/Camera. One tap on a folder moves the files there, as a tap on an album adds to it.
        compose.onNodeWithTag("move-new-folder").assertIsDisplayed()
        compose.onNodeWithTag("move-folder-Pictures/").assertDoesNotExist()
        compose.onNodeWithTag("move-folder-DCIM/Camera/").assertDoesNotExist()
        capture("move-selection")
        compose.onNodeWithTag("move-folder-$secondPath").performScrollTo().performClick()
        waitForMoveDialogToClose()
        compose.waitUntil(20_000) {
            listOf(source, second).all { uri -> model.photos.any { it.uri == uri.toString() && it.folderKey == "external_primary:$secondPath" } }
        }
        assertEquals(secondPath, relativePath())
        assertEquals(secondPath, relativePath(second))
        assertArrayEquals(originalBytes, originalContents())
        // The move ends the selection and the navigation is back.
        compose.onNodeWithContentDescription("Cancel selection").assertDoesNotExist()
        compose.onNodeWithTag("selection-bar").assertDoesNotExist()
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()

        // The note that follows offers to undo: each file returns to the folder it came from.
        compose.onNode(hasText("2 items moved to folder") and hasAnyAncestor(hasTestTag("action-note"))).assertExists()
        capture("move-undo-note")
        compose.onNodeWithTag("action-note-action").performClick()
        compose.waitUntil(20_000) {
            model.photos.any { it.uri == source.toString() && it.folderKey == "external_primary:$sourcePath" }
        }
        assertEquals(sourcePath, relativePath())
        assertEquals(secondPath, relativePath(second))
        assertArrayEquals(originalBytes, originalContents())
        compose.onNodeWithTag("action-note").assertDoesNotExist()
    }

    @Test fun folderColumnSettingChangesActualFolderGridAndPersistsAcrossActivityRecreation() {
        openFixture()
        libraryFolders()
        assertFolderColumns(2)
        val before = compose.onNodeWithTag("folder-external_primary:$sourcePath").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag("navigation-drawer"))).performClick()
        compose.onNodeWithTag("folder-columns-choice-3").performScrollTo().performClick()
        // Android 12+: Settings shows whether Android still asks before every move and delete, and leads to its switch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            compose.onNodeWithTag("media-management-access").performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("Move and delete without asking") and hasAnyAncestor(hasTestTag("media-management-access")), useUnmergedTree = true).assertExists()
            capture("settings-media-management")
        }
        compose.onNodeWithContentDescription("Back").performClick()
        assertFolderColumns(3)
        capture("folder-columns")
        val after = compose.onNodeWithTag("folder-external_primary:$sourcePath").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Three columns make folder tiles smaller than two columns", after < before * .8f)
        assertEquals(3, AppPreferences(context).folderColumns())

        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("folder-grid").fetchSemanticsNodes().isNotEmpty() }
        assertFolderColumns(3)
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag("navigation-drawer"))).performClick()
        compose.onNodeWithTag("folder-columns-choice-3").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
    }

    @Test fun folderArrangementSwitchesToListAndFolderInfoShowsThePath() {
        openFixture()
        libraryFolders()
        compose.onNodeWithTag("arrangement-button").performClick()
        compose.onNodeWithTag("folder-layout-sheet").assertExists()
        compose.onNodeWithTag("folder-layout-list").performClick()
        // Masonry is not offered for folders, and the per-row choice disappears in list mode.
        compose.onNodeWithTag("folder-layout-masonry").assertDoesNotExist()
        compose.onNodeWithTag("folder-columns-choice-3").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("folder-grid").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "List"))
        assertEquals(PhotoLayout.LIST, AppPreferences(context).folderLayout())
        // The path is not under the folder name any more; the info button shows it.
        compose.onNodeWithText(sourcePath.trimEnd('/')).assertDoesNotExist()
        compose.onNodeWithTag("folder-external_primary:$sourcePath").performClick()
        compose.onNodeWithTag("folder-info").performClick()
        compose.onNodeWithTag("folder-info-dialog").assertExists()
        compose.onNodeWithText("Internal storage · ${sourcePath.trimEnd('/')}").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Back to folders").performClick()
    }

    private fun openMoveDialog() {
        compose.onNodeWithTag("viewer-more").performClick()
        compose.onNodeWithTag("viewer-menu-move").performClick()
    }

    private fun openFixture(search: String = photoName) {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[GalleryViewModel::class.java] }
        compose.waitUntil(20_000) {
            model.photos.any { it.uri == source.toString() } && compose.onAllNodesWithTag("search-open").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("search-open").performClick()
        compose.onNodeWithTag("search-text").performTextReplacement(search)
        compose.showSearchResults()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription(photoName).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun enterNewDestination() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("move-new-folder").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("move-new-folder").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-new-folder").assertIsNotEnabled()
        compose.onNodeWithTag("move-folder-name").performTextReplacement(destinationName)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("confirm-new-folder").assertIsEnabled()
    }

    private fun waitForMoveDialogToClose() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("move-photo-dialog").fetchSemanticsNodes().isEmpty() }
    }

    private fun libraryFolders() {
        compose.onNode(hasText("Library") and hasAnyAncestor(hasTestTag("floating-navigation")), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("library-shortcut-folders").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("folder-grid").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertFolderColumns(columns: Int) = compose.onNodeWithTag("folder-grid")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Folders, $columns columns"))

    private fun relativePath(uri: Uri = source): String = context.contentResolver.query(uri,
        arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { cursor ->
        assertTrue(cursor.moveToFirst()); cursor.getString(0)
    }

    private fun originalContents(): ByteArray = context.contentResolver.openInputStream(source)!!.use { it.readBytes() }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(450)
        val configuration = context.resources.configuration
        val dark = configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val output = File(context.getExternalFilesDir(null), "qa/$name-${if (dark) "dark" else "light"}.png")
        output.parentFile!!.mkdirs()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }

    private fun seedPhoto(name: String, path: String): Uri {
        originalBytes = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(Color.rgb(82, 132, 106))
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                bitmap.recycle()
            }
            output.toByteArray()
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        media += uri
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(originalBytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
