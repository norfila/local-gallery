package com.luma.gallery.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sqrt

data class Country(val code: String, val name: String)

/**
 * Land containment. With a positive [coastalToleranceKm], a point outside every polygon uses the
 * nearest land within that distance: simplified coastlines and islets otherwise miss beach photos.
 * Open-sea coordinates further away stay unmatched.
 */
internal class CountryIndex(private val areas: List<CountryArea>, private val coastalToleranceKm: Double = 0.0) {
    fun resolve(latitude: Double, longitude: Double): Country? {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        areas.firstOrNull { it.contains(latitude, longitude) }?.let { return it.country }
        if (coastalToleranceKm <= 0) return null
        val margin = coastalToleranceKm / KM_PER_DEGREE
        var nearest: Country? = null
        var best = coastalToleranceKm
        for (area in areas) {
            if (!area.near(latitude, margin)) continue
            val distance = area.distanceKm(latitude, longitude, margin)
            if (distance <= best) { best = distance; nearest = area.country }
        }
        return nearest
    }
}

internal const val KM_PER_DEGREE = 111.32

internal class CountryArea(val country: Country, private val polygons: List<CountryPolygon>) {
    private val minLatitude = polygons.minOfOrNull { it.minLatitude } ?: 90.0
    private val maxLatitude = polygons.maxOfOrNull { it.maxLatitude } ?: -90.0

    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in minLatitude..maxLatitude && polygons.any { it.contains(latitude, longitude) }

    fun near(latitude: Double, marginDegrees: Double): Boolean =
        latitude in (minLatitude - marginDegrees)..(maxLatitude + marginDegrees)

    fun distanceKm(latitude: Double, longitude: Double, marginDegrees: Double): Double =
        polygons.minOfOrNull { it.distanceKm(latitude, longitude, marginDegrees) } ?: Double.POSITIVE_INFINITY
}

/** The first ring is land; remaining rings are holes (including enclaves). */
internal class CountryPolygon(rings: List<List<CountryPoint>>) {
    private val outer = CountryRing(rings.first())
    private val holes = rings.drop(1).map(::CountryRing)
    val minLatitude: Double get() = outer.minLatitude
    val maxLatitude: Double get() = outer.maxLatitude

    fun contains(latitude: Double, longitude: Double): Boolean =
        outer.contains(latitude, longitude) && holes.none { it.contains(latitude, longitude) }

    /** Distance to the outer coastline; holes are lakes or enclaves and never attract sea points. */
    fun distanceKm(latitude: Double, longitude: Double, marginDegrees: Double): Double =
        outer.distanceKm(latitude, longitude, marginDegrees)
}

internal data class CountryPoint(val longitude: Double, val latitude: Double)

private class CountryRing(points: List<CountryPoint>) {
    private val latitudes = DoubleArray(points.size) { points[it].latitude }
    private val longitudes: DoubleArray
    val minLatitude: Double = latitudes.minOrNull() ?: 90.0
    val maxLatitude: Double = latitudes.maxOrNull() ?: -90.0
    private val minLongitude: Double
    private val maxLongitude: Double

    init {
        // Natural Earth's Antarctic polygon includes the southern pole and both date-line
        // edges. Keep its full-world extent; ordinary date-line crossings are unwrapped.
        val polarCap = points.any { it.latitude == -90.0 || it.latitude == 90.0 } &&
            points.maxOf { it.longitude } - points.minOf { it.longitude } >= 359.99
        longitudes = DoubleArray(points.size)
        points.forEachIndexed { index, point ->
            var longitude = point.longitude
            if (index > 0 && !polarCap) {
                while (longitude - longitudes[index - 1] > 180) longitude -= 360
                while (longitude - longitudes[index - 1] < -180) longitude += 360
            }
            longitudes[index] = longitude
        }
        minLongitude = longitudes.minOrNull() ?: 180.0
        maxLongitude = longitudes.maxOrNull() ?: -180.0
    }

