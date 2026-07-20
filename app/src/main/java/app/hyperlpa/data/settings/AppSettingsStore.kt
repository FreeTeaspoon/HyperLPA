package app.hyperlpa.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.io.IOException

private val Context.hyperLpaDataStore by preferencesDataStore(name = "hyperlpa_settings")

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
enum class ThemeAccent {
    SYSTEM,
    BLUE,
    PURPLE,
    PINK,
    RED,
    ORANGE,
    YELLOW,
    GREEN,
    TEAL,
}

@Serializable
enum class ThemePalette {
    TONAL_SPOT,
    NEUTRAL,
    VIBRANT,
    EXPRESSIVE,
    RAINBOW,
    FRUIT_SALAD,
    MONOCHROME,
    FIDELITY,
    CONTENT,
}

@Serializable
enum class NavigationStyle {
    STANDARD,
    FLOATING,
}

@Serializable
enum class FloatingBottomBarStyle {
    MIUIX,
    IOS_LIKE,
}

@Serializable
enum class NavigationLabels {
    ICON_AND_TEXT,
    ICON_ONLY,
}

@Serializable
enum class ProfileLayout {
    LIST,
    WATERFALL,
}

@Serializable
enum class ProfileSort {
    SLOT_ORDER,
    NAME,
    PROVIDER,
    ICCID,
    STATE,
}

@Serializable
enum class PhoneFormatStrategy {
    INTERNATIONAL_ONLY,
    INTERNATIONAL_AND_MOBILE,
    INTERNATIONAL_AND_ALL,
    OFF,
}

@Serializable
enum class RedactionMode {
    NONE,
    MIDDLE,
    FULL,
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useMonet: Boolean = false,
    val pureBlack: Boolean = false,
    val accent: ThemeAccent = ThemeAccent.SYSTEM,
    val palette: ThemePalette = ThemePalette.TONAL_SPOT,
    val blurEnabled: Boolean = true,
    val predictiveBack: Boolean = true,
    val densityScale: Float = 1f,
    val navigationStyle: NavigationStyle = NavigationStyle.STANDARD,
    val floatingBottomBarStyle: FloatingBottomBarStyle = FloatingBottomBarStyle.MIUIX,
    val navigationLabels: NavigationLabels = NavigationLabels.ICON_AND_TEXT,
    val profileLayout: ProfileLayout = ProfileLayout.LIST,
    val profileSort: ProfileSort = ProfileSort.SLOT_ORDER,
    val sortAscending: Boolean = true,
    val showProfileSearch: Boolean = false,
    val phoneFormatStrategy: PhoneFormatStrategy = PhoneFormatStrategy.INTERNATIONAL_AND_MOBILE,
    val showProfileNameOnHome: Boolean = true,
    val showProfileProviderOnHome: Boolean = true,
    val showProfileIccidOnHome: Boolean = true,
    val showProfileIconOnHome: Boolean = true,
    val showProfileTagsOnHome: Boolean = true,
    val showProfileRemindersOnHome: Boolean = true,
    val showProfileSizeOnHome: Boolean = true,
    val showProfileSwitchOnHome: Boolean = true,
    val showReaderSelectorOnHome: Boolean = true,
    val showEidOnHome: Boolean = true,
    val autoLoadProfiles: Boolean = true,
    val autoLoadRemoteReaders: Boolean = false,
    val enableNBridge: Boolean = true,
    val enableOmapi: Boolean = true,
    val enableTelephony: Boolean = false,
    val enableUsbCcid: Boolean = true,
    val enableBle: Boolean = false,
    val enableRemote: Boolean = false,
    val notificationInitialLoad: Boolean = true,
    val notificationAfterSwitch: Boolean = true,
    val notificationAfterDelete: Boolean = true,
    val notificationBeforeDownload: Boolean = true,
    val notificationAfterDownload: Boolean = true,
    val notificationAutoSend: Boolean = true,
    val notificationAutoRemove: Boolean = true,
    val scheduledReminders: Boolean = true,
    val eidRedaction: RedactionMode = RedactionMode.NONE,
    val iccidRedaction: RedactionMode = RedactionMode.NONE,
    val loadOperatorIcons: Boolean = true,
    val estimateProfileSize: Boolean = true,
    val hideProfileDeletion: Boolean = false,
    val hideEuiccMemoryReset: Boolean = false,
    val apduLogging: Boolean = false,
    val developerMode: Boolean = false,
    val es10xMss: Int = 60,
    val imei: String = "",
    val lastReaderId: String? = null,
    val isdrAids: List<String> = DefaultIsdrAids,
    val remoteReaderUrls: List<String> = emptyList(),
)

