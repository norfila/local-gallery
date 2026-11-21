package com.luma.gallery.widget

import com.luma.gallery.R
import com.luma.gallery.data.Album
import com.luma.gallery.data.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumWidgetSnapshotTest {
    private fun photo(id: Long, folder: String) = Photo(id, "content://photos/$id", "Photo $id", "Travel", folder, id)

    @Test fun coversAndCountsUseLinkedPhotosAndFolderDescendantsWithoutDuplication() {
        val library = listOf(photo(3, "external:DCIM/Trip/Day2/"), photo(2, "external:DCIM/Trip/"), photo(1, "external:Pictures/"))
        val album = Album(1, "Travel", photoUris = setOf(library[0].uri), folderKeys = setOf("external:DCIM/Trip/"))
        assertEquals(AlbumWidgetSnapshot(WidgetLabel.Text("Travel"), WidgetLabel.Plural(R.plurals.photo_count, 2), library[0].uri), snapshot(1, album, library, true))
    }

    @Test fun hiddenMediaAddedToAnAlbumNeverReachesTheHomeScreen() {
        val visible = photo(1, "external:DCIM/Trip/")
        val hidden = photo(2, "external:.Private/").copy(hidden = true)
        val album = Album(1, "Trip", photoUris = setOf(visible.uri, hidden.uri), coverUri = hidden.uri)
        assertEquals(AlbumWidgetSnapshot(WidgetLabel.Text("Trip"), WidgetLabel.Plural(R.plurals.photo_count, 1), visible.uri),
            snapshot(1, album, listOf(visible, hidden), true))
        assertEquals(WidgetLabel.Res(R.string.widget_no_photos), snapshot(1, album.copy(photoUris = setOf(hidden.uri)), listOf(hidden), true).detail)
    }

    @Test fun removedAlbumAndRevokedAccessNeverExposeAnOldCover() {
        assertEquals(WidgetLabel.Res(R.string.widget_album_removed), snapshot(1, null, emptyList(), true).title)
        val state = snapshot(1, Album(1, "Private"), null, false)
        assertEquals(WidgetLabel.Res(R.string.widget_allow_access), state.detail)
        assertNull(state.coverUri)
    }

    @Test fun unconfiguredEmptyAndUnavailableStatesAreActionable() {
        assertEquals(WidgetLabel.Res(R.string.widget_choose_album), snapshot(null, null, null, false).title)
        assertEquals(WidgetLabel.Res(R.string.widget_no_photos), snapshot(1, Album(1, "Empty"), emptyList(), true).detail)
        assertEquals(WidgetLabel.Res(R.string.widget_album_unavailable), snapshot(1, null, null, true, albumsLoaded = false).title)
        assertEquals(WidgetLabel.Res(R.string.widget_tap_to_refresh), snapshot(1, Album(1, "Trip"), null, true).detail)
    }

    @Test fun customCoverUsesOnlyAnAccessibleMemberAndFallsBackWhenUnlinkedOrDeleted() {
        val first = photo(3, "external:DCIM/Trip/")
        val chosen = photo(2, "external:DCIM/Trip/Day2/")
        val outside = photo(1, "external:Pictures/")
        val library = listOf(first, chosen, outside)
        val album = Album(1, "Trip", folderKeys = setOf("external:DCIM/Trip/"), coverUri = chosen.uri)
        assertEquals(chosen, album.coverPhoto(library))
        assertEquals(chosen.uri, snapshot(1, album, library, true).coverUri)
        assertEquals(first.uri, snapshot(1, album, listOf(first, outside), true).coverUri)
        assertEquals(first, album.copy(coverUri = outside.uri).coverPhoto(library))
        assertNull(album.copy(folderKeys = emptySet()).coverPhoto(library))
        assertEquals(first, album.copy(coverUri = null).coverPhoto(library))
    }

    @Test fun oneCellWidgetHidesSecondaryTextWhileLargerWidgetsKeepIt() {
        org.junit.Assert.assertTrue(compactWidget(40, 40))
        org.junit.Assert.assertTrue(compactWidget(72, 140))
        org.junit.Assert.assertTrue(compactWidget(140, 72))
        org.junit.Assert.assertFalse(compactWidget(140, 140))
    }

    @Test fun videoAndMixedAlbumsDescribeTheirMediaAndKeepChosenCover() {
        val image = photo(1, "external:DCIM/Trip/")
        val video = photo(2, "external:DCIM/Trip/").copy(mimeType = "video/mp4", durationMillis = 1_000)
        val album = Album(1, "Trip", folderKeys = setOf("external:DCIM/Trip/"), coverUri = video.uri)
        assertEquals(WidgetLabel.Plural(R.plurals.video_count, 1), snapshot(1, album, listOf(video), true).detail)
        assertEquals(WidgetLabel.Plural(R.plurals.item_count, 2), snapshot(1, album, listOf(image, video), true).detail)
        assertEquals(video.uri, snapshot(1, album, listOf(image, video), true).coverUri)
    }
}
