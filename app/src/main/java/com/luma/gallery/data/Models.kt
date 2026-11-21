package com.luma.gallery.data

data class Photo(
    val id: Long,
    val uri: String,
    val name: String,
    val folder: String,
    val folderKey: String,
    val dateTaken: Long,
    val dateModified: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    val mimeType: String = "image/jpeg",
    val rating: Int = 0,
    val embeddedRating: Int = 0,
    val camera: String = "",
    val lens: String = "",
    val iso: Int? = null,
    val aperture: Double? = null,
    val focalLength: Double? = null,
    val exposureSeconds: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val metadataLoaded: Boolean = false,
    /** Canonical MediaStore identity for an external request; uri retains the sender's read grant. */
    val libraryUri: String? = null,
    val durationMillis: Long = 0,
    /** In a .nomedia or dot folder (or a dot file); such items appear only on the Hidden page. */
    val hidden: Boolean = false,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/", ignoreCase = true)
    val hasLocation: Boolean get() = latitude != null && longitude != null
    val megapixels: Double get() = width.toDouble() * height / 1_000_000
}

data class Album(
    val id: Long,
    val name: String,
    val photoUris: Set<String> = emptySet(),
    val folderKeys: Set<String> = emptySet(),
    val coverUri: String? = null,
) {
    /** Linked folders bring in only visible media; a hidden item joins an album only when added on purpose. */
    fun contains(photo: Photo): Boolean = photo.uri in photoUris || !photo.hidden && folderKeys.any { key ->
        photo.folderKey == key || (key.endsWith("/") && photo.folderKey.startsWith(key))
    }
    fun photos(library: List<Photo>): List<Photo> = library.filter(::contains)

    /** A stale, deleted, or unlinked cover must never show a photo from another album. */
    fun coverPhoto(library: List<Photo>): Photo? =
        library.firstOrNull { it.uri == coverUri && contains(it) } ?: library.firstOrNull(::contains)
}
