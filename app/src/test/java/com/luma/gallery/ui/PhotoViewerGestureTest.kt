package com.luma.gallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoViewerGestureTest {
    @Test fun aShortDragOrAGentleFlickOpensAndClosesDetails() {
        assertEquals(1f, detailsSettleTarget(.3f, 0f, opening = true))
        assertEquals(0f, detailsSettleTarget(.15f, 0f, opening = true))
        assertEquals(1f, detailsSettleTarget(.05f, .8f, opening = true))
        assertEquals(0f, detailsSettleTarget(.7f, 0f, opening = false))
        assertEquals(1f, detailsSettleTarget(.85f, 0f, opening = false))
        assertEquals(0f, detailsSettleTarget(.95f, -.8f, opening = false))
    }

    @Test fun onlyALongPullOrAFastFlingClosesTheViewer() {
        assertTrue(shouldDismissViewer(120f, 0f, 110f))
        assertTrue(shouldDismissViewer(40f, 2_500f, 110f))
        assertFalse(shouldDismissViewer(20f, 2_500f, 110f))
        assertFalse(shouldDismissViewer(80f, 300f, 110f))
    }

    @Test fun playbackTimeUsesHoursOnlyWhenNeeded() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("1:05", formatPlaybackTime(65_000))
        assertEquals("1:01:01", formatPlaybackTime(3_661_000))
    }
}
