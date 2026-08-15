package app.hyperlpa.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hyperlpa.BuildConfig
import app.hyperlpa.R
import app.hyperlpa.data.LpaRepository
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.backup.HyperLpaBackupManager
import app.hyperlpa.data.cloud.NekokoCloudService
import app.hyperlpa.data.history.NotificationHistoryEntry
import app.hyperlpa.data.history.NotificationHistoryStore
import app.hyperlpa.data.metadata.PendingProfileIconImport
import app.hyperlpa.data.metadata.ProfileMetadata
import app.hyperlpa.data.metadata.ProfileIconStorage
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.metadata.normalizeEuiccEid
import app.hyperlpa.data.metadata.providerIconKey
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.AppSettingsStore
import app.hyperlpa.data.settings.FloatingBottomBarStyle
import app.hyperlpa.data.settings.NavigationLabels
import app.hyperlpa.data.settings.NavigationStyle
import app.hyperlpa.data.settings.ProfileLayout
import app.hyperlpa.data.settings.PhoneFormatStrategy
import app.hyperlpa.data.settings.ProfileSort
import app.hyperlpa.data.settings.RedactionMode
import app.hyperlpa.data.settings.ThemeAccent
import app.hyperlpa.data.settings.ThemeMode
import app.hyperlpa.data.settings.ThemePalette
import app.hyperlpa.data.support.SupportReportBuilder
import app.hyperlpa.domain.model.DownloadRequest
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.OperationOutcome
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.provisioning.ProvisioningCoordinator
import app.hyperlpa.reminders.withProfileReminderIsolation
import app.hyperlpa.ui.navigation.AppRoute
import app.hyperlpa.ui.navigation.AppTab
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Instant

data class HyperLpaUiState(
    val settingsLoaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val lpa: LpaRepositoryState = LpaRepositoryState(),
    val selectedTab: AppTab = AppTab.PROFILES,
    val searchQuery: String = "",
    val activationCodeDraft: String = "",
    val metadata: Map<String, ProfileMetadata> = emptyMap(),
    val providerIcons: Map<String, String> = emptyMap(),
    val euiccNames: Map<String, String> = emptyMap(),
    val operatorIcons: Map<String, ByteArray> = emptyMap(),
    val profileSizePredictions: Map<String, Long> = emptyMap(),
    val downloadPreviewIcon: ByteArray? = null,
    val estimatedDownloadBytes: Long? = null,
    val downloadPreviewEnrichmentLoading: Boolean = false,
    val showCancelDownloadConfirmation: Boolean = false,
    val pendingProfileDisableConfirmation: String? = null,
    val profileEnrichmentReady: Boolean = false,
    val notificationHistory: List<NotificationHistoryEntry> = emptyList(),
) {
    val currentEuiccName: String?
        get() = normalizeEuiccEid(lpa.euiccInfo?.eid)?.let { eid -> euiccNames[eid] }

    val profiles: List<ProfileInfo> by lazy {
        profilesWithOptimisticSwitch(lpa.profiles, lpa.operation)
            .map { profile ->
                val extra = metadata[profile.iccid]
                val measuredBytes = extra
                    ?.installedBytes
                    ?.takeIf { extra.installedEid == null || extra.installedEid == lpa.euiccInfo?.eid }
                profile.copy(
                    tags = extra?.tags.orEmpty(),
                    reminderAt = extra?.reminderAt,
                    customIconUri = resolveProfileIconUri(
                        metadata = extra,
                        providerName = profile.providerName,
                        providerIcons = providerIcons,
                    ),
                    smdpAddress = extra?.smdpAddress ?: profile.smdpAddress,
                    isPinned = extra?.isPinned == true,
                    estimatedBytes = measuredBytes ?: profileSizePredictions[profile.iccid],
                    sizeIsEstimated = measuredBytes == null &&
                        profileSizePredictions[profile.iccid] != null,
                )
            }
            .filter { profile ->
                searchQuery.isBlank() || listOf(
                    profile.nickname,
                    profile.name,
                    profile.providerName,
                    profile.iccid,
                    profile.tags.joinToString(" "),
                ).any { value -> value.contains(searchQuery, ignoreCase = true) }
            }
            .let { profiles ->
                val comparator = when (settings.profileSort) {
                    ProfileSort.SLOT_ORDER -> compareBy<ProfileInfo> { lpa.profiles.indexOfFirst { source -> source.iccid == it.iccid } }
                    ProfileSort.NAME -> compareBy { it.nickname.ifBlank { it.name }.lowercase() }
                    ProfileSort.PROVIDER -> compareBy { it.providerName.lowercase() }
                    ProfileSort.ICCID -> compareBy { it.iccid }
                    ProfileSort.STATE -> compareBy { it.state.ordinal }
                }
                profiles
                    .sortedWith(if (settings.sortAscending) comparator else comparator.reversed())
                    .sortedWith(compareByDescending<ProfileInfo> { it.isPinned })
            }
    }
}

internal fun resolveProfileIconUri(
    metadata: ProfileMetadata?,
    providerName: String?,
    providerIcons: Map<String, String>,
): String? = metadata?.iconUri ?: providerIconKey(providerName)
    ?.takeIf { metadata?.isProviderIconHidden != true }
    ?.let(providerIcons::get)

internal fun profilesWithOptimisticSwitch(
    profiles: List<ProfileInfo>,
    operation: LpaOperation,
): List<ProfileInfo> {
    val switching = operation as? LpaOperation.Switching ?: return profiles
    val previousEnabledIccid = profiles
        .singleOrNull { profile ->
            profile.iccid != switching.iccid && profile.state == ProfileState.ENABLED
        }
        ?.iccid
    return profiles.map { profile ->
        val displayedState = when {
            profile.iccid == switching.iccid -> if (switching.enable) {
                ProfileState.ENABLED
            } else {
                ProfileState.DISABLED
            }
            switching.enable && profile.iccid == previousEnabledIccid -> ProfileState.DISABLED
            else -> profile.state
        }
        if (displayedState == profile.state) profile else profile.copy(state = displayedState)
    }
}

