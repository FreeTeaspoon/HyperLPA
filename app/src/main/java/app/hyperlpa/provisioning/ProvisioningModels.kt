package app.hyperlpa.provisioning

import androidx.compose.runtime.Immutable
import app.hyperlpa.domain.model.DownloadRequest

const val MaxProvisioningQueueItems = 32

enum class BatchDownloadStatus {
    WAITING,
    DOWNLOADING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

enum class BatchDownloadError {
    DOWNLOAD_FAILED,
    INTERRUPTED_UNVERIFIED,
    CANCELLED_UNVERIFIED,
    OUTCOME_UNVERIFIED,
}

@Immutable
data class BatchDownloadItem(
    val index: Int,
    val address: String,
    val status: BatchDownloadStatus = BatchDownloadStatus.WAITING,
    val error: BatchDownloadError? = null,
    /** The failure message from this session, which is never persisted. */
    val detail: String? = null,
)

@Immutable
data class BatchDownloadUiState(
    val items: List<BatchDownloadItem> = emptyList(),
    val running: Boolean = false,
    val loading: Boolean = true,
    val restored: Boolean = false,
    val hasSavedQueue: Boolean = false,
    val requiresClearBeforeNewBatch: Boolean = false,
    val notice: String? = null,
) {
    val completedCount: Int
        get() = items.count { it.status == BatchDownloadStatus.SUCCEEDED }
    val failedCount: Int
        get() = items.count { it.status == BatchDownloadStatus.FAILED }
    val resumableCount: Int
        get() = items.count {
            it.status == BatchDownloadStatus.WAITING ||
                it.status == BatchDownloadStatus.CANCELLED
        }
    val retryableCount: Int
        get() = failedCount
}

/**
 * Parses one batch line. A separately supplied confirmation code follows a single `|`:
 * `LPA:1$address$matching-id$$1 | confirmation-code`.
 *
 * Keeping this grammar separate from [DownloadRequest.parse] prevents a confirmation code from
 * being mistaken for an SGP.22 activation-code field.
 */
fun parseBatchDownloadLine(rawLine: String, defaultImei: String? = null): DownloadRequest {
    requireLine(rawLine.length <= MaxBatchLineLength, BatchLineError.LINE_TOO_LONG)
    val delimiterCount = rawLine.count { it == ConfirmationDelimiter }
    requireLine(delimiterCount <= 1, BatchLineError.MULTIPLE_SEPARATORS)

    val activationCode = rawLine.substringBefore(ConfirmationDelimiter).trim()
    val confirmationCode = if (delimiterCount == 1) {
        rawLine.substringAfter(ConfirmationDelimiter).trim()
    } else {
        ""
    }
    requireLine(activationCode.isNotEmpty(), BatchLineError.ACTIVATION_CODE_MISSING)
    requireLine(confirmationCode.length <= MaxConfirmationCodeLength, BatchLineError.CONFIRMATION_CODE_TOO_LONG)

    val request = DownloadRequest.parse(activationCode, defaultImei)
    if (request.confirmationCodeRequired) {
        requireLine(confirmationCode.isNotEmpty(), BatchLineError.CONFIRMATION_CODE_MISSING)
    }
    return if (confirmationCode.isEmpty()) request else request.withConfirmationCode(confirmationCode)
}

enum class BatchLineError {
    LINE_TOO_LONG,
    MULTIPLE_SEPARATORS,
    ACTIVATION_CODE_MISSING,
    CONFIRMATION_CODE_TOO_LONG,
    CONFIRMATION_CODE_MISSING,
}

class BatchLineException(val reason: BatchLineError) : IllegalArgumentException(reason.name)

private fun requireLine(condition: Boolean, reason: BatchLineError) {
    if (!condition) throw BatchLineException(reason)
}

internal fun interruptInFlightItems(items: List<BatchDownloadItem>): List<BatchDownloadItem> =
    items.map { item ->
        if (item.status == BatchDownloadStatus.DOWNLOADING) {
            item.copy(
                status = BatchDownloadStatus.INTERRUPTED,
                error = BatchDownloadError.INTERRUPTED_UNVERIFIED,
            )
        } else {
            item
        }
    }

private const val ConfirmationDelimiter = '|'
private const val MaxBatchLineLength = 4_096
private const val MaxConfirmationCodeLength = 128
