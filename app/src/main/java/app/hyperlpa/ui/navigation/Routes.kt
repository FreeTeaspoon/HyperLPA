package app.hyperlpa.ui.navigation

import androidx.compose.runtime.Immutable
import androidx.annotation.StringRes
import app.hyperlpa.R
import top.yukonga.miuix.kmp.nav.core.NavKey

enum class AppTab {
    PROFILES,
    NOTIFICATIONS,
    TOOLS,
    SETTINGS,
}

@Immutable
sealed interface AppRoute : NavKey {
    data object Shell : AppRoute
    data class ProfileDetails(val iccid: String) : AppRoute
    data object DownloadProfile : AppRoute
    data object ConfirmProfileDownload : AppRoute
    data class ProfileDownloadResult(
        val result: app.hyperlpa.domain.model.ProfileDownloadResult,
    ) : AppRoute
    data object BatchDownload : AppRoute
    data object EuiccDetails : AppRoute
    data object ReaderSettings : AppRoute
    data object NotificationSettings : AppRoute
    data object NotificationHistory : AppRoute
    data object AppearanceSettings : AppRoute
    data object ProfileDisplaySettings : AppRoute
    data object PrivacySettings : AppRoute
    data object AdvancedSettings : AppRoute
    data object BackupRestoreSettings : AppRoute
    data object AidManager : AppRoute
    data object TagsAndReminders : AppRoute
    data object TagManager : AppRoute
    data object ScheduledReminders : AppRoute
    data object Statistics : AppRoute
    data object Logs : AppRoute
    data object About : AppRoute
}

@get:StringRes
val AppTab.titleRes: Int
    get() = when (this) {
    AppTab.PROFILES -> R.string.nav_profiles
    AppTab.NOTIFICATIONS -> R.string.nav_notifications
    AppTab.TOOLS -> R.string.nav_tools
    AppTab.SETTINGS -> R.string.nav_settings
}