class HyperLpaViewModel(
    application: Application,
    private val settingsStore: AppSettingsStore,
    private val metadataStore: ProfileMetadataStore,
    private val repository: LpaRepository,
    private val cloudService: NekokoCloudService,
    private val notificationHistoryStore: NotificationHistoryStore,
    private val supportReportBuilder: SupportReportBuilder,
    private val provisioningCoordinator: ProvisioningCoordinator,
) : AndroidViewModel(application) {
    private val backupManager = HyperLpaBackupManager(application, settingsStore, metadataStore)
    private val profileIconStorage = ProfileIconStorage(application)
    private val backStack = navBackStackOf(AppRoute.Shell)
    val navigationBackStack: NavBackStack = backStack
    private val selectedTab = MutableStateFlow(AppTab.PROFILES)
    private val searchQuery = MutableStateFlow("")
    private val activationCodeDraft = MutableStateFlow("")
    private val cloudProfileData = MutableStateFlow(CloudProfileData())
    private val cloudRefreshToken = MutableStateFlow(0)
    private val downloadPreviewCloudData = MutableStateFlow(DownloadPreviewCloudData())
    private var suppressedCloudProfileSource: CloudEnrichmentKey? = null
    private var suppressedDownloadPreviewSource: DownloadPreviewCloudSourceKey? = null
    private val showCancelDownloadConfirmation = MutableStateFlow(false)
    private val pendingProfileDisableConfirmation = MutableStateFlow<String?>(null)
    private val cloudEnrichmentSemaphore = Semaphore(CloudEnrichmentConcurrency)
    // Serializes settings/profile-metadata edits with backup snapshots and restores. Repository
    // operations have their own mutex; restore additionally acquires that barrier before commit.
    private val dataMutationMutex = Mutex()
    private val _backupOperationInProgress = MutableStateFlow(false)
    val backupOperationInProgress = _backupOperationInProgress.asStateFlow()
    val batchDownloadState = provisioningCoordinator.batchState
    val singleDownloadActive = provisioningCoordinator.singleDownloadActive
    private var simStateRefreshJob: Job? = null
    private var pendingBackupPassword: CharArray? = null
    private var notificationPermissionContinuation: ((Boolean) -> Unit)? = null
    private var runtimePermissionRequestInProgress = false

    @Suppress("UNCHECKED_CAST")
    val state = combine(
        settingsStore.settings,
        repository.state,
        selectedTab,
        searchQuery,
        activationCodeDraft,
        metadataStore.metadata,
        metadataStore.providerIcons,
        cloudRefreshToken,
        cloudProfileData,
        downloadPreviewCloudData,
        showCancelDownloadConfirmation,
        notificationHistoryStore.history,
        pendingProfileDisableConfirmation,
        metadataStore.euiccNames,
    ) { values ->
        val settings = values[0] as AppSettings
        val lpa = values[1] as LpaRepositoryState
        val metadata = values[5] as Map<String, ProfileMetadata>
        val refreshToken = values[7] as Int
        val cloudData = values[8] as CloudProfileData
        val previewCloudData = values[9] as DownloadPreviewCloudData
        val expectedCloudInput = CloudInputs(
            loadOperatorIcons = settings.loadOperatorIcons,
            estimateProfileSize = settings.estimateProfileSize,
            profiles = lpa.profiles,
            eid = lpa.euiccInfo?.eid,
            metadata = metadata,
            refreshToken = refreshToken,
        )
        HyperLpaUiState(
            settingsLoaded = true,
            settings = settings,
            lpa = lpa,
            selectedTab = values[2] as AppTab,
            searchQuery = values[3] as String,
            activationCodeDraft = values[4] as String,
            metadata = metadata,
            providerIcons = values[6] as Map<String, String>,
            operatorIcons = cloudData.operatorIcons,
            profileSizePredictions = cloudData.profileSizePredictions,
            downloadPreviewIcon = previewCloudData.operatorIcon,
            estimatedDownloadBytes = previewCloudData.estimatedBytes,
            downloadPreviewEnrichmentLoading = previewCloudData.loading,
            showCancelDownloadConfirmation = values[10] as Boolean,
            notificationHistory = values[11] as List<NotificationHistoryEntry>,
            pendingProfileDisableConfirmation = values[12] as String?,
            euiccNames = values[13] as Map<String, String>,
            profileEnrichmentReady = lpa.profiles.isEmpty() ||
                (!settings.loadOperatorIcons && !settings.estimateProfileSize) ||
                cloudData.input?.enrichmentKey == expectedCloudInput.enrichmentKey,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HyperLpaUiState())

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                repository.updateSettings(settings)
                if (!repository.state.value.initialized) {
                    repository.discoverReaders(autoConnect = settings.autoLoadProfiles)
                }
            }
        }
        viewModelScope.launch {
            repository.state
                .map { lpa ->
                    lpa.profiles.associate { profile -> profile.iccid to profile.providerName }
                }
                .distinctUntilChanged()
                .collect { providerNamesByIccid ->
                    dataMutationMutex.withLock {
                        metadataStore.recordProviderIdentities(providerNamesByIccid)
                    }
                }
        }
        viewModelScope.launch {
            combine(
                settingsStore.settings,
                repository.state,
                metadataStore.metadata,
                cloudRefreshToken,
            ) { settings, lpa, metadata, refreshToken ->
                CloudInputs(
                    loadOperatorIcons = settings.loadOperatorIcons,
                    estimateProfileSize = settings.estimateProfileSize,
                    profiles = lpa.profiles,
                    eid = lpa.euiccInfo?.eid,
                    metadata = metadata,
                    refreshToken = refreshToken,
                )
            }
                .distinctUntilChanged { previous, current ->
                    previous.enrichmentKey == current.enrichmentKey
                }
                .collectLatest { input ->
                    val sourceKey = input.enrichmentKey.copy(refreshToken = 0)
                    if (suppressedCloudProfileSource == sourceKey) {
                        cloudProfileData.value = CloudProfileData(input = input)
                        return@collectLatest
                    }
                    suppressedCloudProfileSource = null
                    cloudProfileData.value = supervisorScope {
                        val icons = async {
                            if (!input.loadOperatorIcons) return@async emptyMap()
                            val loaded = input.profiles.take(MaxUiOperatorIconEntries).map { profile ->
                                async {
                                    val icon = cloudEnrichmentSemaphore.withPermit {
                                        try {
                                            cloudService.loadOperatorIcon(profile)
                                        } catch (error: Throwable) {
                                            if (error is CancellationException) throw error
                                            null
                                        }
                                    }
                                    profile.iccid to icon
                                }
                            }.awaitAll()
                            boundedOperatorIconMap(loaded)
                        }

                        val sizes = async {
                            if (!input.estimateProfileSize) return@async emptyMap()
                            input.profiles
                                .filter { profile ->
                                    input.metadata[profile.iccid]?.let { metadata ->
                                        metadata.installedBytes != null &&
                                            (metadata.installedEid == null || metadata.installedEid == input.eid)
                                    } != true
                                }
                                .map { profile ->
                                    async {
                                        val enriched = profile.copy(
                                            smdpAddress = input.metadata[profile.iccid]?.smdpAddress
                                                ?: profile.smdpAddress,
                                        )
                                        val size = cloudEnrichmentSemaphore.withPermit {
                                            try {
                                                cloudService.predictProfileSize(
                                                    profile = enriched,
                                                    eid = input.eid,
                                                )
                                            } catch (error: Throwable) {
                                                if (error is CancellationException) throw error
                                                null
                                            }
                                        }
                                        profile.iccid to size
                                    }
                                }
                                .awaitAll()
                                .mapNotNull { (iccid, bytes) -> bytes?.let { iccid to it } }
                                .toMap()
                        }

                        CloudProfileData(
                            input = input,
                            operatorIcons = icons.await(),
                            profileSizePredictions = sizes.await(),
                        )
                    }
                }
        }
        viewModelScope.launch {
            combine(
                settingsStore.settings,
                repository.state.map { it.pendingProfileDownload },
                repository.state.map { it.euiccInfo?.eid },
                cloudRefreshToken,
            ) { settings, preview, eid, refreshToken ->
                DownloadPreviewCloudInput(settings, preview, eid, refreshToken)
            }
                .distinctUntilChanged()
                .collectLatest { input ->
                    if (suppressedDownloadPreviewSource == input.sourceKey) {
                        downloadPreviewCloudData.value = DownloadPreviewCloudData()
                        return@collectLatest
                    }
                    suppressedDownloadPreviewSource = null
                    val preview = input.preview
                    if (preview == null) {
                        if (repository.state.value.completedProfileDownload == null) {
                            downloadPreviewCloudData.value = DownloadPreviewCloudData()
                        } else {
                            // Keep the icon shown on the confirmation page available while the
                            // completed-download result is displayed. It is cleared when the
                            // result route is finished or when a new preview starts.
                            downloadPreviewCloudData.value = downloadPreviewCloudData.value.copy(
                                estimatedBytes = null,
                                loading = false,
                            )
                        }
                        return@collectLatest
                    }
                    val loadIcon = input.settings.loadOperatorIcons
                    val estimateSize = input.settings.estimateProfileSize
                    downloadPreviewCloudData.value = DownloadPreviewCloudData(
                        loading = loadIcon || estimateSize,
                    )
                    downloadPreviewCloudData.value = supervisorScope {
                        val icon = async {
                            if (!loadIcon) return@async null
                            try {
                                cloudService.loadOperatorIcon(preview.profile)
                            } catch (error: Throwable) {
                                if (error is CancellationException) throw error
                                null
                            }
                        }
                        val size = async {
                            if (!estimateSize) return@async null
                            try {
                                cloudService.predictProfileSize(
                                    profile = preview.profile,
                                    eid = input.eid,
                                )
                            } catch (error: Throwable) {
                                if (error is CancellationException) throw error
                                null
                            }
                        }
                        DownloadPreviewCloudData(
                            operatorIcon = icon.await(),
                            estimatedBytes = size.await(),
                        )
                    }
                }
        }
        viewModelScope.launch {
            repository.state.collect { lpa ->
                pendingProfileDisableConfirmation.value?.let { pendingIccid ->
                    val stillEnabled = lpa.profiles.any { profile ->
                        profile.iccid == pendingIccid && profile.state == ProfileState.ENABLED
                    }
                    if (!stillEnabled) pendingProfileDisableConfirmation.value = null
                }
                val top = backStack.lastOrNull()
                when {
                    lpa.pendingProfileDownload != null &&
                        (lpa.operation as? LpaOperation.Downloading)?.stage ==
                        app.hyperlpa.domain.model.DownloadStage.CONFIRMING &&
                        (top == AppRoute.DownloadProfile || top == AppRoute.Shell) -> {
                        backStack.add(AppRoute.ConfirmProfileDownload)
                    }
                    lpa.completedProfileDownload != null &&
                        lpa.operation is LpaOperation.Idle &&
                        top !is AppRoute.ProfileDownloadResult -> {
                        backStack.removeAll { route ->
                            route == AppRoute.DownloadProfile ||
                                route == AppRoute.ConfirmProfileDownload ||
                                route is AppRoute.ProfileDownloadResult
                        }
                        backStack.add(AppRoute.ProfileDownloadResult(lpa.completedProfileDownload))
                        showCancelDownloadConfirmation.value = false
                    }
                    lpa.pendingProfileDownload == null &&
                        lpa.completedProfileDownload == null &&
                        lpa.operation is LpaOperation.Idle &&
                        top == AppRoute.ConfirmProfileDownload -> {
                        backStack.removeAt(backStack.lastIndex)
                        showCancelDownloadConfirmation.value = false
                    }
                }
            }
        }
        viewModelScope.launch {
            var firstEmission = true
            provisioningCoordinator.batchState.collect { batch ->
                if (firstEmission && batch.running && backStack.lastOrNull() == AppRoute.Shell) {
                    backStack.add(AppRoute.BatchDownload)
                }
                firstEmission = false
            }
        }
    }

    fun selectTab(tab: AppTab) {
        selectedTab.value = tab
    }

    fun navigationSnapshot(): NavigationSnapshot = NavigationSnapshot(
        selectedTab = selectedTab.value.name,
        route = backStack.lastOrNull().toPersistedRoute(),
    )

    fun restoreNavigation(snapshot: NavigationSnapshot) {
        selectedTab.value = AppTab.entries.firstOrNull { it.name == snapshot.selectedTab }
            ?: AppTab.PROFILES
        val restoredRoute = snapshot.route.toAppRoute()
        backStack.clear()
        backStack.add(AppRoute.Shell)
        if (restoredRoute != null && restoredRoute != AppRoute.Shell) {
            backStack.add(restoredRoute)
        }
    }

    fun navigate(route: AppRoute) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun navigateBack() {
        when (backStack.lastOrNull()) {
            AppRoute.ConfirmProfileDownload -> {
                val operation = repository.state.value.operation as? LpaOperation.Downloading
                if (operation?.stage == app.hyperlpa.domain.model.DownloadStage.CONFIRMING) {
                    showCancelDownloadConfirmation.value = true
                }
            }
            is AppRoute.ProfileDownloadResult -> finishProfileDownload()
            else -> if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        }
    }

    fun updateSearchQuery(value: String) {
        searchQuery.value = value.take(MaxSearchQueryCharacters)
    }

    fun handleActivationCode(value: String): Boolean {
        val activationCode = extractActivationCode(value) ?: return false
        activationCodeDraft.value = activationCode
        navigate(AppRoute.DownloadProfile)
        return true
    }

    fun setActivationCodeDraft(value: String) {
        activationCodeDraft.value = value
    }

    fun refreshReaders() = launch {
        repository.discoverReaders(autoConnect = true, includeRemoteReaders = true)
    }
    fun onSimStateChanged() {
        simStateRefreshJob?.cancel()
        simStateRefreshJob = viewModelScope.launch {
            delay(1_200)
            if (repository.state.value.operation !is LpaOperation.Idle) return@launch
            repository.discoverReaders(autoConnect = true)
        }
    }
    fun connectReader(readerId: String) = launch {
        if (repository.connect(readerId) is OperationOutcome.Success) {
            settingsStore.setLastReaderId(readerId)
        }
    }
    fun disconnectReader() = launch { repository.disconnectSession() }
    fun refreshProfiles() = launch(repository::refresh)
    fun setProfileEnabled(iccid: String, enabled: Boolean) {
        if (repository.state.value.operation is LpaOperation.Switching) return
        val profiles = repository.state.value.profiles
        if (requiresLastEnabledProfileConfirmation(profiles, iccid, enabled)) {
            pendingProfileDisableConfirmation.value = iccid
            return
        }
        if (enabled && pendingProfileDisableConfirmation.value == iccid) {
            pendingProfileDisableConfirmation.value = null
        }
        launch { repository.setProfileEnabled(iccid, enabled) }
    }
    fun cancelLastEnabledProfileDisable() {
        pendingProfileDisableConfirmation.value = null
    }
    fun confirmLastEnabledProfileDisable() {
        val iccid = pendingProfileDisableConfirmation.value ?: return
        pendingProfileDisableConfirmation.value = null
        val stillEnabled = repository.state.value.profiles.any { profile ->
            profile.iccid == iccid && profile.state == ProfileState.ENABLED
        }
        if (stillEnabled) launch { repository.setProfileEnabled(iccid, false) }
    }
    fun deleteProfile(iccid: String) = launch { repository.deleteProfile(iccid) }
    fun renameProfile(iccid: String, nickname: String) = launch { repository.renameProfile(iccid, nickname) }
    fun downloadProfile(request: DownloadRequest) {
        provisioningCoordinator.startSingleDownload(request)
    }
    fun startBatchDownload(requests: List<DownloadRequest>) {
        provisioningCoordinator.startBatchDownload(requests)
    }
    fun resumeBatchDownload() {
        provisioningCoordinator.resumeInterruptedBatch()
    }
    fun retryFailedBatchDownload() {
        provisioningCoordinator.retryFailedBatch()
    }
    fun cancelBatchDownload() = provisioningCoordinator.cancelBatchDownload()
    fun clearBatchDownload() = provisioningCoordinator.clearBatchDownload()
    fun confirmProfileDownload() = provisioningCoordinator.confirmSingleDownload()
    fun cancelProfileDownload() = provisioningCoordinator.cancelSingleDownload()
    fun dismissCancelProfileDownload() {
        showCancelDownloadConfirmation.value = false
    }
    fun confirmCancelProfileDownload() {
        showCancelDownloadConfirmation.value = false
        if (backStack.lastOrNull() == AppRoute.ConfirmProfileDownload) {
            backStack.removeAt(backStack.lastIndex)
        }
        provisioningCoordinator.cancelSingleDownload()
    }
    fun finishProfileDownload() {
        showCancelDownloadConfirmation.value = false
        activationCodeDraft.value = ""
        selectedTab.value = AppTab.PROFILES
        backStack.clear()
        backStack.add(AppRoute.Shell)
        repository.clearProfileDownloadResult()
        downloadPreviewCloudData.value = DownloadPreviewCloudData()
    }
    fun processNotification(sequenceNumber: Long) = launch { repository.processNotification(sequenceNumber) }
    fun deleteNotification(sequenceNumber: Long) = launch { repository.deleteNotification(sequenceNumber) }
    fun resendNotification(entry: NotificationHistoryEntry) = launch {
        repository.resendNotification(entry)
    }
    fun deleteNotificationHistoryEntry(entry: NotificationHistoryEntry) = launch {
        notificationHistoryStore.delete(entry)
    }
    fun resetEuiccMemory() = launch(repository::resetEuiccMemory)
    fun setDefaultSmdpAddress(address: String) = launch {
        repository.setDefaultSmdpAddress(address)
    }
    fun discoverProfiles(smdsAddress: String?) = launch {
        repository.discoverProfiles(smdsAddress)
    }
    fun useDiscoveredSmdpAddress(address: String) {
        activationCodeDraft.value = address
        navigate(AppRoute.DownloadProfile)
    }
    fun clearFailure() = repository.clearFailure()

    fun retainNotificationPermissionContinuation(continuation: (Boolean) -> Unit) {
        notificationPermissionContinuation?.invoke(false)
        notificationPermissionContinuation = continuation
    }

    fun completeNotificationPermissionRequest(granted: Boolean) {
        val continuation = notificationPermissionContinuation
        notificationPermissionContinuation = null
        continuation?.invoke(granted)
    }

    @Synchronized
    fun beginRuntimePermissionRequest(): Boolean {
        if (runtimePermissionRequestInProgress) return false
        runtimePermissionRequestInProgress = true
        return true
    }

    @Synchronized
    fun completeRuntimePermissionRequest() {
        runtimePermissionRequestInProgress = false
    }

    fun setProfileTags(iccid: String, tags: Set<String>) = launch { metadataStore.setTags(iccid, tags) }
    fun setProfilePinned(iccid: String, pinned: Boolean) = launch {
        metadataStore.setPinned(iccid, pinned)
    }
    fun setEuiccName(eid: String, name: String?) = launch {
        metadataStore.setEuiccName(eid, name)
    }
    fun setProfileReminder(iccid: String, label: String, reminderAt: Instant?) = launch {
        withProfileReminderIsolation {
            withContext(NonCancellable) {
                if (reminderAt != null && !state.value.settings.scheduledReminders) {
                    settingsStore.setScheduledReminders(true)
                    metadataStore.syncReminders(currentReminderSchedules(), enabled = true)
                }
                metadataStore.setReminder(iccid, label, reminderAt, enabled = reminderAt != null)
            }
        }
    }
    fun setProfileIcon(
        iccid: String,
        uri: String?,
        applyToProvider: Boolean = false,
        providerName: String? = null,
        onComplete: (Boolean) -> Unit = {},
    ) = launch {
        var pendingImport: PendingProfileIconImport? = null
        var importCommitted = false
        val result = runCatching {
            val providerKey = providerIconKey(providerName)
            require(!applyToProvider || providerKey != null) { "The profile has no provider identity" }
            if (uri == null) {
                withContext(NonCancellable) {
                    if (applyToProvider) {
                        metadataStore.setProviderIconUri(requireNotNull(providerKey), null)
                    } else {
                        metadataStore.setIconUri(iccid, null, providerName)
                    }
                    runCatching { metadataStore.cleanupOrphanedIconFiles() }
                }
                return@runCatching
            }
            val filePrefix = if (applyToProvider) {
                "provider_${requireNotNull(providerKey).filter { it.isLetterOrDigit() }}"
            } else {
                "profile_${iccid.filter { it.isLetterOrDigit() }}"
            }
            val imported = importProfileIcon(uri, filePrefix)
            pendingImport = imported
            val storedUri = imported.liveFile.toUri().toString()
            if (applyToProvider) {
                commitProviderIconAndClearOverrides(
                    providerKey = requireNotNull(providerKey),
                    storedUri = storedUri,
                    onCommitted = { importCommitted = true },
                )
            } else {
                withContext(NonCancellable) {
                    metadataStore.setIconUri(iccid, storedUri, providerName)
                    importCommitted = true
                    runCatching { metadataStore.cleanupOrphanedIconFiles() }
                }
            }
        }
        result.exceptionOrNull()?.let { error ->
            if (!importCommitted) {
                withContext(NonCancellable + Dispatchers.IO) {
                    pendingImport?.let(profileIconStorage::discard)
                }
            }
            if (error is CancellationException) throw error
        }
        onComplete(result.isSuccess)
    }

    fun setProfileProviderIconHidden(
        iccid: String,
        hidden: Boolean,
        providerName: String? = null,
        onComplete: (Boolean) -> Unit = {},
    ) = launch {
        val result = runCatching {
            withContext(NonCancellable) {
                metadataStore.setProviderIconHidden(
                    iccid = iccid,
                    hidden = hidden,
                    providerName = providerName,
                )
                if (hidden) {
                    runCatching { metadataStore.cleanupOrphanedIconFiles() }
                }
            }
        }
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        onComplete(result.isSuccess)
    }

    fun applyProfileIconToProvider(
        iccid: String,
        providerName: String?,
        onComplete: (Boolean) -> Unit = {},
    ) = launch {
        var pendingImport: PendingProfileIconImport? = null
        var importCommitted = false
        val result = runCatching {
            val providerKey = providerIconKey(providerName)
                ?: error("The profile has no provider identity")
            val sourceUri = state.value.metadata[iccid]?.iconUri
                ?: state.value.providerIcons[providerKey]
                ?: error("No custom image is available")
            val imported = importProfileIcon(
                sourceUri = sourceUri,
                filePrefix = "provider_${providerKey.filter { it.isLetterOrDigit() }}",
            )
            pendingImport = imported
            val storedUri = imported.liveFile.toUri().toString()
            commitProviderIconAndClearOverrides(
                providerKey = providerKey,
                storedUri = storedUri,
                onCommitted = { importCommitted = true },
            )
        }
        result.exceptionOrNull()?.let { error ->
            if (!importCommitted) {
                withContext(NonCancellable + Dispatchers.IO) {
                    pendingImport?.let(profileIconStorage::discard)
                }
            }
            if (error is CancellationException) throw error
        }
        onComplete(result.isSuccess)
    }

    private suspend fun commitProviderIconAndClearOverrides(
        providerKey: String,
        storedUri: String,
        onCommitted: () -> Unit,
    ) = withContext(NonCancellable) {
        val matchingIccids = state.value.lpa.profiles
            .filter { providerIconKey(it.providerName) == providerKey }
            .map(ProfileInfo::iccid)
        metadataStore.setProviderIconAndClearProfileOverrides(
            providerName = providerKey,
            iconUri = storedUri,
            profileIccids = matchingIccids,
        )
            .also { onCommitted() }
            .also { runCatching { metadataStore.cleanupOrphanedIconFiles() } }
    }

    private suspend fun importProfileIcon(
        sourceUri: String,
        filePrefix: String,
    ): PendingProfileIconImport {
        var pending: PendingProfileIconImport? = null
        try {
            return withContext(Dispatchers.IO) {
                val created = profileIconStorage.createPendingImport(filePrefix)
                pending = created
                copyIconToPrivateStorage(sourceUri, created.stagingFile)
                profileIconStorage.promote(created)
                created
            }
        } catch (error: Throwable) {
            pending?.let { abandoned ->
                withContext(NonCancellable + Dispatchers.IO) {
                    profileIconStorage.discard(abandoned)
                }
            }
            throw error
        }
    }

    private fun copyIconToPrivateStorage(sourceUri: String, destination: File) {
        val input = getApplication<Application>().contentResolver
            .openInputStream(sourceUri.toUri())
            ?: error("The selected image could not be opened")
        try {
            input.use { source ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MaxStoredProfileIconBytes) { "The selected image is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            validateStoredIcon(destination)
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun validateStoredIcon(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        require(width in 1..MaxStoredProfileIconDimension)
        require(height in 1..MaxStoredProfileIconDimension)
        require(width.toLong() * height <= MaxStoredProfileIconPixels)

        var sampleSize = 1
        while (
            width / sampleSize > MaxDecodedProfileIconDimension ||
            height / sampleSize > MaxDecodedProfileIconDimension
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: error("The selected image could not be decoded")
        decoded.recycle()
    }

    fun clearCloudCaches() = launch {
        val snapshot = state.value
        val nextRefreshToken = cloudRefreshToken.value + 1
        val profileInput = CloudInputs(
            loadOperatorIcons = snapshot.settings.loadOperatorIcons,
            estimateProfileSize = snapshot.settings.estimateProfileSize,
            profiles = snapshot.lpa.profiles,
            eid = snapshot.lpa.euiccInfo?.eid,
            metadata = snapshot.metadata,
            refreshToken = nextRefreshToken,
        )
        suppressedCloudProfileSource = profileInput.enrichmentKey.copy(refreshToken = 0)
        suppressedDownloadPreviewSource = DownloadPreviewCloudInput(
            settings = snapshot.settings,
            preview = snapshot.lpa.pendingProfileDownload,
            eid = snapshot.lpa.euiccInfo?.eid,
            refreshToken = nextRefreshToken,
        ).sourceKey
        cloudRefreshToken.value = nextRefreshToken
        cloudProfileData.value = CloudProfileData(input = profileInput)
        downloadPreviewCloudData.value = DownloadPreviewCloudData()
        cloudService.clearAllCaches()
    }

    fun exportSupportReport(uri: Uri, onComplete: (Boolean) -> Unit) {
        val snapshot = state.value
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val report = supportReportBuilder.build(
                        settings = snapshot.settings,
                        repositoryState = snapshot.lpa,
                        notificationHistory = snapshot.notificationHistory,
                    )
                    getApplication<Application>().contentResolver
                        .openOutputStream(uri, "wt")
                        ?.bufferedWriter()
                        ?.use { writer -> writer.write(report) }
                        ?: error("Could not open the support report destination")
                }.isSuccess
            }
            onComplete(success)
        }
    }

    @Synchronized
    fun prepareBackup(passphrase: String): Boolean {
        if (passphrase.length !in MinBackupPasswordCharacters..MaxBackupPasswordCharacters) return false
        if (!_backupOperationInProgress.compareAndSet(expect = false, update = true)) return false
        pendingBackupPassword?.fill('\u0000')
        pendingBackupPassword = passphrase.toCharArray()
        return true
    }

    @Synchronized
    fun cancelPreparedBackup() {
        val password = pendingBackupPassword ?: return
        pendingBackupPassword = null
        password.fill('\u0000')
        _backupOperationInProgress.value = false
    }

    fun createPreparedBackup(uri: Uri, onComplete: (Boolean) -> Unit) {
        val password = synchronized(this) {
            pendingBackupPassword.also { pendingBackupPassword = null }
        }
        if (password == null) {
            _backupOperationInProgress.value = false
            onComplete(false)
            return
        }
        viewModelScope.launch {
            var success = false
            try {
                dataMutationMutex.withLock {
                    repository.withExclusiveOperation {
                        withContext(Dispatchers.IO) {
                            val backup = backupManager.createBackup(
                                passphrase = password,
                            )
                            getApplication<Application>().contentResolver
                                .openOutputStream(uri, "wt")
                                ?.bufferedWriter()
                                ?.use { writer -> writer.write(backup) }
                                ?: error("Could not open the backup destination")
                        }
                    }
                }
                success = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The UI reports a generic failure without exposing path or backup contents.
            } finally {
                password.fill('\u0000')
                _backupOperationInProgress.value = false
                onComplete(success)
            }
        }
    }

    fun restoreBackup(uri: Uri, passphrase: String, onComplete: (Boolean) -> Unit) {
        if (!_backupOperationInProgress.compareAndSet(expect = false, update = true)) {
            onComplete(false)
            return
        }
        val password = passphrase.toCharArray()
        viewModelScope.launch {
            var success = false
            try {
                val rawBackup = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.use { input -> input.readTextLimited(MaxBackupBytes) }
                        ?: error("Could not open the backup")
                }
                dataMutationMutex.withLock {
                    provisioningCoordinator.withProvisioningQuiesced {
                        // Once provisioning is idle and the durable restore begins, finish either
                        // its commit or rollback even if the Activity is recreated. The repository
                        // closes its live session before the transaction and rebuilds every
                        // endpoint from the final committed settings without reconnecting.
                        withContext(NonCancellable) {
                            repository.withReadersDisconnectedForStateReplacement(
                                readCommittedSettings = settingsStore::snapshot,
                            ) {
                                withContext(Dispatchers.IO) {
                                    backupManager.restoreBackup(
                                        rawBackup = rawBackup,
                                        passphrase = password,
                                    )
                                }
                            }
                        }
                    }
                }
                success = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Completion still runs so the screen cannot remain disabled after a failure.
            } finally {
                password.fill('\u0000')
                _backupOperationInProgress.value = false
                onComplete(success)
            }
        }
    }

    fun resetSettings(onComplete: (Boolean) -> Unit) {
        if (!_backupOperationInProgress.compareAndSet(expect = false, update = true)) {
            onComplete(false)
            return
        }
        viewModelScope.launch {
            var success = false
            try {
                dataMutationMutex.withLock {
                    provisioningCoordinator.withProvisioningQuiesced {
                        withContext(NonCancellable) {
                            repository.withReadersDisconnectedForStateReplacement(
                                readCommittedSettings = settingsStore::snapshot,
                            ) {
                                withProfileReminderIsolation {
                                    val settings = settingsStore.resetToDefaults()
                                    metadataStore.syncReminders(
                                        reminders = currentReminderSchedules(),
                                        enabled = settings.scheduledReminders,
                                    )
                                }
                            }
                        }
                    }
                }
                success = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Completion still runs so the screen cannot remain disabled after a failure.
            } finally {
                _backupOperationInProgress.value = false
                onComplete(success)
            }
        }
    }

    fun setThemeMode(value: ThemeMode) = launch { settingsStore.setThemeMode(value) }
    fun setUseMonet(value: Boolean) = launch { settingsStore.setUseMonet(value) }
    fun setPureBlack(value: Boolean) = launch { settingsStore.setPureBlack(value) }
    fun setAccent(value: ThemeAccent) = launch { settingsStore.setAccent(value) }
    fun setPalette(value: ThemePalette) = launch { settingsStore.setPalette(value) }
    fun setBlurEnabled(value: Boolean) = launch { settingsStore.setBlurEnabled(value) }
    fun setPredictiveBack(value: Boolean) = launch { settingsStore.setPredictiveBack(value) }
    fun setDensityScale(value: Float) = launch { settingsStore.setDensityScale(value) }
    fun setNavigationStyle(value: NavigationStyle) = launch { settingsStore.setNavigationStyle(value) }
    fun setFloatingBottomBarStyle(value: FloatingBottomBarStyle) =
        launch { settingsStore.setFloatingBottomBarStyle(value) }
    fun setNavigationLabels(value: NavigationLabels) = launch { settingsStore.setNavigationLabels(value) }
    fun setProfileLayout(value: ProfileLayout) = launch { settingsStore.setProfileLayout(value) }
    fun setProfileSort(value: ProfileSort) = launch { settingsStore.setProfileSort(value) }
    fun setSortAscending(value: Boolean) = launch { settingsStore.setSortAscending(value) }
    fun setShowProfileSearch(value: Boolean) = launch { settingsStore.setShowProfileSearch(value) }
    fun setPhoneFormatStrategy(value: PhoneFormatStrategy) =
        launch { settingsStore.setPhoneFormatStrategy(value) }
    fun setShowProfileNameOnHome(value: Boolean) = launch { settingsStore.setShowProfileNameOnHome(value) }
    fun setShowProfileProviderOnHome(value: Boolean) = launch { settingsStore.setShowProfileProviderOnHome(value) }
    fun setShowProfileIccidOnHome(value: Boolean) = launch { settingsStore.setShowProfileIccidOnHome(value) }
    fun setShowProfileIconOnHome(value: Boolean) = launch { settingsStore.setShowProfileIconOnHome(value) }
    fun setShowProfileTagsOnHome(value: Boolean) = launch { settingsStore.setShowProfileTagsOnHome(value) }
    fun setShowProfileRemindersOnHome(value: Boolean) =
        launch { settingsStore.setShowProfileRemindersOnHome(value) }
    fun setShowProfileSizeOnHome(value: Boolean) = launch { settingsStore.setShowProfileSizeOnHome(value) }
    fun setShowProfileSwitchOnHome(value: Boolean) = launch { settingsStore.setShowProfileSwitchOnHome(value) }
    fun setShowReaderSelectorOnHome(value: Boolean) = launch { settingsStore.setShowReaderSelectorOnHome(value) }
    fun setShowEidOnHome(value: Boolean) = launch { settingsStore.setShowEidOnHome(value) }
    fun setAutoLoadProfiles(value: Boolean) = launch { settingsStore.setAutoLoadProfiles(value) }
    fun setEnableNBridge(value: Boolean) = launch { settingsStore.setEnableNBridge(value) }
    fun setEnableOmapi(value: Boolean) = launch { settingsStore.setEnableOmapi(value) }
    fun setEnableUsbCcid(value: Boolean) = launch { settingsStore.setEnableUsbCcid(value) }
    fun setEnableTelephony(value: Boolean) = launch {
        settingsStore.setEnableTelephony(BuildConfig.HAS_PRIVILEGED_TELEPHONY && value)
    }
    fun setEnableBle(value: Boolean) = launch { settingsStore.setEnableBle(value) }
    fun setEnableRemote(value: Boolean) = launch { settingsStore.setEnableRemote(value) }
    fun setNotificationInitialLoad(value: Boolean) = launch { settingsStore.setNotificationInitialLoad(value) }
    fun setNotificationAfterSwitch(value: Boolean) = launch { settingsStore.setNotificationAfterSwitch(value) }
    fun setNotificationAfterDelete(value: Boolean) = launch { settingsStore.setNotificationAfterDelete(value) }
    fun setNotificationBeforeDownload(value: Boolean) = launch { settingsStore.setNotificationBeforeDownload(value) }
    fun setNotificationAfterDownload(value: Boolean) = launch { settingsStore.setNotificationAfterDownload(value) }
    fun setNotificationAutoSend(value: Boolean) = launch { settingsStore.setNotificationAutoSend(value) }
    fun setNotificationAutoRemove(value: Boolean) = launch { settingsStore.setNotificationAutoRemove(value) }
    fun setScheduledReminders(value: Boolean) = launch {
        withProfileReminderIsolation {
            withContext(NonCancellable) {
                settingsStore.setScheduledReminders(value)
                metadataStore.syncReminders(
                    reminders = currentReminderSchedules(),
                    enabled = value,
                )
            }
        }
    }
    fun setEidRedaction(value: RedactionMode) = launch { settingsStore.setEidRedaction(value) }
    fun setIccidRedaction(value: RedactionMode) = launch { settingsStore.setIccidRedaction(value) }
    fun setLoadOperatorIcons(value: Boolean) = launch { settingsStore.setLoadOperatorIcons(value) }
    fun setEstimateProfileSize(value: Boolean) = launch { settingsStore.setEstimateProfileSize(value) }
    fun setHideProfileDeletion(value: Boolean) = launch { settingsStore.setHideProfileDeletion(value) }
    fun setHideEuiccMemoryReset(value: Boolean) = launch { settingsStore.setHideEuiccMemoryReset(value) }
    fun setDeveloperMode(value: Boolean) = launch { settingsStore.setDeveloperMode(value) }
    fun setApduLogging(value: Boolean) = launch { settingsStore.setApduLogging(value) }
    fun setEs10xMss(value: Int) = launch { settingsStore.setEs10xMss(value) }
    fun setImei(value: String) = launch { settingsStore.setImei(value) }
    fun setIsdrAids(value: List<String>) = launch { settingsStore.setIsdrAids(value) }
    fun setRemoteReaderUrls(value: List<String>, onComplete: (Boolean) -> Unit = {}) {
        launch {
            val success = try {
                val updated = settingsStore.setRemoteReaderUrls(value)
                repository.updateSettings(updated)
                repository.reloadRemoteReadersAfterConfigurationChange()
                true
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                false
            }
            onComplete(success)
        }
    }
    fun setRemoteReaderToken(
        endpointUrl: String,
        bearerToken: String?,
        onComplete: (Boolean) -> Unit = {},
    ) {
        launch {
            val success = try {
                val updated = settingsStore.setRemoteReaderToken(endpointUrl, bearerToken)
                repository.updateSettings(updated)
                repository.reloadRemoteReadersAfterConfigurationChange()
                true
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                false
            }
            onComplete(success)
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            dataMutationMutex.withLock { block() }
        }
    }

    private fun currentReminderSchedules(): Map<String, Pair<String, Instant?>> {
        val fallbackProfileName = getApplication<Application>().getString(R.string.profile_default_name)
        val profileLabels = state.value.lpa.profiles.associate { profile ->
            val label = profile.nickname.ifBlank { profile.name.ifBlank { fallbackProfileName } }
            profile.iccid to label
        }
        return state.value.metadata.mapValues { (iccid, metadata) ->
            val label = profileLabels[iccid]
                ?: metadata.reminderLabel
                ?: fallbackProfileName
            label to metadata.reminderAt
        }
    }

    override fun onCleared() {
        synchronized(this) {
            pendingBackupPassword?.fill('\u0000')
            pendingBackupPassword = null
            notificationPermissionContinuation = null
        }
    }

    class Factory(
        private val application: Application,
        private val settingsStore: AppSettingsStore,
        private val metadataStore: ProfileMetadataStore,
        private val repository: LpaRepository,
        private val cloudService: NekokoCloudService,
        private val notificationHistoryStore: NotificationHistoryStore,
        private val supportReportBuilder: SupportReportBuilder,
        private val provisioningCoordinator: ProvisioningCoordinator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HyperLpaViewModel(
            application = application,
            settingsStore = settingsStore,
            metadataStore = metadataStore,
            repository = repository,
            cloudService = cloudService,
            notificationHistoryStore = notificationHistoryStore,
            supportReportBuilder = supportReportBuilder,
            provisioningCoordinator = provisioningCoordinator,
        ) as T
    }
}

