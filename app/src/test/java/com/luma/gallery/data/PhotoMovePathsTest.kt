package com.luma.gallery.data

import org.junit.Assert.*
import org.junit.Test

class PhotoMovePathsTest {
    @Test fun confinesDestinationsToCompatiblePublicFolders() {
        assertEquals("Pictures/Trips/", PhotoMovePaths.normalize("Pictures/Trips", false))
        assertEquals("DCIM/Camera/", PhotoMovePaths.normalize("DCIM/Camera/", false))
        assertEquals("Movies/Trips/", PhotoMovePaths.normalize("Movies/Trips/", true))
        listOf("../Pictures/", "/Pictures/", "Pictures/../DCIM/", "Pictures//Trip/", "Pictures/./Trip/", "Pictures\\Trip", "Android/data/", "external_primary:Pictures/", "Pictures/\u0000/").forEach {
            assertNull(it, PhotoMovePaths.normalize(it, false))
        }
        // Android takes videos in Pictures, but no photos in Movies.
        assertNull(PhotoMovePaths.normalize("Movies/", false))
        assertEquals("Pictures/Trips/", PhotoMovePaths.normalize("Pictures/Trips", true))
        assertNull(PhotoMovePaths.normalize("Music/", true))
    }

    @Test fun createsOneFolderWithoutAllowingTraversalOrHiddenDirectories() {
        assertEquals("Pictures/Summer 2026/", PhotoMovePaths.newFolder(" Summer 2026 ", false))
        assertEquals("Movies/Holidays/", PhotoMovePaths.newFolder("Holidays", true))
        listOf("", " ", ".", "..", ".hidden", "../DCIM", "Trips/Day 1", "Trips\\Day 1", "a:b", "a\n").forEach {
            assertNull(it, PhotoMovePaths.newFolder(it, false))
        }
    }

    @Test fun offersOnlyOtherFoldersOnTheSourceVolume() {
        fun photo(key: String) = Photo(1, "content://media/1", "a.jpg", "folder", key, 0)
        val destinations = PhotoMovePaths.destinations(listOf(
            photo("external_primary:Pictures/Current/"), photo("external_primary:Pictures/Trips/"),
            photo("external_primary:Pictures/Trips/"), photo("external_primary:Movies/Trips/"),
            photo("sdcard:Pictures/Other/"), photo("external_primary:Android/data/private/"),
        ), "external_primary", "Pictures/Current/", false)
        // Only folders that hold media are offered: no empty Pictures or DCIM/Camera.
        assertEquals(listOf("Pictures/Trips/"), destinations)
        // Pictures itself appears once something is in it.
        assertEquals(listOf("Pictures/", "Pictures/Trips/"), PhotoMovePaths.destinations(listOf(
            photo("external_primary:Pictures/"), photo("external_primary:Pictures/Trips/"),
        ), "external_primary", "Pictures/Current/", false))
    }

    @Test fun aSelectionIsOfferedOnlyFoldersThatTakeAllOfIt() {
        fun item(key: String, video: Boolean = false) =
            Photo(1, "content://media/1", "a", "folder", key, 0, mimeType = if (video) "video/mp4" else "image/jpeg")
        val library = listOf(item("external_primary:Pictures/Trips/"), item("external_primary:Pictures/Home/"),
            item("external_primary:DCIM/Camera/"), item("external_primary:DCIM/Drone/", video = true),
            item("external_primary:Movies/Clips/", video = true), item("sdcard:Pictures/Card/"))

        // Photos from one folder: that folder is left out, other volumes are not offered.
        val photos = listOf(item("external_primary:Pictures/Trips/"), item("external_primary:Pictures/Trips/"))
        assertEquals(listOf("DCIM/Camera/", "DCIM/Drone/", "Pictures/Home/"), PhotoMovePaths.sharedDestinations(library, photos))
        assertEquals("Pictures/New/", PhotoMovePaths.newFolder("New", photos))

        // Photos from two folders: both stay on offer, since some of the selection is not there yet.
        val spread = listOf(item("external_primary:Pictures/Trips/"), item("external_primary:Pictures/Home/"))
        assertEquals(listOf("DCIM/Camera/", "DCIM/Drone/", "Pictures/Home/", "Pictures/Trips/"), PhotoMovePaths.sharedDestinations(library, spread))

        // Videos alone may go anywhere a photo may, and to Movies; their own new folders are made in Movies.
        val videos = listOf(item("external_primary:Movies/Clips/", video = true))
        assertEquals(listOf("DCIM/Camera/", "DCIM/Drone/", "Pictures/Home/", "Pictures/Trips/"),
            PhotoMovePaths.sharedDestinations(library, videos))
        assertEquals("Movies/New/", PhotoMovePaths.newFolder("New", videos))

        // Photos and videos together: DCIM and Pictures take both, Movies takes no photos.
        val mixed = listOf(item("external_primary:Pictures/Trips/"), item("external_primary:Movies/Clips/", video = true))
        assertEquals(listOf("DCIM/Camera/", "DCIM/Drone/", "Pictures/Home/", "Pictures/Trips/"), PhotoMovePaths.sharedDestinations(library, mixed))
        assertEquals("Pictures", PhotoMovePaths.newFolderRoot(mixed))
        assertEquals("Pictures/New/", PhotoMovePaths.newFolder("New", mixed))
        assertNull(PhotoMovePaths.newFolder("../New", mixed))
    }
}
