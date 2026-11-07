package app.hyperlpa.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hyperlpa.R
import app.hyperlpa.remote.*
import app.hyperlpa.ui.LocalMiuixSnackbar
import app.hyperlpa.ui.adaptive.CenteredContent
import app.hyperlpa.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

internal data class PhoneInboxTarget(val deviceId: String, val entryId: String)

@Composable
internal fun PhoneNotificationInboxScreen(
    inbox: Map<String, PhoneInboxDevice>,
    devices: RemoteDevicesUiState,
    views: Map<String, PhoneNotificationView>,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    pendingEuiccCount: Int = 0,
    refreshing: Boolean = false,
    refreshFailure: PhoneInboxRefreshFailure? = null,
    target: PhoneInboxTarget? = null,
    onTargetConsumed: () -> Unit = {},
    onArchive: (String, PhoneNotificationEntry, Boolean) -> Unit = { _, _, _ -> },
    onDelete: (String, PhoneNotificationEntry) -> Unit = { _, _ -> },
    onRefresh: (String?) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenPendingEuicc: () -> Unit,
) {
    var selectedDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val peers = devices.peers.filter { it.approved && it.notificationSource }
    val sourceIds = peers.map { it.id }.toSet()
    val selected = selectedDevice.takeIf { id -> peers.any { it.id == id } }
    val entries = remember(inbox, sourceIds, selected, showArchived) {
        inbox.filterKeys { it in sourceIds && (selected == null || selected == it) }.flatMap { (id, history) ->
            history.entries.filter { history.isArchived(it) == showArchived }.map { id to it }
        }.sortedByDescending { it.second.timestamp }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(target, inbox, sourceIds, selected, showArchived) {
        val current = target ?: return@LaunchedEffect
        val archivedTarget = inbox[current.deviceId]?.entries?.firstOrNull { it.id == current.entryId }
            ?.let { inbox.getValue(current.deviceId).isArchived(it) } == true
        if (selected != null || showArchived != archivedTarget) {
            selectedDevice = null
            showArchived = archivedTarget
            return@LaunchedEffect
        }
        val index = entries.indexOfFirst { it.first == current.deviceId && it.second.id == current.entryId }
        if (index >= 0) {
            listState.scrollToItem(index + 1)
            onTargetConsumed()
        } else if (inbox[current.deviceId]?.loaded == true || peers.none { it.id == current.deviceId }) {
            onTargetConsumed()
        }
    }
    val visibleViews = views.filterKeys { id -> peers.any { it.id == id } && (selected == null || selected == id) }
    val firstLoad = !devices.loaded || (entries.isEmpty() && visibleViews.values.any { it.loading && !it.loaded })
    val failedDevice = visibleViews.entries.firstOrNull { it.value.refreshError != null }
    val failure = refreshFailure?.takeIf { it.deviceId == null || selected == null || selected == it.deviceId }?.message
        ?: failedDevice?.let { (id, view) ->
            stringResource(R.string.phone_inbox_source_failure, peers.first { it.id == id }.name, view.refreshError.orEmpty())
        }
    val context = LocalContext.current
    val snackbar = LocalMiuixSnackbar.current
    val copied = stringResource(R.string.phone_notifications_code_copied)
    val codeLabel = stringResource(R.string.phone_notifications_code_label)
    var deleteTarget by remember { mutableStateOf<Pair<String, PhoneNotificationEntry>?>(null) }
    var deleteRequested by remember { mutableStateOf(false) }
    var deleteAfterExit by remember { mutableStateOf(false) }
    val refreshState = rememberPullToRefreshState()
    PullToRefresh(
        isRefreshing = refreshing || visibleViews.values.any { it.refreshing },
        onRefresh = { onRefresh(selected) },
        enabled = !firstLoad,
        pullToRefreshState = refreshState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + RefreshHeaderTopGap),
        topAppBarScrollBehavior = scrollBehavior,
    ) {
        CenteredContent { sidePadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("phone-inbox-list").scrollEndHaptic().overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                overscrollEffect = null,
                contentPadding = PaddingValues(start = sidePadding, end = sidePadding,
                    top = contentPadding.calculateTopPadding() + 12.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp),
            ) {
                item(key = "inbox-controls", contentType = "inbox-controls") {
                    Column {
                        if (peers.isNotEmpty() || inbox.isNotEmpty()) {
                            GroupedCard {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.phone_inbox_device_filter),
                                    items = listOf(stringResource(R.string.phone_inbox_all_devices)) + peers.map { it.name },
                                    selectedIndex = peers.indexOfFirst { it.id == selected } + 1,
                                    onSelectedIndexChange = { selectedDevice = peers.getOrNull(it - 1)?.id },
                                )
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.phone_inbox_view),
                                    items = listOf(stringResource(R.string.phone_inbox_inbox), stringResource(R.string.phone_inbox_archived)),
                                    selectedIndex = if (showArchived) 1 else 0,
                                    onSelectedIndexChange = { showArchived = it == 1 },
                                )
                            }
                        }
                        if (pendingEuiccCount > 0) {
                            GroupedCard {
                                ArrowPreference(title = stringResource(R.string.phone_inbox_pending_euicc),
                                    summary = stringResource(R.string.phone_inbox_pending_count, pendingEuiccCount),
                                    onClick = onOpenPendingEuicc)
                            }
                        }
                        if (failure != null) {
                            GroupedCard {
                                ArrowPreference(title = stringResource(R.string.phone_inbox_refresh_failed),
                                    summary = failure, onClick = { onRefresh(selected) })
                            }
                        }
                    }
                }
                if (entries.isEmpty()) {
                    inlinePageStateItem(key = "inbox-state", listState = listState) { stateModifier ->
                        PageStateHost(state = if (firstLoad) PageStateKind.LOADING else PageStateKind.EMPTY,
                            modifier = Modifier.fillMaxWidth()) { kind ->
                            if (kind == PageStateKind.LOADING) LoadingState(stringResource(R.string.common_loading), stateModifier)
                            else EmptyState(
                                title = stringResource(if (showArchived) R.string.phone_inbox_archive_empty else R.string.phone_notifications_empty),
                                message = stringResource(if (showArchived) R.string.phone_inbox_archive_empty_summary else R.string.phone_inbox_empty_summary),
                                icon = MiuixIcons.Community, modifier = stateModifier,
                                actionText = stringResource(if (showArchived) R.string.phone_inbox_show_inbox else R.string.remote_title),
                                onAction = if (showArchived) ({ showArchived = false }) else onOpenDevices,
                            )
                        }
                    }
                }
                items(entries, key = { (id, entry) -> "$id/${entry.id}" }, contentType = { "phone-notification" }) { (id, entry) ->
                    val peer = peers.firstOrNull { it.id == id }
                    val source = peer?.name.orEmpty()
                    PhoneNotificationRow(entry, Modifier.animateItem().testTag("phone-notification/$id/${entry.id}"),
                        sourceName = source, offline = peer?.online != true, archived = showArchived,
                        onArchive = { onArchive(id, entry, !showArchived) },
                        onDelete = { deleteTarget = id to entry; deleteRequested = true },
                        onCopyCode = { code ->
                            context.copySensitive(codeLabel, code)
                            snackbar(copied, SnackbarDuration.Short)
                        })
                }
            }
        }
    }
    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = deleteRequested,
        title = stringResource(R.string.phone_inbox_delete_title),
        summary = stringResource(R.string.phone_inbox_delete_summary),
        onDismissRequest = { deleteRequested = false },
        onDismissFinished = {
            if (deleteAfterExit) deleteTarget?.let { (id, entry) -> onDelete(id, entry) }
            deleteAfterExit = false
            deleteTarget = null
        },
    ) {
        DialogActionRow(
            onCancel = { deleteRequested = false },
            confirmText = stringResource(R.string.common_delete),
            destructive = true,
            onConfirm = { deleteAfterExit = true; deleteRequested = false },
        )
    }

}

