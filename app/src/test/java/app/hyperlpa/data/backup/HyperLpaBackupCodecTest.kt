package app.hyperlpa.data.backup

import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.PhoneFormatStrategy
import app.hyperlpa.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperLpaBackupCodecTest {
    private val sampleBackup = HyperLpaBackup(
        createdAtEpochMillis = 1_753_000_000_000,
        settings = AppSettings(
            themeMode = ThemeMode.DARK,
            phoneFormatStrategy = PhoneFormatStrategy.INTERNATIONAL_AND_ALL,
            showProfileCountryFlagOnHome = true,
            hideProfileDeletion = true,
            imei = "490154203237518",
            remoteReaderUrls = listOf("https://reader.example"),
        ),
        profiles = mapOf(
            "8944000000000000000" to BackupProfileMetadata(
                tags = setOf("Travel", "Data"),
                reminderEpochMillis = 1_800_000_000_000,
                reminderLabel = "Travel data profile",
                smdpAddress = "smdp.example",
                installedBytes = 524_288,
                installedEid = "89049032000000000000000000000000",
                providerKey = "example mobile",
                isPinned = true,
                iconBase64 = "AQID",
                isProviderIconHidden = true,
            ),
        ),
        providerIcons = mapOf("example mobile" to "BAUG"),
        euiccNames = mapOf("89049032000000000000000000000000" to "Travel card"),
    )

    @Test
    fun roundTripPreservesSettingsMetadataAndIcons() {
        assertEquals(sampleBackup, decodeBackup(encodeBackup(sampleBackup)))
    }

    @Test
    fun encryptedRoundTripRequiresMatchingPassword() {
        val password = "correct horse battery staple".toCharArray()
        val encrypted = encryptBackup(sampleBackup, password)

        assertTrue(isEncryptedBackup(encrypted))
        assertEquals(sampleBackup, decryptBackup(encrypted, password))
        assertThrows(InvalidBackupPassphraseException::class.java) {
            decryptBackup(encrypted, "definitely the wrong password".toCharArray())
        }
    }

    @Test
    fun encryptedBackupRejectsTampering() {
        val password = "correct horse battery staple".toCharArray()
        val encrypted = encryptBackup(sampleBackup, password)
        val marker = "\"ciphertextBase64\": \""
        val valueStart = encrypted.indexOf(marker) + marker.length
        val replacement = if (encrypted[valueStart] == 'A') 'B' else 'A'
        val tampered = encrypted.replaceRange(valueStart, valueStart + 1, replacement.toString())

        assertFalse(tampered == encrypted)
        assertThrows(InvalidBackupPassphraseException::class.java) {
            decryptBackup(tampered, password)
        }
    }

    @Test
    fun serializedSettingsNeverContainRuntimeRemoteReaderCredentials() {
        val secret = "top-secret-bearer-token"
        val backup = sampleBackup.copy(
            settings = sampleBackup.settings.copy(
                remoteReaderTokens = mapOf("https://reader.example" to secret),
            ),
        )

        val encoded = encodeBackup(backup)

        assertFalse(encoded.contains(secret))
        assertTrue(decodeBackup(encoded).settings.remoteReaderTokens.isEmpty())
    }

    @Test
    fun restorePreservesPortableSettingsAndDropsDeviceOnlyState() {
        val settings = sampleBackup.settings.copy(
            autoLoadProfiles = true,
            autoLoadRemoteReaders = true,
            enableNBridge = true,
            enableOmapi = true,
            enableTelephony = true,
            enableUsbCcid = true,
            enableBle = true,
            enableRemote = true,
            notificationAutoSend = true,
            notificationAutoRemove = true,
            scheduledReminders = true,
            loadOperatorIcons = true,
            estimateProfileSize = true,
            apduLogging = true,
            developerMode = true,
            lastReaderId = "source-device-reader",
            remoteReaderTokens = mapOf("https://reader.example" to "runtime-token"),
        )

        val restored = settings.forRestore()

        assertEquals(
            settings.copy(
                lastReaderId = null,
                remoteReaderTokens = emptyMap(),
            ),
            restored,
        )
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

    @Test
    fun rejectsNonCanonicalReminderLabels() {
        val invalid = sampleBackup.copy(
            profiles = sampleBackup.profiles.mapValues { (_, metadata) ->
                metadata.copy(reminderLabel = "  padded label  ")
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            decodeBackup(encodeBackup(invalid))
        }
    }
}
