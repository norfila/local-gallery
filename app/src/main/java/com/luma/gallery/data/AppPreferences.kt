package com.luma.gallery.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import com.luma.gallery.R
import org.json.JSONArray

enum class ThemeMode(@StringRes val labelRes: Int) { SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark) }
enum class StartPage(@StringRes val labelRes: Int) {
    LIBRARY(R.string.nav_library), PHOTOS(R.string.view_photos), VIDEOS(R.string.view_videos), FOLDERS(R.string.view_folders),
    ALBUMS(R.string.nav_albums), PLACES(R.string.nav_places), COUNTRIES(R.string.nav_countries),
}

class AppPreferences(context: Context) {
    private val resources = context.applicationContext.resources
    private val preferences = context.applicationContext.getSharedPreferences("gallery_preferences", Context.MODE_PRIVATE)
    fun themeMode(): ThemeMode = runCatching {
        ThemeMode.valueOf(preferences.getString("theme_mode", ThemeMode.SYSTEM.name)!!)
    }.getOrDefault(ThemeMode.SYSTEM)
    fun setThemeMode(mode: ThemeMode) { preferences.edit().putString("theme_mode", mode.name).apply() }
    fun startPage(): StartPage = runCatching {
        StartPage.valueOf(preferences.getString("start_page", StartPage.LIBRARY.name)!!)
    }.getOrDefault(StartPage.LIBRARY)
    fun setStartPage(page: StartPage) { preferences.edit().putString("start_page", page.name).apply() }
    fun photoColumns(): Int = runCatching { preferences.getInt("photo_columns", 3).coerceIn(1, 6) }.getOrDefault(3)
    fun setPhotoColumns(columns: Int) { preferences.edit().putInt("photo_columns", columns.coerceIn(1, 6)).apply() }
    /** On phones: the library stays upright and only the photo viewer and editor turn with the phone. */
    fun rotateOnlyInViewer(): Boolean = runCatching { preferences.getBoolean("rotate_only_in_viewer", true) }.getOrDefault(true)
    fun setRotateOnlyInViewer(only: Boolean) { preferences.edit().putBoolean("rotate_only_in_viewer", only).apply() }
    /** The online map loads only after the user agreed; null means Gallery has not asked yet. */
    fun onlineMap(): Boolean? = runCatching { if (preferences.contains("online_map")) preferences.getBoolean("online_map", false) else null }.getOrNull()
    fun setOnlineMap(allowed: Boolean) { preferences.edit().putBoolean("online_map", allowed).apply() }
    /** Gallery offers "move and delete without asking" once, after Android's question has interrupted a move or delete. */
    fun mediaManagementOffered(): Boolean = runCatching { preferences.getBoolean("media_management_offered", false) }.getOrDefault(false)
    fun setMediaManagementOffered(offered: Boolean) { preferences.edit().putBoolean("media_management_offered", offered).apply() }
    /** Test fixtures restore "not asked yet" with this. */
    fun forgetOnlineMapChoice() { preferences.edit().remove("online_map").apply() }
    fun roundedPhotoCorners(): Boolean = runCatching { preferences.getBoolean("rounded_photo_corners", false) }.getOrDefault(false)
    fun setRoundedPhotoCorners(rounded: Boolean) { preferences.edit().putBoolean("rounded_photo_corners", rounded).apply() }
    fun folderColumns(): Int = runCatching { preferences.getInt("folder_columns", 2).coerceIn(1, 6) }.getOrDefault(2)
    fun setFolderColumns(columns: Int) { preferences.edit().putInt("folder_columns", columns.coerceIn(1, 6)).apply() }
    /** Folders offer grid or list; an older saved "masonry" falls back to the grid. */
    fun folderLayout(): PhotoLayout = runCatching {
        PhotoLayout.valueOf(preferences.getString("folder_layout", PhotoLayout.GRID.name)!!).takeIf { it in PhotoLayout.folderChoices } ?: PhotoLayout.GRID
    }.getOrDefault(PhotoLayout.GRID)
    fun setFolderLayout(layout: PhotoLayout) { preferences.edit().putString("folder_layout", layout.name).apply() }
    fun roundedFolderCorners(): Boolean = runCatching { preferences.getBoolean("rounded_folder_corners", true) }.getOrDefault(true)
    fun setRoundedFolderCorners(rounded: Boolean) { preferences.edit().putBoolean("rounded_folder_corners", rounded).apply() }
    fun photoLayout(): PhotoLayout = runCatching {
        PhotoLayout.valueOf(preferences.getString("photo_layout", PhotoLayout.GRID.name)!!)
    }.getOrDefault(PhotoLayout.GRID)
    fun setPhotoLayout(layout: PhotoLayout) { preferences.edit().putString("photo_layout", layout.name).apply() }
    private fun defaultFavorites() = FavoriteFilterCodec.defaults(resources.getString(R.string.quick_four_stars),
        resources.getString(R.string.quick_unrated), resources.getString(R.string.quick_with_gps))
    fun favoriteFilters(): List<FavoriteFilter> = runCatching {
        val saved = preferences.getString("favorite_filters", null) ?: return@runCatching defaultFavorites()
        mergeQuickFilters(FavoriteFilterCodec.decode(saved))
    }.getOrDefault(defaultFavorites())
    fun setFavoriteFilters(filters: List<FavoriteFilter>) {
        preferences.edit().putString("favorite_filters", FavoriteFilterCodec.encode(filters)).putBoolean("quick_filters_merged", true).apply()
    }

