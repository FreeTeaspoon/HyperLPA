package app.hyperlpa.ui.navigation

import androidx.compose.runtime.Immutable

enum class AppTab {
    PROFILES,
    NOTIFICATIONS,
    TOOLS,
    SETTINGS,
}

@Immutable
sealed interface AppRoute {
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
    data object AppearanceSettings : AppRoute
    data object ProfileDisplaySettings : AppRoute
    data object PrivacySettings : AppRoute
    data object AdvancedSettings : AppRoute
    data object AidManager : AppRoute
    data object TagsAndReminders : AppRoute
    data object TagManager : AppRoute
    data object ScheduledReminders : AppRoute
    data object Statistics : AppRoute
    data object Logs : AppRoute
    data object About : AppRoute
}

fun AppTab.title(): String = when (this) {
    AppTab.PROFILES -> "Profiles"
    AppTab.NOTIFICATIONS -> "Notifications"
    AppTab.TOOLS -> "Tools"
    AppTab.SETTINGS -> "Settings"
}
