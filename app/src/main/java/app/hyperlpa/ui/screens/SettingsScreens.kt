package app.hyperlpa.ui.screens

import app.hyperlpa.ui.components.PageStart
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.LaunchedEffect
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import app.hyperlpa.ui.components.FieldError
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hyperlpa.BuildConfig
import app.hyperlpa.R
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.DefaultIsdrAids
import app.hyperlpa.data.settings.FloatingBottomBarStyle
import app.hyperlpa.data.settings.IsdrAidValidationError
import app.hyperlpa.data.settings.IsdrAidValidationException
import app.hyperlpa.data.settings.MaximumAidCandidates
import app.hyperlpa.data.settings.MaximumAidEditorCharacters
import app.hyperlpa.data.settings.MaximumRemoteReaderEditorCharacters
import app.hyperlpa.data.settings.MaximumRemoteReaderEndpoints
import app.hyperlpa.data.settings.MAX_INTERFACE_SCALE
import app.hyperlpa.data.settings.MIN_INTERFACE_SCALE
import app.hyperlpa.data.settings.NavigationLabels
import app.hyperlpa.data.settings.NavigationStyle
import app.hyperlpa.data.settings.PhoneFormatStrategy
import app.hyperlpa.data.settings.ProfileLayout
import app.hyperlpa.data.settings.ProfileNameRedactionMode
import app.hyperlpa.data.settings.ProfileSort
import app.hyperlpa.data.settings.RedactionMode
import app.hyperlpa.data.settings.ThemeAccent
import app.hyperlpa.data.settings.ThemeMode
import app.hyperlpa.data.settings.ThemePalette
import app.hyperlpa.data.settings.RemoteReaderSettingsValidationError
import app.hyperlpa.data.settings.RemoteReaderSettingsValidationException
import app.hyperlpa.data.settings.isValidRemoteReaderToken
import app.hyperlpa.data.settings.normalizedInterfaceScale
import app.hyperlpa.data.settings.validateIsdrAids
import app.hyperlpa.data.settings.validateRemoteReaderSettings
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.HyperLpaViewModel
import app.hyperlpa.ui.BluetoothReaderAvailability
import app.hyperlpa.ui.BluetoothReaderUiState
import app.hyperlpa.ui.LocalMiuixSnackbar
import app.hyperlpa.ui.components.lazyGroup
import app.hyperlpa.ui.components.ExpandingPreferenceGroup
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.rememberAppBackdrop
import app.hyperlpa.ui.components.redactIdentifier
import app.hyperlpa.ui.components.DialogActionRow
import app.hyperlpa.ui.components.PrimaryPageButton
import app.hyperlpa.ui.components.TextInputDialog
import app.hyperlpa.ui.components.ValuePreference
import app.hyperlpa.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun AppearanceSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
    onPredictiveBackChange: ((Boolean) -> Unit)? = viewModel::setPredictiveBack,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var densityDraft by remember(settings.densityScale) {
        mutableFloatStateOf(
            (normalizedInterfaceScale(settings.densityScale) * 100f).roundToInt().toFloat(),
        )
    }
    var showDensityDialog by rememberSaveable { mutableStateOf(false) }
    val densityTextState = rememberTextFieldState()
    val blurSupported = isRuntimeShaderSupported() && top.yukonga.miuix.kmp.shader.isRenderEffectSupported()
    val backdrop = rememberAppBackdrop()
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val themeModeLabels = ThemeMode.entries.map { stringResource(it.labelResource()) }
    val paletteLabels = ThemePalette.entries.map { stringResource(it.labelResource()) }
    val accentLabels = ThemeAccent.entries.map { stringResource(it.labelResource()) }
    val floatingBarLabels = FloatingBottomBarStyle.entries.map { stringResource(it.labelResource()) }
    val navigationLabelOptions = NavigationLabels.entries.map { stringResource(it.labelResource()) }

    DetailLazyScaffold(
        title = stringResource(R.string.settings_appearance_theme), onBack = onBack, fullWidth = true,
    ) { _ ->
                item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.appearance_color_theme)) }
                item {
                    GroupedCard {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.appearance_theme_mode),
                            summary = themeModeLabels[settings.themeMode.ordinal],
                            items = themeModeLabels,
                            selectedIndex = settings.themeMode.ordinal,
                            onSelectedIndexChange = { index ->
                                ThemeMode.entries.getOrNull(index)?.let(viewModel::setThemeMode)
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.appearance_monet_colors),
                            summary = stringResource(R.string.appearance_monet_colors_summary),
                            checked = settings.useMonet,
                            onCheckedChange = viewModel::setUseMonet,
                        )
                        AnimatedVisibility(
                            visible = settings.useMonet,
                            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                        ) {
                            Column {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.appearance_color_style),
                                    summary = paletteLabels[settings.palette.ordinal],
                                    items = paletteLabels,
                                    selectedIndex = settings.palette.ordinal,
                                    onSelectedIndexChange = { index ->
                                        ThemePalette.entries.getOrNull(index)?.let(viewModel::setPalette)
                                    },
                                )
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.appearance_accent_color),
                                    summary = accentLabels[settings.accent.ordinal],
                                    items = accentLabels,
                                    selectedIndex = settings.accent.ordinal,
                                    onSelectedIndexChange = { index ->
                                        ThemeAccent.entries.getOrNull(index)?.let(viewModel::setAccent)
                                    },
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.appearance_pure_black),
                                    summary = stringResource(R.string.appearance_pure_black_summary),
                                    checked = settings.pureBlack,
                                    onCheckedChange = viewModel::setPureBlack,
                                )
                            }
                        }
                    }
                }

                item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.appearance_interface_effects)) }
                item {
                    GroupedCard {
                        SwitchPreference(
                            title = stringResource(R.string.appearance_blur_effects),
                            summary = stringResource(R.string.appearance_blur_effects_summary),
                            checked = settings.blurEnabled && blurSupported,
                            enabled = blurSupported,
                            onCheckedChange = viewModel::setBlurEnabled,
                        )
                        if (onPredictiveBackChange != null) {
                            SwitchPreference(
                                title = stringResource(R.string.appearance_predictive_back),
                                summary = stringResource(R.string.appearance_predictive_back_summary),
                                checked = settings.predictiveBack,
                                onCheckedChange = onPredictiveBackChange,
                            )
                        }
                        ArrowPreference(
                            title = stringResource(R.string.appearance_interface_scale),
                            summary = stringResource(R.string.appearance_interface_scale_summary),
                            endActions = {
                                Text(
                                    text = stringResource(
                                        R.string.appearance_scale_percent,
                                        densityDraft.roundToInt(),
                                    ),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            },
                            bottomAction = {
                                Slider(
                                    value = densityDraft.coerceIn(
                                        MIN_INTERFACE_SCALE * 100f,
                                        MAX_INTERFACE_SCALE * 100f,
                                    ),
                                    onValueChange = { densityDraft = it },
                                    onValueChangeFinished = {
                                        viewModel.setDensityScale(densityDraft / 100f)
                                    },
                                    valueRange =
                                        (MIN_INTERFACE_SCALE * 100f)..(MAX_INTERFACE_SCALE * 100f),
                                    showKeyPoints = true,
                                    keyPoints = listOf(80f, 90f, 100f, 110f),
                                    magnetThreshold = 0.01f,
                                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            holdDownState = showDensityDialog,
                            onClick = {
                                densityTextState.setTextAndPlaceCursorAtEnd(densityDraft.roundToInt().toString())
                                showDensityDialog = true
                            },
                        )
                    }
                }

                item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.appearance_bottom_navigation)) }
                item {
                    GroupedCard {
                        SwitchPreference(
                            title = stringResource(R.string.appearance_floating_bottom_bar),
                            summary = stringResource(R.string.appearance_floating_bottom_bar_summary),
                            checked = settings.navigationStyle == NavigationStyle.FLOATING,
                            onCheckedChange = { enabled ->
                                viewModel.setNavigationStyle(
                                    if (enabled) NavigationStyle.FLOATING else NavigationStyle.STANDARD,
                                )
                            },
                        )
                        AnimatedVisibility(
                            visible = settings.navigationStyle == NavigationStyle.FLOATING,
                            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                        ) {
                            Column {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.appearance_floating_bar_style),
                                summary = floatingBarLabels[settings.floatingBottomBarStyle.ordinal],
                                items = floatingBarLabels,
                                selectedIndex = settings.floatingBottomBarStyle.ordinal,
                                onSelectedIndexChange = { index ->
                                    FloatingBottomBarStyle.entries.getOrNull(index)
                                        ?.let(viewModel::setFloatingBottomBarStyle)
                                },
                            )
                            }
                        }
                        OverlayDropdownPreference(
                            title = stringResource(R.string.appearance_bottom_bar_options),
                            summary = navigationLabelOptions[settings.navigationLabels.ordinal],
                            items = navigationLabelOptions,
                            selectedIndex = settings.navigationLabels.ordinal,
                            onSelectedIndexChange = { index ->
                                NavigationLabels.entries.getOrNull(index)?.let(viewModel::setNavigationLabels)
                            },
                        )
                    }
                }
    }


    OverlayDialog(
        show = showDensityDialog,
        title = stringResource(R.string.appearance_interface_scale),
        summary = stringResource(R.string.appearance_interface_scale_summary),
        onDismissRequest = { showDensityDialog = false },
    ) {
        val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val percent = densityTextState.text.toString().toIntOrNull()
        val valid = percent != null && percent in 80..110
        val save = {
            if (valid) {
                viewModel.setDensityScale(requireNotNull(percent) / 100f)
                showDensityDialog = false
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus(); keyboard?.show() }
        TextField(
            state = densityTextState,
            inputTransformation = DensityDigitsOnlyTransformation.maxLength(3),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            onKeyboardAction = { save() },
            trailingIcon = {
                Text(
                    text = "%",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        if (!valid) FieldError(stringResource(R.string.scale_validation))
        Spacer(Modifier.height(12.dp))
        DialogActionRow(
            onCancel = { showDensityDialog = false },
            confirmText = stringResource(R.string.common_save),
            confirmEnabled = valid,
            onConfirm = {
                save()
            },
        )
    }
}

private val DensityDigitsOnlyTransformation = InputTransformation {
    if (!asCharSequence().all { it.isDigit() }) revertAllChanges()
}

@Composable
fun ProfileDisplaySettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
) {
    val profileLayouts = ProfileLayout.entries
    val profileSorts = ProfileSort.entries
    val phoneFormatStrategies = PhoneFormatStrategy.entries

    DetailLazyScaffold(title = stringResource(R.string.settings_profile_display), onBack = onBack) { _ ->
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.profile_display_layout_section)) }
        item {
            GroupedCard {
                OverlayDropdownPreference(
                    items = profileLayouts.map { stringResource(it.labelResource()) },
                    selectedIndex = profileLayouts.indexOf(settings.profileLayout),
                    title = stringResource(R.string.profile_display_layout),
                    summary = stringResource(R.string.profile_display_layout_summary),
                    onSelectedIndexChange = { viewModel.setProfileLayout(profileLayouts[it]) },
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.profile_display_page_section)) }
        item {
            GroupedCard {
                OverlayDropdownPreference(
                    items = profileSorts.map { stringResource(it.labelResource()) },
                    selectedIndex = profileSorts.indexOf(settings.profileSort),
                    title = stringResource(R.string.profile_display_sort),
                    onSelectedIndexChange = { viewModel.setProfileSort(profileSorts[it]) },
                )
                SwitchPreference(
                    checked = settings.sortAscending,
                    onCheckedChange = viewModel::setSortAscending,
                    title = stringResource(R.string.profile_display_ascending),
                )
                SwitchPreference(
                    checked = settings.showProfileSearch,
                    onCheckedChange = viewModel::setShowProfileSearch,
                    title = stringResource(R.string.profile_display_search),
                    summary = stringResource(R.string.profile_display_search_summary),
                )
                OverlayDropdownPreference(
                    items = phoneFormatStrategies.map { stringResource(it.labelResource()) },
                    selectedIndex = phoneFormatStrategies.indexOf(settings.phoneFormatStrategy),
                    title = stringResource(R.string.profile_display_phone_format),
                    summary = stringResource(R.string.profile_display_phone_format_summary),
                    onSelectedIndexChange = { viewModel.setPhoneFormatStrategy(phoneFormatStrategies[it]) },
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.profile_display_home_cards)) }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = settings.showProfileNameOnHome,
                    onCheckedChange = viewModel::setShowProfileNameOnHome,
                    title = stringResource(R.string.profile_display_name),
                    summary = stringResource(R.string.profile_display_name_summary),
                )
                SwitchPreference(
                    checked = settings.showProfileCountryFlagOnHome,
                    onCheckedChange = viewModel::setShowProfileCountryFlagOnHome,
                    title = stringResource(R.string.profile_display_country_flag),
                    summary = stringResource(R.string.profile_display_country_flag_summary),
                )
                SwitchPreference(
                    checked = settings.showProfileProviderOnHome,
                    onCheckedChange = viewModel::setShowProfileProviderOnHome,
                    title = stringResource(R.string.profile_display_provider),
                    summary = stringResource(R.string.profile_display_provider_summary),
                )
                SwitchPreference(
                    checked = settings.showProfileIccidOnHome,
                    onCheckedChange = viewModel::setShowProfileIccidOnHome,
                    title = stringResource(R.string.profile_display_iccid),
                    summary = stringResource(R.string.profile_display_iccid_summary),
                )
                SwitchPreference(
                    checked = settings.showProfileIconOnHome,
                    onCheckedChange = viewModel::setShowProfileIconOnHome,
                    title = stringResource(R.string.profile_display_icon),
                    summary = stringResource(R.string.profile_display_icon_summary),
                )
                SwitchPreference(
                    checked = settings.showProfileTagsOnHome,
                    onCheckedChange = viewModel::setShowProfileTagsOnHome,
                    title = stringResource(R.string.profile_display_tags),
                    summary = stringResource(R.string.profile_display_tags_summary),
                )
                SwitchPreference(
                    checked = settings.showProfileRemindersOnHome,
                    onCheckedChange = viewModel::setShowProfileRemindersOnHome,
                    title = stringResource(R.string.profile_display_reminders),
                    summary = stringResource(R.string.profile_display_reminders_summary),
                )
                SwitchPreference(
                    checked = settings.showProfileSizeOnHome,
                    onCheckedChange = viewModel::setShowProfileSizeOnHome,
                    title = stringResource(R.string.profile_display_size),
                    summary = stringResource(R.string.profile_display_size_summary),
                )
                SwitchPreference(
                    checked = settings.showProfileSwitchOnHome,
                    onCheckedChange = viewModel::setShowProfileSwitchOnHome,
                    title = stringResource(R.string.profile_display_switch),
                    summary = stringResource(R.string.profile_display_switch_summary),
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.profile_display_home_reader)) }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = settings.showReaderSelectorOnHome,
                    onCheckedChange = viewModel::setShowReaderSelectorOnHome,
                    title = stringResource(R.string.profile_display_reader_selector),
                    summary = stringResource(R.string.profile_display_reader_selector_summary),
                )
                SwitchPreference(
                    checked = settings.showEidOnHome,
                    onCheckedChange = viewModel::setShowEidOnHome,
                    title = "EID",
                    summary = stringResource(R.string.profile_display_eid_summary),
                )
            }
        }
    }
}