@Composable
internal fun PhoneInboxSettingsScreen(
    devices: RemoteDevices,
    onBack: () -> Unit,
    onRequestPermission: ((Boolean) -> Unit) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenSharing: () -> Unit,
) {
    val state by devices.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var alertsAvailable by remember { mutableStateOf(true) }
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        alertsAvailable = hasRemoteNotificationPermission(context)
    }
    DetailLazyScaffold(title = stringResource(R.string.phone_inbox_alerts_title), onBack = onBack) { _ ->
        item {
            GroupedCard {
                SwitchPreference(title = stringResource(R.string.phone_inbox_alerts_enabled),
                    summary = stringResource(if (state.remoteNotificationAlerts && !alertsAvailable)
                        R.string.phone_inbox_alerts_blocked else R.string.phone_inbox_alerts_enabled_summary),
                    checked = state.remoteNotificationAlerts,
                    onCheckedChange = { enabled ->
                        if (!enabled) devices.setRemoteNotificationAlerts(false)
                        else onRequestPermission { granted ->
                            alertsAvailable = granted
                            if (granted) devices.setRemoteNotificationAlerts(true)
                        }
                    })
                ArrowPreference(title = stringResource(R.string.phone_inbox_system_settings), onClick = onOpenSystemSettings)
            }
        }
        item { GroupedCard {
            ArrowPreference(title = stringResource(R.string.phone_notifications_title), onClick = onOpenSharing)
        } }
    }
}
