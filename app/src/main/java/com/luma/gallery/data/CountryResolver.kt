package com.luma.gallery.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.util.Locale

/**
 * Offline lookup using bundled, simplified Natural Earth 1:10m land polygons. Run off the UI thread.
 * Points just outside the simplified coastline (beaches, harbours, boats near the shore, islets)
 * use the nearest country within [COASTAL_TOLERANCE_KM]. Open-sea coordinates stay unmatched.
 * No photo coordinates are sent to a geocoder or any other network service.
 */
class CountryResolver(context: Context) {
    private val applicationContext = context.applicationContext
    private val cache = object : LinkedHashMap<Pair<Double, Double>, Country?>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Double, Double>, Country?>?): Boolean = size > 10_000
    }

    @Synchronized
    fun resolve(latitude: Double, longitude: Double): Country? {
        val coordinate = latitude to longitude
        if (cache.containsKey(coordinate)) return cache[coordinate]
        return getIndex(applicationContext).resolve(latitude, longitude).also { cache[coordinate] = it }
    }

    companion object {
        const val COASTAL_TOLERANCE_KM = 25.0
        @Volatile private var sharedIndex: CountryIndex? = null

        private fun getIndex(context: Context): CountryIndex = sharedIndex ?: synchronized(this) {
            sharedIndex ?: readIndex(context).also { sharedIndex = it }
        }

        /** Streams the asset: building a JSON object tree for several MB of coordinates would waste memory. */
        private fun readIndex(context: Context): CountryIndex {
            val areas = ArrayList<CountryArea>()
            JsonReader(context.assets.open("countries.geojson").bufferedReader()).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "features") { reader.skipValue(); continue }
                    reader.beginArray()
                    while (reader.hasNext()) readFeature(reader)?.let(areas::add)
                    reader.endArray()
                }
                reader.endObject()
            }
            return CountryIndex(areas, COASTAL_TOLERANCE_KM)
        }

        private fun readFeature(reader: JsonReader): CountryArea? {
            var code = ""
            var name = ""
            var type = ""
            var polygons: List<CountryPolygon> = emptyList()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "properties" -> {
                        reader.beginObject()
                        while (reader.hasNext()) when (reader.nextName()) {
                            "code" -> code = reader.nextString()
                            "name" -> name = reader.nextString()
                            else -> reader.skipValue()
                        }
                        reader.endObject()
                    }
                    "geometry" -> {
                        reader.beginObject()
                        while (reader.hasNext()) when (reader.nextName()) {
                            "type" -> type = reader.nextString()
                            // GeoJSON permits "coordinates" before "type"; nesting depth tells them apart.
                            "coordinates" -> polygons = readCoordinates(reader)
                            else -> reader.skipValue()
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (code.isBlank() || polygons.isEmpty() || type !in setOf("Polygon", "MultiPolygon")) return null
            // A few Natural Earth map units share an ISO code with their parent country.
            // A common ISO name keeps those units together in the same photo group.
            val isoName = if (code.length == 2) Locale("", code).getDisplayCountry(Locale.ENGLISH) else ""
            return CountryArea(Country(code, isoName.takeIf { it.isNotBlank() && it != code } ?: name), polygons)
        }

        /** Reads Polygon (rings) or MultiPolygon (polygons of rings) coordinates. */
        private fun readCoordinates(reader: JsonReader): List<CountryPolygon> {
            val nested = readNested(reader)
            return when (depth(nested)) {
                3 -> listOf(polygon(nested))
                4 -> nested.map { polygon(it as List<*>) }
                else -> emptyList()
            }
        }

        private fun readNested(reader: JsonReader): List<Any> {
            val values = ArrayList<Any>()
            reader.beginArray()
            while (reader.hasNext()) {
                values += if (reader.peek() == JsonToken.BEGIN_ARRAY) readNested(reader) else reader.nextDouble()
            }
            reader.endArray()
            return values
        }

        private fun depth(value: Any?): Int = if (value is List<*>) 1 + depth(value.firstOrNull()) else 0

        private fun polygon(rings: List<*>): CountryPolygon = CountryPolygon(rings.map { ring ->
            (ring as List<*>).map { point ->
                val pair = point as List<*>
                CountryPoint(pair[0] as Double, pair[1] as Double)
            }
        })
    }
}
