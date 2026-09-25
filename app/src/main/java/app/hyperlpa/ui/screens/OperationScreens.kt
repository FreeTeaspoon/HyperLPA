package app.hyperlpa.ui.screens

import app.hyperlpa.ui.components.PageStart
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import app.hyperlpa.R
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.metadata.normalizeProfileTags
import app.hyperlpa.data.metadata.providerIconKey
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.RedactionMode
import app.hyperlpa.domain.model.ActivityLogEntry
import app.hyperlpa.domain.model.DownloadRequest
import app.hyperlpa.domain.model.DownloadRequestError
import app.hyperlpa.domain.model.DownloadRequestException
import app.hyperlpa.domain.model.DownloadStage
import app.hyperlpa.domain.model.EuiccInfo
import app.hyperlpa.domain.model.LogLevel
import app.hyperlpa.domain.model.LpaNotification
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileDownloadPreview
import app.hyperlpa.domain.model.ProfileDownloadResult
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.domain.model.analyzeIccid
import app.hyperlpa.domain.model.buildActivationCode
import app.hyperlpa.domain.model.takeUnicodeCodePoints
import app.hyperlpa.ui.components.DialogActionRow
import app.hyperlpa.ui.components.TextInputDialog
import app.hyperlpa.ui.components.EmptyState
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.LoadingState
import app.hyperlpa.ui.components.MiuixDatePickerDialog
import app.hyperlpa.ui.components.ResolvedProfileArtwork
import app.hyperlpa.ui.components.SectionHeading
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.FormattedProfileDisplayName
import app.hyperlpa.ui.components.formatProfileDisplayName
import app.hyperlpa.ui.components.PrimaryPageButton
import app.hyperlpa.ui.components.TipCard
import app.hyperlpa.ui.components.ValuePreference
import app.hyperlpa.ui.components.rememberProfileArtworkBitmap
import app.hyperlpa.ui.components.redactIdentifier
import app.hyperlpa.ui.LocalMiuixSnackbar
import app.hyperlpa.ui.components.effect.AccentGradientBackdrop
import app.hyperlpa.ui.components.effect.ProfileGradientBackdrop
import app.hyperlpa.provisioning.BatchDownloadError
import app.hyperlpa.provisioning.BatchDownloadStatus
import app.hyperlpa.provisioning.BatchDownloadUiState
import app.hyperlpa.provisioning.MaxProvisioningQueueItems
import app.hyperlpa.provisioning.parseBatchDownloadLine
import app.hyperlpa.reminders.formatReminderDate
import app.hyperlpa.reminders.toReminderDate
import app.hyperlpa.reminders.toReminderInstant
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProfileDetailsScreen(
    profile: ProfileInfo?,
    lpa: LpaRepositoryState,
    settings: AppSettings,
    suggestedTags: Set<String>,
    operatorIcon: ByteArray?,
    hasProfileIcon: Boolean,
    hasProviderIcon: Boolean,
    isProviderIconHidden: Boolean,
    onBack: () -> Unit,
    onEnableChange: (Boolean) -> Unit,
    profileSwitchEnabled: Boolean = true,
    onSetPinned: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onSetTags: (Set<String>) -> Unit,
    onSetReminder: (String, Instant?) -> Unit,
    onRequestNotificationPermission: ((Boolean) -> Unit) -> Unit,
    onSetIcon: (uri: String?, applyToProvider: Boolean, onComplete: (Boolean) -> Unit) -> Unit,
    onSetProviderIconHidden: (hidden: Boolean, onComplete: (Boolean) -> Unit) -> Unit,
    onApplyIconToProvider: (onComplete: (Boolean) -> Unit) -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var editableTags by remember(profile?.tags) { mutableStateOf(profile?.tags.orEmpty()) }
    var showReminder by remember { mutableStateOf(false) }
    var showCustomReminderDatePicker by rememberSaveable { mutableStateOf(false) }
    var pendingCustomReminderDate by rememberSaveable { mutableStateOf<String?>(null) }
    var showIconOptions by remember { mutableStateOf(false) }
    var showRemoveProfileIconConfirmation by remember { mutableStateOf(false) }
    var showRemoveSharedProviderIconForProfileConfirmation by remember { mutableStateOf(false) }
    var showRemoveProviderIconConfirmation by remember { mutableStateOf(false) }
    var pickForProvider by rememberSaveable(profile?.iccid) { mutableStateOf(false) }
    var technicalDetailsExpanded by rememberSaveable(profile?.iccid) { mutableStateOf(false) }
    val context = LocalContext.current
    val showSnackbar = LocalMiuixSnackbar.current
    val reminderPermissionRequired = stringResource(R.string.profile_reminder_permission_required)
    val iconImportFailed = stringResource(R.string.profile_icon_import_failed)
    val reportIconResult: (Boolean) -> Unit = { success ->
        if (!success) {
            showSnackbar(iconImportFailed, SnackbarDuration.Long)
        }
    }
    val providerFallback = stringResource(R.string.profile_provider_fallback)
    val providerLabel = profile?.providerName?.trim().orEmpty().ifBlank { providerFallback }
    val canShareByProvider = providerIconKey(profile?.providerName) != null
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val applyToProvider = pickForProvider
        pickForProvider = false
        uri?.let { onSetIcon(it.toString(), applyToProvider, reportIconResult) }
    }
    val fallbackName = stringResource(app.hyperlpa.R.string.profile_default_name)
    val reminderLabel = profile?.nickname?.ifBlank { profile.name }.orEmpty().ifBlank { fallbackName }
    val reminderNow = Instant.now()
    val reminderZone = ZoneId.systemDefault()
    val reminderToday = LocalDate.now(reminderZone)
    val reminderFirstSelectableDate = reminderToday.plusDays(1)
    val reminderLatestDate = reminderToday.plusYears(100)
    val reminderInitialDate = profile?.reminderAt
        ?.takeIf { it.isAfter(reminderNow) }
        ?.toReminderDate(reminderZone)
        ?.coerceIn(reminderFirstSelectableDate, reminderLatestDate)
        ?: reminderFirstSelectableDate
    val setReminder: (Instant) -> Unit = { reminderAt ->
        onRequestNotificationPermission { granted ->
            if (granted) {
                onSetReminder(reminderLabel, reminderAt)
                showReminder = false
            } else {
                showSnackbar(reminderPermissionRequired, SnackbarDuration.Long)
            }
        }
    }
    LaunchedEffect(pendingCustomReminderDate) {
        val selectedDate = pendingCustomReminderDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (selectedDate != null) {
            pendingCustomReminderDate = null
            setReminder(selectedDate.toReminderInstant(reminderZone))
        }
    }
    val displayName = remember(
        profile,
        settings.phoneFormatStrategy,
        settings.profileNameRedaction,
        fallbackName,
    ) {
        profile?.let {
            formatProfileDisplayName(
                profile = it,
                strategy = settings.phoneFormatStrategy,
                fallback = fallbackName,
                redactionMode = settings.profileNameRedaction,
            )
        }
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
        ?: stringResource(R.string.profile_collapsed_title)
    val profileLoadingMessage = when (val operation = lpa.operation) {
        is LpaOperation.DiscoveringReaders -> operation.message
        is LpaOperation.Connecting -> stringResource(
            R.string.profiles_connecting_reader,
            operation.readerName,
        )
        is LpaOperation.Refreshing -> operation.message
        else -> stringResource(R.string.reader_loading)
    }
    val openTagsEditor: () -> Unit = {
        editableTags = profile?.tags.orEmpty()
        showTags = true
    }
    val openReminderEditor: () -> Unit = {
        showReminder = true
    }

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
            item(contentType = PageStart.Viewport) {
                if (isProfileDetailsLoading(profile, lpa)) {
                    LoadingState(
                        message = profileLoadingMessage,
                        modifier = Modifier.fillParentMaxSize(),
                    )
                } else {
                    EmptyState(
                        title = stringResource(R.string.profile_unavailable_title),
                        message = stringResource(R.string.profile_unavailable_message),
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
            }
        } else {
            item {
                ProfileHero(
                    profile = profile,
                    settings = settings,
                    artworkBitmap = artworkBitmap,
                    displayName = requireNotNull(displayName),
                    onOpenIconOptions = { showIconOptions = true },
                    onOpenTags = openTagsEditor,
                    onOpenReminder = openReminderEditor,
                )
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.profile_section)) }
            item {
                GroupedCard {
                    SwitchPreference(
                        checked = profile.state == ProfileState.ENABLED,
                        onCheckedChange = onEnableChange,
                        enabled = profileSwitchEnabled,
                        title = stringResource(R.string.profile_enabled),
                        summary = if (profile.state == ProfileState.ENABLED) {
                            stringResource(R.string.profile_enabled_summary)
                        } else {
                            stringResource(R.string.profile_disabled_summary)
                        },
                    )
                    SwitchPreference(
                        checked = profile.isPinned,
                        onCheckedChange = onSetPinned,
                        title = stringResource(R.string.profile_pinned),
                        summary = if (profile.isPinned) {
                            stringResource(R.string.profile_pinned_summary)
                        } else {
                            stringResource(R.string.profile_unpinned_summary)
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.profile_detail_display_name),
                        summary = formattedNickname ?: stringResource(R.string.profile_use_profile_name),
                        onClick = { showRename = true },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.profile_custom_icon),
                        summary = when {
                            hasProfileIcon -> stringResource(R.string.profile_custom_icon_profile_summary)
                            isProviderIconHidden -> stringResource(R.string.profile_custom_icon_hidden_summary)
                            hasProviderIcon -> stringResource(
                                R.string.profile_custom_icon_provider_summary,
                                providerLabel,
                            )
                            else -> stringResource(R.string.profile_custom_icon_choose_summary)
                        },
                        onClick = { showIconOptions = true },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.profile_tags),
                        summary = profile.tags.takeIf { it.isNotEmpty() }?.joinToString()
                            ?: stringResource(R.string.profile_no_tags),
                        onClick = openTagsEditor,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.profile_reminder),
                        summary = profile.reminderAt?.formatReminderDate()
                            ?: stringResource(R.string.profile_no_reminder),
                        onClick = openReminderEditor,
                    )
                }
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.profile_identifiers_section)) }
            item {
                GroupedCard {
                    ValuePreference(
                        title = "ICCID",
                        value = redactIdentifier(profile.iccid, settings.iccidRedaction),
                    )
                    ValuePreference(
                        title = stringResource(R.string.profile_iccid_checksum),
                        value = when (iccidDetails?.checksumValid) {
                            true -> stringResource(R.string.profile_checksum_valid)
                            false -> stringResource(R.string.profile_checksum_invalid)
                            null -> stringResource(R.string.common_unavailable)
                        },
                    )
                    if (settings.iccidRedaction == RedactionMode.NONE) {
                        iccidDetails?.issuerPrefix?.let { prefix ->
                            ValuePreference(title = stringResource(R.string.profile_issuer_prefix), value = prefix)
                        }
                    }
                    ValuePreference(
                        title = "ISD-P AID",
                        value = profile.isdPAid.ifBlank { stringResource(R.string.common_unavailable) },
                    )
                }
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.profile_metadata_section)) }
            item {
                GroupedCard {
                    ValuePreference(
                        title = stringResource(R.string.profile_name),
                        value = profile.name.ifBlank { stringResource(R.string.common_unavailable) },
                    )
                    ValuePreference(
                        title = stringResource(R.string.profile_euicc_nickname),
                        value = profile.nickname.ifBlank { stringResource(R.string.profile_not_set) },
                    )
                    ValuePreference(
                        title = stringResource(R.string.profile_class),
                        value = stringResource(profile.profileClass.labelResource()),
                    )
                    ValuePreference(
                        title = stringResource(R.string.profile_provider),
                        value = profile.providerName.ifBlank { stringResource(R.string.profile_unknown_value) },
                    )
                    if (!profile.mcc.isNullOrBlank() || !profile.mnc.isNullOrBlank()) {
                        ValuePreference(
                            title = stringResource(R.string.profile_network),
                            value = listOfNotNull(
                                profile.mcc?.let { stringResource(R.string.profile_mcc, it) },
                                profile.mnc?.let { stringResource(R.string.profile_mnc, it) },
                            ).joinToString(" · "),
                        )
                    }
                    profile.estimatedBytes?.takeIf { it > 0 }?.let { bytes ->
                        ValuePreference(
                            title = stringResource(
                                if (profile.sizeIsEstimated) {
                                    R.string.profile_estimated_storage
                                } else {
                                    R.string.profile_measured_storage
                                },
                            ),
                            value = if (profile.sizeIsEstimated) {
                                stringResource(R.string.profile_size_estimated, formatBytes(bytes.toInt()))
                            } else {
                                formatBytes(bytes.toInt())
                            },
                        )
                    }
                }
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.profile_advanced_section)) }
            item {
                GroupedCard {
                    BasicComponent(
                        title = stringResource(R.string.profile_technical_data),
                        summary = if (technicalDetailsExpanded) {
                            stringResource(R.string.profile_technical_hide)
                        } else {
                            stringResource(R.string.profile_technical_summary)
                        },
                        endActions = {
                            Icon(
                                imageVector = if (technicalDetailsExpanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                                contentDescription = stringResource(
                                    if (technicalDetailsExpanded) {
                                        R.string.accessibility_show_less
                                    } else {
                                        R.string.accessibility_show_more
                                    },
                                ),
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
                                ValuePreference(stringResource(R.string.profile_notification_address), it)
                            }
                            if (profile.notificationOperations.isNotEmpty()) {
                                ValuePreference(
                                    stringResource(R.string.profile_notification_events),
                                    formatTechnicalValues(profile.notificationOperations),
                                )
                            }
                            profile.dpOid?.takeIf(String::isNotBlank)?.let { ValuePreference("DP OID", it) }
                            if (profile.profilePolicyRules.isNotEmpty()) {
                                ValuePreference(
                                    stringResource(R.string.profile_policy_rules),
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
                                ValuePreference(
                                    stringResource(R.string.profile_technical_data_label),
                                    stringResource(R.string.profile_technical_empty),
                                )
                            }
                        }
                    }
                }
            }
            if (!settings.hideProfileDeletion) {
                item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.profile_danger_zone)) }
                item {
                    GroupedCard {
                        ArrowPreference(
                            title = stringResource(R.string.profile_delete),
                            summary = stringResource(R.string.profile_delete_summary),
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

    ProfileRenameDialog(
        show = showRename,
        nickname = profile?.nickname.orEmpty(),
        onDismiss = { showRename = false },
        onRename = { value ->
            onRename(value)
            showRename = false
        },
    )

    OverlayDialog(
        show = showDelete && !settings.hideProfileDeletion,
        title = stringResource(R.string.profile_delete_first_title),
        summary = stringResource(R.string.profile_delete_first_summary),
        onDismissRequest = { showDelete = false },
    ) {
        DialogActionRow(
            onCancel = { showDelete = false },
            confirmText = stringResource(R.string.common_continue),
            destructive = true,
            onConfirm = {
                showDelete = false
                showDeleteConfirmation = true
            },
        )
    }

    OverlayDialog(
        show = showDeleteConfirmation && !settings.hideProfileDeletion,
        title = stringResource(R.string.profile_delete_final_title),
        summary = stringResource(profileDeleteFinalSummaryRes(profile?.state)),
        onDismissRequest = { showDeleteConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showDeleteConfirmation = false },
            confirmText = stringResource(R.string.profile_delete),
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
        title = stringResource(R.string.profile_tags_title),
        onDismissRequest = { showTags = false },
    ) {
        ProfileTagsEditor(
            tags = editableTags,
            suggestedTags = suggestedTags,
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
        title = stringResource(R.string.profile_reminder_title_sheet),
        onDismissRequest = { showReminder = false },
    ) {
        Column {
            ReminderOption(
                stringResource(R.string.reminder_week),
                stringResource(R.string.reminder_week_summary),
            ) {
                setReminder(reminderToday.plusDays(7).toReminderInstant(reminderZone))
            }
            ReminderOption(
                stringResource(R.string.reminder_month),
                stringResource(R.string.reminder_month_summary),
            ) {
                setReminder(reminderToday.plusDays(30).toReminderInstant(reminderZone))
            }
            ReminderOption(
                stringResource(R.string.reminder_custom),
                stringResource(R.string.reminder_custom_summary),
            ) {
                showCustomReminderDatePicker = true
            }
            ReminderOption(
                stringResource(R.string.reminder_clear),
                profile?.reminderAt?.formatReminderDate() ?: stringResource(R.string.profile_no_reminder),
            ) {
                onSetReminder(reminderLabel, null)
                showReminder = false
            }
            BottomSheetFooterSpacer()
        }
    }

    MiuixDatePickerDialog(
        show = showCustomReminderDatePicker,
        initialDate = reminderInitialDate,
        minimumDate = reminderFirstSelectableDate,
        maximumDate = reminderLatestDate,
        title = stringResource(R.string.reminder_choose_date),
        cancelText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.common_ok),
        onDismissRequest = { showCustomReminderDatePicker = false },
        onDateSelected = { selectedDate ->
            pendingCustomReminderDate = selectedDate.toString()
        },
    )

    OverlayBottomSheet(
        show = showIconOptions,
        title = stringResource(R.string.profile_custom_icon),
        onDismissRequest = { showIconOptions = false },
    ) {
        Column {
            ReminderOption(
                stringResource(R.string.profile_icon_choose_profile),
                stringResource(R.string.profile_icon_choose_profile_summary),
            ) {
                pickForProvider = false
                showIconOptions = false
                runCatching { pickIcon.launch("image/*") }
                    .onFailure { reportIconResult(false) }
            }
            if (canShareByProvider) {
                ReminderOption(
                    stringResource(R.string.profile_icon_choose_provider, providerLabel),
                    stringResource(R.string.profile_icon_choose_provider_summary),
                ) {
                    pickForProvider = true
                    showIconOptions = false
                    runCatching { pickIcon.launch("image/*") }
                        .onFailure {
                            pickForProvider = false
                            reportIconResult(false)
                        }
                }
            }
            if (canShareByProvider && (hasProfileIcon || hasProviderIcon)) {
                ReminderOption(
                    stringResource(R.string.profile_icon_use_provider, providerLabel),
                    stringResource(R.string.profile_icon_use_provider_summary),
                ) {
                    onApplyIconToProvider(reportIconResult)
                    showIconOptions = false
                }
            }
            if (hasProfileIcon) {
                ReminderOption(
                    stringResource(R.string.profile_icon_remove_profile),
                    stringResource(R.string.profile_icon_remove_profile_summary),
                ) {
                    showIconOptions = false
                    showRemoveProfileIconConfirmation = true
                }
            }
            if (hasProviderIcon && !hasProfileIcon && !isProviderIconHidden) {
                ReminderOption(
                    stringResource(R.string.profile_icon_remove_shared_profile),
                    stringResource(R.string.profile_icon_remove_shared_profile_summary),
                ) {
                    showIconOptions = false
                    showRemoveSharedProviderIconForProfileConfirmation = true
                }
            }
            if (hasProviderIcon && isProviderIconHidden) {
                ReminderOption(
                    stringResource(R.string.profile_icon_restore_profile),
                    stringResource(R.string.profile_icon_restore_profile_summary),
                ) {
                    onSetProviderIconHidden(false, reportIconResult)
                    showIconOptions = false
                }
            }
            if (hasProviderIcon) {
                ReminderOption(
                    stringResource(R.string.profile_icon_remove_provider, providerLabel),
                    stringResource(R.string.profile_icon_remove_provider_summary),
                ) {
                    showIconOptions = false
                    showRemoveProviderIconConfirmation = true
                }
            }
            BottomSheetFooterSpacer()
        }
    }

    OverlayDialog(
        show = showRemoveSharedProviderIconForProfileConfirmation,
        title = stringResource(R.string.profile_icon_remove_title),
        summary = stringResource(R.string.profile_icon_remove_shared_profile_confirmation_summary),
        onDismissRequest = { showRemoveSharedProviderIconForProfileConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showRemoveSharedProviderIconForProfileConfirmation = false },
            confirmText = stringResource(R.string.common_remove),
            destructive = true,
            onConfirm = {
                showRemoveSharedProviderIconForProfileConfirmation = false
                onSetProviderIconHidden(true, reportIconResult)
            },
        )
    }

    OverlayDialog(
        show = showRemoveProfileIconConfirmation,
        title = stringResource(R.string.profile_icon_remove_title),
        summary = stringResource(R.string.profile_icon_remove_summary),
        onDismissRequest = { showRemoveProfileIconConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showRemoveProfileIconConfirmation = false },
            confirmText = stringResource(R.string.common_remove),
            destructive = true,
            onConfirm = {
                showRemoveProfileIconConfirmation = false
                onSetIcon(null, false, reportIconResult)
            },
        )
    }

    OverlayDialog(
        show = showRemoveProviderIconConfirmation,
        title = stringResource(R.string.profile_icon_remove_provider_title, providerLabel),
        summary = stringResource(R.string.profile_icon_remove_provider_confirmation_summary),
        onDismissRequest = { showRemoveProviderIconConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showRemoveProviderIconConfirmation = false },
            confirmText = stringResource(R.string.common_remove),
            destructive = true,
            onConfirm = {
                showRemoveProviderIconConfirmation = false
                onSetIcon(null, true, reportIconResult)
            },
        )
    }
}

