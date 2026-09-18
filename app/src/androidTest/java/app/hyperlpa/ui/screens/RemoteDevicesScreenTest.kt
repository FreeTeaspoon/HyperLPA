package app.hyperlpa.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import app.hyperlpa.HyperLpaApplication
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.ui.BluetoothReaderUiState
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.File

class RemoteDevicesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun setupHasRelayAndPairingIdentityFields() {
        val app = ApplicationProvider.getApplicationContext<HyperLpaApplication>()
        compose.setContent {
            HyperLpaTheme(settings = AppSettings()) {
                RemoteDevicesScreen(app.remoteDevices, onBack = {})
            }
        }
        compose.waitUntil(10_000) { app.remoteDevices.ui.value.loaded }
        // Miuix exposes expanded and collapsed title nodes during the scroll transition.
        val titles = compose.onAllNodesWithText("Remote devices")
        assertTrue(titles.fetchSemanticsNodes().indices.any { titles[it].isDisplayed() })
        compose.onNodeWithText("Relay address").assertIsDisplayed()
        compose.onNodeWithText("Enrollment key").assertIsDisplayed()
        compose.onNodeWithText("Device name").assertIsDisplayed()
        val file = File(app.filesDir, "remote-setup-test.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun localAndRemoteSimSlotsShareTheActiveReaderDropdown() {
        var selected: String? = null
        val local = ReaderInfo("local-sim1", "SIM1", ReaderKind.OMAPI)
        val remote = ReaderInfo("device:phone:sim2", "Travel phone · SIM2", ReaderKind.REMOTE,
            deviceId = "phone", sourceReaderId = "sim2")
        val state = HyperLpaUiState(settingsLoaded = true, settings = AppSettings(),
            lpa = LpaRepositoryState(readers = listOf(local, remote), selectedReaderId = local.id, initialized = true))
        compose.setContent {
            HyperLpaTheme(settings = state.settings) {
                Scaffold { padding ->
                    ProfilesScreen(state = state, contentPadding = padding, scrollBehavior = MiuixScrollBehavior(),
                        bluetoothReaderState = BluetoothReaderUiState(false, false, false, false), onSearchChange = {}, onSelectReader = { selected = it },
                        onRefreshReaders = {}, onOpenEuiccDetails = {}, onOpenProfile = {}, onEnableChange = { _, _ -> },
                        onSetPinned = { _, _ -> }, onRename = { _, _ -> }, onDownload = {}, onRefresh = {})
                }
            }
        }
        compose.onNodeWithText("Active reader").performClick()
        compose.onNodeWithText("Travel phone · SIM2").assertIsDisplayed().performClick()
        assertEquals(remote.id, selected)
    }
}
