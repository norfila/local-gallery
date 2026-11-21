package com.luma.gallery.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryStoreTest {
    private lateinit var context: Context
    private lateinit var databaseDirectory: File
    private lateinit var store: LibraryStore

    @Before fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        databaseDirectory = File(appContext.cacheDir, "library-test-${UUID.randomUUID()}")
        check(databaseDirectory.mkdirs())
        context = IsolatedDatabaseContext(appContext, databaseDirectory)
        store = LibraryStore(context)
    }

    @After fun tearDown() {
        if (::store.isInitialized) store.close()
        if (::context.isInitialized) SQLiteDatabase.deleteDatabase(context.getDatabasePath("luma_library.db"))
        if (::databaseDirectory.isInitialized) databaseDirectory.delete()
    }

    @Test fun albumLinksAndRatingsSurviveClosingAndReopening() {
        val album = store.createAlbum("  Summer trip  ")
        store.addPhotos(album.id, setOf("content://photos/1", "content://photos/2"))
        store.addPhotos(album.id, setOf("content://photos/1"))
        store.addFolders(album.id, setOf("external_primary:DCIM/Travel/"))
        store.setRating("content://photos/1", 4)
        store.close()
        store = LibraryStore(context)

        assertEquals(listOf(album.copy(photoUris = setOf("content://photos/1", "content://photos/2"), folderKeys = setOf("external_primary:DCIM/Travel/"))), store.albums())
        assertEquals(mapOf("content://photos/1" to 4), store.ratings())
        store.renameAlbum(album.id, " Favorites ")
        assertEquals("Favorites", store.albums().single().name)
    }

    @Test fun removingLinksLeavesTheAlbumAndOtherLinksIntact() {
        val album = store.createAlbum("Selection")
        store.addPhotos(album.id, setOf("content://photos/1", "content://photos/2"))
        store.addFolders(album.id, setOf("external_primary:DCIM/A/", "external_primary:DCIM/B/"))
        store.removePhotos(album.id, setOf("content://photos/1", "content://photos/missing"))
        store.removeFolders(album.id, setOf("external_primary:DCIM/A/"))
        assertEquals(setOf("content://photos/2"), store.albums().single().photoUris)
        assertEquals(setOf("external_primary:DCIM/B/"), store.albums().single().folderKeys)
    }

    @Test fun zeroStarsPersistsAndNullRemovesOnlyTheLocalOverride() {
        store.setRating("content://photos/1", 5)
        store.setRating("content://photos/1", 0)
        store.setRating("content://photos/2", 3)
        store.close()
        store = LibraryStore(context)
        assertEquals(0, store.ratings()["content://photos/1"])
        store.setRating("content://photos/1", null)
        assertFalse(store.ratings().containsKey("content://photos/1"))
        assertEquals(mapOf("content://photos/2" to 3), store.ratings())
        assertThrows(IllegalArgumentException::class.java) { store.setRating("content://photos/2", 6) }
        assertEquals(3, store.ratings()["content://photos/2"])
    }

    @Test fun deletingAlbumCascadesLinksAndKeepsIndependentRatings() {
        val album = store.createAlbum("Temporary")
        store.addPhotos(album.id, setOf("content://photos/1"))
        store.addFolders(album.id, setOf("external_primary:DCIM/Travel/"))
        store.setRating("content://photos/1", 4)
        store.deleteAlbum(album.id)
        assertTrue(store.albums().isEmpty())
        assertEquals(4, store.ratings()["content://photos/1"])

        SQLiteDatabase.openDatabase(context.getDatabasePath("luma_library.db").path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            listOf("album_photos", "album_folders").forEach { table ->
                database.rawQuery("SELECT COUNT(*) FROM $table WHERE album_id = ?", arrayOf(album.id.toString())).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
        }
    }

    @Test fun namesAreUniqueIgnoringCaseAndTrimmedBeforeValidation() {
        val album = store.createAlbum("Travel")
        assertThrows(IllegalArgumentException::class.java) { store.createAlbum("  tRaVeL  ") }
        val other = store.createAlbum("Other")
        assertThrows(IllegalArgumentException::class.java) { store.renameAlbum(other.id, "TRAVEL") }
        assertThrows(IllegalArgumentException::class.java) { store.createAlbum(" \n ") }
        assertThrows(IllegalArgumentException::class.java) { store.createAlbum("x".repeat(121)) }
        assertThrows(IllegalArgumentException::class.java) { store.renameAlbum(album.id, " ") }
        assertEquals(setOf("Travel", "Other"), store.albums().map { it.name }.toSet())
    }

    @Test fun invalidLinkBatchIsRejectedAtomically() {
        val album = store.createAlbum("Selection")
        assertThrows(IllegalArgumentException::class.java) { store.addPhotos(album.id, setOf("content://photos/1", "")) }
        assertTrue(store.albums().single().photoUris.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.addFolders(-1, setOf("external_primary:DCIM/Travel/")) }
        assertTrue(store.albums().single().folderKeys.isEmpty())
    }

    @Test fun chosenCoverPersistsAndResetRestoresAutomaticCover() {
        val album = store.createAlbum("Covers")
        store.addPhotos(album.id, setOf("content://photos/1", "content://photos/2"))
        store.setAlbumCover(album.id, "content://photos/2")
        store.close()
        store = LibraryStore(context)
        assertEquals("content://photos/2", store.albums().single().coverUri)
        store.renameAlbum(album.id, "Renamed covers")
        assertEquals("content://photos/2", store.albums().single().coverUri)
        store.setAlbumCover(album.id, null)
        store.close()
        store = LibraryStore(context)
        assertEquals(null, store.albums().single().coverUri)
        assertEquals(setOf("content://photos/1", "content://photos/2"), store.albums().single().photoUris)
        assertThrows(IllegalArgumentException::class.java) { store.setAlbumCover(album.id, " ") }
        assertThrows(IllegalArgumentException::class.java) { store.setAlbumCover(-1, "content://photos/1") }
    }

    @Test fun upgradingVersionOnePreservesAlbumsPhotoAndFolderLinksAndRatings() {
        store.close()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("luma_library.db"), null).use { database ->
            database.execSQL("CREATE TABLE albums (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE)")
            database.execSQL("CREATE TABLE album_photos (album_id INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE, uri TEXT NOT NULL, PRIMARY KEY (album_id, uri))")
            database.execSQL("CREATE TABLE album_folders (album_id INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE, folder_key TEXT NOT NULL, PRIMARY KEY (album_id, folder_key))")
            database.execSQL("CREATE TABLE ratings (uri TEXT PRIMARY KEY NOT NULL, rating INTEGER NOT NULL CHECK (rating BETWEEN 0 AND 5))")
            database.execSQL("INSERT INTO albums (id, name, name_key) VALUES (42, 'Existing album', 'existing album')")
            database.execSQL("INSERT INTO album_photos (album_id, uri) VALUES (42, 'content://photos/7')")
            database.execSQL("INSERT INTO album_folders (album_id, folder_key) VALUES (42, 'external:DCIM/Travel/')")
            database.execSQL("INSERT INTO ratings (uri, rating) VALUES ('content://photos/7', 5)")
            database.version = 1
        }
        store = LibraryStore(context)
        assertEquals(listOf(Album(42, "Existing album", setOf("content://photos/7"), setOf("external:DCIM/Travel/"))), store.albums())
        assertEquals(mapOf("content://photos/7" to 5), store.ratings())
        store.setAlbumCover(42, "content://photos/7")
        assertEquals("content://photos/7", store.albums().single().coverUri)
        assertTrue(store.createAlbum("New album").id > 42)
        SQLiteDatabase.openDatabase(context.getDatabasePath("luma_library.db").path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(2, it.version)
        }
    }
}

/** SQLiteOpenHelper must receive this wrapper even through applicationContext. */
private class IsolatedDatabaseContext(base: Context, private val directory: File) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getDatabasePath(name: String): File = File(directory, name)

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        openOrCreateDatabase(name, mode, factory, null)

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
        val flags = SQLiteDatabase.CREATE_IF_NECESSARY or
            (if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0)
        return SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, flags, errorHandler)
    }
}
