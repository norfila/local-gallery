package com.luma.gallery.data

import java.util.Locale

/** Dot files and files inside dot directories are hidden even if a device indexes them. */
internal fun isHiddenMediaPath(path: String, name: String): Boolean =
    name.startsWith('.') || path.replace('\\', '/').split('/').any { it.startsWith('.') && it != "." && it != ".." }

/** Providers sometimes return octet-stream for ordinary pictures, but databases are not media. */
internal fun hiddenMediaMimeType(name: String, declaredType: String): String? {
    val mime = declaredType.substringBefore(';').trim().lowercase(Locale.ROOT)
    if (mime.startsWith("image/") || mime.startsWith("video/")) return mime
    if (mime.isNotEmpty() && mime != "application/octet-stream") return null
    return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "dng" -> "image/x-adobe-dng"
        "tif", "tiff" -> "image/tiff"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp", "3gpp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        "mpeg", "mpg" -> "video/mpeg"
        else -> null
    }
}

internal data class LocalDocumentPath(val volume: String, val path: String) {
    val folderKey: String get() = "$volume:${path.substringBeforeLast('/', "")}/"
    val fileKey: String get() = "$volume:$path"
}

/** Only ExternalStorageProvider has this documented local volume:path identity convention. */
internal fun localDocumentPath(authority: String, documentId: String): LocalDocumentPath? {
    if (authority != "com.android.externalstorage.documents") return null
    val split = documentId.indexOf(':')
    if (split <= 0) return null
    val rawVolume = documentId.substring(0, split)
    if ('/' in rawVolume || '\\' in rawVolume) return null
    val path = documentId.substring(split + 1).replace('\\', '/').trim('/')
    if (path.split('/').any { it == "." || it == ".." }) return null
    val volume = if (rawVolume.equals("primary", true)) "external_primary" else rawVolume.lowercase(Locale.ROOT)
    return LocalDocumentPath(volume, path)
}

internal fun indexedMediaFileKey(photo: Photo): String = "${photo.folderKey.trimEnd('/')}/${photo.name}"

/** Android 8/9 MediaStore uses an absolute DATA path instead of a volume-relative path. */
internal fun indexedMediaFileKeys(photo: Photo, primaryStorageRoot: String): Set<String> {
    val original = indexedMediaFileKey(photo)
    if (!photo.folderKey.startsWith("external:/")) return setOf(original)
    val absolute = photo.folderKey.removePrefix("external:").replace('\\', '/').trimEnd('/')
    val primary = primaryStorageRoot.replace('\\', '/').trimEnd('/')
    val canonical = when {
        primary.isNotEmpty() && (absolute == primary || absolute.startsWith("$primary/")) -> {
            val path = absolute.removePrefix(primary).trim('/')
            "external_primary:${if (path.isEmpty()) "" else "$path/"}${photo.name}"
        }
        absolute.startsWith("/storage/") -> {
            val remainder = absolute.removePrefix("/storage/")
            val volume = remainder.substringBefore('/')
            if (volume.isBlank() || volume == "emulated" || volume == "self") null
            else {
                val path = remainder.substringAfter('/', "").trim('/')
                "${volume.lowercase(Locale.ROOT)}:${if (path.isEmpty()) "" else "$path/"}${photo.name}"
            }
        }
        else -> null
    }
    return if (canonical == null) setOf(original) else setOf(original, canonical)
}
