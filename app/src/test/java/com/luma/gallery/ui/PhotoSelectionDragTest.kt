package com.luma.gallery.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoSelectionDragTest {
    private val uris = listOf("a", "b", "c", "d", "e")

    @Test fun selectingARangePreservesExistingSelectionAndReversingRestoresIt() {
        val drag = PhotoSelectionDrag(uris, 1, setOf("e"))
        assertEquals(setOf("b", "c", "d", "e"), drag.selectionAt(3))
        assertEquals(setOf("b", "e"), drag.selectionAt(1))
        assertEquals(setOf("a", "b", "e"), drag.selectionAt(0))
    }

    @Test fun startingOnASelectedPhotoRemovesRangeAndRestoresItOnReverse() {
        val drag = PhotoSelectionDrag(uris, 2, uris.toSet())
        assertEquals(setOf("a", "b"), drag.selectionAt(4))
        assertEquals(setOf("a", "b", "d", "e"), drag.selectionAt(2))
        assertEquals(setOf("d", "e"), drag.selectionAt(0))
    }
}
