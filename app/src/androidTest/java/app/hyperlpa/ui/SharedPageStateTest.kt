package app.hyperlpa.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.components.*
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Scaffold

class SharedPageStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialLoadingRejectsPullAndKeepsLoader() {
        var requests = 0
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                DetailLazyScaffold(title = "History", onBack = {}, onRefresh = { requests++ },
                    pageState = PageStateKind.LOADING,
                    pageStateContent = { LoadingState("Loading fixture", Modifier.fillMaxSize()) }) {}
            }
        }
        compose.onRoot().performTouchInput { swipeDown(startY = height * .35f, endY = height * .85f, durationMillis = 700) }
        compose.onNodeWithText("Loading fixture").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun deepPullAndPendingRefreshKeepMessageAndSingleRequest() {
        var requests = 0
        val refreshing = mutableStateOf(false)
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                DetailLazyScaffold(title = "History", onBack = {}, isRefreshing = refreshing.value,
                    onRefresh = { if (!refreshing.value) { requests++; refreshing.value = true } },
                    pageState = PageStateKind.EMPTY,
                    pageStateContent = { EmptyState("No messages", "Choose a source", Modifier.fillMaxSize()) }) {}
            }
        }
        compose.onRoot().performTouchInput {
            down(Offset(width * .5f, height * .35f))
            moveBy(Offset(0f, height * .45f), delayMillis = 700)
        }
        compose.onNodeWithText("No messages").assertIsDisplayed()
        compose.onRoot().performTouchInput { up() }
        compose.onNodeWithText("No messages").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onRoot().performTouchInput { swipeDown(startY = height * .35f, endY = height * .85f, durationMillis = 700) }
        compose.runOnIdle { assertEquals(1, requests); refreshing.value = false }
        compose.onNodeWithText("No messages").assertIsDisplayed()
    }

    @Test fun blockingPopupRetainsTextAndLockUntilNativeExitCompletes() {
        val message = mutableStateOf<String?>("Creating backup")
        var locked = false
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                Scaffold { CompactProgressDialog(message.value) { locked = it } }
            }
        }
        compose.onNodeWithText("Creating backup").assertIsDisplayed()
        compose.runOnIdle { assertTrue(locked) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { message.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Backend completion must not unlock the exiting popup", locked) }
        compose.onNodeWithText("Creating backup").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(locked) }
        compose.onNodeWithText("Creating backup").assertDoesNotExist()
    }
}
