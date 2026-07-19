package app.hyperlpa.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hyperlpa.data.LpaRepository
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.metadata.ProfileMetadata
import app.hyperlpa.data.metadata.ProfileMetadataStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

data class HyperLpaUiState(
    val settings: AppSettings = AppSettings(),
    val lpa: LpaRepositoryState = LpaRepositoryState(),
    val backStack: List<AppRoute> = listOf(AppRoute.Shell),
    val selectedTab: AppTab = AppTab.PROFILES,
    val searchQuery: String = "",
    val activationCodeDraft: String = "",
    val metadata: Map<String, ProfileMetadata> = emptyMap(),
) {
    val profiles: List<ProfileInfo>
        get() = lpa.profiles
            .map { profile ->
                val extra = metadata[profile.iccid]
                profile.copy(
                    tags = extra?.tags.orEmpty(),
                    reminderAt = extra?.reminderAt,
                    customIconUri = extra?.iconUri,
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
) : AndroidViewModel(application) {
    private val backStack = MutableStateFlow<List<AppRoute>>(listOf(AppRoute.Shell))
    private val selectedTab = MutableStateFlow(AppTab.PROFILES)
    private val searchQuery = MutableStateFlow("")
    private val activationCodeDraft = MutableStateFlow("")
    private var simStateRefreshJob: Job? = null

    val state = combine(
        settingsStore.settings,
        repository.state,
        backStack,
        selectedTab,
        searchQuery,
        activationCodeDraft,
        metadataStore.metadata,
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
    fun setProfileIcon(iccid: String, uri: String?) = launch { metadataStore.setIconUri(iccid, uri) }

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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HyperLpaViewModel(
            application = application,
            settingsStore = settingsStore,
            metadataStore = metadataStore,
            repository = repository,
        ) as T
    }
}