@Composable
private fun AppearanceBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = stringResource(app.hyperlpa.R.string.common_back),
        )
    }
}

@Composable
fun ReaderSettingsScreen(
    state: HyperLpaUiState,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
    bluetoothReaderState: BluetoothReaderUiState,
    onRefreshReaders: suspend () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenRemoteReaders: () -> Unit,
) {
    val context = LocalContext.current
    val showSnackbar = LocalMiuixSnackbar.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val diagnosticsClipboardLabel = stringResource(R.string.reader_diagnostics_clipboard_label)
    val diagnosticsCopiedMessage = stringResource(R.string.reader_diagnostics_copied)
    val bluetoothAvailability = bluetoothReaderState.availability
    val usesNearbyDevicesPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val bluetoothBackendSummary = when (bluetoothAvailability) {
        BluetoothReaderAvailability.UNSUPPORTED -> stringResource(R.string.reader_bluetooth_unsupported_summary)
        BluetoothReaderAvailability.PERMISSION_REQUIRED -> stringResource(
            if (usesNearbyDevicesPermission) {
                R.string.reader_bluetooth_permission_required_summary
            } else {
                R.string.reader_bluetooth_location_required_summary
            },
        )
        BluetoothReaderAvailability.BLUETOOTH_OFF -> stringResource(R.string.reader_bluetooth_off_summary)
        else -> stringResource(R.string.reader_bluetooth_summary)
    }
    DetailLazyScaffold(
        title = stringResource(R.string.reader_settings_title),
        refreshEnabled = state.lpa.initialized && state.lpa.operation is app.hyperlpa.domain.model.LpaOperation.Idle,
        onBack = onBack,
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch {
                    try { onRefreshReaders() } finally { refreshing = false }
                }
            }
        },
    ) { _ ->
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.reader_settings_behaviour)) }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = state.settings.autoLoadProfiles,
                    onCheckedChange = viewModel::setAutoLoadProfiles,
                    title = stringResource(R.string.reader_connect_automatically),
                    summary = stringResource(R.string.reader_connect_automatically_summary),
                )
                if (state.lpa.selectedReader != null) {
                    ArrowPreference(
                        title = stringResource(R.string.reader_disconnect),
                        summary = stringResource(R.string.reader_disconnect_summary),
                        onClick = viewModel::disconnectReader,
                    )
                }
            }
        }

        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.reader_backends_section)) }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = state.settings.enableNBridge,
                    onCheckedChange = viewModel::setEnableNBridge,
                    title = "NBridge",
                    summary = stringResource(R.string.reader_nbridge_summary),
                )
                SwitchPreference(
                    checked = state.settings.enableOmapi,
                    onCheckedChange = viewModel::setEnableOmapi,
                    title = stringResource(R.string.reader_omapi_title),
                    summary = stringResource(R.string.reader_omapi_summary),
                )
                SwitchPreference(
                    checked = state.settings.enableUsbCcid,
                    onCheckedChange = viewModel::setEnableUsbCcid,
                    title = "USB CCID",
                    summary = stringResource(R.string.reader_usb_summary),
                )
                if (BuildConfig.HAS_PRIVILEGED_TELEPHONY) {
                    SwitchPreference(
                        checked = state.settings.enableTelephony,
                        onCheckedChange = viewModel::setEnableTelephony,
                        title = stringResource(R.string.reader_telephony_title),
                        summary = stringResource(R.string.reader_telephony_summary),
                    )
                } else {
                    ValuePreference(
                        title = stringResource(R.string.reader_telephony_title),
                        value = stringResource(R.string.reader_telephony_unavailable_summary),
                    )
                }
                SwitchPreference(
                    checked = state.settings.enableBle,
                    onCheckedChange = viewModel::setEnableBle,
                    title = stringResource(R.string.reader_bluetooth_title),
                    summary = bluetoothBackendSummary,
                    // Keep a restored, unsupported checked state switchable so it can be disabled.
                    enabled = bluetoothReaderState.supported || state.settings.enableBle,
                )
                ArrowPreference(
                    title = stringResource(R.string.reader_remote_title),
                    summary = if (state.settings.enableRemote) {
                        remoteReaderSummary(state.settings.remoteReaderUrls)
                    } else {
                        stringResource(R.string.common_off)
                    },
                    onClick = onOpenRemoteReaders,
                )
            }
        }

        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.reader_diagnostics_section)) }
        item {
            GroupedCard {
                ValuePreference(
                    title = "HyperLPA",
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                ValuePreference(
                    title = "Android",
                    value = stringResource(
                        R.string.reader_diagnostics_android_version,
                        Build.VERSION.RELEASE,
                        Build.VERSION.SDK_INT,
                    ),
                )
                ValuePreference(
                    title = stringResource(R.string.reader_omapi_title),
                    value = if (context.packageManager.hasSystemFeature("android.hardware.se.omapi")) {
                        stringResource(R.string.reader_omapi_advertised)
                    } else {
                        stringResource(R.string.reader_omapi_not_advertised)
                    },
                )
                val bluetoothPermissionSummary = stringResource(
                    if (bluetoothReaderState.permissionGranted) {
                        R.string.common_granted
                    } else {
                        R.string.common_not_granted
                    },
                )
                if (state.settings.enableBle &&
                    bluetoothReaderState.supported &&
                    !bluetoothReaderState.permissionGranted
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.reader_bluetooth_permission),
                        summary = bluetoothPermissionSummary,
                        onClick = onRequestBluetoothPermission,
                    )
                } else {
                    ValuePreference(
                        title = stringResource(R.string.reader_bluetooth_permission),
                        value = bluetoothPermissionSummary,
                    )
                }
                val bluetoothAdapterSummary = when {
                    !bluetoothReaderState.supported -> stringResource(R.string.reader_bluetooth_unsupported)
                    !bluetoothReaderState.permissionGranted -> stringResource(
                        R.string.reader_bluetooth_adapter_permission_required,
                    )
                    bluetoothReaderState.adapterEnabled -> stringResource(R.string.reader_bluetooth_adapter_on)
                    else -> stringResource(R.string.reader_bluetooth_adapter_off)
                }
                if (bluetoothReaderState.supported && bluetoothReaderState.permissionGranted) {
                    ArrowPreference(
                        title = stringResource(R.string.reader_bluetooth_adapter),
                        summary = bluetoothAdapterSummary,
                        onClick = onOpenBluetoothSettings,
                    )
                } else {
                    ValuePreference(
                        title = stringResource(R.string.reader_bluetooth_adapter),
                        value = bluetoothAdapterSummary,
                    )
                }
                ValuePreference(
                    title = stringResource(R.string.reader_telephony_permission),
                    value = privilegedTelephonyStatus(context),
                )
                BasicComponent(
                    title = stringResource(R.string.reader_copy_diagnostics),
                    summary = stringResource(R.string.reader_copy_diagnostics_summary),
                    endActions = {
                        Icon(
                            imageVector = MiuixIcons.Copy,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                diagnosticsClipboardLabel,
                                buildCompatibilityDiagnostics(state, context),
                            ),
                        )
                        showSnackbar(diagnosticsCopiedMessage, SnackbarDuration.Short)
                    },
                )
            }
        }

        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.reader_available_now)) }
        if (state.lpa.readers.isEmpty()) {
            item {
                GroupedCard {
                    when (bluetoothAvailability) {
                        BluetoothReaderAvailability.PERMISSION_REQUIRED -> ArrowPreference(
                            title = stringResource(R.string.reader_bluetooth_permission_required_title),
                            summary = stringResource(
                                if (usesNearbyDevicesPermission) {
                                    R.string.reader_bluetooth_permission_required_message
                                } else {
                                    R.string.reader_bluetooth_location_required_message
                                },
                            ),
                            onClick = onRequestBluetoothPermission,
                        )
                        BluetoothReaderAvailability.BLUETOOTH_OFF -> ArrowPreference(
                            title = stringResource(R.string.reader_bluetooth_off_title),
                            summary = stringResource(R.string.reader_bluetooth_off_message),
                            onClick = onOpenBluetoothSettings,
                        )
                        else -> ValuePreference(
                            title = stringResource(R.string.reader_no_readers_found),
                            value = stringResource(R.string.reader_no_readers_found_summary),
                        )
                    }
                }
            }
        } else {
            lazyGroup(state.lpa.readers, key = { it.id }, contentType = "reader") { reader ->
                        val selected = reader.id == state.lpa.selectedReaderId
                        BasicComponent(
                            title = reader.name,
                            summary = listOfNotNull(
                                stringResource(reader.kind.labelResource()),
                                reader.detail,
                            ).joinToString(": "),
                            endActions = {
                                if (selected) {
                                    Icon(
                                        imageVector = MiuixIcons.Basic.Check,
                                        contentDescription = stringResource(R.string.common_connected),
                                        tint = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                            onClick = { viewModel.connectReader(reader.id) },
                        )

                }
        }
    }
}

@Composable
fun RemoteReadersScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
) {
    var showRemoteEditor by remember { mutableStateOf(false) }
    var remoteUrls by remember {
        mutableStateOf(settings.remoteReaderUrls.joinToString("\n"))
    }
    var remoteInputTooLong by remember { mutableStateOf(false) }
    var showRemoteToken by remember { mutableStateOf(false) }
    var confirmCredentialRemoval by remember { mutableStateOf(false) }
    var removalAfterEditorExit by remember { mutableStateOf(false) }
    var reopenEditorAfterRemoval by remember { mutableStateOf(false) }
    var remoteTokenEndpoint by remember { mutableStateOf<String?>(null) }
    var remoteTokenSaving by remember { mutableStateOf(false) }
    var remoteTokenSaveFailed by remember { mutableStateOf(false) }
    var remoteUrlsSaving by remember { mutableStateOf(false) }
    var remoteUrlsSaveFailed by remember { mutableStateOf(false) }
    val remoteSaveFailedMessage = stringResource(R.string.reader_remote_save_failed)
    val tokenInvalidMessage = stringResource(R.string.reader_remote_credential_invalid)
    val remoteUrlEntries = remember(remoteUrls) {
        remoteUrls.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(MaximumRemoteReaderEndpoints + 1)
            .toList()
    }
    val remoteValidationError = remember(remoteUrlEntries) {
        runCatching { validateRemoteReaderSettings(remoteUrlEntries) }
            .exceptionOrNull() as? RemoteReaderSettingsValidationException
    }
    val remoteEditorError = when {
        remoteInputTooLong -> stringResource(R.string.reader_remote_input_too_long)
        remoteValidationError?.reason == RemoteReaderSettingsValidationError.TOO_MANY -> stringResource(
            R.string.reader_remote_too_many_endpoints,
            MaximumRemoteReaderEndpoints,
        )
        remoteValidationError?.reason == RemoteReaderSettingsValidationError.INVALID_ENDPOINT -> stringResource(
            R.string.reader_remote_invalid_endpoint_line,
            remoteValidationError.lineNumber ?: 1,
        )
        remoteValidationError?.reason == RemoteReaderSettingsValidationError.DUPLICATE_ENDPOINT -> stringResource(
            R.string.reader_remote_duplicate_endpoint_line,
            remoteValidationError.lineNumber ?: 1,
        )
        else -> null
    }
    fun saveRemoteUrls() {
        remoteUrlsSaving = true
        remoteUrlsSaveFailed = false
        viewModel.setRemoteReaderUrls(remoteUrlEntries) { success ->
            remoteUrlsSaving = false
            if (success) { remoteInputTooLong = false; showRemoteEditor = false; confirmCredentialRemoval = false }
            else {
                remoteUrlsSaveFailed = true
                if (confirmCredentialRemoval) { reopenEditorAfterRemoval = true; confirmCredentialRemoval = false }
            }
        }
    }
    DetailLazyScaffold(title = stringResource(R.string.reader_remote_title), onBack = onBack) { _ ->
        item {
            GroupedCard {
                SwitchPreference(
                    checked = settings.enableRemote,
                    onCheckedChange = viewModel::setEnableRemote,
                    title = stringResource(R.string.reader_remote_enable),
                    summary = stringResource(R.string.reader_remote_summary),
                )
                ExpandingPreferenceGroup(settings.enableRemote) {
                    ArrowPreference(
                        title = stringResource(R.string.reader_remote_addresses),
                        summary = remoteReaderSummary(settings.remoteReaderUrls),
                        onClick = {
                            remoteUrls = settings.remoteReaderUrls.joinToString("\n")
                            remoteInputTooLong = false
                            remoteUrlsSaveFailed = false
                            showRemoteEditor = true
                        },
                    )
                }
            }
        }
        item {
            ExpandingPreferenceGroup(settings.enableRemote && settings.remoteReaderUrls.isNotEmpty()) {
                SectionHeading(stringResource(R.string.reader_remote_credentials))
                GroupedCard {
                    settings.remoteReaderUrls.forEach { endpoint ->
                        ArrowPreference(title = endpoint.toUri().host ?: endpoint,
                            summary = stringResource(if (settings.remoteReaderTokens.containsKey(endpoint))
                                R.string.reader_remote_credential_stored else R.string.reader_remote_credential_none),
                            onClick = { remoteTokenSaveFailed = false; remoteTokenEndpoint = endpoint; showRemoteToken = true })
                    }
                }
            }
        }
    }

    TextEditorDialog(
        show = showRemoteEditor,
        title = stringResource(R.string.reader_remote_addresses),
        summary = stringResource(R.string.reader_remote_addresses_summary),
        value = remoteUrls,
        onValueChange = { value ->
            remoteInputTooLong = value.length > MaximumRemoteReaderEditorCharacters
            if (!remoteInputTooLong) remoteUrls = value
            remoteUrlsSaveFailed = false
        },
        error = remoteEditorError ?: remoteSaveFailedMessage.takeIf { remoteUrlsSaveFailed },
        confirmEnabled = remoteEditorError == null && !remoteUrlsSaving,
        onDismiss = {
            if (!remoteUrlsSaving) {
                remoteInputTooLong = false
                showRemoteEditor = false
            }
        },
        onDismissFinished = {
            if (removalAfterEditorExit) { removalAfterEditorExit = false; confirmCredentialRemoval = true }
            else if (!showRemoteEditor) remoteUrls = ""
        },
        onConfirm = {
            if (!remoteUrlsSaving && remoteEditorError == null) {
                val retained = validateRemoteReaderSettings(remoteUrlEntries).map { it.endpointUrl }.toSet()
                if (settings.remoteReaderTokens.keys.any { it !in retained }) {
                    removalAfterEditorExit = true
                    showRemoteEditor = false
                } else saveRemoteUrls()
            }
        },
    )
    OverlayDialog(show = confirmCredentialRemoval, title = stringResource(R.string.reader_remove_credentials_title),
        summary = stringResource(R.string.reader_remove_credentials_summary),
        onDismissRequest = { reopenEditorAfterRemoval = true; confirmCredentialRemoval = false },
        onDismissFinished = {
            if (reopenEditorAfterRemoval) { reopenEditorAfterRemoval = false; showRemoteEditor = true }
            else if (!confirmCredentialRemoval) remoteUrls = ""
        },
    ) {
        DialogActionRow(onCancel = { reopenEditorAfterRemoval = true; confirmCredentialRemoval = false },
            confirmText = stringResource(R.string.common_save), destructive = true,
            confirmEnabled = !remoteUrlsSaving, onConfirm = ::saveRemoteUrls)
    }

    TextInputDialog(
        show = showRemoteToken,
        onDismissFinished = { if (!showRemoteToken) remoteTokenEndpoint = null },
        confirmEnabled = !remoteTokenSaving,
        title = stringResource(R.string.reader_remote_credential_title),
        summary = remoteTokenEndpoint?.let { endpoint ->
            if (settings.remoteReaderTokens.containsKey(endpoint)) {
                stringResource(R.string.reader_remote_credential_replace_summary)
            } else {
                stringResource(R.string.reader_remote_credential_summary)
            }
        },
        label = stringResource(R.string.reader_remote_credential_label),
        initialValue = "",
        maxLength = 4_096,
        allowBlank = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        inputFilter = { value -> value.filterNot { it == '\r' || it == '\n' } },
        validate = { value -> tokenInvalidMessage.takeUnless { isValidRemoteReaderToken(value) } },
        error = remoteSaveFailedMessage.takeIf { remoteTokenSaveFailed },
        onDismiss = { if (!remoteTokenSaving) showRemoteToken = false },
        onConfirm = { token ->
            val endpoint = remoteTokenEndpoint
            if (endpoint != null && !remoteTokenSaving) {
                remoteTokenSaving = true
                remoteTokenSaveFailed = false
                viewModel.setRemoteReaderToken(endpoint, token) { success ->
                    remoteTokenSaving = false
                    if (success) {
                        showRemoteToken = false
                    } else {
                        remoteTokenSaveFailed = true
                    }
                }
            }
        },
    )
}

