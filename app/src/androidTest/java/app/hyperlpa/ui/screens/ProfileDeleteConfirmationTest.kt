package app.hyperlpa.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Scaffold

class ProfileDeleteConfirmationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun finalConfirmationStartsBeforeWarningFinishesClosing() {
        var deleteRequests = 0
        val profile = ProfileInfo(
            iccid = "8900000000000000000",
            state = ProfileState.DISABLED,
            name = "Test profile",
            nickname = "",
            providerName = "Test provider",
            isdPAid = "",
            profileClass = ProfileClass.TESTING,
        )
        val settings = AppSettings(blurEnabled = false)
        compose.setContent {
            HyperLpaTheme(settings) {
                Scaffold {
                    ProfileDetailsScreen(
                        profile = profile,
                        lpa = LpaRepositoryState(profiles = listOf(profile)),
                        settings = settings,
                        suggestedTags = emptySet(),
                        operatorIcon = null,
                        hasProfileIcon = false,
                        hasProviderIcon = false,
                        isProviderIconHidden = false,
                        onBack = {},
                        onEnableChange = {},
                        onSetPinned = {},
                        onRename = {},
                        onDelete = { deleteRequests++ },
                        onSetTags = {},
                        onSetReminder = { _, _ -> },
                        onRequestNotificationPermission = {},
                        onSetIcon = { _, _, _ -> },
                        onSetProviderIconHidden = { _, _ -> },
                        onApplyIconToProvider = {},
                    )
                }
            }
        }

        val warning = compose.onNodeWithText("Delete this profile?")
        val finalConfirmation = compose.onNodeWithText("Delete profile permanently?")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Delete profile"))
        compose.onNodeWithText("Delete profile").performClick()
        warning.assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Continue").performClick()
        compose.mainClock.advanceTimeBy(176)
        warning.assertExists()
        finalConfirmation.assertIsDisplayed()

        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Cancel").performClick()
        finalConfirmation.assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, deleteRequests) }

        compose.onNodeWithText("Delete profile").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        finalConfirmation.assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, deleteRequests) }
    }
}