internal fun requiresLastEnabledProfileConfirmation(
    profiles: List<ProfileInfo>,
    targetIccid: String,
    requestedEnabled: Boolean,
): Boolean = !requestedEnabled &&
    profiles.count { profile -> profile.state == ProfileState.ENABLED } == 1 &&
    profiles.any { profile ->
        profile.iccid == targetIccid && profile.state == ProfileState.ENABLED
    }

internal fun extractActivationCode(value: String): String? {
    if (value.length > MaxActivationInputCharacters) return null
    val trimmed = value.trim()
    if (trimmed.startsWith("LPA:", ignoreCase = true)) return trimmed
    val uri = runCatching { trimmed.toUri() }.getOrNull() ?: return null
    if (!uri.isHierarchical) return null
    return runCatching {
        listOf("carddata", "activationCode", "activation_code", "code")
            .firstNotNullOfOrNull { key -> uri.getQueryParameter(key) }
    }.getOrNull()
        ?.trim()
        ?.takeIf { it.startsWith("LPA:", ignoreCase = true) }
}

data class NavigationSnapshot(
    val selectedTab: String,
    val route: String?,
)

private const val MaxStoredProfileIconBytes = 4L * 1024L * 1024L
private const val MaxStoredProfileIconDimension = 16_384
private const val MaxStoredProfileIconPixels = 64_000_000L
private const val MaxDecodedProfileIconDimension = 2_048
private const val MinBackupPasswordCharacters = 10
private const val MaxBackupPasswordCharacters = 128
private const val MaxActivationInputCharacters = 4_096
private const val CloudEnrichmentConcurrency = 4