private fun bluetoothPermissionStatus(context: android.content.Context): String =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        permissionStatus(context, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            .let { granted ->
                context.getString(if (granted) R.string.common_granted else R.string.common_not_granted)
            }
    }

private fun permissionStatus(context: android.content.Context, permission: String): String =
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
        context.getString(R.string.common_granted)
    } else {
        context.getString(R.string.common_not_granted)
    }

private fun privilegedTelephonyStatus(context: android.content.Context): String {
    if (!BuildConfig.HAS_PRIVILEGED_TELEPHONY) {
        return context.getString(R.string.reader_telephony_not_in_build)
    }
    val runtime = permissionStatus(context, Manifest.permission.READ_PHONE_STATE)
    val protected = listOf(
        "android.permission.READ_PRIVILEGED_PHONE_STATE",
        "android.permission.MODIFY_PHONE_STATE",
    ).all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    return context.getString(
        R.string.reader_telephony_permission_status,
        runtime,
        context.getString(if (protected) R.string.common_granted else R.string.common_not_granted),
    )
}

private fun buildCompatibilityDiagnostics(
    state: HyperLpaUiState,
    context: android.content.Context,
): String = buildString {
    fun yesNo(value: Boolean): String =
        context.getString(if (value) R.string.support_report_yes else R.string.support_report_no)

    appendLine(context.getString(R.string.reader_diagnostics_app_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
    appendLine(context.getString(R.string.reader_diagnostics_android_line, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
    appendLine(
        context.resources.getQuantityString(
            R.plurals.reader_diagnostics_found_line,
            state.lpa.readers.size,
            state.lpa.readers.size,
        ),
    )
    appendLine(
        context.getString(
            R.string.reader_diagnostics_selected_type,
            state.lpa.selectedReader?.kind?.let { context.getString(it.labelResource()) }
                ?: context.getString(R.string.common_none),
        ),
    )
    appendLine(context.getString(R.string.reader_diagnostics_nbridge, yesNo(state.settings.enableNBridge)))
    appendLine(context.getString(R.string.reader_diagnostics_omapi, yesNo(state.settings.enableOmapi)))
    appendLine(context.getString(R.string.reader_diagnostics_usb, yesNo(state.settings.enableUsbCcid)))
    appendLine(
        context.getString(
            R.string.reader_diagnostics_telephony_build,
            yesNo(BuildConfig.HAS_PRIVILEGED_TELEPHONY),
        ),
    )
    appendLine(
        context.getString(
            R.string.reader_diagnostics_telephony_enabled,
            yesNo(BuildConfig.HAS_PRIVILEGED_TELEPHONY && state.settings.enableTelephony),
        ),
    )
    appendLine(
        context.getString(
            R.string.reader_diagnostics_bluetooth,
            yesNo(state.settings.enableBle),
            bluetoothPermissionStatus(context),
        ),
    )
    appendLine(
        context.resources.getQuantityString(
            R.plurals.reader_diagnostics_remote,
            state.settings.remoteReaderUrls.size,
            yesNo(state.settings.enableRemote),
            state.settings.remoteReaderUrls.size,
        ),
    )
    appendLine(
        context.getString(
            R.string.reader_diagnostics_eid,
            // This action explicitly promises redacted diagnostics regardless
            // of the user's normal on-screen identifier preference.
            state.lpa.euiccInfo?.let { redactIdentifier(it.eid, RedactionMode.FULL) }
                ?: context.getString(R.string.reader_diagnostics_unavailable),
        ),
    )
}

@Composable
private fun remoteReaderSummary(urls: List<String>): String {
    if (urls.isEmpty()) return stringResource(R.string.reader_remote_none_configured)
    val hosts = urls.mapNotNull { raw ->
        runCatching { raw.toUri().host }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }.distinct()
    val count = pluralStringResource(R.plurals.reader_remote_endpoint_count, urls.size, urls.size)
    return hosts.takeIf(List<String>::isNotEmpty)
        ?.joinToString(prefix = "$count: ", limit = 2, truncated = "…")
        ?: count
}

@Composable
fun NotificationSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
) {
    DetailLazyScaffold(title = stringResource(R.string.notification_settings_title), onBack = onBack) { _ ->
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.notification_settings_check_section)) }
        item {
            GroupedCard {
                SwitchPreference(
                    settings.notificationInitialLoad,
                    viewModel::setNotificationInitialLoad,
                    stringResource(R.string.notification_settings_after_connecting),
                )
                SwitchPreference(
                    settings.notificationAfterSwitch,
                    viewModel::setNotificationAfterSwitch,
                    stringResource(R.string.notification_settings_after_switching),
                )
                SwitchPreference(
                    settings.notificationAfterDelete,
                    viewModel::setNotificationAfterDelete,
                    stringResource(R.string.notification_settings_after_deleting),
                )
                SwitchPreference(
                    settings.notificationBeforeDownload,
                    viewModel::setNotificationBeforeDownload,
                    stringResource(R.string.notification_settings_before_downloading),
                )
                SwitchPreference(
                    settings.notificationAfterDownload,
                    viewModel::setNotificationAfterDownload,
                    stringResource(R.string.notification_settings_after_downloading),
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.notification_settings_processing)) }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = settings.notificationAutoSend,
                    onCheckedChange = viewModel::setNotificationAutoSend,
                    title = stringResource(R.string.notification_settings_auto_send),
                    summary = stringResource(R.string.notification_settings_auto_send_summary),
                )
                SwitchPreference(
                    checked = settings.notificationAutoRemove,
                    onCheckedChange = viewModel::setNotificationAutoRemove,
                    title = stringResource(R.string.notification_settings_auto_remove),
                    summary = stringResource(R.string.notification_settings_auto_remove_summary),
                    enabled = settings.notificationAutoSend,
                )
            }
        }
    }
}

