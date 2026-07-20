package app.hyperlpa.data.backup

import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.PhoneFormatStrategy
import app.hyperlpa.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HyperLpaBackupCodecTest {
    @Test
    fun roundTripPreservesSettingsMetadataAndIcons() {
        val backup = HyperLpaBackup(
            createdAtEpochMillis = 1_753_000_000_000,
            settings = AppSettings(
                themeMode = ThemeMode.DARK,
                phoneFormatStrategy = PhoneFormatStrategy.INTERNATIONAL_AND_ALL,
                hideProfileDeletion = true,
                imei = "490154203237518",
                remoteReaderUrls = listOf("wss://reader.example"),
            ),
            profiles = mapOf(
                "8944000000000000000" to BackupProfileMetadata(
                    tags = setOf("Travel", "Data"),
                    reminderEpochMillis = 1_800_000_000_000,
                    smdpAddress = "smdp.example",
                    installedBytes = 524_288,
                    installedEid = "89049032000000000000000000000000",
                    iconBase64 = "AQID",
                ),
            ),
            providerIcons = mapOf("example mobile" to "BAUG"),
        )

        assertEquals(backup, decodeBackup(encodeBackup(backup)))
    }

    @Test
    fun rejectsDocumentsThatAreNotHyperLpaBackups() {
        val invalid = encodeBackup(
            HyperLpaBackup(
                format = "different-app",
                createdAtEpochMillis = 0,
                settings = AppSettings(),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) { decodeBackup(invalid) }
    }
}