private fun NavKey?.toPersistedRoute(): String? = when (val route = this as? AppRoute) {
    null,
    AppRoute.Shell,
    is AppRoute.ProfileDownloadResult,
    AppRoute.ConfirmProfileDownload,
    -> null
    is AppRoute.ProfileDetails -> "profile:${route.iccid}"
    AppRoute.DownloadProfile -> "download"
    AppRoute.BatchDownload -> "batch"
    AppRoute.EuiccDetails -> "euicc"
    AppRoute.ReaderSettings -> "readers"
    AppRoute.NotificationSettings -> "notifications"
    AppRoute.NotificationHistory -> "notification-history"
    AppRoute.AppearanceSettings -> "appearance"
    AppRoute.ProfileDisplaySettings -> "profile-display"
    AppRoute.PrivacySettings -> "privacy"
    AppRoute.AdvancedSettings -> "advanced"
    AppRoute.BackupRestoreSettings -> "backup"
    AppRoute.AidManager -> "aids"
    AppRoute.TagsAndReminders -> "tags-reminders"
    AppRoute.TagManager -> "tags"
    AppRoute.ScheduledReminders -> "reminders"
    AppRoute.Statistics -> "statistics"
    AppRoute.Logs -> "logs"
    AppRoute.About -> "about"
}

