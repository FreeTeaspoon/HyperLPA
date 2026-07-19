package app.hyperlpa.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ActivityLogEntry
import app.hyperlpa.domain.model.DownloadRequest
import app.hyperlpa.domain.model.EuiccInfo
import app.hyperlpa.domain.model.LogLevel
import app.hyperlpa.domain.model.LpaNotification
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.ui.components.EmptyState
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.redactIdentifier
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProfileDetailsScreen(
    profile: ProfileInfo?,
    settings: AppSettings,
    onBack: () -> Unit,
    onEnableChange: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onSetTags: (Set<String>) -> Unit,
    onSetReminder: (String, Instant?) -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }
    var nickname by remember(profile?.nickname) { mutableStateOf(profile?.nickname.orEmpty()) }
    var showDelete by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var tagsText by remember(profile?.tags) { mutableStateOf(profile?.tags?.joinToString(", ").orEmpty()) }
    var showReminder by remember { mutableStateOf(false) }

    DetailLazyScaffold(title = profile?.nickname?.ifBlank { profile.name }.orEmpty().ifBlank { "Profile" }, onBack = onBack) { _ ->
        if (profile == null) {
            item {
                EmptyState(
                    title = "Profile unavailable",
                    message = "Reconnect the eUICC reader and refresh the profile list.",
                    icon = MiuixIcons.BankCards,
                )
            }
        } else {
            item {
                ProfileHero(profile = profile, settings = settings)
            }
            item { SectionHeading("Profile") }
            item {
                GroupedCard {
                    SwitchPreference(
                        checked = profile.state == ProfileState.ENABLED,
                        onCheckedChange = onEnableChange,
                        title = "Enabled",
                        summary = if (profile.state == ProfileState.ENABLED) {
                            "This profile is currently active"
                        } else {
                            "Enable this profile on the connected eUICC"
                        },
                    )
                    ArrowPreference(
                        title = "Display name",
                        summary = profile.nickname.ifBlank { "Use profile name" },
                        onClick = { showRename = true },
                    )
                    ArrowPreference(
                        title = "Tags",
                        summary = profile.tags.takeIf { it.isNotEmpty() }?.joinToString() ?: "No tags",
                        onClick = { showTags = true },
                    )
                    ArrowPreference(
                        title = "Reminder",
                        summary = profile.reminderAt?.formatDateTime() ?: "No reminder scheduled",
                        onClick = { showReminder = true },
                    )
                }
            }
            item { SectionHeading("Identifiers") }
            item {
                GroupedCard {
                    ValuePreference(
                        title = "ICCID",
                        value = redactIdentifier(profile.iccid, settings.iccidRedaction, settings.revealSensitiveData),
                    )
                    ValuePreference(title = "ISD-P AID", value = profile.isdPAid.ifBlank { "Unavailable" })
                    ValuePreference(title = "Profile class", value = profile.profileClass.displayName())
                    ValuePreference(title = "Provider", value = profile.providerName.ifBlank { "Unknown" })
                }
            }
            item { SectionHeading("Danger zone") }
            item {
                GroupedCard {
                    ArrowPreference(
                        title = "Delete profile",
                        summary = "Permanently remove this profile from the eUICC",
                        titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                            color = MiuixTheme.colorScheme.error,
                        ),
                        onClick = { showDelete = true },
                    )
                }
            }
        }
    }

    OverlayDialog(
        show = showRename,
        title = "Rename profile",
        summary = "The nickname is stored on the eUICC.",
        onDismissRequest = { showRename = false },
    ) {
        Column {
            TextField(
                value = nickname,
                onValueChange = { nickname = it.take(64) },
                label = "Profile name",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            DialogActionRow(
                onCancel = { showRename = false },
                confirmText = "Rename",
                onConfirm = {
                    onRename(nickname.trim())
                    showRename = false
                },
            )
        }
    }

    OverlayDialog(
        show = showDelete,
        title = "Delete this profile?",
        summary = "This cannot be undone. You may need the original activation code to install it again.",
        onDismissRequest = { showDelete = false },
    ) {
        DialogActionRow(
            onCancel = { showDelete = false },
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                showDelete = false
                onDelete()
                onBack()
            },
        )
    }

    OverlayBottomSheet(
        show = showTags,
        title = "Profile tags",
        onDismissRequest = { showTags = false },
    ) {
        Column(Modifier.imePadding()) {
            Text(
                text = "Separate tags with commas. Tags stay on this device and can be used for filtering and reminders.",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(14.dp))
            TextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = "Travel, work, backup…",
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            DialogActionRow(
                onCancel = { showTags = false },
                confirmText = "Save",
                onConfirm = {
                    onSetTags(tagsText.toTagSet())
                    showTags = false
                },
            )
        }
    }

    OverlayBottomSheet(
        show = showReminder,
        title = "Profile reminder",
        onDismissRequest = { showReminder = false },
    ) {
        Column {
            val label = profile?.nickname?.ifBlank { profile.name }.orEmpty().ifBlank { "eSIM profile" }
            ReminderOption("Tomorrow", "In 24 hours") {
                onSetReminder(label, Instant.now().plus(Duration.ofDays(1)))
                showReminder = false
            }
            ReminderOption("In one week", "Seven days from now") {
                onSetReminder(label, Instant.now().plus(Duration.ofDays(7)))
                showReminder = false
            }
            ReminderOption("In one month", "Thirty days from now") {
                onSetReminder(label, Instant.now().plus(Duration.ofDays(30)))
                showReminder = false
            }
            ReminderOption("Clear reminder", profile?.reminderAt?.formatDateTime() ?: "No reminder scheduled") {
                onSetReminder(label, null)
                showReminder = false
            }
        }
    }
}

