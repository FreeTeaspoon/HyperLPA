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
import androidx.compose.foundation.lazy.items
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
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.redactIdentifier
import app.hyperlpa.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
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
) {
    val pageState = when {
        !state.lpa.initialized ||
            (state.lpa.operation is LpaOperation.DiscoveringReaders && state.lpa.profiles.isEmpty()) -> PageStateKind.LOADING
        state.lpa.readers.isEmpty() -> PageStateKind.ERROR
        state.lpa.selectedReader == null -> PageStateKind.EMPTY
        state.profiles.isEmpty() -> PageStateKind.EMPTY
        else -> PageStateKind.CONTENT
    }

    CenteredContent(modifier = modifier) { sidePadding ->
        if (state.settings.profileLayout == ProfileLayout.WATERFALL && pageState == PageStateKind.CONTENT) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(280.dp),
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
                items(state.profiles, key = ProfileInfo::iccid) { profile ->
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
                item(key = "state") {
                    PageStateHost(
                        state = pageState,
                        loadingMessage = "Looking for eUICC readers",
                        emptyTitle = if (state.lpa.selectedReader == null) "Choose a reader" else "No profiles installed",
                        emptyMessage = if (state.lpa.selectedReader == null) {
                            "Select an available secure-element reader to continue."
                        } else {
                            "Download an activation code to install your first eSIM profile."
                        },
                        errorTitle = "No eUICC reader found",
                        errorMessage = "Connect a USB or BLE reader, install NBridge, or enable OMAPI access.",
                        onRetry = onRefreshReaders,
                    ) {
                        Column {
                            state.profiles.forEach { profile ->
                                ProfileCard(
                                    profile = profile,
                                    state = state,
                                    onOpen = { onOpenProfile(profile) },
                                    onEnableChange = { enabled -> onEnableChange(profile.iccid, enabled) },
                                )
                            }
                        }
                    }
                }
                if (state.lpa.selectedReader != null && state.profiles.isEmpty()) {
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

@Composable
private fun ProfilesHeader(
    state: HyperLpaUiState,
    onSearchChange: (String) -> Unit,
    onSelectReader: (String) -> Unit,
    onRefreshReaders: () -> Unit,
    onOpenEuiccDetails: () -> Unit,
) {
    SectionHeading("Reader")
    GroupedCard {
        if (state.lpa.readers.isEmpty()) {
            ArrowPreference(
                title = "Find readers",
                summary = "Scan NBridge, OMAPI and USB CCID sources",
                onClick = onRefreshReaders,
            )
        } else {
            val selectedIndex = state.lpa.readers.indexOfFirst { it.id == state.lpa.selectedReaderId }.coerceAtLeast(0)
            OverlayDropdownPreference(
                title = "Active reader",
                summary = state.lpa.selectedReader?.detail ?: "Select a reader",
                items = state.lpa.readers.map { it.name },
                selectedIndex = selectedIndex,
                onSelectedIndexChange = { index -> state.lpa.readers.getOrNull(index)?.id?.let(onSelectReader) },
            )
            state.lpa.euiccInfo?.let { info ->
                ArrowPreference(
                    title = "EID",
                    summary = redactIdentifier(
                        value = info.eid,
                        mode = state.settings.eidRedaction,
                        reveal = state.settings.revealSensitiveData,
                    ),
                    onClick = onOpenEuiccDetails,
                )
            }
        }
    }
    AnimatedVisibility(visible = state.settings.showProfileSearch && state.lpa.selectedReader != null) {
        Column {
            SectionHeading("Profiles")
            TextField(
                value = state.searchQuery,
                onValueChange = onSearchChange,
                label = "Search profiles",
                useLabelAsPlaceholder = true,
                singleLine = true,
                leadingIcon = { Icon(MiuixIcons.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
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
    val operatorAndTags = buildList {
        add(profile.providerName.ifBlank { "Unknown operator" })
        addAll(profile.tags.filter(String::isNotBlank))
    }.joinToString(" · ")

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (isEnabled) {
                    MiuixTheme.colorScheme.primaryContainer
                } else {
                    MiuixTheme.colorScheme.secondaryContainer
                },
                contentColor = if (isEnabled) {
                    MiuixTheme.colorScheme.onPrimaryContainer
                } else {
                    MiuixTheme.colorScheme.onSecondaryContainer
                },
            ) {
                Icon(
                    imageVector = MiuixIcons.BankCards,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile.nickname.ifBlank { profile.name.ifBlank { "eSIM profile" } },
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = operatorAndTags,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = redactIdentifier(
                        profile.iccid,
                        state.settings.iccidRedaction,
                        state.settings.revealSensitiveData,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
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

@Composable
fun NotificationsScreen(
    state: HyperLpaUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onProcess: (Long) -> Unit,
    onDelete: (Long) -> Unit,
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

@Composable
private fun NotificationCard(
    notification: LpaNotification,
    onProcess: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    GroupedCard {
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
                ToolPreference("Tags", "Group and label installed profiles", MiuixIcons.BankCards) {
                    onNavigate(AppRoute.TagManager)
                }
                ToolPreference("Scheduled reminders", "Review upcoming profile reminders", MiuixIcons.Messages) {
                    onNavigate(AppRoute.ScheduledReminders)
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
                    summary = "${state.settings.profileLayout.name.lowercase()} · ${state.settings.profileSort.name.lowercase()}",
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
                    title = "Redaction and metadata",
                    summary = "EID, ICCID, operator icons and size estimates",
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