@Composable
fun DownloadProfileScreen(
    initialValue: String,
    imei: String,
    busy: Boolean,
    readers: List<ReaderInfo>,
    selectedReaderId: String?,
    readerSelectable: Boolean,
    onBack: () -> Unit,
    onValueChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onSelectReader: (String) -> Unit,
    onFindReaders: () -> Unit,
    onContinue: (DownloadRequest) -> Unit,
) {
    var localValue by remember(initialValue) { mutableStateOf(initialValue) }
    // The confirmation code is a credential, so it stays out of saved state.
    var confirmationCode by remember(initialValue) { mutableStateOf("") }
    var imeiOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var editingField by remember { mutableStateOf<DownloadField?>(null) }
    var validationAttempted by rememberSaveable { mutableStateOf(false) }
    var imageScanError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activationTooLong = stringResource(R.string.activation_error_too_long)
    val imageOpenError = stringResource(R.string.activation_error_image_open)
    val noQrError = stringResource(R.string.activation_error_no_qr)
    val qrReadError = stringResource(R.string.activation_error_qr_read)
    val imageDecodeScope = rememberCoroutineScope()
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    DisposableEffect(barcodeScanner) {
        onDispose(barcodeScanner::close)
    }
    fun acceptActivationCode(value: CharSequence?) {
        if (value == null || value.length > MaxActivationInputCharacters) {
            imageScanError = activationTooLong
            return
        }
        val code = normalizeActivationInput(value.toString())
        if (code.isBlank()) return
        if (code != localValue) confirmationCode = ""
        localValue = code
        onValueChange(code)
        imageScanError = null
    }
    val pickQrImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        imageScanError = null
        imageDecodeScope.launch {
            val decoded = try {
                withContext(Dispatchers.IO) { decodeBoundedQrImage(context, uri) }
            } catch (_: Exception) {
                imageScanError = imageOpenError
                return@launch
            }
            try {
                barcodeScanner.process(decoded.image)
                    .addOnSuccessListener { barcodes ->
                        val rawValue = barcodes.firstNotNullOfOrNull(Barcode::getRawValue)
                        if (rawValue == null) {
                            imageScanError = noQrError
                        } else {
                            acceptActivationCode(rawValue)
                        }
                    }
                    .addOnFailureListener {
                        imageScanError = qrReadError
                    }
                    .addOnCompleteListener {
                        decoded.bitmap.recycle()
                    }
            } catch (_: RuntimeException) {
                decoded.bitmap.recycle()
                imageScanError = qrReadError
            }
        }
    }
    val effectiveImei = imeiOverride ?: imei
    val parsedRequest = remember(localValue, effectiveImei) {
        runCatching { DownloadRequest.parse(localValue, effectiveImei.takeIf(String::isNotBlank)) }
    }
    val requestResult = remember(parsedRequest, confirmationCode) {
        parsedRequest.map { request ->
            if (request.confirmationCodeRequired || confirmationCode.isNotBlank()) {
                request.withConfirmationCode(confirmationCode)
            } else {
                request
            }
        }
    }
    val confirmationCodeRequired = parsedRequest.getOrNull()?.confirmationCodeRequired == true
    fun replaceActivationCode(code: String) {
        localValue = code
        onValueChange(code)
    }
    fun editedActivationCode(edit: (DownloadRequest) -> DownloadRequest): Result<String> = runCatching {
        val edited = edit(parsedRequest.getOrNull() ?: DownloadRequest(smdpAddress = ""))
        buildActivationCode(edited.smdpAddress, edited.matchingId, edited.smdpOid, edited.confirmationCodeRequired)
            .also(DownloadRequest::parse)
    }
    DetailLazyScaffold(
        title = stringResource(R.string.action_download_profile),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = onScanQr,
            ) {
                Icon(
                    MiuixIcons.Scan,
                    contentDescription = stringResource(R.string.activation_scan_qr),
                )
            }
        },
    ) { _ ->
        item(contentType = PageStart.Inset) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = localValue,
                        onValueChange = {
                            val bounded = it.take(MaxActivationInputCharacters)
                            if (bounded != localValue) confirmationCode = ""
                            localValue = bounded
                            onValueChange(bounded)
                        },
                        label = stringResource(R.string.activation_entry_label),
                        useLabelAsPlaceholder = true,
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (confirmationCodeRequired) {
                    TipCard(
                        text = stringResource(app.hyperlpa.R.string.activation_confirmation_help),
                    )
                }
            }
        }
        item {
            GroupedCard(
                modifier = Modifier.padding(top = if (confirmationCodeRequired) 0.dp else 10.dp),
            ) {
                ArrowPreference(
                    title = stringResource(app.hyperlpa.R.string.activation_paste),
                    summary = stringResource(app.hyperlpa.R.string.activation_paste_summary),
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                        acceptActivationCode(item?.coerceToText(context))
                    },
                )
                ArrowPreference(
                    title = stringResource(app.hyperlpa.R.string.activation_image),
                    summary = stringResource(app.hyperlpa.R.string.activation_image_summary),
                    onClick = { pickQrImage.launch("image/*") },
                )
            }
        }
        item {
            GroupedCard {
                val parsed = parsedRequest.getOrNull()
                ArrowPreference(
                    title = "SM-DP+",
                    summary = parsed?.smdpAddress ?: stringResource(R.string.activation_waiting_valid),
                    enabled = !busy,
                    onClick = { editingField = DownloadField.SmdpAddress },
                )
                ArrowPreference(
                    title = stringResource(R.string.activation_matching_id),
                    summary = stringResource(
                        if (parsed?.matchingId.isNullOrEmpty()) {
                            R.string.activation_not_included
                        } else {
                            R.string.activation_included
                        },
                    ),
                    enabled = !busy && parsed != null,
                    onClick = { editingField = DownloadField.MatchingId },
                )
                parsed?.smdpOid?.takeIf(String::isNotBlank)?.let { smdpOid ->
                    ArrowPreference(
                        title = "SM-DP+ OID",
                        summary = smdpOid,
                        enabled = !busy,
                        onClick = { editingField = DownloadField.SmdpOid },
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.activation_confirmation_code),
                    summary = when {
                        confirmationCode.isNotBlank() -> stringResource(R.string.activation_entered)
                        confirmationCodeRequired -> stringResource(R.string.activation_required)
                        else -> stringResource(R.string.activation_not_required)
                    },
                    summaryColor = if (validationAttempted && confirmationCodeRequired && confirmationCode.isBlank()) {
                        BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.error)
                    } else {
                        BasicComponentDefaults.summaryColor()
                    },
                    enabled = !busy && parsed != null,
                    onClick = { editingField = DownloadField.ConfirmationCode },
                )
                ArrowPreference(
                    title = "IMEI",
                    summary = effectiveImei.ifBlank { stringResource(R.string.activation_not_supplied) },
                    enabled = !busy,
                    onClick = { editingField = DownloadField.Imei },
                )
                if (readers.isEmpty()) {
                    ArrowPreference(
                        title = stringResource(R.string.download_install_to),
                        summary = stringResource(R.string.profiles_find_readers),
                        enabled = !busy && readerSelectable,
                        onClick = onFindReaders,
                    )
                } else {
                    val selectedReader = readers.firstOrNull { it.id == selectedReaderId }
                    OverlayDropdownPreference(
                        title = stringResource(R.string.download_install_to),
                        summary = selectedReader?.detail ?: stringResource(R.string.profiles_select_reader),
                        items = readers.map { it.name },
                        selectedIndex = readers.indexOf(selectedReader),
                        enabled = !busy && readerSelectable,
                        onSelectedIndexChange = { index -> readers.getOrNull(index)?.id?.let(onSelectReader) },
                    )
                }
            }
        }
        requestResult.exceptionOrNull()?.takeIf { validationAttempted }?.let { error ->
            item {
                Text(
                    text = localizedDownloadRequestError(error),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                )
            }
        }
        if (
            validationAttempted &&
            requestResult.getOrNull()?.let { request ->
                request.confirmationCodeRequired && request.confirmationCode.isNullOrBlank()
            } == true
        ) {
            item {
                Text(
                    text = stringResource(R.string.failure_confirmation_code_required),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                )
            }
        }
        imageScanError?.let { message ->
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
            PrimaryPageButton(
                text = stringResource(
                    if (busy) R.string.activation_checking_profile else R.string.common_continue,
                ),
                onClick = {
                    validationAttempted = true
                    requestResult.getOrNull()
                        ?.takeIf(DownloadRequest::hasRequiredConfirmationCode)
                        ?.let(onContinue)
                },
                busy = busy,
            )
        }
    }
    val parsed = parsedRequest.getOrNull()
    val resources = LocalResources.current
    val closeEditor = { editingField = null }
    fun errorFor(result: Result<*>): String? =
        result.exceptionOrNull()?.let { resources.getString(downloadRequestErrorResource(it)) }
    TextInputDialog(
        show = editingField == DownloadField.SmdpAddress,
        title = "SM-DP+",
        summary = stringResource(R.string.download_smdp_dialog_summary),
        label = stringResource(R.string.download_smdp_label),
        initialValue = parsed?.smdpAddress.orEmpty(),
        maxLength = 253,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        validate = { value -> errorFor(editedActivationCode { it.copy(smdpAddress = value.trim()) }) },
        onDismiss = closeEditor,
        onConfirm = { value ->
            editedActivationCode { it.copy(smdpAddress = value.trim()) }.onSuccess(::replaceActivationCode)
            closeEditor()
        },
    )
    TextInputDialog(
        show = editingField == DownloadField.MatchingId,
        title = stringResource(R.string.activation_matching_id),
        summary = stringResource(R.string.download_matching_id_dialog_summary),
        initialValue = parsed?.matchingId.orEmpty(),
        maxLength = 1_024,
        allowBlank = true,
        validate = { value ->
            if ('$' in value) {
                resources.getString(R.string.activation_error_matching_invalid)
            } else {
                errorFor(editedActivationCode { it.copy(matchingId = value.trim().ifEmpty { null }) })
            }
        },
        onDismiss = closeEditor,
        onConfirm = { value ->
            editedActivationCode { it.copy(matchingId = value.trim().ifEmpty { null }) }.onSuccess(::replaceActivationCode)
            closeEditor()
        },
    )
    TextInputDialog(
        show = editingField == DownloadField.SmdpOid,
        title = "SM-DP+ OID",
        summary = stringResource(R.string.download_oid_dialog_summary),
        initialValue = parsed?.smdpOid.orEmpty(),
        maxLength = 256,
        allowBlank = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        validate = { value -> errorFor(editedActivationCode { it.copy(smdpOid = value.trim().ifEmpty { null }) }) },
        onDismiss = closeEditor,
        onConfirm = { value ->
            editedActivationCode { it.copy(smdpOid = value.trim().ifEmpty { null }) }.onSuccess(::replaceActivationCode)
            closeEditor()
        },
    )
    TextInputDialog(
        show = editingField == DownloadField.ConfirmationCode,
        title = stringResource(R.string.activation_confirmation_code),
        summary = stringResource(R.string.activation_confirmation_help),
        initialValue = confirmationCode,
        maxLength = 128,
        allowBlank = !confirmationCodeRequired,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        validate = { value -> errorFor(runCatching { DownloadRequest(smdpAddress = "").withConfirmationCode(value) }) },
        onDismiss = closeEditor,
        onConfirm = { value ->
            confirmationCode = value.trim()
            closeEditor()
        },
    )
    TextInputDialog(
        show = editingField == DownloadField.Imei,
        title = "IMEI",
        summary = stringResource(R.string.download_imei_dialog_summary),
        initialValue = effectiveImei,
        maxLength = 16,
        allowBlank = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        inputFilter = { it.filter(Char::isDigit) },
        onDismiss = closeEditor,
        onConfirm = { value ->
            imeiOverride = value
            closeEditor()
        },
    )
}

