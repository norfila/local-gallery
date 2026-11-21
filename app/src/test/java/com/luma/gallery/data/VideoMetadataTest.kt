package com.luma.gallery.data

import org.junit.Assert.*
import org.junit.Test

class VideoMetadataTest {
    @Test fun parsesIso6709DecimalCoordinatesAndRejectsMalformedOrOutOfRangeValues() {
        assertEquals(48.137 to 11.575, parseVideoLocation("+48.1370+011.5750/"))
        assertEquals(-33.86 to 151.2, parseVideoLocation("-33.8600+151.2000+0030.0/"))
        assertEquals(0.0 to 0.0, parseVideoLocation("+00.0000+000.0000/"))
        for (value in listOf(null, "", "48,11", "+91.0+011.0/", "+48.0-181.0/", "NaN")) assertNull(parseVideoLocation(value))
    }

    @Test fun parsesUtcContainerDatesWithoutInventingIncompleteDates() {
        val expected = 1_700_000_000_000L
        assertEquals(expected, parseVideoDate("20231114T221320.000Z"))
        assertEquals(expected, parseVideoDate("20231114T221320Z"))
        assertEquals(expected, parseVideoDate("2023-11-14T22:13:20Z"))
        assertNull(parseVideoDate("2023"))
        assertNull(parseVideoDate("garbage"))
    }

    @Test fun videoIdentityDependsOnMimeTypeAndPreservesPhotoDefaults() {
        val photo = Photo(1, "content://media/one", "name", "folder", "folder/", 0)
        assertFalse(photo.isVideo)
        assertEquals(0L, photo.durationMillis)
        assertTrue(photo.copy(mimeType = "video/mp4").isVideo)
        assertTrue(photo.copy(mimeType = "VIDEO/WEBM").isVideo)
    }
}
