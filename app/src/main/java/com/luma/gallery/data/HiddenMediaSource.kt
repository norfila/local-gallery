package com.luma.gallery.data

import com.luma.gallery.R
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Reads only folders explicitly chosen in Android's picker; never changes their files or index. */
internal class HiddenMediaSource(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun loadPhotos(treeUris: List<String>, indexed: List<Photo>): List<Photo> = withContext(Dispatchers.IO) {
        val photos = ArrayList<Photo>()
        val seenDocuments = HashSet<String>()
        val seenUris = indexed.mapTo(HashSet()) { it.uri }
        val primaryRoot = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrDefault("")
        val seenFiles = indexed.flatMapTo(HashSet()) { indexedMediaFileKeys(it, primaryRoot) }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        // Prefer a parent grant when roots overlap so each directory is queried only once.
        val candidates = treeUris.distinct().mapNotNull { raw ->
            runCatching {
                val uri = Uri.parse(raw)
                require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && DocumentsContract.isTreeUri(uri))
                uri to DocumentsContract.getTreeDocumentId(uri)
            }.getOrNull()
        }
        val ancestorCounts = candidates.associateWith { child ->
            candidates.count { parent ->
                if (parent == child || parent.first.authority != child.first.authority) false
                else if (Build.VERSION.SDK_INT >= 29) runCatching {
                    DocumentsContract.isChildDocument(resolver,
                        DocumentsContract.buildDocumentUriUsingTree(parent.first, parent.second),
                        DocumentsContract.buildDocumentUriUsingTree(child.first, child.second))
                }.getOrDefault(false)
                else {
                    val parentPath = localDocumentPath(parent.first.authority.orEmpty(), parent.second)
                    val childPath = localDocumentPath(child.first.authority.orEmpty(), child.second)
                    parentPath != null && childPath != null && parentPath.volume == childPath.volume &&
                        (parentPath.path.isEmpty() || childPath.path.startsWith("${parentPath.path}/"))
                }
            }
        }
        val roots = candidates.sortedWith(compareBy<Pair<Uri, String>> { ancestorCounts[it] ?: 0 }
            .thenBy { it.second.count { char -> char == '/' } }.thenBy { it.second.length }.thenBy { it.first.toString() })
        for ((tree, rootId) in roots) {
            coroutineContext.ensureActive()
            val authority = tree.authority ?: continue
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val rootName = try {
                resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.text(DocumentsContract.Document.COLUMN_DISPLAY_NAME) else ""
                }.orEmpty()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { "" }
            val pending = ArrayDeque<Directory>()
            pending.add(Directory(rootId, rootName.ifBlank { appContext.getString(R.string.hidden_selected_folder) }))
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                val directory = pending.removeFirst()
                val documentKey = "$authority:${directory.id}"
                if (documentKey in seenDocuments) continue
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory.id)
                try {
                    resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        // Mark a directory visited only after opening succeeds. A second valid
                        // grant can still recover the same directory when another is revoked.
                        seenDocuments.add(documentKey)
                        while (cursor.moveToNext()) {
                            coroutineContext.ensureActive()
                            val id = cursor.text(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            if (id.isBlank()) continue
                            val name = cursor.text(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val mime = cursor.text(DocumentsContract.Document.COLUMN_MIME_TYPE)
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                pending.add(Directory(id, name.ifBlank { appContext.getString(R.string.hidden_selected_folder) }))
                                continue
                            }
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            val mediaMime = hiddenMediaMimeType(name, mime) ?: probeImageMime(uri, mime) ?: continue
                            val itemKey = "$authority:$id"
                            if (itemKey in seenDocuments) continue
                            val local = localDocumentPath(authority, id)
                            if (local != null && local.fileKey in seenFiles) {
                                seenDocuments.add(itemKey)
                                continue
                            }
                            // Indexed items can have both a document and a MediaStore URI.
                            val mediaUri = if (Build.VERSION.SDK_INT >= 29) runCatching { MediaStore.getMediaUri(appContext, uri) }.getOrNull()?.toString() else null
                            if (uri.toString() in seenUris || (mediaUri != null && mediaUri in seenUris)) {
                                seenDocuments.add(itemKey)
                                continue
                            }
                            val modifiedMillis = cursor.number(DocumentsContract.Document.COLUMN_LAST_MODIFIED).coerceAtLeast(0)
                            val folderKey = local?.folderKey ?: "document:$authority:${directory.id}/"
                            photos += Photo(
                                id = indexed.size.toLong() + photos.size + 1,
                                uri = uri.toString(),
                                name = name.ifBlank { if (mediaMime.startsWith("video/")) "Video" else "Photo" },
                                folder = local?.path?.substringBeforeLast('/', "")?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: directory.name,
                                folderKey = folderKey,
                                dateTaken = modifiedMillis,
                                dateModified = modifiedMillis / 1000L,
                                size = cursor.number(DocumentsContract.Document.COLUMN_SIZE).coerceAtLeast(0),
                                mimeType = mediaMime,
                                hidden = true,
                            )
                            seenDocuments.add(itemKey)
                            seenUris.add(uri.toString())
                            if (mediaUri != null) seenUris.add(mediaUri)
                            if (local != null) seenFiles.add(local.fileKey)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    // An unavailable, removed, or revoked folder must not hide the normal library.
                }
            }
        }
        photos
    }

    private data class Directory(val id: String, val name: String)
    private fun probeImageMime(uri: Uri, declaredType: String): String? {
        if (declaredType.isNotBlank() && !declaredType.equals("application/octet-stream", true)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        return bounds.outMimeType?.takeIf { bounds.outWidth > 0 && bounds.outHeight > 0 && it.startsWith("image/") }
    }
    private fun Cursor.text(column: String): String = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString).orEmpty()
    private fun Cursor.number(column: String): Long = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L
}
