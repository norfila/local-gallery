package com.luma.gallery.data

import org.junit.Assert.*
import org.junit.Test

class SearchFiltersTest {
    private val rating = FilterRule(1, MetadataField.RATING, CompareOp.GREATER_OR_EQUAL, 4.0)
    private val lowIso = FilterRule(1, MetadataField.ISO, CompareOp.LESS_OR_EQUAL, 800.0)

    @Test fun addingFavoriteKeepsSearchAndIndependentCriteriaWithUniqueEditorIds() {
        val current = PhotoQuery(search = "Travel", rules = listOf(rating), hasLocation = true)
        val favorite = PhotoQuery(search = "Ignored saved text", rules = listOf(rating.copy(id = 99), lowIso), camera = "Pixel 10")
        val merged = current.addFilterCriteria(favorite)
        assertEquals("Travel", merged.search)
        assertEquals(true, merged.hasLocation)
        assertEquals("Pixel 10", merged.camera)
        assertEquals(2, merged.rules.size)
        assertEquals(2, merged.rules.map { it.id }.toSet().size)
        assertEquals(rating, merged.rules.first())
        assertTrue(merged.includesFilter(favorite))
        assertEquals(merged, merged.addFilterCriteria(favorite))
    }

    @Test fun differentBoundariesOnTheSameFieldRemainAnAndRange() {
        val minimum = lowIso.copy(id = 8, op = CompareOp.GREATER_OR_EQUAL, value = 100.0)
        val query = PhotoQuery(rules = listOf(lowIso)).addFilterCriteria(PhotoQuery(rules = listOf(minimum)))
        val source = listOf(50, 100, 400, 800, 1600).mapIndexed { i, iso -> photo(i.toLong()).copy(iso = iso) }
        assertEquals(listOf(100, 400, 800), query.apply(source).map { it.iso })
        assertFalse(query.includesFilter(PhotoQuery(rules = listOf(minimum.copy(value = 200.0)))))
    }

    @Test fun conflictingCategoriesRequireAtomicConfirmationAndPreserveUnrelatedCriteria() {
        val original = PhotoQuery(search = "Vacation", rules = listOf(rating), hasLocation = false, camera = "Canon", folderKey = "one/")
        val favorite = PhotoQuery(rules = listOf(lowIso), hasLocation = true, camera = "Sony", folderKey = "two/")
        assertEquals(setOf("Camera", "Folder", "Location"), original.filterConflicts(favorite))
        assertSame(original, original.addFilterCriteria(favorite))
        val replaced = original.addFilterCriteria(favorite, replaceConflicts = true)
        assertEquals("Vacation", replaced.search)
        assertEquals(2, replaced.rules.size)
        assertTrue(replaced.includesFilter(favorite))
        assertEquals(true, replaced.hasLocation)
        assertEquals("Sony", replaced.camera)
        assertEquals("two/", replaced.folderKey)
    }

    @Test fun cameraCriteriaUseTheSameCaseInsensitiveComparisonAsPhotoSearch() {
        val query = PhotoQuery(camera = "Pixel 10", folderKey = "Camera/")
        val favorite = PhotoQuery(camera = "PIXEL 10")
        assertTrue(query.includesFilter(favorite))
        assertTrue(query.filterConflicts(favorite).isEmpty())
        assertEquals(query, query.addFilterCriteria(favorite))
        assertEquals(listOf(photo(1).copy(camera = "pixel 10", folderKey = "Camera/")),
            query.apply(listOf(photo(1).copy(camera = "pixel 10", folderKey = "Camera/"))))
        assertEquals(setOf("Folder"), query.filterConflicts(PhotoQuery(folderKey = "camera/")))
    }

    @Test fun incomingIdCollisionsIncludingLongBoundaryNeverHideAnotherCondition() {
        val existing = rating.copy(id = Long.MAX_VALUE)
        val favorite = PhotoQuery(rules = listOf(lowIso.copy(id = Long.MAX_VALUE), lowIso.copy(id = Long.MIN_VALUE, value = 400.0)))
        val merged = PhotoQuery(rules = listOf(existing)).addFilterCriteria(favorite)
        assertEquals(3, merged.rules.size)
        assertEquals(3, merged.rules.map { it.id }.toSet().size)
        assertEquals(Long.MAX_VALUE, merged.rules.first().id)
    }

    @Test fun selectionMembershipIgnoresSearchAndOnlyRequiresTheSavedConditions() {
        val query = PhotoQuery(search = "Berlin", rules = listOf(rating, lowIso), hasLocation = true, camera = "Pixel 10")
        assertTrue(query.includesFilter(PhotoQuery(search = "Different text", rules = listOf(rating))))
        assertTrue(query.includesFilter(PhotoQuery(hasLocation = true)))
        assertFalse(query.includesFilter(PhotoQuery(hasLocation = false)))
        assertFalse(query.includesFilter(PhotoQuery(camera = "Canon")))
        assertFalse(query.includesFilter(PhotoQuery(rules = listOf(rating.copy(op = CompareOp.GREATER)))))
    }

    @Test fun defaultFavoritesMatchVisibleBoundaryValuesAndMissingGps() {
        val baseline = photo(1).copy(width = 4000, height = 3000, rating = 4)
        val photos = listOf(baseline, baseline.copy(id = 2, rating = 0, width = 2000), baseline.copy(id = 3, rating = 5, latitude = 52.52, longitude = 13.405))
        val defaults = FavoriteFilterCodec.defaults()
        assertEquals(listOf("4+ stars", "Unrated", "With GPS"), defaults.map { it.name })
        assertEquals(3, defaults.map { it.id }.distinct().size)
        val (fourStars, unrated, withGps) = defaults
        assertEquals(listOf(1L, 3L), fourStars.query.apply(photos).map { it.id })
        assertEquals(listOf(2L), unrated.query.apply(photos).map { it.id })
        assertEquals(listOf(3L), withGps.query.apply(photos).map { it.id })
    }

    private fun photo(id: Long) = Photo(id, "content://photos/$id", "Photo $id.jpg", "Camera", "Camera/", 1_700_000_000_000L)
}
