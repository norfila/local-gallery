package com.luma.gallery.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoMetadataDetailsReaderTest {
    @Before fun seedFixture() {
        seedMetadataFixture(ApplicationProvider.getApplicationContext())
    }

    @Test fun keepsExactSharedProviderUriAndReadsMetadataWithoutMediaStoreLocationPermission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.luma.gallery.test.metadata/photo")
        val photo = ExternalPhotoReader(context).read(ExternalPhotoRequest(uri, "image/jpeg"))!!
        assertEquals(48.137, photo.latitude!!, 0.0001)
        val details = PhotoMetadataDetailsReader.load(context, photo)
        val location = details.categories.single { it.section == MetadataSection.LOCATION }.entries
        assertTrue("The shared stream contains authorized GPS metadata", location.any { it.label == "Coordinates" })
        assertTrue(location.any { it.label == "GPS Latitude" })
        assertFalse(location.any { it.value.contains("Allow photo location access") })
        assertFalse(location.any { it.value.contains("could not supply") })
        assertNull(details.notice)
        val other = details.categories.single { it.section == MetadataSection.OTHER }.entries
        assertTrue("The supplied raw packet is available for an explicitly shared provider stream", other.any { it.label == "Raw XMP" })
    }

    @Test fun unavailableTemporarySourceShowsNoticeWithoutRepeatingCachedGps() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val photo = Photo(
            id = -1, uri = "content://com.luma.gallery.test.metadata/missing", name = "Unavailable photo",
            folder = "Opened photo", folderKey = "external:", dateTaken = 0,
            latitude = 48.137, longitude = 11.575, metadataLoaded = true,
        )
        val details = PhotoMetadataDetailsReader.load(context, photo)
        assertTrue(details.notice?.contains("could not be read") == true)
        val location = details.categories.single { it.section == MetadataSection.LOCATION }.entries
        assertFalse(location.any { it.label == "Coordinates" || it.label == "GPS Latitude" })
    }
}
