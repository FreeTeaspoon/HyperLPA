package app.hyperlpa.data.support

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.hyperlpa.BuildConfig
import app.hyperlpa.R
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.history.NotificationHistoryEntry
import app.hyperlpa.data.history.sanitizeNotificationHost
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ActivityLogEntry
import app.hyperlpa.domain.model.LogLevel
import app.hyperlpa.domain.model.ReaderKind
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class SupportReportBuilder(private val context: Context) {
    fun build(
        settings: AppSettings,
        repositoryState: LpaRepositoryState,
        notificationHistory: List<NotificationHistoryEntry>,
        generatedAt: Instant = Instant.now(),
    ): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return buildSupportReport(
            SupportReportInput(
                generatedAt = generatedAt,
                appVersionName = packageInfo.versionName.orEmpty(),
                appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                packageName = context.packageName,
                buildType = app.hyperlpa.BuildConfig.BUILD_TYPE,
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                androidSdk = Build.VERSION.SDK_INT,
                settings = settings,
                repositoryState = repositoryState,
                notificationHistory = notificationHistory,
                strings = context.supportReportStrings(),
            ),
        )
    }
}

internal data class SupportReportInput(
    val generatedAt: Instant,
    val appVersionName: String,
    val appVersionCode: Long,
    val packageName: String,
    val buildType: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val androidSdk: Int,
    val settings: AppSettings,
    val repositoryState: LpaRepositoryState,
    val notificationHistory: List<NotificationHistoryEntry>,
    val strings: SupportReportStrings,
)

internal data class SupportReportStrings(
    val title: String,
    val generated: String,
    val included: String,
    val excluded: String,
    val appSection: String,
    val packageLine: String,
    val versionLine: String,
    val buildTypeLine: String,
    val deviceSection: String,
    val manufacturerLine: String,
    val modelLine: String,
    val androidLine: String,
    val readersSection: String,
    val readerStatusLine: String,
    val connectedBackendLine: String,
    val flagsSection: String,
    val flagLine: String,
    val yes: String,
    val no: String,
    val failureSection: String,
    val failureLine: String,
    val detailsOmitted: String,
    val readerCategory: String,
    val notificationHistoryCategory: String,
    val lpaCategory: String,
    val operationFailureCategory: String,
    val none: String,
    val logsSection: String,
    val logLine: String,
    val historySection: String,
    val historyLine: String,
    val historyHost: String,
    val historyReason: String,
)

internal fun buildSupportReport(input: SupportReportInput): String {
    val state = input.repositoryState
    val strings = input.strings
    val lines = buildList {
        add(strings.title)
        add(strings.generated.formatLocalized(input.generatedAt.asUtcText()))
        add(strings.included)
        add(strings.excluded)
        add("")
        add(strings.appSection)
        add(strings.packageLine.formatLocalized(sanitizeSupportText(input.packageName)))
        add(
            strings.versionLine.formatLocalized(
                sanitizeSupportText(input.appVersionName),
                input.appVersionCode,
            ),
        )
        add(strings.buildTypeLine.formatLocalized(sanitizeSupportText(input.buildType)))
        add("")
        add(strings.deviceSection)
        add(strings.manufacturerLine.formatLocalized(sanitizeSupportText(input.manufacturer)))
        add(strings.modelLine.formatLocalized(sanitizeSupportText(input.model)))
        add(strings.androidLine.formatLocalized(sanitizeSupportText(input.androidRelease), input.androidSdk))
        add("")
        add(strings.readersSection)
        ReaderKind.entries.forEach { kind ->
            val discovered = state.readers.count { it.kind == kind }
            val available = state.readers.count { it.kind == kind && it.available }
            add(
                strings.readerStatusLine.formatLocalized(
                    kind.name,
                    if (kind.isEnabled(input.settings)) strings.yes else strings.no,
                    discovered,
                    available,
                ),
            )
        }
        add(
            strings.connectedBackendLine.formatLocalized(
                state.selectedReader?.kind?.name ?: strings.none,
            ),
        )
        add("")
        add(strings.flagsSection)
        input.settings.supportFeatureFlags().forEach { (name, value) ->
            add(strings.flagLine.formatLocalized(name, if (value) strings.yes else strings.no))
        }
        add("")
        add(strings.failureSection)
        if (state.failure == null) {
            add(strings.none)
        } else {
            add(
                strings.failureLine.formatLocalized(
                    strings.operationFailureCategory,
                    strings.detailsOmitted,
                ),
            )
        }
        add("")
        add(strings.logsSection.formatLocalized(MaxSupportLogEntries))
        val failureLogs = state.logs
            .filter { it.level == LogLevel.WARNING || it.level == LogLevel.ERROR }
            .takeLast(MaxSupportLogEntries)
        if (failureLogs.isEmpty()) add(strings.none)
        failureLogs.forEach { add(it.asSupportLine(strings)) }
        add("")
        add(strings.historySection.formatLocalized(MaxSupportHistoryEntries))
        val history = input.notificationHistory.takeLast(MaxSupportHistoryEntries)
        if (history.isEmpty()) add(strings.none)
        history.forEach { entry ->
            add(
                buildString {
                    append(
                        strings.historyLine.formatLocalized(
                            Instant.ofEpochMilli(entry.timestampEpochMillis).asUtcText(),
                            entry.action.name,
                            entry.status.name,
                            entry.trigger.name,
                            sanitizeSupportText(entry.notificationOperation),
                        ),
                    )
                    entry.endpointHost?.let {
                        append(strings.historyHost.formatLocalized(sanitizeSupportText(it)))
                    }
                    entry.failureCode?.let {
                        append(strings.historyReason.formatLocalized(sanitizeSupportText(it)))
                    }
                },
            )
        }
    }
    return boundSupportReport(lines)
}

