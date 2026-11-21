package com.luma.gallery.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real files on shared storage, read through "All files access" granted to the app for this test. */
@RunWith(AndroidJUnit4::class)
class HiddenFileScannerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = AppPreferences(context)
    private val root = File(Environment.getExternalStorageDirectory(), "Pictures/GalleryHiddenScan-${UUID.randomUUID().toString().take(8)}")
    private var previousThumbnails = false
    private var previousAccess = ""

    @Before fun grantAndSeed() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        previousAccess = shell("appops get ${context.packageName} MANAGE_EXTERNAL_STORAGE")
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        assumeTrue("All files access could not be granted", AllFilesAccess.granted(context))
        previousThumbnails = preferences.includeThumbnailCaches()
        preferences.setIncludeThumbnailCaches(false)
        HiddenFileScanner.invalidate()
        image(File(root, "Private/secret.jpg"))
        File(root, "Private/.nomedia").apply { parentFile!!.mkdirs(); writeText("") }
        image(File(root, ".dotfolder/inside.jpg"))
        image(File(root, ".thumbnails/123.jpg"))
        image(File(root, "Visible/normal.jpg"))
        File(root, "Private/.trashed-1730000000-binned.jpg").writeBytes(File(root, "Private/secret.jpg").readBytes())
    }

    @After fun clean() {
        root.deleteRecursively()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            preferences.setIncludeThumbnailCaches(previousThumbnails)
            if (!previousAccess.contains("allow")) shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE default")
            HiddenFileScanner.invalidate()
        }
    }

    @Test fun findsNomediaAndDotFoldersButNotOrdinaryBinnedOrCacheFiles() = runBlocking {
        val names = HiddenFileScanner.loadPhotos(context, emptyList(), force = true)
            .filter { it.uri.contains(root.name) }.map { it.name }.toSet()
        assertEquals(setOf("secret.jpg", "inside.jpg"), names)
    }

    @Test fun thumbnailCachesAreIncludedOnlyWhenChosen() = runBlocking {
        preferences.setIncludeThumbnailCaches(true)
        val photos = HiddenFileScanner.loadPhotos(context, emptyList(), force = true).filter { it.uri.contains(root.name) }
        assertTrue(photos.any { it.name == "123.jpg" })
        val secret = photos.single { it.name == "secret.jpg" }
        // Folder keys match MediaStore's, so folders and album links treat hidden files alike.
        assertEquals("external_primary:Pictures/${root.name}/Private/", secret.folderKey)
        assertTrue(secret.uri.startsWith("file://"))
    }

    @Test fun indexedCopiesAreNotDuplicated() = runBlocking {
        val indexed = Photo(1, "content://media/external_primary/images/media/999999", "secret.jpg", "Private",
            "external_primary:Pictures/${root.name}/Private/", 0)
        val names = HiddenFileScanner.loadPhotos(context, listOf(indexed), force = true).filter { it.uri.contains(root.name) }.map { it.name }
        assertFalse(names.contains("secret.jpg"))
    }

    private fun image(file: File) {
        file.parentFile!!.mkdirs()
        file.outputStream().use { Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
