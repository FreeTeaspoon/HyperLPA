package app.hyperlpa.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.metadata.normalizeProfileTags
import app.hyperlpa.data.metadata.providerIconKey
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.RedactionMode
import app.hyperlpa.domain.model.ActivityLogEntry
import app.hyperlpa.domain.model.DownloadRequest
import app.hyperlpa.domain.model.EuiccInfo
import app.hyperlpa.domain.model.LogLevel
import app.hyperlpa.domain.model.LpaNotification
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileDownloadPreview
import app.hyperlpa.domain.model.ProfileDownloadResult
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.domain.model.analyzeIccid
import app.hyperlpa.ui.components.EmptyState
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.ProfileArtwork
import app.hyperlpa.ui.components.ResolvedProfileArtwork
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.FormattedProfileDisplayName
import app.hyperlpa.ui.components.formatProfileDisplayName
import app.hyperlpa.ui.components.rememberProfileArtworkBitmap
import app.hyperlpa.ui.components.redactIdentifier
import app.hyperlpa.ui.components.effect.ProfileGradientBackdrop
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Scan
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
    suggestedTags: Set<String>,
    operatorIcon: ByteArray?,
    hasProfileIcon: Boolean,
    hasProviderIcon: Boolean,
    onBack: () -> Unit,
    onEnableChange: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onSetTags: (Set<String>) -> Unit,
    onSetReminder: (String, Instant?) -> Unit,
    onRequestNotificationPermission: ((Boolean) -> Unit) -> Unit,
    onSetIcon: (uri: String?, applyToProvider: Boolean) -> Unit,
    onApplyIconToProvider: () -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }
    var nickname by remember(profile?.nickname) { mutableStateOf(profile?.nickname.orEmpty()) }
    var showDelete by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var editableTags by remember(profile?.tags) { mutableStateOf(profile?.tags.orEmpty()) }
    var newTag by remember { mutableStateOf("") }
    var showReminder by remember { mutableStateOf(false) }
    var showIconOptions by remember { mutableStateOf(false) }
    var showRemoveProfileIconConfirmation by remember { mutableStateOf(false) }
    var pickForProvider by remember { mutableStateOf(false) }
    var technicalDetailsExpanded by rememberSaveable(profile?.iccid) { mutableStateOf(false) }
    val context = LocalContext.current
    val providerLabel = profile?.providerName?.trim().orEmpty().ifBlank { "this provider" }
    val canShareByProvider = providerIconKey(profile?.providerName) != null
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onSetIcon(it.toString(), pickForProvider) }
    }
    val displayName = remember(profile, settings.phoneFormatStrategy) {
        profile?.let { formatProfileDisplayName(it, settings.phoneFormatStrategy) }
    }
    val formattedNickname = remember(profile, settings.phoneFormatStrategy) {
        profile?.nickname?.takeIf(String::isNotBlank)?.let { nicknameValue ->
            formatProfileDisplayName(
                rawName = nicknameValue,
                strategy = settings.phoneFormatStrategy,
                mcc = profile.mcc,
                mnc = profile.mnc,
                iccid = profile.iccid,
            ).fullText
        }
    }
    val artworkBitmap = rememberProfileArtworkBitmap(profile, operatorIcon)
    val iccidDetails = remember(profile?.iccid) { profile?.iccid?.let(::analyzeIccid) }
    val collapsedTitle = displayName?.nameText
        ?.takeIf(String::isNotBlank)
        ?: profile?.providerName?.takeIf(String::isNotBlank)
        ?: "Profile"

    DetailLazyScaffold(
        title = "",
        onBack = onBack,
        collapsedTitle = collapsedTitle,
        collapsedBarRevealStart = 132.dp,
        background = if (profile == null) null else {
            { ProfileGradientBackdrop(bitmap = artworkBitmap) }
        },
    ) { _ ->
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
                ProfileHero(
                    profile = profile,
                    settings = settings,
                    artworkBitmap = artworkBitmap,
                    displayName = requireNotNull(displayName),
                )
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
                        summary = formattedNickname ?: "Use profile name",
                        onClick = { showRename = true },
                    )
                    ArrowPreference(
                        title = "Custom icon",
                        summary = when {
                            hasProfileIcon -> "Using a custom image for this profile"
                            hasProviderIcon -> "Using a shared icon for $providerLabel"
                            else -> "Choose a photo to replace the operator icon"
                        },
                        onClick = { showIconOptions = true },
                    )
                    ArrowPreference(
                        title = "Tags",
                        summary = profile.tags.takeIf { it.isNotEmpty() }?.joinToString() ?: "No tags",
                        onClick = {
                            editableTags = profile.tags
                            newTag = ""
                            showTags = true
                        },
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
                        value = redactIdentifier(profile.iccid, settings.iccidRedaction),
                    )
                    ValuePreference(
                        title = "ICCID checksum",
                        value = when (iccidDetails?.checksumValid) {
                            true -> "Valid"
                            false -> "Invalid"
                            null -> "Unavailable"
                        },
                    )
                    if (settings.iccidRedaction == RedactionMode.NONE) {
                        iccidDetails?.issuerPrefix?.let { prefix ->
                            ValuePreference(title = "Issuer prefix", value = prefix)
                        }
                    }
                    ValuePreference(title = "ISD-P AID", value = profile.isdPAid.ifBlank { "Unavailable" })
                }
            }
            item { SectionHeading("Metadata") }
            item {
                GroupedCard {
                    ValuePreference(title = "Profile name", value = profile.name.ifBlank { "Unavailable" })
                    ValuePreference(title = "eUICC nickname", value = profile.nickname.ifBlank { "Not set" })
                    ValuePreference(title = "Profile class", value = profile.profileClass.displayName())
                    ValuePreference(title = "Provider", value = profile.providerName.ifBlank { "Unknown" })
                    if (!profile.mcc.isNullOrBlank() || !profile.mnc.isNullOrBlank()) {
                        ValuePreference(
                            title = "Network",
                            value = listOfNotNull(
                                profile.mcc?.let { "MCC $it" },
                                profile.mnc?.let { "MNC $it" },
                            ).joinToString(" · "),
                        )
                    }
                    profile.estimatedBytes?.takeIf { it > 0 }?.let { bytes ->
                        ValuePreference(
                            title = if (profile.sizeIsEstimated) "Estimated profile storage" else "Measured profile storage",
                            value = "${if (profile.sizeIsEstimated) "~" else ""}${formatBytes(bytes.toInt())}",
                        )
                    }
                }
            }
            item { SectionHeading("Advanced") }
            item {
                GroupedCard {
                    BasicComponent(
                        title = "Technical profile data",
                        summary = if (technicalDetailsExpanded) "Hide technical fields" else {
                            "Group identifiers, notification configuration and policy rules"
                        },
                        endActions = {
                            Icon(
                                imageVector = if (technicalDetailsExpanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                                contentDescription = if (technicalDetailsExpanded) "Show less" else "Show more",
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        onClick = { technicalDetailsExpanded = !technicalDetailsExpanded },
                    )
                    AnimatedVisibility(
                        visible = technicalDetailsExpanded,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        Column {
                            profile.gid1?.takeIf(String::isNotBlank)?.let { ValuePreference("GID1", it) }
                            profile.gid2?.takeIf(String::isNotBlank)?.let { ValuePreference("GID2", it) }
                            profile.smdpAddress?.takeIf(String::isNotBlank)?.let {
                                ValuePreference("Notification address", it)
                            }
                            if (profile.notificationOperations.isNotEmpty()) {
                                ValuePreference(
                                    "Notification events",
                                    formatTechnicalValues(profile.notificationOperations),
                                )
                            }
                            profile.dpOid?.takeIf(String::isNotBlank)?.let { ValuePreference("DP OID", it) }
                            if (profile.profilePolicyRules.isNotEmpty()) {
                                ValuePreference(
                                    "Profile policy rules",
                                    formatTechnicalValues(profile.profilePolicyRules),
                                )
                            }
                            if (profile.gid1.isNullOrBlank() &&
                                profile.gid2.isNullOrBlank() &&
                                profile.smdpAddress.isNullOrBlank() &&
                                profile.notificationOperations.isEmpty() &&
                                profile.dpOid.isNullOrBlank() &&
                                profile.profilePolicyRules.isEmpty()
                            ) {
                                ValuePreference("Technical data", "No additional fields reported")
                            }
                        }
                    }
                }
            }
            if (!settings.hideProfileDeletion) {
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
        show = showDelete && !settings.hideProfileDeletion,
        title = "Delete this profile?",
        summary = "This cannot be undone. You may need the original activation code to install it again.",
        onDismissRequest = { showDelete = false },
    ) {
        DialogActionRow(
            onCancel = { showDelete = false },
            confirmText = "Continue",
            destructive = true,
            onConfirm = {
                showDelete = false
                showDeleteConfirmation = true
            },
        )
    }

    OverlayDialog(
        show = showDeleteConfirmation && !settings.hideProfileDeletion,
        title = "Delete profile permanently?",
        summary = "This is your final confirmation. The profile will be permanently removed from the eUICC.",
        onDismissRequest = { showDeleteConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showDeleteConfirmation = false },
            confirmText = "Delete profile",
            destructive = true,
            onConfirm = {
                showDeleteConfirmation = false
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
        ProfileTagsEditor(
            tags = editableTags,
            suggestedTags = suggestedTags,
            newTag = newTag,
            onNewTagChange = { newTag = it },
            onTagsChange = { editableTags = it },
            onCancel = { showTags = false },
            onSave = { tags ->
                onSetTags(tags)
                showTags = false
            },
        )
    }

    OverlayBottomSheet(
        show = showReminder,
        title = "Profile reminder",
        onDismissRequest = { showReminder = false },
    ) {
        Column {
            val label = profile?.nickname?.ifBlank { profile.name }.orEmpty().ifBlank { "eSIM profile" }
            val setReminder: (Instant) -> Unit = { reminderAt ->
                onRequestNotificationPermission { granted ->
                    if (granted) {
                        onSetReminder(label, reminderAt)
                        showReminder = false
                    } else {
                        Toast.makeText(
                            context,
                            "Notification permission is required for reminders",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            ReminderOption("Tomorrow", "In 24 hours") {
                setReminder(Instant.now().plus(Duration.ofDays(1)))
            }
            ReminderOption("In one week", "Seven days from now") {
                setReminder(Instant.now().plus(Duration.ofDays(7)))
            }
            ReminderOption("In one month", "Thirty days from now") {
                setReminder(Instant.now().plus(Duration.ofDays(30)))
            }
            ReminderOption("Custom", "Choose a date and time") {
                showCustomReminderPicker(context, profile?.reminderAt) { reminderAt ->
                    setReminder(reminderAt)
                }
            }
            ReminderOption("Clear reminder", profile?.reminderAt?.formatDateTime() ?: "No reminder scheduled") {
                onSetReminder(label, null)
                showReminder = false
            }
            BottomSheetFooterSpacer()
        }
    }

    OverlayBottomSheet(
        show = showIconOptions,
        title = "Custom icon",
        onDismissRequest = { showIconOptions = false },
    ) {
        Column {
            ReminderOption(
                "Choose photo for this profile",
                "Only this profile uses the selected image",
            ) {
                pickForProvider = false
                showIconOptions = false
                pickIcon.launch("image/*")
            }
            if (canShareByProvider) {
                ReminderOption(
                    "Choose photo for all $providerLabel profiles",
                    "Any profile with this provider name will use the same image",
                ) {
                    pickForProvider = true
                    showIconOptions = false
                    pickIcon.launch("image/*")
                }
            }
            if (canShareByProvider && (hasProfileIcon || hasProviderIcon)) {
                ReminderOption(
                    "Use current icon for all $providerLabel profiles",
                    "Share the artwork already shown on this profile",
                ) {
                    onApplyIconToProvider()
                    showIconOptions = false
                }
            }
            if (hasProfileIcon) {
                ReminderOption(
                    "Remove icon for this profile",
                    "Restore the shared provider icon or operator artwork",
                ) {
                    showIconOptions = false
                    showRemoveProfileIconConfirmation = true
                }
            }
            if (hasProviderIcon) {
                ReminderOption(
                    "Remove shared $providerLabel icon",
                    "Clear the icon used by every profile with this provider name",
                ) {
                    onSetIcon(null, true)
                    showIconOptions = false
                }
            }
            BottomSheetFooterSpacer()
        }
    }

    OverlayDialog(
        show = showRemoveProfileIconConfirmation,
        title = "Remove icon for this profile?",
        summary = "The shared provider icon or operator artwork will be shown instead.",
        onDismissRequest = { showRemoveProfileIconConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showRemoveProfileIconConfirmation = false },
            confirmText = "Remove",
            destructive = true,
            onConfirm = {
                showRemoveProfileIconConfirmation = false
                onSetIcon(null, false)
            },
        )
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
    onContinue: (DownloadRequest) -> Unit,
) {
    var localValue by remember(initialValue) { mutableStateOf(initialValue) }
    val requestResult = remember(localValue, imei) {
        runCatching { DownloadRequest.parse(localValue, imei.takeIf(String::isNotBlank)) }
    }

    DetailLazyScaffold(
        title = "Download profile",
        onBack = onBack,
        actions = {
            IconButton(
                onClick = {
                    onScanQr { scanned ->
                        scanned?.let {
                            localValue = it
                            onValueChange(it)
                        }
                    }
                },
            ) {
                Icon(MiuixIcons.Scan, contentDescription = "Scan QR code")
            }
        },
    ) { _ ->
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Paste an LPA activation code or enter an SM-DP+ address.",
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
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
            }
        }
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
            val primaryColors = ButtonDefaults.buttonColorsPrimary()
            Button(
                onClick = { requestResult.getOrNull()?.let(onContinue) },
                enabled = requestResult.isSuccess && localValue.isNotBlank() && !busy,
                colors = primaryColors.copy(
                    disabledColor = if (busy) primaryColors.color else primaryColors.disabledColor,
                    disabledContentColor = if (busy) {
                        primaryColors.contentColor
                    } else {
                        primaryColors.disabledContentColor
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 18.dp)
                    .defaultMinSize(minHeight = 52.dp),
            ) {
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                foregroundColor = primaryColors.contentColor,
                                disabledForegroundColor = primaryColors.contentColor,
                                backgroundColor = primaryColors.contentColor.copy(alpha = 0.24f),
                            ),
                            size = 22.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Checking profile…")
                    }
                } else {
                    Text("Continue")
                }
            }
        }
    }
}

@Composable
fun ProfileDownloadConfirmationScreen(
    preview: ProfileDownloadPreview,
    cloudIcon: ByteArray?,
    estimatedDownloadBytes: Long?,
    enrichmentLoading: Boolean,
    showCancelConfirmation: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onDismissCancelConfirmation: () -> Unit,
    onConfirmCancel: () -> Unit,
) {
    val profile = preview.profile
    val artworkProfile = if (cloudIcon != null) profile.copy(iconBase64 = null) else profile
    val displayName = profile.name.ifBlank { profile.providerName }.ifBlank { "eSIM profile" }
    val network = listOfNotNull(profile.mcc, profile.mnc)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .ifBlank { "Unavailable" }
    var moreExpanded by rememberSaveable { mutableStateOf(false) }

    DetailLazyScaffold(title = "Download profile", onBack = onBack) { _ ->
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProfileArtwork(
                    profile = artworkProfile,
                    cloudIcon = cloudIcon,
                    isEnabled = true,
                    size = 72.dp,
                    cornerRadius = 18.dp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = displayName,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                profile.providerName
                    .takeIf { it.isNotBlank() && !it.equals(displayName, ignoreCase = true) }
                    ?.let { provider ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = provider,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }
        }
        item { SectionHeading("Profile information") }
        item {
            GroupedCard {
                ValuePreference(
                    title = "Provider",
                    value = profile.providerName.ifBlank { "Unavailable" },
                )
                ValuePreference(title = "ICCID", value = profile.iccid.ifBlank { "Unavailable" })
                ValuePreference(
                    title = "Available storage",
                    value = preview.freeNonVolatileMemory?.let { "${formatBytes(it)} free" }
                        ?: "Unavailable",
                )
                ValuePreference(
                    title = "Estimated download size",
                    value = when {
                        estimatedDownloadBytes != null -> "~${formatBytes(estimatedDownloadBytes)}"
                        enrichmentLoading -> "Checking Nekoko Cloud…"
                        else -> "Unavailable"
                    },
                )
                BasicComponent(
                    title = "More",
                    summary = if (moreExpanded) "Hide technical profile information" else {
                        "Network, profile class and group identifiers"
                    },
                    endActions = {
                        Icon(
                            imageVector = if (moreExpanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                            contentDescription = if (moreExpanded) "Show less" else "Show more",
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    onClick = { moreExpanded = !moreExpanded },
                )
                AnimatedVisibility(
                    visible = moreExpanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column {
                        ValuePreference(title = "Network", value = network)
                        ValuePreference(title = "Profile class", value = profile.profileClass.displayName())
                        profile.gid1?.takeIf(String::isNotBlank)?.let { ValuePreference("GID1", it) }
                        profile.gid2?.takeIf(String::isNotBlank)?.let { ValuePreference("GID2", it) }
                    }
                }
            }
        }
        item {
            Button(
                onClick = onDownload,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 18.dp)
                    .defaultMinSize(minHeight = 52.dp),
            ) {
                Icon(MiuixIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Download")
            }
        }
    }

    OverlayDialog(
        show = showCancelConfirmation,
        title = "Cancel profile download?",
        summary = "Going back will close the secure provisioning session. You can return to the activation code and try again.",
        onDismissRequest = onDismissCancelConfirmation,
    ) {
        DialogActionRow(
            onCancel = onDismissCancelConfirmation,
            cancelText = "Stay here",
            confirmText = "Go back",
            destructive = true,
            onConfirm = onConfirmCancel,
        )
    }
}

@Composable
fun ProfileDownloadResultScreen(
    result: ProfileDownloadResult,
    profile: ProfileInfo,
    busy: Boolean,
    onBack: () -> Unit,
    onEnable: () -> Unit,
    onRename: (String) -> Unit,
    onDone: () -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }
    var nickname by remember(profile.nickname, profile.name) {
        mutableStateOf(profile.nickname.ifBlank { profile.name })
    }

    DetailLazyScaffold(title = "Download profile", onBack = onBack) { _ ->
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MiuixTheme.colorScheme.primaryContainer,
                    contentColor = MiuixTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = null,
                        modifier = Modifier.padding(22.dp).size(42.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Installation successful",
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${profile.providerName.ifBlank { "The profile" }} was installed on your eUICC.",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item { SectionHeading("Storage") }
        item {
            GroupedCard {
                ValuePreference(
                    title = "Used by this profile",
                    value = result.installedBytes?.let(::formatBytes) ?: "Unavailable",
                )
                ValuePreference(
                    title = "Free storage",
                    value = result.freeNonVolatileMemory?.let(::formatBytes) ?: "Unavailable",
                )
            }
        }
        if (profile.state != ProfileState.ENABLED && profile.iccid.isNotBlank()) {
            item {
                Button(
                    onClick = onEnable,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                        .defaultMinSize(minHeight = 52.dp),
                ) {
                    Text("Enable profile")
                }
            }
        }
        if (profile.iccid.isNotBlank()) {
            item {
                Button(
                    onClick = { showRename = true },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                        .defaultMinSize(minHeight = 52.dp),
                ) {
                    Icon(MiuixIcons.Edit, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Rename profile")
                }
            }
        }
        item {
            Button(
                onClick = onDone,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 5.dp, end = 12.dp, bottom = 18.dp)
                    .defaultMinSize(minHeight = 52.dp),
            ) {
                Text("Done")
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
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
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
    reader: ReaderInfo?,
    installedProfileCount: Int,
    enabledProfileCount: Int,
    settings: AppSettings,
    onBack: () -> Unit,
    onReset: () -> Unit,
) {
    var showReset by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showFinalResetConfirmation by remember { mutableStateOf(false) }
    var advancedDetailsExpanded by rememberSaveable(info?.eid) { mutableStateOf(false) }
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
                        value = redactIdentifier(info.eid, settings.eidRedaction),
                    )
                    ValuePreference(
                        title = "eUICC category",
                        value = info.euiccCategory.takeIf(String::isNotBlank)
                            ?.let(::formatTechnicalValue)
                            ?: "Unavailable",
                    )
                    ValuePreference(title = "SAS accreditation", value = info.sasAccreditationNumber.ifBlank { "Unavailable" })
                    ValuePreference(title = "Firmware", value = info.firmwareVersion.ifBlank { "Unavailable" })
                }
            }
            item { SectionHeading("Connection") }
            item {
                GroupedCard {
                    ValuePreference(title = "Reader", value = reader?.name ?: "Unavailable")
                    ValuePreference(
                        title = "Access type",
                        value = reader?.kind?.displayName() ?: "Unavailable",
                    )
                    reader?.detail?.takeIf(String::isNotBlank)?.let { detail ->
                        ValuePreference(title = "Reader details", value = detail)
                    }
                    ValuePreference(title = "Last refreshed", value = info.refreshedAt.formatDateTime())
                }
            }
            item { SectionHeading("Profiles and storage") }
            item {
                GroupedCard {
                    ValuePreference(title = "Installed profiles", value = installedProfileCount.toString())
                    ValuePreference(title = "Enabled profiles", value = enabledProfileCount.toString())
                    ValuePreference(
                        title = "Installed applications",
                        value = info.installedApplicationCount?.toString() ?: "Unavailable",
                    )
                    ValuePreference(title = "Free non-volatile memory", value = info.freeNonVolatileMemory?.let(::formatBytes) ?: "Unavailable")
                    ValuePreference(title = "Free volatile memory", value = info.freeVolatileMemory?.let(::formatBytes) ?: "Unavailable")
                }
            }
            item { SectionHeading("Specifications") }
            item {
                GroupedCard {
                    ValuePreference(title = "SGP.22", value = info.sgp22Version.ifBlank { "Unavailable" })
                    ValuePreference(title = "Profile package", value = info.profileVersion.ifBlank { "Unavailable" })
                    ValuePreference(title = "GlobalPlatform", value = info.globalPlatformVersion.ifBlank { "Unavailable" })
                    ValuePreference(title = "ETSI TS 102 241", value = info.ts102241Version.ifBlank { "Unavailable" })
                    ValuePreference(title = "Protection profile", value = info.protectionProfileVersion.ifBlank { "Unavailable" })
                }
            }
            item { SectionHeading("Capabilities") }
            item {
                GroupedCard {
                    ValuePreference(
                        title = "UICC capabilities",
                        value = formatTechnicalValues(info.uiccCapabilities),
                    )
                    ValuePreference(
                        title = "Remote provisioning",
                        value = formatTechnicalValues(info.rspCapabilities),
                    )
                }
            }
            item { SectionHeading("Provisioning") }
            item {
                GroupedCard {
                    ValuePreference(
                        title = "Default SM-DP+",
                        value = info.defaultSmdpAddress.ifBlank { "Not configured" },
                    )
                    ValuePreference(
                        title = "Root SM-DS",
                        value = info.rootSmdsAddress.ifBlank { "Not configured" },
                    )
                    ValuePreference(
                        title = "Platform label",
                        value = info.platformLabel.ifBlank { "Unavailable" },
                    )
                    ValuePreference(
                        title = "Discovery service",
                        value = info.discoveryBaseUrl.ifBlank { "Unavailable" },
                    )
                }
            }
            item { SectionHeading("Advanced") }
            item {
                GroupedCard {
                    BasicComponent(
                        title = "Keys and policy",
                        summary = if (advancedDetailsExpanded) "Hide key IDs and policy rules" else {
                            "${info.signingKeyIds.size} signing · ${info.verificationKeyIds.size} verification keys"
                        },
                        endActions = {
                            Icon(
                                imageVector = if (advancedDetailsExpanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                                contentDescription = if (advancedDetailsExpanded) "Show less" else "Show more",
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        onClick = { advancedDetailsExpanded = !advancedDetailsExpanded },
                    )
                    AnimatedVisibility(
                        visible = advancedDetailsExpanded,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        Column {
                            ValuePreference(
                                title = "Signing key IDs (${info.signingKeyIds.size})",
                                value = formatKeyIds(info.signingKeyIds),
                            )
                            ValuePreference(
                                title = "Verification key IDs (${info.verificationKeyIds.size})",
                                value = formatKeyIds(info.verificationKeyIds),
                            )
                            ValuePreference(
                                title = "Forbidden policy rules",
                                value = formatTechnicalValues(info.forbiddenProfilePolicyRules),
                            )
                        }
                    }
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
            confirmText = "Continue",
            destructive = true,
            onConfirm = {
                showReset = false
                showResetConfirmation = true
            },
        )
    }
    OverlayDialog(
        show = showResetConfirmation,
        title = "Reset eUICC memory now?",
        summary = "The reset may permanently remove profiles or provisioning state.",
        onDismissRequest = { showResetConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showResetConfirmation = false },
            confirmText = "Continue",
            destructive = true,
            onConfirm = {
                showResetConfirmation = false
                showFinalResetConfirmation = true
            },
        )
    }
    OverlayDialog(
        show = showFinalResetConfirmation,
        title = "Permanently reset eUICC memory?",
        summary = "This is your final confirmation. Any profiles or provisioning state removed by the reset cannot be recovered.",
        onDismissRequest = { showFinalResetConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showFinalResetConfirmation = false },
            confirmText = "Reset memory",
            destructive = true,
            onConfirm = {
                showFinalResetConfirmation = false
                onReset()
            },
        )
    }
}

@Composable
fun TagsAndRemindersScreen(
    settings: AppSettings,
    notificationPermissionGranted: Boolean,
    onBack: () -> Unit,
    onOpenTagManager: () -> Unit,
    onOpenScheduledReminders: () -> Unit,
    onSetRemindersEnabled: (Boolean) -> Unit,
    onRequestNotificationPermission: ((Boolean) -> Unit) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onTestNotification: () -> Unit,
) {
    val context = LocalContext.current
    val permissionRequiredMessage = "Notification permission is required for reminders"
    val manageNotificationPermission = {
        if (notificationPermissionGranted) {
            onOpenNotificationSettings()
        } else {
            onRequestNotificationPermission { granted ->
                if (!granted) {
                    Toast.makeText(context, permissionRequiredMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    DetailLazyScaffold(title = "Tags & reminders", onBack = onBack) { _ ->
        item { SectionHeading("General") }
        item {
            GroupedCard {
                ArrowPreference(
                    title = "Tag manager",
                    summary = "View, search and edit profile tags",
                    onClick = onOpenTagManager,
                )
                SwitchPreference(
                    checked = settings.scheduledReminders,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            onSetRemindersEnabled(false)
                        } else {
                            onRequestNotificationPermission { granted ->
                                if (granted) {
                                    onSetRemindersEnabled(true)
                                } else {
                                    Toast.makeText(context, permissionRequiredMessage, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    title = "Profile reminders",
                    summary = "Schedule notifications for profile dates and events",
                )
            }
        }
        item { SectionHeading("Notifications") }
        item {
            GroupedCard {
                ArrowPreference(
                    title = "Notification permission",
                    summary = if (notificationPermissionGranted) "Permissions active" else "Permissions required",
                    endActions = {
                        if (!notificationPermissionGranted) {
                            TextButton(
                                text = "Enable",
                                onClick = manageNotificationPermission,
                            )
                        }
                    },
                    onClick = manageNotificationPermission,
                )
                ArrowPreference(
                    title = "Test notification",
                    summary = "Verify reminder notification delivery",
                    onClick = {
                        onRequestNotificationPermission { granted ->
                            if (granted) {
                                onTestNotification()
                            } else {
                                Toast.makeText(context, permissionRequiredMessage, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
                ArrowPreference(
                    title = "View scheduled reminders",
                    summary = "Manage upcoming profile notifications",
                    onClick = onOpenScheduledReminders,
                )
            }
        }
    }
}

@Composable
fun TagManagerScreen(
    profiles: List<ProfileInfo>,
    onBack: () -> Unit,
    onSetTags: (String, Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf<ProfileInfo?>(null) }
    var editableTags by remember { mutableStateOf(emptySet<String>()) }
    var newTag by remember { mutableStateOf("") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val allTags = profiles
        .flatMap(ProfileInfo::tags)
        .groupBy(String::lowercase)
        .map { (_, tags) -> tags.first() to tags.size }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
    val visibleTags = allTags.filter { (tag, _) ->
        searchQuery.isBlank() || tag.contains(searchQuery, ignoreCase = true)
    }
    val filteredProfiles = profiles.filter { profile ->
        searchQuery.isBlank() || listOf(
            profile.nickname,
            profile.name,
            profile.providerName,
            profile.tags.joinToString(" "),
        ).any { it.contains(searchQuery, ignoreCase = true) }
    }

    DetailLazyScaffold(title = "Profile tags", onBack = onBack) { _ ->
        if (profiles.isEmpty()) {
            item { EmptyState("No profiles", "Connect an eUICC to organise its profiles.", icon = MiuixIcons.BankCards) }
        } else {
            item {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = "Search tags or profiles",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            item { SectionHeading("Active tags") }
            item {
                GroupedCard {
                    if (allTags.isEmpty()) {
                        ArrowPreference(title = "No tags yet", summary = "Open a profile below to add tags", enabled = false)
                    } else if (visibleTags.isEmpty()) {
                        ArrowPreference(title = "No matching tags", summary = "Profile matches may still appear below", enabled = false)
                    } else {
                        visibleTags.forEach { (tag, count) ->
                            ArrowPreference(
                                title = tag,
                                summary = "$count ${if (count == 1) "profile" else "profiles"}",
                                onClick = { searchQuery = tag },
                            )
                        }
                    }
                }
            }
            item { SectionHeading("Profiles") }
            if (filteredProfiles.isEmpty()) {
                item {
                    EmptyState(
                        title = "No tags found",
                        message = "Try a different tag or profile name.",
                        icon = MiuixIcons.Search,
                    )
                }
            } else {
                item {
                    GroupedCard {
                        filteredProfiles.forEach { profile ->
                            ArrowPreference(
                                title = profile.nickname.ifBlank { profile.name.ifBlank { "eSIM profile" } },
                                summary = profile.tags.takeIf { it.isNotEmpty() }?.joinToString() ?: "No tags",
                                onClick = {
                                    selected = profile
                                    editableTags = profile.tags
                                    newTag = ""
                                },
                            )
                        }
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
        ProfileTagsEditor(
            tags = editableTags,
            suggestedTags = allTags.mapTo(linkedSetOf()) { it.first },
            newTag = newTag,
            onNewTagChange = { newTag = it },
            onTagsChange = { editableTags = it },
            onCancel = { selected = null },
            onSave = { tags ->
                selected?.let { onSetTags(it.iccid, tags) }
                selected = null
            },
        )
    }
}

@Composable
fun ScheduledRemindersScreen(
    profiles: List<ProfileInfo>,
    onBack: () -> Unit,
    onOpen: (ProfileInfo) -> Unit,
    onClear: (ProfileInfo) -> Unit,
) {
    val scheduled = profiles.filter { it.reminderAt != null }.sortedBy { it.reminderAt }
    val now = Instant.now()
    val upcoming = scheduled.filter { it.reminderAt?.isAfter(now) == true }
    val pastDue = scheduled.filterNot { it.reminderAt?.isAfter(now) == true }
    DetailLazyScaffold(title = "Scheduled reminders", onBack = onBack) { _ ->
        if (scheduled.isEmpty()) {
            item {
                EmptyState(
                    title = "No reminders",
                    message = "Open a profile and schedule a reminder for a preset or custom date.",
                    icon = MiuixIcons.Messages,
                )
            }
        } else {
            if (upcoming.isNotEmpty()) {
                item { SectionHeading("Upcoming") }
                item {
                    GroupedCard {
                        upcoming.forEach { profile ->
                            ReminderManagerPreference(profile, onOpen, onClear)
                        }
                    }
                }
            }
            if (pastDue.isNotEmpty()) {
                item { SectionHeading("Past due") }
                item {
                    GroupedCard {
                        pastDue.forEach { profile ->
                            ReminderManagerPreference(profile, onOpen, onClear)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderManagerPreference(
    profile: ProfileInfo,
    onOpen: (ProfileInfo) -> Unit,
    onClear: (ProfileInfo) -> Unit,
) {
    ArrowPreference(
        title = profile.nickname.ifBlank { profile.name.ifBlank { "eSIM profile" } },
        summary = profile.reminderAt?.formatDateTime(),
        endActions = {
            TextButton(text = "Clear", onClick = { onClear(profile) })
        },
        onClick = { onOpen(profile) },
    )
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
private fun ProfileHero(
    profile: ProfileInfo,
    settings: AppSettings,
    artworkBitmap: Bitmap?,
    displayName: FormattedProfileDisplayName,
) {
    val context = LocalContext.current
    val phoneNumberInteractionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ResolvedProfileArtwork(
            profile = profile,
            bitmap = artworkBitmap,
            isEnabled = profile.state == ProfileState.ENABLED,
            size = 78.dp,
            cornerRadius = 20.dp,
        )
        Spacer(Modifier.height(16.dp))
        if (displayName.hasPhoneNumber) {
            if (displayName.nameText.isNotEmpty()) {
                Text(
                    text = displayName.nameText,
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = displayName.phoneText,
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable(
                    interactionSource = phoneNumberInteractionSource,
                    indication = null,
                    onClickLabel = "Copy phone number",
                ) {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(
                            ClipData.newPlainText("Phone number", displayName.phoneText),
                        )
                    Toast.makeText(context, "Phone number copied", Toast.LENGTH_SHORT).show()
                },
            )
        } else {
            Text(
                text = displayName.fullText,
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = profile.providerName.ifBlank { "Unknown operator" },
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = redactIdentifier(profile.iccid, settings.iccidRedaction),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ValuePreference(title: String, value: String) {
    BasicComponent(
        title = title,
        summary = value,
        titleColor = BasicComponentDefaults.titleColor(
            disabledColor = MiuixTheme.colorScheme.onBackground,
        ),
        summaryColor = BasicComponentDefaults.summaryColor(
            disabledColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        ),
        enabled = false,
    )
}

@Composable
private fun ReminderOption(title: String, summary: String, onClick: () -> Unit) {
    ArrowPreference(title = title, summary = summary, onClick = onClick)
}

@Composable
private fun ProfileTagsEditor(
    tags: Set<String>,
    suggestedTags: Set<String>,
    newTag: String,
    onNewTagChange: (String) -> Unit,
    onTagsChange: (Set<String>) -> Unit,
    onCancel: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val availableSuggestions = suggestedTags
        .filter { suggestion -> tags.none { it.equals(suggestion, ignoreCase = true) } }
        .sortedBy(String::lowercase)
        .take(6)
    val canAddTag = newTag.split(',', '\n').any { it.trim().isNotEmpty() } && tags.size < 16

    Column {
        Text(
            text = "Tags stay on this device and make profiles easier to find and organise.",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Active tags",
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        if (tags.isEmpty()) {
            Text(
                text = "No tags assigned to this profile",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        } else {
            GroupedCard {
                tags.sortedBy(String::lowercase).forEach { tag ->
                    BasicComponent(
                        title = tag,
                        summary = "Text tag",
                        endActions = {
                            TextButton(
                                text = "Remove",
                                onClick = { onTagsChange(tags.filterNot { it == tag }.toSet()) },
                            )
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        TextField(
            value = newTag,
            onValueChange = { onNewTagChange(it.take(256)) },
            label = "Text tag (e.g. Work, Travel)",
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            text = "Add tag",
            enabled = canAddTag,
            onClick = {
                onTagsChange(tags.withTagInput(newTag))
                onNewTagChange("")
            },
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (availableSuggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Existing tags",
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            GroupedCard {
                availableSuggestions.forEach { suggestion ->
                    ArrowPreference(
                        title = suggestion,
                        summary = "Add to this profile",
                        onClick = { onTagsChange(tags.withTagInput(suggestion)) },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DialogActionRow(
            onCancel = onCancel,
            confirmText = "Save",
            onConfirm = { onSave(tags.withTagInput(newTag)) },
        )
        BottomSheetFooterSpacer()
    }
}

private fun showCustomReminderPicker(
    context: Context,
    reminderAt: Instant?,
    onReminderSelected: (Instant) -> Unit,
) {
    val now = Instant.now()
    val zoneId = ZoneId.systemDefault()
    val initialDateTime = (reminderAt?.takeIf { it.isAfter(now) } ?: now.plus(Duration.ofDays(1)))
        .atZone(zoneId)

    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val selectedAt = selectedDate.atTime(hour, minute).atZone(zoneId).toInstant()
                    if (selectedAt.isAfter(Instant.now())) {
                        onReminderSelected(selectedAt)
                    } else {
                        Toast.makeText(context, "Choose a future date and time", Toast.LENGTH_SHORT).show()
                    }
                },
                initialDateTime.hour,
                initialDateTime.minute,
                DateFormat.is24HourFormat(context),
            ).show()
        },
        initialDateTime.year,
        initialDateTime.monthValue - 1,
        initialDateTime.dayOfMonth,
    ).apply {
        datePicker.minDate = System.currentTimeMillis()
        show()
    }
}

@Composable
private fun BottomSheetFooterSpacer() {
    val systemBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
    Spacer(Modifier.height(systemBarPadding + 16.dp))
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
    cancelText: String = "Cancel",
    confirmText: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        TextButton(text = cancelText, onClick = onCancel, modifier = Modifier.weight(1f))
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

private fun Set<String>.withTagInput(input: String): Set<String> =
    normalizeProfileTags(this + input.split(',', '\n'))

private fun Instant.formatDateTime(): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())
    .format(this)

private fun Enum<*>.displayName(): String = name
    .lowercase()
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

private fun ReaderKind.displayName(): String = when (this) {
    ReaderKind.NBRIDGE -> "NBridge"
    ReaderKind.OMAPI -> "OMAPI"
    ReaderKind.TELEPHONY -> "Telephony"
    ReaderKind.USB_CCID -> "USB CCID"
    ReaderKind.BLE -> "Bluetooth LE"
    ReaderKind.REMOTE -> "Remote"
}

private fun formatTechnicalValues(values: Set<String>): String = values
    .takeIf { it.isNotEmpty() }
    ?.map(::formatTechnicalValue)
    ?.sorted()
    ?.joinToString()
    ?: "Unavailable"

private fun formatTechnicalValue(value: String): String = when (value) {
    "notificationInstall" -> "Install"
    "notificationLocalEnable" -> "Local enable"
    "notificationLocalDisable" -> "Local disable"
    "notificationLocalDelete" -> "Local delete"
    "notificationRpmEnable" -> "Remote enable"
    "notificationRpmDisable" -> "Remote disable"
    "notificationRpmDelete" -> "Remote delete"
    "loadRpmPackageResult" -> "Remote package result"
    "pprUpdateControl" -> "PPR update control"
    "ppr1" -> "PPR1"
    "ppr2" -> "PPR2"
    "ppr3" -> "PPR3"
    "contactlessSupport" -> "Contactless"
    "usimSupport" -> "USIM"
    "isimSupport" -> "ISIM"
    "csimSupport" -> "CSIM"
    "akaMilenage" -> "AKA Milenage"
    "akaCave" -> "AKA CAVE"
    "akaTuak128" -> "AKA TUAK-128"
    "akaTuak256" -> "AKA TUAK-256"
    "gbaAuthenUsim" -> "GBA authentication (USIM)"
    "gbaAuthenISim" -> "GBA authentication (ISIM)"
    "mbmsAuthenUsim" -> "MBMS authentication"
    "eapClient" -> "EAP client"
    "javacard" -> "Java Card"
    "multos" -> "MULTOS"
    "multipleUsimSupport" -> "Multiple USIMs"
    "multipleIsimSupport" -> "Multiple ISIMs"
    "multipleCsimSupport" -> "Multiple CSIMs"
    "berTlvFileSupport" -> "BER-TLV files"
    "dfLinkSupport" -> "DF links"
    "catTp" -> "CAT-TP"
    "getIdentity" -> "Get Identity"
    "profile-a-x25519" -> "Profile A (X25519)"
    "profile-b-p256" -> "Profile B (P-256)"
    "suciCalculatorApi" -> "SUCI calculator API"
    "additionalProfile" -> "Additional profiles"
    "crlSupport" -> "Certificate revocation lists"
    "rpmSupport" -> "Remote profile management"
    "testProfileSupport" -> "Test profiles"
    "deviceInfoExtensibilitySupport" -> "Extensible device information"
    "basicEuicc" -> "Basic"
    "mediumEuicc" -> "Medium"
    "contactlessEuicc" -> "Contactless"
    "other" -> "Other"
    else -> value
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replaceFirstChar(Char::uppercase)
}

private fun formatKeyIds(values: Set<String>): String = values
    .takeIf { it.isNotEmpty() }
    ?.map { it.uppercase() }
    ?.sorted()
    ?.joinToString("\n")
    ?: "Unavailable"

private fun formatBytes(bytes: Int): String = formatBytes(bytes.toLong())

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024f) return "${(kib * 10).roundToInt() / 10f} KiB"
    val mib = kib / 1024f
    return "${(mib * 10).roundToInt() / 10f} MiB"
}
