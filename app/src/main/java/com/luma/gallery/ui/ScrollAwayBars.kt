package com.luma.gallery.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * On short screens (a phone turned sideways, split screen) the library's header and floating navigation step aside
 * while the photos scroll towards the end, and come back with any scroll towards the start, so they are never more
 * than a flick away. Only content that really moved hides them; a finger pulling down brings them back even where
 * nothing is left to scroll, so they cannot get stuck away when the remaining photos fit on the screen.
 *
 * Attach [connection] to a parent of the scrolling content with `Modifier.nestedScroll`.
 */
@Stable
internal class ScrollAwayBars(private val hideAfterPx: Float, private val showAfterPx: Float) {
    var hidden by mutableStateOf(false)
        private set

    /** While false (tall screens, selection, the map, TalkBack) scrolling leaves the bars alone. */
    var enabled = true

    private var travel = 0f

    fun show() { hidden = false; travel = 0f }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y > 0f) track(available.y)
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed.y < 0f) track(consumed.y)
            return Offset.Zero
        }
    }

    /** Negative [delta] moves the content towards the end. Only an unbroken movement towards the other state counts. */
    private fun track(delta: Float) {
        if (!enabled) return
        val towardsHiding = delta < 0f
        if (towardsHiding == hidden) { travel = 0f; return }
        travel += abs(delta)
        if (travel >= if (towardsHiding) hideAfterPx else showAfterPx) { hidden = towardsHiding; travel = 0f }
    }
}

@Composable
internal fun rememberScrollAwayBars(): ScrollAwayBars {
    val density = LocalDensity.current
    return remember(density) { with(density) { ScrollAwayBars(hideAfterPx = 40.dp.toPx(), showAfterPx = 8.dp.toPx()) } }
}

/** TalkBack users move through the screen item by item; bars that vanish would take the search and tabs with them. */
@Composable
internal fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
