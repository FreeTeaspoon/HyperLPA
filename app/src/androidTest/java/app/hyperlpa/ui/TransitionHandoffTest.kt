package app.hyperlpa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import app.hyperlpa.HyperLpaApplication
import app.hyperlpa.data.history.NotificationHistoryAction
import app.hyperlpa.data.history.NotificationHistoryEntry
import app.hyperlpa.data.history.NotificationHistoryStatus
import app.hyperlpa.data.history.NotificationHistoryTrigger
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileDownloadResult
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.ui.navigation.AppRoute
import app.hyperlpa.ui.navigation.AppTab
import app.hyperlpa.ui.screens.NotificationHistoryScreen
import app.hyperlpa.ui.theme.HyperLpaTheme
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

class TransitionHandoffTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()

    @After fun clearViewModel() = compose.runOnUiThread { store.clear() }

    private enum class Route : NavKey { Start, Destination }

    @Test fun historyDetailsEnterWhileTheActionsSheetExits() {
        openHistoryActions()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("View details").performClick()
        compose.mainClock.advanceTimeBy(176)

        compose.onNodeWithText("History options").assertExists()
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("History options").assertDoesNotExist()
    }

    @Test fun historyDeleteConfirmationOverlapsAndCancelDoesNotDelete() {
        val deleted = mutableListOf<NotificationHistoryEntry>()
        openHistoryActions(deleted::add)

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Delete entry").performClick()
        compose.mainClock.advanceTimeBy(176)

        compose.onNodeWithText("History options").assertExists()
        compose.onNodeWithText("Delete history entry?").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Delete history entry?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<NotificationHistoryEntry>(), deleted) }
    }

    @Test fun navigationHandoffFiresOnlyAfterTheDestinationSettles() {
        val backStack = navBackStackOf(Route.Start)
        var settled = 0
        compose.setContent {
            NavDisplay(backStack = backStack) {
                entry<Route> { route ->
                    if (route == Route.Destination) OnNavEntrySettled(route) { settled++ }
                    Box(Modifier.fillMaxSize())
                }
            }
        }

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { backStack.add(Route.Destination) }
        compose.mainClock.advanceTimeBy(176)
        compose.runOnIdle { assertEquals(0, settled) }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(1, settled) }
    }

    @Test fun downloadDonePopsToProfilesWithoutRevisitingTheDownloadForm() {
        val settings = AppSettings(blurEnabled = false, compatibilityWizardCompleted = true)
        val application = ApplicationProvider.getApplicationContext<HyperLpaApplication>()
        val viewModel = compose.runOnUiThread {
            ViewModelProvider(
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
            )[HyperLpaViewModel::class.java].apply {
                selectTab(AppTab.NOTIFICATIONS)
            }
        }
        val result = AppRoute.ProfileDownloadResult(
            ProfileDownloadResult(
                ProfileInfo("", ProfileState.DISABLED, "Fixture profile", "", "", "", ProfileClass.TESTING),
            ),
        )
        val backStack = viewModel.navigationBackStack
        compose.runOnUiThread {
            backStack.clear()
            backStack.addAll(listOf(AppRoute.Shell, AppRoute.DownloadProfile, AppRoute.ConfirmProfileDownload, result))
        }
        val snackbar = SnackbarHostState()
        compose.setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            HyperLpaTheme(settings) {
                HyperLpaApp(
                    state = state.copy(settings = settings),
                    backStack = backStack,
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
        val historySlot = AppRoute.ProfileDownloadHistorySlot(slot = 0)
        compose.runOnIdle { assertEquals(historySlot, backStack[2]) }
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Done").performClick()
        compose.mainClock.advanceTimeBy(176)
        compose.runOnIdle { assertEquals(historySlot, backStack.last()) }
        compose.onNodeWithText("Done").assertExists()
        compose.mainClock.autoAdvance = true
        compose.runOnIdle {
            assertEquals(listOf(AppRoute.Shell), backStack.toList())
            assertEquals(AppTab.PROFILES.name, viewModel.navigationSnapshot().selectedTab)
        }
        compose.onNodeWithText("Done").assertDoesNotExist()
    }

    private fun openHistoryActions(onDelete: (NotificationHistoryEntry) -> Unit = {}) {
        val entry = NotificationHistoryEntry(
            timestampEpochMillis = 0L,
            action = NotificationHistoryAction.SEND,
            status = NotificationHistoryStatus.SUCCEEDED,
            trigger = NotificationHistoryTrigger.MANUAL,
            notificationOperation = "install",
            profileName = "Fixture profile",
        )
        val settings = AppSettings(blurEnabled = false)
        compose.setContent {
            HyperLpaTheme(settings) {
                Scaffold {
                    NotificationHistoryScreen(
                        state = HyperLpaUiState(settings = settings, notificationHistory = listOf(entry)),
                        onBack = {},
                        onDeleteHistoryEntry = onDelete,
                        onResendNotification = {},
                        onRefreshHistory = { Job().apply { complete() } },
                        onClearLocalHistory = { it(true) },
                    )
                }
            }
        }
        compose.onNodeWithText("Fixture profile", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("History options").assertIsDisplayed()
    }
}
