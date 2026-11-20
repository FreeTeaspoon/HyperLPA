package app.hyperlpa.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Rule
import org.junit.Test

class TagManagerSearchTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun glassSearchFiltersTagsAndProfilesAndClearsBoth() {
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                TagManagerScreen(
                    profiles = listOf(
                        profile("test-travel", "Travel eSIM", "Holiday"),
                        profile("test-work", "Work eSIM", "Business"),
                    ),
                    onBack = {},
                    onSetTags = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Work eSIM").assertIsDisplayed()
        val search = compose.onNodeWithContentDescription("Search tags or profiles")
        search.performTextInput("holiday")
        compose.onNodeWithText("Travel eSIM").assertIsDisplayed()
        compose.onNodeWithText("Work eSIM").assertDoesNotExist()
        compose.onNodeWithText("Business", substring = true).assertDoesNotExist()

        search.performImeAction()
        compose.onNodeWithText("Travel eSIM").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear").performClick()
        compose.onNodeWithText("Work eSIM").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear").assertDoesNotExist()
    }

    private fun profile(id: String, name: String, tag: String) = ProfileInfo(
        iccid = id,
        state = ProfileState.DISABLED,
        name = name,
        nickname = "",
        providerName = "",
        isdPAid = "",
        profileClass = ProfileClass.OPERATIONAL,
        tags = setOf(tag),
    )
}