@Composable
fun PrivacySettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
) {
    val redactionModes = RedactionMode.entries
    val profileNameRedactionModes = ProfileNameRedactionMode.entries
    var showClearCloudCachesConfirmation by remember { mutableStateOf(false) }
    DetailLazyScaffold(title = stringResource(R.string.privacy_title), onBack = onBack) { _ ->
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.privacy_sensitive_identifiers)) }
        item {
            GroupedCard {
                OverlayDropdownPreference(
                    items = redactionModes.map { stringResource(it.labelResource()) },
                    selectedIndex = redactionModes.indexOf(settings.eidRedaction),
                    title = stringResource(R.string.privacy_eid_redaction),
                    summary = stringResource(R.string.privacy_eid_redaction_summary),
                    onSelectedIndexChange = { viewModel.setEidRedaction(redactionModes[it]) },
                )
                OverlayDropdownPreference(
                    items = redactionModes.map { stringResource(it.labelResource()) },
                    selectedIndex = redactionModes.indexOf(settings.iccidRedaction),
                    title = stringResource(R.string.privacy_iccid_redaction),
                    summary = stringResource(R.string.privacy_iccid_redaction_summary),
                    onSelectedIndexChange = { viewModel.setIccidRedaction(redactionModes[it]) },
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.privacy_profile_names)) }
        item {
            GroupedCard {
                OverlayDropdownPreference(
                    items = profileNameRedactionModes.map { stringResource(it.labelResource()) },
                    selectedIndex = profileNameRedactionModes.indexOf(settings.profileNameRedaction),
                    title = stringResource(R.string.privacy_profile_name_redaction),
                    summary = stringResource(R.string.privacy_profile_name_redaction_summary),
                    onSelectedIndexChange = {
                        viewModel.setProfileNameRedaction(profileNameRedactionModes[it])
                    },
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.privacy_nekoko_cloud)) }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = settings.loadOperatorIcons,
                    onCheckedChange = viewModel::setLoadOperatorIcons,
                    title = stringResource(R.string.privacy_operator_icons),
                    summary = stringResource(R.string.privacy_operator_icons_summary),
                )
                SwitchPreference(
                    checked = settings.estimateProfileSize,
                    onCheckedChange = viewModel::setEstimateProfileSize,
                    title = stringResource(R.string.privacy_profile_size),
                    summary = stringResource(R.string.privacy_profile_size_summary) + "\n" + stringResource(R.string.privacy_cloud_data_use_summary),
                )
            }
        }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.privacy_clear_cloud_caches_title),
                    summary = stringResource(R.string.privacy_clear_cloud_caches_summary),
                    titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                    onClick = { showClearCloudCachesConfirmation = true },
                )
            }
        }
    }

    OverlayDialog(
        show = showClearCloudCachesConfirmation,
        title = stringResource(R.string.privacy_clear_cloud_caches_dialog_title),
        summary = stringResource(R.string.privacy_clear_cloud_caches_dialog_summary),
        onDismissRequest = { showClearCloudCachesConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showClearCloudCachesConfirmation = false },
            confirmText = stringResource(R.string.privacy_clear_cloud_caches),
            destructive = true,
            onConfirm = {
                showClearCloudCachesConfirmation = false
                viewModel.clearCloudCaches()
            },
        )
    }
}

