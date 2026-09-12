package app.hyperlpa.ui

import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.ui.navigation.AppRoute
import app.hyperlpa.ui.screens.hasDetectedCompatibilityReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityWizardNavigationTest {
    @Test
    fun wizardOpensOnlyForAnIncompleteFirstRunOnTheShell() {
        assertTrue(
            shouldOpenCompatibilityWizard(
                settingsLoaded = true,
                wizardCompleted = false,
                activationCodeDraft = "",
                currentRoute = AppRoute.Shell,
            ),
        )
        assertFalse(
            shouldOpenCompatibilityWizard(
                settingsLoaded = false,
                wizardCompleted = false,
                activationCodeDraft = "",
                currentRoute = AppRoute.Shell,
            ),
        )
        assertFalse(
            shouldOpenCompatibilityWizard(
                settingsLoaded = true,
                wizardCompleted = true,
                activationCodeDraft = "",
                currentRoute = AppRoute.Shell,
            ),
        )
        assertFalse(
            shouldOpenCompatibilityWizard(
                settingsLoaded = true,
                wizardCompleted = false,
                activationCodeDraft = "LPA:1${'$'}example${'$'}matching-id",
                currentRoute = AppRoute.Shell,
            ),
        )
        assertFalse(
            shouldOpenCompatibilityWizard(
                settingsLoaded = true,
                wizardCompleted = false,
                activationCodeDraft = "",
                currentRoute = AppRoute.ReaderSettings,
            ),
        )
    }

    @Test
    fun detectedReaderUsesThePlainContinueLabel() {
        val reader = ReaderInfo("usb", "USB reader", ReaderKind.USB_CCID)
        assertFalse(hasDetectedCompatibilityReader(selectedReader = null, availableReaders = emptyList()))
        assertTrue(hasDetectedCompatibilityReader(selectedReader = reader, availableReaders = emptyList()))
        assertTrue(hasDetectedCompatibilityReader(selectedReader = null, availableReaders = listOf(reader)))
    }
}
