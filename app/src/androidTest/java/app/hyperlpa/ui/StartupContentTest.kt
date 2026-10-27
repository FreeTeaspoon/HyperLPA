package app.hyperlpa.ui

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.hyperlpa.HyperLpaApplication
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.SnackbarHostState

class StartupContentTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()

    @After fun clearViewModel() = compose.runOnUiThread { store.clear() }

    @Test fun shellDrawsWhileCombinedDataIsPending() = checkStartupContent(firstRun = false)

    @Test fun firstRunDrawsWhileCombinedDataIsPending() = checkStartupContent(firstRun = true)

    private fun checkStartupContent(firstRun: Boolean) {
        val application = ApplicationProvider.getApplicationContext<HyperLpaApplication>()
        val settings = AppSettings(blurEnabled = false, compatibilityWizardCompleted = !firstRun)
        lateinit var viewModel: HyperLpaViewModel
        compose.runOnUiThread {
            viewModel = ViewModelProvider(
                store,
                HyperLpaViewModel.Factory(
                    application = application,
                    settingsStore = application.settingsStore,
                    metadataStore = application.metadataStore,
                    repository = application.lpaRepository,
                    cloudService = application.cloudService,
                    notificationHistoryStore = application.notificationHistoryStore,
                    supportReportBuilder = application.supportReportBuilder,
                    provisioningCoordinator = application.provisioningCoordinator,
                    initialSettings = settings,
                ),
            )[HyperLpaViewModel::class.java]
            viewModel.resolveStartRoute(settings)
        }
        // Deliberately keep the pre-collection state on screen. DataStore, reader
        // discovery and remote state must never control the whole page's opacity.
        val initialState = viewModel.state.value.copy(lpa = LpaRepositoryState())
        assertFalse(initialState.initialDataLoaded)
        val snackbar = SnackbarHostState()
        compose.setContent {
            HyperLpaTheme(settings) {
                HyperLpaApp(
                    state = initialState,
                    backStack = viewModel.navigationBackStack,
                    viewModel = viewModel,
                    snackbarHostState = snackbar,
                    notificationPermissionGranted = true,
                    onRequestNotificationPermission = {},
                    onOpenNotificationSettings = {},
                    onTestProfileReminder = {},
                    onScanQr = {},
                    bluetoothReaderState = BluetoothReaderUiState(false, false, false, false),
                    onResolveReaderAccess = {},
                    onRequestBluetoothPermission = {},
                    onOpenBluetoothSettings = {},
                )
            }
        }
        val label = compose.onAllNodesWithText(
            if (firstRun) "Checking eUICC access" else "Settings",
        ).onFirst()
        label.assertIsDisplayed()
        // Semantics can still exist inside an alpha=0 layer. Inspect the pixels
        // too, so a page that only draws its background cannot pass this test.
        val pixels = label.captureToImage().toPixelMap()
        val background = pixels[0, 0]
        var foregroundPixels = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y] != background) foregroundPixels++
            }
        }
        assertTrue("Startup content must be visible before combined data arrives", foregroundPixels > 100)
    }
}
