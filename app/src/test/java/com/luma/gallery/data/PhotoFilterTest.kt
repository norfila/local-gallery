package com.luma.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PhotoFilterTest {
    private val photo = Photo(
        id = 1,
        uri = "content://photos/1",
        name = "Berlin.jpg",
        folder = "Travel",
        folderKey = "DCIM/Travel/",
        dateTaken = 1_700_000_000_000L,
        width = 4000,
        height = 3000,
        size = 6_000_000,
        rating = 4,
        camera = "Example Camera",
        iso = 200,
        aperture = 2.8,
        focalLength = 35.0,
        latitude = 52.52,
        longitude = 13.405,
    )

    @Test fun comparisonsRespectAllBoundaries() {
        val expectations = mapOf(
            CompareOp.LESS to listOf(true, false, false),
            CompareOp.LESS_OR_EQUAL to listOf(true, true, false),
            CompareOp.EQUAL to listOf(false, true, false),
            CompareOp.NOT_EQUAL to listOf(true, false, true),
            CompareOp.GREATER_OR_EQUAL to listOf(false, true, true),
            CompareOp.GREATER to listOf(false, false, true),
        )
        expectations.forEach { (operation, expected) ->
            assertEquals(operation.symbol, expected, listOf(3.0, 4.0, 5.0).map { operation.matches(it, 4.0) })
        }
    }

    @Test fun nonFiniteNumbersNeverMatch() {
        CompareOp.entries.forEach { operation ->
            assertFalse(operation.matches(Double.NaN, 4.0))
            assertFalse(operation.matches(4.0, Double.POSITIVE_INFINITY))
        }
    }

    @Test fun missingMetadataDoesNotMatchIncludingNotEqual() {
        val unknown = photo.copy(iso = null, aperture = null, focalLength = null, width = 0, height = 0, size = 0, dateTaken = 0)
        MetadataField.entries.filter { it != MetadataField.RATING }.forEach { field ->
            CompareOp.entries.forEach { operation ->
                assertFalse("$field ${operation.symbol}", FilterRule(1, field, operation, 1.0).matches(unknown))
            }
        }
    }

    @Test fun unratedIsAValidZeroStarValue() {
        assertTrue(FilterRule(1, MetadataField.RATING, CompareOp.EQUAL, 0.0).matches(photo.copy(rating = 0)))
    }

    @Test fun numericFieldsUseTheirDisplayedUnits() {
        assertEquals(12.0, MetadataField.MEGAPIXELS.value(photo)!!, 0.0)
        assertEquals(6.0, MetadataField.FILE_SIZE_MB.value(photo)!!, 0.0)
        assertEquals(200.0, MetadataField.ISO.value(photo)!!, 0.0)
        assertEquals(photo.dateTaken.toDouble(), MetadataField.DATE_TAKEN.value(photo)!!, 0.0)
    }

    @Test fun rulesAreCombinedWithAnd() {
        val query = PhotoQuery(rules = listOf(
            FilterRule(1, MetadataField.RATING, CompareOp.GREATER_OR_EQUAL, 4.0),
            FilterRule(2, MetadataField.ISO, CompareOp.LESS_OR_EQUAL, 200.0),
        ))
        val lowRating = photo.copy(id = 2, rating = 3)
        val highIso = photo.copy(id = 3, iso = 400)
        assertEquals(listOf(photo), query.apply(listOf(photo, lowRating, highIso)))
    }

    @Test fun searchesNameCameraAndFolderCaseInsensitively() {
        listOf(" BERLIN ", "camera", "travel").forEach { search ->
            assertEquals(listOf(photo), PhotoQuery(search = search).apply(listOf(photo)))
        }
        assertTrue(PhotoQuery(search = "absent").apply(listOf(photo)).isEmpty())
    }

    @Test fun locationCameraAndFolderSelectionsAreCombined() {
        val query = PhotoQuery(hasLocation = true, camera = "example camera", folderKey = "DCIM/Travel/")
        assertEquals(listOf(photo), query.apply(listOf(photo, photo.copy(id = 2, longitude = null))))
        assertTrue(query.copy(hasLocation = false).apply(listOf(photo)).isEmpty())
        assertTrue(query.copy(folderKey = "other").apply(listOf(photo)).isEmpty())
    }

    @Test fun albumIncludesExplicitPhotosAndCurrentFolderContentsWithoutDuplicates() {
        val direct = photo.copy(id = 2, uri = "content://photos/2", folderKey = "other")
        val unrelated = photo.copy(id = 3, uri = "content://photos/3", folderKey = "other")
        val album = Album(1, "Trip", setOf(photo.uri, direct.uri), setOf(photo.folderKey))
        assertEquals(listOf(photo, direct), album.photos(listOf(photo, direct, unrelated)))
        assertTrue(album.contains(photo.copy(id = 4, uri = "content://photos/4")))
        assertFalse(album.contains(unrelated))
    }

    @Test fun hiddenMediaJoinsAnAlbumOnlyWhenAddedOnPurpose() {
        val hiddenInLinkedFolder = photo.copy(id = 5, uri = "file:///storage/emulated/0/DCIM/.private.jpg", hidden = true)
        val hiddenAdded = photo.copy(id = 6, uri = "file:///storage/emulated/0/.Secret/a.jpg", folderKey = "external_primary:.Secret/", hidden = true)
        val album = Album(1, "Trip", setOf(hiddenAdded.uri), setOf(photo.folderKey))
        assertTrue(album.contains(photo))
        assertFalse("A linked folder does not pull in hidden files", album.contains(hiddenInLinkedFolder))
        assertTrue(album.contains(hiddenAdded))
        assertEquals(listOf(photo, hiddenAdded), album.photos(listOf(photo, hiddenInLinkedFolder, hiddenAdded)))
    }

    @Test fun albumFolderLinksIncludeDescendantsWithoutSiblingPrefixMatches() {
        val album = Album(1, "Trip", folderKeys = setOf("external_primary:DCIM/Travel/"))
        assertTrue(album.contains(photo.copy(folderKey = "external_primary:DCIM/Travel/Berlin/")))
        assertFalse(album.contains(photo.copy(folderKey = "external_primary:DCIM/TravelMore/")))
        assertFalse(album.contains(photo.copy(folderKey = "other_volume:DCIM/Travel/")))
        assertFalse(Album(1, "Legacy", folderKeys = setOf("DCIM/Travel")).contains(photo.copy(folderKey = "DCIM/TravelMore/")))
    }

    @Test fun dateEqualsIncludesTheWholeLocalCalendarDay() {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.of(2026, 10, 25)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val equal = FilterRule(1, MetadataField.DATE_TAKEN, CompareOp.EQUAL, start.toDouble())
        assertTrue(equal.matches(photo.copy(dateTaken = start)))
        assertTrue(equal.matches(photo.copy(dateTaken = end - 1)))
        assertFalse(equal.matches(photo.copy(dateTaken = start - 1)))
        assertFalse(equal.matches(photo.copy(dateTaken = end)))
        assertFalse(equal.copy(op = CompareOp.NOT_EQUAL).matches(photo.copy(dateTaken = start + 1000)))
    }

    @Test fun chronologicalComparisonsNormalizeBothDateOperands() {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.of(2026, 3, 29)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val next = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val before = FilterRule(1, MetadataField.DATE_TAKEN, CompareOp.LESS, (start + 60_000).toDouble())
        assertFalse(before.matches(photo.copy(dateTaken = start)))
        assertTrue(before.matches(photo.copy(dateTaken = start - 1)))
        assertTrue(before.copy(op = CompareOp.LESS_OR_EQUAL).matches(photo.copy(dateTaken = next - 1)))
        assertFalse(before.copy(op = CompareOp.GREATER).matches(photo.copy(dateTaken = next - 1)))
        assertTrue(before.copy(op = CompareOp.GREATER).matches(photo.copy(dateTaken = next)))
    }
}
