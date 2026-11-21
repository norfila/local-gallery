package com.luma.gallery.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.luma.gallery.MainActivity
import com.luma.gallery.data.AppPreferences
import com.luma.gallery.data.StartPage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** The online map loads only after the user agreed, and can be switched off again on the map or in Settings. */
class MapConsentUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val preferences = AppPreferences(context)
            val start = preferences.startPage()
            val online = preferences.onlineMap()
            try {
                preferences.setStartPage(StartPage.PLACES)
                preferences.forgetOnlineMapChoice()
                base.evaluate()
            } finally {
                preferences.setStartPage(start)
                if (online == null) preferences.forgetOnlineMapChoice() else preferences.setOnlineMap(online)
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixture).around(compose)

    @Test fun firstVisitAsksBeforeLoadingAndNotNowKeepsTheMapOff() {
        compose.onNodeWithTag("map-consent").assertExists()
        compose.onNodeWithText("Load the online map?").assertExists()
        // No map view exists, so nothing is downloaded.
        compose.onNodeWithTag("photo-map").assertDoesNotExist()
        compose.onNodeWithTag("map-consent-decline").performClick()
        compose.onNodeWithText("The online map is off").assertExists()
        compose.onNodeWithTag("map-consent-decline").assertDoesNotExist()
        compose.onNodeWithTag("photo-map").assertDoesNotExist()
        assertEquals(false, AppPreferences(context).onlineMap())
    }

    @Test fun loadMapShowsItAndTheShieldButtonTurnsItOffAgain() {
        compose.onNodeWithTag("map-consent-allow").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("photo-map").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("map-consent").assertDoesNotExist()
        assertEquals(true, AppPreferences(context).onlineMap())
        compose.onNodeWithTag("map-privacy-info").performClick()
        compose.onNodeWithText("Turn off online map").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("map-consent").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The online map is off").assertExists()
        compose.onNodeWithTag("photo-map").assertDoesNotExist()
        assertEquals(false, AppPreferences(context).onlineMap())
    }

    @Test fun settingsSwitchTurnsTheMapOnAndOff() {
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag("navigation-drawer"))).performClick()
        compose.onNodeWithTag("online-map").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("online-map").assertIsOn()
        assertEquals(true, AppPreferences(context).onlineMap())
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("photo-map").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag("navigation-drawer"))).performClick()
        compose.onNodeWithTag("online-map").performScrollTo().performClick()
        compose.onNodeWithTag("online-map").assertIsOff()
        assertEquals(false, AppPreferences(context).onlineMap())
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("map-consent").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun savedMapImagesCanBeCleared() {
        val result = AtomicReference<Boolean?>(null)
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            clearMapTileCache(context) { result.set(it); done.countDown() }
        }
        assertTrue("Clearing the map cache did not finish", done.await(20, TimeUnit.SECONDS))
        assertEquals(true, result.get())
    }
}
