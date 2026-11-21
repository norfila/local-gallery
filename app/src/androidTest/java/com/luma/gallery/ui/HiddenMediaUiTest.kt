package com.luma.gallery.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.luma.gallery.GalleryViewModel
import androidx.test.espresso.Espresso
import com.luma.gallery.MainActivity
import com.luma.gallery.data.AllFilesAccess
import com.luma.gallery.data.AppPreferences
import com.luma.gallery.data.StartPage
import com.luma.gallery.data.ThemeMode
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

private const val HIDDEN_AUTHORITY = "com.luma.gallery.test.hidden"
private val HIDDEN_CONTROL = Uri.parse("content://$HIDDEN_AUTHORITY.control")

class HiddenMediaUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val preferences = AppPreferences(context)
            val theme = preferences.themeMode()
            val start = preferences.startPage()
            val hidden = preferences.showHiddenMedia()
            val folders = preferences.hiddenMediaFolders()
            try {
                preferences.setThemeMode(ThemeMode.SYSTEM)
                preferences.setStartPage(StartPage.LIBRARY)
                preferences.setShowHiddenMedia(false)
                preferences.setHiddenMediaFolders(emptyList())
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                if (Build.VERSION.SDK_INT >= 33) {
                    automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
                    automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
                } else automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
                base.evaluate()
            } finally {
                preferences.setThemeMode(theme)
                preferences.setStartPage(start)
                preferences.setShowHiddenMedia(hidden)
                preferences.setHiddenMediaFolders(folders)
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixtures).around(compose)

    @Test fun hiddenPageStartsOffAndSettingsAddItToTheSideMenuAcrossRecreation() {
        openDrawer()
        hiddenLink().assertDoesNotExist()
        Espresso.pressBack()
        openSettings()
        compose.onNodeWithTag("show-hidden-media").performScrollTo().assertIsOff()
        assertFalse(AppPreferences(context).showHiddenMedia())
        compose.onNodeWithTag("show-hidden-media").performClick().assertIsOn()
        assertTrue(AppPreferences(context).showHiddenMedia())
        compose.runOnIdle { assertTrue(model().showHiddenMedia) }
        // Access is set up on the Hidden page itself, not in Settings.
        compose.onNodeWithTag("add-hidden-media-folder").assertDoesNotExist()
        capture("hidden-media-settings")
        Espresso.pressBack()

        openDrawer()
        hiddenLink().performClick()
        assertOnEmptyHiddenPage()
        capture("hidden-setup")

        compose.activityRule.scenario.recreate()
        assertOnEmptyHiddenPage()
        openSettings()
        compose.onNodeWithTag("show-hidden-media").performScrollTo().assertIsOn().performClick().assertIsOff()
        assertFalse(AppPreferences(context).showHiddenMedia())
        compose.runOnIdle { assertFalse(model().showHiddenMedia) }
        Espresso.pressBack()
        // Switching the page off leaves it for the library and removes it from the menu.
        compose.onNodeWithTag("hidden-setup").assertDoesNotExist()
        compose.onNodeWithTag("library-current-view").assertTextEquals("All items")
        openDrawer()
        hiddenLink().assertDoesNotExist()
    }

    @Test fun hiddenMediaAppearsOnlyOnTheHiddenPage() {
        seedFixture()
        try {
            compose.runOnIdle {
                model().addHiddenMediaFolder(DocumentsContract.buildTreeDocumentUri(HIDDEN_AUTHORITY, "root").toString())
                model().changeShowHiddenMedia(true)
            }
            compose.waitUntil(20_000) { model().photos.count { it.hidden && Uri.parse(it.uri).authority == HIDDEN_AUTHORITY } == 7 }
            compose.waitUntil(20_000) { !model().indexing }

            openDrawer()
            compose.onNode(hasText("Folders") and hasAnyAncestor(hasTestTag("navigation-drawer"))).performClick()
            compose.onNodeWithTag("folder-grid").assertExists()
            compose.onAllNodesWithText("Private").assertCountEquals(0)
            compose.onAllNodesWithText(".thumbnails").assertCountEquals(0)

            openDrawer()
            hiddenLink().performClick()
            compose.onNodeWithTag("library-current-view").assertTextEquals("Hidden")
            compose.onNodeWithTag("hidden-manage").assertIsDisplayed()
            // Other hidden folders on the device can share these names, so the fixture's are found by key.
            val masked = "folder-document:$HIDDEN_AUTHORITY:masked/"
            compose.onNodeWithTag("folder-grid").performScrollToNode(hasTestTag(masked))
            compose.onNodeWithTag(masked).assertIsDisplayed()
            compose.onNodeWithTag("folder-grid").performScrollToNode(hasTestTag("folder-document:$HIDDEN_AUTHORITY:dot/"))
            capture("hidden-folders")
            compose.onNodeWithTag("folder-grid").performScrollToNode(hasTestTag(masked))
            compose.onNodeWithTag(masked).performClick()
            compose.onNodeWithContentDescription("photo.jpg").assertExists()
            compose.onNodeWithTag("collection-back").performClick()
            compose.onNodeWithTag("folder-grid").assertExists()

            // Access settings live in a sheet on the page.
            compose.onNodeWithTag("hidden-manage").performClick()
            compose.onNodeWithTag("hidden-access-sheet").assertIsDisplayed()
            compose.onNodeWithTag("add-hidden-media-folder").assertExists()
            capture("hidden-access-sheet")
            Espresso.pressBack()
            compose.onNodeWithTag("hidden-access-sheet").assertDoesNotExist()

            // Back leaves the Hidden page for the library.
            Espresso.pressBack()
            compose.onNodeWithTag("library-current-view").assertTextEquals("All items")
        } finally {
            context.contentResolver.call(HIDDEN_CONTROL, "revoke", null, null)
        }
    }

    private fun assertOnEmptyHiddenPage() {
        if (AllFilesAccess.granted(context)) compose.onNodeWithTag("library-current-view").assertTextEquals("Hidden")
        else {
            // Without any access the page explains both ways in.
            compose.onNodeWithTag("hidden-setup").assertIsDisplayed()
            compose.onNodeWithTag("allow-all-files").assertIsDisplayed()
            compose.onNodeWithTag("hidden-choose-folder").assertIsDisplayed()
        }
    }

    private fun model() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]

    private fun hiddenLink() = compose.onNode(hasText("Hidden") and hasAnyAncestor(hasTestTag("navigation-drawer")))

    private fun openDrawer() {
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.waitForIdle()
    }

    /** The test APK's documents provider serves a small tree of .nomedia and dot folders. */
    private fun seedFixture() {
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        val bytes = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray() } finally { bitmap.recycle() }
        context.contentResolver.call(HIDDEN_CONTROL, "seed", context.packageName, Bundle().apply { putByteArray("bytes", bytes) })
    }

    private fun openSettings() {
        openDrawer()
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag("navigation-drawer"))).performClick()
        compose.onNodeWithTag("settings-page").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val output = File(context.getExternalFilesDir(null), "qa/$name-${if (dark) "dark" else "light"}.png")
        output.parentFile!!.mkdirs()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }
}
