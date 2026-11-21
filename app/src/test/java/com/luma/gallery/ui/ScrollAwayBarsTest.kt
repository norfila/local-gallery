package com.luma.gallery.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollAwayBarsTest {
    private val drag = NestedScrollSource.UserInput

    /** The content moved [px] towards the end (finger up). */
    private fun ScrollAwayBars.scrolledOn(px: Float) {
        connection.onPreScroll(Offset(0f, -px), drag)
        connection.onPostScroll(Offset(0f, -px), Offset.Zero, drag)
    }

    /** The finger pulled down [px]; [moved] says whether the content still had room to scroll back. */
    private fun ScrollAwayBars.pulledBack(px: Float, moved: Boolean = true) {
        connection.onPreScroll(Offset(0f, px), drag)
        connection.onPostScroll(if (moved) Offset(0f, px) else Offset.Zero, if (moved) Offset.Zero else Offset(0f, px), drag)
    }

    @Test fun barsHideOnlyAfterTheContentHasMovedFarEnough() {
        val bars = ScrollAwayBars(hideAfterPx = 100f, showAfterPx = 20f)
        bars.scrolledOn(60f)
        assertFalse(bars.hidden)
        bars.scrolledOn(50f)
        assertTrue(bars.hidden)
    }

    @Test fun aShortScrollBackBringsThemBack() {
        val bars = ScrollAwayBars(hideAfterPx = 100f, showAfterPx = 20f)
        bars.scrolledOn(150f)
        bars.pulledBack(10f)
        assertTrue(bars.hidden)
        bars.pulledBack(15f)
        assertFalse(bars.hidden)
    }

    @Test fun pullingDownBringsThemBackWhereNothingIsLeftToScroll() {
        val bars = ScrollAwayBars(hideAfterPx = 100f, showAfterPx = 20f)
        bars.scrolledOn(150f)
        bars.pulledBack(30f, moved = false)
        assertFalse(bars.hidden)
    }

    @Test fun aSwipeThatCouldNotMoveTheContentDoesNotHideThem() {
        val bars = ScrollAwayBars(hideAfterPx = 100f, showAfterPx = 20f)
        repeat(5) {
            bars.connection.onPreScroll(Offset(0f, -80f), drag)
            bars.connection.onPostScroll(Offset.Zero, Offset(0f, -80f), drag)
        }
        assertFalse(bars.hidden)
    }

    @Test fun aChangeOfDirectionStartsTheCountAgain() {
        val bars = ScrollAwayBars(hideAfterPx = 100f, showAfterPx = 20f)
        bars.scrolledOn(90f)
        bars.pulledBack(5f)
        bars.scrolledOn(90f)
        assertFalse(bars.hidden)
        bars.scrolledOn(20f)
        assertTrue(bars.hidden)
    }

    @Test fun disabledBarsIgnoreScrolling() {
        val bars = ScrollAwayBars(hideAfterPx = 100f, showAfterPx = 20f)
        bars.enabled = false
        bars.scrolledOn(500f)
        assertFalse(bars.hidden)
        bars.enabled = true
        bars.scrolledOn(150f)
        assertTrue(bars.hidden)
        bars.show()
        assertFalse(bars.hidden)
    }

    @Test fun sidewaysScrollingDoesNothing() {
        val bars = ScrollAwayBars(hideAfterPx = 100f, showAfterPx = 20f)
        repeat(5) {
            bars.connection.onPreScroll(Offset(-80f, 0f), drag)
            bars.connection.onPostScroll(Offset(-80f, 0f), Offset.Zero, drag)
        }
        assertFalse(bars.hidden)
    }
}
