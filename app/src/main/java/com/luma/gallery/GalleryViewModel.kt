package com.luma.gallery

import android.app.Application
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luma.gallery.data.Album
import com.luma.gallery.data.LibraryStore
import com.luma.gallery.data.MediaRepository
import com.luma.gallery.data.Photo
import com.luma.gallery.data.AppPreferences
import com.luma.gallery.data.ColorPalette
import com.luma.gallery.data.ThemeMode
import com.luma.gallery.data.StartPage
import com.luma.gallery.data.PhotoLayout
import com.luma.gallery.data.PhotoQuery
import com.luma.gallery.data.FavoriteFilter
import com.luma.gallery.data.filterKey
import com.luma.gallery.data.HiddenFileScanner
import com.luma.gallery.data.userMessage
import com.luma.gallery.R
import com.luma.gallery.ui.clearMapTileCache
import com.luma.gallery.widget.AlbumWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val media = MediaRepository(application)
    private val store = LibraryStore(application)
    private val libraryMutex = Mutex()
    private val preferences = AppPreferences(application)
    var themeMode by mutableStateOf(preferences.themeMode())
        private set
    var colorPalette by mutableStateOf(preferences.colorPalette())
        private set
    var pureBlack by mutableStateOf(preferences.pureBlack())
        private set
    var startPage by mutableStateOf(preferences.startPage())
        private set
    var photoColumns by mutableStateOf(preferences.photoColumns())
        private set
    var roundedPhotoCorners by mutableStateOf(preferences.roundedPhotoCorners())
        private set
    var rotateOnlyInViewer by mutableStateOf(preferences.rotateOnlyInViewer())
        private set
    var folderColumns by mutableStateOf(preferences.folderColumns())
        private set
    var folderLayout by mutableStateOf(preferences.folderLayout())
        private set
    var roundedFolderCorners by mutableStateOf(preferences.roundedFolderCorners())
        private set
    var photoLayout by mutableStateOf(preferences.photoLayout())
        private set
    var favoriteFilters by mutableStateOf(preferences.favoriteFilters())
        private set
    var showHiddenMedia by mutableStateOf(preferences.showHiddenMedia())
        private set
    var hiddenMediaFolders by mutableStateOf(preferences.hiddenMediaFolders())
        private set
    var includeThumbnailCaches by mutableStateOf(preferences.includeThumbnailCaches())
        private set
    /** True while storage is searched for hidden media, so the Hidden page can say so instead of "none found". */
    var scanningHidden by mutableStateOf(false)
        private set
    var libraryReady by mutableStateOf(false)
        private set
    var photos by mutableStateOf<List<Photo>>(emptyList())
        private set
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var indexing by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var scan: Job? = null
    private var scanGeneration = 0L
    private var observerJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            observerJob?.cancel()
            observerJob = viewModelScope.launch { delay(700); refresh() }
        }
    }

    init {
        viewModelScope.launch {
            try {
                // App ratings stay in the database for now but are not applied: only ratings stored in the files count.
                libraryMutex.withLock { albums = withContext(Dispatchers.IO) { store.albums() } }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                error = application.getString(R.string.error_saved_data)
            } finally { libraryReady = true }
        }
        application.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        application.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    fun refresh() = refreshLibrary(forceMetadata = false)

    fun rescan() = refreshLibrary(forceMetadata = true, forceHiddenScan = true)

    /** Hide deleted items at once; the following refresh confirms the change from storage. */
    fun removeFromLibrary(uris: Set<String>) {
        viewModelScope.launch {
            libraryMutex.withLock {
                val source = photos
                photos = withContext(Dispatchers.Default) { source.filterNot { it.uri in uris || it.libraryUri in uris } }
            }
            refreshLibrary(forceMetadata = false, forceHiddenScan = true)
        }
    }

    private fun refreshLibrary(forceMetadata: Boolean, forceHiddenScan: Boolean = false) {
        val previous = scan
        previous?.cancel()
        val generation = ++scanGeneration
        scan = viewModelScope.launch {
            indexing = true
            loaded = 0
            error = null
            try {
                previous?.cancelAndJoin()
                if (forceMetadata) media.clearMetadataCache()
                scanningHidden = showHiddenMedia
                val hiddenShown = photos.any { it.hidden }
                val (visible, hidden) = try {
                    val indexed = media.loadPhotos()
                    // Show the library before walking storage for hidden media. While hidden items are
                    // shown, keep the current list until the walk finishes so the Hidden page never empties.
                    if (!hiddenShown) publishPhotos(indexed)
                    indexed to media.loadHiddenPhotos(indexed, forceHiddenScan)
                } finally { scanningHidden = false }
                val initial = if (hidden.isEmpty()) visible
                    else (visible + hidden).sortedWith(compareByDescending<Photo> { it.dateTaken }.thenBy { it.uri })
                if (hiddenShown || hidden.isNotEmpty()) publishPhotos(initial)
                val enriched = initial.toMutableList()
                var lastPublished = SystemClock.uptimeMillis()
                for ((index, photo) in initial.withIndex()) {
                    enriched[index] = media.loadMetadata(photo)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPublished >= 750L || index == initial.lastIndex) {
                        loaded = index + 1
                        publishPhotos(enriched)
                        lastPublished = now
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                error = getApplication<Application>().getString(if (failure is SecurityException) R.string.error_access_changed else R.string.error_library)
            } finally {
                if (generation == scanGeneration) {
                    indexing = false
                    AlbumWidgetProvider.refreshAll(getApplication())
                }
            }
        }
    }

    private suspend fun publishPhotos(source: List<Photo>) {
        libraryMutex.withLock {
            photos = source
            AlbumWidgetProvider.shareLibrary(source)
        }
    }

    fun changeThemeMode(mode: ThemeMode) {
        preferences.setThemeMode(mode)
        themeMode = mode
        AlbumWidgetProvider.refreshAll(getApplication())
    }

    fun changeColorPalette(palette: ColorPalette) { preferences.setColorPalette(palette); colorPalette = palette }
    fun changePureBlack(black: Boolean) { preferences.setPureBlack(black); pureBlack = black }

    fun changeStartPage(page: StartPage) { preferences.setStartPage(page); startPage = page }
    fun changePhotoColumns(columns: Int) { preferences.setPhotoColumns(columns); photoColumns = columns.coerceIn(1, 6) }
    fun changeRoundedPhotoCorners(rounded: Boolean) { preferences.setRoundedPhotoCorners(rounded); roundedPhotoCorners = rounded }
    fun changeRotateOnlyInViewer(only: Boolean) { preferences.setRotateOnlyInViewer(only); rotateOnlyInViewer = only }
    /** True once the user agreed to load the online map, false after "Not now" or switching it off, null before asking. */
    var onlineMap by mutableStateOf(preferences.onlineMap())
        private set
    fun changeOnlineMap(allowed: Boolean) {
        preferences.setOnlineMap(allowed)
        onlineMap = allowed
        // Map images saved while the map was on (also by earlier versions) go with it.
        if (!allowed) clearMapTileCache(getApplication())
    }
    fun changeFolderColumns(columns: Int) { preferences.setFolderColumns(columns); folderColumns = columns.coerceIn(1, 6) }
    fun changeFolderLayout(layout: PhotoLayout) { preferences.setFolderLayout(layout); folderLayout = preferences.folderLayout() }
    fun changeRoundedFolderCorners(rounded: Boolean) { preferences.setRoundedFolderCorners(rounded); roundedFolderCorners = rounded }
    fun changePhotoLayout(layout: PhotoLayout) { preferences.setPhotoLayout(layout); photoLayout = layout }
    fun changeShowHiddenMedia(show: Boolean) {
        preferences.setShowHiddenMedia(show)
        showHiddenMedia = show
        HiddenFileScanner.invalidate()
        refreshLibrary(forceMetadata = false, forceHiddenScan = true)
    }
    fun changeIncludeThumbnailCaches(include: Boolean) {
        preferences.setIncludeThumbnailCaches(include)
        includeThumbnailCaches = include
        HiddenFileScanner.invalidate()
        refreshLibrary(forceMetadata = false, forceHiddenScan = true)
    }
    fun addHiddenMediaFolder(uri: String) {
        preferences.setHiddenMediaFolders(hiddenMediaFolders + uri)
        hiddenMediaFolders = preferences.hiddenMediaFolders()
        refresh()
    }
    fun removeHiddenMediaFolder(uri: String) {
        preferences.setHiddenMediaFolders(hiddenMediaFolders - uri)
        hiddenMediaFolders = preferences.hiddenMediaFolders()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        refresh()
    }
    fun saveFavoriteFilter(name: String, query: PhotoQuery) {
        val id = (favoriteFilters.maxOfOrNull { it.id } ?: 0L) + 1
        val updated = favoriteFilters.filterNot { it.query.filterKey() == query.filterKey() } + FavoriteFilter(id, name.trim(), query.copy(search = ""))
        preferences.setFavoriteFilters(updated); favoriteFilters = updated
    }
    fun removeFavoriteFilter(id: Long) {
        val updated = favoriteFilters.filterNot { it.id == id }
        preferences.setFavoriteFilters(updated); favoriteFilters = updated
    }
    fun renameFavoriteFilter(id: Long, name: String) {
        val updated = favoriteFilters.map { if (it.id == id) it.copy(name = name.trim()) else it }
        preferences.setFavoriteFilters(updated); favoriteFilters = updated
    }

    private fun updateLibrary(action: () -> Unit) {
        viewModelScope.launch {
            try {
                libraryMutex.withLock {
                    albums = withContext(Dispatchers.IO) { action(); store.albums() }
                }
                AlbumWidgetProvider.refreshAll(getApplication())
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { error = failure.userMessage(getApplication(), R.string.error_change) }
        }
    }

    fun createAlbum(name: String, uris: Set<String> = emptySet(), folders: Set<String> = emptySet(), onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val created = libraryMutex.withLock {
                    val result = withContext(Dispatchers.IO) {
                        val album = store.createAlbum(name)
                        store.addPhotos(album.id, uris)
                        store.addFolders(album.id, folders)
                        album to store.albums()
                    }
                    albums = result.second
                    result.first
                }
                onCreated(created.id)
                AlbumWidgetProvider.refreshAll(getApplication())
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { error = failure.userMessage(getApplication(), R.string.error_album_create) }
        }
    }

    fun renameAlbum(id: Long, name: String) = updateLibrary { store.renameAlbum(id, name) }
    fun setAlbumCover(id: Long, uri: String?) = updateLibrary { store.setAlbumCover(id, uri) }
    fun deleteAlbum(id: Long) = updateLibrary { store.deleteAlbum(id) }
    fun addPhotos(id: Long, uris: Set<String>) = updateLibrary { store.addPhotos(id, uris) }
    fun removePhotos(id: Long, uris: Set<String>) = updateLibrary { store.removePhotos(id, uris) }
    fun addFolders(id: Long, keys: Set<String>) = updateLibrary { store.addFolders(id, keys) }
    fun removeFolders(id: Long, keys: Set<String>) = updateLibrary { store.removeFolders(id, keys) }
    fun updateFolders(id: Long, keys: Set<String>) = updateLibrary {
        val previous = store.albums().firstOrNull { it.id == id }?.folderKeys
            ?: throw com.luma.gallery.data.UserMessageException(R.string.error_album_missing)
        store.addFolders(id, keys)
        store.removeFolders(id, previous - keys)
    }

    fun clearError() { error = null }
    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        val scopeJob = viewModelScope.coroutineContext[Job]
        scopeJob?.cancel()
        // Native provider/SQLite calls finish before coroutine cancellation is delivered.
        // Wait for those workers, then close both databases off the main thread.
        CoroutineScope(Dispatchers.IO).launch {
            scopeJob?.join()
            runCatching { media.close() }
            runCatching { store.close() }
        }
        super.onCleared()
    }
}
