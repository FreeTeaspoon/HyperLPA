package app.hyperlpa.data.support

import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.history.NotificationHistoryAction
import app.hyperlpa.data.history.NotificationHistoryEntry
import app.hyperlpa.data.history.NotificationHistoryStatus
import app.hyperlpa.data.history.NotificationHistoryTrigger
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ActivityLogEntry
import app.hyperlpa.domain.model.LogLevel
import app.hyperlpa.domain.model.OperationFailure
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportReportBuilderTest {
    @Test
    fun reportOmitsFailureAndActivityLogDetails() {
        val iccid = "8901234567890123456"
        val eid = "89049032000000000000000000000001"
        val activationCode = "LPA:1\$smdp.example\$matching-secret\$1.2.3\$1"
        val bearer = "Bearer top-secret-token"
        val report = buildSupportReport(
            baseInput(
                repositoryState = LpaRepositoryState(
                    failure = OperationFailure(
                        title = "Failed for $iccid",
                        message = "$activationCode $bearer EID=$eid",
                    ),
                    logs = listOf(
                        ActivityLogEntry(
                            timestamp = Instant.EPOCH,
                            level = LogLevel.ERROR,
                            tag = "Remote-$iccid",
                            message = "https://user:password@example.com/path?matchingId=matching-secret " +
                                "$iccid confirmation code 1234 APDU 00 A4 04 00 10 A0 00 00 05 59 10 10 00",
                        ),
                    ),
                ),
            ),
        )

        listOf(
            iccid,
            eid,
            activationCode,
            "top-secret-token",
            "user:password",
            "matching-secret",
            "1234",
            "00 A4 04 00 10 A0 00 00 05 59 10 10 00",
        )
            .forEach { secret -> assertFalse("Report leaked $secret", report.contains(secret)) }
        assertFalse(report.contains("Remote-"))
        assertTrue(report.contains("category=operation failure details=[details omitted]"))
        assertTrue(report.contains("verbose_protocol_logging=no"))
        assertFalse(report.contains("apdu_logging"))
    }

    @Test
    fun reportIsBoundedEvenWithOversizedInputs() {
        val logs = (0 until 500).map { index ->
            ActivityLogEntry(
                timestamp = Instant.ofEpochMilli(index.toLong()),
                level = LogLevel.ERROR,
                tag = "tag-$index",
                message = "x".repeat(5_000),
            )
        }
        val history = (0 until 500).map { index ->
            NotificationHistoryEntry(
                timestampEpochMillis = index.toLong(),
                action = NotificationHistoryAction.SEND,
                status = NotificationHistoryStatus.FAILED,
                trigger = NotificationHistoryTrigger.AUTOMATIC,
                notificationOperation = "INSTALL",
                endpointHost = "h".repeat(5_000),
                failureCode = "rejected",
            )
        }

        val report = buildSupportReport(
            baseInput(
                repositoryState = LpaRepositoryState(logs = logs),
                notificationHistory = history,
            ),
        )

        assertTrue(report.length <= MaxSupportReportChars)
        assertTrue(report.lines().count { "level=ERROR" in it } <= MaxSupportLogEntries)
        assertTrue(report.lines().count { "action=SEND" in it } <= MaxSupportHistoryEntries)
    }

    private fun baseInput(
        repositoryState: LpaRepositoryState = LpaRepositoryState(),
        notificationHistory: List<NotificationHistoryEntry> = emptyList(),
    ) = SupportReportInput(
        generatedAt = Instant.EPOCH,
        appVersionName = "1.0",
        appVersionCode = 1,
        packageName = "app.hyperlpa",
        buildType = "debug",
        manufacturer = "Example",
        model = "Device",
        androidRelease = "16",
        androidSdk = 36,
        settings = AppSettings(),
        repositoryState = repositoryState,
        notificationHistory = notificationHistory,
        strings = testSupportReportStrings(),
    )

    private fun testSupportReportStrings() = SupportReportStrings(
        title = "HyperLPA privacy-safe support report",
        generated = "Generated: %1\$s",
        included = "Included: diagnostic data.",
        excluded = "Excluded: secrets.",
        appSection = "[App]",
        packageLine = "Package: %1\$s",
        versionLine = "Version: %1\$s (%2\$d)",
        buildTypeLine = "Build type: %1\$s",
        deviceSection = "[Device]",
        manufacturerLine = "Manufacturer: %1\$s",
        modelLine = "Model: %1\$s",
        androidLine = "Android: %1\$s (SDK %2\$d)",
        readersSection = "[Reader backends]",
        readerStatusLine = "%1\$s: enabled=%2\$s discovered=%3\$d available=%4\$d",
        connectedBackendLine = "Connected backend: %1\$s",
        flagsSection = "[Feature flags]",
        flagLine = "%1\$s=%2\$s",
        yes = "yes",
        no = "no",
        failureSection = "[Current failure]",
        failureLine = "category=%1\$s details=%2\$s",
        detailsOmitted = "[details omitted]",
        readerCategory = "reader",
        notificationHistoryCategory = "notification history",
        lpaCategory = "LPA",
        operationFailureCategory = "operation failure",
        none = "None",
        logsSection = "[Recent warning/error logs; maximum %1\$d]",
        logLine = "%1\$s level=%2\$s category=%3\$s details=%4\$s",
        historySection = "[Notification outcomes; maximum %1\$d]",
        historyLine = "%1\$s action=%2\$s status=%3\$s trigger=%4\$s operation=%5\$s",
        historyHost = " host=%1\$s",
        historyReason = " reason=%1\$s",
    )
}
