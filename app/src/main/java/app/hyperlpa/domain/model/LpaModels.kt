package app.hyperlpa.domain.model

import androidx.compose.runtime.Immutable
import java.math.BigInteger
import java.net.URI
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
    val notificationOperations: Set<String> = emptySet(),
    val dpOid: String? = null,
    val profilePolicyRules: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val isPinned: Boolean = false,
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
    val installedApplicationCount: Int? = null,
    val uiccCapabilities: Set<String> = emptySet(),
    val ts102241Version: String = "",
    val rspCapabilities: Set<String> = emptySet(),
    val euiccCategory: String = "",
    val forbiddenProfilePolicyRules: Set<String> = emptySet(),
    val platformLabel: String = "",
    val discoveryBaseUrl: String = "",
    val defaultSmdpAddress: String = "",
    val rootSmdsAddress: String = "",
    val refreshedAt: Instant = Instant.now(),
)

@Immutable
data class DownloadRequest(
    val smdpAddress: String,
    val matchingId: String? = null,
    val smdpOid: String? = null,
    val confirmationCodeRequired: Boolean = false,
    /**
     * A confirmation code entered by the user. This is deliberately not populated from the
     * activation-code string: the fourth and fifth activation-code fields are the SM-DP+ OID and
     * confirmation-code-required flag respectively.
     */
    val confirmationCode: String? = null,
    val imei: String? = null,
) {
    val hasRequiredConfirmationCode: Boolean
        get() = !confirmationCodeRequired || !confirmationCode.isNullOrBlank()

    fun withConfirmationCode(value: String?): DownloadRequest {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)
        requireDownload(
            normalized == null || normalized.length <= MaximumConfirmationCodeCharacters,
            DownloadRequestError.CONFIRMATION_CODE_TOO_LONG,
        )
        requireDownload(
            normalized == null || normalized.none(Char::isISOControl),
            DownloadRequestError.CONFIRMATION_CODE_INVALID,
        )
        return copy(confirmationCode = normalized)
    }

    companion object {
        fun parse(rawValue: String, defaultImei: String? = null): DownloadRequest {
            val value = rawValue.trim()
            val qrPrefixed = value.startsWith("LPA:", ignoreCase = true)
            if (!qrPrefixed && '$' !in value) {
                requireDownload(value.isNotEmpty(), DownloadRequestError.SMDP_ADDRESS_REQUIRED)
                return DownloadRequest(smdpAddress = normalizeRspServerAddress(value), imei = defaultImei)
            }

            val encodedActivationCode = if (qrPrefixed) value.substringAfter(':') else value
            requireDownload(
                encodedActivationCode.length <= MaximumActivationCodeCharacters,
                DownloadRequestError.ACTIVATION_CODE_TOO_LONG,
            )
            val fields = encodedActivationCode.split('$').map { field -> field.trim() }
            requireDownload(fields.size >= 3, DownloadRequestError.ACTIVATION_CODE_FIELDS)
            requireDownload(fields.firstOrNull() == "1", DownloadRequestError.ACTIVATION_CODE_VERSION)
            val address = fields.getOrNull(1).orEmpty()
            requireDownload(address.isNotEmpty(), DownloadRequestError.ACTIVATION_CODE_ADDRESS_MISSING)
            // MatchingID is mandatory for an activation code but is explicitly
            // allowed to be zero length. Preserve "" rather than treating it as
            // the absent value used by a default SM-DP+ download.
            val matchingId = fields[2]
            requireDownload(
                matchingId.length <= MaximumMatchingIdCharacters,
                DownloadRequestError.MATCHING_ID_TOO_LONG,
            )
            requireDownload(
                matchingId.none(Char::isISOControl),
                DownloadRequestError.MATCHING_ID_INVALID,
            )
            val smdpOid = fields.getOrNull(3)
                ?.takeIf(String::isNotEmpty)
                ?.let(::normalizeSmdpOid)
            val confirmationRequired = fields.getOrNull(4).orEmpty()
            requireDownload(
                confirmationRequired.isEmpty() || confirmationRequired == "1",
                DownloadRequestError.CONFIRMATION_FLAG_INVALID,
            )
            return DownloadRequest(
                smdpAddress = normalizeRspServerAddress(address),
                matchingId = matchingId,
                smdpOid = smdpOid,
                confirmationCodeRequired = confirmationRequired == "1",
                imei = defaultImei,
            )
        }
    }
}

enum class DownloadRequestError {
    CONFIRMATION_CODE_TOO_LONG,
    CONFIRMATION_CODE_INVALID,
    SMDP_ADDRESS_REQUIRED,
    ACTIVATION_CODE_TOO_LONG,
    ACTIVATION_CODE_FIELDS,
    ACTIVATION_CODE_VERSION,
    ACTIVATION_CODE_ADDRESS_MISSING,
    MATCHING_ID_TOO_LONG,
    MATCHING_ID_INVALID,
    SMDP_OID_INVALID,
    CONFIRMATION_FLAG_INVALID,
    RSP_ADDRESS_REQUIRED,
    RSP_ADDRESS_TOO_LONG,
    RSP_ADDRESS_HAS_SCHEME,
    RSP_ADDRESS_WHITESPACE,
    RSP_ADDRESS_UNSUPPORTED_CHARACTERS,
    RSP_ADDRESS_INVALID,
    RSP_PORT_INVALID,
}

