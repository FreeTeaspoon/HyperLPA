package app.hyperlpa.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.components.PageStateHost
import app.hyperlpa.ui.components.PageStateKind
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Rule
import org.junit.Test

class PageStateHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun errorStateExposesMessageAndRetryAction() {
        compose.setContent {
            HyperLpaTheme(settings = AppSettings()) {
                PageStateHost(
                    state = PageStateKind.ERROR,
                    errorTitle = "Reader unavailable",
                    errorMessage = "Connect a reader and try again.",
                    onRetry = {},
                ) {}
            }
        }

        compose.onNodeWithText("Reader unavailable").assertIsDisplayed()
        compose.onNodeWithText("Connect a reader and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }
}
