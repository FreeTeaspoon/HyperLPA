package app.hyperlpa.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hyperlpa.data.LpaRepository
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.cloud.NekokoCloudService
import app.hyperlpa.data.metadata.ProfileMetadata
import app.hyperlpa.data.metadata.ProfileMetadataStore
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
import app.hyperlpa.domain.model.DownloadRequest
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.ui.navigation.AppRoute
import app.hyperlpa.ui.navigation.AppTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
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
    val operatorIcons: Map<String, ByteArray> = emptyMap(),
    val profileSizePredictions: Map<String, Long> = emptyMap(),
    val downloadPreviewIcon: ByteArray? = null,
    val estimatedDownloadBytes: Long? = null,
    val downloadPreviewEnrichmentLoading: Boolean = false,
    val showCancelDownloadConfirmation: Boolean = false,
    val profileEnrichmentReady: Boolean = false,
) {
    val profiles: List<ProfileInfo> by lazy {
        lpa.profiles
            .map { profile ->
                val extra = metadata[profile.iccid]
                val measuredBytes = extra
                    ?.installedBytes
                    ?.takeIf { extra.installedEid == null || extra.installedEid == lpa.euiccInfo?.eid }
                val providerKey = providerIconKey(profile.providerName)
                profile.copy(
                    tags = extra?.tags.orEmpty(),
                    reminderAt = extra?.reminderAt,
                    customIconUri = extra?.iconUri
                        ?: providerKey?.let(providerIcons::get),
                    smdpAddress = extra?.smdpAddress ?: profile.smdpAddress,
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
                profiles.sortedWith(if (settings.sortAscending) comparator else comparator.reversed())
            }
    }
}

class HyperLpaViewModel(
    application: Application,
    private val settingsStore: AppSettingsStore,
    private val metadataStore: ProfileMetadataStore,
    private val repository: LpaRepository,
    private val cloudService: NekokoCloudService,
) : AndroidViewModel(application) {
    private val backStack = mutableStateListOf<AppRoute>(AppRoute.Shell)
    val navigationBackStack: List<AppRoute> = backStack
    private val selectedTab = MutableStateFlow(AppTab.PROFILES)
    private val searchQuery = MutableStateFlow("")
    private val activationCodeDraft = MutableStateFlow("")
    private val cloudProfileData = MutableStateFlow(CloudProfileData())
    private val cloudRefreshToken = MutableStateFlow(0)
    private val downloadPreviewCloudData = MutableStateFlow(DownloadPreviewCloudData())
    private val showCancelDownloadConfirmation = MutableStateFlow(false)
    private var simStateRefreshJob: Job? = null

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
                    cloudProfileData.value = supervisorScope {
                        val icons = async {
                            if (!input.loadOperatorIcons) return@async emptyMap()
                            input.profiles.map { profile ->
                                async {
                                    val icon = try {
                                        cloudService.loadOperatorIcon(profile)
                                    } catch (error: Throwable) {
                                        if (error is CancellationException) throw error
                                        null
                                    }
                                    profile.iccid to icon
                                }
                            }.awaitAll().mapNotNull { (iccid, bytes) ->
                                bytes?.let { iccid to it }
                            }.toMap()
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
                                        val size = try {
                                            cloudService.predictProfileSize(
                                                profile = enriched,
                                                eid = input.eid,
                                            )
                                        } catch (error: Throwable) {
                                            if (error is CancellationException) throw error
                                            null
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
            ) { settings, preview -> DownloadPreviewCloudInput(settings, preview) }
                .distinctUntilChanged()
                .collectLatest { input ->
                    val preview = input.preview
                    if (preview == null) {
                        downloadPreviewCloudData.value = DownloadPreviewCloudData()
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
                                    eid = repository.state.value.euiccInfo?.eid,
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
                val top = backStack.lastOrNull()
                when {
                    lpa.pendingProfileDownload != null &&
                        (lpa.operation as? LpaOperation.Downloading)?.stage ==
                        app.hyperlpa.domain.model.DownloadStage.CONFIRMING &&
                        top == AppRoute.DownloadProfile -> {
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
                        backStack.removeLast()
                        showCancelDownloadConfirmation.value = false
                    }
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        selectedTab.value = tab
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
            else -> if (backStack.size > 1) backStack.removeLast()
        }
    }

    fun updateSearchQuery(value: String) {
        searchQuery.value = value
    }

    fun handleActivationCode(value: String) {
        if (!value.startsWith("LPA:", ignoreCase = true)) return
        activationCodeDraft.value = value
        navigate(AppRoute.DownloadProfile)
    }

    fun setActivationCodeDraft(value: String) {
        activationCodeDraft.value = value
    }

    fun refreshReaders() = launch { repository.discoverReaders(autoConnect = true) }
    fun onSimStateChanged() {
        simStateRefreshJob?.cancel()
        simStateRefreshJob = viewModelScope.launch {
            delay(1_200)
            if (repository.state.value.operation !is LpaOperation.Idle) return@launch
            repository.discoverReaders(autoConnect = true)
        }
    }
    fun connectReader(readerId: String) = launch {
        settingsStore.setLastReaderId(readerId)
        repository.connect(readerId)
    }
    fun refreshProfiles() = launch(repository::refresh)
    fun setProfileEnabled(iccid: String, enabled: Boolean) = launch { repository.setProfileEnabled(iccid, enabled) }
    fun deleteProfile(iccid: String) = launch { repository.deleteProfile(iccid) }
    fun renameProfile(iccid: String, nickname: String) = launch { repository.renameProfile(iccid, nickname) }
    fun downloadProfile(request: DownloadRequest) = launch { repository.downloadProfile(request) }
    fun downloadProfileWithoutConfirmation(request: DownloadRequest) = launch {
        repository.downloadProfile(request, confirmBeforeInstall = false)
    }
    fun confirmProfileDownload() = repository.confirmProfileDownload()
    fun cancelProfileDownload() = repository.cancelProfileDownload()
    fun dismissCancelProfileDownload() {
        showCancelDownloadConfirmation.value = false
    }
    fun confirmCancelProfileDownload() {
        showCancelDownloadConfirmation.value = false
        if (backStack.lastOrNull() == AppRoute.ConfirmProfileDownload) {
            backStack.removeLast()
        }
        repository.cancelProfileDownload()
    }
    fun finishProfileDownload() {
        showCancelDownloadConfirmation.value = false
        selectedTab.value = AppTab.PROFILES
        backStack.clear()
        backStack.add(AppRoute.Shell)
        repository.clearProfileDownloadResult()
    }
    fun processNotification(sequenceNumber: Long) = launch { repository.processNotification(sequenceNumber) }
    fun deleteNotification(sequenceNumber: Long) = launch { repository.deleteNotification(sequenceNumber) }
    fun resetEuiccMemory() = launch(repository::resetEuiccMemory)
    fun clearFailure() = repository.clearFailure()

    fun setProfileTags(iccid: String, tags: Set<String>) = launch { metadataStore.setTags(iccid, tags) }
    fun setProfileReminder(iccid: String, label: String, reminderAt: Instant?) = launch {
        if (reminderAt != null && !state.value.settings.scheduledReminders) {
            settingsStore.setScheduledReminders(true)
            metadataStore.syncReminders(currentReminderSchedules(), enabled = true)
        }
        metadataStore.setReminder(iccid, label, reminderAt, enabled = reminderAt != null)
    }
    fun setProfileIcon(
        iccid: String,
        uri: String?,
        applyToProvider: Boolean = false,
        providerName: String? = null,
    ) = launch {
        val iconsDir = File(getApplication<Application>().filesDir, "profile-icons").apply { mkdirs() }
        val providerKey = providerIconKey(providerName)
        val previousUri = if (applyToProvider && providerKey != null) {
            state.value.providerIcons[providerKey]
        } else {
            state.value.metadata[iccid]?.iconUri
        }
        if (uri == null) {
            withContext(Dispatchers.IO) { deleteStoredIconFile(previousUri, iconsDir) }
            if (applyToProvider && providerKey != null) {
                metadataStore.setProviderIconUri(providerKey, null)
            } else {
                metadataStore.setIconUri(iccid, null)
            }
            return@launch
        }
        val filePrefix = if (applyToProvider && providerKey != null) {
            "provider_${providerKey.filter { it.isLetterOrDigit() }}"
        } else {
            "profile_${iccid.filter { it.isLetterOrDigit() }}"
        }
        val storedUri = withContext(Dispatchers.IO) {
            copyIconToPrivateStorage(
                sourceUri = uri,
                destination = File(iconsDir, "${filePrefix}_${System.nanoTime()}.img"),
            )?.also {
                deleteStoredIconFile(previousUri, iconsDir)
            }
        } ?: return@launch
        if (applyToProvider && providerKey != null) {
            clearProfileIconOverridesForProvider(providerKey, iconsDir)
            metadataStore.setProviderIconUri(providerKey, storedUri)
        } else {
            metadataStore.setIconUri(iccid, storedUri)
        }
    }

    fun applyProfileIconToProvider(iccid: String, providerName: String?) = launch {
        val providerKey = providerIconKey(providerName) ?: return@launch
        val sourceUri = state.value.metadata[iccid]?.iconUri
            ?: state.value.providerIcons[providerKey]
            ?: return@launch
        val iconsDir = File(getApplication<Application>().filesDir, "profile-icons").apply { mkdirs() }
        val previousUri = state.value.providerIcons[providerKey]
        val storedUri = withContext(Dispatchers.IO) {
            copyIconToPrivateStorage(
                sourceUri = sourceUri,
                destination = File(
                    iconsDir,
                    "provider_${providerKey.filter { it.isLetterOrDigit() }}_${System.nanoTime()}.img",
                ),
            )
        } ?: return@launch
        clearProfileIconOverridesForProvider(
            providerKey = providerKey,
            iconsDir = iconsDir,
            keepUris = setOfNotNull(storedUri),
        )
        if (previousUri != null && previousUri != storedUri) {
            withContext(Dispatchers.IO) { deleteStoredIconFile(previousUri, iconsDir) }
        }
        metadataStore.setProviderIconUri(providerKey, storedUri)
    }

    private suspend fun clearProfileIconOverridesForProvider(
        providerKey: String,
        iconsDir: File,
        keepUris: Set<String> = emptySet(),
    ) {
        val matchingIccids = state.value.lpa.profiles
            .filter { providerIconKey(it.providerName) == providerKey }
            .map(ProfileInfo::iccid)
        val removedUris = metadataStore.clearProfileIconUris(matchingIccids)
        withContext(Dispatchers.IO) {
            removedUris
                .filterNot(keepUris::contains)
                .forEach { deleteStoredIconFile(it, iconsDir) }
        }
    }

    private fun copyIconToPrivateStorage(sourceUri: String, destination: File): String? = runCatching {
        getApplication<Application>().contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return@runCatching null
        destination.toUri().toString()
    }.getOrNull()

    private fun deleteStoredIconFile(uri: String?, iconsDir: File) {
        if (uri.isNullOrBlank()) return
        runCatching {
            val file = Uri.parse(uri).path?.let(::File) ?: return
            if (file.exists() && file.canonicalFile.startsWith(iconsDir.canonicalFile)) {
                file.delete()
            }
        }
    }
    fun clearOperatorIconCache() = launch {
        cloudService.clearOperatorIconCache()
        cloudProfileData.value = CloudProfileData()
        cloudRefreshToken.value += 1
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
    fun setEnableTelephony(value: Boolean) = launch { settingsStore.setEnableTelephony(value) }
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
        settingsStore.setScheduledReminders(value)
        metadataStore.syncReminders(
            reminders = currentReminderSchedules(),
            enabled = value,
        )
    }
    fun setEidRedaction(value: RedactionMode) = launch { settingsStore.setEidRedaction(value) }
    fun setIccidRedaction(value: RedactionMode) = launch { settingsStore.setIccidRedaction(value) }
    fun setLoadOperatorIcons(value: Boolean) = launch { settingsStore.setLoadOperatorIcons(value) }
    fun setEstimateProfileSize(value: Boolean) = launch { settingsStore.setEstimateProfileSize(value) }
    fun setHideEuiccMemoryReset(value: Boolean) = launch { settingsStore.setHideEuiccMemoryReset(value) }
    fun setDeveloperMode(value: Boolean) = launch { settingsStore.setDeveloperMode(value) }
    fun setApduLogging(value: Boolean) = launch { settingsStore.setApduLogging(value) }
    fun setEs10xMss(value: Int) = launch { settingsStore.setEs10xMss(value) }
    fun setImei(value: String) = launch { settingsStore.setImei(value) }
    fun setIsdrAids(value: List<String>) = launch { settingsStore.setIsdrAids(value) }
    fun setRemoteReaderUrls(value: List<String>) = launch { settingsStore.setRemoteReaderUrls(value) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun currentReminderSchedules(): Map<String, Pair<String, Instant?>> =
        state.value.profiles.associate { profile ->
            val label = profile.nickname.ifBlank { profile.name.ifBlank { "eSIM profile" } }
            profile.iccid to (label to profile.reminderAt)
        }

    class Factory(
        private val application: Application,
        private val settingsStore: AppSettingsStore,
        private val metadataStore: ProfileMetadataStore,
        private val repository: LpaRepository,
        private val cloudService: NekokoCloudService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HyperLpaViewModel(
            application = application,
            settingsStore = settingsStore,
            metadataStore = metadataStore,
            repository = repository,
            cloudService = cloudService,
        ) as T
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
)

private data class DownloadPreviewCloudData(
    val operatorIcon: ByteArray? = null,
    val estimatedBytes: Long? = null,
    val loading: Boolean = false,
)