private fun String?.toAppRoute(): AppRoute? = when {
    this == null -> null
    startsWith("profile:") -> substringAfter("profile:")
        .takeIf(String::isNotBlank)
        ?.let(AppRoute::ProfileDetails)
    else -> when (this) {
        "download" -> AppRoute.DownloadProfile
        "batch" -> AppRoute.BatchDownload
        "euicc" -> AppRoute.EuiccDetails
        "readers" -> AppRoute.ReaderSettings
        "notifications" -> AppRoute.NotificationSettings
        "notification-history" -> AppRoute.NotificationHistory
        "appearance" -> AppRoute.AppearanceSettings
        "profile-display" -> AppRoute.ProfileDisplaySettings
        "privacy" -> AppRoute.PrivacySettings
        "advanced" -> AppRoute.AdvancedSettings
        "backup" -> AppRoute.BackupRestoreSettings
        "aids" -> AppRoute.AidManager
        "tags-reminders" -> AppRoute.TagsAndReminders
        "tags" -> AppRoute.TagManager
        "reminders" -> AppRoute.ScheduledReminders
        "statistics" -> AppRoute.Statistics
        "logs" -> AppRoute.Logs
        "about" -> AppRoute.About
        else -> null
    }
}

private data class CloudInputs(
    val loadOperatorIcons: Boolean,
    val estimateProfileSize: Boolean,
    val profiles: List<ProfileInfo>,
    val eid: String?,
    val metadata: Map<String, ProfileMetadata>,
    val refreshToken: Int,
) {
    // Enabled state and reader-provided ordering do not affect either cloud lookup.
    val enrichmentKey: CloudEnrichmentKey
        get() = CloudEnrichmentKey(
            loadOperatorIcons = loadOperatorIcons,
            estimateProfileSize = estimateProfileSize,
            profiles = profiles
                .map { profile ->
                    val profileMetadata = metadata[profile.iccid]
                    CloudProfileEnrichmentKey(
                        iccid = profile.iccid,
                        name = profile.name,
                        providerName = profile.providerName,
                        mcc = profile.mcc,
                        mnc = profile.mnc,
                        gid1 = profile.gid1,
                        gid2 = profile.gid2,
                        smdpAddress = profileMetadata?.smdpAddress ?: profile.smdpAddress,
                        needsSizePrediction = profileMetadata?.let { stored ->
                            stored.installedBytes != null &&
                                (stored.installedEid == null || stored.installedEid == eid)
                        } != true,
                    )
                }
                .sortedBy(CloudProfileEnrichmentKey::iccid),
            eid = eid,
            refreshToken = refreshToken,
        )
}

