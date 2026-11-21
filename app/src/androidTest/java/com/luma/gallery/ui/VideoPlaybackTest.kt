package com.luma.gallery.ui

import android.content.Context
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.luma.gallery.data.ExternalPhotoReader
import com.luma.gallery.data.ExternalPhotoRequest
import com.luma.gallery.data.ThemeMode
import com.luma.gallery.data.createVideoFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class VideoPlaybackTest {
    @get:Rule val compose = createComposeRule()

    /** Controls hide themselves during playback; a tap on the video brings them back. */
    private fun showControls() {
        if (compose.onAllNodesWithTag("viewer-more").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("video-viewer-player").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer-more").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun realVideoCanPlayPauseAndShowContainerDetails(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = createVideoFixture(context)
        try {
            val photo = ExternalPhotoReader(context).read(ExternalPhotoRequest(uri, "video/mp4"))!!
            compose.setContent {
                GalleryTheme(ThemeMode.LIGHT) {
                    PhotoViewer(listOf(photo), photo.uri, { }, { })
                }
            }
            // Media3 starts the video automatically.
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("video-viewer-player") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing video"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            showControls()
            compose.onAllNodesWithContentDescription("Pause video").onFirst().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("video-viewer-player") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused video"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithContentDescription("Play video").onFirst().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("video-viewer-player") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing video"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            showControls()
            compose.onAllNodesWithContentDescription("Pause video").onFirst().performClick()
            showControls()
            compose.onNodeWithTag("viewer-more").performClick()
            compose.onNodeWithTag("viewer-menu-details").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("photo-details-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("photo-details-list").performScrollToNode(hasContentDescription("Expand Image"))
            compose.onNodeWithContentDescription("Expand Image").performClick()
            compose.onNodeWithTag("photo-details-list").performScrollToNode(hasText("Duration"))
            compose.onNodeWithText("Duration").assertExists()
        } finally { context.contentResolver.delete(uri, null, null) }
    }
}
