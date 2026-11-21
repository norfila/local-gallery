package com.luma.gallery.data

/**
 * Destination paths are relative to the source's storage volume, never arbitrary filesystem paths.
 *
 * Android keeps photos in DCIM and Pictures, and videos in DCIM, Movies and Pictures. So DCIM and Pictures can
 * take a selection of both, while Movies stays closed to photos.
 */
object PhotoMovePaths {
    fun normalize(path: String, video: Boolean): String? {
        if (path.startsWith('/') || '\\' in path || path.any { it.code < 32 }) return null
        val parts = path.trimEnd('/').split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || ':' in it }) return null
        val roots = if (video) setOf("DCIM", "Movies", "Pictures") else setOf("DCIM", "Pictures")
        if (parts.first() !in roots) return null
        return parts.joinToString("/", postfix = "/")
    }

    fun newFolder(name: String, video: Boolean): String? =
        folderName(name)?.let { normalize("${if (video) "Movies" else "Pictures"}/$it", video) }

    /** Where a new folder for [moving] is created: videos alone go to Movies, anything with a photo to Pictures. */
    fun newFolderRoot(moving: List<Photo>): String = if (moving.isNotEmpty() && moving.all { it.isVideo }) "Movies" else "Pictures"

    fun newFolder(name: String, moving: List<Photo>): String? {
        val path = "${newFolderRoot(moving)}/${folderName(name) ?: return null}"
        return moving.map { it.isVideo }.distinct().map { normalize(path, it) }.reduceOrNull { all, next -> next.takeIf { it == all } }
    }

    private fun folderName(name: String): String? {
        if (name.any { it.code < 32 }) return null
        val cleaned = name.trim()
        if (cleaned.isEmpty() || cleaned.length > 100 || cleaned.startsWith('.') ||
            cleaned.any { it in "/\\:*?\"<>|" || it.code < 32 }) return null
        return cleaned
    }

    /**
     * Folders that can take all of [moving]: valid for every kind in it, on a volume it uses. Each item stays on
     * its own volume. The folder the whole selection already shares is left out.
     */
    fun sharedDestinations(library: List<Photo>, moving: List<Photo>): List<String> {
        val kinds = moving.mapTo(hashSetOf()) { it.isVideo }
        val current = moving.map { it.folderKey.substringAfter(':') }.distinct().singleOrNull().orEmpty()
        return moving.mapTo(hashSetOf()) { it.folderKey.substringBefore(':', "") }.flatMap { volume ->
            kinds.map { video -> destinations(library, volume, current, video).toSet() }.reduce { all, next -> all intersect next }
        }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /**
     * The folders the library already has media in, like the Folders page: an empty Pictures or DCIM/Camera is not
     * offered, and appears once something is in it. A folder that does not exist yet is made with "New folder".
     */
    fun destinations(photos: List<Photo>, volume: String, currentPath: String, video: Boolean): List<String> =
        buildSet {
            photos.forEach { photo ->
                if (photo.folderKey.substringBefore(':', "") == volume) {
                    normalize(photo.folderKey.substringAfter(':'), video)?.let(::add)
                }
            }
        }.filter { it != currentPath }.sortedWith(String.CASE_INSENSITIVE_ORDER)
}
