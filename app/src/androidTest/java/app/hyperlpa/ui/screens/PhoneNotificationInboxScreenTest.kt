package app.hyperlpa.ui.screens

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.remote.*
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold

class PhoneNotificationInboxScreenTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()
    private val devices = RemoteDevicesUiState(loaded = true, peers = listOf(
        RemotePeerUi("one", "Travel phone", true, false, true),
        RemotePeerUi("two", "Home phone", true, false, false)))
    private fun entry(id: String, title: String, time: Long) =
        PhoneNotificationEntry(id, "org.example.messages", title, "Message body", time, appLabel = "Messages")

    @Test fun combinesDevicesWithoutReadControlsAndKeepsPendingEuiccAccessibleWithoutAReader() {
        var inbox by mutableStateOf(mapOf(
            "one" to PhoneInboxDevice().snapshot(listOf(entry("same", "Travel message", now)), true, now),
            "two" to PhoneInboxDevice().snapshot(listOf(entry("same", "Home message", now + 1)), true, now)))
        var pendingOpened = false
        compose.setContent {
            HyperLpaTheme(settings = AppSettings()) {
                Scaffold { padding ->
                    PhoneNotificationInboxScreen(inbox, devices, emptyMap(), padding, MiuixScrollBehavior(),
                        pendingEuiccCount = 1,
                        onArchive = { id, entry, archived -> inbox = inbox + (id to
                            if (archived) inbox.getValue(id).archive(entry) else inbox.getValue(id).restore(entry)) },
                        onRefresh = {}, onOpenDevices = {}, onOpenPendingEuicc = { pendingOpened = true })
                }
            }
        }
        compose.onNodeWithText("Home message").assertIsDisplayed()
        compose.onNodeWithText("Travel message").assertIsDisplayed()
        compose.onAllNodesWithText("Messages on", substring = true).assertCountEquals(2)
        compose.onNodeWithText("Messages on Home phone,", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Offline", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Unread only").assertDoesNotExist()
        compose.onNodeWithTag("phone-notification/two/same").assertHasNoClickAction()
        messageActions("two/same").performClick()
        compose.onNodeWithText("Archive", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Home message").assertDoesNotExist()
        compose.onNodeWithText("Travel message").assertIsDisplayed()
        showArchived()
        compose.onNodeWithText("Home message").assertIsDisplayed()
        compose.onNodeWithText("Travel message").assertDoesNotExist()
        messageActions("two/same").performClick()
        compose.onNodeWithText("Restore", useUnmergedTree = true).performClick()
        compose.onNodeWithText("No archived notifications").assertIsDisplayed()
        showInbox()
        compose.onNodeWithText("Home message").assertIsDisplayed()
        compose.onNodeWithText("Pending eUICC notifications").performClick()
        assertTrue(pendingOpened)
    }

    @Test fun deviceDropdownFiltersAndLargeHistoryRemainsLazyAndReachesLastEntry() {
        val entries = (0..149).map { entry("entry-$it", "Message $it", now - it) }
        val inbox = mapOf("one" to PhoneInboxDevice().snapshot(entries, true, now),
            "two" to PhoneInboxDevice().snapshot(listOf(entry("home", "Only home", now + 1)), true, now))
        compose.setContent {
            HyperLpaTheme(settings = AppSettings()) {
                Scaffold { padding ->
                    PhoneNotificationInboxScreen(inbox, devices, emptyMap(), padding, MiuixScrollBehavior(),
                        onRefresh = {}, onOpenDevices = {}, onOpenPendingEuicc = {})
                }
            }
        }
        assertTrue(compose.onAllNodes(hasText("Message ", substring = true)).fetchSemanticsNodes().size < 30)
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Home phone", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Only home").assertIsDisplayed()
        compose.onNodeWithText("Message 0").assertDoesNotExist()
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Travel phone", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("phone-inbox-list").performScrollToKey("one/entry-149")
        compose.onNodeWithText("Message 149").assertIsDisplayed()
    }

    @Test fun filteringDoesNotRefreshOrExposeBackgroundErrors() {
        var refreshes = 0
        val inbox = mapOf("one" to PhoneInboxDevice().snapshot(listOf(entry("one", "Travel message", now)), true, now),
            "two" to PhoneInboxDevice().snapshot(listOf(entry("two", "Home message", now + 1)), true, now))
        val views = mapOf("one" to PhoneNotificationView("one", loaded = true, error = "Background timeout"),
            "two" to PhoneNotificationView("two", loaded = true, error = "Disconnected"))
        compose.setContent {
            HyperLpaTheme(AppSettings()) {
                Scaffold { padding ->
                    PhoneNotificationInboxScreen(inbox, devices, views, padding, MiuixScrollBehavior(),
                        onRefresh = { refreshes++ },
                        onOpenDevices = {}, onOpenPendingEuicc = {})
                }
            }
        }
        showArchived()
        showInbox()
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Home phone", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Home message").assertIsDisplayed()
        compose.onNodeWithText("Could not refresh notifications").assertDoesNotExist()
        assertEquals(0, refreshes)
    }

    @Test fun refreshFailureIsScopedToSourceAndRemainsDuringRetry() {
        var refreshing by mutableStateOf(false)
        var requested: String? = "not-called"
        val inbox = mapOf("one" to PhoneInboxDevice().snapshot(listOf(entry("one", "Travel message", now)), true, now))
        compose.setContent {
            HyperLpaTheme(AppSettings()) {
                Scaffold { padding ->
                    PhoneNotificationInboxScreen(inbox, devices,
                        mapOf("one" to PhoneNotificationView("one", loaded = true,
                            refreshing = refreshing, refreshError = "Timed out")),
                        padding, MiuixScrollBehavior(),
                        onRefresh = { requested = it; refreshing = true },
                        onOpenDevices = {}, onOpenPendingEuicc = {})
                }
            }
        }
        compose.onNodeWithText("Travel phone: Timed out").assertIsDisplayed()
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Home phone", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Could not refresh notifications").assertDoesNotExist()
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Travel phone", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Could not refresh notifications").performClick()
        assertEquals("one", requested)
        compose.onNodeWithText("Travel phone: Timed out").assertIsDisplayed()
        compose.onNodeWithText("Travel message").assertIsDisplayed()
    }

    @Test fun copyMessageCopiesBodyAsSensitiveWithoutChangingTheList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = entry("copy", "Your verification code", now).copy(text = "Your code is 123456. Do not share it.")
        compose.setContent {
            HyperLpaTheme(AppSettings()) {
                Scaffold { padding ->
                    PhoneNotificationInboxScreen(mapOf("one" to PhoneInboxDevice().snapshot(listOf(message), true, now)),
                        devices, emptyMap(), padding, MiuixScrollBehavior(),
                        onRefresh = {}, onOpenDevices = {}, onOpenPendingEuicc = {})
                }
            }
        }
        messageActions("one/copy").performClick()
        compose.onNodeWithText("Copy", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your verification code").assertIsDisplayed()
        compose.onNodeWithTag("phone-notification/one/copy").assertHasNoClickAction()
        compose.runOnIdle {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            assertNotNull(clip)
            assertEquals(message.text, clip!!.getItemAt(0).text.toString())
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        }
    }
    @Test fun deletingSavedCopyRequiresConfirmationAndCancelKeepsMessage() {
        val message = entry("delete", "Remove this copy", now)
        var inbox by mutableStateOf(mapOf("one" to PhoneInboxDevice().snapshot(listOf(message), true, now)))
        var deletions = 0
        compose.setContent {
            HyperLpaTheme(AppSettings()) {
                Scaffold { padding ->
                    PhoneNotificationInboxScreen(inbox, devices, emptyMap(), padding, MiuixScrollBehavior(),
                        onDelete = { id, deleted ->
                            deletions++
                            inbox = inbox + (id to inbox.getValue(id).delete(deleted))
                        }, onRefresh = {}, onOpenDevices = {}, onOpenPendingEuicc = {})
                }
            }
        }
        messageActions("one/delete").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Delete saved copy?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Remove this copy").assertIsDisplayed()
        assertEquals(0, deletions)
        messageActions("one/delete").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { deletions == 1 }
        compose.onNodeWithText("Remove this copy").assertDoesNotExist()
        showArchived()
        compose.onNodeWithText("Remove this copy").assertDoesNotExist()
    }

    @Test fun duplicateAppTitleAppearsOnlyOnceAndArchivedAlertTargetOpensArchive() {
        val message = entry("target", "Messages", now)
        var consumed = false
        compose.setContent {
            HyperLpaTheme(AppSettings()) {
                Scaffold { padding ->
                    PhoneNotificationInboxScreen(mapOf("one" to PhoneInboxDevice().snapshot(listOf(message), true, now).archive(message)),
                        devices, emptyMap(), padding, MiuixScrollBehavior(), target = PhoneInboxTarget("one", "target"),
                        onTargetConsumed = { consumed = true }, onRefresh = {}, onOpenDevices = {}, onOpenPendingEuicc = {})
                }
            }
        }
        compose.waitUntil(5_000) { consumed }
        compose.onNodeWithText("Messages on Travel phone").assertIsDisplayed()
        compose.onAllNodesWithText("Messages", substring = true).assertCountEquals(1)
        compose.onNodeWithText("Travel phone", substring = true).assertIsDisplayed()
        messageActions("one/target").performClick()
        compose.onNodeWithText("Restore", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun compactCardKeepsContentAndMenuVisibleAtDoubleTextSize() {
        checkCompactLayout(LayoutDirection.Ltr)
    }

    @Test fun compactCardMirrorsAtDoubleTextSize() {
        checkCompactLayout(LayoutDirection.Rtl)
    }

    private fun checkCompactLayout(direction: LayoutDirection) {
        val message = entry("compact", "Delivery update", now).copy(text = "Ready to collect.")
        val source = "Messages on The phone with a longer device name"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides direction,
            ) {
                HyperLpaTheme(AppSettings()) {
                    Scaffold {
                        Box(Modifier.width(320.dp)) {
                            PhoneNotificationRow(message, Modifier.testTag("compact-card"),
                                sourceName = "The phone with a longer device name",
                                onCopyCode = {}, onArchive = {}, onDelete = {})
                        }
                    }
                }
            }
        }
        val card = compose.onNodeWithTag("compact-card").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Delivery update", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithText("Ready to collect.", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val origin = compose.onNodeWithText(source, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithContentDescription("Message actions", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Title $title, body $body, card $card", body.top >= title.bottom)
        assertTrue("Source $origin, body $body", origin.top >= body.bottom)
        if (direction == LayoutDirection.Ltr) assertEquals(title.left, body.left, 1f)
        else assertEquals(title.right, body.right, 1f)
        for (bounds in listOf(title, body, origin, menu)) {
            assertTrue(bounds.left >= card.left && bounds.right <= card.right)
            assertTrue(bounds.top >= card.top && bounds.bottom <= card.bottom)
        }
        compose.onNodeWithContentDescription("Message actions").performClick()
        compose.onNodeWithText("Copy", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Archive", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Delete", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun messageActions(key: String) = compose.onNode(
        hasContentDescription("Message actions") and hasAnyAncestor(hasTestTag("phone-notification/$key")))

    private fun showArchived() {
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithText("Archived", useUnmergedTree = true).performClick()
    }

    private fun showInbox() {
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithText("Inbox", useUnmergedTree = true).performClick()
    }

}