private data class CloudEnrichmentKey(
    val loadOperatorIcons: Boolean,
    val estimateProfileSize: Boolean,
    val profiles: List<CloudProfileEnrichmentKey>,
    val eid: String?,
    val refreshToken: Int,
)

private data class CloudProfileEnrichmentKey(
    val iccid: String,
    val name: String,
    val providerName: String,
    val mcc: String?,
    val mnc: String?,
    val gid1: String?,
    val gid2: String?,
    val smdpAddress: String?,
    val needsSizePrediction: Boolean,
)

private data class CloudProfileData(
    val input: CloudInputs? = null,
    val operatorIcons: Map<String, ByteArray> = emptyMap(),
    val profileSizePredictions: Map<String, Long> = emptyMap(),
)

private data class DownloadPreviewCloudInput(
    val settings: AppSettings,
    val preview: app.hyperlpa.domain.model.ProfileDownloadPreview?,
    val eid: String?,
    val refreshToken: Int,
) {
    val sourceKey: DownloadPreviewCloudSourceKey
        get() = DownloadPreviewCloudSourceKey(
            loadOperatorIcons = settings.loadOperatorIcons,
            estimateProfileSize = settings.estimateProfileSize,
            profile = preview?.profile,
            eid = eid,
        )
}

private data class DownloadPreviewCloudSourceKey(
    val loadOperatorIcons: Boolean,
    val estimateProfileSize: Boolean,
    val profile: ProfileInfo?,
    val eid: String?,
)

private data class DownloadPreviewCloudData(
    val operatorIcon: ByteArray? = null,
    val estimatedBytes: Long? = null,
    val loading: Boolean = false,
)

internal fun boundedOperatorIconMap(
    entries: List<Pair<String, ByteArray?>>,
    maxBytes: Long = MaxUiOperatorIconBytes,
    maxEntries: Int = MaxUiOperatorIconEntries,
): Map<String, ByteArray> {
    if (maxBytes <= 0L || maxEntries <= 0) return emptyMap()
    var retainedBytes = 0L
    return buildMap {
        for ((iccid, bytes) in entries) {
            if (size >= maxEntries || bytes == null || containsKey(iccid)) continue
            if (bytes.size > maxBytes - retainedBytes) continue
            put(iccid, bytes)
            retainedBytes += bytes.size
        }
    }
}

private fun InputStream.readTextLimited(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "The selected backup is too large" }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

private const val MaxBackupBytes = 48 * 1024 * 1024
private const val MaxUiOperatorIconBytes = 8L * 1024 * 1024
private const val MaxUiOperatorIconEntries = 32
private const val MaxSearchQueryCharacters = 256
