package com.luma.gallery.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Checks the actual bundled geometry, including islands, enclaves and the date line. */
class CountryResolverTest {
    private val resolver = CountryResolver(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun recognizesContinentsAndHolidayIslandsOffline() {
        val coordinates = listOf(
            Triple("DE", 52.52, 13.405), // Berlin
            Triple("AT", 48.2082, 16.3738), // Vienna
            Triple("US", 37.7749, -122.4194), // San Francisco
            Triple("US", 21.35, -157.87), // Oahu, Hawaii
            Triple("FJ", -17.8, 177.95), // Viti Levu, Fiji
            Triple("GB", 51.5074, -.1278), // London
            Triple("JP", 35.6762, 139.6503), // Tokyo
            Triple("NZ", -41.2, 174.75), // Wellington region
            Triple("AQ", -85.0, 30.0), // Antarctic polar cap
            Triple("SC", -4.62, 55.45), // Victoria, Mahé, Seychelles
            Triple("SC", -4.33, 55.73), // Praslin, Seychelles
            Triple("SC", -4.358, 55.835), // La Digue, Seychelles
            Triple("MV", 4.1755, 73.5093), // Malé, Maldives
            Triple("MU", -20.16, 57.50), // Mauritius
        )
        coordinates.forEach { (code, latitude, longitude) ->
            assertEquals("$latitude, $longitude", code, resolver.resolve(latitude, longitude)?.code)
        }
    }

    @Test fun countryEnclaveIsNotAssignedToItsSurroundingCountry() {
        assertEquals("LS", resolver.resolve(-29.3151, 27.4869)?.code) // Maseru, Lesotho
        assertEquals("ZA", resolver.resolve(-28.75, 26.9)?.code) // South Africa
    }

    @Test fun beachAndHarbourPhotosJustOffTheCoastlineMatch() {
        assertEquals("SC", resolver.resolve(-4.6145, 55.4290)?.code) // Beau Vallon bay, Mahé
        assertEquals("GR", resolver.resolve(37.4467, 25.3289)?.code) // Mykonos harbour
    }

    @Test fun offshoreAndInvalidPointsStayUnmatched() {
        assertNull(resolver.resolve(0.0, -30.0))
        assertNull(resolver.resolve(0.0, 0.0))
        assertNull(resolver.resolve(0.0, 180.0))
        assertNull(resolver.resolve(91.0, 10.0))
        assertNull(resolver.resolve(Double.NaN, 10.0))
    }
}
