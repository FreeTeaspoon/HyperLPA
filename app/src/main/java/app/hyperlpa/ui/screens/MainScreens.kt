package app.hyperlpa.ui.screens

import app.hyperlpa.ui.components.MishkaPageContent
import app.hyperlpa.ui.components.PageStart
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.settings.ProfileLayout
import app.hyperlpa.data.history.NotificationHistoryEntry
import app.hyperlpa.data.history.NotificationHistoryAction
import app.hyperlpa.data.history.NotificationHistoryStatus
import app.hyperlpa.data.history.NotificationHistoryTrigger
import app.hyperlpa.R
import app.hyperlpa.domain.model.LpaNotification
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.NotificationOperation
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.domain.model.takeUnicodeCodePoints
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.BluetoothReaderAvailability
import app.hyperlpa.ui.BluetoothReaderUiState
import app.hyperlpa.ui.adaptive.CenteredContent
import app.hyperlpa.ui.components.EmptyState
import app.hyperlpa.ui.components.ErrorState
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.LoadingState
import app.hyperlpa.ui.components.PageStateKind
import app.hyperlpa.ui.components.PageStateOverlay
import app.hyperlpa.ui.components.ProfilesStateIcon
import app.hyperlpa.ui.components.ReaderStateIcon
import app.hyperlpa.ui.components.RefreshHeaderTopGap
import app.hyperlpa.ui.components.inlinePageStateItem
import app.hyperlpa.ui.components.pageStateTransition
import app.hyperlpa.ui.components.ResolvedProfileArtwork
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.DialogActionRow
import app.hyperlpa.ui.components.TextInputDialog
import app.hyperlpa.ui.components.formatProfileDisplayName
import app.hyperlpa.ui.components.profileCountryFlag
import app.hyperlpa.ui.components.redactIdentifier
import app.hyperlpa.ui.components.rememberProfileArtworkBitmaps
import app.hyperlpa.ui.navigation.AppRoute
import app.hyperlpa.reminders.formatReminderDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet

