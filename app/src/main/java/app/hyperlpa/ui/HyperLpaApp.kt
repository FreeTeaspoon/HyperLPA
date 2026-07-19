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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import app.hyperlpa.data.settings.FloatingBottomBarStyle
import app.hyperlpa.data.settings.NavigationLabels
import app.hyperlpa.data.settings.NavigationStyle
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.ui.adaptive.AdaptiveTopAppBar
import app.hyperlpa.ui.adaptive.rememberIsWideWindow
import app.hyperlpa.ui.components.BlurredBar
import app.hyperlpa.ui.components.appBackdropBlur
import app.hyperlpa.ui.components.liquid.NzbLiquidGlassNavigationBar
import app.hyperlpa.ui.components.rememberAppBackdrop
import app.hyperlpa.ui.navigation.AppRoute
import app.hyperlpa.ui.navigation.AppTab
import app.hyperlpa.ui.navigation.title
import app.hyperlpa.ui.screens.AboutScreen
import app.hyperlpa.ui.screens.AdvancedSettingsScreen
import app.hyperlpa.ui.screens.AidManagerScreen
import app.hyperlpa.ui.screens.AppearanceSettingsScreen
import app.hyperlpa.ui.screens.BatchDownloadScreen
import app.hyperlpa.ui.screens.DownloadProfileScreen
import app.hyperlpa.ui.screens.EuiccDetailsScreen
import app.hyperlpa.ui.screens.LogsScreen
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
import app.hyperlpa.ui.screens.ToolsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
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
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HyperLpaApp(
    state: HyperLpaUiState,
    viewModel: HyperLpaViewModel,
    onScanQr: ((String?) -> Unit) -> Unit,
) {
    val currentState = rememberUpdatedState(state)
    val entries = remember {
        entryProvider<AppRoute> {
            entry<AppRoute.Shell> {
                MainShell(
                    state = currentState.value,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.ProfileDetails> { route ->
                ProfileDetailsScreen(
                    profile = currentState.value.profiles.firstOrNull { it.iccid == route.iccid },
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    onEnableChange = { enabled -> viewModel.setProfileEnabled(route.iccid, enabled) },
                    onRename = { nickname -> viewModel.renameProfile(route.iccid, nickname) },
                    onDelete = { viewModel.deleteProfile(route.iccid) },
                    onSetTags = { tags -> viewModel.setProfileTags(route.iccid, tags) },
                    onSetReminder = { label, instant -> viewModel.setProfileReminder(route.iccid, label, instant) },
                )
            }
            entry<AppRoute.DownloadProfile> {
                DownloadProfileScreen(
                    initialValue = currentState.value.activationCodeDraft,
                    imei = currentState.value.settings.imei,
                    busy = currentState.value.lpa.operation is LpaOperation.Downloading,
                    onBack = viewModel::navigateBack,
                    onValueChange = viewModel::setActivationCodeDraft,
                    onScanQr = onScanQr,
                    onDownload = viewModel::downloadProfile,
                )
            }
            entry<AppRoute.BatchDownload> {
                BatchDownloadScreen(
                    imei = currentState.value.settings.imei,
                    onBack = viewModel::navigateBack,
                    onDownload = viewModel::downloadProfile,
                )
            }
            entry<AppRoute.EuiccDetails> {
                EuiccDetailsScreen(
                    info = currentState.value.lpa.euiccInfo,
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    onReset = viewModel::resetEuiccMemory,
                )
            }
            entry<AppRoute.ReaderSettings> {
                ReaderSettingsScreen(
                    state = currentState.value,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.NotificationSettings> {
                NotificationSettingsScreen(
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.AppearanceSettings> {
                AppearanceSettingsScreen(
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.ProfileDisplaySettings> {
                ProfileDisplaySettingsScreen(
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.PrivacySettings> {
                PrivacySettingsScreen(
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.AdvancedSettings> {
                AdvancedSettingsScreen(
                    settings = currentState.value.settings,
                    onBack = viewModel::navigateBack,
                    viewModel = viewModel,
                )
            }
            entry<AppRoute.AidManager> {
                AidManagerScreen(
                    aids = currentState.value.settings.isdrAids,
                    onBack = viewModel::navigateBack,
                    onSave = viewModel::setIsdrAids,
                )
            }
            entry<AppRoute.TagManager> {
                TagManagerScreen(
                    profiles = currentState.value.profiles,
                    onBack = viewModel::navigateBack,
                    onSetTags = viewModel::setProfileTags,
                )
            }
            entry<AppRoute.ScheduledReminders> {
                ScheduledRemindersScreen(
                    profiles = currentState.value.profiles,
                    onBack = viewModel::navigateBack,
                    onClear = { profile ->
                        viewModel.setProfileReminder(
                            profile.iccid,
                            profile.nickname.ifBlank { profile.name },
                            null,
                        )
                    },
                )
            }
            entry<AppRoute.Statistics> {
                StatisticsScreen(
                    profiles = currentState.value.profiles,
                    notifications = currentState.value.lpa.notifications,
                    onBack = viewModel::navigateBack,
                )
            }
            entry<AppRoute.Logs> {
                LogsScreen(logs = currentState.value.lpa.logs, onBack = viewModel::navigateBack)
            }
            entry<AppRoute.About> {
                AboutScreen(onBack = viewModel::navigateBack)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = state.backStack,
                onBack = viewModel::navigateBack,
                entryProvider = entries,
                transitionEffects = NavDisplayTransitionEffects(
                    enableCornerClip = true,
                    dimAmount = 0.5f,
                    blockInputDuringTransition = true,
                    popDirectionFollowsSwipeEdge = false,
                ),
            )

            OperationProgressDialog(operation = state.lpa.operation)
            OperationFailureDialog(
                failure = state.lpa.failure,
                onDismiss = viewModel::clearFailure,
            )
        }
    }
}

@Composable
private fun MainShell(
    state: HyperLpaUiState,
    viewModel: HyperLpaViewModel,
) {
    val tabs = AppTab.entries
    val pagerState = rememberPagerState(initialPage = state.selectedTab.ordinal) { tabs.size }
    val mainPagerState = rememberMainPagerState(pagerState)
    val selectedTab by rememberUpdatedState(state.selectedTab)
    val scrollBehaviors = tabs.map { MiuixScrollBehavior() }

    LaunchedEffect(state.selectedTab) {
        if (mainPagerState.selectedPage != state.selectedTab.ordinal) {
            mainPagerState.animateToPage(state.selectedTab.ordinal)
        }
    }
    LaunchedEffect(pagerState.currentPage) { mainPagerState.syncPage() }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val tab = tabs[page]
                if (tab != selectedTab) viewModel.selectTab(tab)
            }
    }

    val navigationItems = remember {
        listOf(
            NavigationItem("Profiles", MiuixIcons.BankCards),
            NavigationItem("Notifications", MiuixIcons.Messages),
            NavigationItem("Tools", MiuixIcons.Tune),
            NavigationItem("Settings", MiuixIcons.Settings),
        )
    }
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
            )
        }
    }

    if (isWide) {
        Scaffold(containerColor = MiuixTheme.colorScheme.surface) { _ ->
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
        val backdrop = rememberAppBackdrop()
        val blurActive = backdrop != null
        val showLabels = state.settings.navigationLabels == NavigationLabels.ICON_AND_TEXT
        val regularBarColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
        val floatingShape = RoundedCornerShape(50.dp)
        val floatingColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
        val floatingHighlight = if (MiuixTheme.colorScheme.surface.luminance() < 0.5f) {
            Highlight.GlassStrokeMiddleDark
        } else {
            Highlight.GlassStrokeMiddleLight
        }

        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
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
            pagerContent(
                Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
                padding,
            )
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
) {
    val backdrop = rememberAppBackdrop()
    val topBarColor = if (backdrop == null) MiuixTheme.colorScheme.surface else Color.Transparent
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop = backdrop) {
                AdaptiveTopAppBar(
                    title = tab.title(),
                    color = topBarColor,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        when (tab) {
                            AppTab.PROFILES -> {
                                IconButton(onClick = { viewModel.navigate(AppRoute.DownloadProfile) }) {
                                    Icon(MiuixIcons.Download, contentDescription = "Download profile")
                                }
                                IconButton(onClick = viewModel::refreshProfiles) {
                                    Icon(MiuixIcons.Refresh, contentDescription = "Refresh profiles")
                                }
                            }
                            AppTab.NOTIFICATIONS -> IconButton(onClick = viewModel::refreshProfiles) {
                                Icon(MiuixIcons.Refresh, contentDescription = "Refresh notifications")
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
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
        when (tab) {
            AppTab.PROFILES -> ProfilesScreen(
                state = state,
                modifier = modifier,
                contentPadding = contentPadding,
                scrollBehavior = scrollBehavior,
                onSearchChange = viewModel::updateSearchQuery,
                onSelectReader = viewModel::connectReader,
                onRefreshReaders = viewModel::refreshReaders,
                onOpenEuiccDetails = { viewModel.navigate(AppRoute.EuiccDetails) },
                onOpenProfile = { profile -> viewModel.navigate(AppRoute.ProfileDetails(profile.iccid)) },
                onEnableChange = viewModel::setProfileEnabled,
                onDownload = { viewModel.navigate(AppRoute.DownloadProfile) },
            )
            AppTab.NOTIFICATIONS -> NotificationsScreen(
                state = state,
                modifier = modifier,
                contentPadding = contentPadding,
                scrollBehavior = scrollBehavior,
                onProcess = viewModel::processNotification,
                onDelete = viewModel::deleteNotification,
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
private fun OperationFailureDialog(
    failure: app.hyperlpa.domain.model.OperationFailure?,
    onDismiss: () -> Unit,
) {
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
            text = "Close",
            onClick = onDismiss,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OperationProgressDialog(operation: LpaOperation) {
    val title = when (operation) {
        is LpaOperation.Switching -> if (operation.enable) "Enabling profile" else "Disabling profile"
        is LpaOperation.Deleting -> "Deleting profile"
        is LpaOperation.Renaming -> "Renaming profile"
        is LpaOperation.Downloading -> "Downloading profile"
        is LpaOperation.ProcessingNotification -> "Processing notification"
        is LpaOperation.Resetting -> "Resetting eUICC"
        LpaOperation.Idle,
        is LpaOperation.Connecting,
        is LpaOperation.DiscoveringReaders,
        is LpaOperation.Refreshing,
        -> null
    }

    OverlayDialog(
        show = title != null,
        title = title,
        summary = "Keep the eUICC connected. This can take a few seconds.",
        onDismissRequest = null,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(size = 30.dp)
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