val DefaultIsdrAids = listOf(
    "A0000005591010FFFFFFFF8900000100",
    "A0000005591010FFFFFFFF8900050500",
    "A0000005591010000000008900000300",
    "A0000005591010FFFFFFFF8900000177",
)

class AppSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.hyperLpaDataStore

    val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::readSettings)

    suspend fun setThemeMode(value: ThemeMode) = set(Keys.ThemeMode, value.name)
    suspend fun setUseMonet(value: Boolean) = set(Keys.UseMonet, value)
    suspend fun setPureBlack(value: Boolean) = set(Keys.PureBlack, value)
    suspend fun setAccent(value: ThemeAccent) = set(Keys.Accent, value.name)
    suspend fun setPalette(value: ThemePalette) = set(Keys.Palette, value.name)
    suspend fun setBlurEnabled(value: Boolean) = set(Keys.BlurEnabled, value)
    suspend fun setPredictiveBack(value: Boolean) = set(Keys.PredictiveBack, value)
    suspend fun setDensityScale(value: Float) = set(Keys.DensityScale, value.coerceIn(0.8f, 1.1f))
    suspend fun setNavigationStyle(value: NavigationStyle) = set(Keys.NavigationStyle, value.name)
    suspend fun setFloatingBottomBarStyle(value: FloatingBottomBarStyle) =
        set(Keys.FloatingBottomBarStyle, value.name)
    suspend fun setNavigationLabels(value: NavigationLabels) = set(Keys.NavigationLabels, value.name)
    suspend fun setProfileLayout(value: ProfileLayout) = set(Keys.ProfileLayout, value.name)
    suspend fun setProfileSort(value: ProfileSort) = set(Keys.ProfileSort, value.name)
    suspend fun setSortAscending(value: Boolean) = set(Keys.SortAscending, value)
    suspend fun setShowProfileSearch(value: Boolean) = set(Keys.ShowProfileSearch, value)
    suspend fun setPhoneFormatStrategy(value: PhoneFormatStrategy) = set(Keys.PhoneFormatStrategy, value.name)
    suspend fun setShowProfileNameOnHome(value: Boolean) = set(Keys.ShowProfileNameOnHome, value)
    suspend fun setShowProfileProviderOnHome(value: Boolean) = set(Keys.ShowProfileProviderOnHome, value)
    suspend fun setShowProfileIccidOnHome(value: Boolean) = set(Keys.ShowProfileIccidOnHome, value)
    suspend fun setShowProfileIconOnHome(value: Boolean) = set(Keys.ShowProfileIconOnHome, value)
    suspend fun setShowProfileTagsOnHome(value: Boolean) = set(Keys.ShowProfileTagsOnHome, value)
    suspend fun setShowProfileRemindersOnHome(value: Boolean) = set(Keys.ShowProfileRemindersOnHome, value)
    suspend fun setShowProfileSizeOnHome(value: Boolean) = set(Keys.ShowProfileSizeOnHome, value)
    suspend fun setShowProfileSwitchOnHome(value: Boolean) = set(Keys.ShowProfileSwitchOnHome, value)
    suspend fun setShowReaderSelectorOnHome(value: Boolean) = set(Keys.ShowReaderSelectorOnHome, value)
    suspend fun setShowEidOnHome(value: Boolean) = set(Keys.ShowEidOnHome, value)
    suspend fun setAutoLoadProfiles(value: Boolean) = set(Keys.AutoLoadProfiles, value)
    suspend fun setAutoLoadRemoteReaders(value: Boolean) = set(Keys.AutoLoadRemoteReaders, value)
    suspend fun setEnableNBridge(value: Boolean) = set(Keys.EnableNBridge, value)
    suspend fun setEnableOmapi(value: Boolean) = set(Keys.EnableOmapi, value)
    suspend fun setEnableTelephony(value: Boolean) = set(Keys.EnableTelephony, value)
    suspend fun setEnableUsbCcid(value: Boolean) = set(Keys.EnableUsbCcid, value)
    suspend fun setEnableBle(value: Boolean) = set(Keys.EnableBle, value)
    suspend fun setEnableRemote(value: Boolean) = set(Keys.EnableRemote, value)
    suspend fun setNotificationInitialLoad(value: Boolean) = set(Keys.NotificationInitialLoad, value)
    suspend fun setNotificationAfterSwitch(value: Boolean) = set(Keys.NotificationAfterSwitch, value)
    suspend fun setNotificationAfterDelete(value: Boolean) = set(Keys.NotificationAfterDelete, value)
    suspend fun setNotificationBeforeDownload(value: Boolean) = set(Keys.NotificationBeforeDownload, value)
    suspend fun setNotificationAfterDownload(value: Boolean) = set(Keys.NotificationAfterDownload, value)
    suspend fun setNotificationAutoSend(value: Boolean) = set(Keys.NotificationAutoSend, value)
    suspend fun setNotificationAutoRemove(value: Boolean) = set(Keys.NotificationAutoRemove, value)
    suspend fun setScheduledReminders(value: Boolean) = set(Keys.ScheduledReminders, value)
    suspend fun setEidRedaction(value: RedactionMode) = set(Keys.EidRedaction, value.name)
    suspend fun setIccidRedaction(value: RedactionMode) = set(Keys.IccidRedaction, value.name)
    suspend fun setLoadOperatorIcons(value: Boolean) = set(Keys.LoadOperatorIcons, value)
    suspend fun setEstimateProfileSize(value: Boolean) = set(Keys.EstimateProfileSize, value)
    suspend fun setHideProfileDeletion(value: Boolean) = set(Keys.HideProfileDeletion, value)
    suspend fun setHideEuiccMemoryReset(value: Boolean) = set(Keys.HideEuiccMemoryReset, value)
    suspend fun setApduLogging(value: Boolean) = set(Keys.ApduLogging, value)
    suspend fun setDeveloperMode(value: Boolean) = set(Keys.DeveloperMode, value)
    suspend fun setEs10xMss(value: Int) = set(Keys.Es10xMss, value.coerceIn(32, 255))
    suspend fun setImei(value: String) = set(Keys.Imei, value.filter(Char::isDigit).take(16))
    suspend fun setLastReaderId(value: String?) = setNullable(Keys.LastReaderId, value)
    suspend fun setIsdrAids(value: List<String>) = set(
        Keys.IsdrAids,
        value.map(String::trim).filter(String::isNotEmpty).distinct().joinToString("\n"),
    )
    suspend fun setRemoteReaderUrls(value: List<String>) = set(
        Keys.RemoteReaderUrls,
        value.map(String::trim).filter(String::isNotEmpty).distinct().joinToString("\n"),
    )

    suspend fun replaceSettings(settings: AppSettings) {
        dataStore.edit { preferences ->
            preferences.clear()
            preferences.writeSettings(settings)
        }
    }

    suspend fun resetToDefaults(): AppSettings = AppSettings().also {
        dataStore.edit { preferences -> preferences.clear() }
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        dataStore.edit { preferences -> preferences[key] = value }
    }

    private suspend fun setNullable(key: Preferences.Key<String>, value: String?) {
        dataStore.edit { preferences ->
            if (value == null) preferences.remove(key) else preferences[key] = value
        }
    }

    private fun MutablePreferences.writeSettings(settings: AppSettings) {
        this[Keys.ThemeMode] = settings.themeMode.name
        this[Keys.UseMonet] = settings.useMonet
        this[Keys.PureBlack] = settings.pureBlack
        this[Keys.Accent] = settings.accent.name
        this[Keys.Palette] = settings.palette.name
        this[Keys.BlurEnabled] = settings.blurEnabled
        this[Keys.PredictiveBack] = settings.predictiveBack
        this[Keys.DensityScale] = settings.densityScale.coerceIn(0.8f, 1.1f)
        this[Keys.NavigationStyle] = settings.navigationStyle.name
        this[Keys.FloatingBottomBarStyle] = settings.floatingBottomBarStyle.name
        this[Keys.NavigationLabels] = settings.navigationLabels.name
        this[Keys.ProfileLayout] = settings.profileLayout.name
        this[Keys.ProfileSort] = settings.profileSort.name
        this[Keys.SortAscending] = settings.sortAscending
        this[Keys.ShowProfileSearch] = settings.showProfileSearch
        this[Keys.PhoneFormatStrategy] = settings.phoneFormatStrategy.name
        this[Keys.ShowProfileNameOnHome] = settings.showProfileNameOnHome
        this[Keys.ShowProfileProviderOnHome] = settings.showProfileProviderOnHome
        this[Keys.ShowProfileIccidOnHome] = settings.showProfileIccidOnHome
        this[Keys.ShowProfileIconOnHome] = settings.showProfileIconOnHome
        this[Keys.ShowProfileTagsOnHome] = settings.showProfileTagsOnHome
        this[Keys.ShowProfileRemindersOnHome] = settings.showProfileRemindersOnHome
        this[Keys.ShowProfileSizeOnHome] = settings.showProfileSizeOnHome
        this[Keys.ShowProfileSwitchOnHome] = settings.showProfileSwitchOnHome
        this[Keys.ShowReaderSelectorOnHome] = settings.showReaderSelectorOnHome
        this[Keys.ShowEidOnHome] = settings.showEidOnHome
        this[Keys.AutoLoadProfiles] = settings.autoLoadProfiles
        this[Keys.AutoLoadRemoteReaders] = settings.autoLoadRemoteReaders
        this[Keys.EnableNBridge] = settings.enableNBridge
        this[Keys.EnableOmapi] = settings.enableOmapi
        this[Keys.EnableTelephony] = settings.enableTelephony
        this[Keys.EnableUsbCcid] = settings.enableUsbCcid
        this[Keys.EnableBle] = settings.enableBle
        this[Keys.EnableRemote] = settings.enableRemote
        this[Keys.NotificationInitialLoad] = settings.notificationInitialLoad
        this[Keys.NotificationAfterSwitch] = settings.notificationAfterSwitch
        this[Keys.NotificationAfterDelete] = settings.notificationAfterDelete
        this[Keys.NotificationBeforeDownload] = settings.notificationBeforeDownload
        this[Keys.NotificationAfterDownload] = settings.notificationAfterDownload
        this[Keys.NotificationAutoSend] = settings.notificationAutoSend
        this[Keys.NotificationAutoRemove] = settings.notificationAutoRemove
        this[Keys.ScheduledReminders] = settings.scheduledReminders
        this[Keys.EidRedaction] = settings.eidRedaction.name
        this[Keys.IccidRedaction] = settings.iccidRedaction.name
        this[Keys.LoadOperatorIcons] = settings.loadOperatorIcons
        this[Keys.EstimateProfileSize] = settings.estimateProfileSize
        this[Keys.HideProfileDeletion] = settings.hideProfileDeletion
        this[Keys.HideEuiccMemoryReset] = settings.hideEuiccMemoryReset
        this[Keys.ApduLogging] = settings.apduLogging
        this[Keys.DeveloperMode] = settings.developerMode
        this[Keys.Es10xMss] = settings.es10xMss.coerceIn(32, 255)
        this[Keys.Imei] = settings.imei.filter(Char::isDigit).take(16)
        settings.lastReaderId?.let { this[Keys.LastReaderId] = it }
        this[Keys.IsdrAids] = settings.isdrAids
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString("\n")
        this[Keys.RemoteReaderUrls] = settings.remoteReaderUrls
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString("\n")
    }

    private fun readSettings(preferences: Preferences): AppSettings = AppSettings(
        themeMode = preferences.enum(Keys.ThemeMode, ThemeMode.SYSTEM),
        useMonet = preferences[Keys.UseMonet] ?: false,
        pureBlack = preferences[Keys.PureBlack] ?: false,
        accent = preferences.enum(Keys.Accent, ThemeAccent.SYSTEM),
        palette = preferences.enum(Keys.Palette, ThemePalette.TONAL_SPOT),
        blurEnabled = preferences[Keys.BlurEnabled] ?: true,
        predictiveBack = preferences[Keys.PredictiveBack] ?: true,
        densityScale = (preferences[Keys.DensityScale] ?: 1f).coerceIn(0.8f, 1.1f),
        navigationStyle = preferences.enum(Keys.NavigationStyle, NavigationStyle.STANDARD),
        floatingBottomBarStyle = preferences.enum(
            Keys.FloatingBottomBarStyle,
            FloatingBottomBarStyle.MIUIX,
        ),
        navigationLabels = preferences.enum(Keys.NavigationLabels, NavigationLabels.ICON_AND_TEXT),
        profileLayout = preferences.enum(Keys.ProfileLayout, ProfileLayout.LIST),
        profileSort = preferences.enum(Keys.ProfileSort, ProfileSort.SLOT_ORDER),
        sortAscending = preferences[Keys.SortAscending] ?: true,
        showProfileSearch = preferences[Keys.ShowProfileSearch] ?: false,
        phoneFormatStrategy = preferences.enum(
            Keys.PhoneFormatStrategy,
            PhoneFormatStrategy.INTERNATIONAL_AND_MOBILE,
        ),
        showProfileNameOnHome = preferences[Keys.ShowProfileNameOnHome] ?: true,
        showProfileProviderOnHome = preferences[Keys.ShowProfileProviderOnHome] ?: true,
        showProfileIccidOnHome = preferences[Keys.ShowProfileIccidOnHome] ?: true,
        showProfileIconOnHome = preferences[Keys.ShowProfileIconOnHome] ?: true,
        showProfileTagsOnHome = preferences[Keys.ShowProfileTagsOnHome] ?: true,
        showProfileRemindersOnHome = preferences[Keys.ShowProfileRemindersOnHome] ?: true,
        showProfileSizeOnHome = preferences[Keys.ShowProfileSizeOnHome] ?: true,
        showProfileSwitchOnHome = preferences[Keys.ShowProfileSwitchOnHome] ?: true,
        showReaderSelectorOnHome = preferences[Keys.ShowReaderSelectorOnHome] ?: true,
        showEidOnHome = preferences[Keys.ShowEidOnHome] ?: true,
        autoLoadProfiles = preferences[Keys.AutoLoadProfiles] ?: true,
        autoLoadRemoteReaders = preferences[Keys.AutoLoadRemoteReaders] ?: false,
        enableNBridge = preferences[Keys.EnableNBridge] ?: true,
        enableOmapi = preferences[Keys.EnableOmapi] ?: true,
        enableTelephony = preferences[Keys.EnableTelephony] ?: false,
        enableUsbCcid = preferences[Keys.EnableUsbCcid] ?: true,
        enableBle = preferences[Keys.EnableBle] ?: false,
        enableRemote = preferences[Keys.EnableRemote] ?: false,
        notificationInitialLoad = preferences[Keys.NotificationInitialLoad] ?: true,
        notificationAfterSwitch = preferences[Keys.NotificationAfterSwitch] ?: true,
        notificationAfterDelete = preferences[Keys.NotificationAfterDelete] ?: true,
        notificationBeforeDownload = preferences[Keys.NotificationBeforeDownload] ?: true,
        notificationAfterDownload = preferences[Keys.NotificationAfterDownload] ?: true,
        notificationAutoSend = preferences[Keys.NotificationAutoSend] ?: true,
        notificationAutoRemove = preferences[Keys.NotificationAutoRemove] ?: true,
        scheduledReminders = preferences[Keys.ScheduledReminders] ?: true,
        eidRedaction = preferences.enum(Keys.EidRedaction, RedactionMode.NONE),
        iccidRedaction = preferences.enum(Keys.IccidRedaction, RedactionMode.NONE),
        loadOperatorIcons = preferences[Keys.LoadOperatorIcons] ?: true,
        estimateProfileSize = preferences[Keys.EstimateProfileSize] ?: true,
        hideProfileDeletion = preferences[Keys.HideProfileDeletion] ?: false,
        hideEuiccMemoryReset = preferences[Keys.HideEuiccMemoryReset] ?: false,
        apduLogging = preferences[Keys.ApduLogging] ?: false,
        developerMode = preferences[Keys.DeveloperMode] ?: false,
        es10xMss = (preferences[Keys.Es10xMss] ?: 60).coerceIn(32, 255),
        imei = preferences[Keys.Imei].orEmpty(),
        lastReaderId = preferences[Keys.LastReaderId],
        isdrAids = preferences[Keys.IsdrAids]
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?.toList()
            ?.takeIf(List<String>::isNotEmpty)
            ?: DefaultIsdrAids,
        remoteReaderUrls = preferences[Keys.RemoteReaderUrls]
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?.toList()
            .orEmpty(),
    )

    private inline fun <reified T : Enum<T>> Preferences.enum(
        key: Preferences.Key<String>,
        defaultValue: T,
    ): T = this[key]?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: defaultValue

    private object Keys {
        val ThemeMode = stringPreferencesKey("theme_mode")
        val UseMonet = booleanPreferencesKey("use_monet")
        val PureBlack = booleanPreferencesKey("pure_black")
        val Accent = stringPreferencesKey("theme_accent")
        val Palette = stringPreferencesKey("theme_palette")
        val BlurEnabled = booleanPreferencesKey("blur_enabled")
        val PredictiveBack = booleanPreferencesKey("predictive_back")
        val DensityScale = floatPreferencesKey("density_scale")
        val NavigationStyle = stringPreferencesKey("navigation_style")
        val FloatingBottomBarStyle = stringPreferencesKey("floating_bottom_bar_style")
        val NavigationLabels = stringPreferencesKey("navigation_labels")
        val ProfileLayout = stringPreferencesKey("profile_layout")
        val ProfileSort = stringPreferencesKey("profile_sort")
        val SortAscending = booleanPreferencesKey("sort_ascending")
        val ShowProfileSearch = booleanPreferencesKey("show_profile_search")
        val PhoneFormatStrategy = stringPreferencesKey("phone_format_strategy")
        val ShowProfileNameOnHome = booleanPreferencesKey("show_profile_name_on_home")
        val ShowProfileProviderOnHome = booleanPreferencesKey("show_profile_provider_on_home")
        val ShowProfileIccidOnHome = booleanPreferencesKey("show_profile_iccid_on_home")
        val ShowProfileIconOnHome = booleanPreferencesKey("show_profile_icon_on_home")
        val ShowProfileTagsOnHome = booleanPreferencesKey("show_profile_tags_on_home")
        val ShowProfileRemindersOnHome = booleanPreferencesKey("show_profile_reminders_on_home")
        val ShowProfileSizeOnHome = booleanPreferencesKey("show_profile_size_on_home")
        val ShowProfileSwitchOnHome = booleanPreferencesKey("show_profile_switch_on_home")
        val ShowReaderSelectorOnHome = booleanPreferencesKey("show_reader_selector_on_home")
        val ShowEidOnHome = booleanPreferencesKey("show_eid_on_home")
        val AutoLoadProfiles = booleanPreferencesKey("auto_load_profiles")
        val AutoLoadRemoteReaders = booleanPreferencesKey("auto_load_remote_readers")
        val EnableNBridge = booleanPreferencesKey("reader_nbridge")
        val EnableOmapi = booleanPreferencesKey("reader_omapi")
        val EnableTelephony = booleanPreferencesKey("reader_telephony")
        val EnableUsbCcid = booleanPreferencesKey("reader_usb_ccid")
        val EnableBle = booleanPreferencesKey("reader_ble")
        val EnableRemote = booleanPreferencesKey("reader_remote")
        val NotificationInitialLoad = booleanPreferencesKey("notification_initial_load")
        val NotificationAfterSwitch = booleanPreferencesKey("notification_after_switch")
        val NotificationAfterDelete = booleanPreferencesKey("notification_after_delete")
        val NotificationBeforeDownload = booleanPreferencesKey("notification_before_download")
        val NotificationAfterDownload = booleanPreferencesKey("notification_after_download")
        val NotificationAutoSend = booleanPreferencesKey("notification_auto_send")
        val NotificationAutoRemove = booleanPreferencesKey("notification_auto_remove")
        val ScheduledReminders = booleanPreferencesKey("scheduled_reminders")
        val EidRedaction = stringPreferencesKey("eid_redaction")
        val IccidRedaction = stringPreferencesKey("iccid_redaction")
        val LoadOperatorIcons = booleanPreferencesKey("load_operator_icons")
        val EstimateProfileSize = booleanPreferencesKey("estimate_profile_size")
        val HideProfileDeletion = booleanPreferencesKey("hide_profile_deletion")
        val HideEuiccMemoryReset = booleanPreferencesKey("hide_euicc_memory_reset")
        val ApduLogging = booleanPreferencesKey("apdu_logging")
        val DeveloperMode = booleanPreferencesKey("developer_mode")
        val Es10xMss = intPreferencesKey("es10x_mss")
        val Imei = stringPreferencesKey("imei")
        val LastReaderId = stringPreferencesKey("last_reader_id")
        val IsdrAids = stringPreferencesKey("isdr_aids")
        val RemoteReaderUrls = stringPreferencesKey("remote_reader_urls")
    }
}