@Composable
fun AdvancedSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
) {
    var showMssEditor by remember { mutableStateOf(false) }
    var showImeiEditor by remember { mutableStateOf(false) }
    val mssOutOfRange = stringResource(R.string.advanced_mss_out_of_range)

    DetailLazyScaffold(title = stringResource(R.string.advanced_title), onBack = onBack) { _ ->
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.advanced_lpa_protocol)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.advanced_mss),
                    summary = stringResource(R.string.advanced_mss_bytes, settings.es10xMss),
                    onClick = { showMssEditor = true },
                )
                ArrowPreference(
                    title = "IMEI",
                    summary = settings.imei.ifBlank { stringResource(R.string.advanced_imei_not_supplied) },
                    onClick = { showImeiEditor = true },
                )
                ArrowPreference(
                    title = stringResource(R.string.advanced_isdr_identifiers),
                    summary = pluralStringResource(
                        R.plurals.advanced_isdr_candidates,
                        settings.isdrAids.size,
                        settings.isdrAids.size,
                    ),
                    onClick = { viewModel.navigate(AppRoute.AidManager) },
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.advanced_diagnostics)) }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = settings.developerMode,
                    onCheckedChange = viewModel::setDeveloperMode,
                    title = stringResource(R.string.advanced_developer_mode),
                    summary = stringResource(R.string.advanced_developer_mode_summary),
                )
                SwitchPreference(
                    checked = settings.apduLogging,
                    onCheckedChange = viewModel::setApduLogging,
                    title = stringResource(R.string.advanced_apdu_logging),
                    summary = stringResource(R.string.advanced_apdu_logging_summary),
                    enabled = settings.developerMode,
                )
                SwitchPreference(
                    checked = settings.hideProfileDeletion,
                    onCheckedChange = viewModel::setHideProfileDeletion,
                    title = stringResource(R.string.advanced_hide_deletion),
                    summary = stringResource(R.string.advanced_hide_deletion_summary),
                )
                SwitchPreference(
                    checked = settings.hideEuiccMemoryReset,
                    onCheckedChange = viewModel::setHideEuiccMemoryReset,
                    title = stringResource(R.string.advanced_hide_memory_reset),
                    summary = stringResource(R.string.advanced_hide_memory_reset_summary),
                )
            }
        }
    }

    TextInputDialog(
        show = showMssEditor,
        title = stringResource(R.string.advanced_mss_dialog_title),
        summary = stringResource(R.string.advanced_mss_dialog_summary),
        initialValue = settings.es10xMss.toString(),
        maxLength = 3,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        inputFilter = { it.filter(Char::isDigit) },
        validate = { value -> mssOutOfRange.takeUnless { value.toIntOrNull() in MinEs10xMss..MaxEs10xMss } },
        onDismiss = { showMssEditor = false },
        onConfirm = { value ->
            value.toIntOrNull()?.let(viewModel::setEs10xMss)
            showMssEditor = false
        },
    )
    TextInputDialog(
        show = showImeiEditor,
        title = "IMEI",
        summary = stringResource(R.string.advanced_imei_dialog_summary),
        initialValue = settings.imei,
        maxLength = 16,
        allowBlank = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        inputFilter = { it.filter(Char::isDigit) },
        onDismiss = { showImeiEditor = false },
        onConfirm = { value ->
            viewModel.setImei(value)
            showImeiEditor = false
        },
    )
}

