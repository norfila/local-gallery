package com.luma.gallery.data

import android.content.res.Resources
import com.luma.gallery.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

data class FavoriteFilter(val id: Long, val name: String, val query: PhotoQuery)

/** Favorites remember metadata criteria; the current search text stays independent. */
fun PhotoQuery.filterKey(): String = buildString {
    append(hasLocation); append('|'); append(camera); append('|'); append(folderKey)
    rules.map { "${it.field.name}:${it.op.name}:${it.value}" }.sorted().forEach { append('|'); append(it) }
}

fun PhotoQuery.hasFilterCriteria(): Boolean = rules.isNotEmpty() || hasLocation != null || camera != null || folderKey != null

fun PhotoQuery.filterDescription(resources: Resources): String = buildList {
    rules.forEach { rule ->
        val number = if (rule.value % 1.0 == 0.0) rule.value.toLong().toString() else rule.value.toString()
        val value = when (rule.field) {
            MetadataField.DATE_TAKEN -> Instant.ofEpochMilli(rule.value.toLong()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            MetadataField.APERTURE -> "f/$number"
            else -> number + rule.field.unitText(resources).takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        }
        add("${resources.getString(rule.field.labelRes)} ${rule.op.symbol} $value")
    }
    hasLocation?.let { add(resources.getString(if (it) R.string.quick_with_gps else R.string.filter_without_gps)) }
    camera?.let { add(it) }
    folderKey?.let { add(it.substringAfter(':').trimEnd('/').substringAfterLast('/')) }
}.joinToString(" · ")

object FavoriteFilterCodec {
    /** Default names are written in the user's language when the favorites are first created. */
    fun defaults(highlyRated: String = "4+ stars", unrated: String = "Unrated", withGps: String = "With GPS"): List<FavoriteFilter> = listOf(
        FavoriteFilter(1, highlyRated, PhotoQuery(rules = listOf(FilterRule(1, MetadataField.RATING, CompareOp.GREATER_OR_EQUAL, 4.0)))),
        FavoriteFilter(2, unrated, PhotoQuery(rules = listOf(FilterRule(1, MetadataField.RATING, CompareOp.EQUAL, 0.0)))),
        FavoriteFilter(3, withGps, PhotoQuery(hasLocation = true)),
    )

    fun encode(filters: List<FavoriteFilter>): String = JSONArray().apply {
        filters.forEach { favorite -> put(JSONObject().apply {
            put("id", favorite.id); put("name", favorite.name)
            put("rules", JSONArray().apply { favorite.query.rules.forEach { rule -> put(JSONObject().apply {
                put("field", rule.field.name); put("op", rule.op.name); put("value", rule.value)
            }) } })
            favorite.query.hasLocation?.let { put("location", it) }
            favorite.query.camera?.let { put("camera", it) }
            favorite.query.folderKey?.let { put("folder", it) }
        }) }
    }.toString()

    fun decode(value: String?): List<FavoriteFilter> {
        if (value == null) return defaults()
        return runCatching {
            val array = JSONArray(value)
            (0 until array.length()).mapNotNull { index -> runCatching {
                val saved = array.getJSONObject(index)
                val rules = saved.getJSONArray("rules")
                val query = PhotoQuery(
                    rules = (0 until rules.length()).map { ruleIndex ->
                        val rule = rules.getJSONObject(ruleIndex)
                        val number = rule.getDouble("value").also { require(it.isFinite()) }
                        FilterRule(ruleIndex.toLong(), MetadataField.valueOf(rule.getString("field")), CompareOp.valueOf(rule.getString("op")), number)
                    },
                    hasLocation = if (saved.has("location")) saved.getBoolean("location") else null,
                    camera = if (saved.has("camera")) saved.getString("camera") else null,
                    folderKey = if (saved.has("folder")) saved.getString("folder") else null,
                )
                val name = saved.getString("name").trim().also { require(it.isNotEmpty()) }
                require(query.hasFilterCriteria())
                FavoriteFilter(saved.getLong("id"), name, query)
            }.getOrNull() }.distinctBy { it.id }
        }.getOrDefault(defaults())
    }
}
