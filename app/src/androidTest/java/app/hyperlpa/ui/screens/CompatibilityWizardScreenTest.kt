package app.hyperlpa.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.EuiccInfo
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CompatibilityWizardScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun firstRunExplainsMissingReaderWithOnlyContinue() {
        var continued = false
        val state = HyperLpaUiState(
            settings = AppSettings(),
            lpa = LpaRepositoryState(initialized = true),
        )

        compose.setContent {
            HyperLpaTheme(settings = state.settings) {
                CompatibilityWizardScreen(
                    state = state,
                    firstRun = true,
                    onBack = {},
                    onRefreshReaders = {},
                    onContinue = { continued = true },
                )
            }
        }

        compose.onNodeWithText("No eUICC reader found").assertIsDisplayed()
        compose.onNodeWithText("Available readers: None").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithText("Continue").performClick()
        assertTrue(continued)
    }

    @Test
    fun listsReadersThatExposeAnEuicc() {
        val sim = ReaderInfo(id = "sim1", name = "SIM1", kind = ReaderKind.OMAPI)
        val usb = ReaderInfo(id = "usb", name = "USB reader", kind = ReaderKind.USB_CCID)
        val state = HyperLpaUiState(
            settings = AppSettings(),
            lpa = LpaRepositoryState(
                initialized = true,
                readers = listOf(sim, usb),
                selectedReaderId = sim.id,
                euiccInfo = EuiccInfo(eid = "89049032000000000000000000000000"),
            ),
        )

        compose.setContent {
            HyperLpaTheme(settings = state.settings) {
                CompatibilityWizardScreen(
                    state = state,
                    firstRun = false,
                    onBack = {},
                    onRefreshReaders = {},
                    onContinue = {},
                )
            }
        }

        compose.onNodeWithText("Your phone can manage eSIM profiles with HyperLPA").assertIsDisplayed()
        compose.onNodeWithText("Available readers: SIM1 and USB reader").assertIsDisplayed()
        compose.onNodeWithText("eUICC access: SIM1").assertIsDisplayed()
    }
}