private const val MinEs10xMss = 32
private const val MaxEs10xMss = 255

@Composable
fun BackupRestoreSettingsScreen(onBack: () -> Unit, viewModel: HyperLpaViewModel) {
    val snackbar = LocalMiuixSnackbar.current
    val created = stringResource(R.string.backup_created)
    val createFailed = stringResource(R.string.backup_create_failed)
    val restored = stringResource(R.string.backup_restored)
    val restoreFailed = stringResource(R.string.backup_restore_failed)
    val reset = stringResource(R.string.backup_reset_complete)
    val resetFailed = stringResource(R.string.backup_reset_failed)
    val pickerUnavailable = stringResource(R.string.common_file_picker_unavailable)
    val createLabel = stringResource(R.string.backup_create)
    val restoreLabel = stringResource(R.string.backup_restore)
    val resetLabel = stringResource(R.string.backup_reset_settings)
    val backendBusy by viewModel.backupOperationInProgress.collectAsStateWithLifecycle()
    val operationKind by viewModel.backupOperationKind.collectAsStateWithLifecycle()
    val progress = when (operationKind) {
        app.hyperlpa.ui.BackupOperationKind.CREATE -> createLabel
        app.hyperlpa.ui.BackupOperationKind.RESTORE -> restoreLabel
        app.hyperlpa.ui.BackupOperationKind.RESET -> resetLabel
        null -> null
    }
    var progressLocked by remember { mutableStateOf(false) }
    val busy = backendBusy || progressLocked
    var showCreate by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var createAfterExit by remember { mutableStateOf(false) }
    var restoreAfterExit by remember { mutableStateOf(false) }
    var resetAfterExit by remember { mutableStateOf(false) }
    var retryRestoreAfterProgress by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf(false) }
    val createPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) viewModel.cancelPreparedBackup()
        else {
            viewModel.createPreparedBackup(uri) { success ->
                snackbar(if (success) created else createFailed, SnackbarDuration.Short)
            }
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; restoreError = false; showRestore = true }
    }
    DetailLazyScaffold(title = stringResource(R.string.backup_title), onBack = onBack) { _ ->
        item {
            GroupedCard {
                ArrowPreference(title = createLabel,
                    summary = stringResource(R.string.backup_create_summary) + "\n" + stringResource(R.string.backup_esim_profiles_summary),
                    enabled = !busy, onClick = { showCreate = true })
                ArrowPreference(title = restoreLabel, summary = stringResource(R.string.backup_restore_summary),
                    enabled = !busy, onClick = {
                        runCatching { restorePicker.launch(arrayOf("application/json", "text/plain")) }
                            .onFailure { snackbar(pickerUnavailable, SnackbarDuration.Long) }
                    })
            }
        }
        item {
            GroupedCard {
                ArrowPreference(title = resetLabel, summary = stringResource(R.string.backup_reset_settings_summary),
                    enabled = !busy, titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                    onClick = { showReset = true })
            }
        }
    }
    OverlayDialog(show = showCreate, title = stringResource(R.string.backup_protect_title),
        summary = stringResource(R.string.backup_protect_summary) + "\n" + stringResource(R.string.backup_keep_secure_summary),
        onDismissRequest = { showCreate = false },
        onDismissFinished = {
            backupPassword = ""
            confirmation = ""
            if (createAfterExit) {
                createAfterExit = false
                runCatching { createPicker.launch("hyperlpa-backup-${LocalDate.now()}.json") }
                    .onFailure { viewModel.cancelPreparedBackup(); snackbar(pickerUnavailable, SnackbarDuration.Long) }
            }
        },
    ) {
        val mismatch = confirmation.isNotEmpty() && (!backupPassword.startsWith(confirmation) ||
            confirmation.length >= backupPassword.length) && backupPassword != confirmation
        TextField(value = backupPassword, onValueChange = { backupPassword = it.take(128) },
            label = stringResource(R.string.backup_password), singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        TextField(value = confirmation, onValueChange = { confirmation = it.take(128) },
            label = stringResource(R.string.backup_confirm_password), singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth())
        if (mismatch) FieldError(stringResource(R.string.backup_passwords_differ))
        Spacer(Modifier.height(12.dp))
        DialogActionRow(onCancel = { showCreate = false }, confirmText = stringResource(R.string.common_save),
            confirmEnabled = (backupPassword.isEmpty() && confirmation.isEmpty()) ||
                (backupPassword.length >= 10 && backupPassword == confirmation),
            onConfirm = {
                if (viewModel.prepareBackup(backupPassword)) { createAfterExit = true; showCreate = false }
                else snackbar(createFailed, SnackbarDuration.Short)
            })
    }
    OverlayDialog(show = showRestore, title = stringResource(R.string.backup_restore_dialog_title),
        summary = stringResource(R.string.backup_restore_dialog_summary),
        onDismissRequest = { showRestore = false },
        onDismissFinished = {
            if (restoreAfterExit) {
                restoreAfterExit = false
                restoreUri?.let { uri ->
                    viewModel.restoreBackup(uri, restorePassword) { success ->
                        if (success) { restoreUri = null; restorePassword = "" }
                        else { restoreError = true; retryRestoreAfterProgress = true }
                        snackbar(if (success) restored else restoreFailed, SnackbarDuration.Short)
                    }
                }
            } else if (!retryRestoreAfterProgress && progress == null) {
                restoreUri = null
                restorePassword = ""
                restoreError = false
            }
        },
    ) {
        TextField(value = restorePassword, onValueChange = { restorePassword = it.take(128); restoreError = false },
            label = stringResource(R.string.backup_password), useLabelAsPlaceholder = true, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth())
        if (restoreError) FieldError(restoreFailed)
        Spacer(Modifier.height(12.dp))
        DialogActionRow(onCancel = { showRestore = false }, confirmText = stringResource(R.string.common_restore),
            onConfirm = { restoreAfterExit = true; showRestore = false })
    }
    OverlayDialog(show = showReset, title = stringResource(R.string.backup_reset_dialog_title),
        summary = stringResource(R.string.backup_reset_dialog_summary), onDismissRequest = { showReset = false },
        onDismissFinished = {
            if (resetAfterExit) {
                resetAfterExit = false
                viewModel.resetSettings { success ->
                    snackbar(if (success) reset else resetFailed, SnackbarDuration.Short)
                }
            }
        }) {
        DialogActionRow(onCancel = { showReset = false }, confirmText = stringResource(R.string.common_reset),
            destructive = true, onConfirm = { resetAfterExit = true; showReset = false })
    }
    app.hyperlpa.ui.components.CompactProgressDialog(progress) { locked ->
        progressLocked = locked
        viewModel.updateOperationPopupLock(locked, owner = "backup")
        if (!locked && retryRestoreAfterProgress) { retryRestoreAfterProgress = false; showRestore = true }
    }
}

