package com.luma.gallery.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import com.luma.gallery.BuildConfig
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Android's "All files access" (Android 11+) or legacy storage access lets Gallery read hidden folders directly. */
object AllFilesAccess {
    /** Android 11+ grants this in system settings; older versions use the storage permission. */
    val needsSystemSettings: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The Google Play version cannot declare all files access; there, hidden media comes only from chosen folders. */
    val offered: Boolean get() = BuildConfig.ALL_FILES_ACCESS || !needsSystemSettings

    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun settingsIntents(context: Context): List<Intent> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && offered) listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    ) else emptyList()
}

/**
 * Finds photos and videos Android's media index leaves out: files in folders containing `.nomedia`,
 * dot folders (such as `.thumbnails` or WhatsApp `.Statuses`) and dot files. Files are only read.
 * Ordinary media is skipped here because MediaStore already supplies it.
 */
internal object HiddenFileScanner {
    private const val MAX_DEPTH = 16
    private const val MAX_FILES = 100_000
    private const val CACHE_MILLIS = 10 * 60 * 1000L

    private data class HiddenFile(
        val path: String, val name: String, val volume: String, val relativeDirectory: String,
        val absoluteDirectory: String, val mimeType: String, val size: Long, val modified: Long,
    )

    @Volatile private var cache: Pair<Long, List<HiddenFile>>? = null

    fun invalidate() { cache = null }

    suspend fun loadPhotos(context: Context, indexed: List<Photo>, force: Boolean): List<Photo> = withContext(Dispatchers.IO) {
        if (!AllFilesAccess.granted(context)) return@withContext emptyList()
        val cached = cache
        val files = if (!force && cached != null && SystemClock.elapsedRealtime() - cached.first < CACHE_MILLIS) cached.second
            else scan(context).also { cache = SystemClock.elapsedRealtime() to it }
        val primaryRoot = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrDefault("")
        val indexedFiles = indexed.flatMapTo(HashSet()) { indexedMediaFileKeys(it, primaryRoot) }
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        files.filter { "${it.volume}:${it.relativeDirectory.withSlash()}${it.name}" !in indexedFiles }.mapIndexed { index, file ->
            Photo(
                id = indexed.size.toLong() + index + 1,
                uri = Uri.fromFile(File(file.path)).toString(),
                name = file.name,
                folder = file.relativeDirectory.substringAfterLast('/').ifBlank { file.volume },
                // Match MediaStore folder keys so Folders, album folder links and filters treat both alike.
                folderKey = if (legacy) "external:${file.absoluteDirectory}/" else "${file.volume}:${file.relativeDirectory.withSlash()}",
                dateTaken = file.modified,
                dateModified = file.modified / 1000L,
                size = file.size,
                mimeType = file.mimeType,
                hidden = true,
            )
        }
    }

    private fun String.withSlash() = if (isEmpty()) "" else "$this/"

    private suspend fun scan(context: Context): List<HiddenFile> {
        val found = ArrayList<HiddenFile>()
        val thumbnailCaches = AppPreferences(context).includeThumbnailCaches()
        for ((volume, root) in roots(context)) {
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: continue
            val pending = ArrayDeque<Triple<File, Boolean, Int>>()
            pending.add(Triple(File(rootPath), false, 0))
            while (pending.isNotEmpty() && found.size < MAX_FILES) {
                coroutineContext.ensureActive()
                val (directory, hiddenParent, depth) = pending.removeLast()
                val children = directory.listFiles() ?: continue
                val hidden = hiddenParent || (depth > 0 && directory.name.startsWith('.')) ||
                    children.any { it.name.equals(".nomedia", ignoreCase = true) }
                val relative = directory.path.removePrefix(rootPath).replace('\\', '/').trim('/')
                for (child in children) {
                    val name = child.name
                    if (child.isDirectory) {
                        val cache = name.equals(".thumbnails", ignoreCase = true)
                        if (depth < MAX_DEPTH && !skipDirectory(relative, name) && (thumbnailCaches || !cache)) pending.add(Triple(child, hidden, depth + 1))
                        continue
                    }
                    // MediaStore keeps binned and in-progress files as .trashed-* / .pending-* dot files.
                    if (!hidden && !name.startsWith('.')) continue
                    if (name.startsWith(".trashed-") || name.startsWith(".pending-")) continue
                    val mime = hiddenMediaMimeType(name, "") ?: continue
                    val size = child.length()
                    if (size <= 0) continue
                    found += HiddenFile(child.path, name, volume, relative, directory.path.replace('\\', '/').trimEnd('/'), mime, size, child.lastModified())
                }
            }
        }
        return found
    }

    /** Other apps' private folders are unreadable; the rest of Android/ (for example Android/media) is scanned. */
    private fun skipDirectory(parent: String, name: String): Boolean =
        parent.equals("Android", ignoreCase = true) && (name.equals("data", true) || name.equals("obb", true))

    private fun roots(context: Context): List<Pair<String, File>> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storage = context.getSystemService(StorageManager::class.java) ?: return emptyList()
            return storage.storageVolumes.mapNotNull { volume ->
                val directory = volume.directory ?: return@mapNotNull null
                if (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
                val name = if (volume.isPrimary) "external_primary" else volume.uuid?.lowercase(Locale.ROOT) ?: return@mapNotNull null
                name to directory
            }
        }
        val primary = Environment.getExternalStorageDirectory()
        // App-specific directories reveal removable volumes: /storage/XXXX-XXXX/Android/data/<package>/files.
        val secondary = context.getExternalFilesDirs(null).drop(1).filterNotNull().mapNotNull { appDirectory ->
            val path = appDirectory.path.replace('\\', '/')
            val root = path.substringBefore("/Android/", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            File(root).name.lowercase(Locale.ROOT) to File(root)
        }
        return listOf("external_primary" to primary) + secondary
    }
}