private enum class DownloadField { SmdpAddress, MatchingId, SmdpOid, ConfirmationCode, Imei }

private data class DecodedQrImage(
    val image: InputImage,
    val bitmap: Bitmap,
)

private fun decodeBoundedQrImage(context: Context, uri: Uri): DecodedQrImage {
    val encoded = context.contentResolver.openInputStream(uri)?.use { input ->
        input.readBytesLimited(MaxQrEncodedImageBytes)
    } ?: throw IllegalArgumentException("The selected image could not be opened")
    try {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(encoded))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val sourceWidth = info.size.width
            val sourceHeight = info.size.height
            require(sourceWidth in 1..MaxQrSourceDimension)
            require(sourceHeight in 1..MaxQrSourceDimension)
            val longestEdge = maxOf(sourceWidth, sourceHeight)
            if (longestEdge > MaxQrDecodedEdge) {
                val scale = MaxQrDecodedEdge.toDouble() / longestEdge.toDouble()
                decoder.setTargetSize(
                    maxOf(1, (sourceWidth * scale).roundToInt()),
                    maxOf(1, (sourceHeight * scale).roundToInt()),
                )
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
        }
        return DecodedQrImage(
            image = InputImage.fromBitmap(bitmap, 0),
            bitmap = bitmap,
        )
    } finally {
        encoded.fill(0)
    }
}

