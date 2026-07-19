package app.hyperlpa.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hyperlpa.BuildConfig
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.DefaultIsdrAids
import app.hyperlpa.data.settings.FloatingBottomBarStyle
import app.hyperlpa.data.settings.NavigationLabels
import app.hyperlpa.data.settings.NavigationStyle
import app.hyperlpa.data.settings.ProfileLayout
import app.hyperlpa.data.settings.ProfileSort
import app.hyperlpa.data.settings.RedactionMode
import app.hyperlpa.data.settings.ThemeAccent
import app.hyperlpa.data.settings.ThemeMode
import app.hyperlpa.data.settings.ThemePalette
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.HyperLpaViewModel
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.BlurredBar
import app.hyperlpa.ui.components.rememberAppBackdrop
import app.hyperlpa.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
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

private val ThemeModeLabels = listOf("Follow System", "Light", "Dark")
private val PaletteLabels = listOf(
    "Tonal Spot (TonalSpot)",
    "Neutral (Neutral)",
    "Vibrant (Vibrant)",
    "Expressive (Expressive)",
    "Rainbow (Rainbow)",
    "Fruit Salad (FruitSalad)",
    "Monochrome (Monochrome)",
    "Fidelity (Fidelity)",
    "Content (Content)",
)
private val AccentLabels = listOf(
    "System color",
    "Blue",
    "Purple",
    "Pink",
    "Red",
    "Orange",
    "Yellow",
    "Green",
    "Teal",
)

