package app.hyperlpa.ui

import android.app.Application
import android.net.Uri
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
    val settings: AppSettings = AppSettings(),
    val lpa: LpaRepositoryState = LpaRepositoryState(),
    val backStack: List<AppRoute> = listOf(AppRoute.Shell),
    val selectedTab: AppTab = AppTab.PROFILES,
    val searchQuery: String = "",
    val activationCodeDraft: String = "",
    val metadata: Map<String, ProfileMetadata> = emptyMap(),
    val providerIcons: Map<String, String> = emptyMap(),
    val operatorIcons: Map<String, ByteArray> = emptyMap(),
    val profileSizePredictions: Map<String, Long> = emptyMap(),
) {
    val profiles: List<ProfileInfo>
        get() = lpa.profiles
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

class HyperLpaViewModel(
    application: Application,
    private val settingsStore: AppSettingsStore,
    private val metadataStore: ProfileMetadataStore,
    private val repository: LpaRepository,
    private val cloudService: NekokoCloudService,
) : AndroidViewModel(application) {
    private val backStack = MutableStateFlow<List<AppRoute>>(listOf(AppRoute.Shell))
    private val selectedTab = MutableStateFlow(AppTab.PROFILES)
    private val searchQuery = MutableStateFlow("")
    private val activationCodeDraft = MutableStateFlow("")
    private val operatorIcons = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    private val profileSizePredictions = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val cloudRefreshToken = MutableStateFlow(0)
    private var simStateRefreshJob: Job? = null

    val state = combine(
        settingsStore.settings,
        repository.state,
        backStack,
        selectedTab,
        searchQuery,
        activationCodeDraft,
        metadataStore.metadata,
        metadataStore.providerIcons,
        operatorIcons,
        profileSizePredictions,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        HyperLpaUiState(
            settings = values[0] as AppSettings,
            lpa = values[1] as LpaRepositoryState,
            backStack = values[2] as List<AppRoute>,
            selectedTab = values[3] as AppTab,
            searchQuery = values[4] as String,
            activationCodeDraft = values[5] as String,
            metadata = values[6] as Map<String, ProfileMetadata>,
            providerIcons = values[7] as Map<String, String>,
            operatorIcons = values[8] as Map<String, ByteArray>,
            profileSizePredictions = values[9] as Map<String, Long>,
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
                .distinctUntilChanged()
                .collectLatest { input ->
                    if (!input.loadOperatorIcons) operatorIcons.value = emptyMap()
                    if (!input.estimateProfileSize) profileSizePredictions.value = emptyMap()
                    operatorIcons.value = if (input.loadOperatorIcons) {
                        supervisorScope {
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
                            }.awaitAll()
                        }.mapNotNull { (iccid, bytes) -> bytes?.let { iccid to it } }.toMap()
                    } else {
                        emptyMap()
                    }

                    profileSizePredictions.value = if (input.estimateProfileSize) {
                        supervisorScope {
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
                        }.mapNotNull { (iccid, bytes) -> bytes?.let { iccid to it } }.toMap()
                    } else {
                        emptyMap()
                    }
                }
        }
    }

    fun selectTab(tab: AppTab) {
        selectedTab.value = tab
    }

    fun navigate(route: AppRoute) {
        if (backStack.value.lastOrNull() != route) backStack.value = backStack.value + route
    }

    fun navigateBack() {
        if (backStack.value.size > 1) backStack.value = backStack.value.dropLast(1)
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
    fun processNotification(sequenceNumber: Long) = launch { repository.processNotification(sequenceNumber) }
    fun deleteNotification(sequenceNumber: Long) = launch { repository.deleteNotification(sequenceNumber) }
    fun resetEuiccMemory() = launch(repository::resetEuiccMemory)
    fun clearFailure() = repository.clearFailure()

    fun setProfileTags(iccid: String, tags: Set<String>) = launch { metadataStore.setTags(iccid, tags) }
    fun setProfileReminder(iccid: String, label: String, reminderAt: Instant?) = launch {
        metadataStore.setReminder(iccid, label, reminderAt)
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
        operatorIcons.value = emptyMap()
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
    fun setScheduledReminders(value: Boolean) = launch { settingsStore.setScheduledReminders(value) }
    fun setEidRedaction(value: RedactionMode) = launch { settingsStore.setEidRedaction(value) }
    fun setIccidRedaction(value: RedactionMode) = launch { settingsStore.setIccidRedaction(value) }
    fun setRevealSensitiveData(value: Boolean) = launch { settingsStore.setRevealSensitiveData(value) }
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
)
