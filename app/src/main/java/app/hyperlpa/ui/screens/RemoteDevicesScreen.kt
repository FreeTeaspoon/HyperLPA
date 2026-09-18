package app.hyperlpa.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hyperlpa.R
import app.hyperlpa.remote.RemoteDevices
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

@Composable
internal fun RemoteDevicesScreen(devices: RemoteDevices, onBack: () -> Unit) {
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
    LaunchedEffect(state.loaded, state.relay) {
        if (state.loaded) { address = state.relay; name = state.name; if (state.relay.isNotBlank()) enrollment = "" }
    }
    DetailLazyScaffold(title = stringResource(R.string.remote_title), onBack = onBack) { _ ->
        if (!state.loaded) item { InfiniteProgressIndicator(modifier = Modifier.padding(24.dp)) }
        state.error?.let { message -> item { TipCard(message) } }
        if (state.relay.isBlank()) {
            item { TipCard(stringResource(R.string.remote_setup_tip)) }
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
            item { TipCard(stringResource(R.string.remote_background_tip)) }
            if (state.pendingOperations > 0) item { TipCard(stringResource(R.string.remote_pending_operations)) }
            item { SectionHeading(stringResource(R.string.remote_peers)) }
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
                    }
                }
            }
            item { TipCard(stringResource(R.string.remote_pairing_tip)) }
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
