package app.hyperlpa.domain.model

import androidx.compose.runtime.Immutable
import java.time.Instant

enum class ReaderKind {
    NBRIDGE,
    OMAPI,
    TELEPHONY,
    USB_CCID,
    BLE,
    REMOTE,
}

@Immutable
data class ReaderInfo(
    val id: String,
    val name: String,
    val kind: ReaderKind,
    val detail: String? = null,
    val available: Boolean = true,
    val eid: String? = null,
)

enum class ProfileState {
    ENABLED,
    DISABLED,
}

enum class ProfileClass {
    OPERATIONAL,
    TESTING,
    PROVISIONING,
    UNKNOWN,
}

@Immutable
data class ProfileInfo(
    val iccid: String,
    val state: ProfileState,
    val name: String,
    val nickname: String,
    val providerName: String,
    val isdPAid: String,
    val profileClass: ProfileClass,
    val iconBase64: String? = null,
    val mcc: String? = null,
    val mnc: String? = null,
    val gid1: String? = null,
    val gid2: String? = null,
    val smdpAddress: String? = null,
    val tags: Set<String> = emptySet(),
    val reminderAt: Instant? = null,
    val customIconUri: String? = null,
    val estimatedBytes: Long? = null,
    val sizeIsEstimated: Boolean = false,
)

enum class NotificationOperation {
    INSTALL,
    ENABLE,
    DISABLE,
    DELETE,
    UNKNOWN,
}

@Immutable
data class LpaNotification(
    val sequenceNumber: Long,
    val operation: NotificationOperation,
    val address: String,
    val iccid: String,
    val receivedAt: Instant = Instant.now(),
)

@Immutable
data class EuiccInfo(
    val eid: String,
    val sgp22Version: String = "",
    val profileVersion: String = "",
    val firmwareVersion: String = "",
    val globalPlatformVersion: String = "",
    val sasAccreditationNumber: String = "",
    val protectionProfileVersion: String = "",
    val freeNonVolatileMemory: Int? = null,
    val freeVolatileMemory: Int? = null,
    val signingKeyIds: Set<String> = emptySet(),
    val verificationKeyIds: Set<String> = emptySet(),
)

@Immutable
data class DownloadRequest(
    val smdpAddress: String,
    val matchingId: String? = null,
    val confirmationCode: String? = null,
    val imei: String? = null,
) {
    companion object {
        fun parse(rawValue: String, defaultImei: String? = null): DownloadRequest {
            val value = rawValue.trim()
            if (!value.startsWith("LPA:", ignoreCase = true)) {
                return DownloadRequest(smdpAddress = value, imei = defaultImei)
            }

            val fields = value.substringAfter(':').split('$')
            require(fields.firstOrNull() == "1") { "Only LPA activation code version 1 is supported" }
            val address = fields.getOrNull(1)?.trim().orEmpty()
            require(address.isNotEmpty()) { "The activation code does not contain an SM-DP+ address" }
            return DownloadRequest(
                smdpAddress = address,
                matchingId = fields.getOrNull(2)?.takeIf(String::isNotBlank),
                confirmationCode = fields.getOrNull(3)?.takeIf(String::isNotBlank),
                imei = defaultImei,
            )
        }
    }
}

sealed interface LpaOperation {
    data object Idle : LpaOperation
    data class DiscoveringReaders(val message: String = "Looking for eUICC readers") : LpaOperation
    data class Connecting(val readerName: String) : LpaOperation
    data class Refreshing(val message: String = "Reading eSIM profiles") : LpaOperation
    data class Switching(val iccid: String, val enable: Boolean) : LpaOperation
    data class Deleting(val iccid: String) : LpaOperation
    data class Renaming(val iccid: String) : LpaOperation
    data class Downloading(val stage: DownloadStage, val profileName: String? = null) : LpaOperation
    data class ProcessingNotification(val sequenceNumber: Long) : LpaOperation
    data class Resetting(val message: String = "Resetting eUICC memory") : LpaOperation
}

enum class DownloadStage {
    PREPARING,
    CONNECTING,
    AUTHENTICATING,
    CONFIRMING,
    DOWNLOADING,
    FINALIZING,
}

@Immutable
data class OperationFailure(
    val title: String,
    val message: String,
    val diagnostic: String? = null,
    val recoverable: Boolean = true,
)

@Immutable
data class ActivityLogEntry(
    val timestamp: Instant,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
}
