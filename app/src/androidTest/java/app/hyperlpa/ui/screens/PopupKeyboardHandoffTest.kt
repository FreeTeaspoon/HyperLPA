package app.hyperlpa.ui.screens

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.EuiccInfo
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.ui.BluetoothReaderUiState
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.components.DialogActionRow
import app.hyperlpa.ui.components.Os4Dialog
import app.hyperlpa.ui.components.TextInputDialog
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold

class PopupKeyboardHandoffTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val settings = AppSettings(blurEnabled = false, showProfileIconOnHome = false, showProfileSearch = false)

    @Before fun configureWindow() = compose.runOnUiThread {
        compose.activity.enableEdgeToEdge()
        compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    @Test fun renameKeepsTheKeyboardAfterTheActionsSheetCloses() {
        val reader = ReaderInfo("fixture-reader", "Fixture reader", ReaderKind.OMAPI)
        val profile = ProfileInfo(
            iccid = "fixture-profile", state = ProfileState.ENABLED, name = "Fixture profile", nickname = "",
            providerName = "Fixture provider", isdPAid = "", profileClass = ProfileClass.TESTING,
        )
        val state = HyperLpaUiState(
            settingsLoaded = true,
            settings = settings,
            profileEnrichmentReady = true,
            lpa = LpaRepositoryState(
                readers = listOf(reader), selectedReaderId = reader.id, profiles = listOf(profile),
                euiccInfo = EuiccInfo(eid = "89049032000000000000000000000000"), initialized = true,
            ),
        )
        compose.setContent {
            HyperLpaTheme(settings) {
                Scaffold { padding ->
                    ProfilesScreen(
                        state = state,
                        contentPadding = padding,
                        scrollBehavior = MiuixScrollBehavior(),
                        bluetoothReaderState = BluetoothReaderUiState(false, false, false, false),
                        onSearchChange = {},
                        onSelectReader = {},
                        onResolveReaderAccess = {},
                        onOpenEuiccDetails = {},
                        onOpenProfile = {},
                        onEnableChange = { _, _ -> },
                        onSetPinned = { _, _ -> },
                        onRename = { _, _ -> },
                        onRefresh = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Fixture profile", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Profile actions").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Rename profile", substring = true).performClick()
        compose.mainClock.advanceTimeBy(176)
        compose.onNodeWithText("Profile actions").assertExists()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        awaitKeyboard(visible = true)

        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Profile actions").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        awaitKeyboard(visible = true)
        compose.onNodeWithText("Cancel").performClick()
        awaitKeyboard(visible = false)
    }

    @Test fun cancelWarningRestoresEditorFocusAndKeepsItsKeyboard() {
        compose.setContent {
            HyperLpaTheme(settings) {
                Scaffold {
                    var showEditor by remember { mutableStateOf(true) }
                    var showWarning by remember { mutableStateOf(false) }
                    TextInputDialog(
                        show = showEditor,
                        title = "Address editor",
                        initialValue = "Fixture address",
                        onDismiss = { showEditor = false },
                        onConfirm = {
                            showEditor = false
                            showWarning = true
                        },
                    )
                    Os4Dialog(show = showWarning, title = "Remove credentials?",
                        onDismissRequest = { showWarning = false }) {
                        DialogActionRow(
                            onCancel = {
                                showWarning = false
                                showEditor = true
                            },
                            confirmText = "Remove",
                            onConfirm = {},
                        )
                    }
                }
            }
        }
        awaitKeyboard(visible = true)
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Remove credentials?").assertIsDisplayed()
        awaitKeyboard(visible = false)

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Remove credentials?").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        awaitKeyboard(visible = true)
    }

    private fun awaitKeyboard(visible: Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnUiThread {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
            }
        }
    }
}
