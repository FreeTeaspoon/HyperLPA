package app.hyperlpa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

class Os4MenuNavigationTest {
    @get:Rule val compose = createComposeRule()
    private data object Inbox : NavKey
    private data object Alerts : NavKey

    @Test fun menuAnimatesOnFirstOpenAndClosesWithItsPage() {
        val stack = navBackStackOf(Inbox)
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                NavDisplay(backStack = stack) {
                    entry<Inbox> {
                        Os4MenuHost(active = stack.lastOrNull() == Inbox) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                                Os4IconDropdownMenu(entry = DropdownEntry(listOf(
                                    DropdownItem(text = "Stay here", onClick = {}),
                                    DropdownItem(text = "Open next page", onClick = { stack.add(Alerts) }),
                                ))) {
                                    Text("More", Modifier.testTag("overflow-icon"))
                                }
                            }
                        }
                    }
                    entry<Alerts> {
                        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                            Text("Next page")
                        }
                    }
                }
            }
        }

        val icons = compose.onAllNodesWithTag("overflow-icon", useUnmergedTree = true)
        fun openMenu() {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("overflow-icon", useUnmergedTree = true).performTouchInput { click() }
            // A popup created already open has no moving copy of the anchor at this point.
            compose.mainClock.advanceTimeBy(48)
            icons.assertCountEquals(2)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText("Open next page", useUnmergedTree = true).assertExists()
            icons.assertCountEquals(1)
            compose.mainClock.autoAdvance = false
        }

        openMenu()
        compose.onNodeWithText("Stay here", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(112)
        icons.assertCountEquals(2)
        compose.mainClock.autoAdvance = true
        icons.assertCountEquals(1)

        openMenu()
        compose.onNodeWithText("Open next page", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(112)
        compose.onNodeWithText("Next page").assertExists()
        icons.assertCountEquals(2)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Open next page", useUnmergedTree = true).assertDoesNotExist()

        compose.runOnIdle { stack.removeLastOrNull() }
        icons.assertCountEquals(1)
        openMenu()
        compose.mainClock.autoAdvance = true
    }
}
