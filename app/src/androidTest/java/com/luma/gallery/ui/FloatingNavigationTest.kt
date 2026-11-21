package com.luma.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.luma.gallery.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FloatingNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionBubbleStretchesThenSettlesAtTheSelectedDestination() {
        val selection = mutableIntStateOf(0)
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) { FloatingGalleryNavigation(selection.intValue, { selection.intValue = it }) } }
        val initial = compose.onNodeWithTag("navigation-selection").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Map", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(96)
        val mid = compose.onNodeWithTag("navigation-selection").fetchSemanticsNode().boundsInRoot
        assertTrue("Shared selection bubble must stretch during travel", mid.width > initial.width + 8f)
        compose.mainClock.advanceTimeBy(1500)
        val settled = compose.onNodeWithTag("navigation-selection").fetchSemanticsNode().boundsInRoot
        assertTrue(settled.left > initial.right)
        assertTrue(kotlin.math.abs(settled.width - initial.width) < 4f)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Map", useUnmergedTree = true).assertExists()
        compose.onAllNodes(isSelected()).assertCountEquals(1)
    }

    @Test fun aChevronAfterTheLibraryLabelShowsThatTheTabOpensItsViews() {
        val tab = mutableIntStateOf(0)
        val expanded = mutableStateOf(false)
        val available = mutableStateOf(true)
        compose.setContent { GalleryTheme(ThemeMode.LIGHT) {
            FloatingGalleryNavigation(tab.intValue, { if (it == 0 && tab.intValue == 0) expanded.value = !expanded.value else tab.intValue = it },
                libraryMenuExpanded = expanded.value, libraryMenuAvailable = available.value)
        } }
        fun chevron() = compose.onNodeWithTag("library-menu-chevron", useUnmergedTree = true)
        val label = compose.onNodeWithText("Library", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val mark = chevron().fetchSemanticsNode().boundsInRoot
        assertTrue("The chevron follows the word, on its line", mark.left >= label.right - 1f && mark.center.y in label.top..label.bottom)
        assertTrue("It stays as small as the label", mark.height <= label.height)
        // The word stays centred in its tab although the chevron hangs after it.
        val cell = compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
        assertEquals(cell.left + cell.width / 6, label.center.x, cell.width * .02f)
        // It is there while the menu is open too, and only where another tap opens the menu.
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("library-shortcut-strip").assertExists()
        chevron().assertExists()
        compose.runOnIdle { expanded.value = false; available.value = false }
        chevron().assertDoesNotExist()
        compose.runOnIdle { available.value = true; tab.intValue = 1 }
        chevron().assertDoesNotExist()
    }

    @Test fun floatingNavigationHasAnOpaqueBackgroundInLightAppearance() = assertOpaqueNavigation(ThemeMode.LIGHT)

    @Test fun floatingNavigationHasAnOpaqueBackgroundInDarkAppearance() = assertOpaqueNavigation(ThemeMode.DARK)

    private fun assertOpaqueNavigation(mode: ThemeMode) {
        val backdrop = Color(0xFF926B8F)
        var toolbarColor = Color.Unspecified
        compose.setContent {
            GalleryTheme(mode) {
                toolbarColor = MaterialTheme.colorScheme.surfaceVariant
                Box(Modifier.background(backdrop)) {
                    FloatingGalleryNavigation(0, {})
                }
            }
        }
        val navigation = compose.onNodeWithTag("floating-navigation").captureToImage().toPixelMap()
        // Content behind an unselected tab must not show through the floating capsule.
        assertEquals(toolbarColor.toArgb(), navigation[navigation.width / 2, navigation.height * 15 / 100].toArgb())
        assertEquals("The rounded corner stays outside the capsule", backdrop.toArgb(), navigation[0, 0].toArgb())
        compose.onNodeWithText("Library", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Albums", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Map", useUnmergedTree = true).assertExists()
    }
}
