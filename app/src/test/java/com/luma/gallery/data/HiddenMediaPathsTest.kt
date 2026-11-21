package com.luma.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMediaPathsTest {
    @Test fun detectsDotFilesAndAncestorsWithoutHidingOrdinaryNames() {
        assertTrue(isHiddenMediaPath("DCIM/.thumbnails/", "photo.jpg"))
        assertTrue(isHiddenMediaPath("Pictures\\.private\\Trip", "photo.jpg"))
        assertTrue(isHiddenMediaPath("Pictures/Trip/", ".cover.jpg"))
        assertFalse(isHiddenMediaPath("Pictures/Trip.2026/", "photo.jpg"))
        assertFalse(isHiddenMediaPath("Pictures/Trip/", "photo.jpg"))
    }

    @Test fun acceptsVisualMimeAndOnlyRecognizedExtensionsForGenericFiles() {
        assertEquals("image/jpeg", hiddenMediaMimeType("preview", "image/jpeg"))
        assertEquals("image/jpeg", hiddenMediaMimeType("preview.JPG", "application/octet-stream"))
        assertEquals("video/mp4", hiddenMediaMimeType("movie.MP4", ""))
        assertNull(hiddenMediaMimeType(".thumbdata4-12345", "application/octet-stream"))
        assertNull(hiddenMediaMimeType(".nomedia", ""))
        assertNull(hiddenMediaMimeType("fake.jpg", "text/plain"))
        assertNull(hiddenMediaMimeType("notes.txt", "application/octet-stream"))
    }

    @Test fun localDocumentKeysMatchMediaStoreFolderIdentity() {
        val local = localDocumentPath("com.android.externalstorage.documents", "primary:DCIM/.thumbnails/preview.jpg")!!
        assertEquals("external_primary:DCIM/.thumbnails/", local.folderKey)
        val photo = Photo(1, "content://media/external_primary/images/media/1", "preview.jpg", ".thumbnails", local.folderKey, 0)
        assertEquals(indexedMediaFileKey(photo), local.fileKey)
        assertEquals("abcd-1234:Pictures/Trip/photo.jpg", localDocumentPath("com.android.externalstorage.documents", "ABCD-1234:Pictures/Trip/photo.jpg")!!.fileKey)
        assertNull(localDocumentPath("third.party.documents", "primary:Pictures/photo.jpg"))
        assertNull(localDocumentPath("com.android.externalstorage.documents", "primary:Pictures/../photo.jpg"))
    }

    @Test fun legacyAbsoluteMediaStoreKeysHaveVolumeRelativeDedupeAliases() {
        val primary = Photo(1, "content://media/external/images/media/1", "preview.jpg", ".thumbnails", "external:/storage/emulated/0/DCIM/.thumbnails/", 0)
        val primaryKeys = indexedMediaFileKeys(primary, "/storage/emulated/0")
        assertTrue("external_primary:DCIM/.thumbnails/preview.jpg" in primaryKeys)
        assertTrue(indexedMediaFileKey(primary) in primaryKeys)
        val sd = primary.copy(folderKey = "external:/storage/ABCD-1234/Pictures/Trip/")
        assertTrue("abcd-1234:Pictures/Trip/preview.jpg" in indexedMediaFileKeys(sd, "/storage/emulated/0"))
        assertEquals(setOf(indexedMediaFileKey(primary)), indexedMediaFileKeys(primary, "/unrelated/primary"))
        val modern = primary.copy(folderKey = "external_primary:DCIM/.thumbnails/")
        assertEquals(setOf(indexedMediaFileKey(modern)), indexedMediaFileKeys(modern, "/storage/emulated/0"))
    }
}