    /**
     * Version 0.15 folded the separate quick filters into favorites. Saved favorites get each shortcut the
     * user still had (4+ stars, Unrated, With GPS) once, and the old "On map" default is renamed "With GPS".
     */
    private fun mergeQuickFilters(saved: List<FavoriteFilter>): List<FavoriteFilter> {
        if (preferences.getBoolean("quick_filters_merged", false)) return saved
        val kept = preferences.getString("quick_filters", null)?.split(',')?.map { it.trim() }
        val oldOnMap = setOf("On map", resources.getString(R.string.favorite_on_map))
        val gpsKey = PhotoQuery(hasLocation = true).filterKey()
        var merged = saved.map { if (it.name in oldOnMap && it.query.filterKey() == gpsKey) it.copy(name = resources.getString(R.string.quick_with_gps)) else it }
        defaultFavorites().zip(listOf("FOUR_STARS", "UNRATED", "WITH_GPS")).forEach { (favorite, quick) ->
            if ((kept == null || quick in kept) && merged.none { it.query.filterKey() == favorite.query.filterKey() })
                merged = merged + favorite.copy(id = (merged.maxOfOrNull { it.id } ?: 0L) + 1)
        }
        setFavoriteFilters(merged)
        preferences.edit().remove("quick_filters").apply()
        return merged
    }
    fun showHiddenMedia(): Boolean = runCatching { preferences.getBoolean("show_hidden_media", false) }.getOrDefault(false)
    fun setShowHiddenMedia(show: Boolean) { preferences.edit().putBoolean("show_hidden_media", show).apply() }
    /** Android's .thumbnails caches repeat photos the library already shows, so they are opt-in. */
    fun includeThumbnailCaches(): Boolean = runCatching { preferences.getBoolean("include_thumbnail_caches", false) }.getOrDefault(false)
    fun setIncludeThumbnailCaches(include: Boolean) { preferences.edit().putBoolean("include_thumbnail_caches", include).apply() }
    fun hiddenMediaFolders(): List<String> = runCatching {
        val encoded = preferences.getString("hidden_media_folders", null) ?: return@runCatching emptyList()
        val entries = JSONArray(encoded)
        (0 until entries.length()).mapNotNull { entries.optString(it).takeIf(::isMediaFolderUri) }.distinct()
    }.getOrDefault(emptyList())
    fun setHiddenMediaFolders(folders: List<String>) {
        preferences.edit().putString("hidden_media_folders", JSONArray(folders.filter(::isMediaFolderUri).distinct()).toString()).apply()
    }
    private fun isMediaFolderUri(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme == "content" && !uri.authority.isNullOrBlank() && DocumentsContract.isTreeUri(uri)
    }.getOrDefault(false)
}
