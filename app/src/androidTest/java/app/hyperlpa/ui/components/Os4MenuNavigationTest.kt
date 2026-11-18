package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text

class Os4MenuNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyDismissalsOnTheSamePageAnimateTheIconBack() {
        var destination by mutableIntStateOf(0)
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                Os4MenuHost(navigationOwner = destination) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                        // NavDisplay retains the outgoing page while the new page opens.
                        Os4IconDropdownMenu(entry = DropdownEntry(listOf(
                            DropdownItem(text = "Stay here", onClick = {}),
                            DropdownItem(text = "Open next page", onClick = { destination = 1 }),
                        ))) {
                            Text("More", Modifier.testTag("overflow-icon"))
                        }
                        if (destination == 1) Text("Next page", Modifier.align(Alignment.Center))
                    }
                }
            }
        }

        fun openMenu() {
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("overflow-icon").performClick()
            compose.onNodeWithText("Open next page", useUnmergedTree = true).assertExists()
            compose.mainClock.autoAdvance = false
        }

        openMenu()
        compose.onNodeWithText("Stay here", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(128)
        compose.onAllNodesWithTag("overflow-icon", useUnmergedTree = true).assertCountEquals(2)

        openMenu()
        compose.onNodeWithText("Open next page", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(128)
        compose.onNodeWithText("Next page").assertExists()
        compose.onAllNodesWithTag("overflow-icon", useUnmergedTree = true).assertCountEquals(1)

        compose.runOnIdle { destination = 0 }
        openMenu()
    }
}