    fun contains(latitude: Double, longitude: Double): Boolean {
        if (longitudes.size < 3 || latitude !in minLatitude..maxLatitude) return false
        // Bring a query on either side of the date line into this ring's longitude range.
        val x = longitude + 360 * round(((minLongitude + maxLongitude) / 2 - longitude) / 360)
        if (x !in minLongitude..maxLongitude) return false
        var inside = false
        var previous = longitudes.lastIndex
        for (index in longitudes.indices) {
            val ax = longitudes[previous]
            val ay = latitudes[previous]
            val bx = longitudes[index]
            val by = latitudes[index]
            if (onSegment(x, latitude, ax, ay, bx, by)) return true
            if ((ay > latitude) != (by > latitude) && x < (bx - ax) * (latitude - ay) / (by - ay) + ax) inside = !inside
            previous = index
        }
        return inside
    }

    /** Approximate distance to the nearest edge, in a local equirectangular projection. */
    fun distanceKm(latitude: Double, longitude: Double, marginDegrees: Double): Double {
        if (longitudes.size < 2 || latitude !in (minLatitude - marginDegrees)..(maxLatitude + marginDegrees)) return Double.POSITIVE_INFINITY
        val x = longitude + 360 * round(((minLongitude + maxLongitude) / 2 - longitude) / 360)
        val scale = cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
        if (x < minLongitude - marginDegrees / scale || x > maxLongitude + marginDegrees / scale) return Double.POSITIVE_INFINITY
        var best = Double.POSITIVE_INFINITY
        var previous = longitudes.lastIndex
        for (index in longitudes.indices) {
            val ax = (longitudes[previous] - x) * scale
            val ay = latitudes[previous] - latitude
            val bx = (longitudes[index] - x) * scale
            val by = latitudes[index] - latitude
            val dx = bx - ax
            val dy = by - ay
            val length = dx * dx + dy * dy
            val t = if (length == 0.0) 0.0 else (-(ax * dx + ay * dy) / length).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            best = minOf(best, px * px + py * py)
            previous = index
        }
        return sqrt(best) * KM_PER_DEGREE
    }

    private fun onSegment(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        val tolerance = 1e-10
        if (x < minOf(ax, bx) - tolerance || x > maxOf(ax, bx) + tolerance ||
            y < minOf(ay, by) - tolerance || y > maxOf(ay, by) + tolerance) return false
        return abs((x - ax) * (by - ay) - (y - ay) * (bx - ax)) <= tolerance
    }
}

enum class CountryGroupKind { COUNTRY, NO_LOCATION, NOT_SCANNED, UNMATCHED }

data class CountryPhotoGroup(val key: String, val title: String, val photos: List<Photo>, val kind: CountryGroupKind)

/** Kept separate from UI so incomplete metadata is never presented as a missing GPS tag. */
fun groupPhotosByCountry(photos: List<Photo>, resolve: (Double, Double) -> Country?): List<CountryPhotoGroup> {
    data class GroupKey(val key: String, val title: String, val kind: CountryGroupKind)
    return photos.groupBy { photo ->
        val latitude = photo.latitude
        val longitude = photo.longitude
        when {
            latitude != null && longitude != null -> {
                val country = resolve(latitude, longitude)
                if (country != null) GroupKey(country.code, country.name, CountryGroupKind.COUNTRY)
                else GroupKey("unmatched", "Unmatched GPS", CountryGroupKind.UNMATCHED)
            }
            !photo.metadataLoaded -> GroupKey("not-scanned", "Not scanned yet", CountryGroupKind.NOT_SCANNED)
            else -> GroupKey("no-location", "No location", CountryGroupKind.NO_LOCATION)
        }
    }.map { (group, contents) -> CountryPhotoGroup(group.key, group.title, contents, group.kind) }
        .sortedWith(compareBy<CountryPhotoGroup> { it.kind.ordinal }.thenBy { it.title.lowercase() })
}
