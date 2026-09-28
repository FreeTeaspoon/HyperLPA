package app.hyperlpa.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onNodeWithText
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.EuiccInfo
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.ui.BluetoothReaderUiState
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.LocalMiuixSnackbar
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold

class ProfilesLoadingTest {
    @get:Rule val compose = createComposeRule()

    private val reader = ReaderInfo("reader", "Test reader", ReaderKind.OMAPI)
    private val profile = ProfileInfo(
        iccid = "profile", state = ProfileState.ENABLED, name = "Test profile", nickname = "",
        providerName = "Provider", isdPAid = "", profileClass = ProfileClass.OPERATIONAL,
    )
    private val info = EuiccInfo(eid = "89049032000000000000000000000000")
    private val loaded = HyperLpaUiState(
        settingsLoaded = true,
        settings = AppSettings(showProfileIconOnHome = false, showProfileSearch = true),
        profileEnrichmentReady = true,
        lpa = LpaRepositoryState(
            readers = listOf(reader), selectedReaderId = reader.id,
            profiles = listOf(profile), euiccInfo = info, initialized = true,
        ),
    )

    @Test
    fun onlyConflictingOperationsStayLockedThroughTheSwitchTail() {
        val state = mutableStateOf(loaded)
        show({ state.value })
        assertControlsEnabled()

        compose.runOnIdle {
            state.value = loaded.copy(
                requestedProfileSwitchIccid = profile.iccid,
                lpa = loaded.lpa.copy(operation = LpaOperation.Switching(profile.iccid, false)),
            )
        }
        assertControlsDisabled()
        compose.runOnIdle {
            state.value = state.value.copy(lpa = loaded.lpa)
        }
        // An idle repository alone must not release the controls before the queued action ends.
        assertControlsDisabled()
        compose.runOnIdle { state.value = loaded }
        assertControlsEnabled()
    }

    @Test
    fun uncachedSwitchShowsProfilesAsSoonAsReaderIsReady() {
        val state = mutableStateOf(loaded)
        show({ state.value })
        assertControlsEnabled()

        compose.runOnIdle {
            state.value = loaded.copy(
                lpa = loaded.lpa.copy(
                    selectedReaderId = null, euiccInfo = null, profiles = emptyList(),
                    operation = LpaOperation.Connecting(reader.name),
                ),
            )
        }
        assertResultHidden()
        compose.runOnIdle {
            state.value = state.value.copy(lpa = state.value.lpa.copy(euiccInfo = info))
        }
        assertResultHidden()
        compose.runOnIdle {
            state.value = loaded.copy(profileEnrichmentReady = false)
        }
        assertControlsEnabled()
        compose.runOnIdle { state.value = loaded }
        assertControlsEnabled()
    }

    @Test
    fun startupKeepsOneLoaderUntilDiscoveryFinishes() {
        val state = mutableStateOf(loaded.copy(lpa = LpaRepositoryState()))
        show({ state.value })
        assertResultHidden()
        compose.runOnIdle {
            state.value = loaded.copy(
                lpa = loaded.lpa.copy(operation = LpaOperation.DiscoveringReaders("Looking for readers")),
            )
        }
        assertResultHidden()
        compose.runOnIdle { state.value = loaded }
        assertControlsEnabled()
    }

    @Test
    fun cachedReaderConnectionKeepsProfileColorsAndBrowsing() {
        val state = mutableStateOf(loaded)
        var opened = 0
        show({ state.value }, onOpenProfile = { opened++ })
        val before = compose.onNodeWithText("Test profile", substring = true)
            .captureToImage().asAndroidBitmap()
        compose.runOnIdle {
            state.value = loaded.copy(lpa = loaded.lpa.copy(
                operation = LpaOperation.Connecting(reader.name), readerSnapshotPendingRefresh = true,
            ))
        }
        assertControlsDisabled()
        compose.onNodeWithText("Test profile", substring = true).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        val after = compose.onNodeWithText("Test profile", substring = true)
            .captureToImage().asAndroidBitmap()
        assertTrue("Connecting must not fade the profile card or its switch", before.sameAs(after))
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "profiles-connecting-test.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun busyReaderMenuStaysUsableAndExplainsTheOperationLock() {
        val other = ReaderInfo("other", "Other reader", ReaderKind.OMAPI)
        val state = mutableStateOf(loaded.copy(lpa = loaded.lpa.copy(
            readers = listOf(reader, other), operation = LpaOperation.Connecting(reader.name),
            readerSnapshotPendingRefresh = true,
        )))
        var selected: String? = null
        var message: String? = null
        show({ state.value }, onSelectReader = { selected = it }, onSnackbar = { message = it })
        compose.onNodeWithText("Active reader").assertIsEnabled().performClick()
        compose.onNodeWithText("Other reader").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(null, selected)
            assertEquals("Wait for the current operation to finish before changing readers.", message)
        }
    }

    private fun show(
        state: () -> HyperLpaUiState,
        onOpenProfile: (ProfileInfo) -> Unit = {},
        onSelectReader: (String) -> Unit = {},
        onSnackbar: (String) -> Unit = {},
    ) {
        compose.setContent {
            val current = state()
            HyperLpaTheme(settings = current.settings) {
                CompositionLocalProvider(LocalMiuixSnackbar provides { message, _ -> onSnackbar(message) }) {
                Scaffold { padding ->
                    ProfilesScreen(
                        state = current, contentPadding = padding, scrollBehavior = MiuixScrollBehavior(),
                        bluetoothReaderState = BluetoothReaderUiState(false, false, false, false),
                        onSearchChange = {}, onSelectReader = onSelectReader, onResolveReaderAccess = {},
                        onOpenEuiccDetails = {}, onOpenProfile = onOpenProfile, onEnableChange = { _, _ -> },
                        onSetPinned = { _, _ -> }, onRename = { _, _ -> }, onRefresh = {},
                    )
                }
                }
            }
        }
    }

    private fun assertResultHidden() {
        compose.onNodeWithText("Active reader").assertDoesNotExist()
        compose.onNodeWithText("EID").assertDoesNotExist()
        compose.onNodeWithText("Test profile", substring = true).assertDoesNotExist()
    }

    private fun assertControlsDisabled() {
        compose.onNodeWithText("Active reader").assertIsEnabled()
        compose.onNodeWithText("EID").assertIsEnabled()
        compose.onNodeWithText("Test profile", substring = true).assertIsEnabled()
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    private fun assertControlsEnabled() {
        compose.onNodeWithText("Active reader").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("EID").assertIsDisplayed().assertIsEnabled()
        compose.onNode(isToggleable()).assertIsEnabled()
    }
}
