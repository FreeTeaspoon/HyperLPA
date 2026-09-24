package app.hyperlpa.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.ComponentName
import android.app.NotificationManager
import android.os.PersistableBundle
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hyperlpa.R
import app.hyperlpa.remote.RemoteDevices
import app.hyperlpa.remote.PhoneNotificationEntry
import app.hyperlpa.ui.components.PageStart
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.TipCard
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.text.DateFormat
import java.util.Date

@Composable
internal fun RemoteDevicesScreen(devices: RemoteDevices, onBack: () -> Unit, onOpenHistory: (String) -> Unit) {
    val state by devices.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val codeLabel = stringResource(R.string.remote_code_label)
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    // Credentials deliberately do not survive Activity/process recreation in saved state.
    var enrollment by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var removePeer by remember { mutableStateOf<String?>(null) }
    var removeRelay by remember { mutableStateOf(false) }
    var showSensitiveNotificationSetup by remember { mutableStateOf(false) }
    val sensitiveNotificationCommand = remember(context.packageName) {
        "adb shell cmd appops set --user 0 ${context.packageName} RECEIVE_SENSITIVE_NOTIFICATIONS allow"
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val listenerGranted = lifecycle.isAtLeast(Lifecycle.State.STARTED) &&
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
            ComponentName(context, app.hyperlpa.remote.PhoneNotificationListener::class.java))
    LaunchedEffect(state.loaded, state.relay) {
        if (state.loaded) { address = state.relay; name = state.name; if (state.relay.isNotBlank()) enrollment = "" }
    }
    DetailLazyScaffold(title = stringResource(R.string.remote_title), onBack = onBack) { _ ->
        if (!state.loaded) item { InfiniteProgressIndicator(modifier = Modifier.padding(24.dp)) }
        state.error?.let { message -> item(contentType = PageStart.Inset) { TipCard(message) } }
        if (state.relay.isBlank()) {
            item(contentType = PageStart.Inset) { TipCard(stringResource(R.string.remote_setup_tip)) }
            item {
                GroupedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextField(value = address, onValueChange = { if (it.length <= 253) address = it },
                            label = stringResource(R.string.remote_relay_address), singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextField(value = enrollment, onValueChange = { if (it.length <= 256) enrollment = it },
                            label = stringResource(R.string.remote_enrollment_key), singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        TextField(value = name, onValueChange = { if (it.length <= 80) name = it },
                            label = stringResource(R.string.remote_device_name), singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(text = stringResource(R.string.remote_save),
                            enabled = !state.busy && address.isNotBlank() && enrollment.isNotBlank() && name.isNotBlank(),
                            onClick = { devices.configure(address, enrollment, name) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            item {
                GroupedCard {
                    SwitchPreference(title = stringResource(R.string.remote_access),
                        summary = stringResource(R.string.remote_access_summary), checked = state.enabled,
                        enabled = !state.busy, onCheckedChange = devices::setEnabled)
                    ArrowPreference(title = state.relay,
                        summary = stringResource(if (state.connected) R.string.remote_connected else R.string.remote_disconnected),
                        onClick = devices::discover, enabled = state.connected)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextField(value = name, onValueChange = { if (it.length <= 80) name = it },
                            label = stringResource(R.string.remote_device_name), singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(text = stringResource(R.string.remote_name_save), enabled = !state.busy && name.isNotBlank() && name != state.name,
                            onClick = { devices.setName(name) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item(contentType = PageStart.Inset) { TipCard(stringResource(R.string.remote_background_tip)) }
            if (state.pendingOperations > 0) item(contentType = PageStart.Inset) { TipCard(stringResource(R.string.remote_pending_operations)) }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.remote_peers)) }
            items(state.peers, key = { it.id }) { peer ->
                GroupedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(peer.name)
                        Text(stringResource(when {
                            !peer.approved && peer.incoming -> R.string.remote_incoming_request
                            !peer.approved -> R.string.remote_waiting_approval
                            peer.online -> R.string.remote_online
                            else -> R.string.remote_offline
                        }))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (peer.incoming && !peer.approved) TextButton(text = stringResource(R.string.remote_approve),
                                enabled = !state.busy && state.connected, onClick = { devices.approve(peer.id) }, modifier = Modifier.weight(1f))
                            TextButton(text = stringResource(R.string.remote_remove), enabled = !state.busy,
                                onClick = { removePeer = peer.id }, modifier = Modifier.weight(1f))
                        }
                        if (peer.approved) {
                            SwitchPreference(title = stringResource(R.string.phone_notifications_allow_peer),
                                checked = peer.id in state.notificationPeers,
                                enabled = !state.busy && state.sharePhoneNotifications,
                                onCheckedChange = { devices.setNotificationPeer(peer.id, it) })
                            TextButton(text = stringResource(R.string.phone_notifications_view),
                                onClick = { onOpenHistory(peer.id) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item(contentType = PageStart.Inset) { TipCard(stringResource(R.string.remote_pairing_tip)) }
            item {
                GroupedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(text = stringResource(R.string.remote_create_code), enabled = !state.busy && state.connected,
                            onClick = devices::createInvitation, modifier = Modifier.fillMaxWidth())
                        state.invitation?.let { invitation ->
                            TextButton(text = stringResource(R.string.remote_copy_code), onClick = {
                                val clip = ClipData.newPlainText(codeLabel, invitation)
                                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                            }, modifier = Modifier.fillMaxWidth())
                        }
                        TextField(value = code, onValueChange = { if (it.length <= 4096) code = it },
                            label = stringResource(R.string.remote_code_label), singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        TextButton(text = stringResource(R.string.remote_pair), enabled = !state.busy && state.connected && code.isNotBlank(),
                            onClick = { devices.pair(code); code = "" }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                GroupedCard {
                    ArrowPreference(title = stringResource(R.string.remote_refresh), onClick = devices::discover, enabled = state.connected)
                    ArrowPreference(title = stringResource(R.string.remote_remove_relay), onClick = { removeRelay = true }, enabled = !state.busy)
                }
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.phone_notifications_title)) }
        item {
            GroupedCard {
                SwitchPreference(title = stringResource(R.string.phone_notifications_share),
                    summary = stringResource(R.string.phone_notifications_share_summary),
                    checked = state.sharePhoneNotifications, enabled = !state.busy,
                    onCheckedChange = devices::setPhoneNotificationSharing)
                if (state.sharePhoneNotifications) {
                    ArrowPreference(title = stringResource(R.string.phone_notifications_access),
                        summary = stringResource(if (listenerGranted) R.string.phone_notifications_access_granted
                            else R.string.phone_notifications_access_required),
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
                    if (Build.VERSION.SDK_INT >= 35) {
                        ArrowPreference(title = stringResource(R.string.phone_notifications_adb_title),
                            summary = stringResource(R.string.phone_notifications_adb_summary),
                            onClick = { showSensitiveNotificationSetup = true })
                    }
                    ArrowPreference(title = stringResource(R.string.phone_notifications_this_phone),
                        onClick = { onOpenHistory("local") })
                }
            }
        }
        if (state.sharePhoneNotifications) {
            item(contentType = PageStart.Inset) { TipCard(stringResource(R.string.phone_notifications_apps_tip)) }
            items(state.notificationApps.sorted()) { packageName ->
                val label = remember(packageName) { runCatching {
                    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
                }.getOrDefault(packageName) }
                GroupedCard {
                    SwitchPreference(title = label, summary = packageName,
                        checked = packageName in state.allowedNotificationApps,
                        onCheckedChange = { devices.setNotificationApp(packageName, it) })
                }
            }
        }
    }
    OverlayDialog(
        show = showSensitiveNotificationSetup,
        title = stringResource(R.string.phone_notifications_adb_title),
        summary = stringResource(R.string.phone_notifications_adb_instructions),
        onDismissRequest = { showSensitiveNotificationSetup = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(sensitiveNotificationCommand)
            TextButton(text = stringResource(R.string.phone_notifications_adb_copy), onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(context.getString(R.string.phone_notifications_adb_title), sensitiveNotificationCommand),
                )
                Toast.makeText(context, R.string.phone_notifications_adb_copied, Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth())
        }
    }
    OverlayDialog(show = removePeer != null || removeRelay,
        title = stringResource(if (removeRelay) R.string.remote_remove_relay_title else R.string.remote_remove_title),
        summary = stringResource(if (removeRelay) R.string.remote_remove_relay_summary else R.string.remote_remove_summary),
        onDismissRequest = { removePeer = null; removeRelay = false }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = stringResource(R.string.remote_cancel), onClick = { removePeer = null; removeRelay = false }, modifier = Modifier.weight(1f))
            TextButton(text = stringResource(R.string.remote_remove), onClick = {
                if (removeRelay) devices.unregister() else removePeer?.let(devices::forget)
                removePeer = null; removeRelay = false
            }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun PhoneNotificationHistoryScreen(devices: RemoteDevices, deviceId: String, onBack: () -> Unit) {
    val state by devices.ui.collectAsStateWithLifecycle()
    val phoneView by devices.phoneView.collectAsStateWithLifecycle()
    val localNotifications by devices.localNotifications.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) {
        if (deviceId != "local") devices.refreshPhoneNotifications(deviceId)
    }
    DisposableEffect(deviceId) {
        onDispose { if (deviceId != "local") devices.closePhoneNotifications(deviceId) }
    }
    PhoneNotificationHistoryContent(
        title = if (deviceId == "local") state.name else state.peers.firstOrNull { it.id == deviceId }?.name.orEmpty(),
        entries = if (deviceId == "local") localNotifications else phoneView.entries.takeIf { phoneView.deviceId == deviceId }.orEmpty(),
        available = deviceId == "local" || (phoneView.deviceId == deviceId && phoneView.available),
        loading = deviceId != "local" && phoneView.deviceId == deviceId && phoneView.loading,
        onBack = onBack,
        onRefresh = { if (deviceId != "local") devices.refreshPhoneNotifications(deviceId) },
        onDelete = { id -> devices.deletePhoneNotification(id, deviceId) },
        onClear = if (deviceId == "local") devices::clearPhoneNotifications else null,
    )
}

@Composable
private fun PhoneNotificationHistoryContent(
    title: String,
    entries: List<PhoneNotificationEntry>,
    available: Boolean,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onClear: (() -> Unit)?,
) {
    val context = LocalContext.current
    var deleteId by remember { mutableStateOf<String?>(null) }
    var clearRequested by remember { mutableStateOf(false) }
    DetailLazyScaffold(title = title.ifBlank { stringResource(R.string.phone_notifications_title) }, onBack = onBack) { _ ->
        if (loading) {
            item(contentType = PageStart.Viewport) {
                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    InfiniteProgressIndicator()
                }
            }
        } else {
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.phone_notifications_title)) }
            item(contentType = PageStart.Inset) { TipCard(stringResource(R.string.phone_notifications_delete_tip)) }
            if (!available) item(contentType = PageStart.Inset) {
                TipCard(stringResource(R.string.phone_notifications_unavailable))
            }
            if (available && entries.isEmpty()) item(contentType = PageStart.Inset) {
                TipCard(stringResource(R.string.phone_notifications_empty))
            }
            item {
                GroupedCard {
                    if (onClear != null) ArrowPreference(title = stringResource(R.string.phone_notifications_clear),
                        onClick = { clearRequested = true }, enabled = entries.isNotEmpty())
                    else ArrowPreference(title = stringResource(R.string.phone_notifications_refresh), onClick = onRefresh)
                }
            }
            items(entries.asReversed(), key = { it.id }) { entry ->
                val appName = remember(entry.packageName) { runCatching {
                    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(entry.packageName, 0)).toString()
                }.getOrDefault(entry.packageName) }
                GroupedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(appName + " · " + DateFormat.getDateTimeInstance().format(Date(entry.timestamp)))
                        if (entry.title.isNotBlank() && entry.title != appName) Text(entry.title)
                        if (entry.text.isNotBlank()) Text(if (entry.text.trim().equals(
                                "Sensitive notification content hidden", ignoreCase = true,
                            )) stringResource(R.string.phone_notifications_content_hidden) else entry.text)
                        TextButton(text = stringResource(R.string.phone_notifications_delete), onClick = { deleteId = entry.id })
                    }
                }
            }
        }
    }
    OverlayDialog(show = deleteId != null || clearRequested,
        title = stringResource(if (clearRequested) R.string.phone_notifications_clear_title
            else R.string.phone_notifications_delete_title),
        summary = stringResource(R.string.phone_notifications_delete_tip),
        onDismissRequest = { deleteId = null; clearRequested = false }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = stringResource(R.string.remote_cancel), onClick = {
                deleteId = null; clearRequested = false
            }, modifier = Modifier.weight(1f))
            TextButton(text = stringResource(if (clearRequested) R.string.phone_notifications_clear
                else R.string.phone_notifications_delete), onClick = {
                if (clearRequested) onClear?.invoke() else deleteId?.let(onDelete)
                deleteId = null; clearRequested = false
            }, modifier = Modifier.weight(1f))
        }
    }
}
