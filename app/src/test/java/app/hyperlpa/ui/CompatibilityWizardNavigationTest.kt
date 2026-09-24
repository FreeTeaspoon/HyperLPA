package app.hyperlpa.ui

import app.hyperlpa.ui.navigation.AppRoute
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
}