@Composable
fun ProfilesScreen(
    state: HyperLpaUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    bluetoothReaderState: BluetoothReaderUiState,
    onSearchChange: (String) -> Unit,
    onSelectReader: (String) -> Unit,
    onResolveReaderAccess: () -> Unit,
    onOpenEuiccDetails: () -> Unit,
    onOpenProfile: (ProfileInfo) -> Unit,
    onEnableChange: (String, Boolean) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onRename: (String, String) -> Unit,
    refreshPending: Boolean = false,
    onRefresh: () -> Unit,
) {
    // Keep both layout positions alive while switching between list and waterfall.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // Keep the selected profile in a holder so only the overlay reads its value. A long press
    // must not invalidate the home list while the bottom-sheet entrance animation is running.
    val profileActionsState = remember { mutableStateOf<ProfileInfo?>(null) }
    val profiles = state.profiles
    // The view model serializes switch requests, so another profile stays tappable while the
    // active eUICC command finishes its refresh and reconnect tail.
    val profileSwitchLocked = state.lpa.operation is LpaOperation.Connecting
    val artworkLoadState = rememberProfileArtworkBitmaps(
        profiles = profiles,
        cloudIcons = state.operatorIcons,
        enabled = state.settings.showProfileIconOnHome,
    )
    val initialArtworkReady = artworkLoadState.ready && state.profileEnrichmentReady
    // Artwork may hold back a list only while the page already shows loading, as on the first
    // load or for a reader without a cached card. A visible list, such as a cached card after a
    // reader switch, is replaced at once; its artwork is normally already in memory.
    val lastPageState = remember { LastPageState() }
    val awaitInitialArtwork = state.lpa.profiles.isNotEmpty() &&
        !initialArtworkReady &&
        lastPageState.kind == PageStateKind.LOADING
    val hasNoSearchResults = state.searchQuery.isNotBlank() &&
        state.lpa.profiles.isNotEmpty() &&
        profiles.isEmpty()
    val loadingMessage = when (val operation = state.lpa.operation) {
        is LpaOperation.Connecting -> stringResource(
            R.string.profiles_connecting_reader,
            operation.readerName,
        )
        else -> stringResource(
            if (state.lpa.profiles.isEmpty()) {
                R.string.reader_loading
            } else {
                R.string.operation_reading_profiles
            },
        )
    }
    val usesNearbyDevicesPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val noReaderTitle = when (bluetoothReaderState.availability) {
        BluetoothReaderAvailability.PERMISSION_REQUIRED -> stringResource(
            app.hyperlpa.R.string.reader_bluetooth_permission_required_title,
        )
        BluetoothReaderAvailability.BLUETOOTH_OFF -> stringResource(
            app.hyperlpa.R.string.reader_bluetooth_off_title,
        )
        else -> stringResource(app.hyperlpa.R.string.reader_none_title)
    }
    val noReaderMessage = when (bluetoothReaderState.availability) {
        BluetoothReaderAvailability.PERMISSION_REQUIRED -> stringResource(
            if (usesNearbyDevicesPermission) {
                app.hyperlpa.R.string.reader_bluetooth_permission_required_message
            } else {
                app.hyperlpa.R.string.reader_bluetooth_location_required_message
            },
        )
        BluetoothReaderAvailability.BLUETOOTH_OFF -> stringResource(
            app.hyperlpa.R.string.reader_bluetooth_off_message,
        )
        else -> stringResource(app.hyperlpa.R.string.reader_none_message)
    }
    // Keep the first presentation synchronized with artwork, then apply later artwork updates
    // in place without replacing the already-visible profile list with a loading state.
    val pageState = profilesPageState(state.lpa, profiles, awaitInitialArtwork, refreshPending)
    SideEffect { lastPageState.kind = pageState }
    val isRefreshing = refreshPending || state.lpa.operation is LpaOperation.Refreshing
    var hideStateDuringPull by remember { mutableStateOf(false) }
    var headerPresented by remember { mutableStateOf(false) }
    LaunchedEffect(pageState) {
        if (pageState != PageStateKind.LOADING) headerPresented = true
    }
    // A reader switch keeps the reader selector and shows its progress below it.
    val keepHeaderWhileLoading = headerPresented &&
        pageState == PageStateKind.LOADING &&
        state.lpa.readers.isNotEmpty() &&
        (state.lpa.operation is LpaOperation.Connecting || awaitInitialArtwork)
    val stateModel = when (pageState) {
        PageStateKind.LOADING -> ProfilesStateModel(kind = PageStateKind.LOADING, title = loadingMessage)
        PageStateKind.ERROR -> ProfilesStateModel(
            kind = PageStateKind.ERROR,
            title = noReaderTitle,
            message = noReaderMessage,
            icon = ReaderStateIcon,
        )
        PageStateKind.EMPTY -> ProfilesStateModel(
            kind = PageStateKind.EMPTY,
            title = stringResource(
                when {
                    state.lpa.selectedReader == null -> R.string.profiles_choose_reader
                    hasNoSearchResults -> R.string.profiles_none_found
                    else -> R.string.profiles_none_installed
                },
            ),
            message = stringResource(
                when {
                    state.lpa.selectedReader == null -> R.string.profiles_choose_reader_message
                    hasNoSearchResults -> R.string.profiles_search_empty_message
                    else -> R.string.profiles_none_installed_message
                },
            ),
            icon = when {
                state.lpa.selectedReader == null -> ReaderStateIcon
                hasNoSearchResults -> MiuixIcons.Search
                else -> ProfilesStateIcon
            },
        )
        PageStateKind.CONTENT -> null
    }
    val listTopPadding = contentPadding.calculateTopPadding() + ProfilesFirstCardGap
    val listBottomPadding = contentPadding.calculateBottomPadding() + 24.dp

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = {
                // A pull on the no-reader state also asks for missing Bluetooth access, which must
                // never stop the other reader providers from refreshing.
                if (pageState == PageStateKind.ERROR) onResolveReaderAccess()
                onRefresh()
            },
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + RefreshHeaderTopGap,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            topAppBarScrollBehavior = scrollBehavior,
            onPullProgress = { hideStateDuringPull = it > 0.01f },
        ) {
            CenteredContent { sidePadding ->
                if (pageState == PageStateKind.LOADING && !keepHeaderWhileLoading) {
                    // Same area as the list's state item, so loading lines up with what follows it.
                    LoadingState(
                        message = loadingMessage,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = listTopPadding, bottom = listBottomPadding),
                    )
                } else if (
                    state.settings.profileLayout == ProfileLayout.WATERFALL &&
                    pageState == PageStateKind.CONTENT &&
                    profiles.isNotEmpty()
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(280.dp),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        overscrollEffect = null,
                        contentPadding = PaddingValues(
                            start = sidePadding,
                            end = sidePadding,
                            top = contentPadding.calculateTopPadding() + ProfilesFirstCardGap,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ProfilesHeader(
                                state = state,
                                onSearchChange = onSearchChange,
                                onSelectReader = onSelectReader,
                                onOpenEuiccDetails = onOpenEuiccDetails,
                            )
                        }
                        items(profiles, key = ProfileInfo::iccid) { profile ->
                            ProfileCard(
                                profile = profile,
                                artworkBitmap = artworkLoadState.bitmaps[profile.iccid],
                                state = state,
                                switchEnabled = !profileSwitchLocked,
                                onOpen = { onOpenProfile(profile) },
                                onEnableChange = { enabled -> onEnableChange(profile.iccid, enabled) },
                                onLongPress = { profileActionsState.value = profile },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        overscrollEffect = null,
                        contentPadding = PaddingValues(
                            start = sidePadding,
                            end = sidePadding,
                            top = listTopPadding,
                            bottom = listBottomPadding,
                        ),
                    ) {
                        item(key = "header") {
                            ProfilesHeader(
                                state = state,
                                onSearchChange = onSearchChange,
                                onSelectReader = onSelectReader,
                                onOpenEuiccDetails = onOpenEuiccDetails,
                            )
                        }
                        if (pageState == PageStateKind.CONTENT) {
                            items(profiles, key = ProfileInfo::iccid) { profile ->
                                ProfileCard(
                                    profile = profile,
                                    artworkBitmap = artworkLoadState.bitmaps[profile.iccid],
                                    state = state,
                                    switchEnabled = !profileSwitchLocked,
                                    onOpen = { onOpenProfile(profile) },
                                    onEnableChange = { enabled -> onEnableChange(profile.iccid, enabled) },
                                    onLongPress = { profileActionsState.value = profile },
                                )
                            }
                        }
                        if (stateModel != null) {
                            inlinePageStateItem(key = ProfilesStateKey, listState = listState) { fillModifier ->
                                AnimatedContent(
                                    // The refresh indicator reports progress itself during a pull.
                                    targetState = stateModel.takeUnless { isRefreshing || hideStateDuringPull },
                                    modifier = Modifier.fillMaxWidth(),
                                    transitionSpec = { pageStateTransition() },
                                    contentAlignment = Alignment.TopCenter,
                                    contentKey = { it?.kind to it?.title },
                                    label = "profiles-state",
                                ) { model ->
                                    if (model != null) when (model.kind) {
                                        PageStateKind.LOADING -> LoadingState(
                                            message = model.title,
                                            modifier = fillModifier,
                                        )
                                        PageStateKind.ERROR -> ErrorState(
                                            title = model.title,
                                            message = model.message,
                                            modifier = fillModifier,
                                            icon = model.icon,
                                        )
                                        PageStateKind.EMPTY -> EmptyState(
                                            title = model.title,
                                            message = model.message,
                                            modifier = fillModifier,
                                            icon = model.icon,
                                        )
                                        PageStateKind.CONTENT -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    ProfileActionsOverlay(
        profileState = profileActionsState,
        profileSwitchLocked = profileSwitchLocked,
        onEnableChange = onEnableChange,
        onSetPinned = onSetPinned,
        onRename = onRename,
    )
}

internal fun profilesPageState(
    lpa: LpaRepositoryState,
    profiles: List<ProfileInfo>,
    awaitInitialArtwork: Boolean = false,
    refreshPending: Boolean = false,
): PageStateKind = when {
    lpa.operation is LpaOperation.Connecting && !lpa.readerSnapshotPendingRefresh ->
        PageStateKind.LOADING
    // A pull without a reader rediscovers readers; its indicator already shows that progress.
    refreshPending && lpa.operation is LpaOperation.DiscoveringReaders && lpa.profiles.isEmpty() ->
        PageStateKind.CONTENT
    !lpa.initialized ||
        (lpa.operation is LpaOperation.DiscoveringReaders && lpa.profiles.isEmpty()) -> PageStateKind.LOADING
    awaitInitialArtwork && lpa.profiles.isNotEmpty() -> PageStateKind.LOADING
    lpa.readers.isEmpty() -> PageStateKind.ERROR
    lpa.selectedReader == null -> PageStateKind.EMPTY
    // An empty list is unconfirmed until the refresh finishes; the pull-to-refresh indicator
    // already shows its progress, so no page state is drawn below the header meanwhile.
    lpa.operation is LpaOperation.Refreshing && lpa.profiles.isEmpty() -> PageStateKind.CONTENT
    profiles.isEmpty() -> PageStateKind.EMPTY
    else -> PageStateKind.CONTENT
}

private const val ProfilesStateKey = "state"

/** The page state of the previous composition. It never needs to trigger a recomposition. */
private class LastPageState(var kind: PageStateKind = PageStateKind.LOADING)

private data class ProfilesStateModel(
    val kind: PageStateKind,
    val title: String,
    val message: String = "",
    val icon: ImageVector = MiuixIcons.Notes,
)

@Composable
private fun ProfileActionsOverlay(
    profileState: MutableState<ProfileInfo?>,
    profileSwitchLocked: Boolean,
    onEnableChange: (String, Boolean) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onRename: (String, String) -> Unit,
) {
    var renameProfile by remember { mutableStateOf<ProfileInfo?>(null) }
    var pendingRenameProfile by remember { mutableStateOf<ProfileInfo?>(null) }
    var isClosing by remember { mutableStateOf(false) }
    val profile = profileState.value

    LaunchedEffect(profile?.iccid) {
        if (profile != null) isClosing = false
    }

    OverlayBottomSheet(
        show = profile != null && !isClosing,
        title = stringResource(R.string.profile_actions_title),
        onDismissRequest = { isClosing = true },
        onDismissFinished = {
            val nextRenameProfile = pendingRenameProfile
            pendingRenameProfile = null
            profileState.value = null
            isClosing = false
            if (nextRenameProfile != null) renameProfile = nextRenameProfile
        },
    ) {
        profile?.let { selectedProfile ->
            val isEnabled = selectedProfile.state == ProfileState.ENABLED
            Column {
                ArrowPreference(
                    title = stringResource(
                        if (selectedProfile.isPinned) {
                            R.string.profile_action_unpin
                        } else {
                            R.string.profile_action_pin
                        },
                    ),
                    summary = stringResource(
                        if (selectedProfile.isPinned) {
                            R.string.profile_action_unpin_summary
                        } else {
                            R.string.profile_action_pin_summary
                        },
                    ),
                    onClick = {
                        onSetPinned(selectedProfile.iccid, !selectedProfile.isPinned)
                        isClosing = true
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.profile_rename),
                    summary = stringResource(R.string.profile_rename_summary),
                    onClick = {
                        pendingRenameProfile = selectedProfile
                        isClosing = true
                    },
                )
                ArrowPreference(
                    title = stringResource(
                        if (isEnabled) {
                            R.string.profile_action_disable
                        } else {
                            R.string.profile_action_enable
                        },
                    ),
                    summary = stringResource(
                        if (isEnabled) {
                            R.string.profile_action_disable_summary
                        } else {
                            R.string.profile_action_enable_summary
                        },
                    ),
                    enabled = !profileSwitchLocked,
                    onClick = {
                        onEnableChange(selectedProfile.iccid, !isEnabled)
                        isClosing = true
                    },
                )
                ProfileActionSheetFooterSpacer()
            }
        }
    }

    ProfileRenameDialog(
        show = renameProfile != null,
        nickname = renameProfile?.nickname.orEmpty(),
        onDismiss = { renameProfile = null },
        onRename = { nickname ->
            renameProfile?.let { selectedProfile -> onRename(selectedProfile.iccid, nickname) }
            renameProfile = null
        },
    )
}

@Composable
internal fun ProfileRenameDialog(
    show: Boolean,
    nickname: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    TextInputDialog(
        show = show,
        title = stringResource(R.string.profile_rename),
        summary = stringResource(R.string.profile_rename_summary),
        label = stringResource(R.string.profile_name),
        initialValue = nickname,
        confirmText = stringResource(R.string.profile_rename_action),
        allowBlank = true,
        inputFilter = { it.takeUnicodeCodePoints(64) },
        onDismiss = onDismiss,
        onConfirm = { value -> onRename(value.trim()) },
    )
}

@Composable
private fun ProfilesHeader(
    state: HyperLpaUiState,
    onSearchChange: (String) -> Unit,
    onSelectReader: (String) -> Unit,
    onOpenEuiccDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enrichmentLoading = !state.profileEnrichmentReady && state.lpa.profiles.isNotEmpty()
    val enrichmentEid = state.lpa.euiccInfo?.eid
    var showEnrichmentLoading by remember(enrichmentEid) { mutableStateOf(false) }
    LaunchedEffect(enrichmentLoading, enrichmentEid) {
        showEnrichmentLoading = false
        if (enrichmentLoading) {
            delay(ProfileEnrichmentLoadingDelayMillis)
            showEnrichmentLoading = true
        }
    }
    // A reader without a cached card has no EID until it opens. Holding the row's place keeps
    // the rows and the loading state below it from shifting when the EID arrives.
    val connecting = state.lpa.operation is LpaOperation.Connecting
    Column(modifier = modifier.fillMaxWidth()) {
        val showEid = state.settings.showEidOnHome && (state.lpa.euiccInfo != null || connecting)
        val showReaderSelector = state.settings.showReaderSelectorOnHome && state.lpa.readers.isNotEmpty()
        val cardName = state.currentEuiccName
        if (showReaderSelector || showEid) {
            GroupedCard {
                if (showReaderSelector) {
                    // A reader without a cached card is unselected until it opens; name it meanwhile.
                    val shownReader = state.lpa.selectedReader
                        ?: (state.lpa.operation as? LpaOperation.Connecting)?.let { connecting ->
                            state.lpa.readers.firstOrNull { it.name == connecting.readerName }
                        }
                    val selectedIndex = state.lpa.readers.indexOfFirst { it.id == shownReader?.id }
                    OverlayDropdownPreference(
                        title = stringResource(R.string.profiles_active_reader),
                        enabled = state.lpa.operation is LpaOperation.Idle,
                        summary = shownReader?.detail
                            ?: stringResource(R.string.profiles_select_reader),
                        items = state.lpa.readers.map { it.name },
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = { index ->
                            state.lpa.readers.getOrNull(index)?.id?.let(onSelectReader)
                        },
                    )
                }
                val info = state.lpa.euiccInfo
                if (showEid && info == null) {
                    ArrowPreference(
                        title = stringResource(R.string.euicc_eid),
                        summary = stringResource(R.string.euicc_eid_reading),
                    )
                } else if (showEid && info != null) {
                    val redactedEid = redactIdentifier(
                        value = info.eid,
                        mode = state.settings.eidRedaction,
                    )
                    ArrowPreference(
                        title = cardName ?: stringResource(R.string.euicc_eid),
                        summary = if (cardName == null) {
                            redactedEid
                        } else {
                            stringResource(R.string.euicc_eid_named_summary, redactedEid)
                        },
                        onClick = onOpenEuiccDetails,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = state.settings.showProfileSearch && (state.lpa.selectedReader != null || connecting),
        ) {
            Column {
                TextField(
                    value = state.searchQuery,
                    onValueChange = { onSearchChange(it.take(MaxSearchQueryCharacters)) },
                    label = stringResource(R.string.profiles_search),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = MiuixIcons.Search,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        AnimatedVisibility(visible = showEnrichmentLoading) {
            Text(
                text = stringResource(R.string.profiles_optional_data_loading),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
            )
        }
    }
}

private const val ProfileEnrichmentLoadingDelayMillis = 500L

// The same 12 dp card start MishkaPageContent gives other pages; the refresh header offset assumes it.
private val ProfilesFirstCardGap = 12.dp

@Composable
private fun ProfileCard(
    profile: ProfileInfo,
    artworkBitmap: Bitmap?,
    state: HyperLpaUiState,
    switchEnabled: Boolean,
    onOpen: () -> Unit,
    onEnableChange: (Boolean) -> Unit,
    onLongPress: () -> Unit,
) {
    val isEnabled = profile.state == ProfileState.ENABLED
    val fallbackName = stringResource(R.string.profile_default_name)
    val displayName = remember(
        profile,
        state.settings.phoneFormatStrategy,
        state.settings.profileNameRedaction,
        fallbackName,
    ) {
        formatProfileDisplayName(
            profile = profile,
            strategy = state.settings.phoneFormatStrategy,
            fallback = fallbackName,
            redactionMode = state.settings.profileNameRedaction,
        )
    }
    val countryFlag = if (state.settings.showProfileCountryFlagOnHome) {
        profileCountryFlag(profile)
    } else {
        null
    }
    val unknownOperator = stringResource(R.string.profile_unknown_operator)
    val profileTags = if (state.settings.showProfileTagsOnHome) {
        profile.tags.filter(String::isNotBlank).sortedBy(String::lowercase)
    } else {
        emptyList()
    }
    val profileBytes = if (state.settings.showProfileSizeOnHome) {
        profile.estimatedBytes?.takeIf { it > 0 }
    } else {
        null
    }
    val profileSizeText = profileBytes?.let { bytes ->
        formatProfileBytes(bytes).let { formatted ->
            if (profile.sizeIsEstimated) {
                stringResource(R.string.profile_size_estimated, formatted)
            } else {
                formatted
            }
        }
    }
    val profileIccidText = if (state.settings.showProfileIccidOnHome) {
        redactIdentifier(profile.iccid, state.settings.iccidRedaction)
    } else {
        null
    }
    val reminderAt = profile.reminderAt
        ?.takeIf { state.settings.showProfileRemindersOnHome }
    val showProfileName = state.settings.showProfileNameOnHome
    val showProfileProvider = state.settings.showProfileProviderOnHome
    val hasProfileInfoRow = showProfileName ||
        profileIccidText != null ||
        showProfileProvider
    val inlineProfileSizeText = profileSizeText?.takeIf {
        profileTags.isEmpty() && reminderAt == null && hasProfileInfoRow
    }
    val footerProfileSizeText = if (inlineProfileSizeText == null) {
        profileSizeText
    } else {
        null
    }
    val cardDescription = buildList {
        add(stringResource(R.string.profile_open_named, displayName.fullText))
        if (profileTags.isNotEmpty()) {
            add(stringResource(R.string.profile_card_tags_description, profileTags.joinToString()))
        }
        reminderAt?.let {
            add(
                stringResource(
                    R.string.profile_card_reminder_description,
                    it.formatReminderDate(),
                ),
            )
        }
        profileSizeText?.let { sizeText ->
            add(stringResource(R.string.profile_card_size_description, sizeText))
        }
        profileIccidText?.let { iccidText ->
            add(
                stringResource(
                    R.string.profile_card_iccid_description,
                    iccidText,
                ),
            )
        }
    }.joinToString(". ")
    val switching = state.lpa.operation as? LpaOperation.Switching
    val switchDescription = if (switching != null && switching.iccid == profile.iccid) {
        stringResource(
            if (switching.enable) {
                R.string.operation_enabling_profile
            } else {
                R.string.operation_disabling_profile
            },
        )
    } else {
        stringResource(
            if (isEnabled) R.string.profile_disable_named else R.string.profile_enable_named,
            displayName.fullText,
        )
    }
    val profileActionsDescription = stringResource(R.string.profile_actions_title)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp)
            .defaultMinSize(minHeight = 48.dp)
            .semantics {
                contentDescription = cardDescription
                customActions = listOf(
                    CustomAccessibilityAction(profileActionsDescription) {
                        onLongPress()
                        true
                    },
                )
            },
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onOpen,
        onLongPress = onLongPress,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.settings.showProfileIconOnHome) {
                ResolvedProfileArtwork(
                    profile = profile,
                    bitmap = artworkBitmap,
                    isEnabled = isEnabled,
                )
            }
            Column(Modifier.weight(1f)) {
                if (showProfileName) {
                    val isLastInfoRow = profileIccidText == null && !showProfileProvider
                    if (isLastInfoRow && inlineProfileSizeText != null) {
                        ProfileCardInfoRow(text = inlineProfileSizeText) {
                            ProfileCardName(
                                text = displayName.fullText,
                                countryFlag = countryFlag,
                            )
                        }
                    } else {
                        ProfileCardName(
                            text = displayName.fullText,
                            countryFlag = countryFlag,
                        )
                    }
                }
                if (profileIccidText != null) {
                    val isLastInfoRow = !showProfileProvider
                    if (isLastInfoRow && inlineProfileSizeText != null) {
                        ProfileCardInfoRow(text = inlineProfileSizeText) {
                            Text(
                                text = profileIccidText,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = profileIccidText,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showProfileProvider) {
                    if (inlineProfileSizeText != null) {
                        ProfileCardInfoRow(text = inlineProfileSizeText) {
                            Text(
                                text = profile.providerName.ifBlank { unknownOperator },
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = profile.providerName.ifBlank { unknownOperator },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (profileTags.isNotEmpty() || reminderAt != null || footerProfileSizeText != null) {
                    Spacer(
                        Modifier.height(
                            if (profileTags.isNotEmpty()) 6.dp else 8.dp,
                        ),
                    )
                    ProfileCardMetadataFooter(
                        tags = profileTags,
                        reminderAt = reminderAt,
                        profileSizeText = footerProfileSizeText,
                    )
                }
            }
            if (state.settings.showProfileSwitchOnHome) {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onEnableChange,
                    enabled = switchEnabled,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .semantics {
                            contentDescription = switchDescription
                        },
                )
            }
        }
    }
}

@Composable
private fun ProfileCardName(
    text: String,
    countryFlag: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        countryFlag?.let { flag ->
            Text(
                text = flag,
                style = MiuixTheme.textStyles.body1,
                maxLines = 1,
            )
        }
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProfileCardInfoRow(
    text: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            content()
        }
        ProfileSizeMeta(text = text)
    }
}

@Composable
private fun ProfileTagsRow(
    tags: List<String>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Keep tags to one compact row. Very narrow cards show one tag; wider cards show two,
        // followed by a compact overflow chip when necessary.
        val visibleTagCount = if (maxWidth < 210.dp) 1 else 2
        val visibleTags = tags.take(visibleTagCount)
        val hiddenTagCount = (tags.size - visibleTags.size).coerceAtLeast(0)
        val maxTagWidth = when {
            maxWidth < 180.dp -> 88.dp
            maxWidth < 260.dp -> 88.dp
            maxWidth < 320.dp -> 112.dp
            else -> 128.dp
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visibleTags.forEach { tag ->
                ProfileTagChip(
                    text = tag,
                    modifier = Modifier.widthIn(max = maxTagWidth),
                )
            }
            if (hiddenTagCount > 0) {
                ProfileTagChip(text = stringResource(R.string.profile_tags_overflow, hiddenTagCount))
            }
        }
    }
}

@Composable
private fun ProfileTagChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(
                color = MiuixTheme.colorScheme.secondaryContainerVariant,
                shape = RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
private fun ProfileCardMetadataFooter(
    tags: List<String>,
    reminderAt: Instant?,
    profileSizeText: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tags.isNotEmpty()) {
            ProfileTagsRow(
                tags = tags,
                modifier = Modifier.weight(1f),
            )
        }
        if (reminderAt != null) {
            ProfileReminderChip(
                dateText = reminderAt.formatReminderDate(),
                fullDateText = reminderAt.formatReminderDate(),
            )
        }
        if (profileSizeText != null) {
            Spacer(Modifier.weight(1f))
            ProfileSizeMeta(text = profileSizeText)
        }
    }
}

@Composable
private fun ProfileReminderChip(
    dateText: String,
    fullDateText: String,
    modifier: Modifier = Modifier,
) {
    val reminderDescription = stringResource(
        R.string.profile_card_reminder_description,
        fullDateText,
    )
    Row(
        modifier = modifier
            .semantics {
                contentDescription = reminderDescription
            }
            .background(
                color = MiuixTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dateText,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProfileSizeMeta(text: String) {
    Row(
        modifier = Modifier.widthIn(max = 104.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.File,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProfileActionSheetFooterSpacer() {
    val systemBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
    Spacer(Modifier.height(systemBarPadding + 16.dp))
}

@Composable
private fun formatProfileBytes(bytes: Long): String {
    if (bytes < 1_024) return stringResource(R.string.size_bytes, bytes)
    val kib = bytes / 1_024.0
    if (kib < 1_024) {
        return stringResource(
            if (kib >= 100) R.string.size_kibibytes_whole else R.string.size_kibibytes_decimal,
            kib,
        )
    }
    val mib = kib / 1_024.0
    return stringResource(
        if (mib >= 100) R.string.size_mebibytes_whole else R.string.size_mebibytes_decimal,
        mib,
    )
}

@Composable
fun NotificationsScreen(
    state: HyperLpaUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onProcess: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    refreshPending: Boolean = false,
    onRefresh: () -> Unit,
) {
    val profileFallbackName = stringResource(R.string.profile_default_name)
    val profilesByIccid = remember(state.profiles) {
        state.profiles.associateBy(ProfileInfo::iccid)
    }
    var selectedNotification by remember { mutableStateOf<LpaNotification?>(null) }
    var showNotificationDetails by remember { mutableStateOf(false) }
    var showNotificationActions by remember { mutableStateOf(false) }
    var openDetailsAfterActions by remember { mutableStateOf(false) }
    var confirmDeleteAfterActions by remember { mutableStateOf(false) }
    var deleteNotification by remember { mutableStateOf<LpaNotification?>(null) }
    val openActions: (LpaNotification) -> Unit = { notification ->
        selectedNotification = notification
        showNotificationActions = true
    }
    val isRefreshing = refreshPending || state.lpa.operation is LpaOperation.Refreshing
    var hideStateDuringPull by remember { mutableStateOf(false) }
    val hasReader = state.lpa.selectedReader != null

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + RefreshHeaderTopGap,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            topAppBarScrollBehavior = scrollBehavior,
            onPullProgress = { hideStateDuringPull = it > 0.01f },
        ) {
            val pageContent = MishkaPageContent {
                if (hasReader && state.lpa.notifications.isNotEmpty()) {
                    items(state.lpa.notifications, key = LpaNotification::sequenceNumber) { notification ->
                        val profile = profilesByIccid[notification.iccid]
                        NotificationCard(
                            notification = notification,
                            profileName = profile?.let {
                                formatProfileDisplayName(
                                    it,
                                    state.settings.phoneFormatStrategy,
                                    profileFallbackName,
                                    state.settings.profileNameRedaction,
                                ).fullText
                            },
                            providerName = profile?.providerName?.takeIf(String::isNotBlank),
                            onClick = { openActions(notification) },
                        )
                    }
                }
            }
            CenteredContent { sidePadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    overscrollEffect = null,
                    contentPadding = PaddingValues(
                        start = sidePadding,
                        end = sidePadding,
                        top = contentPadding.calculateTopPadding() + pageContent.topPadding,
                        bottom = contentPadding.calculateBottomPadding() + 24.dp,
                    ),
                    content = pageContent.content,
                )
            }
        }
        // ERROR stands for the missing reader, so it cross-fades with the nothing-pending state.
        val emptyState = when {
            isRefreshing || hideStateDuringPull -> PageStateKind.CONTENT
            !hasReader -> PageStateKind.ERROR
            state.lpa.notifications.isEmpty() -> PageStateKind.EMPTY
            else -> PageStateKind.CONTENT
        }
        PageStateOverlay(
            state = emptyState,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
        ) { kind ->
            val noReader = kind == PageStateKind.ERROR
            EmptyState(
                title = stringResource(
                    if (noReader) R.string.notifications_no_reader else R.string.notifications_none_pending,
                ),
                message = stringResource(
                    if (noReader) R.string.notifications_no_reader_message
                    else R.string.notifications_none_pending_message,
                ),
                icon = if (noReader) ReaderStateIcon else MiuixIcons.Community,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    selectedNotification?.let { notification ->
        val profile = profilesByIccid[notification.iccid]
        NotificationActionsSheet(
            show = showNotificationActions,
            onSend = {
                showNotificationActions = false
                onProcess(notification.sequenceNumber)
            },
            onViewDetails = {
                openDetailsAfterActions = true
                showNotificationActions = false
            },
            onDelete = {
                confirmDeleteAfterActions = true
                showNotificationActions = false
            },
            onDismissRequest = { showNotificationActions = false },
            onDismissFinished = {
                when {
                    openDetailsAfterActions -> {
                        openDetailsAfterActions = false
                        showNotificationDetails = true
                    }
                    confirmDeleteAfterActions -> {
                        confirmDeleteAfterActions = false
                        deleteNotification = notification
                        selectedNotification = null
                    }
                    else -> selectedNotification = null
                }
            },
        )
        NotificationDetailsSheet(
            notification = notification,
            profile = profile,
            profileName = profile?.let {
                formatProfileDisplayName(
                    it,
                    state.settings.phoneFormatStrategy,
                    profileFallbackName,
                    state.settings.profileNameRedaction,
                ).fullText
            },
            iccid = notification.iccid,
            show = showNotificationDetails,
            onDismissRequest = { showNotificationDetails = false },
            onDismissFinished = { selectedNotification = null },
        )
    }

    OverlayDialog(
        show = deleteNotification != null,
        title = stringResource(R.string.notifications_delete_title),
        summary = stringResource(R.string.notifications_delete_summary),
        onDismissRequest = { deleteNotification = null },
    ) {
        DialogActionRow(
            onCancel = { deleteNotification = null },
            confirmText = stringResource(R.string.notifications_option_delete),
            destructive = true,
            onConfirm = {
                deleteNotification?.let { onDelete(it.sequenceNumber) }
                deleteNotification = null
            },
        )
    }
}

@Composable
private fun NotificationActionsSheet(
    show: Boolean,
    onSend: () -> Unit,
    onViewDetails: () -> Unit,
    onDelete: () -> Unit,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.notifications_options_title),
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        Column {
            ArrowPreference(
                title = stringResource(R.string.notifications_option_send),
                summary = stringResource(R.string.notifications_option_send_summary),
                onClick = onSend,
            )
            ArrowPreference(
                title = stringResource(R.string.notification_history_option_details),
                summary = stringResource(R.string.notifications_option_details_summary),
                onClick = onViewDetails,
            )
            ArrowPreference(
                title = stringResource(R.string.notifications_option_delete),
                summary = stringResource(R.string.notifications_option_delete_summary),
                titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                onClick = onDelete,
            )
            ProfileActionSheetFooterSpacer()
        }
    }
}

@Composable
fun NotificationHistoryScreen(
    state: HyperLpaUiState,
    onBack: () -> Unit,
    onDeleteHistoryEntry: (NotificationHistoryEntry) -> Unit,
    onResendNotification: (NotificationHistoryEntry) -> Unit,
) {
    var selectedHistoryEntry by remember { mutableStateOf<NotificationHistoryEntry?>(null) }
    var showHistoryDetails by remember { mutableStateOf(false) }
    var showHistoryActions by remember { mutableStateOf(false) }
    var openDetailsAfterActions by remember { mutableStateOf(false) }
    var deleteHistoryEntry by remember { mutableStateOf<NotificationHistoryEntry?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val historyItems = remember(state.notificationHistory) {
        val seen = HashMap<Long, Int>()
        state.notificationHistory.asReversed().map { entry ->
            val occurrence = seen.merge(entry.timestampEpochMillis, 1, Int::plus)
            "${entry.timestampEpochMillis}:$occurrence" to entry
        }
    }

    DetailLazyScaffold(
        title = stringResource(R.string.notification_history_title),
        onBack = onBack,
        pageState = if (state.notificationHistory.isEmpty()) PageStateKind.EMPTY else PageStateKind.CONTENT,
        pageStateContent = {
            EmptyState(
                title = stringResource(R.string.notification_history_empty),
                message = stringResource(R.string.notification_history_empty_message),
                icon = MiuixIcons.File,
                modifier = Modifier.fillMaxSize(),
            )
        },
    ) {
        if (state.notificationHistory.isNotEmpty()) {
            items(historyItems, key = { it.first }) { (_, entry) ->
                NotificationHistoryCard(
                    entry = entry,
                    onClick = {
                        selectedHistoryEntry = entry
                        showHistoryDetails = true
                    },
                    onLongPress = {
                        selectedHistoryEntry = entry
                        showHistoryActions = true
                    },
                )
            }
        }
    }

    selectedHistoryEntry?.let { entry ->
        NotificationHistoryDetailsSheet(
            entry = entry,
            show = showHistoryDetails,
            onDismissRequest = { showHistoryDetails = false },
            onDismissFinished = { selectedHistoryEntry = null },
        )
        NotificationHistoryActionsSheet(
            entry = entry,
            show = showHistoryActions,
            onViewDetails = {
                openDetailsAfterActions = true
                showHistoryActions = false
            },
            onResend = {
                showHistoryActions = false
                onResendNotification(entry)
            },
            onDelete = {
                deleteHistoryEntry = entry
                showHistoryActions = false
            },
            onDismissRequest = { showHistoryActions = false },
            onDismissFinished = {
                if (deleteHistoryEntry != null) {
                    showDeleteConfirmation = true
                }
                if (openDetailsAfterActions) {
                    openDetailsAfterActions = false
                    showHistoryDetails = true
                }
            },
        )
    }

    OverlayDialog(
        show = showDeleteConfirmation,
        title = stringResource(R.string.notification_history_delete_title),
        summary = stringResource(R.string.notification_history_delete_summary),
        onDismissRequest = {
            showDeleteConfirmation = false
            deleteHistoryEntry = null
        },
    ) {
        DialogActionRow(
            onCancel = {
                showDeleteConfirmation = false
                deleteHistoryEntry = null
            },
            confirmText = stringResource(R.string.notification_history_delete_action),
            destructive = true,
            onConfirm = {
                deleteHistoryEntry?.let(onDeleteHistoryEntry)
                showDeleteConfirmation = false
                deleteHistoryEntry = null
                selectedHistoryEntry = null
            },
        )
    }
}

@Composable
private fun NotificationHistoryCard(
    entry: NotificationHistoryEntry,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val actionLabel = entry.action.localizedLabel()
    val statusLabel = entry.status.localizedLabel()
    val operationLabel = localizedNotificationOperation(entry.notificationOperation)
    val profileLabel = entry.profileName?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.common_unavailable)
    val timestamp = entry.timestamp.formatReminderDateTime()
    val statusColor = if (entry.status == NotificationHistoryStatus.FAILED) {
        MiuixTheme.colorScheme.error
    } else {
        MiuixTheme.colorScheme.primary
    }
    GroupedCard(onClick = onClick, onLongPress = onLongPress) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = actionLabel,
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = operationLabel,
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = statusLabel,
                    style = MiuixTheme.textStyles.subtitle,
                    color = statusColor,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profileLabel,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = timestamp,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun NotificationCard(
    notification: LpaNotification,
    profileName: String?,
    providerName: String?,
    onClick: () -> Unit,
) {
    val operationLabel = notification.operation.localizedLabel()
    val address = notification.address.ifBlank {
        stringResource(R.string.notification_no_address)
    }
    GroupedCard(onClick = onClick, onLongPress = onClick) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = profileName ?: operationLabel,
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (profileName != null) {
                Text(
                    text = operationLabel,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            providerName?.let { provider ->
                Text(
                    text = provider,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.notification_sequence,
                        notification.sequenceNumber,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = address,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NotificationDetailsSheet(
    notification: LpaNotification,
    profile: ProfileInfo?,
    profileName: String?,
    iccid: String,
    show: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    WindowBottomSheet(
        show = show,
        title = profileName ?: stringResource(R.string.notification_details_title),
        startAction = {
            val dismissState = LocalDismissState.current
            IconButton(onClick = { dismissState?.invoke() ?: onDismissRequest() }) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.common_close),
                )
            }
        },
        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
        insideMargin = DpSize(24.dp, 0.dp),
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_operation),
                    value = notification.operation.localizedLabel(),
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_profile),
                    value = profileName ?: stringResource(R.string.profile_unavailable_title),
                )
            }
            profile?.let { selectedProfile ->
                item {
                    NotificationDetailLine(
                        title = stringResource(R.string.profile_provider),
                        value = selectedProfile.providerName.ifBlank {
                            stringResource(R.string.profile_unknown_operator)
                        },
                    )
                }
                item {
                    NotificationDetailLine(
                        title = stringResource(R.string.notification_details_profile_state),
                        value = if (selectedProfile.state == ProfileState.ENABLED) {
                            stringResource(R.string.profile_enabled)
                        } else {
                            stringResource(R.string.profile_disabled)
                        },
                    )
                }
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_iccid),
                    value = iccid.ifBlank { stringResource(R.string.common_unavailable) },
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.profile_notification_address),
                    value = notification.address.ifBlank {
                        stringResource(R.string.notification_no_address)
                    },
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_sequence),
                    value = notification.sequenceNumber.toString(),
                )
            }
            item {
                Spacer(
                    modifier = Modifier.height(
                        24.dp +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun NotificationHistoryDetailsSheet(
    entry: NotificationHistoryEntry,
    show: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    val operationLabel = localizedNotificationOperation(entry.notificationOperation)
    WindowBottomSheet(
        show = show,
        title = entry.profileName ?: operationLabel,
        startAction = {
            val dismissState = LocalDismissState.current
            IconButton(onClick = { dismissState?.invoke() ?: onDismissRequest() }) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.common_close),
                )
            }
        },
        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
        insideMargin = DpSize(24.dp, 0.dp),
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_action),
                    value = entry.action.localizedLabel(),
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_status),
                    value = entry.status.localizedLabel(),
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_trigger),
                    value = entry.trigger.localizedLabel(),
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_operation),
                    value = operationLabel,
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_profile),
                    value = entry.profileName ?: stringResource(R.string.common_unavailable),
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.profile_provider),
                    value = entry.providerName ?: stringResource(R.string.common_unavailable),
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_eid),
                    value = entry.eid ?: stringResource(R.string.common_unavailable),
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_iccid),
                    value = entry.iccid
                        ?: entry.redactedIccid
                        ?: stringResource(R.string.common_unavailable),
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.profile_notification_address),
                    value = entry.notificationAddress
                        ?: entry.endpointHost
                        ?: stringResource(R.string.common_unavailable),
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_sequence),
                    value = entry.sequenceNumber?.toString()
                        ?: stringResource(R.string.common_unavailable),
                )
            }
            item {
                NotificationDetailLine(
                    title = stringResource(R.string.notification_details_time),
                    value = entry.timestamp.formatReminderDateTime(),
                )
            }
            entry.failureCode?.let { failureCode ->
                item {
                    NotificationDetailLine(
                        title = stringResource(R.string.notification_details_failure),
                        value = failureCode.localizedFailureLabel(),
                    )
                }
            }
            item {
                Spacer(
                    modifier = Modifier.height(
                        24.dp +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun NotificationHistoryActionsSheet(
    entry: NotificationHistoryEntry,
    show: Boolean,
    onViewDetails: () -> Unit,
    onResend: () -> Unit,
    onDelete: () -> Unit,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.notification_history_options_title),
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        Column {
            ArrowPreference(
                title = stringResource(R.string.notification_history_option_details),
                summary = stringResource(R.string.notification_history_option_details_summary),
                onClick = onViewDetails,
            )
            if (entry.sequenceNumber != null) {
                ArrowPreference(
                    title = stringResource(R.string.notification_history_option_resend),
                    summary = stringResource(R.string.notification_history_option_resend_summary),
                    onClick = onResend,
                )
            }
            ArrowPreference(
                title = stringResource(R.string.notification_history_option_delete),
                summary = stringResource(R.string.notification_history_option_delete_summary),
                onClick = onDelete,
            )
            ProfileActionSheetFooterSpacer()
        }
    }
}

@Composable
private fun NotificationDetailLine(
    title: String,
    value: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.main,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NotificationOperation.localizedLabel(): String = stringResource(
    when (this) {
        NotificationOperation.INSTALL -> R.string.notification_operation_install
        NotificationOperation.ENABLE -> R.string.notification_operation_enable
        NotificationOperation.DISABLE -> R.string.notification_operation_disable
        NotificationOperation.DELETE -> R.string.notification_operation_delete
        NotificationOperation.UNKNOWN -> R.string.notification_operation_unknown
    },
)

@Composable
private fun localizedNotificationOperation(rawValue: String): String =
    runCatching { NotificationOperation.valueOf(rawValue) }
        .getOrDefault(NotificationOperation.UNKNOWN)
        .localizedLabel()

@Composable
private fun NotificationHistoryAction.localizedLabel(): String = stringResource(
    when (this) {
        NotificationHistoryAction.SEND -> R.string.notification_history_action_send
        NotificationHistoryAction.DELETE -> R.string.notification_history_action_delete
    },
)

@Composable
private fun NotificationHistoryStatus.localizedLabel(): String = stringResource(
    when (this) {
        NotificationHistoryStatus.SUCCEEDED -> R.string.notification_history_status_succeeded
        NotificationHistoryStatus.FAILED -> R.string.notification_history_status_failed
    },
)

@Composable
private fun NotificationHistoryTrigger.localizedLabel(): String = stringResource(
    when (this) {
        NotificationHistoryTrigger.MANUAL -> R.string.notification_history_trigger_manual
        NotificationHistoryTrigger.AUTOMATIC -> R.string.notification_history_trigger_automatic
    },
)

@Composable
private fun String.localizedFailureLabel(): String = stringResource(
    when (this) {
        "rejected" -> R.string.notification_history_failure_rejected
        "exception" -> R.string.notification_history_failure_exception
        else -> R.string.notification_history_failure_unknown
    },
)

@Composable
fun ToolsScreen(
    state: HyperLpaUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onNavigate: (AppRoute) -> Unit,
) {
    PreferencePage(
        modifier = modifier,
        contentPadding = contentPadding,
        scrollBehavior = scrollBehavior,
    ) {
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.tools_profile_management)) }
        item {
            GroupedCard {
                ToolPreference(
                    stringResource(R.string.action_download_profile),
                    stringResource(R.string.tools_download_profile_summary),
                    MiuixIcons.Download,
                ) {
                    onNavigate(AppRoute.DownloadProfile)
                }
                ToolPreference(
                    stringResource(R.string.tools_batch_download),
                    stringResource(R.string.tools_batch_download_summary),
                    MiuixIcons.Layers,
                ) {
                    onNavigate(AppRoute.BatchDownload)
                }
                ToolPreference(
                    stringResource(R.string.tools_euicc_information),
                    state.lpa.euiccInfo?.let { redactIdentifier(it.eid, state.settings.eidRedaction) }
                        ?: stringResource(R.string.tools_connect_reader_first),
                    MiuixIcons.Info,
                ) {
                    onNavigate(AppRoute.EuiccDetails)
                }
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.tools_organisation)) }
        item {
            GroupedCard {
                ToolPreference(
                    stringResource(R.string.tools_tags_reminders),
                    stringResource(R.string.tools_tags_reminders_summary),
                    MiuixIcons.Alarm,
                ) {
                    onNavigate(AppRoute.TagsAndReminders)
                }
                ToolPreference(
                    stringResource(R.string.tools_statistics),
                    stringResource(R.string.tools_statistics_summary),
                    MiuixIcons.GridView,
                ) {
                    onNavigate(AppRoute.Statistics)
                }
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.tools_diagnostics)) }
        item {
            GroupedCard {
                ToolPreference(
                    stringResource(R.string.tools_isdr_aids),
                    stringResource(R.string.tools_isdr_aids_summary),
                    MiuixIcons.Tune,
                ) {
                    onNavigate(AppRoute.AidManager)
                }
                ToolPreference(
                    stringResource(R.string.tools_activity_logs),
                    stringResource(R.string.tools_activity_logs_summary),
                    MiuixIcons.Notes,
                ) {
                    onNavigate(AppRoute.Logs)
                }
            }
        }
    }
}

