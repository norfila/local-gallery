package com.luma.gallery.data

import android.content.res.Resources
import androidx.annotation.StringRes
import com.luma.gallery.R
import java.time.Instant
import java.time.ZoneId

/** Numeric comparisons deliberately use exact boundaries, just as entered by the user. */
enum class CompareOp(val symbol: String) {
    LESS("<"),
    LESS_OR_EQUAL("<="),
    EQUAL("="),
    NOT_EQUAL("!="),
    GREATER_OR_EQUAL(">="),
    GREATER(">"),
    ;

    fun matches(actual: Double, expected: Double): Boolean {
        if (!actual.isFinite() || !expected.isFinite()) return false
        return when (this) {
            LESS -> actual < expected
            LESS_OR_EQUAL -> actual <= expected
            EQUAL -> actual == expected
            NOT_EQUAL -> actual != expected
            GREATER_OR_EQUAL -> actual >= expected
            GREATER -> actual > expected
        }
    }
}

/** Units are symbols except for ratings, whose unit word is translated. */
enum class MetadataField(@StringRes val labelRes: Int, val unit: String = "", @StringRes val unitRes: Int = 0) {
    RATING(R.string.field_rating, unitRes = R.string.unit_stars),
    ISO(R.string.field_iso),
    APERTURE(R.string.field_aperture, "f/"),
    FOCAL_LENGTH(R.string.field_focal_length, "mm"),
    MEGAPIXELS(R.string.field_resolution, "MP"),
    FILE_SIZE_MB(R.string.field_file_size, "MB"),
    WIDTH(R.string.field_width, "px"),
    HEIGHT(R.string.field_height, "px"),
    DATE_TAKEN(R.string.field_date_taken),
    ;

    fun unitText(resources: Resources): String = if (unitRes != 0) resources.getString(unitRes) else unit

    /** Missing numeric metadata must not become zero in a comparison. */
    fun value(photo: Photo): Double? = when (this) {
        RATING -> photo.rating.toDouble().takeIf { photo.rating in 0..5 }
        ISO -> photo.iso?.takeIf { it > 0 }?.toDouble()
        APERTURE -> photo.aperture?.takeIf { it > 0 && it.isFinite() }
        FOCAL_LENGTH -> photo.focalLength?.takeIf { it > 0 && it.isFinite() }
        MEGAPIXELS -> photo.megapixels.takeIf { photo.width > 0 && photo.height > 0 }
        FILE_SIZE_MB -> (photo.size / 1_000_000.0).takeIf { photo.size > 0 }
        WIDTH -> photo.width.toDouble().takeIf { photo.width > 0 }
        HEIGHT -> photo.height.toDouble().takeIf { photo.height > 0 }
        DATE_TAKEN -> photo.dateTaken.toDouble().takeIf { photo.dateTaken > 0 }
    }
}

data class FilterRule(
    val id: Long,
    val field: MetadataField,
    val op: CompareOp,
    val value: Double,
) {
    fun matches(photo: Photo): Boolean {
        val actual = field.value(photo) ?: return false
        if (field != MetadataField.DATE_TAKEN) return op.matches(actual, value)
        if (!value.isFinite()) return false
        // Keep raw metadata in epoch milliseconds, but compare dates as calendar days.
        // This makes =, != and inclusive boundaries include the whole selected day.
        val zone = ZoneId.systemDefault()
        fun dayStart(epoch: Double): Double = Instant.ofEpochMilli(epoch.toLong())
            .atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli().toDouble()
        return op.matches(dayStart(actual), dayStart(value))
    }
}

data class PhotoQuery(
    val rules: List<FilterRule> = emptyList(),
    val search: String = "",
    val hasLocation: Boolean? = null,
    val camera: String? = null,
    val folderKey: String? = null,
) {
    fun apply(photos: List<Photo>): List<Photo> {
        val text = search.trim()
        if (rules.isEmpty() && text.isEmpty() && hasLocation == null && camera == null && folderKey == null) return photos
        return photos.filter { photo ->
            rules.all { it.matches(photo) } &&
                (hasLocation == null || photo.hasLocation == hasLocation) &&
                (camera == null || photo.camera.equals(camera, ignoreCase = true)) &&
                (folderKey == null || photo.folderKey == folderKey) &&
                (text.isEmpty() || photo.name.contains(text, ignoreCase = true) || photo.camera.contains(text, ignoreCase = true) ||
                    photo.folder.contains(text, ignoreCase = true) || photo.folderKey.substringAfter(':').contains(text, ignoreCase = true))
        }
    }
}
