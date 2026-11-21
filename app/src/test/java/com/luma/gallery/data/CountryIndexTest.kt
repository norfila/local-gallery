package com.luma.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryIndexTest {
    private val country = Country("XX", "Test country")

    @Test fun boundedLandContainsEdgesButDoesNotClaimNearbySea() {
        val index = index(polygon(ring(0.0 to 0.0, 5.0 to 0.0, 5.0 to 5.0, 0.0 to 5.0)))
        assertEquals(country, index.resolve(2.0, 2.0))
        assertEquals(country, index.resolve(2.0, 0.0))
        assertEquals(country, index.resolve(5.0, 5.0))
        assertNull(index.resolve(2.0, -.0001))
        assertNull(index.resolve(2.0, 80.0))
    }

    @Test fun enclavesAndHolesAreExcluded() {
        val index = index(polygon(
            ring(0.0 to 0.0, 5.0 to 0.0, 5.0 to 5.0, 0.0 to 5.0),
            ring(1.0 to 1.0, 4.0 to 1.0, 4.0 to 4.0, 1.0 to 4.0),
        ))
        assertEquals(country, index.resolve(.5, .5))
        assertNull(index.resolve(2.0, 2.0))
        assertNull(index.resolve(1.0, 2.0))
    }

    @Test fun disconnectedIslandsBelongToTheSameCountry() {
        val index = index(
            polygon(ring(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)),
            polygon(ring(10.0 to 10.0, 11.0 to 10.0, 11.0 to 11.0, 10.0 to 11.0)),
        )
        assertEquals(country, index.resolve(.5, .5))
        assertEquals(country, index.resolve(10.5, 10.5))
        assertNull(index.resolve(5.0, 5.0))
    }

    @Test fun dateLineCrossingsDoNotTurnIntoAWorldWidePolygon() {
        val index = index(polygon(ring(170.0 to -10.0, -170.0 to -10.0, -170.0 to 10.0, 170.0 to 10.0)))
        assertEquals(country, index.resolve(0.0, 175.0))
        assertEquals(country, index.resolve(0.0, -175.0))
        assertEquals(country, index.resolve(0.0, -180.0))
        assertEquals(country, index.resolve(0.0, 180.0))
        assertNull(index.resolve(0.0, 0.0))
        assertNull(index.resolve(0.0, 169.0))
    }

    @Test fun polarCapRetainsBothDateLineEdges() {
        val index = index(polygon(ring(-180.0 to -90.0, 180.0 to -90.0, 180.0 to -70.0, 0.0 to -70.0, -180.0 to -70.0)))
        assertEquals(country, index.resolve(-85.0, 30.0))
        assertEquals(country, index.resolve(-85.0, -179.0))
        assertEquals(country, index.resolve(-85.0, 179.0))
        assertNull(index.resolve(-60.0, 30.0))
    }

    @Test fun invalidGpsNeverMatches() {
        val index = index(polygon(ring(-180.0 to -90.0, 180.0 to -90.0, 180.0 to 90.0, -180.0 to 90.0)))
        assertNull(index.resolve(Double.NaN, 0.0))
        assertNull(index.resolve(0.0, Double.POSITIVE_INFINITY))
        assertNull(index.resolve(91.0, 0.0))
        assertNull(index.resolve(0.0, 181.0))
    }

    @Test fun coastalToleranceAssignsNearbyBeachesButNotOpenSea() {
        val island = CountryArea(Country("SC", "Seychelles"), listOf(polygon(ring(55.40 to -4.70, 55.50 to -4.70, 55.50 to -4.60, 55.40 to -4.60))))
        val mainland = CountryArea(country, listOf(polygon(ring(56.0 to -5.0, 57.0 to -5.0, 57.0 to -4.0, 56.0 to -4.0))))
        val index = CountryIndex(listOf(island, mainland), coastalToleranceKm = 25.0)
        assertEquals("SC", index.resolve(-4.65, 55.52)?.code) // about 2 km east of the coastline
        assertEquals("SC", index.resolve(-4.65, 55.60)?.code) // nearer the island than the mainland
        assertEquals("XX", index.resolve(-4.5, 55.95)?.code) // nearer the mainland
        assertNull(index.resolve(-4.65, 55.76)) // about 29 km from both
        assertNull(index.resolve(0.0, -30.0))
    }

    @Test fun coastalToleranceWorksAcrossTheDateLine() {
        val index = CountryIndex(listOf(CountryArea(country, listOf(polygon(ring(179.9 to -1.0, -179.9 to -1.0, -179.9 to 1.0, 179.9 to 1.0))))), 25.0)
        assertEquals(country, index.resolve(0.0, -179.75))
        assertEquals(country, index.resolve(0.0, 179.75))
        assertNull(index.resolve(0.0, 179.0))
    }

    @Test fun groupingSeparatesUnknownMetadataFromMissingLocationAndOffshoreGps() {
        val photos = listOf(
            photo(1).copy(latitude = 1.0, longitude = 1.0),
            photo(2).copy(metadataLoaded = true),
            photo(3),
            photo(4).copy(latitude = 0.0, longitude = -30.0, metadataLoaded = true),
        )
        val groups = groupPhotosByCountry(photos) { lat, lon -> if (lat == 1.0 && lon == 1.0) country else null }
        assertEquals(listOf(CountryGroupKind.COUNTRY, CountryGroupKind.NO_LOCATION, CountryGroupKind.NOT_SCANNED, CountryGroupKind.UNMATCHED), groups.map { it.kind })
        assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L), listOf(4L)), groups.map { it.photos.map(Photo::id) })
    }

    @Test fun countryGroupsAreAlphabeticalAndKeepAllMatchingPhotos() {
        val photos = listOf(photo(1), photo(2), photo(3)).map { it.copy(latitude = it.id.toDouble(), longitude = 2.0) }
        val groups = groupPhotosByCountry(photos) { lat, _ -> if (lat < 3) Country("DE", "Germany") else Country("AT", "Austria") }
        assertEquals(listOf("AT", "DE"), groups.map { it.key })
        assertEquals(listOf(1, 2), groups.map { it.photos.size })
    }

    private fun index(vararg polygons: CountryPolygon) = CountryIndex(listOf(CountryArea(country, polygons.toList())))
    private fun polygon(vararg rings: List<CountryPoint>) = CountryPolygon(rings.toList())
    private fun ring(vararg points: Pair<Double, Double>) = points.map { CountryPoint(it.first, it.second) }.let { it + it.first() }
    private fun photo(id: Long) = Photo(id, "content://countries/$id", "$id.jpg", "Travel", "DCIM/Travel/", 0)
}