@Composable
fun AidManagerScreen(
    aids: List<String>,
    onBack: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var text by remember(aids) { mutableStateOf(aids.joinToString("\n")) }
    var inputTooLong by remember { mutableStateOf(false) }
    val entries = remember(text) {
        text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(MaximumAidCandidates + 1)
            .toList()
    }
    val validation = remember(entries) { runCatching { validateIsdrAids(entries) } }
    val parsed = validation.getOrNull().orEmpty()
    val validationError = validation.exceptionOrNull() as? IsdrAidValidationException
    val validationMessage = when {
        inputTooLong -> stringResource(R.string.aid_manager_input_too_long)
        validationError?.reason == IsdrAidValidationError.EMPTY -> stringResource(R.string.aid_manager_empty)
        validationError?.reason == IsdrAidValidationError.TOO_MANY -> stringResource(
            R.string.aid_manager_too_many,
            MaximumAidCandidates,
        )
        validationError?.reason == IsdrAidValidationError.INVALID_AID -> stringResource(
            R.string.aid_manager_invalid_line,
            validationError.lineNumber ?: 1,
        )
        validationError?.reason == IsdrAidValidationError.DUPLICATE_AID -> stringResource(
            R.string.aid_manager_duplicate_line,
            validationError.lineNumber ?: 1,
        )
        else -> null
    }

    DetailLazyScaffold(
        title = stringResource(R.string.aid_manager_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { text = DefaultIsdrAids.joinToString("\n") }) {
                Icon(
                    imageVector = MiuixIcons.Reset,
                    contentDescription = stringResource(R.string.common_restore_defaults),
                )
            }
        },
    ) { _ ->
        item {
            TextField(
                value = text,
                onValueChange = { value ->
                    inputTooLong = value.length > MaximumAidEditorCharacters
                    if (!inputTooLong) text = value
                },
                label = stringResource(R.string.aid_manager_input_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                useLabelAsPlaceholder = true,
                minLines = 7,
                maxLines = 12,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = if (validationMessage == null) 12.dp else 0.dp),
            )
        }
        validationMessage?.let { message -> item { FieldError(message, Modifier.padding(horizontal = 28.dp)) } }
        item {
            PrimaryPageButton(
                text = stringResource(R.string.common_save),
                onClick = {
                    onSave(parsed)
                    onBack()
                },
                enabled = validation.isSuccess && !inputTooLong,
                modifier = Modifier.padding(top = if (validationMessage != null) 12.dp else 0.dp),
            )
        }
    }
}

