package app.hyperlpa.ui.screens

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.provider.Telephony
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hyperlpa.R
import app.hyperlpa.remote.PhoneNotificationEntry
import app.hyperlpa.remote.PhoneNotificationListener
import app.hyperlpa.remote.RemoteDevices
import app.hyperlpa.remote.RemotePeerUi
import app.hyperlpa.remote.detectOtp
import app.hyperlpa.ui.LocalMiuixSnackbar
import app.hyperlpa.ui.components.AppIcon
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.DialogActionRow
import app.hyperlpa.ui.components.EmptyState
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.LoadingState
import app.hyperlpa.ui.components.PageStart
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.TextInputDialog
import app.hyperlpa.ui.components.TipCard
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date

private const val RemoteSetupGuideUrl = "https://github.com/FreeTeaspoon/HyperLPA/blob/main/relay/README.md"
private const val LocalHistory = "local"
private const val HiddenNotificationText = "Sensitive notification content hidden"
private val OnlineColor = Color(0xFF34C759)

@Composable
internal fun RemoteDevicesScreen(
    devices: RemoteDevices,
    onBack: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onOpenPhoneNotifications: () -> Unit,
) {
    val state by devices.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val showSnackbar = LocalMiuixSnackbar.current
    val scope = rememberCoroutineScope()
    val codeLabel = stringResource(R.string.remote_code_label)
    val codeCopied = stringResource(R.string.remote_code_copied)
    // Credentials deliberately do not survive Activity/process recreation in saved state.
    var address by remember { mutableStateOf("") }
    var enrollment by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var enteringCode by remember { mutableStateOf(false) }
    var dialogPeer by remember { mutableStateOf<RemotePeerUi?>(null) }
    var showPeerDialog by remember { mutableStateOf(false) }
    var removeRelay by remember { mutableStateOf(false) }
    val configured = state.loaded && state.relay.isNotBlank()
    LaunchedEffect(state.loaded, state.relay) {
        if (state.loaded) { address = state.relay; name = state.name; if (state.relay.isNotBlank()) enrollment = "" }
    }
    DetailLazyScaffold(
        title = stringResource(R.string.remote_title),
        onBack = onBack,
        isRefreshing = refreshing,
        onRefresh = if (configured) {
            {
                if (!refreshing) scope.launch {
                    refreshing = true
                    try { devices.refresh() } finally { refreshing = false }
                }
            }
        } else null,
    ) { _ ->
        when {
            !state.loaded -> item(contentType = PageStart.Viewport) {
                LoadingState(stringResource(R.string.common_loading), Modifier.fillParentMaxSize())
            }
            !configured -> {
                state.error?.let { message -> item(contentType = PageStart.Inset) { TipCard(message) } }
                item(contentType = PageStart.Inset) { RemoteSetupTip() }
                item { SmallTitle(stringResource(R.string.remote_relay_address)) }
                item {
                    FormField(address, { if (it.length <= 253) address = it },
                        stringResource(R.string.remote_relay_address_hint), keyboardType = KeyboardType.Uri)
                }
                item { SmallTitle(stringResource(R.string.remote_enrollment_key)) }
                item {
                    FormField(enrollment, { if (it.length <= 256) enrollment = it },
                        stringResource(R.string.remote_enrollment_key_hint), keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation())
                }
                item { SmallTitle(stringResource(R.string.remote_device_name)) }
                item {
                    FormField(name, { if (it.length <= 80) name = it },
                        stringResource(R.string.remote_device_name_hint), last = true)
                }
                item {
                    TextButton(
                        text = stringResource(R.string.remote_save),
                        onClick = { devices.configure(address, enrollment, name) },
                        enabled = !state.busy && address.isNotBlank() && enrollment.isNotBlank() && name.isNotBlank(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    )
                }
            }
            else -> {
                item(contentType = PageStart.Inset) { TipCard(stringResource(R.string.remote_background_tip)) }
                state.error?.let { message -> item { TipCard(message) } }
                if (state.pendingOperations > 0) item { TipCard(stringResource(R.string.remote_pending_operations)) }
                item {
                    GroupedCard {
                        SwitchPreference(
                            title = stringResource(R.string.remote_access),
                            summary = stringResource(R.string.remote_access_summary),
                            checked = state.enabled,
                            onCheckedChange = devices::setEnabled,
                        )
                        BasicComponent(
                            title = state.relay.removePrefix("https://"),
                            summary = stringResource(if (state.connected) R.string.remote_connected else R.string.remote_disconnected),
                        )
                        ArrowPreference(
                            title = stringResource(R.string.remote_device_name),
                            summary = state.name,
                            onClick = { editingName = true },
                        )
                    }
                }
                item {
                    GroupedCard {
                        ArrowPreference(
                            title = stringResource(R.string.phone_notifications_title),
                            summary = stringResource(if (state.sharePhoneNotifications) R.string.phone_notifications_on
                                else R.string.phone_notifications_off),
                            onClick = onOpenPhoneNotifications,
                        )
                    }
                }
                if (state.peers.isNotEmpty()) {
                    item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.remote_peers)) }
                    item {
                        GroupedCard {
                            state.peers.forEach { peer ->
                                key(peer.id) {
                                    PeerRow(
                                        peer = peer,
                                        sharing = state.sharePhoneNotifications && peer.id in state.notificationPeers,
                                        onOpen = {
                                            if (peer.approved) onOpenDevice(peer.id)
                                            else { dialogPeer = peer; showPeerDialog = true }
                                        },
                                        onSharingChange = { share ->
                                            if (share && !state.sharePhoneNotifications) devices.setPhoneNotificationSharing(true)
                                            devices.setNotificationPeer(peer.id, share)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                item(contentType = PageStart.Inset) { TipCard(stringResource(R.string.remote_pairing_tip)) }
                item {
                    GroupedCard {
                        ArrowPreference(
                            title = stringResource(R.string.remote_create_code),
                            enabled = state.connected,
                            onClick = devices::createInvitation,
                        )
                        state.invitation?.let { invitation ->
                            BasicComponent(
                                title = stringResource(R.string.remote_copy_code),
                                summary = stringResource(R.string.remote_code_expiry),
                                endActions = {
                                    Icon(
                                        MiuixIcons.Copy,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                },
                                onClick = {
                                    context.copySensitive(codeLabel, invitation)
                                    showSnackbar(codeCopied, SnackbarDuration.Short)
                                },
                            )
                        }
                        ArrowPreference(
                            title = stringResource(R.string.remote_enter_code),
                            enabled = state.connected,
                            onClick = { enteringCode = true },
                        )
                    }
                }
                item {
                    GroupedCard {
                        ArrowPreference(
                            title = stringResource(R.string.remote_remove_relay),
                            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                            onClick = { removeRelay = true },
                        )
                    }
                }
            }
        }
    }
    TextInputDialog(
        show = editingName,
        title = stringResource(R.string.remote_device_name),
        summary = stringResource(R.string.remote_name_summary),
        initialValue = state.name,
        maxLength = 80,
        onDismiss = { editingName = false },
        onConfirm = { value ->
            if (value.trim() != state.name) devices.setName(value)
            editingName = false
        },
    )
    TextInputDialog(
        show = enteringCode,
        title = stringResource(R.string.remote_enter_code),
        summary = stringResource(R.string.remote_enter_code_summary),
        label = codeLabel,
        initialValue = "",
        confirmText = stringResource(R.string.remote_pair),
        maxLength = 4096,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        onDismiss = { enteringCode = false },
        onConfirm = { code ->
            devices.pair(code)
            enteringCode = false
        },
    )
    val peer = dialogPeer
    OverlayDialog(
        show = showPeerDialog && peer != null,
        title = if (peer?.incoming == true) stringResource(R.string.remote_incoming_request) else peer?.name,
        summary = peer?.let {
            stringResource(if (it.incoming) R.string.remote_incoming_summary else R.string.remote_outgoing_summary, it.name)
        },
        onDismissRequest = { showPeerDialog = false },
    ) {
        if (peer?.incoming == true) {
            DialogActionRow(
                onCancel = { devices.forget(peer.id); showPeerDialog = false },
                cancelText = stringResource(R.string.remote_remove),
                confirmText = stringResource(R.string.remote_approve),
                confirmEnabled = state.connected,
                onConfirm = { devices.approve(peer.id); showPeerDialog = false },
            )
        } else {
            DialogActionRow(
                onCancel = { showPeerDialog = false },
                confirmText = stringResource(R.string.remote_remove),
                destructive = true,
                onConfirm = { peer?.let { devices.forget(it.id) }; showPeerDialog = false },
            )
        }
    }
    OverlayDialog(
        show = removeRelay,
        title = stringResource(R.string.remote_remove_relay_title),
        summary = stringResource(R.string.remote_remove_relay_summary),
        onDismissRequest = { removeRelay = false },
    ) {
        DialogActionRow(
            onCancel = { removeRelay = false },
            confirmText = stringResource(R.string.remote_remove),
            destructive = true,
            onConfirm = { devices.unregister(); removeRelay = false },
        )
    }
}

@Composable
private fun RemoteSetupTip() {
    val tip = stringResource(R.string.remote_setup_tip)
    val guide = stringResource(R.string.remote_setup_guide)
    val linkStyle = TextLinkStyles(SpanStyle(color = MiuixTheme.colorScheme.primary))
    TipCard {
        Text(
            text = buildAnnotatedString {
                append(tip)
                append(" ")
                withLink(LinkAnnotation.Url(RemoteSetupGuideUrl, linkStyle)) { append(guide) }
            },
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    last: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = placeholder,
        useLabelAsPlaceholder = true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
        ),
        visualTransformation = visualTransformation,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = if (last) 12.dp else 6.dp),
    )
}

@Composable
private fun PeerRow(
    peer: RemotePeerUi,
    sharing: Boolean,
    onOpen: () -> Unit,
    onSharingChange: (Boolean) -> Unit,
) {
    val status = stringResource(if (peer.online) R.string.remote_online else R.string.remote_offline)
    if (peer.approved) {
        BasicComponent(
            title = peer.name,
            startAction = { StatusDot(peer.online, status) },
            endActions = { Switch(checked = sharing, onCheckedChange = onSharingChange) },
            onClick = onOpen,
        )
    } else {
        ArrowPreference(
            title = peer.name,
            summary = stringResource(if (peer.incoming) R.string.remote_incoming_request else R.string.remote_waiting_approval),
            startAction = { StatusDot(online = false, description = status) },
            onClick = onOpen,
        )
    }
}

@Composable
private fun StatusDot(online: Boolean, description: String) {
    Box(
        Modifier
            .padding(end = 12.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(if (online) OnlineColor else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f))
            .semantics { contentDescription = description },
    )
}

@Composable
internal fun PhoneNotificationSettingsScreen(devices: RemoteDevices, onBack: () -> Unit, onOpenHistory: () -> Unit) {
    val state by devices.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val showSnackbar = LocalMiuixSnackbar.current
    val listenerGranted = rememberNotificationListenerGranted()
    val commandCopied = stringResource(R.string.phone_notifications_adb_copied)
    var showSensitiveNotificationSetup by remember { mutableStateOf(false) }
    val sensitiveNotificationCommand = remember(context.packageName) {
        "adb shell cmd appops set --user 0 ${context.packageName} RECEIVE_SENSITIVE_NOTIFICATIONS allow"
    }
    val messagesApp = remember { runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull() }
    val apps = remember(state.notificationApps, messagesApp) {
        state.notificationApps.map { it to (installedAppLabel(context, it) ?: it) }
            .sortedWith(compareBy({ it.first != messagesApp }, { it.second.lowercase() }))
    }
    DetailLazyScaffold(title = stringResource(R.string.phone_notifications_title), onBack = onBack) { _ ->
        item {
            GroupedCard {
                SwitchPreference(
                    title = stringResource(R.string.phone_notifications_share),
                    summary = stringResource(R.string.phone_notifications_share_summary),
                    checked = state.sharePhoneNotifications,
                    onCheckedChange = devices::setPhoneNotificationSharing,
                )
                if (state.sharePhoneNotifications) {
                    ArrowPreference(
                        title = stringResource(R.string.phone_notifications_access),
                        summary = stringResource(if (listenerGranted) R.string.phone_notifications_access_granted
                            else R.string.phone_notifications_access_required),
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    )
                    if (Build.VERSION.SDK_INT >= 35) {
                        ArrowPreference(
                            title = stringResource(R.string.phone_notifications_adb_title),
                            summary = stringResource(R.string.phone_notifications_adb_summary),
                            onClick = { showSensitiveNotificationSetup = true },
                        )
                    }
                }
                ArrowPreference(
                    title = stringResource(R.string.phone_notifications_this_phone),
                    onClick = onOpenHistory,
                )
            }
        }
        if (state.sharePhoneNotifications) {
            item(contentType = PageStart.Inset) { TipCard(stringResource(R.string.phone_notifications_apps_tip)) }
            if (apps.isNotEmpty()) item {
                GroupedCard {
                    apps.forEach { (packageName, label) ->
                        key(packageName) {
                            SwitchPreference(
                                title = label,
                                checked = packageName in state.allowedNotificationApps,
                                startAction = { AppIcon(packageName, label, Modifier.padding(end = 12.dp)) },
                                onCheckedChange = { devices.setNotificationApp(packageName, it) },
                            )
                        }
                    }
                }
            }
        }
    }
    val adbTitle = stringResource(R.string.phone_notifications_adb_title)
    OverlayDialog(
        show = showSensitiveNotificationSetup,
        title = adbTitle,
        summary = stringResource(R.string.phone_notifications_adb_instructions),
        onDismissRequest = { showSensitiveNotificationSetup = false },
    ) {
        Column {
            Text(sensitiveNotificationCommand, style = MiuixTheme.textStyles.body2)
            Spacer(Modifier.height(12.dp))
            DialogActionRow(
                onCancel = { showSensitiveNotificationSetup = false },
                confirmText = stringResource(R.string.phone_notifications_adb_copy),
                onConfirm = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText(adbTitle, sensitiveNotificationCommand),
                    )
                    showSnackbar(commandCopied, SnackbarDuration.Short)
                    showSensitiveNotificationSetup = false
                },
            )
        }
    }
}

@Composable
internal fun PhoneNotificationHistoryScreen(devices: RemoteDevices, deviceId: String, onBack: () -> Unit) {
    val state by devices.ui.collectAsStateWithLifecycle()
    val phoneView by devices.phoneView.collectAsStateWithLifecycle()
    val localNotifications by devices.localNotifications.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val showSnackbar = LocalMiuixSnackbar.current
    val codeLabel = stringResource(R.string.phone_notifications_code_label)
    val codeCopied = stringResource(R.string.phone_notifications_code_copied)
    val local = deviceId == LocalHistory
    val peer = state.peers.firstOrNull { it.id == deviceId }
    var clearRequested by remember { mutableStateOf(false) }
    var removeRequested by remember { mutableStateOf(false) }
    LaunchedEffect(deviceId) {
        if (!local) devices.refreshPhoneNotifications(deviceId)
    }
    DisposableEffect(deviceId) {
        onDispose { if (!local) devices.closePhoneNotifications(deviceId) }
    }
    val view = phoneView.takeIf { it.deviceId == deviceId }
    val entries = if (local) localNotifications else view?.entries.orEmpty()
    val available = local || view?.available == true
    val firstLoad = !local && (view == null || (view.loading && !view.loaded))
    val title = if (local) state.name else peer?.name.orEmpty()
    val copyCode: (String) -> Unit = { code ->
        context.copySensitive(codeLabel, code)
        showSnackbar(codeCopied, SnackbarDuration.Short)
    }
    DetailLazyScaffold(
        title = title.ifBlank { stringResource(R.string.phone_notifications_title) },
        onBack = onBack,
        actions = {
            if (entries.isNotEmpty()) {
                IconButton(onClick = { clearRequested = true }) {
                    Icon(MiuixIcons.Delete, contentDescription = stringResource(R.string.phone_notifications_clear))
                }
            }
            if (peer != null) {
                OverlayIconDropdownMenu(
                    entry = DropdownEntry(listOf(DropdownItem(
                        text = stringResource(R.string.remote_remove_device),
                        onClick = { removeRequested = true },
                    ))),
                ) {
                    Icon(MiuixIcons.More, contentDescription = stringResource(R.string.remote_more_options))
                }
            }
        },
        isRefreshing = view?.refreshing == true,
        onRefresh = if (local) null else ({ devices.refreshPhoneNotifications(deviceId, userInitiated = true) }),
        emptyOverlay = if (!firstLoad && available && entries.isEmpty()) ({
            EmptyState(
                title = stringResource(R.string.phone_notifications_empty),
                message = "",
                icon = MiuixIcons.Messages,
                modifier = Modifier.fillMaxSize(),
            )
        }) else null,
    ) { _ ->
        when {
            firstLoad -> item(contentType = PageStart.Viewport) {
                LoadingState(stringResource(R.string.common_loading), Modifier.fillParentMaxSize())
            }
            !available -> item(contentType = PageStart.Inset) {
                TipCard(stringResource(R.string.phone_notifications_unavailable))
            }
            entries.isEmpty() -> Unit
            else -> items(entries.asReversed(), key = { it.id }) { entry ->
                PhoneNotificationRow(entry, Modifier.animateItem(), onCopyCode = copyCode)
            }
        }
    }
    OverlayDialog(
        show = clearRequested,
        title = stringResource(R.string.phone_notifications_clear_title),
        summary = if (local) stringResource(R.string.phone_notifications_clear_local_summary)
            else stringResource(R.string.phone_notifications_clear_remote_summary, title),
        onDismissRequest = { clearRequested = false },
    ) {
        DialogActionRow(
            onCancel = { clearRequested = false },
            confirmText = stringResource(R.string.phone_notifications_clear),
            destructive = true,
            onConfirm = { devices.clearPhoneNotifications(deviceId); clearRequested = false },
        )
    }
    OverlayDialog(
        show = removeRequested,
        title = stringResource(R.string.remote_remove_title),
        summary = stringResource(R.string.remote_remove_summary),
        onDismissRequest = { removeRequested = false },
    ) {
        DialogActionRow(
            onCancel = { removeRequested = false },
            confirmText = stringResource(R.string.remote_remove),
            destructive = true,
            onConfirm = {
                removeRequested = false
                devices.forget(deviceId)
                onBack()
            },
        )
    }
}

@Composable
private fun PhoneNotificationRow(entry: PhoneNotificationEntry, modifier: Modifier, onCopyCode: (String) -> Unit) {
    val context = LocalContext.current
    val appName = remember(entry.packageName, entry.appLabel) {
        entry.appLabel.ifBlank { installedAppLabel(context, entry.packageName) ?: entry.packageName }
    }
    val hidden = entry.text.trim().equals(HiddenNotificationText, ignoreCase = true)
    val body = if (hidden) stringResource(R.string.phone_notifications_content_hidden) else entry.text.trim()
    val time = remember(entry.timestamp) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp))
    }
    val codeStyle = TextLinkStyles(SpanStyle(color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold))
    val summary = buildAnnotatedString {
        val code = if (hidden) null else detectOtp(body)
        if (code == null) {
            append(body)
        } else {
            append(body, 0, code.first)
            val value = body.substring(code)
            withLink(LinkAnnotation.Clickable("otp", codeStyle) { onCopyCode(value) }) { append(value) }
            append(body, code.last + 1, body.length)
        }
        if (body.isNotEmpty()) append(" · ")
        append(time)
    }
    GroupedCard(modifier) {
        BasicComponent(startAction = { AppIcon(entry.packageName, appName, Modifier.padding(end = 12.dp)) }) {
            Text(
                text = entry.title.trim().ifBlank { appName },
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun rememberNotificationListenerGranted(): Boolean {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    return lifecycle.isAtLeast(Lifecycle.State.STARTED) &&
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
            ComponentName(context, PhoneNotificationListener::class.java))
}

private fun installedAppLabel(context: Context, packageName: String): String? = runCatching {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
}.getOrNull()

private fun Context.copySensitive(label: String, value: String) {
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
