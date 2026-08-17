package app.hyperlpa.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.hyperlpa.data.settings.FloatingBottomBarStyle
import app.hyperlpa.data.settings.NavigationLabels
import app.hyperlpa.data.settings.NavigationStyle
import app.hyperlpa.data.metadata.providerIconKey
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.DownloadStage
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.ui.adaptive.AdaptiveTopAppBar
import app.hyperlpa.ui.adaptive.rememberIsWideWindow
import app.hyperlpa.ui.components.BlurredBar
import app.hyperlpa.ui.components.appBackdropBlur
import app.hyperlpa.ui.components.liquid.NzbLiquidGlassNavigationBar
import app.hyperlpa.ui.components.rememberAppBackdrop
import app.hyperlpa.ui.navigation.AppRoute
import app.hyperlpa.ui.navigation.AppTab
import app.hyperlpa.ui.navigation.titleRes
import app.hyperlpa.ui.screens.AboutScreen
import app.hyperlpa.ui.screens.AdvancedSettingsScreen
import app.hyperlpa.ui.screens.AidManagerScreen
import app.hyperlpa.ui.screens.AppearanceSettingsScreen
import app.hyperlpa.ui.screens.BatchDownloadScreen
import app.hyperlpa.ui.screens.BackupRestoreSettingsScreen
import app.hyperlpa.ui.screens.DownloadProfileScreen
import app.hyperlpa.ui.screens.ProfileDownloadConfirmationScreen
import app.hyperlpa.ui.screens.ProfileDownloadResultScreen
import app.hyperlpa.ui.screens.EuiccDetailsScreen
import app.hyperlpa.ui.screens.LogsScreen
import app.hyperlpa.ui.screens.NotificationHistoryScreen
import app.hyperlpa.ui.screens.NotificationSettingsScreen
import app.hyperlpa.ui.screens.NotificationsScreen
import app.hyperlpa.ui.screens.PrivacySettingsScreen
import app.hyperlpa.ui.screens.ProfileDetailsScreen
import app.hyperlpa.ui.screens.ProfileDisplaySettingsScreen
import app.hyperlpa.ui.screens.ProfilesScreen
import app.hyperlpa.ui.screens.ReaderSettingsScreen
import app.hyperlpa.ui.screens.ScheduledRemindersScreen
import app.hyperlpa.ui.screens.SettingsScreen
import app.hyperlpa.ui.screens.StatisticsScreen
import app.hyperlpa.ui.screens.TagManagerScreen
import app.hyperlpa.ui.screens.TagsAndRemindersScreen
import app.hyperlpa.ui.screens.ToolsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HyperLpaApp(
    state: HyperLpaUiState,
    backStack: NavBackStack,
    viewModel: HyperLpaViewModel,
    snackbarHostState: SnackbarHostState,
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: ((Boolean) -> Unit) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onTestProfileReminder: () -> Unit,
    onScanQr: () -> Unit,
    bluetoothReaderState: BluetoothReaderUiState,
    onRefreshReaders: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val currentState = rememberUpdatedState(state)
    val currentNotificationPermissionGranted = rememberUpdatedState(notificationPermissionGranted)
    val currentBluetoothReaderState = rememberUpdatedState(bluetoothReaderState)
    val currentOnRefreshReaders = rememberUpdatedState(onRefreshReaders)
    val currentOnRequestBluetoothPermission = rememberUpdatedState(onRequestBluetoothPermission)
    val currentOnOpenBluetoothSettings = rememberUpdatedState(onOpenBluetoothSettings)
    val navCornerRadius = if (rememberIsWideWindow()) 0.dp else rememberNavSystemCornerRadius()
    val navSurface = MiuixTheme.colorScheme.surface
    val swipeBackDirection = when (LocalLayoutDirection.current) {
        LayoutDirection.Rtl -> NavSwipeDirection.RightToLeft
        else -> NavSwipeDirection.LeftToRight
    }

    val snackbarScope = rememberCoroutineScope()
    val showSnackbar: (String, top.yukonga.miuix.kmp.basic.SnackbarDuration) -> Unit = remember(
        snackbarHostState,
        snackbarScope,
    ) {
        { message, duration ->
            snackbarScope.launch {
                snackbarHostState.showSnackbar(message = message, duration = duration)
            }
        }
    }

    CompositionLocalProvider(LocalMiuixSnackbar provides showSnackbar) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = navSurface,
            snackbarHost = {
                if (backStack.lastOrNull() != AppRoute.Shell) {
                    SnackbarHost(state = snackbarHostState)
                }
            },
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                NavDisplay(
                backStack = backStack,
                onBack = viewModel::navigateBack,
                transition = NavTransitions.MiuixDefault,
                effects = NavDisplayEffects(
                    enableCornerClip = true,
                    cornerClipRadius = navCornerRadius,
                    cornerClipMode = NavCornerClipMode.Leading,
                    dimAmount = 0.5f,
                    blockInputDuringTransition = false,
                    backdropColor = navSurface,
                ),
                ) {
                entry<AppRoute.Shell>(swipeDismiss = swipeBackDirection) {
                    MainShell(
                        state = currentState.value,
                        viewModel = viewModel,
                        bluetoothReaderState = currentBluetoothReaderState.value,
                        onRefreshReaders = { currentOnRefreshReaders.value() },
                        snackbarHostState = snackbarHostState,
                    )
                }
            entry<AppRoute.ProfileDetails>(swipeDismiss = swipeBackDirection) { route ->
                val profile = currentState.value.profiles.firstOrNull { it.iccid == route.iccid }
                ProfileDetailsScreen(
                    profile = profile,
                    lpa = currentState.value.lpa,
                    settings = currentState.value.settings,
                    suggestedTags = currentState.value.profiles.flatMap { it.tags }.toSet(),
                    operatorIcon = currentState.value.operatorIcons[route.iccid],
                    hasProfileIcon = currentState.value.metadata[route.iccid]?.iconUri != null,
                    isProviderIconHidden = currentState.value.metadata[route.iccid]
                        ?.isProviderIconHidden == true,
                    hasProviderIcon = hasProviderIcon(
                        providerName = profile?.providerName,
                        providerIcons = currentState.value.providerIcons,
                    ),
                    onBack = viewModel::navigateBack,
                    onEnableChange = { enabled -> viewModel.setProfileEnabled(route.iccid, enabled) },
                    profileSwitchEnabled = currentState.value.lpa.operation !is LpaOperation.Switching,
                    onSetPinned = { pinned -> viewModel.setProfilePinned(route.iccid, pinned) },
                    onRename = { nickname -> viewModel.renameProfile(route.iccid, nickname) },
                    onDelete = { viewModel.deleteProfile(route.iccid) },
                    onSetTags = { tags -> viewModel.setProfileTags(route.iccid, tags) },
                    onSetReminder = { label, instant -> viewModel.setProfileReminder(route.iccid, label, instant) },
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onSetIcon = { uri, applyToProvider, onComplete ->
                        viewModel.setProfileIcon(
                            iccid = route.iccid,
                            uri = uri,
                            applyToProvider = applyToProvider,
                            providerName = profile?.providerName,
                            onComplete = onComplete,
                        )
                    },
                    onSetProviderIconHidden = { hidden, onComplete ->
                        viewModel.setProfileProviderIconHidden(
                            iccid = route.iccid,
                            hidden = hidden,
                            providerName = profile?.providerName,
                            onComplete = onComplete,
                        )
                    },
                    onApplyIconToProvider = { onComplete ->
                        viewModel.applyProfileIconToProvider(
                            route.iccid,
                            profile?.providerName,
                            onComplete,
                        )
                    },
                )
            }
            entry<AppRoute.DownloadProfile>(swipeDismiss = swipeBackDirection) {
                val singleDownloadActive by viewModel.singleDownloadActive.collectAsStateWithLifecycle()
                DownloadProfileScreen(
                    initialValue = currentState.value.activationCodeDraft,
                    imei = currentState.value.settings.imei,
                    busy = singleDownloadActive,
                    onBack = viewModel::navigateBack,
                    onValueChange = viewModel::setActivationCodeDraft,
                    onScanQr = onScanQr,
                    onContinue = viewModel::downloadProfile,
                )
            }
            entry<AppRoute.ConfirmProfileDownload>(swipeDismiss = swipeBackDirection) {
                val livePreview = currentState.value.lpa.pendingProfileDownload
                var retainedPreview by remember { mutableStateOf(livePreview) }
                var retainedIcon by remember { mutableStateOf(currentState.value.downloadPreviewIcon) }
                var retainedEstimatedBytes by remember {
                    mutableStateOf(currentState.value.estimatedDownloadBytes)
                }
                var retainedEnrichmentLoading by remember {
                    mutableStateOf(currentState.value.downloadPreviewEnrichmentLoading)
                }
                LaunchedEffect(
                    livePreview,
                    currentState.value.downloadPreviewIcon,
                    currentState.value.estimatedDownloadBytes,
                    currentState.value.downloadPreviewEnrichmentLoading,
                ) {
                    if (livePreview != null) {
                        retainedPreview = livePreview
                        retainedIcon = currentState.value.downloadPreviewIcon
                        retainedEstimatedBytes = currentState.value.estimatedDownloadBytes
                        retainedEnrichmentLoading = currentState.value.downloadPreviewEnrichmentLoading
                    }
                }
                (livePreview ?: retainedPreview)?.let { preview ->
                    ProfileDownloadConfirmationScreen(
                        preview = preview,
                        iccidRedaction = currentState.value.settings.iccidRedaction,
                        cloudIcon = if (livePreview != null) {
                            currentState.value.downloadPreviewIcon
                        } else {
                            retainedIcon
                        },
                        estimatedDownloadBytes = if (livePreview != null) {
                            currentState.value.estimatedDownloadBytes
                        } else {
                            retainedEstimatedBytes
                        },
                        enrichmentLoading = if (livePreview != null) {
                            currentState.value.downloadPreviewEnrichmentLoading
                        } else {
                            retainedEnrichmentLoading
                        },
                        showCancelConfirmation = currentState.value.showCancelDownloadConfirmation,
                        onBack = viewModel::navigateBack,
                        onDownload = viewModel::confirmProfileDownload,
                        onDismissCancelConfirmation = viewModel::dismissCancelProfileDownload,
                        onConfirmCancel = viewModel::confirmCancelProfileDownload,
                    )
                }
            }
            entry<AppRoute.ProfileDownloadResult>(swipeDismiss = swipeBackDirection) { route ->
                val result = route.result
                val profile = currentState.value.profiles
                    .firstOrNull { it.iccid == result.profile.iccid }
                    ?: result.profile
                ProfileDownloadResultScreen(
                    result = result,
                    profile = profile,
                    cloudIcon = currentState.value.downloadPreviewIcon
                        ?: currentState.value.operatorIcons[result.profile.iccid],
                    busy = currentState.value.lpa.operation !is LpaOperation.Idle,
                    onBack = viewModel::navigateBack,
                    onEnable = { viewModel.setProfileEnabled(profile.iccid, true) },
                    onRename = { nickname -> viewModel.renameProfile(profile.iccid, nickname) },
                    onDone = viewModel::finishProfileDownload,
                )
            }
            entry<AppRoute.BatchDownload>(swipeDismiss = swipeBackDirection) {
                val batchState by viewModel.batchDownloadState.collectAsStateWithLifecycle()
                BatchDownloadScreen(
                    imei = currentState.value.settings.imei,
                    state = batchState,
                    onBack = viewModel::navigateBack,
                    onDownload = viewModel::startBatchDownload,
                    onResume = viewModel::resumeBatchDownload,
                    onRetry = viewModel::retryFailedBatchDownload,
                    onCancel = viewModel::cancelBatchDownload,
                    onClear = viewModel::clearBatchDownload,
                )
            }
            entry<AppRoute.EuiccDetails>(swipeDismiss = swipeBackDirection) {
                EuiccDetailsScreen(
                    info = currentState.value.lpa.euiccInfo,
                    cardName = currentState.value.currentEuiccName,
                    reader = currentState.value.lpa.selectedReader,
                    installedProfileCount = currentState.value.lpa.profiles.size,
                    enabledProfileCount = currentState.value.lpa.profiles.count {
                        it.state == ProfileState.ENABLED
                    },
                    discoveredSmdpAddresses = currentState.value.lpa.discoveredSmdpAddresses,
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    onSetCardName = { name ->
                        currentState.value.lpa.euiccInfo?.eid?.let { eid ->
                            viewModel.setEuiccName(eid, name)
                        }
                    },
                    onReset = viewModel::resetEuiccMemory,
                    onSetDefaultSmdpAddress = viewModel::setDefaultSmdpAddress,
                    onDiscoverProfiles = viewModel::discoverProfiles,
                    onUseDiscoveredAddress = viewModel::useDiscoveredSmdpAddress,
                )
            }
            entry<AppRoute.ReaderSettings>(swipeDismiss = swipeBackDirection) {
                ReaderSettingsScreen(
                    state = currentState.value,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                    bluetoothReaderState = currentBluetoothReaderState.value,
                    onDiscoverReaders = { currentOnRefreshReaders.value() },
                    onRequestBluetoothPermission = {
                        currentOnRequestBluetoothPermission.value()
                    },
                    onOpenBluetoothSettings = { currentOnOpenBluetoothSettings.value() },
                )
            }
            entry<AppRoute.NotificationSettings>(swipeDismiss = swipeBackDirection) {
                NotificationSettingsScreen(
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.NotificationHistory>(swipeDismiss = swipeBackDirection) {
                NotificationHistoryScreen(
                    state = currentState.value,
                    onBack = viewModel::navigateBack,
                    onDeleteHistoryEntry = viewModel::deleteNotificationHistoryEntry,
                    onResendNotification = viewModel::resendNotification,
                )
            }
            entry<AppRoute.AppearanceSettings>(swipeDismiss = swipeBackDirection) {
                AppearanceSettingsScreen(
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.ProfileDisplaySettings>(swipeDismiss = swipeBackDirection) {
                ProfileDisplaySettingsScreen(
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.PrivacySettings>(swipeDismiss = swipeBackDirection) {
                PrivacySettingsScreen(
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.AdvancedSettings>(swipeDismiss = swipeBackDirection) {
                AdvancedSettingsScreen(
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.BackupRestoreSettings>(swipeDismiss = swipeBackDirection) {
                BackupRestoreSettingsScreen(
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.AidManager>(swipeDismiss = swipeBackDirection) {
                AidManagerScreen(
                    aids = currentState.value.settings.isdrAids,
                    onBack = viewModel::navigateBack,
                    onSave = viewModel::setIsdrAids,
                )
            }
            entry<AppRoute.TagsAndReminders>(swipeDismiss = swipeBackDirection) {
                TagsAndRemindersScreen(
                    settings = currentState.value.settings,
                    notificationPermissionGranted = currentNotificationPermissionGranted.value,
                    onBack = viewModel::navigateBack,
                    onOpenTagManager = { viewModel.navigate(AppRoute.TagManager) },
                    onOpenScheduledReminders = { viewModel.navigate(AppRoute.ScheduledReminders) },
                    onSetRemindersEnabled = viewModel::setScheduledReminders,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onTestNotification = onTestProfileReminder,
                )
            }
            entry<AppRoute.TagManager>(swipeDismiss = swipeBackDirection) {
                TagManagerScreen(
                    profiles = currentState.value.profiles,
                    onBack = viewModel::navigateBack,
                    onSetTags = viewModel::setProfileTags,
                )
            }
            entry<AppRoute.ScheduledReminders>(swipeDismiss = swipeBackDirection) {
                ScheduledRemindersScreen(
                    profiles = currentState.value.profiles,
                    onBack = viewModel::navigateBack,
                    onOpen = { profile -> viewModel.navigate(AppRoute.ProfileDetails(profile.iccid)) },
                    onClear = { profile ->
                        viewModel.setProfileReminder(
                            profile.iccid,
                            profile.nickname.ifBlank { profile.name },
                            null,
                        )
                    },
                )
            }
            entry<AppRoute.Statistics>(swipeDismiss = swipeBackDirection) {
                StatisticsScreen(
                    profiles = currentState.value.profiles,
                    notifications = currentState.value.lpa.notifications,
                    onBack = viewModel::navigateBack,
                )
            }
            entry<AppRoute.Logs>(swipeDismiss = swipeBackDirection) {
                LogsScreen(
                    logs = currentState.value.lpa.logs,
                    onBack = viewModel::navigateBack,
                    onExportSupportReport = viewModel::exportSupportReport,
                )
            }
            entry<AppRoute.About>(swipeDismiss = swipeBackDirection) {
                AboutScreen(onBack = viewModel::navigateBack)
            }
        }

            OperationProgressDialog(operation = state.lpa.operation)
            ProfileInstallProgressDialog(operation = state.lpa.operation)
            OperationFailureDialog(
                failure = state.lpa.failure,
                onDismiss = viewModel::clearFailure,
            )
            LastEnabledProfileDisableDialog(
                show = state.pendingProfileDisableConfirmation != null,
                onCancel = viewModel::cancelLastEnabledProfileDisable,
                onConfirm = viewModel::confirmLastEnabledProfileDisable,
            )
            MainTabBackHandler(
                enabled = backStack.lastOrNull() == AppRoute.Shell &&
                    state.selectedTab != AppTab.PROFILES,
                onBack = { viewModel.selectTab(AppTab.PROFILES) },
            )
            }
        }
    }
}

internal fun hasProviderIcon(
    providerName: String?,
    providerIcons: Map<String, String>,
): Boolean = providerIconKey(providerName)?.let(providerIcons::containsKey) == true

@Composable
private fun MainTabBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val navigationEventState = rememberNavigationEventState(
        currentInfo = NavigationEventInfo.None,
    )
    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
}

@Composable
private fun MainShell(
    state: HyperLpaUiState,
    viewModel: HyperLpaViewModel,
    bluetoothReaderState: BluetoothReaderUiState,
    onRefreshReaders: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val tabs = AppTab.entries
    val pagerState = rememberPagerState(initialPage = state.selectedTab.ordinal) { tabs.size }
    val mainPagerState = rememberMainPagerState(pagerState)
    val selectedTab by rememberUpdatedState(state.selectedTab)
    val scrollBehaviors = tabs.map { MiuixScrollBehavior() }

    LaunchedEffect(pagerState.currentPage) { mainPagerState.syncPage() }
    LaunchedEffect(state.selectedTab) {
        mainPagerState.animateToPage(state.selectedTab.ordinal)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to mainPagerState.navigating }
            .distinctUntilChanged()
            .collect { (page, navigating) ->
                // Cancelling one programmatic scroll can briefly settle its intermediate page.
                // Wait for the latest request to finish before publishing a page to the model,
                // otherwise that stale result can cancel the newer tab animation.
                if (navigating) return@collect
                val tab = tabs[page]
                if (tab != selectedTab) viewModel.selectTab(tab)
            }
    }

    val navigationItems = listOf(
        NavigationItem(stringResource(app.hyperlpa.R.string.nav_profiles), MiuixIcons.BankCards),
        NavigationItem(stringResource(app.hyperlpa.R.string.nav_notifications), MiuixIcons.Messages),
        NavigationItem(stringResource(app.hyperlpa.R.string.nav_tools), MiuixIcons.Tune),
        NavigationItem(stringResource(app.hyperlpa.R.string.nav_settings), MiuixIcons.Settings),
    )
    val isWide = rememberIsWideWindow()
    val navigateTo: (Int) -> Unit = { index ->
        if (index != mainPagerState.selectedPage) mainPagerState.animateToPage(index)
    }
    val pagerContent: @Composable (Modifier, PaddingValues) -> Unit = { modifier, outerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = modifier,
            beyondViewportPageCount = 1,
            overscrollEffect = null,
        ) { page ->
            val tab = tabs[page]
            MainTabPage(
                tab = tab,
                state = state,
                viewModel = viewModel,
                scrollBehavior = scrollBehaviors[page],
                outerPadding = outerPadding,
                bluetoothReaderState = bluetoothReaderState,
                onRefreshReaders = onRefreshReaders,
            )
        }
    }

    if (isWide) {
        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(state = snackbarHostState) },
        ) { _ ->
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    navigationItems.forEachIndexed { index, item ->
                        NavigationRailItem(
                            selected = mainPagerState.selectedPage == index,
                            onClick = { navigateTo(index) },
                            icon = item.icon,
                            label = item.label,
                        )
                    }
                }
                pagerContent(
                    Modifier.weight(1f).fillMaxHeight(),
                    PaddingValues(0.dp),
                )
            }
        }
    } else {
        val profilesLoading = state.isProfilesLoading
        val useStaticBackdrop = profilesLoading && mainPagerState.selectedPage == AppTab.PROFILES.ordinal
        val backdrop = rememberAppBackdrop()
        val blurActive = backdrop != null
        val showLabels = state.settings.navigationLabels == NavigationLabels.ICON_AND_TEXT
        val regularBarColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
        val floatingShape = RoundedCornerShape(50.dp)
        val solidFloatingColor = if (profilesLoading) {
            MiuixTheme.colorScheme.surface
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        }
        val floatingColor = if (blurActive) Color.Transparent else solidFloatingColor
        val floatingHighlight = if (MiuixTheme.colorScheme.surface.luminance() < 0.5f) {
            Highlight.GlassStrokeMiddleDark
        } else {
            Highlight.GlassStrokeMiddleLight
        }

        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(state = snackbarHostState) },
            bottomBar = {
                if (state.settings.navigationStyle == NavigationStyle.FLOATING) {
                    if (state.settings.floatingBottomBarStyle == FloatingBottomBarStyle.IOS_LIKE) {
                        NzbLiquidGlassNavigationBar(
                            items = navigationItems,
                            selectedIndex = mainPagerState.selectedPage,
                            onItemClick = navigateTo,
                            backdrop = backdrop,
                            isBlurActive = blurActive,
                            isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f,
                            showLabels = showLabels,
                            solidContainerColor = solidFloatingColor,
                        )
                    } else {
                        FloatingNavigationBar(
                            modifier = Modifier.appBackdropBlur(
                                backdrop = backdrop,
                                shape = floatingShape,
                                color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                                highlight = floatingHighlight,
                            ),
                            color = floatingColor,
                            cornerRadius = 50.dp,
                        ) {
                            navigationItems.forEachIndexed { index, item ->
                                FloatingTabItem(
                                    item = item,
                                    selected = mainPagerState.selectedPage == index,
                                    onClick = { navigateTo(index) },
                                    showLabel = showLabels,
                                )
                            }
                        }
                    }
                } else {
                    BlurredBar(backdrop = backdrop) {
                        NavigationBar(
                            color = regularBarColor,
                            mode = if (showLabels) {
                                NavigationBarDisplayMode.IconAndText
                            } else {
                                NavigationBarDisplayMode.IconOnly
                            },
                        ) {
                            navigationItems.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = mainPagerState.selectedPage == index,
                                    onClick = { navigateTo(index) },
                                    icon = item.icon,
                                    label = item.label,
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                if (useStaticBackdrop && backdrop != null) {
                    Spacer(Modifier.fillMaxSize().layerBackdrop(backdrop))
                }
                pagerContent(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (backdrop != null && !useStaticBackdrop) {
                                Modifier.layerBackdrop(backdrop)
                            } else {
                                Modifier
                            },
                        ),
                    padding,
                )
            }
        }
    }
}

@Composable
private fun MainTabPage(
    tab: AppTab,
    state: HyperLpaUiState,
    viewModel: HyperLpaViewModel,
    scrollBehavior: ScrollBehavior,
    outerPadding: PaddingValues,
    bluetoothReaderState: BluetoothReaderUiState,
    onRefreshReaders: () -> Unit,
) {
    val profilesLoading = state.isProfilesLoading
    val useStaticBackdrop = profilesLoading && tab == AppTab.PROFILES
    val backdrop = rememberAppBackdrop()
    val topBarColor = if (backdrop == null) MiuixTheme.colorScheme.surface else Color.Transparent
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(
                backdrop = backdrop,
                progressive = true,
                scrollBehavior = scrollBehavior,
            ) {
                AdaptiveTopAppBar(
                    title = stringResource(tab.titleRes),
                    color = topBarColor,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        when (tab) {
                            AppTab.PROFILES -> {
                                IconButton(onClick = { viewModel.navigate(AppRoute.DownloadProfile) }) {
                                    Icon(
                                        MiuixIcons.Download,
                                        contentDescription = stringResource(app.hyperlpa.R.string.action_download_profile),
                                    )
                                }
                            }
                            AppTab.NOTIFICATIONS -> {
                                IconButton(onClick = { viewModel.navigate(AppRoute.NotificationHistory) }) {
                                    Icon(
                                        MiuixIcons.Timer,
                                        contentDescription = stringResource(
                                            app.hyperlpa.R.string.notification_history_title,
                                        ),
                                    )
                                }
                            }
                            AppTab.TOOLS,
                            AppTab.SETTINGS,
                            -> Unit
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        val contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = outerPadding.calculateBottomPadding(),
        )
        val modifier = Modifier
            .fillMaxSize()
            .then(
                if (backdrop != null && !useStaticBackdrop) {
                    Modifier.layerBackdrop(backdrop)
                } else {
                    Modifier
                },
            )
        Box(Modifier.fillMaxSize()) {
            if (useStaticBackdrop && backdrop != null) {
                Spacer(Modifier.fillMaxSize().layerBackdrop(backdrop))
            }
            when (tab) {
                AppTab.PROFILES -> ProfilesScreen(
                    state = state,
                    modifier = modifier,
                    contentPadding = contentPadding,
                    scrollBehavior = scrollBehavior,
                    bluetoothReaderState = bluetoothReaderState,
                    onSearchChange = viewModel::updateSearchQuery,
                    onSelectReader = viewModel::connectReader,
                    onRefreshReaders = onRefreshReaders,
                    onOpenEuiccDetails = { viewModel.navigate(AppRoute.EuiccDetails) },
                    onOpenProfile = { profile -> viewModel.navigate(AppRoute.ProfileDetails(profile.iccid)) },
                    onEnableChange = viewModel::setProfileEnabled,
                    onSetPinned = viewModel::setProfilePinned,
                    onRename = viewModel::renameProfile,
                    onDownload = { viewModel.navigate(AppRoute.DownloadProfile) },
                    onRefresh = viewModel::refreshProfiles,
                )
                AppTab.NOTIFICATIONS -> NotificationsScreen(
                    state = state,
                    modifier = modifier,
                    contentPadding = contentPadding,
                    scrollBehavior = scrollBehavior,
                    onProcess = viewModel::processNotification,
                    onDelete = viewModel::deleteNotification,
                    onRefresh = viewModel::refreshProfiles,
                )
                AppTab.TOOLS -> ToolsScreen(
                    state = state,
                    modifier = modifier,
                    contentPadding = contentPadding,
                    scrollBehavior = scrollBehavior,
                    onNavigate = viewModel::navigate,
                )
                AppTab.SETTINGS -> SettingsScreen(
                    state = state,
                    modifier = modifier,
                    contentPadding = contentPadding,
                    scrollBehavior = scrollBehavior,
                    onNavigate = viewModel::navigate,
                )
            }
        }
    }
}

private val HyperLpaUiState.isProfilesLoading: Boolean
    get() = !lpa.initialized ||
        (lpa.operation is LpaOperation.DiscoveringReaders && lpa.profiles.isEmpty())

@Composable
private fun FloatingTabItem(
    item: NavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val baseColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> baseColor.copy(alpha = if (selected) 0.7f else 0.5f)
        selected -> baseColor
        else -> baseColor.copy(alpha = 0.6f)
    }
    Column(
        modifier = modifier
            .defaultMinSize(minWidth = if (showLabel) 56.dp else 48.dp, minHeight = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(horizontal = if (showLabel) 8.dp else 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = if (showLabel) null else item.label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        if (showLabel) {
            Text(
                text = item.label,
                color = tint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LastEnabledProfileDisableDialog(
    show: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(app.hyperlpa.R.string.last_profile_disable_title),
        summary = stringResource(app.hyperlpa.R.string.last_profile_disable_summary),
        onDismissRequest = onCancel,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = stringResource(app.hyperlpa.R.string.common_cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(app.hyperlpa.R.string.last_profile_disable_action),
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    textColor = MiuixTheme.colorScheme.error,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OperationFailureDialog(
    failure: app.hyperlpa.domain.model.OperationFailure?,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = failure != null,
        title = failure?.title,
        summary = failure?.message,
        onDismissRequest = onDismiss,
    ) {
        if (!failure?.diagnostic.isNullOrBlank()) {
            Text(
                text = failure.diagnostic.orEmpty(),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(12.dp))
        }
        TextButton(
            text = stringResource(app.hyperlpa.R.string.common_close),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onDismiss()
            },
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NonDismissibleProgressDialog(
    show: Boolean,
    title: String?,
    summary: String,
    content: @Composable () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = null,
    ) {
        // Miuix still animates predictive back when onDismissRequest is null. A handler composed
        // inside its content takes precedence and consumes back without changing dialog progress.
        val navigationEventState = rememberNavigationEventState(
            currentInfo = NavigationEventInfo.None,
        )
        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = show,
            onBackCompleted = {},
        )
        content()
    }
}

@Composable
private fun OperationProgressDialog(operation: LpaOperation) {
    val title = when (operation) {
        is LpaOperation.Deleting -> stringResource(app.hyperlpa.R.string.operation_deleting_profile)
        is LpaOperation.Renaming -> stringResource(app.hyperlpa.R.string.operation_renaming_profile)
        is LpaOperation.Downloading -> null
        is LpaOperation.ProcessingNotification -> stringResource(
            app.hyperlpa.R.string.operation_processing_notification,
        )
        is LpaOperation.Resetting -> stringResource(app.hyperlpa.R.string.operation_resetting_euicc)
        LpaOperation.Idle,
        is LpaOperation.Connecting,
        is LpaOperation.DiscoveringReaders,
        is LpaOperation.Refreshing,
        is LpaOperation.Switching,
        -> null
    }

    NonDismissibleProgressDialog(
        show = title != null,
        title = title,
        summary = stringResource(app.hyperlpa.R.string.operation_keep_connected_summary),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            InfiniteProgressIndicator()
        }
    }
}

@Composable
private fun ProfileInstallProgressDialog(operation: LpaOperation) {
    val download = operation as? LpaOperation.Downloading
    val show = download?.stage == DownloadStage.DOWNLOADING ||
        download?.stage == DownloadStage.FINALIZING ||
        download?.stage == DownloadStage.INSTALLING
    val sentBytes = download?.sentBytes
    val totalBytes = download?.totalBytes
    val progress = if (sentBytes != null && totalBytes != null && totalBytes > 0) {
        (sentBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

    NonDismissibleProgressDialog(
        show = show,
        title = stringResource(app.hyperlpa.R.string.install_progress_title),
        summary = stringResource(app.hyperlpa.R.string.install_progress_summary),
    ) {
        if (progress != null && sentBytes != null && totalBytes != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    height = 6.dp,
                )
                Spacer(Modifier.size(14.dp))
                Text(
                    text = stringResource(
                        app.hyperlpa.R.string.install_progress_percent,
                        (progress * 100).roundToInt(),
                    ),
                    style = MiuixTheme.textStyles.title2,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(
                        app.hyperlpa.R.string.install_progress_bytes,
                        sentBytes,
                        totalBytes,
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                InfiniteProgressIndicator()
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(app.hyperlpa.R.string.install_progress_preparing),
                    style = MiuixTheme.textStyles.body1,
                )
            }
        }
    }
}

@Stable
private class MainPagerState(
    val pagerState: PagerState,
    private val scope: CoroutineScope,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set
    var navigating by mutableStateOf(false)
        private set
    private var navigationJob: Job? = null

    fun animateToPage(target: Int) {
        if (target == selectedPage) return
        navigationJob?.cancel()
        selectedPage = target
        navigating = true
        navigationJob = scope.launch {
            val currentJob = coroutineContext.job
            try {
                pagerState.scroll(MutatePriority.UserInput) {
                    val distance = kotlin.math.abs(target - pagerState.currentPage).coerceAtLeast(2)
                    val duration = 100 * distance + 100
                    val pageSize = pagerState.layoutInfo.pageSize + pagerState.layoutInfo.pageSpacing
                    val pages = target - pagerState.currentPage - pagerState.currentPageOffsetFraction
                    val pixels = pages * pageSize
                    var consumed = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = pixels,
                        animationSpec = tween(durationMillis = duration, easing = EaseInOut),
                    ) { value, _ -> consumed += scrollBy(value - consumed) }
                }
                if (pagerState.currentPage != target) pagerState.scrollToPage(target)
            } finally {
                if (navigationJob == currentJob) {
                    navigating = false
                    selectedPage = pagerState.currentPage
                }
            }
        }
    }

    fun syncPage() {
        if (!navigating) selectedPage = pagerState.currentPage
    }
}

@Composable
private fun rememberMainPagerState(
    pagerState: PagerState,
    scope: CoroutineScope = rememberCoroutineScope(),
): MainPagerState = remember(pagerState, scope) { MainPagerState(pagerState, scope) }
