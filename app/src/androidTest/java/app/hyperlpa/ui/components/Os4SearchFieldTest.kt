package app.hyperlpa.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class Os4SearchFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchPreservesLiveQueryAndClearUpdatesTheSameState() {
        var query by mutableStateOf("")
        compose.setContent {
            HyperLpaTheme(AppSettings()) {
                Os4SearchField(query, { query = it }, "Search profiles")
            }
        }
        compose.onNodeWithContentDescription("Clear").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search profiles").performTextInput("Travel")
        compose.onNodeWithContentDescription("Search profiles").assertTextContains("Travel")
        compose.runOnIdle { assertEquals("Travel", query) }
        compose.onNodeWithContentDescription("Clear").performClick()
        compose.runOnIdle { assertEquals("", query) }
        compose.onNodeWithContentDescription("Clear").assertDoesNotExist()
    }
}
