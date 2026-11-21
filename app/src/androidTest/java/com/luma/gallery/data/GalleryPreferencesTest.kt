package com.luma.gallery.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryPreferencesTest {
    private lateinit var context: Context
    private lateinit var stored: SharedPreferences

    @Before fun isolatePreferences() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "gallery-preferences-test-${UUID.randomUUID()}"
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(fileName, mode)
        }
        stored = context.getSharedPreferences("gallery_preferences", Context.MODE_PRIVATE)
    }

    @After fun removePreferences() { stored.edit().clear().commit() }

    @Test fun defaultsAndEachStartPageThemeAndLayoutSurviveReopeningPreferences() {
        val initial = AppPreferences(context)
        assertEquals(ThemeMode.SYSTEM, initial.themeMode())
        assertEquals(StartPage.LIBRARY, initial.startPage())
        assertEquals(PhotoLayout.GRID, initial.photoLayout())
        assertEquals(3, initial.photoColumns())
        assertFalse(initial.roundedPhotoCorners())
        assertEquals(2, initial.folderColumns())
        assertFavoriteKeysEqual(FavoriteFilterCodec.defaults(), initial.favoriteFilters())
        StartPage.entries.forEach { page ->
            initial.setStartPage(page)
            assertEquals(page, AppPreferences(context).startPage())
        }
        ThemeMode.entries.forEach { theme ->
            initial.setThemeMode(theme)
            assertEquals(theme, AppPreferences(context).themeMode())
        }
        PhotoLayout.entries.forEach { layout ->
            initial.setPhotoLayout(layout)
            assertEquals(layout, AppPreferences(context).photoLayout())
        }
        initial.setRoundedPhotoCorners(true)
        assertTrue(AppPreferences(context).roundedPhotoCorners())
        initial.setRoundedPhotoCorners(false)
        assertFalse(AppPreferences(context).roundedPhotoCorners())
    }

    @Test fun columnLimitsAreEnforcedForWritesAndExistingOutOfRangeValues() {
        val preferences = AppPreferences(context)
        (1..6).forEach { columns ->
            preferences.setPhotoColumns(columns)
            assertEquals(columns, AppPreferences(context).photoColumns())
        }
        preferences.setPhotoColumns(Int.MIN_VALUE)
        assertEquals(1, AppPreferences(context).photoColumns())
        preferences.setPhotoColumns(Int.MAX_VALUE)
        assertEquals(6, AppPreferences(context).photoColumns())
        stored.edit().putInt("photo_columns", -10).commit()
        assertEquals(1, AppPreferences(context).photoColumns())
        stored.edit().putInt("photo_columns", 10).commit()
        assertEquals(6, AppPreferences(context).photoColumns())
    }

    @Test fun unknownEnumValuesMalformedJsonAndWrongPreferenceTypesFallBackSafely() {
        stored.edit().putString("theme_mode", "future-theme")
            .putString("start_page", "removed-page").putString("photo_layout", "unsupported-layout")
            .putString("favorite_filters", "{broken-json").commit()
        var preferences = AppPreferences(context)
        assertEquals(ThemeMode.SYSTEM, preferences.themeMode())
        assertEquals(StartPage.LIBRARY, preferences.startPage())
        assertEquals(PhotoLayout.GRID, preferences.photoLayout())
        assertFavoriteKeysEqual(FavoriteFilterCodec.defaults(), preferences.favoriteFilters())
        stored.edit().putBoolean("theme_mode", true).putInt("start_page", 9)
            .putBoolean("photo_layout", false).putString("photo_columns", "five")
            .putString("rounded_photo_corners", "rounded")
            .putBoolean("favorite_filters", false).commit()
        preferences = AppPreferences(context)
        assertEquals(ThemeMode.SYSTEM, preferences.themeMode())
        assertEquals(StartPage.LIBRARY, preferences.startPage())
        assertEquals(PhotoLayout.GRID, preferences.photoLayout())
        assertEquals(3, preferences.photoColumns())
        assertFalse(preferences.roundedPhotoCorners())
        assertFavoriteKeysEqual(FavoriteFilterCodec.defaults(), preferences.favoriteFilters())
    }

    @Test fun folderColumnsPersistIndependentlyAndInvalidStoredValuesFallBackSafely() {
        val preferences = AppPreferences(context)
        preferences.setPhotoColumns(5)
        (1..6).forEach { columns ->
            preferences.setFolderColumns(columns)
            assertEquals(columns, AppPreferences(context).folderColumns())
            assertEquals("Folder density must not change photo density", 5, AppPreferences(context).photoColumns())
        }
        preferences.setFolderColumns(Int.MIN_VALUE)
        assertEquals(1, AppPreferences(context).folderColumns())
        preferences.setFolderColumns(Int.MAX_VALUE)
        assertEquals(6, AppPreferences(context).folderColumns())
        stored.edit().putInt("folder_columns", -10).commit()
        assertEquals(1, AppPreferences(context).folderColumns())
        stored.edit().putInt("folder_columns", 10).commit()
        assertEquals(6, AppPreferences(context).folderColumns())
        stored.edit().putString("folder_columns", "three").commit()
        assertEquals(2, AppPreferences(context).folderColumns())
    }

    @Test fun explicitlyRemovingEveryFavoriteStaysEmptyAndCanBeReplacedAfterReopening() {
        AppPreferences(context).setFavoriteFilters(emptyList())
        assertTrue(AppPreferences(context).favoriteFilters().isEmpty())
        val saved = FavoriteFilter(40, "High ISO", PhotoQuery(rules = listOf(FilterRule(100, MetadataField.ISO, CompareOp.GREATER_OR_EQUAL, 800.0))))
        AppPreferences(context).setFavoriteFilters(listOf(saved))
        val reopened = AppPreferences(context).favoriteFilters()
        assertEquals(listOf(40L), reopened.map { it.id })
        assertEquals("High ISO", reopened.single().name)
        assertEquals(saved.query.filterKey(), reopened.single().query.filterKey())
    }

    @Test fun codecPreservesEveryComparisonAndMetadataFieldWithoutDependingOnTransientRuleIds() {
        val rules = MetadataField.entries.mapIndexed { index, field ->
            FilterRule(9_000L + index, field, CompareOp.entries[index % CompareOp.entries.size], index + .5)
        }
        val favorite = FavoriteFilter(12, "Camera criteria", PhotoQuery(rules = rules, hasLocation = false, camera = "Test Camera", folderKey = "external_primary:DCIM/Camera/"))
        val restored = FavoriteFilterCodec.decode(FavoriteFilterCodec.encode(listOf(favorite))).single()
        assertEquals(favorite.id, restored.id)
        assertEquals(favorite.name, restored.name)
        assertEquals(favorite.query.filterKey(), restored.query.filterKey())
        assertNotEquals(rules.map { it.id }, restored.query.rules.map { it.id })
        val renumbered = favorite.query.copy(rules = rules.reversed().mapIndexed { index, rule -> rule.copy(id = index.toLong()) })
        assertEquals("Order and editor row IDs must not create duplicate filter identities", favorite.query.filterKey(), renumbered.filterKey())
    }

    @Test fun searchTextIsIndependentOfSavedCriteriaAndDoesNotCreateAFilterOnItsOwn() {
        val query = PhotoQuery(search = "Oktober holiday", rules = listOf(FilterRule(5, MetadataField.RATING, CompareOp.GREATER_OR_EQUAL, 4.0)))
        assertEquals(query.filterKey(), query.copy(search = "Different folder").filterKey())
        val restored = FavoriteFilterCodec.decode(FavoriteFilterCodec.encode(listOf(FavoriteFilter(1, "Rated", query)))).single().query
        assertEquals("", restored.search)
        assertEquals(query.filterKey(), restored.filterKey())
        assertFalse(PhotoQuery(search = "Only a search").hasFilterCriteria())
        assertTrue(restored.hasFilterCriteria())
    }

    @Test fun invalidFavoriteEntriesAreSkippedWithoutLosingValidSavedFiltersOrEmptyChoice() {
        val valid = FavoriteFilterCodec.encode(listOf(FavoriteFilter(15, "With GPS", PhotoQuery(hasLocation = true))))
        val array = JSONArray(valid)
        array.put(JSONObject().put("id", 16).put("name", "Bad field").put("rules", JSONArray().put(
            JSONObject().put("field", "UNKNOWN").put("op", "EQUAL").put("value", 1.0),
        )))
        array.put(JSONObject().put("id", 17).put("name", "Search without criteria").put("rules", JSONArray()))
        array.put(array.getJSONObject(0))
        val restored = FavoriteFilterCodec.decode(array.toString())
        assertEquals(1, restored.size)
        assertEquals(15L, restored.single().id)
        assertEquals(true, restored.single().query.hasLocation)
        assertTrue(FavoriteFilterCodec.decode("[]").isEmpty())
        assertFavoriteKeysEqual(FavoriteFilterCodec.defaults(), FavoriteFilterCodec.decode(null))
    }

    @Test fun oldQuickFiltersBecomeRemovableFavoritesOnce() {
        // A 0.14 install: the old "On map" default plus a custom favorite, with Unrated and 12+ MP still chosen as quick filters.
        val onMap = FavoriteFilter(2, "On map", PhotoQuery(hasLocation = true))
        val custom = FavoriteFilter(5, "Pixel", PhotoQuery(camera = "Pixel 10"))
        stored.edit().putString("favorite_filters", FavoriteFilterCodec.encode(listOf(onMap, custom)))
            .putString("quick_filters", "UNRATED,LARGE_IMAGES").commit()
        val merged = AppPreferences(context).favoriteFilters()
        assertEquals(listOf("With GPS", "Pixel", "Unrated"), merged.map { it.name })
        assertEquals(listOf(2L, 5L, 6L), merged.map { it.id })
        assertEquals(FavoriteFilterCodec.defaults()[1].query.filterKey(), merged.last().query.filterKey())
        assertFalse(stored.contains("quick_filters"))
        // Removing a merged favorite sticks: the merge never runs again.
        AppPreferences(context).setFavoriteFilters(merged.filterNot { it.name == "Unrated" })
        assertEquals(listOf("With GPS", "Pixel"), AppPreferences(context).favoriteFilters().map { it.name })
    }

    @Test fun onlyQuickFiltersTheUserStillHadAreMergedAndExistingCriteriaAreNotDuplicated() {
        val highlyRated = FavoriteFilter(9, "Highly rated", FavoriteFilterCodec.defaults()[0].query)
        stored.edit().putString("favorite_filters", FavoriteFilterCodec.encode(listOf(highlyRated))).putString("quick_filters", "").commit()
        assertEquals(listOf("Highly rated"), AppPreferences(context).favoriteFilters().map { it.name })
        // Never customized: every old default shortcut is kept, except the one already saved as a favorite.
        stored.edit().clear().putString("favorite_filters", FavoriteFilterCodec.encode(listOf(highlyRated))).commit()
        assertEquals(listOf("Highly rated", "Unrated", "With GPS"), AppPreferences(context).favoriteFilters().map { it.name })
        assertEquals(listOf("Highly rated", "Unrated", "With GPS"), AppPreferences(context).favoriteFilters().map { it.name })
    }

    @Test fun hiddenMediaIsOptInAndGrantedFolderChoicesPersistIndependently() {
        val preferences = AppPreferences(context)
        val pictures = "content://com.android.externalstorage.documents/tree/primary%3APictures"
        val camera = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
        assertFalse(preferences.showHiddenMedia())
        assertTrue(preferences.hiddenMediaFolders().isEmpty())
        preferences.setHiddenMediaFolders(listOf(pictures, camera, pictures))
        assertEquals(listOf(pictures, camera), AppPreferences(context).hiddenMediaFolders())
        assertFalse("Saving folder choices must not enable hidden media", AppPreferences(context).showHiddenMedia())
        preferences.setShowHiddenMedia(true)
        assertTrue(AppPreferences(context).showHiddenMedia())
        preferences.setShowHiddenMedia(false)
        assertFalse(AppPreferences(context).showHiddenMedia())
        assertEquals("Turning hidden media off retains the chosen folders", listOf(pictures, camera), AppPreferences(context).hiddenMediaFolders())
        preferences.setHiddenMediaFolders(emptyList())
        assertTrue(AppPreferences(context).hiddenMediaFolders().isEmpty())
    }

    @Test fun hiddenMediaRejectsCorruptValuesAndInvalidFolderUris() {
        val pictures = "content://com.android.externalstorage.documents/tree/primary%3APictures"
        stored.edit().putString("show_hidden_media", "enabled").putBoolean("hidden_media_folders", true).commit()
        assertFalse(AppPreferences(context).showHiddenMedia())
        assertTrue(AppPreferences(context).hiddenMediaFolders().isEmpty())
        stored.edit().putString("hidden_media_folders", "{broken-json").commit()
        assertTrue(AppPreferences(context).hiddenMediaFolders().isEmpty())
        stored.edit().putString("hidden_media_folders", JSONArray(listOf("", "https://example.com/tree/folder", "content://media/external/images/media/7", pictures, pictures)).toString()).commit()
        assertEquals(listOf(pictures), AppPreferences(context).hiddenMediaFolders())
        AppPreferences(context).setHiddenMediaFolders(listOf("", "file:///storage/emulated/0/Pictures", pictures))
        assertEquals(listOf(pictures), AppPreferences(context).hiddenMediaFolders())
    }

    private fun assertFavoriteKeysEqual(expected: List<FavoriteFilter>, actual: List<FavoriteFilter>) {
        assertEquals(expected.map { it.id to it.name }, actual.map { it.id to it.name })
        assertEquals(expected.map { it.query.filterKey() }, actual.map { it.query.filterKey() })
    }
}
