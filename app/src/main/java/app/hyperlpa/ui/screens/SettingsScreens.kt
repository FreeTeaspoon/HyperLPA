package app.hyperlpa.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import app.hyperlpa.ui.adaptive.AdaptiveTopAppBar
import app.hyperlpa.ui.adaptive.CenteredContent
import app.hyperlpa.ui.adaptive.horizontalCutoutPadding
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.BlurredBar
import app.hyperlpa.ui.components.TipCard
import app.hyperlpa.ui.components.rememberAppBackdrop
import app.hyperlpa.ui.components.redactIdentifier
import app.hyperlpa.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt
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
    val blurSupported = isRuntimeShaderSupported()
    val backdrop = rememberAppBackdrop()
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val themeModeLabels = ThemeMode.entries.map { stringResource(it.labelResource()) }
    val paletteLabels = ThemePalette.entries.map { stringResource(it.labelResource()) }
    val accentLabels = ThemeAccent.entries.map { stringResource(it.labelResource()) }
    val floatingBarLabels = FloatingBottomBarStyle.entries.map { stringResource(it.labelResource()) }
    val navigationLabelOptions = NavigationLabels.entries.map { stringResource(it.labelResource()) }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(
                backdrop = backdrop,
                progressive = true,
                scrollBehavior = scrollBehavior,
            ) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_appearance_theme),
                    color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { AppearanceBackButton(onBack) },
                )
            }
        },
    ) { innerPadding ->
        CenteredContent(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            maxWidth = null,
        ) { sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    end = sidePadding,
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item { SectionHeading(stringResource(R.string.appearance_color_theme)) }
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

                item { SectionHeading(stringResource(R.string.appearance_interface_effects)) }
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

                item { SectionHeading(stringResource(R.string.appearance_bottom_navigation)) }
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
        }
    }

    WindowDialog(
        show = showDensityDialog,
        title = stringResource(R.string.appearance_interface_scale),
        summary = stringResource(R.string.appearance_interface_scale_summary),
        onDismissRequest = { showDensityDialog = false },
    ) {
        TextField(
            state = densityTextState,
            inputTransformation = DensityDigitsOnlyTransformation.maxLength(3),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            onKeyboardAction = { focusManager.clearFocus() },
            trailingIcon = {
                Text(
                    text = "%",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = { showDensityDialog = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.common_confirm),
                onClick = {
                    val percent = densityTextState.text.toString().toIntOrNull()
                        ?.coerceIn(
                            (MIN_INTERFACE_SCALE * 100f).roundToInt(),
                            (MAX_INTERFACE_SCALE * 100f).roundToInt(),
                        )
                        ?: densityDraft.roundToInt()
                    densityDraft = percent.toFloat()
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    viewModel.setDensityScale(percent / 100f)
                    showDensityDialog = false
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
            )
        }
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
        item { SectionHeading(stringResource(R.string.profile_display_layout_section)) }
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
        item { SectionHeading(stringResource(R.string.profile_display_page_section)) }
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
        item { SectionHeading(stringResource(R.string.profile_display_home_cards)) }
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
        item { SectionHeading(stringResource(R.string.profile_display_home_reader)) }
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
    onDiscoverReaders: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val context = LocalContext.current
    val showSnackbar = LocalMiuixSnackbar.current
    var showRemoteEditor by remember { mutableStateOf(false) }
    var remoteUrls by remember(state.settings.remoteReaderUrls) {
        mutableStateOf(state.settings.remoteReaderUrls.joinToString("\n"))
    }
    var remoteInputTooLong by remember { mutableStateOf(false) }
    var remoteTokenEndpoint by remember { mutableStateOf<String?>(null) }
    var remoteTokenDraft by remember { mutableStateOf("") }
    var remoteTokenSaving by remember { mutableStateOf(false) }
    var remoteUrlsSaving by remember { mutableStateOf(false) }
    val diagnosticsClipboardLabel = stringResource(R.string.reader_diagnostics_clipboard_label)
    val diagnosticsCopiedMessage = stringResource(R.string.reader_diagnostics_copied)
    val remoteSaveFailedMessage = stringResource(R.string.reader_remote_save_failed)
    val bluetoothAvailability = bluetoothReaderState.availability
    val usesNearbyDevicesPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val discoverSummary = when (bluetoothAvailability) {
        BluetoothReaderAvailability.PERMISSION_REQUIRED -> stringResource(
            if (usesNearbyDevicesPermission) {
                R.string.reader_discover_permission_required_summary
            } else {
                R.string.reader_discover_location_required_summary
            },
        )
        BluetoothReaderAvailability.BLUETOOTH_OFF -> stringResource(
            R.string.reader_discover_bluetooth_off_summary,
        )
        else -> stringResource(R.string.reader_discover_now_summary)
    }
    // The Activity refreshes every backend and handles any BLE remediation separately.
    val discoverAction = onDiscoverReaders
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
    DetailLazyScaffold(title = stringResource(R.string.reader_settings_title), onBack = onBack) { _ ->
        item { SectionHeading(stringResource(R.string.reader_settings_behaviour)) }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = state.settings.autoLoadProfiles,
                    onCheckedChange = viewModel::setAutoLoadProfiles,
                    title = stringResource(R.string.reader_connect_automatically),
                    summary = stringResource(R.string.reader_connect_automatically_summary),
                )
                ArrowPreference(
                    title = stringResource(R.string.reader_discover_now),
                    summary = discoverSummary,
                    onClick = discoverAction,
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

        item { SectionHeading(stringResource(R.string.reader_backends_section)) }
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
                    ArrowPreference(
                        title = stringResource(R.string.reader_telephony_title),
                        summary = stringResource(R.string.reader_telephony_unavailable_summary),
                        enabled = false,
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
                SwitchPreference(
                    checked = state.settings.enableRemote,
                    onCheckedChange = viewModel::setEnableRemote,
                    title = stringResource(R.string.reader_remote_title),
                    summary = stringResource(R.string.reader_remote_summary),
                )
                ArrowPreference(
                    title = stringResource(R.string.reader_remote_addresses),
                    summary = remoteReaderSummary(state.settings.remoteReaderUrls),
                    enabled = state.settings.enableRemote,
                    onClick = {
                        remoteInputTooLong = false
                        showRemoteEditor = true
                    },
                )
                if (state.settings.enableRemote) {
                    state.settings.remoteReaderUrls.forEach { endpoint ->
                        ArrowPreference(
                            title = endpoint.toUri().host ?: endpoint,
                            summary = if (state.settings.remoteReaderTokens.containsKey(endpoint)) {
                                stringResource(R.string.reader_remote_credential_stored)
                            } else {
                                stringResource(R.string.reader_remote_credential_none)
                            },
                            onClick = {
                                remoteTokenDraft = ""
                                remoteTokenEndpoint = endpoint
                            },
                        )
                    }
                }
            }
        }

        item { SectionHeading(stringResource(R.string.reader_diagnostics_section)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = "HyperLPA",
                    summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    enabled = false,
                )
                ArrowPreference(
                    title = "Android",
                    summary = stringResource(
                        R.string.reader_diagnostics_android_version,
                        Build.VERSION.RELEASE,
                        Build.VERSION.SDK_INT,
                    ),
                    enabled = false,
                )
                ArrowPreference(
                    title = stringResource(R.string.reader_omapi_title),
                    summary = if (context.packageManager.hasSystemFeature("android.hardware.se.omapi")) {
                        stringResource(R.string.reader_omapi_advertised)
                    } else {
                        stringResource(R.string.reader_omapi_not_advertised)
                    },
                    enabled = false,
                )
                ArrowPreference(
                    title = stringResource(R.string.reader_bluetooth_permission),
                    summary = stringResource(
                        if (bluetoothReaderState.permissionGranted) {
                            R.string.common_granted
                        } else {
                            R.string.common_not_granted
                        },
                    ),
                    enabled = state.settings.enableBle &&
                        bluetoothReaderState.supported &&
                        !bluetoothReaderState.permissionGranted,
                    onClick = onRequestBluetoothPermission,
                )
                ArrowPreference(
                    title = stringResource(R.string.reader_bluetooth_adapter),
                    summary = when {
                        !bluetoothReaderState.supported -> stringResource(R.string.reader_bluetooth_unsupported)
                        !bluetoothReaderState.permissionGranted -> stringResource(
                            R.string.reader_bluetooth_adapter_permission_required,
                        )
                        bluetoothReaderState.adapterEnabled -> stringResource(R.string.reader_bluetooth_adapter_on)
                        else -> stringResource(R.string.reader_bluetooth_adapter_off)
                    },
                    enabled = bluetoothReaderState.supported && bluetoothReaderState.permissionGranted,
                    onClick = onOpenBluetoothSettings,
                )
                ArrowPreference(
                    title = stringResource(R.string.reader_telephony_permission),
                    summary = privilegedTelephonyStatus(context),
                    enabled = false,
                )
                ArrowPreference(
                    title = stringResource(R.string.reader_copy_diagnostics),
                    summary = stringResource(R.string.reader_copy_diagnostics_summary),
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

        item { SectionHeading(stringResource(R.string.reader_available_now)) }
        if (state.lpa.readers.isEmpty()) {
            item {
                GroupedCard {
                    ArrowPreference(
                        title = when (bluetoothAvailability) {
                            BluetoothReaderAvailability.PERMISSION_REQUIRED -> stringResource(
                                R.string.reader_bluetooth_permission_required_title,
                            )
                            BluetoothReaderAvailability.BLUETOOTH_OFF -> stringResource(
                                R.string.reader_bluetooth_off_title,
                            )
                            else -> stringResource(R.string.reader_no_readers_found)
                        },
                        summary = when (bluetoothAvailability) {
                            BluetoothReaderAvailability.PERMISSION_REQUIRED -> stringResource(
                                if (usesNearbyDevicesPermission) {
                                    R.string.reader_bluetooth_permission_required_message
                                } else {
                                    R.string.reader_bluetooth_location_required_message
                                },
                            )
                            BluetoothReaderAvailability.BLUETOOTH_OFF -> stringResource(
                                R.string.reader_bluetooth_off_message,
                            )
                            else -> stringResource(R.string.reader_no_readers_found_summary)
                        },
                        enabled = bluetoothAvailability == BluetoothReaderAvailability.PERMISSION_REQUIRED ||
                            bluetoothAvailability == BluetoothReaderAvailability.BLUETOOTH_OFF,
                        onClick = discoverAction,
                    )
                }
            }
        } else {
            item {
                GroupedCard {
                    state.lpa.readers.forEach { reader ->
                        ArrowPreference(
                            title = reader.name,
                            summary = listOfNotNull(
                                stringResource(reader.kind.labelResource()),
                                reader.detail,
                            ).joinToString(" · "),
                            endActions = {
                                if (reader.id == state.lpa.selectedReaderId) {
                                    Text(
                                        text = stringResource(R.string.common_connected),
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            },
                            onClick = { viewModel.connectReader(reader.id) },
                        )
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
        },
        error = remoteEditorError,
        confirmEnabled = remoteEditorError == null && !remoteUrlsSaving,
        onDismiss = {
            if (!remoteUrlsSaving) {
                remoteInputTooLong = false
                showRemoteEditor = false
            }
        },
        onConfirm = {
            if (!remoteUrlsSaving && remoteEditorError == null) {
                remoteUrlsSaving = true
                viewModel.setRemoteReaderUrls(
                    remoteUrlEntries,
                ) { success ->
                    remoteUrlsSaving = false
                    if (success) {
                        remoteInputTooLong = false
                        showRemoteEditor = false
                    } else {
                        showSnackbar(remoteSaveFailedMessage, SnackbarDuration.Short)
                    }
                }
            }
        },
    )
    OverlayDialog(
        show = remoteTokenEndpoint != null,
        title = stringResource(R.string.reader_remote_credential_title),
        summary = remoteTokenEndpoint?.let { endpoint ->
            if (state.settings.remoteReaderTokens.containsKey(endpoint)) {
                stringResource(R.string.reader_remote_credential_replace_summary)
            } else {
                stringResource(R.string.reader_remote_credential_summary)
            }
        },
        onDismissRequest = { if (!remoteTokenSaving) remoteTokenEndpoint = null },
    ) {
        val tokenValid = remoteTokenDraft.isEmpty() || isValidRemoteReaderToken(remoteTokenDraft)
        TextField(
            value = remoteTokenDraft,
            onValueChange = { value ->
                remoteTokenDraft = value.filterNot { it == '\r' || it == '\n' }.take(4_096)
            },
            label = stringResource(R.string.reader_remote_credential_label),
            useLabelAsPlaceholder = true,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!tokenValid) {
            Text(
                text = stringResource(R.string.reader_remote_credential_invalid),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        DialogButtons(
            onDismiss = { if (!remoteTokenSaving) remoteTokenEndpoint = null },
            confirmEnabled = tokenValid && !remoteTokenSaving,
            onConfirm = {
                remoteTokenEndpoint?.let { endpoint ->
                    remoteTokenSaving = true
                    viewModel.setRemoteReaderToken(endpoint, remoteTokenDraft) { success ->
                        remoteTokenSaving = false
                        if (success) {
                            remoteTokenDraft = ""
                            remoteTokenEndpoint = null
                        } else {
                            showSnackbar(remoteSaveFailedMessage, SnackbarDuration.Short)
                        }
                    }
                }
            },
        )
    }
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
        ?.joinToString(prefix = "$count · ", limit = 2, truncated = "…")
        ?: count
}

@Composable
fun NotificationSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
) {
    DetailLazyScaffold(title = stringResource(R.string.notification_settings_title), onBack = onBack) { _ ->
        item { SectionHeading(stringResource(R.string.notification_settings_check_section)) }
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
        item { SectionHeading(stringResource(R.string.notification_settings_processing)) }
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
        item { SectionHeading(stringResource(R.string.privacy_sensitive_identifiers)) }
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
        item { SectionHeading(stringResource(R.string.privacy_profile_names)) }
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
        item { SectionHeading(stringResource(R.string.privacy_nekoko_cloud)) }
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
                    summary = stringResource(R.string.privacy_profile_size_summary),
                )
                ArrowPreference(
                    title = stringResource(R.string.privacy_clear_cloud_caches),
                    summary = stringResource(R.string.privacy_clear_cloud_caches_summary),
                    onClick = { showClearCloudCachesConfirmation = true },
                )
            }
        }
        item {
            TipCard(text = stringResource(R.string.privacy_cloud_data_use_summary))
        }
    }

    OverlayDialog(
        show = showClearCloudCachesConfirmation,
        title = stringResource(R.string.privacy_clear_cloud_caches_dialog_title),
        summary = stringResource(R.string.privacy_clear_cloud_caches_dialog_summary),
        onDismissRequest = { showClearCloudCachesConfirmation = false },
    ) {
        SettingsConfirmationActions(
            confirmText = stringResource(R.string.privacy_clear_cloud_caches),
            destructive = true,
            onCancel = { showClearCloudCachesConfirmation = false },
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
    var mssText by remember(settings.es10xMss) { mutableStateOf(settings.es10xMss.toString()) }
    var showImeiEditor by remember { mutableStateOf(false) }
    var imeiText by remember(settings.imei) { mutableStateOf(settings.imei) }

    DetailLazyScaffold(title = stringResource(R.string.advanced_title), onBack = onBack) { _ ->
        item { SectionHeading(stringResource(R.string.advanced_lpa_protocol)) }
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
        item { SectionHeading(stringResource(R.string.advanced_diagnostics)) }
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

    NumberEditorDialog(
        show = showMssEditor,
        title = stringResource(R.string.advanced_mss_dialog_title),
        summary = stringResource(R.string.advanced_mss_dialog_summary),
        value = mssText,
        onValueChange = { mssText = it.filter(Char::isDigit).take(3) },
        onDismiss = { showMssEditor = false },
        onConfirm = {
            mssText.toIntOrNull()?.let(viewModel::setEs10xMss)
            showMssEditor = false
        },
    )
    NumberEditorDialog(
        show = showImeiEditor,
        title = "IMEI",
        summary = stringResource(R.string.advanced_imei_dialog_summary),
        value = imeiText,
        onValueChange = { imeiText = it.filter(Char::isDigit).take(16) },
        onDismiss = { showImeiEditor = false },
        onConfirm = {
            viewModel.setImei(imeiText)
            showImeiEditor = false
        },
    )
}

@Composable
fun BackupRestoreSettingsScreen(
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
) {
    val showSnackbar = LocalMiuixSnackbar.current
    val backupCreated = stringResource(R.string.backup_created)
    val backupCreateFailed = stringResource(R.string.backup_create_failed)
    val backupRestored = stringResource(R.string.backup_restored)
    val backupRestoreFailed = stringResource(R.string.backup_restore_failed)
    val backupResetComplete = stringResource(R.string.backup_reset_complete)
    val backupResetFailed = stringResource(R.string.backup_reset_failed)
    val busy by viewModel.backupOperationInProgress.collectAsStateWithLifecycle()
    var showCreatePassword by rememberSaveable { mutableStateOf(false) }
    var pendingRestoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var backupPasswordConfirmation by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            viewModel.cancelPreparedBackup()
        } else {
            viewModel.createPreparedBackup(uri) { success ->
                showSnackbar(
                    if (success) backupCreated else backupCreateFailed,
                    SnackbarDuration.Short,
                )
            }
        }
    }
    val restoreBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        pendingRestoreUri = uri?.toString()
    }

    DetailLazyScaffold(title = stringResource(R.string.backup_title), onBack = onBack) { _ ->
        item { SectionHeading(stringResource(R.string.backup_section)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.backup_create),
                    summary = stringResource(R.string.backup_create_summary),
                    enabled = !busy,
                    onClick = { showCreatePassword = true },
                )
            }
        }
        item { SectionHeading(stringResource(R.string.backup_restore_section)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.backup_restore),
                    summary = stringResource(R.string.backup_restore_summary),
                    enabled = !busy,
                    onClick = {
                        restoreBackupLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.backup_reset_settings),
                    summary = stringResource(R.string.backup_reset_settings_summary),
                    enabled = !busy,
                    titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.error,
                    ),
                    onClick = { showResetConfirmation = true },
                )
            }
        }
        item { SectionHeading(stringResource(R.string.backup_privacy_section)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.backup_keep_secure),
                    summary = stringResource(R.string.backup_keep_secure_summary),
                    enabled = false,
                    onClick = {},
                )
            }
        }
        item { SectionHeading(stringResource(R.string.backup_not_included)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.backup_esim_profiles),
                    summary = stringResource(R.string.backup_esim_profiles_summary),
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }

    OverlayDialog(
        show = showCreatePassword,
        title = stringResource(R.string.backup_protect_title),
        summary = stringResource(R.string.backup_protect_summary),
        onDismissRequest = {
            showCreatePassword = false
            backupPassword = ""
            backupPasswordConfirmation = ""
        },
    ) {
            Column {
            TextField(
                value = backupPassword,
                onValueChange = { backupPassword = it.take(128) },
                label = stringResource(R.string.backup_password),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            TextField(
                value = backupPasswordConfirmation,
                onValueChange = { backupPasswordConfirmation = it.take(128) },
                label = stringResource(R.string.backup_confirm_password),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            SettingsConfirmationActions(
                confirmText = stringResource(R.string.backup_encrypt_save),
                enabled = backupPassword.length >= 10 && backupPassword == backupPasswordConfirmation,
                onCancel = {
                    showCreatePassword = false
                    backupPassword = ""
                    backupPasswordConfirmation = ""
                },
                onConfirm = {
                    val password = backupPassword
                    showCreatePassword = false
                    backupPassword = ""
                    backupPasswordConfirmation = ""
                    if (!viewModel.prepareBackup(password)) {
                        showSnackbar(backupCreateFailed, SnackbarDuration.Short)
                        return@SettingsConfirmationActions
                    }
                    runCatching {
                        createBackupLauncher.launch(
                            "hyperlpa-backup-${LocalDate.now()}.json",
                        )
                    }.onFailure {
                        viewModel.cancelPreparedBackup()
                        showSnackbar(backupCreateFailed, SnackbarDuration.Short)
                    }
                },
            )
        }
    }

    OverlayDialog(
        show = pendingRestoreUri != null,
        title = stringResource(R.string.backup_restore_dialog_title),
        summary = stringResource(R.string.backup_restore_dialog_summary),
        onDismissRequest = {
            pendingRestoreUri = null
            restorePassword = ""
        },
    ) {
        Column {
            TextField(
                value = restorePassword,
                onValueChange = { restorePassword = it.take(128) },
                label = stringResource(R.string.backup_password),
                useLabelAsPlaceholder = true,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            SettingsConfirmationActions(
                confirmText = stringResource(R.string.backup_decrypt_restore),
                enabled = restorePassword.isNotEmpty(),
                onCancel = {
                    pendingRestoreUri = null
                    restorePassword = ""
                },
                onConfirm = {
                    val uri = pendingRestoreUri?.toUri() ?: return@SettingsConfirmationActions
                    val password = restorePassword
                    pendingRestoreUri = null
                    restorePassword = ""
                    viewModel.restoreBackup(uri, password) { success ->
                        showSnackbar(
                            if (success) backupRestored else backupRestoreFailed,
                            SnackbarDuration.Short,
                        )
                    }
                },
            )
        }
    }

    OverlayDialog(
        show = showResetConfirmation,
        title = stringResource(R.string.backup_reset_dialog_title),
        summary = stringResource(R.string.backup_reset_dialog_summary),
        onDismissRequest = { showResetConfirmation = false },
    ) {
        SettingsConfirmationActions(
            confirmText = stringResource(R.string.backup_reset_settings),
            destructive = true,
            onCancel = { showResetConfirmation = false },
            onConfirm = {
                showResetConfirmation = false
                viewModel.resetSettings { success ->
                    showSnackbar(
                        if (success) backupResetComplete else backupResetFailed,
                        SnackbarDuration.Short,
                    )
                }
            },
        )
    }
}

@Composable
private fun SettingsConfirmationActions(
    confirmText: String,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            text = stringResource(R.string.common_cancel),
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = enabled,
            colors = if (destructive) {
                ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error)
            } else {
                ButtonDefaults.textButtonColorsPrimary()
            },
            modifier = Modifier.weight(1f),
        )
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

    DetailLazyScaffold(title = stringResource(R.string.aid_manager_title), onBack = onBack) { _ ->
        item { SectionHeading(stringResource(R.string.aid_manager_selection_order)) }
        item {
            TipCard(text = stringResource(R.string.aid_manager_summary))
        }
        item {
            TextField(
                value = text,
                onValueChange = { value ->
                    inputTooLong = value.length > MaximumAidEditorCharacters
                    if (!inputTooLong) text = value
                },
                label = stringResource(R.string.aid_manager_input_label),
                useLabelAsPlaceholder = true,
                minLines = 7,
                maxLines = 12,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = if (validationMessage == null) 12.dp else 0.dp),
            )
        }
        validationMessage?.let { message ->
            item {
                Text(
                    text = message,
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier
                        .padding(horizontal = 28.dp)
                        .padding(top = 10.dp),
                )
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = if (validationMessage != null) 12.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.common_restore_defaults),
                    onClick = { text = DefaultIsdrAids.joinToString("\n") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.common_save),
                    onClick = {
                        onSave(parsed)
                        onBack()
                    },
                    enabled = validation.isSuccess && !inputTooLong,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
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
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(
                value = value,
                onValueChange = onValueChange,
                label = stringResource(R.string.reader_addresses_label),
                useLabelAsPlaceholder = true,
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
            Spacer(Modifier.height(12.dp))
            DialogButtons(
                onDismiss = onDismiss,
                onConfirm = onConfirm,
                confirmEnabled = confirmEnabled,
            )
        }
    }
}

@Composable
private fun NumberEditorDialog(
    show: Boolean,
    title: String,
    summary: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(
                value = value,
                onValueChange = onValueChange,
                label = title,
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            DialogButtons(onDismiss = onDismiss, onConfirm = onConfirm)
        }
    }
}

@Composable
private fun DialogButtons(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            text = stringResource(R.string.common_cancel),
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = stringResource(R.string.common_confirm),
            onClick = onConfirm,
            enabled = confirmEnabled,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.weight(1f),
        )
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
