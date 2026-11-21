package com.luma.gallery.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction

/**
 * Shows the search results and waits until the search workspace has closed.
 *
 * The footer button can still be moving with a closing keyboard, so a touch may start on it and end
 * beside it; the click is invoked directly. And the library behind the workspace already shows the
 * live result, so waiting for the library alone could carry on while the workspace is still open.
 */
internal fun ComposeTestRule.showSearchResults() {
    onNodeWithTag("search-show-results").performSemanticsAction(SemanticsActions.OnClick)
    waitUntil(5_000) { onAllNodesWithTag("search-show-results").fetchSemanticsNodes().isEmpty() }
}

/**
 * Hides the keyboard of the dialog in front (search workspace, filter editor, move dialog). Espresso's plain
 * closeSoftKeyboard() picks whichever window has focus at that instant; right after a dialog opens that can be
 * the activity window, and Espresso then waited 10 s for it to regain focus and failed.
 */
internal fun hideKeyboardInDialog() {
    Espresso.onView(ViewMatchers.isRoot()).inRoot(RootMatchers.isDialog()).perform(ViewActions.closeSoftKeyboard())
}