@Composable
private fun TextEditorDialog(
    show: Boolean,
    title: String,
    summary: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    confirmEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onDismissFinished: () -> Unit = {},
) {
    OverlayDialog(
        show = show,
        onDismissFinished = onDismissFinished,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
        LaunchedEffect(Unit) { focusRequester.requestFocus(); keyboard?.show() }
        Column {
            TextField(value = field, onValueChange = { field = it; onValueChange(it.text) },
                label = stringResource(R.string.reader_addresses_label), useLabelAsPlaceholder = true,
                minLines = 4, maxLines = 8,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester))
            if (error != null) FieldError(error)
            Spacer(Modifier.height(12.dp))
            DialogActionRow(
                onCancel = onDismiss,
                confirmText = stringResource(R.string.common_save),
                confirmEnabled = confirmEnabled,
                onConfirm = onConfirm,
            )
        }
    }
}

private fun ThemeMode.labelResource(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.appearance_theme_system
    ThemeMode.LIGHT -> R.string.appearance_theme_light
    ThemeMode.DARK -> R.string.appearance_theme_dark
}

private fun ThemePalette.labelResource(): Int = when (this) {
    ThemePalette.TONAL_SPOT -> R.string.appearance_palette_tonal_spot
    ThemePalette.NEUTRAL -> R.string.appearance_palette_neutral
    ThemePalette.VIBRANT -> R.string.appearance_palette_vibrant
    ThemePalette.EXPRESSIVE -> R.string.appearance_palette_expressive
    ThemePalette.RAINBOW -> R.string.appearance_palette_rainbow
    ThemePalette.FRUIT_SALAD -> R.string.appearance_palette_fruit_salad
    ThemePalette.MONOCHROME -> R.string.appearance_palette_monochrome
    ThemePalette.FIDELITY -> R.string.appearance_palette_fidelity
    ThemePalette.CONTENT -> R.string.appearance_palette_content
}

private fun ThemeAccent.labelResource(): Int = when (this) {
    ThemeAccent.SYSTEM -> R.string.appearance_accent_system
    ThemeAccent.BLUE -> R.string.appearance_accent_blue
    ThemeAccent.PURPLE -> R.string.appearance_accent_purple
    ThemeAccent.PINK -> R.string.appearance_accent_pink
    ThemeAccent.RED -> R.string.appearance_accent_red
    ThemeAccent.ORANGE -> R.string.appearance_accent_orange
    ThemeAccent.YELLOW -> R.string.appearance_accent_yellow
    ThemeAccent.GREEN -> R.string.appearance_accent_green
    ThemeAccent.TEAL -> R.string.appearance_accent_teal
}

private fun FloatingBottomBarStyle.labelResource(): Int = when (this) {
    FloatingBottomBarStyle.MIUIX -> R.string.appearance_bar_style_miuix
    FloatingBottomBarStyle.IOS_LIKE -> R.string.appearance_bar_style_ios
}

private fun NavigationLabels.labelResource(): Int = when (this) {
    NavigationLabels.ICON_AND_TEXT -> R.string.appearance_bar_icons_text
    NavigationLabels.ICON_ONLY -> R.string.appearance_bar_icons_only
}

private fun ProfileLayout.labelResource(): Int = when (this) {
    ProfileLayout.LIST -> R.string.profile_layout_list
    ProfileLayout.WATERFALL -> R.string.profile_layout_waterfall
}

private fun ProfileSort.labelResource(): Int = when (this) {
    ProfileSort.SLOT_ORDER -> R.string.profile_sort_slot_order
    ProfileSort.NAME -> R.string.profile_sort_name
    ProfileSort.PROVIDER -> R.string.profile_sort_provider
    ProfileSort.ICCID -> R.string.profile_sort_iccid
    ProfileSort.STATE -> R.string.profile_sort_state
}

private fun PhoneFormatStrategy.labelResource(): Int = when (this) {
    PhoneFormatStrategy.INTERNATIONAL_ONLY -> R.string.phone_format_e164_only
    PhoneFormatStrategy.INTERNATIONAL_AND_MOBILE -> R.string.phone_format_international_mobile
    PhoneFormatStrategy.INTERNATIONAL_AND_ALL -> R.string.phone_format_international_all
    PhoneFormatStrategy.OFF -> R.string.phone_format_off
}

private fun ProfileNameRedactionMode.labelResource(): Int = when (this) {
    ProfileNameRedactionMode.NONE -> R.string.privacy_profile_name_redaction_none
    ProfileNameRedactionMode.PROVIDER_ONLY -> R.string.privacy_profile_name_redaction_provider_only
    ProfileNameRedactionMode.NUMBERS -> R.string.privacy_profile_name_redaction_numbers
}

private fun RedactionMode.labelResource(): Int = when (this) {
    RedactionMode.NONE -> R.string.redaction_none
    RedactionMode.MIDDLE -> R.string.redaction_middle
    RedactionMode.FULL -> R.string.redaction_full
}

private fun ReaderKind.labelResource(): Int = when (this) {
    ReaderKind.NBRIDGE -> R.string.reader_kind_nbridge
    ReaderKind.OMAPI -> R.string.reader_kind_omapi
    ReaderKind.TELEPHONY -> R.string.reader_kind_telephony
    ReaderKind.USB_CCID -> R.string.reader_kind_usb
    ReaderKind.BLE -> R.string.reader_kind_bluetooth
    ReaderKind.REMOTE -> R.string.reader_kind_remote
}