@Composable
fun AppearanceSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var densityDraft by remember(settings.densityScale) {
        mutableFloatStateOf((settings.densityScale * 100f).roundToInt().toFloat())
    }
    var showDensityDialog by rememberSaveable { mutableStateOf(false) }
    var densityText by rememberSaveable { mutableStateOf("") }
    val blurSupported = isRuntimeShaderSupported()
    val backdrop = rememberAppBackdrop()
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current

    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "Appearance & Theme",
                    color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { AppearanceBackButton(onBack) },
                )
            }
        },
    ) { innerPadding ->
        AppearanceResponsiveContent(
            modifier = Modifier
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) { contentModifier ->
            LazyColumn(
                modifier = contentModifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
                overscrollEffect = null,
            ) {
                item { SmallTitle("Color & Theme") }
                item {
                    AppearancePreferenceCard {
                        OverlayDropdownPreference(
                            title = "Theme Mode",
                            summary = ThemeModeLabels[settings.themeMode.ordinal],
                            items = ThemeModeLabels,
                            selectedIndex = settings.themeMode.ordinal,
                            onSelectedIndexChange = { index ->
                                ThemeMode.entries.getOrNull(index)?.let(viewModel::setThemeMode)
                            },
                        )
                        SwitchPreference(
                            title = "Monet Colors",
                            summary = "Use the system dynamic color palette when available",
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
                                    title = "Color Style",
                                    summary = PaletteLabels[settings.palette.ordinal],
                                    items = PaletteLabels,
                                    selectedIndex = settings.palette.ordinal,
                                    onSelectedIndexChange = { index ->
                                        ThemePalette.entries.getOrNull(index)?.let(viewModel::setPalette)
                                    },
                                )
                                OverlayDropdownPreference(
                                    title = "Accent Color",
                                    summary = AccentLabels[settings.accent.ordinal],
                                    items = AccentLabels,
                                    selectedIndex = settings.accent.ordinal,
                                    onSelectedIndexChange = { index ->
                                        ThemeAccent.entries.getOrNull(index)?.let(viewModel::setAccent)
                                    },
                                )
                                SwitchPreference(
                                    title = "Pure Black Background",
                                    summary = "Use a pure black background in dark mode",
                                    checked = settings.pureBlack,
                                    onCheckedChange = viewModel::setPureBlack,
                                )
                            }
                        }
                    }
                }

                item { SmallTitle("Interface & Effects") }
                item {
                    AppearancePreferenceCard {
                        SwitchPreference(
                            title = "Blur Effects",
                            summary = "Enable blur effects for bars and layered surfaces",
                            checked = settings.blurEnabled && blurSupported,
                            enabled = blurSupported,
                            onCheckedChange = viewModel::setBlurEnabled,
                        )
                        SwitchPreference(
                            title = "Predictive Back Gesture",
                            summary = "Enable predictive back animation",
                            checked = settings.predictiveBack,
                            onCheckedChange = viewModel::setPredictiveBack,
                        )
                        ArrowPreference(
                            title = "Interface Scale",
                            summary = "Adjust the global display scale (80% - 110%)",
                            endActions = {
                                Text(
                                    text = "${densityDraft.roundToInt()}%",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            },
                            bottomAction = {
                                Slider(
                                    value = densityDraft.coerceIn(80f, 110f),
                                    onValueChange = { densityDraft = it },
                                    onValueChangeFinished = {
                                        viewModel.setDensityScale(densityDraft / 100f)
                                    },
                                    valueRange = 80f..110f,
                                    showKeyPoints = true,
                                    keyPoints = listOf(80f, 90f, 100f, 110f),
                                    magnetThreshold = 0.01f,
                                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            holdDownState = showDensityDialog,
                            onClick = {
                                densityText = densityDraft.roundToInt().toString()
                                showDensityDialog = true
                            },
                        )
                    }
                }

                item { SmallTitle("Bottom Navigation") }
                item {
                    AppearancePreferenceCard {
                        SwitchPreference(
                            title = "Floating Bottom Bar",
                            summary = "Use a floating bottom navigation bar on phones",
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
                                title = "Floating Bottom Bar Style",
                                summary = if (settings.floatingBottomBarStyle == FloatingBottomBarStyle.MIUIX) {
                                    "Miuix"
                                } else {
                                    "iOS-like (Liquid Glass)"
                                },
                                items = listOf("Miuix", "iOS-like (Liquid Glass)"),
                                selectedIndex = settings.floatingBottomBarStyle.ordinal,
                                onSelectedIndexChange = { index ->
                                    FloatingBottomBarStyle.entries.getOrNull(index)
                                        ?.let(viewModel::setFloatingBottomBarStyle)
                                },
                            )
                        }
                        OverlayDropdownPreference(
                            title = "Bottom Bar Options",
                            summary = if (settings.navigationLabels == NavigationLabels.ICON_AND_TEXT) {
                                "Icon and Text"
                            } else {
                                "Icon Only"
                            },
                            items = listOf("Icon and Text", "Icon Only"),
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
        title = "Interface Scale",
        summary = "Adjust the global display scale (80% - 110%)",
        onDismissRequest = { showDensityDialog = false },
    ) {
        TextField(
            value = densityText,
            onValueChange = { value -> densityText = value.filter(Char::isDigit).take(3) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() },
            ),
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
                text = "Cancel",
                onClick = { showDensityDialog = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "Confirm",
                onClick = {
                    val percent = densityText.toIntOrNull()
                        ?.coerceIn(80, 110)
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

@Composable
fun ProfileDisplaySettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
) {
    val profileLayouts = ProfileLayout.entries
    val profileSorts = ProfileSort.entries

    DetailLazyScaffold(title = "Profile display", onBack = onBack) { _ ->
        item { SectionHeading("Layout") }
        item {
            GroupedCard {
                OverlayDropdownPreference(
                    items = profileLayouts.map { it.displayName() },
                    selectedIndex = profileLayouts.indexOf(settings.profileLayout),
                    title = "Profile layout",
                    summary = "Choose a list or responsive waterfall cards",
                    onSelectedIndexChange = { viewModel.setProfileLayout(profileLayouts[it]) },
                )
            }
        }
        item { SectionHeading("Profile page") }
        item {
            GroupedCard {
                OverlayDropdownPreference(
                    items = profileSorts.map { it.displayName() },
                    selectedIndex = profileSorts.indexOf(settings.profileSort),
                    title = "Sort profiles by",
                    onSelectedIndexChange = { viewModel.setProfileSort(profileSorts[it]) },
                )
                SwitchPreference(
                    checked = settings.sortAscending,
                    onCheckedChange = viewModel::setSortAscending,
                    title = "Ascending order",
                )
                SwitchPreference(
                    checked = settings.showProfileSearch,
                    onCheckedChange = viewModel::setShowProfileSearch,
                    title = "Profile search",
                    summary = "Show the search field below the eUICC selector",
                )
            }
        }
    }
}

@Composable
private fun AppearanceResponsiveContent(
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        content(Modifier.fillMaxWidth().widthIn(max = 720.dp))
    }
}

@Composable
private fun AppearancePreferenceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp),
        content = content,
    )
}

@Composable
private fun AppearanceBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
    }
}

@Composable
fun ReaderSettingsScreen(
    state: HyperLpaUiState,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
) {
    var showRemoteEditor by remember { mutableStateOf(false) }
    var remoteUrls by remember(state.settings.remoteReaderUrls) {
        mutableStateOf(state.settings.remoteReaderUrls.joinToString("\n"))
    }

    DetailLazyScaffold(title = "eUICC readers", onBack = onBack) { _ ->
        item { SectionHeading("Behaviour") }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = state.settings.autoLoadProfiles,
                    onCheckedChange = viewModel::setAutoLoadProfiles,
                    title = "Connect automatically",
                    summary = "Reconnect the last available reader when the app starts",
                )
                ArrowPreference(
                    title = "Discover readers now",
                    summary = "Refresh NBridge, OMAPI and USB CCID endpoints",
                    onClick = { viewModel.refreshReaders() },
                )
            }
        }

        item { SectionHeading("Reader backends") }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = state.settings.enableNBridge,
                    onCheckedChange = viewModel::setEnableNBridge,
                    title = "NBridge",
                    summary = "Use Nekoko-compatible privileged bridge slots",
                )
                SwitchPreference(
                    checked = state.settings.enableOmapi,
                    onCheckedChange = viewModel::setEnableOmapi,
                    title = "Secure Element service",
                    summary = "Use Android OMAPI readers exposed by the device",
                )
                SwitchPreference(
                    checked = state.settings.enableUsbCcid,
                    onCheckedChange = viewModel::setEnableUsbCcid,
                    title = "USB CCID",
                    summary = "Use removable smart-card and eUICC readers",
                )
                SwitchPreference(
                    checked = state.settings.enableTelephony,
                    onCheckedChange = viewModel::setEnableTelephony,
                    title = "Privileged telephony",
                    summary = "Requires system or carrier privileges on supported ROMs",
                )
                SwitchPreference(
                    checked = state.settings.enableBle,
                    onCheckedChange = viewModel::setEnableBle,
                    title = "Bluetooth LE readers",
                    summary = "Discover compatible external APDU reader devices",
                )
                SwitchPreference(
                    checked = state.settings.enableRemote,
                    onCheckedChange = viewModel::setEnableRemote,
                    title = "Remote readers",
                    summary = "Use explicitly configured network APDU endpoints",
                )
                ArrowPreference(
                    title = "Remote reader addresses",
                    summary = state.settings.remoteReaderUrls.takeIf { it.isNotEmpty() }
                        ?.joinToString() ?: "No remote endpoints configured",
                    enabled = state.settings.enableRemote,
                    onClick = { showRemoteEditor = true },
                )
            }
        }

        item { SectionHeading("Available now") }
        if (state.lpa.readers.isEmpty()) {
            item {
                GroupedCard {
                    ArrowPreference(
                        title = "No readers found",
                        summary = "Check permissions, connect a reader, then discover again",
                        enabled = false,
                    )
                }
            }
        } else {
            item {
                GroupedCard {
                    state.lpa.readers.forEach { reader ->
                        ArrowPreference(
                            title = reader.name,
                            summary = listOfNotNull(reader.kind.displayName(), reader.detail).joinToString(" · "),
                            endActions = {
                                if (reader.id == state.lpa.selectedReaderId) {
                                    Text(
                                        text = "Connected",
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
        title = "Remote reader addresses",
        summary = "Enter one HTTPS or WebSocket endpoint per line.",
        value = remoteUrls,
        onValueChange = { remoteUrls = it },
        onDismiss = { showRemoteEditor = false },
        onConfirm = {
            viewModel.setRemoteReaderUrls(remoteUrls.lineSequence().map(String::trim).filter(String::isNotEmpty).toList())
            showRemoteEditor = false
            viewModel.refreshReaders()
        },
    )
}

@Composable
fun NotificationSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    viewModel: HyperLpaViewModel,
) {
    DetailLazyScaffold(title = "Notifications", onBack = onBack) { _ ->
        item { SectionHeading("Check for notifications") }
        item {
            GroupedCard {
                SwitchPreference(settings.notificationInitialLoad, viewModel::setNotificationInitialLoad, "After connecting")
                SwitchPreference(settings.notificationAfterSwitch, viewModel::setNotificationAfterSwitch, "After switching a profile")
                SwitchPreference(settings.notificationAfterDelete, viewModel::setNotificationAfterDelete, "After deleting a profile")
                SwitchPreference(settings.notificationBeforeDownload, viewModel::setNotificationBeforeDownload, "Before downloading")
                SwitchPreference(settings.notificationAfterDownload, viewModel::setNotificationAfterDownload, "After downloading")
            }
        }
        item { SectionHeading("Processing") }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = settings.notificationAutoSend,
                    onCheckedChange = viewModel::setNotificationAutoSend,
                    title = "Send automatically",
                    summary = "Deliver pending eUICC notifications to their SM-DP+ servers",
                )
                SwitchPreference(
                    checked = settings.notificationAutoRemove,
                    onCheckedChange = viewModel::setNotificationAutoRemove,
                    title = "Remove after sending",
                    summary = "Delete a notification from the eUICC only after successful delivery",
                    enabled = settings.notificationAutoSend,
                )
                SwitchPreference(
                    checked = settings.scheduledReminders,
                    onCheckedChange = viewModel::setScheduledReminders,
                    title = "Profile reminders",
                    summary = "Allow scheduled device notifications for tagged profiles",
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
    DetailLazyScaffold(title = "Privacy", onBack = onBack) { _ ->
        item { SectionHeading("Sensitive identifiers") }
        item {
            GroupedCard {
                OverlayDropdownPreference(
                    items = redactionModes.map { it.displayName() },
                    selectedIndex = redactionModes.indexOf(settings.eidRedaction),
                    title = "EID redaction",
                    summary = "Controls how the eUICC identifier is shown",
                    onSelectedIndexChange = { viewModel.setEidRedaction(redactionModes[it]) },
                )
                OverlayDropdownPreference(
                    items = redactionModes.map { it.displayName() },
                    selectedIndex = redactionModes.indexOf(settings.iccidRedaction),
                    title = "ICCID redaction",
                    summary = "Controls how profile identifiers are shown",
                    onSelectedIndexChange = { viewModel.setIccidRedaction(redactionModes[it]) },
                )
                SwitchPreference(
                    checked = settings.revealSensitiveData,
                    onCheckedChange = viewModel::setRevealSensitiveData,
                    title = "Reveal identifiers",
                    summary = "Temporarily override redaction throughout the app",
                )
            }
        }
        item { SectionHeading("Nekoko Cloud") }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = settings.loadOperatorIcons,
                    onCheckedChange = viewModel::setLoadOperatorIcons,
                    title = "Operator icons",
                    summary = "Resolve and cache profile artwork from operator-icons.pages.dev",
                )
                SwitchPreference(
                    checked = settings.estimateProfileSize,
                    onCheckedChange = viewModel::setEstimateProfileSize,
                    title = "Profile size predictions",
                    summary = "Use NekokoLPA reference data when a measured size is unavailable",
                )
                ArrowPreference(
                    title = "Clear icon cache",
                    summary = "Remove downloaded operator artwork and fetch it again when needed",
                    onClick = viewModel::clearOperatorIconCache,
                )
                ArrowPreference(
                    title = "Cloud data use",
                    summary = "Downloads public icon catalogs and size references; HyperLPA does not upload installation reports",
                    enabled = false,
                )
            }
        }
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

    DetailLazyScaffold(title = "Advanced", onBack = onBack) { _ ->
        item { SectionHeading("LPA protocol") }
        item {
            GroupedCard {
                ArrowPreference(
                    title = "ES10x maximum segment size",
                    summary = "${settings.es10xMss} bytes",
                    onClick = { showMssEditor = true },
                )
                ArrowPreference(
                    title = "IMEI",
                    summary = settings.imei.ifBlank { "Not supplied to SM-DP+ sessions" },
                    onClick = { showImeiEditor = true },
                )
                ArrowPreference(
                    title = "ISD-R application identifiers",
                    summary = "${settings.isdrAids.size} selection candidates",
                    onClick = { viewModel.navigate(AppRoute.AidManager) },
                )
            }
        }
        item { SectionHeading("Diagnostics") }
        item {
            GroupedCard {
                SwitchPreference(
                    checked = settings.developerMode,
                    onCheckedChange = viewModel::setDeveloperMode,
                    title = "Developer mode",
                    summary = "Expose protocol details and diagnostic controls",
                )
                SwitchPreference(
                    checked = settings.apduLogging,
                    onCheckedChange = viewModel::setApduLogging,
                    title = "APDU logging",
                    summary = "May contain sensitive card traffic; use only for debugging",
                    enabled = settings.developerMode,
                )
                SwitchPreference(
                    checked = settings.hideEuiccMemoryReset,
                    onCheckedChange = viewModel::setHideEuiccMemoryReset,
                    title = "Hide eUICC memory reset",
                    summary = "Remove the reset action from eUICC information",
                )
            }
        }
    }

    NumberEditorDialog(
        show = showMssEditor,
        title = "Maximum segment size",
        summary = "Enter a value from 32 to 255 bytes.",
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
        summary = "Optional. HyperLPA stores it locally and supplies it only during profile downloads.",
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
fun AidManagerScreen(
    aids: List<String>,
    onBack: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var text by remember(aids) { mutableStateOf(aids.joinToString("\n")) }
    val parsed = text.lineSequence().map { it.trim().uppercase() }.filter(String::isNotEmpty).distinct().toList()
    val invalid = parsed.filterNot { value -> value.length % 2 == 0 && value.all { it in "0123456789ABCDEF" } }

    DetailLazyScaffold(title = "ISD-R AIDs", onBack = onBack) { _ ->
        item { SectionHeading("Selection order") }
        item {
            GroupedCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "HyperLPA tries these application identifiers in order until the eUICC accepts one.",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(14.dp))
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        label = "One hexadecimal AID per line",
                        useLabelAsPlaceholder = true,
                        minLines = 7,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (invalid.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${invalid.size} invalid ${if (invalid.size == 1) "entry" else "entries"}",
                            color = MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = "Restore defaults",
                            onClick = { text = DefaultIsdrAids.joinToString("\n") },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "Save",
                            onClick = {
                                onSave(parsed)
                                onBack()
                            },
                            enabled = parsed.isNotEmpty() && invalid.isEmpty(),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    DetailLazyScaffold(title = "About HyperLPA", onBack = onBack) { _ ->
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 26.dp)) {
                Text(
                    text = "HyperLPA",
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "A native HyperOS-style local profile assistant",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        item { SectionHeading("Build") }
        item {
            GroupedCard {
                ArrowPreference(title = "Version", summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", enabled = false)
                ArrowPreference(title = "Android", summary = "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}", enabled = false)
                ArrowPreference(title = "Interface", summary = "Miuix Compose 0.9.3 · Jetpack Compose 1.11.4", enabled = false)
            }
        }
        item { SectionHeading("Open source") }
        item {
            GroupedCard {
                ArrowPreference(
                    title = "Source code",
                    summary = "GPL-3.0-or-later compatible implementation",
                    onClick = { uriHandler.openUri("https://github.com/") },
                )
                ArrowPreference(
                    title = "Miuix Compose",
                    summary = "Apache-2.0 interface toolkit",
                    onClick = { uriHandler.openUri("https://github.com/compose-miuix-ui/miuix") },
                )
                ArrowPreference(
                    title = "lpac / OpenEUICC",
                    summary = "eSIM protocol engine and compatible reader work",
                    onClick = { uriHandler.openUri("https://github.com/estkme-group/openeuicc") },
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
                label = "Addresses",
                useLabelAsPlaceholder = true,
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            DialogButtons(onDismiss = onDismiss, onConfirm = onConfirm)
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
            Spacer(Modifier.height(18.dp))
            DialogButtons(onDismiss = onDismiss, onConfirm = onConfirm)
        }
    }
}

@Composable
private fun DialogButtons(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        TextButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        TextButton(
            text = "Confirm",
            onClick = onConfirm,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.weight(1f),
        )
    }
}

private fun Enum<*>.displayName(): String = name
    .lowercase()
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