@Composable
fun DownloadProfileScreen(
    initialValue: String,
    imei: String,
    busy: Boolean,
    onBack: () -> Unit,
    onValueChange: (String) -> Unit,
    onScanQr: ((String?) -> Unit) -> Unit,
    onDownload: (DownloadRequest) -> Unit,
) {
    var localValue by remember(initialValue) { mutableStateOf(initialValue) }
    val requestResult = remember(localValue, imei) {
        runCatching { DownloadRequest.parse(localValue, imei.takeIf(String::isNotBlank)) }
    }

    DetailLazyScaffold(title = "Download profile", onBack = onBack) { _ ->
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).imePadding()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Paste an LPA activation code or enter an SM-DP+ address. HyperLPA talks directly to the provisioning server.",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(18.dp))
                TextField(
                    value = localValue,
                    onValueChange = {
                        localValue = it
                        onValueChange(it)
                    },
                    label = "LPA:1\$address\$matching-id or SM-DP+ address",
                    useLabelAsPlaceholder = true,
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    text = "Scan QR code",
                    onClick = {
                        onScanQr { scanned ->
                            scanned?.let {
                                localValue = it
                                onValueChange(it)
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item { SectionHeading("Provisioning request") }
        item {
            GroupedCard {
                val request = requestResult.getOrNull()
                ValuePreference(title = "SM-DP+", value = request?.smdpAddress ?: "Waiting for a valid code")
                ValuePreference(title = "Matching ID", value = request?.matchingId ?: "Not included")
                ValuePreference(title = "Confirmation code", value = if (request?.confirmationCode.isNullOrBlank()) "Not included" else "Included")
                ValuePreference(title = "IMEI", value = imei.ifBlank { "Not supplied" })
            }
        }
        requestResult.exceptionOrNull()?.message?.let { message ->
            item {
                Text(
                    text = message,
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                )
            }
        }
        item {
            Button(
                onClick = { requestResult.getOrNull()?.let(onDownload) },
                enabled = requestResult.isSuccess && localValue.isNotBlank() && !busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 18.dp)
                    .defaultMinSize(minHeight = 52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(size = 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Downloading…")
                } else {
                    Icon(MiuixIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Download profile")
                }
            }
        }
    }
}

@Composable
fun BatchDownloadScreen(
    imei: String,
    onBack: () -> Unit,
    onDownload: (DownloadRequest) -> Unit,
) {
    var values by remember { mutableStateOf("") }
    var queued by remember { mutableIntStateOf(0) }
    val lines = values.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val parsed = lines.map { line -> runCatching { DownloadRequest.parse(line, imei.takeIf(String::isNotBlank)) } }
    val validCount = parsed.count(Result<DownloadRequest>::isSuccess)

    DetailLazyScaffold(title = "Batch download", onBack = onBack) { _ ->
        item {
            Column(Modifier.fillMaxWidth().padding(12.dp).imePadding()) {
                Text(
                    text = "Enter one activation code per line. Downloads are submitted in order and serialized by the LPA engine.",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
                TextField(
                    value = values,
                    onValueChange = { values = it },
                    label = "Activation codes",
                    useLabelAsPlaceholder = true,
                    minLines = 8,
                    maxLines = 16,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            GroupedCard {
                ValuePreference(title = "Codes", value = lines.size.toString())
                ValuePreference(title = "Valid", value = validCount.toString())
                ValuePreference(title = "Invalid", value = (lines.size - validCount).toString())
                AnimatedVisibility(visible = queued > 0) {
                    ValuePreference(title = "Submitted", value = queued.toString())
                }
            }
        }
        item {
            Button(
                onClick = {
                    val requests = parsed.mapNotNull(Result<DownloadRequest>::getOrNull)
                    queued = requests.size
                    requests.forEach(onDownload)
                },
                enabled = lines.isNotEmpty() && validCount == lines.size,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp),
            ) {
                Text("Queue $validCount ${if (validCount == 1) "profile" else "profiles"}")
            }
        }
    }
}

@Composable
fun EuiccDetailsScreen(
    info: EuiccInfo?,
    settings: AppSettings,
    onBack: () -> Unit,
    onReset: () -> Unit,
) {
    var showReset by remember { mutableStateOf(false) }
    DetailLazyScaffold(title = "eUICC information", onBack = onBack) { _ ->
        if (info == null) {
            item {
                EmptyState(
                    title = "No eUICC connected",
                    message = "Select a reader before opening eUICC information.",
                    icon = MiuixIcons.Info,
                )
            }
        } else {
            item { SectionHeading("Identity") }
            item {
                GroupedCard {
                    ValuePreference(
                        title = "EID",
                        value = redactIdentifier(info.eid, settings.eidRedaction, settings.revealSensitiveData),
                    )
                    ValuePreference(title = "SAS accreditation", value = info.sasAccreditationNumber.ifBlank { "Unavailable" })
                    ValuePreference(title = "Firmware", value = info.firmwareVersion.ifBlank { "Unavailable" })
                }
            }
            item { SectionHeading("Specifications") }
            item {
                GroupedCard {
                    ValuePreference(title = "SGP.22", value = info.sgp22Version.ifBlank { "Unavailable" })
                    ValuePreference(title = "Profile package", value = info.profileVersion.ifBlank { "Unavailable" })
                    ValuePreference(title = "GlobalPlatform", value = info.globalPlatformVersion.ifBlank { "Unavailable" })
                    ValuePreference(title = "Protection profile", value = info.protectionProfileVersion.ifBlank { "Unavailable" })
                }
            }
            item { SectionHeading("Memory and keys") }
            item {
                GroupedCard {
                    ValuePreference(title = "Non-volatile memory", value = info.freeNonVolatileMemory?.let(::formatBytes) ?: "Unavailable")
                    ValuePreference(title = "Volatile memory", value = info.freeVolatileMemory?.let(::formatBytes) ?: "Unavailable")
                    ValuePreference(title = "Signing key IDs", value = info.signingKeyIds.size.toString())
                    ValuePreference(title = "Verification key IDs", value = info.verificationKeyIds.size.toString())
                }
            }
            if (!settings.hideEuiccMemoryReset) {
                item { SectionHeading("Maintenance") }
                item {
                    GroupedCard {
                        ArrowPreference(
                            title = "Reset eUICC memory",
                            summary = "Remove test data and reset supported memory areas",
                            onClick = { showReset = true },
                        )
                    }
                }
            }
        }
    }
    OverlayDialog(
        show = showReset,
        title = "Reset eUICC memory?",
        summary = "This low-level operation can remove profiles or provisioning state, depending on the eUICC implementation.",
        onDismissRequest = { showReset = false },
    ) {
        DialogActionRow(
            onCancel = { showReset = false },
            confirmText = "Reset",
            destructive = true,
            onConfirm = {
                showReset = false
                onReset()
            },
        )
    }
}

@Composable
fun TagManagerScreen(
    profiles: List<ProfileInfo>,
    onBack: () -> Unit,
    onSetTags: (String, Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf<ProfileInfo?>(null) }
    var tagsText by remember { mutableStateOf("") }
    val allTags = profiles.flatMap { it.tags }.groupingBy(String::lowercase).eachCount().toList().sortedByDescending { it.second }

    DetailLazyScaffold(title = "Profile tags", onBack = onBack) { _ ->
        if (profiles.isEmpty()) {
            item { EmptyState("No profiles", "Connect an eUICC to organise its profiles.", icon = MiuixIcons.BankCards) }
        } else {
            item { SectionHeading("Tag overview") }
            item {
                GroupedCard {
                    if (allTags.isEmpty()) {
                        ArrowPreference(title = "No tags yet", summary = "Open a profile below to add tags", enabled = false)
                    } else {
                        allTags.forEach { (tag, count) -> ValuePreference(tag, "$count ${if (count == 1) "profile" else "profiles"}") }
                    }
                }
            }
            item { SectionHeading("Profiles") }
            item {
                GroupedCard {
                    profiles.forEach { profile ->
                        ArrowPreference(
                            title = profile.nickname.ifBlank { profile.name.ifBlank { "eSIM profile" } },
                            summary = profile.tags.takeIf { it.isNotEmpty() }?.joinToString() ?: "No tags",
                            onClick = {
                                selected = profile
                                tagsText = profile.tags.joinToString(", ")
                            },
                        )
                    }
                }
            }
        }
    }
    OverlayBottomSheet(
        show = selected != null,
        title = selected?.nickname?.ifBlank { selected?.name.orEmpty() }?.ifBlank { "Profile tags" },
        onDismissRequest = { selected = null },
    ) {
        Column(Modifier.imePadding()) {
            TextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = "Comma-separated tags",
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            DialogActionRow(
                onCancel = { selected = null },
                confirmText = "Save",
                onConfirm = {
                    selected?.let { onSetTags(it.iccid, tagsText.toTagSet()) }
                    selected = null
                },
            )
        }
    }
}

@Composable
fun ScheduledRemindersScreen(
    profiles: List<ProfileInfo>,
    onBack: () -> Unit,
    onClear: (ProfileInfo) -> Unit,
) {
    val scheduled = profiles.filter { it.reminderAt != null }.sortedBy { it.reminderAt }
    DetailLazyScaffold(title = "Scheduled reminders", onBack = onBack) { _ ->
        if (scheduled.isEmpty()) {
            item {
                EmptyState(
                    title = "No reminders",
                    message = "Open a profile and schedule a reminder for tomorrow, next week, or next month.",
                    icon = MiuixIcons.Messages,
                )
            }
        } else {
            item { SectionHeading("Upcoming") }
            item {
                GroupedCard {
                    scheduled.forEach { profile ->
                        ArrowPreference(
                            title = profile.nickname.ifBlank { profile.name.ifBlank { "eSIM profile" } },
                            summary = profile.reminderAt?.formatDateTime(),
                            endActions = {
                                TextButton(text = "Clear", onClick = { onClear(profile) })
                            },
                            enabled = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatisticsScreen(
    profiles: List<ProfileInfo>,
    notifications: List<LpaNotification>,
    onBack: () -> Unit,
) {
    val enabled = profiles.count { it.state == ProfileState.ENABLED }
    val tagged = profiles.count { it.tags.isNotEmpty() }
    val estimatedBytes = profiles.mapNotNull(ProfileInfo::estimatedBytes).sum()
    DetailLazyScaffold(title = "Statistics", onBack = onBack) { _ ->
        item { SectionHeading("Profiles") }
        item {
            GroupedCard {
                StatRow("Total profiles", profiles.size.toString())
                StatRow("Enabled", enabled.toString())
                StatRow("Disabled", (profiles.size - enabled).toString())
                StatRow("Tagged", tagged.toString())
                StatRow("Testing", profiles.count { it.profileClass == ProfileClass.TESTING }.toString())
                StatRow("Provisioning", profiles.count { it.profileClass == ProfileClass.PROVISIONING }.toString())
            }
        }
        item { SectionHeading("eUICC activity") }
        item {
            GroupedCard {
                StatRow("Pending notifications", notifications.size.toString())
                StatRow("Estimated profile data", if (estimatedBytes > 0) formatBytes(estimatedBytes.toInt()) else "Not available")
                StatRow("Unique providers", profiles.map(ProfileInfo::providerName).filter(String::isNotBlank).distinct().size.toString())
                StatRow("Unique tags", profiles.flatMap(ProfileInfo::tags).map(String::lowercase).distinct().size.toString())
            }
        }
    }
}

@Composable
fun LogsScreen(
    logs: List<ActivityLogEntry>,
    onBack: () -> Unit,
) {
    val levels = listOf<LogLevel?>(null) + LogLevel.entries
    var selectedLevel by remember { mutableIntStateOf(0) }
    val visibleLogs = logs.asReversed().filter { levels[selectedLevel] == null || it.level == levels[selectedLevel] }
    DetailLazyScaffold(title = "Activity logs", onBack = onBack) { _ ->
        item { SectionHeading("Filter") }
        item {
            GroupedCard {
                OverlayDropdownPreference(
                    items = listOf("All levels") + LogLevel.entries.map { it.displayName() },
                    selectedIndex = selectedLevel,
                    title = "Log level",
                    summary = "${visibleLogs.size} visible ${if (visibleLogs.size == 1) "entry" else "entries"}",
                    onSelectedIndexChange = { selectedLevel = it },
                )
            }
        }
        if (visibleLogs.isEmpty()) {
            item { EmptyState("No log entries", "LPA activity and errors will appear here.", icon = MiuixIcons.Search) }
        } else {
            item { SectionHeading("Recent") }
            visibleLogs.forEach { entry ->
                item(key = "${entry.timestamp}-${entry.tag}-${entry.message}") {
                    LogCard(entry)
                }
            }
        }
    }
}

@Composable
private fun ProfileHero(profile: ProfileInfo, settings: AppSettings) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = if (profile.state == ProfileState.ENABLED) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.secondaryContainer,
            contentColor = if (profile.state == ProfileState.ENABLED) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(MiuixIcons.BankCards, contentDescription = null, modifier = Modifier.padding(20.dp).size(38.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = profile.nickname.ifBlank { profile.name.ifBlank { "eSIM profile" } },
            style = MiuixTheme.textStyles.title1,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = profile.providerName.ifBlank { "Unknown operator" },
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = redactIdentifier(profile.iccid, settings.iccidRedaction, settings.revealSensitiveData),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ValuePreference(title: String, value: String) {
    ArrowPreference(
        title = title,
        summary = value,
        enabled = false,
    )
}

@Composable
private fun ReminderOption(title: String, summary: String, onClick: () -> Unit) {
    ArrowPreference(title = title, summary = summary, onClick = onClick)
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LogCard(entry: ActivityLogEntry) {
    GroupedCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = when (entry.level) {
                        LogLevel.ERROR -> MiuixTheme.colorScheme.errorContainer
                        LogLevel.WARNING -> MiuixTheme.colorScheme.secondaryContainer
                        else -> MiuixTheme.colorScheme.primaryContainer
                    },
                    contentColor = when (entry.level) {
                        LogLevel.ERROR -> MiuixTheme.colorScheme.onErrorContainer
                        LogLevel.WARNING -> MiuixTheme.colorScheme.onSecondaryContainer
                        else -> MiuixTheme.colorScheme.onPrimaryContainer
                    },
                ) {
                    Icon(
                        imageVector = when (entry.level) {
                            LogLevel.ERROR -> MiuixIcons.Delete
                            LogLevel.WARNING -> MiuixIcons.Info
                            LogLevel.DEBUG -> MiuixIcons.Search
                            LogLevel.INFO -> MiuixIcons.Refresh
                        },
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp).size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.tag, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
                    Text(
                        entry.timestamp.formatDateTime(),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Text(
                    entry.level.displayName(),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(entry.message, style = MiuixTheme.textStyles.body2)
        }
    }
}

@Composable
private fun DialogActionRow(
    onCancel: () -> Unit,
    confirmText: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        TextButton(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            colors = if (destructive) {
                ButtonDefaults.textButtonColors(
                    textColor = MiuixTheme.colorScheme.error,
                )
            } else {
                ButtonDefaults.textButtonColorsPrimary()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

private fun String.toTagSet(): Set<String> = split(',', '\n')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .take(16)
    .toSet()

private fun Instant.formatDateTime(): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())
    .format(this)

private fun Enum<*>.displayName(): String = name
    .lowercase()
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

private fun formatBytes(bytes: Int): String {
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024f
    if (kib < 1024f) return "${(kib * 10).roundToInt() / 10f} KiB"
    val mib = kib / 1024f
    return "${(mib * 10).roundToInt() / 10f} MiB"
}
