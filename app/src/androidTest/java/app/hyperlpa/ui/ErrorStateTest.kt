package app.hyperlpa.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.components.ErrorState
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Rule
import org.junit.Test

class ErrorStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun errorStateExposesTitleAndMessageWithoutAnAction() {
        compose.setContent {
            HyperLpaTheme(settings = AppSettings()) {
                ErrorState(
                    title = "Reader unavailable",
                    message = "Connect a reader and try again.",
                )
            }
        }

        compose.onNodeWithText("Reader unavailable").assertIsDisplayed()
        compose.onNodeWithText("Connect a reader and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }
}
