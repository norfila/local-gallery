package com.luma.gallery.data

import androidx.annotation.StringRes
import com.luma.gallery.R

/** Favorites contain metadata criteria; typed search text is deliberately independent. */
fun PhotoQuery.includesFilter(criteria: PhotoQuery): Boolean =
    criteria.rules.all { expected -> rules.any { it.sameCriterion(expected) } } &&
        (criteria.hasLocation == null || criteria.hasLocation == hasLocation) &&
        (criteria.camera == null || criteria.camera.equals(camera, ignoreCase = true)) &&
        (criteria.folderKey == null || criteria.folderKey == folderKey)

/** Only single-choice categories require confirmation. Numeric ranges remain AND conditions. */
fun PhotoQuery.filterConflicts(criteria: PhotoQuery): Set<String> = buildSet {
    if (camera != null && criteria.camera != null && !camera.equals(criteria.camera, ignoreCase = true)) add("Camera")
    if (folderKey != null && criteria.folderKey != null && folderKey != criteria.folderKey) add("Folder")
    if (hasLocation != null && criteria.hasLocation != null && hasLocation != criteria.hasLocation) add("Location")
}

/** Add a favorite atomically, retaining the current search and independent conditions. */
fun PhotoQuery.addFilterCriteria(criteria: PhotoQuery, replaceConflicts: Boolean = false): PhotoQuery {
    if (!replaceConflicts && filterConflicts(criteria).isNotEmpty()) return this
    val combined = mutableListOf<FilterRule>()
    val usedIds = mutableSetOf<Long>()
    val largestId = (rules + criteria.rules).maxOfOrNull { it.id } ?: 0L
    var nextId = if (largestId == Long.MAX_VALUE) Long.MIN_VALUE else largestId + 1L
    fun freshId(): Long {
        while (nextId in usedIds) nextId++
        return nextId++
    }
    (rules + criteria.rules).forEach { rule ->
        if (combined.none { it.sameCriterion(rule) }) {
            val unique = if (rule.id in usedIds) rule.copy(id = freshId()) else rule
            combined += unique
            usedIds += unique.id
        }
    }
    return copy(
        rules = combined,
        hasLocation = criteria.hasLocation ?: hasLocation,
        camera = if (camera != null && camera.equals(criteria.camera, ignoreCase = true)) camera else criteria.camera ?: camera,
        folderKey = criteria.folderKey ?: folderKey,
    )
}

private fun FilterRule.sameCriterion(other: FilterRule): Boolean =
    field == other.field && op == other.op && value == other.value

