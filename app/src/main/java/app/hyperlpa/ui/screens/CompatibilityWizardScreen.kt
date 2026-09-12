package app.hyperlpa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hyperlpa.R
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.ui.BluetoothReaderAvailability
import app.hyperlpa.ui.BluetoothReaderUiState
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.TipCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CompatibilityWizardScreen(
    state: HyperLpaUiState,
    bluetoothReaderState: BluetoothReaderUiState,
    onBack: () -> Unit,
    onDiscoverReaders: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenReaderSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    val selectedReader = state.lpa.selectedReader?.takeIf { it.available }
    val availableReaders = state.lpa.readers.filter { it.available }
    val checkingReaders = !state.lpa.initialized || when (state.lpa.operation) {
        is LpaOperation.DiscoveringReaders,
        is LpaOperation.Connecting,
        is LpaOperation.Refreshing,
        -> true
        else -> false
    }
    val readerDetected = hasDetectedCompatibilityReader(selectedReader, availableReaders)
    val readerStatus = when {
        checkingReaders -> stringResource(R.string.compatibility_checking)
        selectedReader != null -> stringResource(R.string.compatibility_ready)
        availableReaders.isNotEmpty() -> stringResource(R.string.compatibility_reader_found)
        else -> stringResource(R.string.compatibility_not_ready)
    }
    val readerStatusColor = when {
        selectedReader != null -> MiuixTheme.colorScheme.primary
        checkingReaders -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MiuixTheme.colorScheme.error
    }
    val readerSummary = when {
        selectedReader != null -> readerDescription(selectedReader)
        checkingReaders -> stringResource(R.string.compatibility_checking_summary)
        availableReaders.isNotEmpty() -> pluralStringResource(
            R.plurals.compatibility_readers_found_summary,
            availableReaders.size,
            availableReaders.size,
        )
        else -> stringResource(R.string.compatibility_no_reader_summary)
    }
    val discoverySummary = if (checkingReaders) {
        stringResource(R.string.compatibility_discovering_summary)
    } else {
        stringResource(R.string.compatibility_discover_summary)
    }
    val continueLabel = stringResource(
        if (readerDetected) R.string.common_continue else R.string.compatibility_continue,
    )

    DetailLazyScaffold(
        title = stringResource(R.string.compatibility_title),
        onBack = onBack,
    ) { _ ->
        item {
            TipCard {
                Text(
                    text = stringResource(R.string.compatibility_intro),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.compatibility_phone_esim_note),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item { SectionHeading(stringResource(R.string.compatibility_status_section)) }
        item {
            GroupedCard {
                BasicComponent(
                    title = stringResource(R.string.compatibility_reader_status_title),
                    summary = readerSummary,
                    enabled = false,
                    endActions = {
                        Text(
                            text = readerStatus,
                            color = readerStatusColor,
                            style = MiuixTheme.textStyles.body2,
                        )
                    },
                )
                if (selectedReader == null) {
                    availableReaders.take(MaxVisibleReaders).forEach { reader ->
                        BasicComponent(
                            title = reader.name,
                            summary = readerDescription(reader),
                            enabled = false,
                        )
                    }
                }
                BluetoothStatusPreference(
                    bluetoothReaderState = bluetoothReaderState,
                    onRequestPermission = onRequestBluetoothPermission,
                    onOpenBluetoothSettings = onOpenBluetoothSettings,
                    onOpenReaderSettings = onOpenReaderSettings,
                )
            }
        }

        item { SectionHeading(stringResource(R.string.compatibility_actions_section)) }
        item {
            GroupedCard {
                BasicComponent(
                    title = stringResource(R.string.compatibility_discover),
                    summary = discoverySummary,
                    enabled = !checkingReaders,
                    onClick = onDiscoverReaders,
                    onClickLabel = stringResource(R.string.compatibility_discover),
                    role = Role.Button,
                    endActions = {
                        if (checkingReaders) {
                            InfiniteProgressIndicator(size = 20.dp)
                        } else {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.compatibility_reader_settings),
                    summary = stringResource(R.string.compatibility_reader_settings_summary),
                    onClick = onOpenReaderSettings,
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = continueLabel,
                    onClick = onContinue,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun BluetoothStatusPreference(
    bluetoothReaderState: BluetoothReaderUiState,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenReaderSettings: () -> Unit,
) {
    val title: String
    val summary: String
    val action: BluetoothWizardAction
    when (bluetoothReaderState.availability) {
        BluetoothReaderAvailability.DISABLED -> {
            title = stringResource(R.string.compatibility_bluetooth_disabled)
            summary = stringResource(R.string.compatibility_bluetooth_disabled_summary)
            action = BluetoothWizardAction.OpenReaderSettings
        }
        BluetoothReaderAvailability.UNSUPPORTED -> {
            title = stringResource(R.string.compatibility_bluetooth_status)
            summary = stringResource(R.string.compatibility_bluetooth_unsupported_summary)
            action = BluetoothWizardAction.None
        }
        BluetoothReaderAvailability.PERMISSION_REQUIRED -> {
            title = stringResource(R.string.compatibility_bluetooth_permission)
            summary = stringResource(R.string.compatibility_bluetooth_permission_summary)
            action = BluetoothWizardAction.RequestPermission
        }
        BluetoothReaderAvailability.BLUETOOTH_OFF -> {
            title = stringResource(R.string.compatibility_bluetooth_status)
            summary = stringResource(R.string.compatibility_bluetooth_off_summary)
            action = BluetoothWizardAction.OpenBluetoothSettings
        }
        BluetoothReaderAvailability.READY -> {
            title = stringResource(R.string.compatibility_bluetooth_status)
            summary = stringResource(R.string.compatibility_bluetooth_ready_summary)
            action = BluetoothWizardAction.OpenBluetoothSettings
        }
    }
    when (action) {
        BluetoothWizardAction.OpenReaderSettings -> ArrowPreference(
            title = title,
            summary = summary,
            onClick = onOpenReaderSettings,
        )
        BluetoothWizardAction.OpenBluetoothSettings -> BasicComponent(
            title = title,
            summary = summary,
            onClick = onOpenBluetoothSettings,
            onClickLabel = title,
            role = Role.Button,
        )
        BluetoothWizardAction.RequestPermission -> BasicComponent(
            title = title,
            summary = summary,
            onClick = onRequestPermission,
            onClickLabel = title,
            role = Role.Button,
        )
        BluetoothWizardAction.None -> BasicComponent(
            title = title,
            summary = summary,
            enabled = false,
        )
    }
}

@Composable
private fun readerDescription(reader: ReaderInfo): String = listOfNotNull(
    stringResource(reader.kind.labelResource()),
    reader.detail?.takeIf(String::isNotBlank),
).joinToString(" · ").ifBlank {
    stringResource(R.string.compatibility_reader_no_detail)
}

private fun ReaderKind.labelResource(): Int = when (this) {
    ReaderKind.NBRIDGE -> R.string.reader_kind_nbridge
    ReaderKind.OMAPI -> R.string.reader_kind_omapi
    ReaderKind.TELEPHONY -> R.string.reader_kind_telephony
    ReaderKind.USB_CCID -> R.string.reader_kind_usb
    ReaderKind.BLE -> R.string.reader_kind_bluetooth
    ReaderKind.REMOTE -> R.string.reader_kind_remote
}

internal fun hasDetectedCompatibilityReader(
    selectedReader: ReaderInfo?,
    availableReaders: List<ReaderInfo>,
): Boolean = selectedReader != null || availableReaders.isNotEmpty()

private enum class BluetoothWizardAction {
    None,
    OpenReaderSettings,
    OpenBluetoothSettings,
    RequestPermission,
}

private const val MaxVisibleReaders = 3