class DownloadRequestException(
    val reason: DownloadRequestError,
    cause: Throwable? = null,
) : IllegalArgumentException(reason.name, cause)

private const val MaximumActivationCodeCharacters = 255
private const val MaximumMatchingIdCharacters = 1_024
private const val MaximumSmdpOidCharacters = 256
private const val MaximumConfirmationCodeCharacters = 128

private fun requireDownload(condition: Boolean, reason: DownloadRequestError) {
    if (!condition) throw DownloadRequestException(reason)
}

private fun normalizeSmdpOid(value: String): String {
    requireDownload(value.length in 3..MaximumSmdpOidCharacters, DownloadRequestError.SMDP_OID_INVALID)
    val arcs = value.split('.')
    requireDownload(
        arcs.size >= 2 && arcs.none(String::isEmpty),
        DownloadRequestError.SMDP_OID_INVALID,
    )
    val values = arcs.map { arc ->
        requireDownload(
            arc.length <= 78 && arc.all(Char::isDigit),
            DownloadRequestError.SMDP_OID_INVALID,
        )
        BigInteger(arc)
    }
    val two = BigInteger.valueOf(2)
    requireDownload(values.first() <= two, DownloadRequestError.SMDP_OID_INVALID)
    requireDownload(
        values.first() == two || values[1] <= BigInteger.valueOf(39),
        DownloadRequestError.SMDP_OID_INVALID,
    )
    return values.joinToString(".")
}

internal fun normalizeRspServerAddress(value: String): String {
    val normalized = value.trim()
    requireDownload(normalized.isNotEmpty(), DownloadRequestError.RSP_ADDRESS_REQUIRED)
    requireDownload(normalized.length <= 253, DownloadRequestError.RSP_ADDRESS_TOO_LONG)
    requireDownload("://" !in normalized, DownloadRequestError.RSP_ADDRESS_HAS_SCHEME)
    requireDownload(
        normalized.none { it.isWhitespace() || it.isISOControl() },
        DownloadRequestError.RSP_ADDRESS_WHITESPACE,
    )
    requireDownload(
        normalized.none { it == '/' || it == '\\' || it == '?' || it == '#' || it == '$' || it == '@' },
        DownloadRequestError.RSP_ADDRESS_UNSUPPORTED_CHARACTERS,
    )
    val uri = runCatching { URI("https://$normalized") }
        .getOrElse { throw DownloadRequestException(DownloadRequestError.RSP_ADDRESS_INVALID, it) }
    requireDownload(
        !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawPath.isNullOrEmpty(),
        DownloadRequestError.RSP_ADDRESS_INVALID,
    )
    requireDownload(uri.port == -1 || uri.port in 1..65_535, DownloadRequestError.RSP_PORT_INVALID)
    return normalized
}

@Immutable
data class ProfileDownloadPreview(
    val profile: ProfileInfo,
    val request: DownloadRequest,
    val freeNonVolatileMemory: Int? = null,
)

@Immutable
data class ProfileDownloadResult(
    val profile: ProfileInfo,
    val installedBytes: Long? = null,
    val freeNonVolatileMemory: Int? = null,
)

sealed interface LpaOperation {
    data object Idle : LpaOperation
    data class DiscoveringReaders(val message: String) : LpaOperation
    data class Connecting(val readerName: String) : LpaOperation
    data class Refreshing(val message: String) : LpaOperation
    data class Switching(val iccid: String, val enable: Boolean) : LpaOperation
    data class Deleting(val iccid: String) : LpaOperation
    data class Renaming(val iccid: String) : LpaOperation
    data class Downloading(
        val stage: DownloadStage,
        val profileName: String? = null,
        val sentBytes: Long? = null,
        val totalBytes: Long? = null,
    ) : LpaOperation
    data class ProcessingNotification(val sequenceNumber: Long) : LpaOperation
    data class Resetting(val message: String) : LpaOperation
}

enum class DownloadStage {
    PREPARING,
    CONNECTING,
    AUTHENTICATING,
    CONFIRMING,
    DOWNLOADING,
    FINALIZING,
    INSTALLING,
}

@Immutable
data class OperationFailure(
    val title: String,
    val message: String,
    val diagnostic: String? = null,
    val recoverable: Boolean = true,
)

sealed interface OperationOutcome {
    data object Success : OperationOutcome
    data class Failed(val failure: OperationFailure) : OperationOutcome
    /** A card-changing command may have completed, but authoritative refresh was unavailable. */
    data class Unverified(val failure: OperationFailure) : OperationOutcome
}

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

/** Truncates without splitting a UTF-16 surrogate pair or under-counting non-BMP characters. */
internal fun String.takeUnicodeCodePoints(maxCodePoints: Int): String {
    require(maxCodePoints >= 0)
    if (codePointCount(0, length) <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints))
}
