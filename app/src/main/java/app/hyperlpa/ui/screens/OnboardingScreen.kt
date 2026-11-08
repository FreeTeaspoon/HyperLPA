package app.hyperlpa.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.hyperlpa.R
import app.hyperlpa.data.settings.ProfileLayout
import app.hyperlpa.data.settings.RedactionMode
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.ui.BluetoothReaderUiState
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.OnboardingPreference
import app.hyperlpa.ui.OnboardingReader
import app.hyperlpa.ui.adaptive.horizontalCutoutPadding
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.GroupedCard
import app.hyperlpa.ui.components.LoadingState
import app.hyperlpa.ui.components.PageStart
import app.hyperlpa.ui.components.PageStateKind
import app.hyperlpa.ui.components.ProfileArtworkState
import app.hyperlpa.ui.components.animation.rememberSystemAnimationsEnabled
import app.hyperlpa.ui.onboardingConnectedReader
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.ZoneId

private const val OnboardingStepCount = 5
private const val ReaderStep = 1
private const val OnboardingStepDurationMillis = 100
private val OnboardingStepEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** One first-run destination; safe presentation state survives activity/process recreation. */
@Composable
fun OnboardingScreen(
    state: HyperLpaUiState,
    bluetoothReaderState: BluetoothReaderUiState,
    onPreferenceChange: (OnboardingPreference, Boolean) -> Unit,
    onLayoutChange: (ProfileLayout) -> Unit,
    onCheckReader: suspend (OnboardingReader) -> Unit,
    onOpenPairedDevices: () -> Unit,
    onFinish: () -> Unit,
    finishing: Boolean = false,
    active: Boolean = true,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var reader by rememberSaveable { mutableStateOf(OnboardingReader.PHONE) }
    var checkRequested by rememberSaveable { mutableStateOf(false) }
    var moreOptions by rememberSaveable { mutableStateOf(false) }
    var privacyDetails by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val discoveryRunning = state.lpa.operation.let {
        it is LpaOperation.DiscoveringReaders || it is LpaOperation.Connecting || it is LpaOperation.Refreshing
    }
    val checking = submitting || (checkRequested && discoveryRunning)
    val connectedReader = onboardingConnectedReader(state.lpa, reader)
    // Keep a finished check visible during a pull, rather than publishing partial discovery.
    var result by remember(reader) { mutableStateOf<Pair<Boolean, String?>?>(null) }
    LaunchedEffect(checkRequested, checking, state.lpa) {
        if (checkRequested && !checking && state.lpa.initialized) {
            result = (connectedReader != null) to connectedReader?.name
        }
    }
    val check: () -> Unit = {
        if (!submitting && !discoveryRunning) {
            checkRequested = true
            if (reader == OnboardingReader.PAIRED_DEVICE && state.lpa.readers.none { it.available && reader.accepts(it) }) {
                onOpenPairedDevices()
            } else {
                submitting = true
                scope.launch {
                    try { onCheckReader(reader) } finally { submitting = false }
                }
            }
        }
    }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = active && step > 0 && !finishing,
        onBackCompleted = { step-- },
    )
    val animationsEnabled = rememberSystemAnimationsEnabled()
    val stepTransition = updateTransition(step, label = "Onboarding step")
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = "",
                navigationIcon = {
                    if (step > 0) {
                        IconButton(onClick = { if (!finishing) step-- }) {
                            Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    Text(
                        stringResource(R.string.onboarding_step, step + 1, OnboardingStepCount),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(end = 20.dp),
                    )
                },
            )
        },
        bottomBar = {
            OnboardingFooter(
                step = step,
                interactionEnabled = !stepTransition.isRunning,
                busy = (step == ReaderStep && checking) || finishing,
                primaryLabel = when (step) {
                    0 -> R.string.onboarding_get_started
                    ReaderStep -> when {
                        result?.first == true -> R.string.common_continue
                        reader == OnboardingReader.PAIRED_DEVICE && state.lpa.readers.none { it.available && reader.accepts(it) } -> R.string.onboarding_pair_device
                        reader == OnboardingReader.BLUETOOTH && !bluetoothReaderState.permissionGranted -> R.string.reader_bluetooth_permission
                        reader == OnboardingReader.BLUETOOTH && !bluetoothReaderState.adapterEnabled -> R.string.reader_bluetooth_adapter
                        reader == OnboardingReader.PHONE -> R.string.onboarding_check_phone
                        else -> R.string.onboarding_connect_reader
                    }
                    2, 3 -> R.string.common_continue
                    else -> R.string.onboarding_open_app
                },
                onNext = {
                    when {
                        step == ReaderStep && result?.first != true -> check()
                        step == OnboardingStepCount - 1 -> onFinish()
                        else -> step++
                    }
                },
                onSkip = if (step == ReaderStep) ({ step++ }) else null,
            )
        },
    ) { padding ->
        stepTransition.AnimatedContent(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).clipToBounds(),
            transitionSpec = {
                val duration = if (animationsEnabled) OnboardingStepDurationMillis else 0
                (fadeIn(tween(duration, easing = OnboardingStepEasing)) togetherWith
                    fadeOut(tween(duration, easing = OnboardingStepEasing))).using(null)
            },
        ) { shownStep ->
            DetailLazyScaffold(
                title = "",
                scrollOnlyWhenOverflowing = true,
                showTopBar = false,
                onBack = {},
                isRefreshing = refreshing,
                refreshEnabled = shownStep == ReaderStep && result != null && !checking,
                onRefresh = if (shownStep == ReaderStep && reader != OnboardingReader.PAIRED_DEVICE && result != null) ({
                    if (!checking && !refreshing) {
                        refreshing = true
                        scope.launch {
                            try { onCheckReader(reader) } finally { refreshing = false }
                        }
                    }
                }) else null,
                pageState = if (shownStep == ReaderStep && checking && result == null) PageStateKind.LOADING else PageStateKind.CONTENT,
                pageStateContent = {
                    LoadingState(stringResource(R.string.compatibility_checking), Modifier.fillMaxSize())
                },
            ) { _ ->
                when (shownStep) {
                    0 -> item(contentType = PageStart.Viewport) { WelcomeContent() }
                    ReaderStep -> {
                        onboardingHeading(R.string.onboarding_reader_title)
                        item {
                            GroupedCard {
                                OnboardingReader.entries.forEach { choice ->
                                    RadioButtonPreference(
                                        title = stringResource(choice.title),
                                        selected = reader == choice,
                                        radioButtonLocation = RadioButtonLocation.End,
                                        enabled = choice != OnboardingReader.BLUETOOTH || bluetoothReaderState.supported,
                                        onClick = {
                                            reader = choice
                                            checkRequested = false
                                        },
                                    )
                                }
                            }
                        }
                        item {
                            val shownResult = result
                            if (shownResult != null) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp)
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        stringResource(if (shownResult.first) R.string.onboarding_reader_found else R.string.compatibility_result_none),
                                        style = MiuixTheme.textStyles.title3,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                    )
                                    shownResult.second?.let {
                                        Text(it, style = MiuixTheme.textStyles.body2,
                                            modifier = Modifier.padding(top = 8.dp))
                                    }
                                    OnboardingNote(if (shownResult.first) R.string.onboarding_reader_found_summary else R.string.onboarding_reader_missing)
                                }
                            } else {
                                OnboardingNote(when (reader) {
                                    OnboardingReader.PHONE -> R.string.onboarding_phone_hint
                                    OnboardingReader.USB -> R.string.onboarding_usb_hint
                                    OnboardingReader.BLUETOOTH -> R.string.onboarding_bluetooth_hint
                                    OnboardingReader.PAIRED_DEVICE -> R.string.onboarding_paired_hint
                                })
                            }
                        }
                    }
                    2 -> {
                        onboardingHeading(R.string.onboarding_online_title, R.string.onboarding_online_intro)
                        item {
                            GroupedCard {
                                SwitchPreference(
                                    title = stringResource(R.string.onboarding_download_icons),
                                    checked = state.settings.loadOperatorIcons,
                                    onCheckedChange = { onPreferenceChange(OnboardingPreference.OPERATOR_ICONS, it) },
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.onboarding_estimate_sizes),
                                    summary = stringResource(R.string.onboarding_sizes_summary),
                                    checked = state.settings.estimateProfileSize,
                                    onCheckedChange = { onPreferenceChange(OnboardingPreference.SIZE_ESTIMATES, it) },
                                )
                            }
                        }
                        item {
                            GroupedCard {
                                ArrowPreference(
                                    title = stringResource(R.string.onboarding_online_privacy),
                                    onClick = { privacyDetails = !privacyDetails },
                                )
                                AnimatedVisibility(privacyDetails, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(stringResource(R.string.privacy_cloud_data_use_summary),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        Text(stringResource(R.string.onboarding_local_data),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        onboardingHeading(R.string.onboarding_appearance_title)
                        item { OnboardingProfilePreview(state) }
                        item {
                            GroupedCard {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.profile_display_layout),
                                    items = listOf(stringResource(R.string.profile_layout_list), stringResource(R.string.profile_layout_waterfall)),
                                    selectedIndex = ProfileLayout.entries.indexOf(state.settings.profileLayout),
                                    onSelectedIndexChange = { onLayoutChange(ProfileLayout.entries[it]) },
                                )
                                OnboardingSwitch(state.settings.showProfileIconOnHome, R.string.profile_display_icon, OnboardingPreference.PROFILE_ICONS, onPreferenceChange)
                                OnboardingSwitch(state.settings.showProfileSizeOnHome, R.string.profile_display_size, OnboardingPreference.PROFILE_SIZES, onPreferenceChange)
                                OnboardingSwitch(state.settings.showProfileIccidOnHome, R.string.profile_display_iccid, OnboardingPreference.ICCID, onPreferenceChange)
                            }
                        }
                        item {
                            GroupedCard {
                                ArrowPreference(title = stringResource(R.string.onboarding_more_options), onClick = { moreOptions = !moreOptions })
                                AnimatedVisibility(moreOptions, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(), exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) {
                                    Column {
                                        OnboardingSwitch(state.settings.showProfileNameOnHome, R.string.profile_display_name, OnboardingPreference.NAMES, onPreferenceChange)
                                        OnboardingSwitch(state.settings.showProfileProviderOnHome, R.string.profile_display_provider, OnboardingPreference.PROVIDERS, onPreferenceChange)
                                        OnboardingSwitch(state.settings.showProfileCountryFlagOnHome, R.string.profile_display_country_flag, OnboardingPreference.COUNTRY_FLAGS, onPreferenceChange)
                                        OnboardingSwitch(state.settings.showProfileTagsOnHome, R.string.profile_display_tags, OnboardingPreference.TAGS, onPreferenceChange)
                                        OnboardingSwitch(state.settings.showProfileRemindersOnHome, R.string.profile_display_reminders, OnboardingPreference.REMINDERS, onPreferenceChange)
                                        OnboardingSwitch(state.settings.showProfileSwitchOnHome, R.string.profile_display_switch, OnboardingPreference.SWITCHES, onPreferenceChange)
                                        OnboardingSwitch(state.settings.showProfileSearch, R.string.profile_display_search, OnboardingPreference.SEARCH, onPreferenceChange)
                                        OnboardingSwitch(state.settings.showReaderSelectorOnHome, R.string.profile_display_reader_selector, OnboardingPreference.READER_SELECTOR, onPreferenceChange)
                                        OnboardingSwitch(state.settings.showEidOnHome, R.string.euicc_eid, OnboardingPreference.EID, onPreferenceChange)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        item(contentType = PageStart.Viewport) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(MiuixIcons.Basic.Check, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
                                Text(stringResource(R.string.onboarding_ready_title), fontSize = 32.sp, lineHeight = 38.sp,
                                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 24.dp).semantics { heading() })
                                Text(stringResource(if (state.lpa.euiccInfo != null) R.string.onboarding_ready_connected else R.string.onboarding_ready_later),
                                    style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.onboardingHeading(@StringRes title: Int, @StringRes summary: Int? = null) {
    item(contentType = PageStart.Viewport) {
        Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 22.dp)) {
            Text(stringResource(title), fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            if (summary != null) {
                Text(stringResource(summary), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun WelcomeContent() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.about_logo), contentDescription = null, modifier = Modifier.size(180.dp))
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.onboarding_welcome), fontSize = 32.sp, lineHeight = 38.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.onboarding_welcome_summary), style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun OnboardingNote(@StringRes text: Int) {
    Text(stringResource(text), style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp))
}

@Composable
private fun OnboardingSwitch(checked: Boolean, @StringRes title: Int, preference: OnboardingPreference, onChange: (OnboardingPreference, Boolean) -> Unit) {
    SwitchPreference(checked = checked, title = stringResource(title), onCheckedChange = { onChange(preference, it) })
}

@Composable
private fun OnboardingFooter(step: Int, busy: Boolean, interactionEnabled: Boolean, @StringRes primaryLabel: Int, onNext: () -> Unit, onSkip: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface).horizontalCutoutPadding()
        .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(text = if (busy) "" else stringResource(primaryLabel), onClick = { if (interactionEnabled) onNext() },
                    enabled = !busy, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth().testTag("onboarding-next"))
                if (busy) InfiniteProgressIndicator(color = MiuixTheme.colorScheme.onPrimary)
            }
            if (onSkip != null) {
                TextButton(text = stringResource(R.string.onboarding_setup_later), onClick = { if (interactionEnabled) onSkip() }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("onboarding-skip"))
            }
        }
        val progressDescription = stringResource(R.string.onboarding_step, step + 1, OnboardingStepCount)
        Row(Modifier.padding(top = 14.dp, bottom = 3.dp).testTag("onboarding-progress").semantics {
            contentDescription = progressDescription
            progressBarRangeInfo = ProgressBarRangeInfo(step.toFloat(), 0f..4f, 3)
        }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(OnboardingStepCount) { index ->
                Box(Modifier.height(5.dp).width(if (index == step) 18.dp else 5.dp)
                    .background(if (index == step) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = .15f), CircleShape))
            }
        }
    }
}

/** Sample data stays inside this read-only preview, never in the repository or cloud loader. */
@Composable
private fun OnboardingProfilePreview(state: HyperLpaUiState) {
    val settings = state.settings
    val sampleTitle = stringResource(R.string.onboarding_sample_everyday)
    val travelTitle = stringResource(R.string.onboarding_sample_travel)
    val provider = stringResource(R.string.onboarding_sample_provider)
    val travelProvider = stringResource(R.string.onboarding_sample_travel_provider)
    val personalTag = stringResource(R.string.onboarding_sample_personal_tag)
    val travelTag = stringResource(R.string.onboarding_sample_travel_tag)
    val reminderAt = remember { LocalDate.now().plusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant() }
    val samples = remember(sampleTitle, travelTitle, provider, travelProvider, personalTag, travelTag, settings.estimateProfileSize) {
        listOf(
            ProfileInfo("8900000000000001042", ProfileState.ENABLED, sampleTitle, "", provider, "", ProfileClass.OPERATIONAL,
                mcc = "505", mnc = "01", tags = setOf(personalTag), estimatedBytes = 92L * 1024),
            ProfileInfo("8900000000000002608", ProfileState.DISABLED, travelTitle, "", travelProvider, "", ProfileClass.OPERATIONAL,
                mcc = "440", mnc = "10", tags = setOf(travelTag), reminderAt = reminderAt,
                estimatedBytes = (84L * 1024).takeIf { settings.estimateProfileSize }, sizeIsEstimated = true),
        )
    }
    val bitmaps = remember { listOf(sampleArtwork("M", 0xff2767e9.toInt()), sampleArtwork("T", 0xffd56a20.toInt())) }
    val previewDescription = stringResource(R.string.onboarding_sample_preview)
    // Mask sample identifiers even if a real-profile redaction setting has not been configured.
    val previewState = state.copy(settings = settings.copy(iccidRedaction = RedactionMode.MIDDLE))
    Column(Modifier.fillMaxWidth().testTag("onboarding-profile-preview").clearAndSetSemantics { contentDescription = previewDescription }) {
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.onboarding_live_preview), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(stringResource(R.string.onboarding_sample_data), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (settings.profileLayout == ProfileLayout.WATERFALL && maxWidth >= 560.dp) {
                Row(Modifier.fillMaxWidth()) {
                    samples.forEachIndexed { index, profile ->
                        Box(Modifier.weight(1f)) {
                            PreviewCard(profile, previewState, bitmaps[index].takeIf { settings.loadOperatorIcons })
                        }
                    }
                }
            } else {
                Column {
                    samples.forEachIndexed { index, profile ->
                        PreviewCard(profile, previewState, bitmaps[index].takeIf { settings.loadOperatorIcons })
                    }
                }
            }
        }
        if (settings.profileLayout == ProfileLayout.WATERFALL) OnboardingNote(R.string.onboarding_waterfall_hint)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PreviewCard(profile: ProfileInfo, state: HyperLpaUiState, bitmap: Bitmap?) {
    ProfileCard(profile = profile, artwork = ProfileArtworkState(bitmap, ready = true), state = state,
        switchEnabled = true, onOpen = {}, onEnableChange = {}, onLongPress = {}, preview = true)
}

private fun sampleArtwork(letter: String, color: Int): Bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).also {
    Canvas(it).apply {
        drawColor(color)
        drawText(letter, 40f, 57f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 48f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
    }
}

private val OnboardingReader.title: Int get() = when (this) {
    OnboardingReader.PHONE -> R.string.onboarding_reader_phone
    OnboardingReader.USB -> R.string.onboarding_reader_usb
    OnboardingReader.BLUETOOTH -> R.string.onboarding_reader_bluetooth
    OnboardingReader.PAIRED_DEVICE -> R.string.onboarding_reader_paired
}
