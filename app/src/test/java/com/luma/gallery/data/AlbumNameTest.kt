package com.luma.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumNameTest {
    @Test fun trimsSurroundingWhitespace() {
        assertEquals("Summer trip", normalizedAlbumName("  Summer trip \n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankNames() {
        normalizedAlbumName(" \t\n ")
    }

    @Test fun acceptsTheMaximumLength() {
        assertEquals("A".repeat(120), normalizedAlbumName("A".repeat(120)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExcessiveLength() {
        normalizedAlbumName("A".repeat(121))
    }
}