private fun ActivityLogEntry.asSupportLine(strings: SupportReportStrings): String =
    strings.logLine.formatLocalized(
        timestamp.asUtcText(),
        level.name,
        supportCategory(strings),
        strings.detailsOmitted,
    )

private fun ActivityLogEntry.supportCategory(strings: SupportReportStrings): String = when (tag) {
    "Reader" -> strings.readerCategory
    "Notification history" -> strings.notificationHistoryCategory
    "LPA" -> strings.lpaCategory
    else -> strings.operationFailureCategory
}

private fun AppSettings.supportFeatureFlags(): List<Pair<String, Boolean>> = listOf(
    "auto_load_profiles" to autoLoadProfiles,
    "auto_load_remote_readers" to autoLoadRemoteReaders,
    "notification_on_connect" to notificationInitialLoad,
    "notification_after_switch" to notificationAfterSwitch,
    "notification_after_delete" to notificationAfterDelete,
    "notification_before_download" to notificationBeforeDownload,
    "notification_after_download" to notificationAfterDownload,
    "notification_auto_send" to notificationAutoSend,
    "notification_auto_remove" to notificationAutoRemove,
    "scheduled_reminders" to scheduledReminders,
    "operator_icon_cloud_lookup" to loadOperatorIcons,
    "profile_size_cloud_estimation" to estimateProfileSize,
    "developer_mode" to developerMode,
    "verbose_protocol_logging" to apduLogging,
)

private fun ReaderKind.isEnabled(settings: AppSettings): Boolean = when (this) {
    ReaderKind.NBRIDGE -> settings.enableNBridge
    ReaderKind.OMAPI -> settings.enableOmapi
    ReaderKind.TELEPHONY -> BuildConfig.HAS_PRIVILEGED_TELEPHONY && settings.enableTelephony
    ReaderKind.USB_CCID -> settings.enableUsbCcid
    ReaderKind.BLE -> settings.enableBle
    ReaderKind.REMOTE -> settings.enableRemote
}

internal fun sanitizeSupportText(value: String): String {
    var sanitized = value.take(MaxSupportScanChars)
        .replace(ControlCharacters, " ")
        .replace(WebUrl) { match ->
            sanitizeNotificationHost(match.value)
                ?.let { host -> "[url-host:$host]" }
                ?: "[url]"
        }
        .replace(ActivationCode, "[activation-code]")
        .replace(BearerCredential, "Bearer [credential]")
        .replace(UrlCredentials, "$1[credentials]@")
        .replace(SensitiveQueryValue, "$1=[secret]")
        .replace(ContextualSecret, "$1=[secret]")
        .replace(LabelledIdentifier, "$1=[identifier]")
        .replace(ReaderId, "$1 [redacted]")
        .replace(ApduBytes, "[apdu]")
        .replace(LongDecimalIdentifier, "[identifier]")
        .replace(LongHexIdentifier, "[identifier]")
        .replace(RepeatedWhitespace, " ")
        .trim()
    if (sanitized.length > MaxSupportFieldChars) {
        sanitized = sanitized.take(MaxSupportFieldChars) + "…"
    }
    return sanitized
}

