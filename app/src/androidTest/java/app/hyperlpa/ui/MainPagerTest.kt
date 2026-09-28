package app.hyperlpa.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Text

class MainPagerTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var coordinator: MainPagerState
    private lateinit var rootBack: RootTabBackState

    @Test
    fun rapidRetargetingKeepsTheLatestSelection() {
        showPager()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { coordinator.animateToPage(3) }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnUiThread {
            assertEquals(3, coordinator.selectedPage)
            coordinator.animateToPage(1)
            coordinator.animateToPage(2)
            assertEquals(2, coordinator.selectedPage)
            assertEquals(2, rootBack.targetPage.value)
        }
        assertSettlesAt(2)
    }

    @Test
    fun backDuringAMultiPageJumpReturnsToProfiles() {
        showPager()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { coordinator.animateToPage(3) }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnUiThread {
            assertEquals(3, rootBack.targetPage.value)
            rootBack.onBack()
            assertEquals(0, rootBack.targetPage.value)
        }
        assertSettlesAt(0)
    }

    @Test
    fun horizontalSwipeTakesOverProgrammaticNavigation() {
        showPager()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { coordinator.animateToPage(3) }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("pager").performTouchInput { swipeRight(durationMillis = 400) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(coordinator.navigating)
            assertTrue(coordinator.pagerState.settledPage < 3)
            assertEquals(coordinator.pagerState.settledPage, coordinator.selectedPage)
            assertEquals(coordinator.selectedPage, rootBack.targetPage.value)
        }
    }

    @Test
    fun horizontalSwipeCanFollowAChildVerticalFling() {
        showPager()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("pager").performTouchInput { swipeUp(durationMillis = 100) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft(durationMillis = 400) }
        assertSettlesAt(1)
    }

    @Test
    fun rtlSwipeUsesLogicalPageOrder() {
        showPager(LayoutDirection.Rtl)
        compose.onNodeWithTag("pager").performTouchInput { swipeRight(durationMillis = 400) }
        assertSettlesAt(1)
    }

    private fun showPager(layoutDirection: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            HyperLpaTheme(AppSettings()) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    val pager = rememberPagerState { 4 }
                    val scope = rememberCoroutineScope()
                    val back = remember { RootTabBackState() }
                    val state = remember { MainPagerState(pager, scope, back) }
                    coordinator = state
                    rootBack = back
                    back.onBack = { state.animateToPage(0) }
                    LaunchedEffect(pager.currentPage) { state.syncPage() }
                    MainTabPager(pager, Modifier.fillMaxSize().testTag("pager")) { page ->
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            repeat(80) { row ->
                                Text("Page $page row $row", Modifier.padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertSettlesAt(page: Int) {
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(coordinator.navigating)
            assertEquals(page, coordinator.pagerState.settledPage)
            assertEquals(page, coordinator.selectedPage)
            assertEquals(page, rootBack.targetPage.value)
        }
    }
}