private fun Instant.formatReminderDateTime(): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())
    .format(this)

@Composable
fun SettingsScreen(
    state: HyperLpaUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onNavigate: (AppRoute) -> Unit,
) {
    PreferencePage(
        modifier = modifier,
        contentPadding = contentPadding,
        scrollBehavior = scrollBehavior,
    ) {
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.settings_personalisation)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_appearance_theme),
                    summary = stringResource(R.string.settings_appearance_theme_summary),
                    onClick = { onNavigate(AppRoute.AppearanceSettings) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_profile_display),
                    summary = stringResource(R.string.settings_profile_display_summary),
                    onClick = { onNavigate(AppRoute.ProfileDisplaySettings) },
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.settings_lpa_section)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_reader_types),
                    summary = stringResource(R.string.settings_reader_types_summary),
                    onClick = { onNavigate(AppRoute.ReaderSettings) },
                )
                ArrowPreference(
                    title = stringResource(R.string.remote_title),
                    summary = stringResource(R.string.remote_settings_summary),
                    onClick = { onNavigate(AppRoute.RemoteDevices) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_compatibility_setup),
                    summary = stringResource(R.string.settings_compatibility_setup_summary),
                    onClick = { onNavigate(AppRoute.CompatibilityWizard) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_notification_processing),
                    summary = stringResource(R.string.settings_notification_processing_summary),
                    onClick = { onNavigate(AppRoute.NotificationSettings) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_advanced_lpa),
                    summary = stringResource(R.string.settings_advanced_lpa_summary),
                    onClick = { onNavigate(AppRoute.AdvancedSettings) },
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.settings_privacy_section)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_privacy_cloud),
                    summary = stringResource(R.string.settings_privacy_cloud_summary),
                    onClick = { onNavigate(AppRoute.PrivacySettings) },
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.settings_app_section)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_backup_restore),
                    summary = stringResource(R.string.settings_backup_restore_summary),
                    onClick = { onNavigate(AppRoute.BackupRestoreSettings) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_about),
                    summary = stringResource(R.string.settings_about_summary),
                    onClick = { onNavigate(AppRoute.About) },
                )
            }
        }
    }
}

@Composable
private fun PreferencePage(
    modifier: Modifier,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    content: LazyListScope.() -> Unit,
) {
    val pageContent = MishkaPageContent(content)
    CenteredContent(modifier = modifier) { sidePadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            overscrollEffect = null,
            contentPadding = PaddingValues(
                start = sidePadding,
                end = sidePadding,
                top = contentPadding.calculateTopPadding() + pageContent.topPadding,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            content = pageContent.content,
        )
    }
}

@Composable
private fun ToolPreference(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = title,
        summary = summary,
        startAction = {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        },
        onClick = onClick,
    )
}

private const val MaxSearchQueryCharacters = 256