internal fun boundSupportReport(lines: List<String>): String {
    val output = StringBuilder(minOf(MaxSupportReportChars, lines.sumOf { it.length + 1 }))
    lines.forEach { rawLine ->
        val line = sanitizeSupportText(rawLine)
        val remaining = MaxSupportReportChars - output.length
        if (remaining <= 1) return@forEach
        if (line.length + 1 <= remaining) {
            output.append(line).append('\n')
        } else {
            output.append(line.take(remaining - 1)).append('\n')
        }
    }
    return output.toString()
}

private fun Instant.asUtcText(): String = DateTimeFormatter.ISO_INSTANT
    .withZone(ZoneOffset.UTC)
    .format(this)

internal const val MaxSupportReportChars = 64 * 1024
internal const val MaxSupportLogEntries = 50
internal const val MaxSupportHistoryEntries = 100
private const val MaxSupportFieldChars = 640
private const val MaxSupportScanChars = 4 * 1024

private fun String.formatLocalized(vararg arguments: Any): String =
    String.format(Locale.getDefault(), this, *arguments)

private fun Context.supportReportStrings() = SupportReportStrings(
    title = getString(R.string.support_report_title),
    generated = getString(R.string.support_report_generated),
    included = getString(R.string.support_report_included),
    excluded = getString(R.string.support_report_excluded),
    appSection = getString(R.string.support_report_section_app),
    packageLine = getString(R.string.support_report_package),
    versionLine = getString(R.string.support_report_version),
    buildTypeLine = getString(R.string.support_report_build_type),
    deviceSection = getString(R.string.support_report_section_device),
    manufacturerLine = getString(R.string.support_report_manufacturer),
    modelLine = getString(R.string.support_report_model),
    androidLine = getString(R.string.support_report_android),
    readersSection = getString(R.string.support_report_section_readers),
    readerStatusLine = getString(R.string.support_report_reader_status),
    connectedBackendLine = getString(R.string.support_report_connected_backend),
    flagsSection = getString(R.string.support_report_section_flags),
    flagLine = getString(R.string.support_report_flag),
    yes = getString(R.string.support_report_yes),
    no = getString(R.string.support_report_no),
    failureSection = getString(R.string.support_report_section_failure),
    failureLine = getString(R.string.support_report_failure),
    detailsOmitted = getString(R.string.support_report_details_omitted),
    readerCategory = getString(R.string.support_report_category_reader),
    notificationHistoryCategory = getString(R.string.support_report_category_notification_history),
    lpaCategory = getString(R.string.support_report_category_lpa),
    operationFailureCategory = getString(R.string.support_report_category_operation_failure),
    none = getString(R.string.support_report_none),
    logsSection = getString(R.string.support_report_section_logs),
    logLine = getString(R.string.support_report_log),
    historySection = getString(R.string.support_report_section_history),
    historyLine = getString(R.string.support_report_history),
    historyHost = getString(R.string.support_report_history_host),
    historyReason = getString(R.string.support_report_history_reason),
)

private val ControlCharacters = Regex("\\p{Cc}")
private val WebUrl = Regex("(?i)\\bhttps?://[^\\s\\\"'<>]+")
private val ActivationCode = Regex("(?i)LPA:1\\$[^\\s\\\"'<>]+")
private val BearerCredential = Regex("(?i)Bearer\\s+[^\\s,;]+")
private val UrlCredentials = Regex("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@")
private val SensitiveQueryValue = Regex("(?i)(token|secret|password|passphrase|matching_?id|confirmation_?code|activation_?code)=([^&#\\s]+)")
private val ContextualSecret = Regex("(?i)\\b(confirmation(?:[_ -]?code)?|matching(?:[_ -]?id)?|activation(?:[_ -]?code)?|password|passphrase|token|secret)\\s*(?:[:=]\\s*|\\s+)[^\\s,;]+")
private val LabelledIdentifier = Regex("(?i)\\b(iccid|eid|imei)\\s*[:=]?\\s*[0-9 -]{14,40}")
private val ReaderId = Regex("(?i)\\b(reader\\s+id)\\s+[^\\s,;]+")
private val ApduBytes = Regex("(?i)(?:\\b[0-9a-f]{2}[ :_-]*){10,}")
private val LongDecimalIdentifier = Regex("(?<!\\d)\\d{15,32}(?!\\d)")
private val LongHexIdentifier = Regex("(?i)(?<![0-9a-f])[0-9a-f]{20,64}(?![0-9a-f])")
private val RepeatedWhitespace = Regex("[ \\t]{2,}")
