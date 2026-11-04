package app.hyperlpa.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.DownloadStage
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Scaffold

class DownloadProgressTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bytesReplaceSpinnerInTheSamePopupAndSurviveExit() {
        val operation = mutableStateOf<LpaOperation.Downloading?>(LpaOperation.Downloading(DownloadStage.PREPARING))
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                Scaffold { ProfileDownloadProgressBar(operation.value) }
            }
        }
        compose.onNodeWithText("Preparing profile…").assertIsDisplayed()
        compose.runOnIdle {
            operation.value = LpaOperation.Downloading(DownloadStage.DOWNLOADING, sentBytes = 512, totalBytes = 1024)
        }
        compose.onNodeWithText("Downloading…", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("50%, 512 B/1.0 KiB", useUnmergedTree = true).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { operation.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("50%, 512 B/1.0 KiB", useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Downloading…", useUnmergedTree = true).assertDoesNotExist()
    }
}