private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(32 * 1024, maxBytes))
    val buffer = ByteArray(32 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        require(total <= maxBytes - count) { "The selected image is too large" }
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}

private fun normalizeActivationInput(value: String): String {
    if (value.length > MaxActivationInputCharacters) return ""
    val trimmed = value.trim()
    if (trimmed.startsWith("LPA:", ignoreCase = true)) return trimmed
    val uri = runCatching { trimmed.toUri() }.getOrNull() ?: return trimmed
    return runCatching {
        listOf("carddata", "activationCode", "activation_code", "code")
            .firstNotNullOfOrNull(uri::getQueryParameter)
    }.getOrNull()
        ?.trim()
        ?.takeIf { it.startsWith("LPA:", ignoreCase = true) }
        ?: trimmed
}

@Composable
private fun localizedDownloadRequestError(error: Throwable): String =
    stringResource(downloadRequestErrorResource(error))

private fun downloadRequestErrorResource(error: Throwable): Int =
    when ((error as? DownloadRequestException)?.reason) {
        DownloadRequestError.CONFIRMATION_CODE_TOO_LONG -> R.string.activation_error_confirmation_too_long
        DownloadRequestError.CONFIRMATION_CODE_INVALID -> R.string.activation_error_confirmation_invalid
        DownloadRequestError.SMDP_ADDRESS_REQUIRED -> R.string.activation_error_address_required
        DownloadRequestError.ACTIVATION_CODE_TOO_LONG -> R.string.activation_error_too_long
        DownloadRequestError.ACTIVATION_CODE_FIELDS -> R.string.activation_error_fields
        DownloadRequestError.ACTIVATION_CODE_VERSION -> R.string.activation_error_version
        DownloadRequestError.ACTIVATION_CODE_ADDRESS_MISSING -> R.string.activation_error_address_missing
        DownloadRequestError.MATCHING_ID_TOO_LONG -> R.string.activation_error_matching_too_long
        DownloadRequestError.MATCHING_ID_INVALID -> R.string.activation_error_matching_invalid
        DownloadRequestError.SMDP_OID_INVALID -> R.string.activation_error_oid_invalid
        DownloadRequestError.CONFIRMATION_FLAG_INVALID -> R.string.activation_error_confirmation_flag
        DownloadRequestError.RSP_ADDRESS_REQUIRED -> R.string.activation_error_rsp_required
        DownloadRequestError.RSP_ADDRESS_TOO_LONG -> R.string.activation_error_rsp_too_long
        DownloadRequestError.RSP_ADDRESS_HAS_SCHEME -> R.string.activation_error_rsp_scheme
        DownloadRequestError.RSP_ADDRESS_WHITESPACE -> R.string.activation_error_rsp_whitespace
        DownloadRequestError.RSP_ADDRESS_UNSUPPORTED_CHARACTERS -> R.string.activation_error_rsp_characters
        DownloadRequestError.RSP_ADDRESS_INVALID -> R.string.activation_error_rsp_invalid
        DownloadRequestError.RSP_PORT_INVALID -> R.string.activation_error_rsp_port
        null -> R.string.activation_error_invalid
    }

