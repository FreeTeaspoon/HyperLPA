package app.hyperlpa.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.ui.BluetoothReaderUiState
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CompatibilityWizardScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun explainsReaderRequirementAndOffersSetupActions() {
        var openedReaderSettings = false
        var continuedWithoutReader = false
        var discoveredReaders = false
        val state = HyperLpaUiState(
            settings = AppSettings(),
            lpa = LpaRepositoryState(initialized = true),
        )

        compose.setContent {
            HyperLpaTheme(settings = state.settings) {
                CompatibilityWizardScreen(
                    state = state,
                    bluetoothReaderState = BluetoothReaderUiState(
                        enabled = false,
                        supported = false,
                        permissionGranted = false,
                        adapterEnabled = false,
                    ),
                    onBack = {},
                    onDiscoverReaders = { discoveredReaders = true },
                    onRequestBluetoothPermission = {},
                    onOpenBluetoothSettings = {},
                    onOpenReaderSettings = { openedReaderSettings = true },
                    onContinue = { continuedWithoutReader = true },
                )
            }
        }

        // Miuix exposes expanded and collapsed title nodes during the scroll transition.
        val titles = compose.onAllNodesWithText("eUICC access setup")
        assertTrue(titles.fetchSemanticsNodes().indices.any { titles[it].isDisplayed() })
        compose.onNodeWithText(
            "HyperLPA manages eSIM profiles through a compatible eUICC reader. " +
                "This setup checks what your device can use and points you to the next step.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Discover readers").performClick()
        compose.onNodeWithText("Open reader settings").performClick()
        compose.onNodeWithText("Continue without a reader").performClick()

        assertTrue(discoveredReaders)
        assertTrue(openedReaderSettings)
        assertTrue(continuedWithoutReader)
    }

    @Test
    fun continueLabelDropsTheSkipWordingWhenAReaderIsAvailable() {
        var continued = false
        val reader = ReaderInfo(
            id = "usb",
            name = "USB reader",
            kind = ReaderKind.USB_CCID,
            available = true,
        )
        val state = HyperLpaUiState(
            settings = AppSettings(),
            lpa = LpaRepositoryState(
                initialized = true,
                readers = listOf(reader),
                selectedReaderId = reader.id,
            ),
        )

        compose.setContent {
            HyperLpaTheme(settings = state.settings) {
                CompatibilityWizardScreen(
                    state = state,
                    bluetoothReaderState = BluetoothReaderUiState(
                        enabled = false,
                        supported = false,
                        permissionGranted = false,
                        adapterEnabled = false,
                    ),
                    onBack = {},
                    onDiscoverReaders = {},
                    onRequestBluetoothPermission = {},
                    onOpenBluetoothSettings = {},
                    onOpenReaderSettings = {},
                    onContinue = { continued = true },
                )
            }
        }

        compose.onNodeWithText("Continue without a reader").assertDoesNotExist()
        compose.onNodeWithText("Continue").performClick()
        assertTrue(continued)
    }
}
