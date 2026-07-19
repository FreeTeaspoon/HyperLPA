package app.hyperlpa.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.ProfileLayout
import app.hyperlpa.domain.model.LpaNotification
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.NotificationOperation
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.adaptive.CenteredContent
import app.hyperlpa.ui.components.EmptyState
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.LoadingState
import app.hyperlpa.ui.components.PageStateHost
import app.hyperlpa.ui.components.PageStateKind
import app.hyperlpa.ui.components.ProfileArtwork
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.formatProfileDisplayName
import app.hyperlpa.ui.components.redactIdentifier
import app.hyperlpa.ui.navigation.AppRoute
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Surface
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
    onSearchChange: (String) -> Unit,
    onSelectReader: (String) -> Unit,
    onRefreshReaders: () -> Unit,
    onOpenEuiccDetails: () -> Unit,
    onOpenProfile: (ProfileInfo) -> Unit,
    onEnableChange: (String, Boolean) -> Unit,
    onDownload: () -> Unit,
    onRefresh: () -> Unit,
) {
    // Keep both layout positions alive while profile refresh/enrichment temporarily
    // swaps the content for a loading state during a profile switch.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val profiles = state.profiles
    val hasNoSearchResults = state.searchQuery.isNotBlank() &&
        state.lpa.profiles.isNotEmpty() &&
        profiles.isEmpty()
    val pageState = when {
        !state.lpa.initialized ||
            (state.lpa.operation is LpaOperation.DiscoveringReaders && state.lpa.profiles.isEmpty()) -> PageStateKind.LOADING
        !state.profileEnrichmentReady && state.lpa.profiles.isNotEmpty() -> PageStateKind.LOADING
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
                    LoadingState(message = "Looking for eUICC readers")
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
                            state = state,
                            onOpen = { onOpenProfile(profile) },
                            onEnableChange = { enabled -> onEnableChange(profile.iccid, enabled) },
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
                                state = state,
                                onOpen = { onOpenProfile(profile) },
                                onEnableChange = { enabled -> onEnableChange(profile.iccid, enabled) },
                            )
                        }
                    } else {
                        item(key = "state") {
                            PageStateHost(
                                state = pageState,
                                loadingMessage = "Looking for eUICC readers",
                                emptyTitle = when {
                                    state.lpa.selectedReader == null -> "Choose a reader"
                                    hasNoSearchResults -> "No profiles found"
                                    else -> "No profiles installed"
                                },
                                emptyMessage = when {
                                    state.lpa.selectedReader == null -> "Select an available secure-element reader to continue."
                                    hasNoSearchResults -> "Try a different search."
                                    else -> "Download an activation code to install your first eSIM profile."
                                },
                                errorTitle = "No eUICC reader found",
                                errorMessage = "Connect a USB or BLE reader, install NBridge, or enable OMAPI access.",
                                onRetry = onRefreshReaders,
                            ) {}
                        }
                    }
                    if (pageState == PageStateKind.EMPTY && state.lpa.selectedReader != null && !hasNoSearchResults) {
                        item(key = "download") {
                            TextButton(
                                text = "Download profile",
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
}

@Composable
private fun ProfilesHeader(
    state: HyperLpaUiState,
    onSearchChange: (String) -> Unit,
    onSelectReader: (String) -> Unit,
    onRefreshReaders: () -> Unit,
    onOpenEuiccDetails: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val showEid = state.settings.showEidOnHome && state.lpa.euiccInfo != null
        if (state.settings.showReaderSelectorOnHome || showEid) {
            GroupedCard {
                if (state.settings.showReaderSelectorOnHome) {
                    if (state.lpa.readers.isEmpty()) {
                        ArrowPreference(
                            title = "Find readers",
                            summary = "Scan NBridge, OMAPI and USB CCID sources",
                            onClick = onRefreshReaders,
                        )
                    } else {
                        val selectedIndex = state.lpa.readers.indexOfFirst {
                            it.id == state.lpa.selectedReaderId
                        }.coerceAtLeast(0)
                        OverlayDropdownPreference(
                            title = "Active reader",
                            summary = state.lpa.selectedReader?.detail ?: "Select a reader",
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
                    ArrowPreference(
                        title = "EID",
                        summary = redactIdentifier(
                            value = info.eid,
                            mode = state.settings.eidRedaction,
                        ),
                        onClick = onOpenEuiccDetails,
                    )
                }
            }
        }
        AnimatedVisibility(visible = state.settings.showProfileSearch && state.lpa.selectedReader != null) {
            Column {
                TextField(
                    value = state.searchQuery,
                    onValueChange = onSearchChange,
                    label = "Search profiles",
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
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileInfo,
    state: HyperLpaUiState,
    onOpen: () -> Unit,
    onEnableChange: (Boolean) -> Unit,
) {
    val isEnabled = profile.state == ProfileState.ENABLED
    val displayName = remember(profile, state.settings.phoneFormatStrategy) {
        formatProfileDisplayName(profile, state.settings.phoneFormatStrategy)
    }
    val operatorAndTags = buildList {
        if (state.settings.showProfileProviderOnHome) {
            add(profile.providerName.ifBlank { "Unknown operator" })
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

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.settings.showProfileIconOnHome) {
                ProfileArtwork(
                    profile = profile,
                    cloudIcon = state.operatorIcons[profile.iccid],
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
                            text = "Reminder · ${reminderAt.formatReminderDateTime()}",
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
                                text = "${if (profile.sizeIsEstimated) "~" else ""}${formatProfileBytes(bytes)}",
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
                        contentDescription = if (isEnabled) "Disable profile" else "Enable profile"
                    },
                )
            }
        }
    }
}private fun formatProfileBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val kib = bytes / 1_024.0
    if (kib < 1_024) return if (kib >= 100) "%.0f KiB".format(kib) else "%.1f KiB".format(kib)
    val mib = kib / 1_024.0
    return if (mib >= 100) "%.0f MiB".format(mib) else "%.1f MiB".format(mib)
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
                item { SectionHeading("Pending on eUICC") }
                if (state.lpa.selectedReader == null) {
                    item {
                        EmptyState(
                            title = "No reader connected",
                            message = "Connect a reader on the Profiles page to load notifications.",
                            icon = MiuixIcons.Messages,
                        )
                    }
                } else if (state.lpa.notifications.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No pending notifications",
                            message = "Profile management notifications are already up to date.",
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
                        text = notification.operation.label,
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = notification.address.ifBlank { "No notification address" },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Sequence ${notification.sequenceNumber}",
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
                TextButton(text = "Remove", onClick = { onDelete(notification.sequenceNumber) })
                TextButton(
                    text = "Send",
                    onClick = { onProcess(notification.sequenceNumber) },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

private val NotificationOperation.label: String
    get() = when (this) {
        NotificationOperation.INSTALL -> "Profile installed"
        NotificationOperation.ENABLE -> "Profile enabled"
        NotificationOperation.DISABLE -> "Profile disabled"
        NotificationOperation.DELETE -> "Profile deleted"
        NotificationOperation.UNKNOWN -> "Profile operation"
    }

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
        item { SectionHeading("Profile management") }
        item {
            GroupedCard {
                ToolPreference("Download profile", "Install an activation code or scan a QR code", MiuixIcons.Download) {
                    onNavigate(AppRoute.DownloadProfile)
                }
                ToolPreference("Batch download", "Queue multiple activation codes", MiuixIcons.Add) {
                    onNavigate(AppRoute.BatchDownload)
                }
                ToolPreference("eUICC information", state.lpa.euiccInfo?.eid ?: "Connect a reader first", MiuixIcons.Info) {
                    onNavigate(AppRoute.EuiccDetails)
                }
            }
        }
        item { SectionHeading("Organisation") }
        item {
            GroupedCard {
                ToolPreference("Tags & reminders", "Manage tags, dates and reminder permissions", MiuixIcons.Messages) {
                    onNavigate(AppRoute.TagsAndReminders)
                }
                ToolPreference("Statistics", "Profile and notification overview", MiuixIcons.Info) {
                    onNavigate(AppRoute.Statistics)
                }
            }
        }
        item { SectionHeading("Diagnostics") }
        item {
            GroupedCard {
                ToolPreference("Reader diagnostics", "Reader types and current availability", MiuixIcons.Refresh) {
                    onNavigate(AppRoute.ReaderSettings)
                }
                ToolPreference("ISD-R AIDs", "Manage compatibility AID candidates", MiuixIcons.Info) {
                    onNavigate(AppRoute.AidManager)
                }
                ToolPreference("Activity logs", "Inspect LPA and reader events", MiuixIcons.Messages) {
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
        item { SectionHeading("Personalisation") }
        item {
            GroupedCard {
                ArrowPreference(
                    title = "Appearance & Theme",
                    summary = "Color mode, palette, interface scale and bottom bar style",
                    onClick = { onNavigate(AppRoute.AppearanceSettings) },
                )
                ArrowPreference(
                    title = "Profile display",
                    summary = "Layout, sorting and profile search",
                    onClick = { onNavigate(AppRoute.ProfileDisplaySettings) },
                )
            }
        }
        item { SectionHeading("LPA") }
        item {
            GroupedCard {
                ArrowPreference(
                    title = "Reader types",
                    summary = "NBridge, OMAPI, USB CCID, telephony, BLE and remote",
                    onClick = { onNavigate(AppRoute.ReaderSettings) },
                )
                ArrowPreference(
                    title = "Notification processing",
                    summary = "Automatic send and removal policies",
                    onClick = { onNavigate(AppRoute.NotificationSettings) },
                )
                ArrowPreference(
                    title = "Tags & reminders",
                    summary = "Tags, reminder permissions and scheduled alerts",
                    onClick = { onNavigate(AppRoute.TagsAndReminders) },
                )
                ArrowPreference(
                    title = "Advanced LPA settings",
                    summary = "MSS, IMEI, AIDs and developer diagnostics",
                    onClick = { onNavigate(AppRoute.AdvancedSettings) },
                )
            }
        }
        item { SectionHeading("Privacy") }
        item {
            GroupedCard {
                ArrowPreference(
                    title = "Privacy & Nekoko Cloud",
                    summary = "Redaction, operator icons and profile size predictions",
                    onClick = { onNavigate(AppRoute.PrivacySettings) },
                )
            }
        }
        item { SectionHeading("App") }
        item {
            GroupedCard {
                ArrowPreference(title = "Logs", summary = "Recent app and LPA activity", onClick = { onNavigate(AppRoute.Logs) })
                ArrowPreference(title = "About HyperLPA", summary = "Version, licenses and implementation notes", onClick = { onNavigate(AppRoute.About) })
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
            Surface(
                shape = CircleShape,
                color = MiuixTheme.colorScheme.primaryContainer,
                contentColor = MiuixTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(9.dp).size(20.dp))
            }
        },
        onClick = onClick,
    )
}