@Composable
fun ProfileDownloadConfirmationScreen(
    preview: ProfileDownloadPreview,
    iccidRedaction: RedactionMode,
    cloudIcon: ByteArray?,
    estimatedDownloadBytes: Long?,
    enrichmentLoading: Boolean,
    downloadOperation: LpaOperation.Downloading?,
    showCancelConfirmation: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onDismissCancelConfirmation: () -> Unit,
    onConfirmCancel: () -> Unit,
) {
    val profile = preview.profile
    val artworkProfile = if (cloudIcon != null) profile.copy(iconBase64 = null) else profile
    val artworkBitmap = rememberProfileArtworkBitmap(artworkProfile, cloudIcon)
    val displayName = profile.name.ifBlank { profile.providerName }
        .ifBlank { stringResource(R.string.profile_default_name) }
    val network = listOfNotNull(profile.mcc, profile.mnc)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .ifBlank { stringResource(R.string.common_unavailable) }
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    val progressOperation = downloadOperation?.takeUnless { it.stage == DownloadStage.CONFIRMING }
    val progressVisible = progressOperation != null
    val navigationEventState = rememberNavigationEventState(
        currentInfo = NavigationEventInfo.None,
    )
    val handleBack: () -> Unit = if (progressVisible) ({}) else onBack

    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = progressVisible,
        onBackCompleted = {},
    )

    DetailLazyScaffold(
        title = "",
        onBack = handleBack,
        showBackButton = !progressVisible,
        collapsedTitle = displayName,
        collapsedBarRevealStart = 132.dp,
        background = {
            if (artworkBitmap != null) {
                ProfileGradientBackdrop(bitmap = artworkBitmap)
            } else {
                AccentGradientBackdrop()
            }
        },
    ) { _ ->
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ResolvedProfileArtwork(
                    profile = artworkProfile,
                    bitmap = artworkBitmap,
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
        item {
            GroupedCard {
                ValuePreference(
                    title = stringResource(R.string.profile_provider),
                    value = profile.providerName.ifBlank { stringResource(R.string.common_unavailable) },
                )
                ValuePreference(
                    title = "ICCID",
                    value = profile.iccid
                        .takeIf(String::isNotBlank)
                        ?.let { redactIdentifier(it, iccidRedaction) }
                        ?: stringResource(R.string.common_unavailable),
                )
                ValuePreference(
                    title = stringResource(R.string.download_available_storage),
                    value = preview.freeNonVolatileMemory?.let {
                        stringResource(R.string.download_storage_free, formatBytes(it))
                    } ?: stringResource(R.string.common_unavailable),
                )
                ValuePreference(
                    title = stringResource(R.string.download_estimated_size),
                    value = when {
                        estimatedDownloadBytes != null -> stringResource(
                            R.string.profile_size_estimated,
                            formatBytes(estimatedDownloadBytes),
                        )
                        enrichmentLoading -> stringResource(R.string.download_checking_cloud)
                        else -> stringResource(R.string.common_unavailable)
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.download_more),
                    summary = if (moreExpanded) {
                        stringResource(R.string.download_hide_technical)
                    } else {
                        stringResource(R.string.download_more_summary)
                    },
                    endActions = {
                        Icon(
                            imageVector = if (moreExpanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                            contentDescription = stringResource(
                                if (moreExpanded) {
                                    R.string.accessibility_show_less
                                } else {
                                    R.string.accessibility_show_more
                                },
                            ),
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
                        ValuePreference(title = stringResource(R.string.profile_network), value = network)
                        ValuePreference(
                            title = stringResource(R.string.profile_class),
                            value = stringResource(profile.profileClass.labelResource()),
                        )
                        profile.gid1?.takeIf(String::isNotBlank)?.let { ValuePreference("GID1", it) }
                        profile.gid2?.takeIf(String::isNotBlank)?.let { ValuePreference("GID2", it) }
                    }
                }
            }
        }
        if (progressOperation != null) {
            item {
                ProfileDownloadProgressBar(
                    operation = progressOperation,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                )
            }
        } else {
            item {
                PrimaryPageButton(
                    text = stringResource(R.string.common_download),
                    onClick = onDownload,
                )
            }
        }
    }

    OverlayDialog(
        show = showCancelConfirmation,
        title = stringResource(R.string.download_cancel_title),
        summary = stringResource(R.string.download_cancel_summary),
        onDismissRequest = onDismissCancelConfirmation,
    ) {
        DialogActionRow(
            onCancel = onDismissCancelConfirmation,
            cancelText = stringResource(R.string.download_stay_here),
            confirmText = stringResource(R.string.download_go_back),
            destructive = true,
            onConfirm = onConfirmCancel,
        )
    }
}

@Composable
private fun ProfileDownloadProgressBar(
    operation: LpaOperation.Downloading,
    modifier: Modifier = Modifier,
) {
    val sentBytes = operation.sentBytes?.coerceAtLeast(0L)
    val totalBytes = operation.totalBytes?.takeIf { it > 0L }
    val targetProgress = if (sentBytes != null && totalBytes != null) {
        (sentBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    val progress by animateFloatAsState(
        targetValue = targetProgress ?: 0f,
        animationSpec = tween(durationMillis = 300),
    )
    val stageText = when (operation.stage) {
        DownloadStage.PREPARING -> stringResource(R.string.download_progress_preparing)
        DownloadStage.CONNECTING -> stringResource(R.string.download_progress_connecting)
        DownloadStage.AUTHENTICATING -> stringResource(R.string.download_progress_authenticating)
        DownloadStage.CONFIRMING -> stringResource(R.string.download_progress_preparing)
        DownloadStage.DOWNLOADING -> stringResource(R.string.download_progress_downloading)
        DownloadStage.FINALIZING -> stringResource(R.string.download_progress_finalizing)
        DownloadStage.INSTALLING -> stringResource(R.string.download_progress_installing)
    }
    val progressText = if (targetProgress != null && sentBytes != null && totalBytes != null) {
        stringResource(
            R.string.download_progress_status,
            stageText,
            stringResource(
                R.string.download_progress_detail,
                (targetProgress * 100).roundToInt(),
                formatBytes(sentBytes),
                formatBytes(totalBytes),
            ),
        )
    } else {
        stageText
    }
    val mutedStage = operation.stage == DownloadStage.DOWNLOADING ||
        operation.stage == DownloadStage.INSTALLING
    val contentColor = if (progress < 0.45f) {
        if (MiuixTheme.isDynamicColor) {
            MiuixTheme.colorScheme.onSecondaryContainer
        } else {
            MiuixTheme.colorScheme.onSecondaryVariant
        }
    } else {
        MiuixTheme.colorScheme.onPrimary
    }
    val trackColor = if (mutedStage) {
        MiuixTheme.colorScheme.disabledSecondaryVariant
    } else if (MiuixTheme.isDynamicColor) {
        MiuixTheme.colorScheme.secondaryContainer
    } else {
        MiuixTheme.colorScheme.secondaryVariant
    }
    val progressColor = MiuixTheme.colorScheme.primary

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                progressBarRangeInfo = targetProgress?.let {
                    ProgressBarRangeInfo(progress, 0f..1f)
                } ?: ProgressBarRangeInfo.Indeterminate
            },
        shape = RoundedCornerShape(ButtonDefaults.CornerRadius),
        color = trackColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(
                    minWidth = ButtonDefaults.MinWidth,
                    minHeight = ButtonDefaults.MinHeight,
                )
                .drawWithContent {
                    if (progress > 0f) {
                        drawRect(
                            color = progressColor,
                            size = Size(
                                width = size.width * progress,
                                height = size.height,
                            ),
                        )
                    }
                    drawContent()
                }
                .padding(ButtonDefaults.InsideMargin),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfiniteProgressIndicator(color = contentColor)
            Spacer(Modifier.width(10.dp))
            Text(
                text = progressText,
                color = contentColor,
                style = MiuixTheme.textStyles.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ProfileDownloadResultScreen(
    result: ProfileDownloadResult,
    profile: ProfileInfo,
    cloudIcon: ByteArray?,
    busy: Boolean,
    onBack: () -> Unit,
    onEnable: () -> Unit,
    onRename: (String) -> Unit,
    onDone: () -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }
    val displayName = profile.name.ifBlank { profile.providerName }
        .ifBlank { stringResource(R.string.profile_default_name) }
    val artworkProfile = if (cloudIcon != null) profile.copy(iconBase64 = null) else profile
    val artworkBitmap = rememberProfileArtworkBitmap(artworkProfile, cloudIcon)

    DetailLazyScaffold(
        title = "",
        onBack = onBack,
        showBackButton = false,
        collapsedTitle = displayName,
        collapsedBarRevealStart = 132.dp,
        background = {
            if (artworkBitmap != null) {
                ProfileGradientBackdrop(bitmap = artworkBitmap)
            } else {
                AccentGradientBackdrop()
            }
        },
    ) { _ ->
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ResolvedProfileArtwork(
                    profile = artworkProfile,
                    bitmap = artworkBitmap,
                    isEnabled = true,
                    size = 72.dp,
                    cornerRadius = 18.dp,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.download_success_title),
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.download_success_summary,
                        profile.providerName.ifBlank {
                            stringResource(R.string.download_success_profile_fallback)
                        },
                    ),
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            GroupedCard {
                ValuePreference(
                    title = stringResource(R.string.download_storage_used),
                    value = result.installedBytes?.let { formatBytes(it) }
                        ?: stringResource(R.string.common_unavailable),
                )
                ValuePreference(
                    title = stringResource(R.string.download_storage_free_title),
                    value = result.freeNonVolatileMemory?.let { formatBytes(it) }
                        ?: stringResource(R.string.common_unavailable),
                )
                if (profile.iccid.isNotBlank()) {
                    ArrowPreference(
                        title = stringResource(R.string.profile_detail_display_name),
                        summary = profile.nickname.ifBlank { stringResource(R.string.profile_use_profile_name) },
                        enabled = !busy,
                        onClick = { showRename = true },
                    )
                    if (profile.state != ProfileState.ENABLED) {
                        ArrowPreference(
                            title = stringResource(R.string.download_enable_profile),
                            summary = stringResource(R.string.download_enable_profile_summary),
                            enabled = !busy,
                            onClick = onEnable,
                        )
                    }
                }
            }
        }
        item {
            PrimaryPageButton(
                text = stringResource(R.string.common_done),
                onClick = onDone,
                enabled = !busy,
            )
        }
    }

    ProfileRenameDialog(
        show = showRename,
        nickname = profile.nickname,
        onDismiss = { showRename = false },
        onRename = { value ->
            onRename(value)
            showRename = false
        },
    )
}

@Composable
fun BatchDownloadScreen(
    imei: String,
    state: BatchDownloadUiState,
    onBack: () -> Unit,
    onDownload: (List<DownloadRequest>) -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    var values by remember { mutableStateOf("") }
    var showClearConfirmation by remember { mutableStateOf(false) }
    val lines = values.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .take(MaxProvisioningQueueItems + 1)
        .toList()
    val parsed = lines.map { line ->
        runCatching { parseBatchDownloadLine(line, imei.takeIf(String::isNotBlank)) }
    }
    val parsedRequests = parsed.mapNotNull(Result<DownloadRequest>::getOrNull)
    val validCount = parsedRequests.size
    val duplicateCount = parsedRequests.size - parsedRequests.distinctBy { request ->
        Triple(request.smdpAddress, request.matchingId, request.smdpOid)
    }.size
    val withinQueueLimit = lines.size <= MaxProvisioningQueueItems

    DetailLazyScaffold(title = stringResource(R.string.batch_download_title), onBack = onBack) { _ ->
        item(contentType = PageStart.Inset) {
            TipCard(text = stringResource(R.string.batch_download_instructions))
        }
        item {
            TextField(
                value = values,
                onValueChange = { values = it.take(MaxBatchInputCharacters) },
                label = stringResource(R.string.batch_download_codes_label),
                useLabelAsPlaceholder = true,
                minLines = 8,
                maxLines = 16,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = 10.dp),
            )
        }
        item {
            GroupedCard {
                ValuePreference(title = stringResource(R.string.batch_codes), value = lines.size.toString())
                ValuePreference(title = stringResource(R.string.batch_valid), value = validCount.toString())
                ValuePreference(
                    title = stringResource(R.string.batch_invalid),
                    value = (lines.size - validCount).toString(),
                )
                ValuePreference(
                    title = stringResource(R.string.batch_queue_limit),
                    value = stringResource(
                        R.string.batch_queue_limit_value,
                        lines.size,
                        MaxProvisioningQueueItems,
                    ),
                )
                if (duplicateCount > 0) {
                    ValuePreference(
                        title = stringResource(R.string.batch_duplicates),
                        value = duplicateCount.toString(),
                    )
                }
            }
        }
        state.notice?.let { notice ->
            item {
                Text(
                    text = notice,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                )
            }
        }
        item {
            PrimaryPageButton(
                text = if (state.running) {
                    stringResource(R.string.batch_download_in_progress)
                } else {
                    pluralStringResource(R.plurals.batch_download_profiles, validCount, validCount)
                },
                onClick = { onDownload(parsedRequests) },
                enabled = !state.loading &&
                    !state.requiresClearBeforeNewBatch &&
                    lines.isNotEmpty() &&
                    withinQueueLimit &&
                    validCount == lines.size &&
                    duplicateCount == 0,
                busy = state.running,
            )
        }
        if (state.items.isNotEmpty()) {
            item(contentType = PageStart.Heading) {
                SectionHeading(
                    stringResource(
                        if (state.restored) R.string.batch_saved_queue else R.string.batch_queue,
                    ),
                )
            }
            item {
                GroupedCard {
                    state.items.forEach { item ->
                        ValuePreference(
                            title = "${item.index + 1}. ${item.address}",
                            value = when (item.error) {
                                BatchDownloadError.DOWNLOAD_FAILED -> stringResource(
                                    app.hyperlpa.R.string.provisioning_error_download_failed,
                                )
                                BatchDownloadError.INTERRUPTED_UNVERIFIED ->
                                    stringResource(app.hyperlpa.R.string.provisioning_error_interrupted)
                                BatchDownloadError.CANCELLED_UNVERIFIED ->
                                    stringResource(
                                        app.hyperlpa.R.string.provisioning_error_cancelled_unverified,
                                    )
                                BatchDownloadError.OUTCOME_UNVERIFIED ->
                                    stringResource(
                                        app.hyperlpa.R.string.provisioning_error_outcome_unverified,
                                    )
                                null -> when (item.status) {
                                    BatchDownloadStatus.WAITING -> stringResource(R.string.batch_status_waiting)
                                    BatchDownloadStatus.DOWNLOADING -> stringResource(R.string.batch_status_downloading)
                                    BatchDownloadStatus.SUCCEEDED -> stringResource(R.string.batch_status_installed)
                                    BatchDownloadStatus.FAILED -> stringResource(R.string.batch_status_failed)
                                    BatchDownloadStatus.CANCELLED -> stringResource(R.string.batch_status_cancelled)
                                    BatchDownloadStatus.INTERRUPTED ->
                                        stringResource(app.hyperlpa.R.string.provisioning_error_interrupted)
                                }
                            },
                        )
                    }
                    ValuePreference(
                        title = stringResource(R.string.batch_progress),
                        value = stringResource(
                            R.string.batch_progress_value,
                            state.completedCount,
                            state.failedCount,
                        ),
                    )
                }
            }
        }
        val canResume = !state.running && state.resumableCount > 0
        val canRetry = !state.running && state.retryableCount > 0
        if (state.items.isNotEmpty() && (state.running || canResume || canRetry)) {
            item {
                GroupedCard {
                    if (state.running) {
                        ArrowPreference(
                            title = stringResource(R.string.batch_cancel_remaining),
                            onClick = onCancel,
                        )
                    }
                    if (canResume) {
                        ArrowPreference(
                            title = pluralStringResource(
                                R.plurals.batch_resume_pending,
                                state.resumableCount,
                                state.resumableCount,
                            ),
                            onClick = onResume,
                        )
                    }
                    if (canRetry) {
                        ArrowPreference(
                            title = pluralStringResource(
                                R.plurals.batch_retry_failed,
                                state.retryableCount,
                                state.retryableCount,
                            ),
                            onClick = onRetry,
                        )
                    }
                }
            }
        }
        if (!state.running && state.hasSavedQueue) {
            item {
                GroupedCard {
                    ArrowPreference(
                        title = stringResource(R.string.batch_clear_saved),
                        titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                        onClick = { showClearConfirmation = true },
                    )
                }
            }
        }
    }

    OverlayDialog(
        show = showClearConfirmation,
        title = stringResource(R.string.batch_clear_title),
        summary = stringResource(R.string.batch_clear_summary),
        onDismissRequest = { showClearConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showClearConfirmation = false },
            confirmText = stringResource(R.string.common_clear),
            destructive = true,
            onConfirm = {
                showClearConfirmation = false
                onClear()
            },
        )
    }
}

