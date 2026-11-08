package app.hyperlpa.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.ProfileLayout
import app.hyperlpa.ui.screens.OnboardingScreen
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun showText(text: String) {
        val scrollTrace = mutableListOf<String>()
        for (up in listOf(true, false)) {
            repeat(8) {
                if (compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onNodeWithText(text).isDisplayed()) return
                if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithText(text).assertIsDisplayed()
                    return
                }
                val scroller = compose.onNode(hasScrollAction())
                val scrollNode = scroller.fetchSemanticsNode()
                val axis = scrollNode.config[SemanticsProperties.VerticalScrollAxisRange]
                scrollTrace += "up=$up, bounds=${scrollNode.boundsInRoot}, offset=${axis.value()}, max=${axis.maxValue()}"
                scroller.performTouchInput {
                    if (up) swipeUp(durationMillis = 500) else swipeDown(durationMillis = 500)
                }
            }
        }
        throw AssertionError("Could not reveal '$text': $scrollTrace\n${compose.onRoot().printToString()}")
    }

    private fun assertProgressGap(lastActionTag: String, pixelsPerDp: Float) {
        val action = compose.onNodeWithTag(lastActionTag).fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithTag("onboarding-progress").fetchSemanticsNode().boundsInRoot
        assertEquals("Dots follow the last visible action without an empty button slot", 14f * pixelsPerDp, progress.top - action.bottom, 1f)
    }

    @Test fun contentFadesWithoutHorizontalMovementAndFooterHasNoEmptyActionSlotInLtrAndRtl() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        var readerChecks = 0
        var pixelsPerDp = 1f
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                HyperLpaTheme(AppSettings(blurEnabled = false)) {
                    OnboardingScreen(
                        state = HyperLpaUiState(settings = AppSettings(blurEnabled = false)),
                        bluetoothReaderState = BluetoothReaderUiState(false, true, false, false),
                        onPreferenceChange = { _, _ -> }, onLayoutChange = {},
                        onCheckReader = { readerChecks++ }, onOpenPairedDevices = {}, onFinish = {},
                    )
                }
            }
        }
        for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction.value = layout }
            val welcomeLeft = compose.onNodeWithText("Welcome to HyperLPA").fetchSemanticsNode().boundsInRoot.left
            val buttonBounds = compose.onNodeWithTag("onboarding-next").fetchSemanticsNode().boundsInRoot
            val progressBounds = compose.onNodeWithTag("onboarding-progress").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("onboarding-skip").assertDoesNotExist()
            assertProgressGap("onboarding-next", pixelsPerDp)
            fun assertReaderFooter() {
                assertEquals(progressBounds, compose.onNodeWithTag("onboarding-progress").fetchSemanticsNode().boundsInRoot)
                assertProgressGap("onboarding-skip", pixelsPerDp)
                compose.onAllNodes(hasText("Check this phone")).assertCountEquals(1)
            }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("onboarding-next").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(32)
            assertReaderFooter()
            val departingWelcome = compose.onNodeWithText("Welcome to HyperLPA").fetchSemanticsNode().boundsInRoot.left
            assertEquals(welcomeLeft, departingWelcome, 1f)
            val enteringReader = compose.onNodeWithText("Connect your reader").fetchSemanticsNode().boundsInRoot.left
            // Another tap while the content is moving must not start reader discovery.
            compose.onNodeWithTag("onboarding-next").performClick()
            compose.mainClock.advanceTimeBy(128)
            assertReaderFooter()
            val readerLeft = compose.onNodeWithText("Connect your reader").fetchSemanticsNode().boundsInRoot.left
            assertEquals(enteringReader, readerLeft, 1f)
            compose.onNodeWithText("Welcome to HyperLPA").assertDoesNotExist()
            assertEquals(0, readerChecks)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(32)
            assertEquals(buttonBounds, compose.onNodeWithTag("onboarding-next").fetchSemanticsNode().boundsInRoot)
            assertEquals(progressBounds, compose.onNodeWithTag("onboarding-progress").fetchSemanticsNode().boundsInRoot)
            assertProgressGap("onboarding-next", pixelsPerDp)
            val returningWelcome = compose.onNodeWithText("Welcome to HyperLPA").fetchSemanticsNode().boundsInRoot.left
            assertEquals(welcomeLeft, returningWelcome, 1f)
            compose.mainClock.advanceTimeBy(128)
            compose.onNodeWithText("Welcome to HyperLPA").assertIsDisplayed()
            compose.onNodeWithText("Connect your reader").assertDoesNotExist()
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun moreOptionsKeepsProfilePreviewAndControlsInPlaceDuringExpansionAndCollapse() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)) {
                Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                    HyperLpaTheme(AppSettings(blurEnabled = false, showProfileSearch = true)) {
                        OnboardingScreen(
                            state = HyperLpaUiState(settings = AppSettings(blurEnabled = false, showProfileSearch = true)),
                            bluetoothReaderState = BluetoothReaderUiState(false, true, false, false),
                            onPreferenceChange = { _, _ -> }, onLayoutChange = {},
                            onCheckReader = {}, onOpenPairedDevices = {}, onFinish = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithTag("onboarding-skip").performClick()
        compose.onNodeWithTag("onboarding-next").performClick()
        showText("More options")
        val previewHeight = compose.onNodeWithTag("onboarding-profile-preview").fetchSemanticsNode().size.height
        val controlsTop = compose.onNodeWithText("Profile layout").fetchSemanticsNode().boundsInRoot.top
        fun assertStable() {
            assertEquals(previewHeight, compose.onNodeWithTag("onboarding-profile-preview").fetchSemanticsNode().size.height)
            assertEquals(controlsTop, compose.onNodeWithText("Profile layout").fetchSemanticsNode().boundsInRoot.top, 1f)
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("More options").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertStable()
        compose.mainClock.advanceTimeBy(100)
        assertStable()
        compose.mainClock.advanceTimeBy(1000)
        assertStable()
        compose.onNodeWithText("More options").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertStable()
        compose.mainClock.advanceTimeBy(1000)
        assertStable()
        compose.mainClock.autoAdvance = true
    }

    @Test fun fittingPagesStayStillWhileExpandedContentCanScroll() {
        var pixelsPerDp = 1f
        val viewportHeight = mutableStateOf(700.dp)
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(pixelsPerDp, fontScale = 1f)) {
                Box(Modifier.size(width = 400.dp, height = viewportHeight.value)) {
                    HyperLpaTheme(AppSettings(blurEnabled = false)) {
                        OnboardingScreen(
                            state = HyperLpaUiState(settings = AppSettings(blurEnabled = false)),
                            bluetoothReaderState = BluetoothReaderUiState(false, true, false, false),
                            onPreferenceChange = { _, _ -> }, onLayoutChange = {},
                            onCheckReader = {}, onOpenPairedDevices = {}, onFinish = {},
                        )
                    }
                }
            }
        }
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        val welcomeTop = compose.onNodeWithText("Welcome to HyperLPA").fetchSemanticsNode().boundsInRoot.top
        compose.onRoot().performTouchInput {
            down(Offset(width * .5f, height * .35f))
            moveBy(Offset(0f, 80f), delayMillis = 500)
        }
        assertEquals(welcomeTop, compose.onNodeWithText("Welcome to HyperLPA").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onRoot().performTouchInput { up() }
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        val primary = compose.onNodeWithTag("onboarding-next").fetchSemanticsNode().boundsInRoot
        val skip = compose.onNodeWithTag("onboarding-skip").fetchSemanticsNode().boundsInRoot
        assertEquals(primary.width, skip.width, 1f)
        assertTrue("Footer feedback needs space between buttons", skip.top - primary.bottom >= 11f * pixelsPerDp)
        assertProgressGap("onboarding-skip", pixelsPerDp)
        compose.onNodeWithTag("onboarding-skip").performClick()
        assertProgressGap("onboarding-next", pixelsPerDp)
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        compose.onNodeWithText("What connects to the internet?").performClick()
        compose.runOnIdle { viewportHeight.value = 500.dp }
        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
        showText("Measured sizes and custom icons remain available with these options off.")
        showText("What connects to the internet?")
        compose.onNodeWithText("What connects to the internet?").performClick()
        compose.runOnIdle { viewportHeight.value = 700.dp }
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        compose.onNodeWithTag("onboarding-next").performClick()
        assertProgressGap("onboarding-next", pixelsPerDp)
        compose.runOnIdle { viewportHeight.value = 500.dp }
        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
        showText("More options")
        compose.onNodeWithText("More options").performClick()
        showText("Profile name")
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.runOnIdle { viewportHeight.value = 700.dp }
        compose.onNodeWithText("You are ready").assertIsDisplayed()
        assertProgressGap("onboarding-next", pixelsPerDp)
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    @Test fun skipReaderChooseOnlineExtrasAndAppearanceThenFinish() {
        val settings = mutableStateOf(AppSettings(blurEnabled = false))
        var readerChecks = 0
        var finished = false
        compose.setContent {
            HyperLpaTheme(settings.value) {
                OnboardingScreen(
                    state = HyperLpaUiState(settings = settings.value),
                    bluetoothReaderState = BluetoothReaderUiState(false, true, false, false),
                    onPreferenceChange = { preference, value ->
                        settings.value = when (preference) {
                            OnboardingPreference.OPERATOR_ICONS -> settings.value.copy(loadOperatorIcons = value)
                            OnboardingPreference.SIZE_ESTIMATES -> settings.value.copy(estimateProfileSize = value)
                            OnboardingPreference.PROFILE_ICONS -> settings.value.copy(showProfileIconOnHome = value)
                            else -> settings.value
                        }
                    },
                    onLayoutChange = { settings.value = settings.value.copy(profileLayout = it) },
                    onCheckReader = { readerChecks++ },
                    onOpenPairedDevices = {},
                    onFinish = { finished = true },
                )
            }
        }
        compose.onNodeWithText("Welcome to HyperLPA").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithTag("onboarding-skip").performClick()
        compose.onNodeWithText("Download operator icons").performClick()
        compose.onNodeWithText("Estimate profile sizes").performClick()
        compose.runOnIdle {
            assertTrue(settings.value.loadOperatorIcons)
            assertTrue(settings.value.estimateProfileSize)
            assertEquals(0, readerChecks)
        }
        compose.onNodeWithTag("onboarding-next").performClick()
        showText("Profile icon")
        compose.onNodeWithText("Profile icon").performClick()
        showText("Profile layout")
        compose.onNodeWithText("Profile layout").performClick()
        compose.onNodeWithText("Waterfall").performClick()
        compose.runOnIdle {
            assertFalse(settings.value.showProfileIconOnHome)
            assertEquals(ProfileLayout.WATERFALL, settings.value.profileLayout)
        }
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithText("You are ready").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.runOnIdle { assertTrue(finished) }
    }

    @Test fun stepAndReaderChoiceSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var checkedReader: OnboardingReader? = null
        restoration.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                OnboardingScreen(
                    state = HyperLpaUiState(settings = AppSettings(blurEnabled = false)),
                    bluetoothReaderState = BluetoothReaderUiState(false, true, false, false),
                    onPreferenceChange = { _, _ -> }, onLayoutChange = {},
                    onCheckReader = { checkedReader = it }, onOpenPairedDevices = {}, onFinish = {},
                )
            }
        }
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithText("USB reader").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Connect your reader").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.runOnIdle { assertEquals(OnboardingReader.USB, checkedReader) }
    }
}
