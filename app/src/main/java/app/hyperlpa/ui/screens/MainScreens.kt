package app.hyperlpa.ui.screens

import android.os.Build
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.LoadingState
import app.hyperlpa.ui.components.PageStateHost
import app.hyperlpa.ui.components.PageStateKind
import app.hyperlpa.ui.components.ResolvedProfileArtwork
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.formatProfileDisplayName
import app.hyperlpa.ui.components.redactIdentifier
import app.hyperlpa.ui.components.rememberProfileArtworkBitmaps
import app.hyperlpa.ui.navigation.AppRoute
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ProfilesScreen(
    state: HyperLpaUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    bluetoothReaderState: BluetoothReaderUiState,
    onSearchChange: (String) -> Unit,
    onSelectReader: (String) -> Unit,
    onRefreshReaders: () -> Unit,
    onOpenEuiccDetails: () -> Unit,
    onOpenProfile: (ProfileInfo) -> Unit,
    onEnableChange: (String, Boolean) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onRename: (String, String) -> Unit,
    onDownload: () -> Unit,
    onRefresh: () -> Unit,
) {
    // Keep both layout positions alive while switching between list and waterfall.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // Keep the selected profile in a holder so only the overlay reads its value. A long press
    // must not invalidate the home list while the bottom-sheet entrance animation is running.
    val profileActionsState = remember { mutableStateOf<ProfileInfo?>(null) }
    val profiles = state.profiles
    val artworkLoadState = rememberProfileArtworkBitmaps(
        profiles = profiles,
        cloudIcons = state.operatorIcons,
        sourceKey = state.lpa.euiccInfo?.eid,
        enabled = state.settings.showProfileIconOnHome,
    )
    // Remote operator artwork and size predictions are optional enhancements. They must not
    // hold the locally-read eUICC profiles behind a network timeout.
    val artworkReady = artworkLoadState.ready
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
    val pageState = when {
        state.lpa.operation is LpaOperation.Connecting -> PageStateKind.LOADING
        !state.lpa.initialized ||
            (state.lpa.operation is LpaOperation.DiscoveringReaders && state.lpa.profiles.isEmpty()) -> PageStateKind.LOADING
        state.lpa.profiles.isNotEmpty() && !artworkReady -> PageStateKind.LOADING
        state.lpa.readers.isEmpty() -> PageStateKind.ERROR
        state.lpa.selectedReader == null -> PageStateKind.EMPTY
        profiles.isEmpty() -> PageStateKind.EMPTY
        else -> PageStateKind.CONTENT
    }

    PullToRefresh(
        isRefreshing = state.lpa.operation is LpaOperation.Refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        contentPadding = contentPadding,
        topAppBarScrollBehavior = scrollBehavior,
    ) {
        CenteredContent { sidePadding ->
            if (pageState == PageStateKind.LOADING) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                    LoadingState(message = loadingMessage)
                }
            } else if (state.settings.profileLayout == ProfileLayout.WATERFALL && pageState == PageStateKind.CONTENT) {
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
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding() + 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ProfilesHeader(
                            state = state,
                            onSearchChange = onSearchChange,
                            onSelectReader = onSelectReader,
                            onRefreshReaders = onRefreshReaders,
                            onOpenEuiccDetails = onOpenEuiccDetails,
                        )
                    }
                    items(profiles, key = ProfileInfo::iccid) { profile ->
                        ProfileCard(
                            profile = profile,
                            artworkBitmap = artworkLoadState.bitmaps[profile.iccid],
                            state = state,
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
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding() + 24.dp,
                    ),
                ) {
                    item(key = "header") {
                        ProfilesHeader(
                            state = state,
                            onSearchChange = onSearchChange,
                            onSelectReader = onSelectReader,
                            onRefreshReaders = onRefreshReaders,
                            onOpenEuiccDetails = onOpenEuiccDetails,
                        )
                    }
                    if (pageState == PageStateKind.CONTENT) {
                        items(profiles, key = ProfileInfo::iccid) { profile ->
                            ProfileCard(
                                profile = profile,
                                artworkBitmap = artworkLoadState.bitmaps[profile.iccid],
                                state = state,
                                onOpen = { onOpenProfile(profile) },
                                onEnableChange = { enabled -> onEnableChange(profile.iccid, enabled) },
                                onLongPress = { profileActionsState.value = profile },
                            )
                        }
                    } else {
                        item(key = "state") {
                            PageStateHost(
                                state = pageState,
                                loadingMessage = loadingMessage,
                                emptyTitle = when {
                                    state.lpa.selectedReader == null -> stringResource(R.string.profiles_choose_reader)
                                    hasNoSearchResults -> stringResource(R.string.profiles_none_found)
                                    else -> stringResource(R.string.profiles_none_installed)
                                },
                                emptyMessage = when {
                                    state.lpa.selectedReader == null -> stringResource(R.string.profiles_choose_reader_message)
                                    hasNoSearchResults -> stringResource(R.string.profiles_search_empty_message)
                                    else -> stringResource(R.string.profiles_none_installed_message)
                                },
                                errorTitle = noReaderTitle,
                                errorMessage = noReaderMessage,
                                onRetry = onRefreshReaders,
                            ) {}
                        }
                    }
                    if (pageState == PageStateKind.EMPTY && state.lpa.selectedReader != null && !hasNoSearchResults) {
                        item(key = "download") {
                            TextButton(
                                text = stringResource(R.string.action_download_profile),
                                onClick = onDownload,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    ProfileActionsOverlay(
        profileState = profileActionsState,
        onEnableChange = onEnableChange,
        onSetPinned = onSetPinned,
        onRename = onRename,
    )
}

@Composable
private fun ProfileActionsOverlay(
    profileState: MutableState<ProfileInfo?>,
    onEnableChange: (String, Boolean) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onRename: (String, String) -> Unit,
) {
    var renameProfile by remember { mutableStateOf<ProfileInfo?>(null) }
    var pendingRenameProfile by remember { mutableStateOf<ProfileInfo?>(null) }
    var isClosing by remember { mutableStateOf(false) }
    var renameValue by remember(renameProfile?.iccid) {
        mutableStateOf(renameProfile?.nickname.orEmpty())
    }
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
                    onClick = {
                        onEnableChange(selectedProfile.iccid, !isEnabled)
                        isClosing = true
                    },
                )
                ProfileActionSheetFooterSpacer()
            }
        }
    }

    OverlayDialog(
        show = renameProfile != null,
        title = stringResource(R.string.profile_rename),
        summary = stringResource(R.string.profile_rename_summary),
        onDismissRequest = { renameProfile = null },
    ) {
        Column {
            TextField(
                value = renameValue,
                onValueChange = { renameValue = it.takeUnicodeCodePoints(64) },
                label = stringResource(R.string.profile_name),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { renameProfile = null },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.profile_rename_action),
                    onClick = {
                        renameProfile?.let { selectedProfile ->
                            onRename(selectedProfile.iccid, renameValue.trim())
                        }
                        renameProfile = null
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ProfilesHeader(
    state: HyperLpaUiState,
    onSearchChange: (String) -> Unit,
    onSelectReader: (String) -> Unit,
    onRefreshReaders: () -> Unit,
    onOpenEuiccDetails: () -> Unit,
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
    Column(modifier = Modifier.fillMaxWidth()) {
        val showEid = state.settings.showEidOnHome && state.lpa.euiccInfo != null
        val cardName = state.currentEuiccName
        if (state.settings.showReaderSelectorOnHome || showEid) {
            GroupedCard {
                if (state.settings.showReaderSelectorOnHome) {
                    if (state.lpa.readers.isEmpty()) {
                        ArrowPreference(
                            title = stringResource(R.string.profiles_find_readers),
                            summary = stringResource(R.string.profiles_find_readers_summary),
                            onClick = onRefreshReaders,
                        )
                    } else {
                        val selectedIndex = state.lpa.readers.indexOfFirst {
                            it.id == state.lpa.selectedReaderId
                        }.coerceAtLeast(0)
                        OverlayDropdownPreference(
                            title = stringResource(R.string.profiles_active_reader),
                            summary = state.lpa.selectedReader?.detail
                                ?: stringResource(R.string.profiles_select_reader),
                            items = state.lpa.readers.map { it.name },
                            selectedIndex = selectedIndex,
                            onSelectedIndexChange = { index ->
                                state.lpa.readers.getOrNull(index)?.id?.let(onSelectReader)
                            },
                        )
                    }
                }
                if (showEid) {
                    val info = requireNotNull(state.lpa.euiccInfo)
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
        AnimatedVisibility(visible = state.settings.showProfileSearch && state.lpa.selectedReader != null) {
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

@Composable
private fun ProfileCard(
    profile: ProfileInfo,
    artworkBitmap: Bitmap?,
    state: HyperLpaUiState,
    onOpen: () -> Unit,
    onEnableChange: (Boolean) -> Unit,
    onLongPress: () -> Unit,
) {
    val isEnabled = profile.state == ProfileState.ENABLED
    val fallbackName = stringResource(R.string.profile_default_name)
    val displayName = remember(profile, state.settings.phoneFormatStrategy, fallbackName) {
        formatProfileDisplayName(profile, state.settings.phoneFormatStrategy, fallbackName)
    }
    val unknownOperator = stringResource(R.string.profile_unknown_operator)
    val operatorAndTags = buildList {
        if (state.settings.showProfileProviderOnHome) {
            add(profile.providerName.ifBlank { unknownOperator })
        }
        if (state.settings.showProfileTagsOnHome) {
            addAll(profile.tags.filter(String::isNotBlank))
        }
    }.joinToString(" · ")
    val profileBytes = if (state.settings.showProfileSizeOnHome) {
        profile.estimatedBytes?.takeIf { it > 0 }
    } else {
        null
    }
    val cardDescription = stringResource(R.string.profile_open_named, displayName.fullText)
    val switchDescription = stringResource(
        if (isEnabled) R.string.profile_disable_named else R.string.profile_enable_named,
        displayName.fullText,
    )
    val profileActionsDescription = stringResource(R.string.profile_actions_title)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
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
                if (state.settings.showProfileNameOnHome) {
                    Text(
                        text = displayName.fullText,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (operatorAndTags.isNotEmpty()) {
                    Text(
                        text = operatorAndTags,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.settings.showProfileRemindersOnHome) {
                    profile.reminderAt?.let { reminderAt ->
                        Text(
                            text = stringResource(
                                R.string.profile_reminder_summary,
                                reminderAt.formatReminderDateTime(),
                            ),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (state.settings.showProfileIccidOnHome || profileBytes != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.settings.showProfileIccidOnHome) {
                            Text(
                                text = redactIdentifier(
                                    profile.iccid,
                                    state.settings.iccidRedaction,
                                ),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        profileBytes?.let { bytes ->
                            Text(
                                text = formatProfileBytes(bytes).let { formatted ->
                                    if (profile.sizeIsEstimated) {
                                        stringResource(R.string.profile_size_estimated, formatted)
                                    } else {
                                        formatted
                                    }
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (profile.sizeIsEstimated) {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                } else {
                                    MiuixTheme.colorScheme.primary
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (state.settings.showProfileSwitchOnHome) {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onEnableChange,
                    modifier = Modifier.semantics {
                        contentDescription = switchDescription
                    },
                )
            }
        }
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
    onRefresh: () -> Unit,
) {
    PullToRefresh(
        isRefreshing = state.lpa.operation is LpaOperation.Refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        contentPadding = contentPadding,
        topAppBarScrollBehavior = scrollBehavior,
    ) {
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
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item { SectionHeading(stringResource(R.string.notifications_pending_section)) }
                if (state.lpa.selectedReader == null) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.notifications_no_reader),
                            message = stringResource(R.string.notifications_no_reader_message),
                            icon = MiuixIcons.Messages,
                        )
                    }
                } else if (state.lpa.notifications.isEmpty()) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.notifications_none_pending),
                            message = stringResource(R.string.notifications_none_pending_message),
                            icon = MiuixIcons.Messages,
                        )
                    }
                } else {
                    items(state.lpa.notifications, key = LpaNotification::sequenceNumber) { notification ->
                        NotificationCard(notification = notification, onProcess = onProcess, onDelete = onDelete)
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationHistoryScreen(
    state: HyperLpaUiState,
    onBack: () -> Unit,
) {
    DetailLazyScaffold(
        title = stringResource(R.string.notification_history_title),
        onBack = onBack,
    ) {
        if (state.notificationHistory.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.notification_history_empty),
                    message = stringResource(R.string.notification_history_empty_message),
                    icon = MiuixIcons.Messages,
                )
            }
        } else {
            item {
                GroupedCard {
                    Text(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        text = pluralStringResource(
                            R.plurals.notification_history_saved_count,
                            state.notificationHistory.size,
                            state.notificationHistory.size,
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            items(state.notificationHistory.asReversed()) { entry ->
                NotificationHistoryCard(entry = entry)
            }
        }
    }
}

@Composable
private fun NotificationHistoryCard(entry: NotificationHistoryEntry) {
    val actionLabel = entry.action.localizedLabel()
    val statusLabel = entry.status.localizedLabel()
    val triggerLabel = entry.trigger.localizedLabel()
    val operationLabel = localizedNotificationOperation(entry.notificationOperation)
    val operationSummary = entry.endpointHost?.let { host ->
        stringResource(R.string.notification_history_operation_host, operationLabel, host)
    } ?: operationLabel
    val failureLabel = entry.failureCode?.localizedFailureLabel()
    val timestamp = entry.timestamp.formatReminderDateTime()
    val timestampSummary = failureLabel?.let { failure ->
        stringResource(R.string.notification_history_time_failure, timestamp, failure)
    } ?: timestamp
    GroupedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.notification_history_action_status,
                        actionLabel,
                        statusLabel,
                    ),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    color = if (entry.status == NotificationHistoryStatus.FAILED) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                )
                Text(
                    text = triggerLabel,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = operationSummary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = timestampSummary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun NotificationCard(
    notification: LpaNotification,
    onProcess: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    GroupedCard {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MiuixIcons.Messages,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = notification.operation.localizedLabel(),
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = notification.address.ifBlank {
                            stringResource(R.string.notification_no_address)
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.notification_sequence,
                            notification.sequenceNumber,
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    text = stringResource(R.string.common_remove),
                    onClick = { onDelete(notification.sequenceNumber) },
                )
                TextButton(
                    text = stringResource(R.string.common_send),
                    onClick = { onProcess(notification.sequenceNumber) },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
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
        item { SectionHeading(stringResource(R.string.tools_profile_management)) }
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
                    MiuixIcons.Add,
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
        item { SectionHeading(stringResource(R.string.tools_organisation)) }
        item {
            GroupedCard {
                ToolPreference(
                    stringResource(R.string.tools_tags_reminders),
                    stringResource(R.string.tools_tags_reminders_summary),
                    MiuixIcons.Messages,
                ) {
                    onNavigate(AppRoute.TagsAndReminders)
                }
                ToolPreference(
                    stringResource(R.string.tools_statistics),
                    stringResource(R.string.tools_statistics_summary),
                    MiuixIcons.Info,
                ) {
                    onNavigate(AppRoute.Statistics)
                }
            }
        }
        item { SectionHeading(stringResource(R.string.tools_diagnostics)) }
        item {
            GroupedCard {
                ToolPreference(
                    stringResource(R.string.tools_reader_diagnostics),
                    stringResource(R.string.tools_reader_diagnostics_summary),
                    MiuixIcons.Refresh,
                ) {
                    onNavigate(AppRoute.ReaderSettings)
                }
                ToolPreference(
                    stringResource(R.string.tools_isdr_aids),
                    stringResource(R.string.tools_isdr_aids_summary),
                    MiuixIcons.Info,
                ) {
                    onNavigate(AppRoute.AidManager)
                }
                ToolPreference(
                    stringResource(R.string.tools_activity_logs),
                    stringResource(R.string.tools_activity_logs_summary),
                    MiuixIcons.Messages,
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
        item { SectionHeading(stringResource(R.string.settings_personalisation)) }
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
        item { SectionHeading(stringResource(R.string.settings_lpa_section)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_reader_types),
                    summary = stringResource(R.string.settings_reader_types_summary),
                    onClick = { onNavigate(AppRoute.ReaderSettings) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_notification_processing),
                    summary = stringResource(R.string.settings_notification_processing_summary),
                    onClick = { onNavigate(AppRoute.NotificationSettings) },
                )
                ArrowPreference(
                    title = stringResource(R.string.tools_tags_reminders),
                    summary = stringResource(R.string.settings_tags_reminders_summary),
                    onClick = { onNavigate(AppRoute.TagsAndReminders) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_advanced_lpa),
                    summary = stringResource(R.string.settings_advanced_lpa_summary),
                    onClick = { onNavigate(AppRoute.AdvancedSettings) },
                )
            }
        }
        item { SectionHeading(stringResource(R.string.settings_privacy_section)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_privacy_cloud),
                    summary = stringResource(R.string.settings_privacy_cloud_summary),
                    onClick = { onNavigate(AppRoute.PrivacySettings) },
                )
            }
        }
        item { SectionHeading(stringResource(R.string.settings_app_section)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_backup_restore),
                    summary = stringResource(R.string.settings_backup_restore_summary),
                    onClick = { onNavigate(AppRoute.BackupRestoreSettings) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_logs),
                    summary = stringResource(R.string.settings_logs_summary),
                    onClick = { onNavigate(AppRoute.Logs) },
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
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            content = content,
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