@Composable
fun EuiccDetailsScreen(
    info: EuiccInfo?,
    cardName: String?,
    reader: ReaderInfo?,
    installedProfileCount: Int,
    enabledProfileCount: Int,
    discoveredSmdpAddresses: List<String>,
    settings: AppSettings,
    onBack: () -> Unit,
    onSetCardName: (String) -> Unit,
    onReset: () -> Unit,
    onSetDefaultSmdpAddress: (String) -> Unit,
    onDiscoverProfiles: (String?) -> Unit,
    onUseDiscoveredAddress: (String) -> Unit,
) {
    var showReset by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showFinalResetConfirmation by remember { mutableStateOf(false) }
    var advancedDetailsExpanded by rememberSaveable(info?.eid) { mutableStateOf(false) }
    var showDefaultSmdpEditor by remember { mutableStateOf(false) }
    var showSmdsEditor by remember { mutableStateOf(false) }
    var showCardNameEditor by remember { mutableStateOf(false) }
    DetailLazyScaffold(title = stringResource(R.string.euicc_information_title), onBack = onBack) { _ ->
        if (info == null) {
            item(contentType = PageStart.Viewport) {
                EmptyState(
                    title = stringResource(R.string.euicc_not_connected),
                    message = stringResource(R.string.euicc_not_connected_message),
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        } else {
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.euicc_identity)) }
            item {
                GroupedCard {
                    ArrowPreference(
                        title = stringResource(R.string.euicc_card_name),
                        summary = cardName ?: stringResource(R.string.euicc_card_name_not_set),
                        onClick = { showCardNameEditor = true },
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_eid),
                        value = redactIdentifier(info.eid, settings.eidRedaction),
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_category),
                        value = info.euiccCategory.takeIf(String::isNotBlank)
                            ?.let { value -> formatTechnicalValue(value) }
                            ?: stringResource(R.string.common_unavailable),
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_sas_accreditation),
                        value = info.sasAccreditationNumber.ifBlank {
                            stringResource(R.string.common_unavailable)
                        },
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_firmware),
                        value = info.firmwareVersion.ifBlank { stringResource(R.string.common_unavailable) },
                    )
                }
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.euicc_connection)) }
            item {
                GroupedCard {
                    ValuePreference(
                        title = stringResource(R.string.euicc_reader),
                        value = reader?.name ?: stringResource(R.string.common_unavailable),
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_access_type),
                        value = reader?.kind?.let { stringResource(it.labelResource()) }
                            ?: stringResource(R.string.common_unavailable),
                    )
                    reader?.detail?.takeIf(String::isNotBlank)?.let { detail ->
                        ValuePreference(title = stringResource(R.string.euicc_reader_details), value = detail)
                    }
                    ValuePreference(
                        title = stringResource(R.string.euicc_last_refreshed),
                        value = info.refreshedAt.formatDateTime(),
                    )
                }
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.euicc_profiles_storage)) }
            item {
                GroupedCard {
                    ValuePreference(
                        title = stringResource(R.string.euicc_installed_profiles),
                        value = installedProfileCount.toString(),
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_enabled_profiles),
                        value = enabledProfileCount.toString(),
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_installed_apps),
                        value = info.installedApplicationCount?.toString()
                            ?: stringResource(R.string.common_unavailable),
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_free_nonvolatile),
                        value = info.freeNonVolatileMemory?.let { formatBytes(it) }
                            ?: stringResource(R.string.common_unavailable),
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_free_volatile),
                        value = info.freeVolatileMemory?.let { formatBytes(it) }
                            ?: stringResource(R.string.common_unavailable),
                    )
                }
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.euicc_specifications)) }
            item {
                GroupedCard {
                    ValuePreference(title = "SGP.22", value = info.sgp22Version.ifBlank { stringResource(R.string.common_unavailable) })
                    ValuePreference(
                        title = stringResource(R.string.euicc_profile_package),
                        value = info.profileVersion.ifBlank { stringResource(R.string.common_unavailable) },
                    )
                    ValuePreference(title = "GlobalPlatform", value = info.globalPlatformVersion.ifBlank { stringResource(R.string.common_unavailable) })
                    ValuePreference(title = "ETSI TS 102 241", value = info.ts102241Version.ifBlank { stringResource(R.string.common_unavailable) })
                    ValuePreference(
                        title = stringResource(R.string.euicc_protection_profile),
                        value = info.protectionProfileVersion.ifBlank {
                            stringResource(R.string.common_unavailable)
                        },
                    )
                }
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.euicc_capabilities)) }
            item {
                GroupedCard {
                    ValuePreference(
                        title = stringResource(R.string.euicc_uicc_capabilities),
                        value = formatTechnicalValues(info.uiccCapabilities),
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_remote_provisioning),
                        value = formatTechnicalValues(info.rspCapabilities),
                    )
                }
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.euicc_provisioning)) }
            item {
                GroupedCard {
                    ArrowPreference(
                        title = stringResource(R.string.euicc_default_smdp),
                        summary = info.defaultSmdpAddress.ifBlank {
                            stringResource(R.string.euicc_not_configured)
                        },
                        onClick = { showDefaultSmdpEditor = true },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.euicc_root_smds),
                        summary = info.rootSmdsAddress.ifBlank {
                            stringResource(R.string.euicc_not_configured)
                        },
                        onClick = { showSmdsEditor = true },
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_platform_label),
                        value = info.platformLabel.ifBlank { stringResource(R.string.common_unavailable) },
                    )
                    ValuePreference(
                        title = stringResource(R.string.euicc_discovery_service),
                        value = info.discoveryBaseUrl.ifBlank { stringResource(R.string.common_unavailable) },
                    )
                }
            }
            if (discoveredSmdpAddresses.isNotEmpty()) {
                item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.euicc_discovered_profiles)) }
                item {
                    GroupedCard {
                        discoveredSmdpAddresses.forEach { address ->
                            ArrowPreference(
                                title = address,
                                summary = stringResource(R.string.euicc_open_discovered_summary),
                                onClick = { onUseDiscoveredAddress(address) },
                            )
                        }
                    }
                }
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.profile_advanced_section)) }
            item {
                GroupedCard {
                    BasicComponent(
                        title = stringResource(R.string.euicc_keys_policy),
                        summary = if (advancedDetailsExpanded) {
                            stringResource(R.string.euicc_keys_policy_hide)
                        } else {
                            stringResource(
                                R.string.euicc_key_counts,
                                info.signingKeyIds.size,
                                info.verificationKeyIds.size,
                            )
                        },
                        endActions = {
                            Icon(
                                imageVector = if (advancedDetailsExpanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                                contentDescription = stringResource(
                                    if (advancedDetailsExpanded) {
                                        R.string.accessibility_show_less
                                    } else {
                                        R.string.accessibility_show_more
                                    },
                                ),
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
                                title = stringResource(R.string.euicc_signing_keys, info.signingKeyIds.size),
                                value = formatKeyIds(info.signingKeyIds),
                            )
                            ValuePreference(
                                title = stringResource(
                                    R.string.euicc_verification_keys,
                                    info.verificationKeyIds.size,
                                ),
                                value = formatKeyIds(info.verificationKeyIds),
                            )
                            ValuePreference(
                                title = stringResource(R.string.euicc_forbidden_rules),
                                value = formatTechnicalValues(info.forbiddenProfilePolicyRules),
                            )
                        }
                    }
                }
            }
            if (!settings.hideEuiccMemoryReset) {
                item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.euicc_maintenance)) }
                item {
                    GroupedCard {
                        ArrowPreference(
                            title = stringResource(R.string.euicc_reset_memory),
                            summary = stringResource(R.string.euicc_reset_memory_summary),
                            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                            onClick = { showReset = true },
                        )
                    }
                }
            }
        }
    }
    ProvisioningAddressDialog(
        show = showDefaultSmdpEditor,
        title = stringResource(R.string.euicc_default_smdp_dialog),
        summary = stringResource(R.string.euicc_default_smdp_dialog_summary),
        initialValue = info?.defaultSmdpAddress.orEmpty(),
        allowBlank = false,
        confirmText = stringResource(R.string.common_save),
        onDismiss = { showDefaultSmdpEditor = false },
        onConfirm = { address ->
            onSetDefaultSmdpAddress(address)
            showDefaultSmdpEditor = false
        },
    )
    ProvisioningAddressDialog(
        show = showSmdsEditor,
        title = stringResource(R.string.euicc_discover_profiles),
        summary = stringResource(R.string.euicc_discover_profiles_summary),
        initialValue = info?.rootSmdsAddress.orEmpty(),
        allowBlank = info?.rootSmdsAddress?.isNotBlank() == true,
        confirmText = stringResource(R.string.common_discover),
        onDismiss = { showSmdsEditor = false },
        onConfirm = { address ->
            onDiscoverProfiles(address.trim().takeIf(String::isNotBlank))
            showSmdsEditor = false
        },
    )
    TextInputDialog(
        show = showCardNameEditor && info != null,
        title = stringResource(R.string.euicc_card_name),
        summary = stringResource(R.string.euicc_card_name_dialog_summary),
        initialValue = cardName.orEmpty(),
        allowBlank = true,
        inputFilter = { it.takeUnicodeCodePoints(MaxEuiccNameCharacters) },
        onDismiss = { showCardNameEditor = false },
        onConfirm = { name ->
            onSetCardName(name.trim())
            showCardNameEditor = false
        },
    )
    OverlayDialog(
        show = showReset,
        title = stringResource(R.string.euicc_reset_first_title),
        summary = stringResource(R.string.euicc_reset_first_summary),
        onDismissRequest = { showReset = false },
    ) {
        DialogActionRow(
            onCancel = { showReset = false },
            confirmText = stringResource(R.string.common_continue),
            destructive = true,
            onConfirm = {
                showReset = false
                showResetConfirmation = true
            },
        )
    }
    OverlayDialog(
        show = showResetConfirmation,
        title = stringResource(R.string.euicc_reset_second_title),
        summary = stringResource(R.string.euicc_reset_second_summary),
        onDismissRequest = { showResetConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showResetConfirmation = false },
            confirmText = stringResource(R.string.common_continue),
            destructive = true,
            onConfirm = {
                showResetConfirmation = false
                showFinalResetConfirmation = true
            },
        )
    }
    OverlayDialog(
        show = showFinalResetConfirmation,
        title = stringResource(R.string.euicc_reset_final_title),
        summary = stringResource(R.string.euicc_reset_final_summary),
        onDismissRequest = { showFinalResetConfirmation = false },
    ) {
        DialogActionRow(
            onCancel = { showFinalResetConfirmation = false },
            confirmText = stringResource(R.string.euicc_reset_action),
            destructive = true,
            onConfirm = {
                showFinalResetConfirmation = false
                onReset()
            },
        )
    }
}

