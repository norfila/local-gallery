package com.luma.gallery.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.util.Locale
import com.luma.gallery.R

/** Stores references and local rating overrides. It never writes to or relocates a photo. */
class LibraryStore(context: Context) : Closeable {
    private val helper = LibraryDatabase(context.applicationContext)

    fun albums(): List<Album> = helper.readableDatabase.transaction { db ->
        val names = linkedMapOf<Long, String>()
        val covers = mutableMapOf<Long, String?>()
        val photoUris = mutableMapOf<Long, MutableSet<String>>()
        val folderKeys = mutableMapOf<Long, MutableSet<String>>()
        db.query("albums", arrayOf("id", "name", "cover_uri"), null, null, null, null, "name_key ASC, id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                names[cursor.getLong(0)] = cursor.getString(1)
                covers[cursor.getLong(0)] = if (cursor.isNull(2)) null else cursor.getString(2)
            }
        }
        db.query("album_photos", arrayOf("album_id", "uri"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) photoUris.getOrPut(cursor.getLong(0)) { linkedSetOf() }.add(cursor.getString(1))
        }
        db.query("album_folders", arrayOf("album_id", "folder_key"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) folderKeys.getOrPut(cursor.getLong(0)) { linkedSetOf() }.add(cursor.getString(1))
        }
        names.map { (id, name) ->
            Album(id, name, photoUris[id]?.toSet().orEmpty(), folderKeys[id]?.toSet().orEmpty(), covers[id])
        }
    }

    fun createAlbum(name: String): Album {
        val normalized = normalizedAlbumName(name)
        return translateNameConflict {
            helper.writableDatabase.transaction { db ->
                val values = ContentValues().apply {
                    put("name", normalized)
                    put("name_key", normalized.lowercase(Locale.ROOT))
                }
                Album(db.insertOrThrow("albums", null, values), normalized)
            }
        }
    }

    fun renameAlbum(id: Long, name: String) {
        val normalized = normalizedAlbumName(name)
        translateNameConflict {
            helper.writableDatabase.transaction { db ->
                val values = ContentValues().apply {
                    put("name", normalized)
                    put("name_key", normalized.lowercase(Locale.ROOT))
                }
                if (db.update("albums", values, "id = ?", arrayOf(id.toString())) != 1) throw UserMessageException(R.string.error_album_missing)
            }
        }
    }

    fun deleteAlbum(id: Long) {
        helper.writableDatabase.transaction { db ->
            db.delete("albums", "id = ?", arrayOf(id.toString()))
        }
    }

    /** Null restores the first current member. Folder links can supply the chosen photo too. */
    fun setAlbumCover(id: Long, uri: String?) {
        require(uri == null || uri.isNotBlank()) { "A photo reference is required." }
        helper.writableDatabase.transaction { db ->
            val values = ContentValues().apply {
                if (uri == null) putNull("cover_uri") else put("cover_uri", uri)
            }
            if (db.update("albums", values, "id = ?", arrayOf(id.toString())) != 1) throw UserMessageException(R.string.error_album_missing)
        }
    }

    fun addPhotos(albumId: Long, uris: Set<String>) = addLinks("album_photos", "uri", albumId, uris)

    fun removePhotos(albumId: Long, uris: Set<String>) = removeLinks("album_photos", "uri", albumId, uris)

    fun addFolders(albumId: Long, keys: Set<String>) = addLinks("album_folders", "folder_key", albumId, keys)

    fun removeFolders(albumId: Long, keys: Set<String>) = removeLinks("album_folders", "folder_key", albumId, keys)

    fun ratings(): Map<String, Int> {
        val ratings = linkedMapOf<String, Int>()
        helper.readableDatabase.query("ratings", arrayOf("uri", "rating"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) ratings[cursor.getString(0)] = cursor.getInt(1)
        }
        return ratings
    }

    /** Null restores the embedded rating. Zero explicitly overrides it with an unrated value. */
    fun setRating(uri: String, rating: Int?) {
        require(uri.isNotBlank()) { "A photo reference is required." }
        require(rating == null || rating in 0..5) { "Rating must be between 0 and 5 stars." }
        helper.writableDatabase.transaction { db ->
            if (rating == null) {
                db.delete("ratings", "uri = ?", arrayOf(uri))
            } else {
                val values = ContentValues().apply {
                    put("uri", uri)
                    put("rating", rating)
                }
                db.insertWithOnConflict("ratings", null, values, SQLiteDatabase.CONFLICT_REPLACE).also {
                    check(it != -1L) { "Could not save the rating." }
                }
            }
        }
    }

    override fun close() = helper.close()

    private fun addLinks(table: String, column: String, albumId: Long, links: Set<String>) {
        require(links.all { it.isNotBlank() }) { "Empty photo or folder references are not allowed." }
        helper.writableDatabase.transaction { db ->
            requireAlbum(db, albumId)
            links.forEach { link ->
                val values = ContentValues().apply {
                    put("album_id", albumId)
                    put(column, link)
                }
                // A repeated link is already present; constraints other than this primary key
                // remain enforced by the schema and requireAlbum above.
                db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    private fun removeLinks(table: String, column: String, albumId: Long, links: Set<String>) {
        helper.writableDatabase.transaction { db ->
            requireAlbum(db, albumId)
            links.forEach { db.delete(table, "album_id = ? AND $column = ?", arrayOf(albumId.toString(), it)) }
        }
    }

    private fun requireAlbum(db: SQLiteDatabase, id: Long) {
        db.query("albums", arrayOf("id"), "id = ?", arrayOf(id.toString()), null, null, null).use {
            if (!it.moveToFirst()) throw UserMessageException(R.string.error_album_missing)
        }
    }

    private inline fun <T> translateNameConflict(block: () -> T): T = try {
        block()
    } catch (error: SQLiteConstraintException) {
        throw UserMessageException(R.string.error_album_exists).apply { initCause(error) }
    }
}

internal fun normalizedAlbumName(name: String): String = name.trim().also {
    if (it.isEmpty()) throw UserMessageException(R.string.error_album_name_empty)
    if (it.length > 120) throw UserMessageException(R.string.error_album_name_long)
}

private inline fun <T> SQLiteDatabase.transaction(block: (SQLiteDatabase) -> T): T {
    beginTransactionNonExclusive()
    return try {
        block(this).also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}

private class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "luma_library.db", null, 2) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE albums (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, cover_uri TEXT)")
        db.execSQL("CREATE TABLE album_photos (album_id INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE, uri TEXT NOT NULL, PRIMARY KEY (album_id, uri))")
        db.execSQL("CREATE TABLE album_folders (album_id INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE, folder_key TEXT NOT NULL, PRIMARY KEY (album_id, folder_key))")
        db.execSQL("CREATE TABLE ratings (uri TEXT PRIMARY KEY NOT NULL, rating INTEGER NOT NULL CHECK (rating BETWEEN 0 AND 5))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive migration preserves every album, link, rating, and stable album ID.
        if (oldVersion < 2) db.execSQL("ALTER TABLE albums ADD COLUMN cover_uri TEXT")
    }
}