@Composable
private fun ProvisioningAddressDialog(
    show: Boolean,
    title: String,
    summary: String,
    initialValue: String,
    allowBlank: Boolean,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val tooLong = stringResource(R.string.euicc_server_address_too_long)
    TextInputDialog(
        show = show,
        title = title,
        summary = summary,
        label = stringResource(R.string.euicc_server_address_label),
        initialValue = initialValue,
        confirmText = confirmText,
        maxLength = MaxProvisioningAddressCharacters * 4,
        allowBlank = allowBlank,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        validate = { value -> tooLong.takeIf { value.length > MaxProvisioningAddressCharacters } },
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
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
    val showSnackbar = LocalMiuixSnackbar.current
    val permissionRequiredMessage = stringResource(R.string.profile_reminder_permission_required)
    val manageNotificationPermission = {
        if (notificationPermissionGranted) {
            onOpenNotificationSettings()
        } else {
            onRequestNotificationPermission { granted ->
                if (!granted) {
                    showSnackbar(permissionRequiredMessage, SnackbarDuration.Long)
                }
            }
        }
    }

    DetailLazyScaffold(title = stringResource(R.string.tags_reminders_title), onBack = onBack) { _ ->
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.tags_reminders_general)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.tags_manager),
                    summary = stringResource(R.string.tags_manager_summary),
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
                                    showSnackbar(permissionRequiredMessage, SnackbarDuration.Long)
                                }
                            }
                        }
                    },
                    title = stringResource(R.string.reminders_profile),
                    summary = stringResource(R.string.reminders_profile_summary),
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.reminders_notifications)) }
        item {
            GroupedCard {
                ArrowPreference(
                    title = stringResource(R.string.reminders_permission),
                    summary = stringResource(
                        if (notificationPermissionGranted) {
                            R.string.reminders_permissions_active
                        } else {
                            R.string.reminders_permissions_required
                        },
                    ),
                    onClick = manageNotificationPermission,
                )
                ArrowPreference(
                    title = stringResource(R.string.reminders_test),
                    summary = stringResource(R.string.reminders_test_summary),
                    onClick = {
                        onRequestNotificationPermission { granted ->
                            if (granted) {
                                onTestNotification()
                            } else {
                                showSnackbar(permissionRequiredMessage, SnackbarDuration.Long)
                            }
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.reminders_view_scheduled),
                    summary = stringResource(R.string.reminders_view_scheduled_summary),
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

    DetailLazyScaffold(title = stringResource(R.string.profile_tags_title), onBack = onBack) { _ ->
        if (profiles.isEmpty()) {
            item(contentType = PageStart.Viewport) {
                EmptyState(
                    stringResource(R.string.tags_no_profiles),
                    stringResource(R.string.tags_no_profiles_message),
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        } else {
            item {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(MaxSearchQueryCharacters) },
                    label = stringResource(R.string.tags_search),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                )
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.tags_active)) }
            item {
                GroupedCard {
                    if (allTags.isEmpty()) {
                        ValuePreference(
                            title = stringResource(R.string.tags_none_yet),
                            value = stringResource(R.string.tags_none_yet_summary),
                        )
                    } else if (visibleTags.isEmpty()) {
                        ValuePreference(
                            title = stringResource(R.string.tags_no_matching),
                            value = stringResource(R.string.tags_no_matching_summary),
                        )
                    } else {
                        visibleTags.forEach { (tag, count) ->
                            ArrowPreference(
                                title = tag,
                                summary = pluralStringResource(
                                    R.plurals.tags_profile_count,
                                    count,
                                    count,
                                ),
                                onClick = { searchQuery = tag },
                            )
                        }
                    }
                }
            }
            item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.tags_profiles_section)) }
            if (filteredProfiles.isEmpty()) {
                item(contentType = PageStart.Viewport) {
                    EmptyState(
                        title = stringResource(R.string.tags_none_found),
                        message = stringResource(R.string.tags_none_found_message),
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
            } else {
                item {
                    GroupedCard {
                        filteredProfiles.forEach { profile ->
                            ArrowPreference(
                                title = profile.nickname.ifBlank {
                                    profile.name.ifBlank { stringResource(R.string.profile_default_name) }
                                },
                                summary = profile.tags.takeIf { it.isNotEmpty() }?.joinToString()
                                    ?: stringResource(R.string.profile_no_tags),
                                onClick = {
                                    selected = profile
                                    editableTags = profile.tags
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
        title = selected?.nickname?.ifBlank { selected?.name.orEmpty() }
            ?.ifBlank { stringResource(R.string.profile_tags_title) },
        onDismissRequest = { selected = null },
    ) {
        ProfileTagsEditor(
            tags = editableTags,
            suggestedTags = allTags.mapTo(linkedSetOf()) { it.first },
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
) {
    val scheduled = profiles.filter { it.reminderAt != null }.sortedBy { it.reminderAt }
    val now = Instant.now()
    val upcoming = scheduled.filter { it.reminderAt?.isAfter(now) == true }
    val pastDue = scheduled.filterNot { it.reminderAt?.isAfter(now) == true }
    DetailLazyScaffold(title = stringResource(R.string.reminders_scheduled_title), onBack = onBack) { _ ->
        if (scheduled.isEmpty()) {
            item(contentType = PageStart.Viewport) {
                EmptyState(
                    title = stringResource(R.string.reminders_none),
                    message = stringResource(R.string.reminders_none_message),
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        } else {
            if (upcoming.isNotEmpty()) {
                item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.reminders_upcoming)) }
                item {
                    GroupedCard {
                        upcoming.forEach { profile ->
                            ReminderManagerPreference(profile, onOpen)
                        }
                    }
                }
            }
            if (pastDue.isNotEmpty()) {
                item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.reminders_past_due)) }
                item {
                    GroupedCard {
                        pastDue.forEach { profile ->
                            ReminderManagerPreference(profile, onOpen)
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
) {
    ArrowPreference(
        title = profile.nickname.ifBlank {
            profile.name.ifBlank { stringResource(R.string.profile_default_name) }
        },
        summary = profile.reminderAt?.formatReminderDate(),
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
    DetailLazyScaffold(title = stringResource(R.string.statistics_title), onBack = onBack) { _ ->
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.tags_profiles_section)) }
        item {
            GroupedCard {
                StatRow(stringResource(R.string.statistics_total_profiles), profiles.size.toString())
                StatRow(stringResource(R.string.statistics_enabled), enabled.toString())
                StatRow(stringResource(R.string.statistics_disabled), (profiles.size - enabled).toString())
                StatRow(stringResource(R.string.statistics_tagged), tagged.toString())
                StatRow(
                    stringResource(R.string.statistics_testing),
                    profiles.count { it.profileClass == ProfileClass.TESTING }.toString(),
                )
                StatRow(
                    stringResource(R.string.statistics_provisioning),
                    profiles.count { it.profileClass == ProfileClass.PROVISIONING }.toString(),
                )
            }
        }
        item(contentType = PageStart.Heading) { SectionHeading(stringResource(R.string.statistics_euicc_activity)) }
        item {
            GroupedCard {
                StatRow(stringResource(R.string.statistics_pending_notifications), notifications.size.toString())
                StatRow(
                    stringResource(R.string.statistics_estimated_data),
                    if (estimatedBytes > 0) {
                        formatBytes(estimatedBytes)
                    } else {
                        stringResource(R.string.statistics_not_available)
                    },
                )
                StatRow(
                    stringResource(R.string.statistics_unique_providers),
                    profiles.map(ProfileInfo::providerName).filter(String::isNotBlank).distinct().size.toString(),
                )
                StatRow(
                    stringResource(R.string.statistics_unique_tags),
                    profiles.flatMap(ProfileInfo::tags).map(String::lowercase).distinct().size.toString(),
                )
            }
        }
    }
}

@Composable
fun LogsScreen(
    logs: List<ActivityLogEntry>,
    onBack: () -> Unit,
    onExportSupportReport: (Uri, (Boolean) -> Unit) -> Unit,
) {
    val showSnackbar = LocalMiuixSnackbar.current
    val logsExported = stringResource(R.string.logs_exported)
    val logsExportFailed = stringResource(R.string.logs_export_failed)
    val exportSupportReport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onExportSupportReport(uri) { success ->
            showSnackbar(
                if (success) logsExported else logsExportFailed,
                SnackbarDuration.Short,
            )
        }
    }
    val levels = listOf<LogLevel?>(null) + LogLevel.entries
    var selectedLevel by remember { mutableIntStateOf(0) }
    val visibleLogs = logs
        .mapIndexed { index, entry -> index to entry }
        .asReversed()
        .filter { (_, entry) -> levels[selectedLevel] == null || entry.level == levels[selectedLevel] }
    DetailLazyScaffold(
        title = stringResource(R.string.logs_title),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { exportSupportReport.launch("hyperlpa-support-report.txt") },
            ) {
                Icon(
                    MiuixIcons.UploadCloud,
                    contentDescription = stringResource(R.string.logs_export_report),
                )
            }
        },
    ) { _ ->
        item(contentType = PageStart.Inset) {
            TipCard {
                Text(
                    text = stringResource(R.string.logs_support_included),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.logs_support_excluded),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        item {
            GroupedCard {
                OverlayDropdownPreference(
                    items = listOf(stringResource(R.string.logs_all_levels)) +
                        LogLevel.entries.map { stringResource(it.labelResource()) },
                    selectedIndex = selectedLevel,
                    title = stringResource(R.string.logs_level),
                    summary = pluralStringResource(
                        R.plurals.logs_visible_entries,
                        visibleLogs.size,
                        visibleLogs.size,
                    ),
                    onSelectedIndexChange = { selectedLevel = it },
                )
            }
        }
        if (visibleLogs.isEmpty()) {
            item(contentType = PageStart.Viewport) {
                EmptyState(
                    stringResource(R.string.logs_empty),
                    stringResource(R.string.logs_empty_message),
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        } else {
            visibleLogs.forEach { (sourceIndex, entry) ->
                item(key = sourceIndex) {
                    LogCard(entry)
                }
            }
        }
    }
}

internal fun isProfileDetailsLoading(
    profile: ProfileInfo?,
    lpa: LpaRepositoryState,
): Boolean = when {
    profile != null -> false
    !lpa.initialized -> true
    lpa.operation is LpaOperation.DiscoveringReaders -> true
    lpa.operation is LpaOperation.Connecting -> true
    lpa.operation is LpaOperation.Refreshing -> true
    else -> false
}

internal fun profileDeleteFinalSummaryRes(profileState: ProfileState?): Int =
    if (profileState == ProfileState.ENABLED) {
        R.string.profile_delete_final_enabled_summary
    } else {
        R.string.profile_delete_final_disabled_summary
    }

@Composable
private fun ProfileHero(
    profile: ProfileInfo,
    settings: AppSettings,
    artworkBitmap: Bitmap?,
    displayName: FormattedProfileDisplayName,
    onOpenIconOptions: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenReminder: () -> Unit,
) {
    val context = LocalContext.current
    val showSnackbar = LocalMiuixSnackbar.current
    val copyPhoneLabel = stringResource(R.string.profile_copy_phone)
    val phoneClipboardLabel = stringResource(R.string.profile_phone_clipboard_label)
    val phoneCopiedMessage = stringResource(R.string.profile_phone_copied)
    val phoneNumberInteractionSource = remember { MutableInteractionSource() }
    val profileIconInteractionSource = remember { MutableInteractionSource() }
    val profileTags = profile.tags
        .filter(String::isNotBlank)
        .sortedBy(String::lowercase)
    val reminderText = profile.reminderAt?.formatReminderDate()
    val hasHeroMetadata = profileTags.isNotEmpty() || reminderText != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = if (hasHeroMetadata) 0.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ResolvedProfileArtwork(
            profile = profile,
            bitmap = artworkBitmap,
            isEnabled = profile.state == ProfileState.ENABLED,
            modifier = Modifier.clickable(
                interactionSource = profileIconInteractionSource,
                indication = null,
                onClickLabel = stringResource(R.string.profile_custom_icon),
                onClick = onOpenIconOptions,
            ),
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
                    onClickLabel = copyPhoneLabel,
                ) {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(
                            ClipData.newPlainText(phoneClipboardLabel, displayName.phoneText),
                        )
                    showSnackbar(phoneCopiedMessage, SnackbarDuration.Short)
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
            text = profile.providerName.ifBlank { stringResource(R.string.profile_unknown_operator) },
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = redactIdentifier(profile.iccid, settings.iccidRedaction),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (hasHeroMetadata) {
            Spacer(Modifier.height(6.dp))
            ProfileHeroMetadataRow(
                tags = profileTags,
                reminderText = reminderText,
                onOpenTags = onOpenTags,
                onOpenReminder = onOpenReminder,
            )
        }
    }
}

@Composable
private fun ProfileHeroMetadataRow(
    tags: List<String>,
    reminderText: String?,
    onOpenTags: () -> Unit,
    onOpenReminder: () -> Unit,
) {
    val visibleTagCount = if (reminderText != null) 1 else 2
    val visibleTags = tags.take(visibleTagCount)
    val hiddenTagCount = (tags.size - visibleTags.size).coerceAtLeast(0)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (visibleTags.isNotEmpty()) {
            ProfileHeroMetadataAction(
                onClickLabel = stringResource(R.string.profile_tags),
                onClick = onOpenTags,
            ) {
                visibleTags.forEach { tag ->
                    ProfileHeroTagChip(text = tag)
                }
                if (hiddenTagCount > 0) {
                    ProfileHeroTagChip(
                        text = stringResource(R.string.profile_tags_overflow, hiddenTagCount),
                    )
                }
            }
        }
        if (visibleTags.isNotEmpty() && reminderText != null) {
            Spacer(Modifier.width(2.dp))
        }
        reminderText?.let { dateText ->
            ProfileHeroMetadataAction(
                onClickLabel = stringResource(R.string.profile_reminder),
                onClick = onOpenReminder,
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            color = MiuixTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(percent = 50),
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Alarm,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = dateText,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 128.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileHeroMetadataAction(
    onClickLabel: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        content()
    }
}

@Composable
private fun ProfileHeroTagChip(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 104.dp)
            .background(
                color = MiuixTheme.colorScheme.secondaryContainerVariant,
                shape = RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
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
    onTagsChange: (Set<String>) -> Unit,
    onCancel: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val rows = remember {
        mutableStateListOf<String>().apply {
            addAll(tags.sortedBy(String::lowercase))
            addAll(
                suggestedTags
                    .filter { suggestion -> tags.none { it.equals(suggestion, ignoreCase = true) } }
                    .sortedBy(String::lowercase)
                    .take(6),
            )
        }
    }
    var showAddTag by remember { mutableStateOf(false) }
    val atLimit = tags.size >= MaxProfileTags

    Column {
        Text(
            text = stringResource(R.string.tags_editor_summary),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        rows.forEach { tag ->
            val checked = tags.any { it.equals(tag, ignoreCase = true) }
            CheckboxPreference(
                title = tag,
                checked = checked,
                checkboxLocation = CheckboxLocation.End,
                enabled = checked || !atLimit,
                onCheckedChange = { add ->
                    onTagsChange(
                        if (add) {
                            tags.withTagInput(tag)
                        } else {
                            tags.filterNot { it.equals(tag, ignoreCase = true) }.toSet()
                        },
                    )
                },
            )
        }
        ArrowPreference(
            title = stringResource(R.string.tags_add),
            summary = stringResource(if (atLimit) R.string.tags_limit_reached else R.string.tags_add_summary),
            enabled = !atLimit,
            onClick = { showAddTag = true },
        )
        Spacer(Modifier.height(8.dp))
        DialogActionRow(
            onCancel = onCancel,
            confirmText = stringResource(R.string.common_save),
            onConfirm = { onSave(tags) },
        )
        BottomSheetFooterSpacer()
    }

    TextInputDialog(
        show = showAddTag,
        title = stringResource(R.string.tags_add),
        summary = stringResource(R.string.tags_add_dialog_summary),
        label = stringResource(R.string.tags_input_label),
        initialValue = "",
        confirmText = stringResource(R.string.common_add),
        onDismiss = { showAddTag = false },
        onConfirm = { input ->
            val updated = tags.withTagInput(input)
            updated.filter { tag -> rows.none { it.equals(tag, ignoreCase = true) } }.forEach(rows::add)
            onTagsChange(updated)
            showAddTag = false
        },
    )
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
                Icon(
                    imageVector = when (entry.level) {
                        LogLevel.ERROR -> MiuixIcons.Delete
                        LogLevel.WARNING -> MiuixIcons.Info
                        LogLevel.DEBUG -> MiuixIcons.Search
                        LogLevel.INFO -> MiuixIcons.Refresh
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = when (entry.level) {
                        LogLevel.ERROR -> MiuixTheme.colorScheme.error
                        LogLevel.WARNING -> MiuixTheme.colorScheme.secondary
                        else -> MiuixTheme.colorScheme.primary
                    },
                )
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
                    stringResource(entry.level.labelResource()),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(entry.message, style = MiuixTheme.textStyles.body2)
        }
    }
}

private fun Set<String>.withTagInput(input: String): Set<String> =
    normalizeProfileTags(this + input.split(',', '\n'))

private fun Instant.formatDateTime(): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())
    .format(this)

private fun ProfileClass.labelResource(): Int = when (this) {
    ProfileClass.OPERATIONAL -> R.string.profile_class_operational
    ProfileClass.TESTING -> R.string.profile_class_testing
    ProfileClass.PROVISIONING -> R.string.profile_class_provisioning
    ProfileClass.UNKNOWN -> R.string.profile_class_unknown
}

private fun ReaderKind.labelResource(): Int = when (this) {
    ReaderKind.NBRIDGE -> R.string.reader_kind_nbridge
    ReaderKind.OMAPI -> R.string.reader_kind_omapi
    ReaderKind.TELEPHONY -> R.string.reader_kind_telephony
    ReaderKind.USB_CCID -> R.string.reader_kind_usb
    ReaderKind.BLE -> R.string.reader_kind_bluetooth
    ReaderKind.REMOTE -> R.string.reader_kind_remote
}

private fun LogLevel.labelResource(): Int = when (this) {
    LogLevel.DEBUG -> R.string.log_level_debug
    LogLevel.INFO -> R.string.log_level_info
    LogLevel.WARNING -> R.string.log_level_warning
    LogLevel.ERROR -> R.string.log_level_error
}

@Composable
private fun formatTechnicalValues(values: Set<String>): String = if (values.isEmpty()) {
    stringResource(R.string.common_unavailable)
} else {
    values.map { value -> formatTechnicalValue(value) }.sorted().joinToString()
}

@Composable
private fun formatTechnicalValue(value: String): String = when (value) {
    "notificationInstall" -> stringResource(R.string.technical_notification_install)
    "notificationLocalEnable" -> stringResource(R.string.technical_notification_local_enable)
    "notificationLocalDisable" -> stringResource(R.string.technical_notification_local_disable)
    "notificationLocalDelete" -> stringResource(R.string.technical_notification_local_delete)
    "notificationRpmEnable" -> stringResource(R.string.technical_notification_remote_enable)
    "notificationRpmDisable" -> stringResource(R.string.technical_notification_remote_disable)
    "notificationRpmDelete" -> stringResource(R.string.technical_notification_remote_delete)
    "loadRpmPackageResult" -> stringResource(R.string.technical_remote_package_result)
    "pprUpdateControl" -> stringResource(R.string.technical_ppr_update_control)
    "ppr1" -> stringResource(R.string.technical_ppr1)
    "ppr2" -> stringResource(R.string.technical_ppr2)
    "ppr3" -> stringResource(R.string.technical_ppr3)
    "contactlessSupport" -> stringResource(R.string.technical_contactless)
    "usimSupport" -> stringResource(R.string.technical_usim)
    "isimSupport" -> stringResource(R.string.technical_isim)
    "csimSupport" -> stringResource(R.string.technical_csim)
    "akaMilenage" -> stringResource(R.string.technical_aka_milenage)
    "akaCave" -> stringResource(R.string.technical_aka_cave)
    "akaTuak128" -> stringResource(R.string.technical_aka_tuak_128)
    "akaTuak256" -> stringResource(R.string.technical_aka_tuak_256)
    "gbaAuthenUsim" -> stringResource(R.string.technical_gba_authentication_usim)
    "gbaAuthenISim" -> stringResource(R.string.technical_gba_authentication_isim)
    "mbmsAuthenUsim" -> stringResource(R.string.technical_mbms_authentication)
    "eapClient" -> stringResource(R.string.technical_eap_client)
    "javacard" -> stringResource(R.string.technical_java_card)
    "multos" -> stringResource(R.string.technical_multos)
    "multipleUsimSupport" -> stringResource(R.string.technical_multiple_usims)
    "multipleIsimSupport" -> stringResource(R.string.technical_multiple_isims)
    "multipleCsimSupport" -> stringResource(R.string.technical_multiple_csims)
    "berTlvFileSupport" -> stringResource(R.string.technical_ber_tlv_files)
    "dfLinkSupport" -> stringResource(R.string.technical_df_links)
    "catTp" -> stringResource(R.string.technical_cat_tp)
    "getIdentity" -> stringResource(R.string.technical_get_identity)
    "profile-a-x25519" -> stringResource(R.string.technical_profile_a_x25519)
    "profile-b-p256" -> stringResource(R.string.technical_profile_b_p256)
    "suciCalculatorApi" -> stringResource(R.string.technical_suci_calculator_api)
    "additionalProfile" -> stringResource(R.string.technical_additional_profiles)
    "crlSupport" -> stringResource(R.string.technical_certificate_revocation_lists)
    "rpmSupport" -> stringResource(R.string.technical_remote_profile_management)
    "testProfileSupport" -> stringResource(R.string.technical_test_profiles)
    "deviceInfoExtensibilitySupport" -> stringResource(
        R.string.technical_extensible_device_information,
    )
    "basicEuicc" -> stringResource(R.string.technical_basic)
    "mediumEuicc" -> stringResource(R.string.technical_medium)
    "contactlessEuicc" -> stringResource(R.string.technical_contactless)
    "other" -> stringResource(R.string.technical_other)
    else -> value
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replaceFirstChar(Char::uppercase)
}

@Composable
private fun formatKeyIds(values: Set<String>): String = if (values.isEmpty()) {
    stringResource(R.string.common_unavailable)
} else {
    values.map(String::uppercase).sorted().joinToString("\n")
}

@Composable
private fun formatBytes(bytes: Int): String = formatBytes(bytes.toLong())

@Composable
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return stringResource(R.string.size_bytes, bytes)
    val kib = bytes / 1024.0
    if (kib < 1024f) {
        return stringResource(R.string.size_kibibytes_decimal, (kib * 10).roundToInt() / 10.0)
    }
    val mib = kib / 1024.0
    return stringResource(R.string.size_mebibytes_decimal, (mib * 10).roundToInt() / 10.0)
}

private const val MaxBatchInputCharacters = 128 * 1024
private const val MaxActivationInputCharacters = 4_096
private const val MaxProvisioningAddressCharacters = 253
private const val MaxEuiccNameCharacters = 64
private const val MaxProfileTags = 16
private const val MaxSearchQueryCharacters = 256
private const val MaxQrEncodedImageBytes = 16 * 1024 * 1024
private const val MaxQrDecodedEdge = 2_048
private const val MaxQrSourceDimension = 100_000
