package app.hyperlpa.data

import androidx.compose.runtime.Immutable
import app.hyperlpa.domain.model.*
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class LpaRepositoryState(
    val readers: List<ReaderInfo> = emptyList(),
    val selectedReaderId: String? = null,
    val profiles: List<ProfileInfo> = emptyList(),
    val notifications: List<LpaNotification> = emptyList(),
    val euiccInfo: EuiccInfo? = null,
    val pendingProfileDownload: ProfileDownloadPreview? = null,
    val completedProfileDownload: ProfileDownloadResult? = null,
    val discoveredSmdpAddresses: List<String> = emptyList(),
    val operation: LpaOperation = LpaOperation.Idle,
    val failure: OperationFailure? = null,
    val initialized: Boolean = false,
    val logs: List<ActivityLogEntry> = emptyList(),
    val readerSnapshotPendingRefresh: Boolean = false,
) {
    val selectedReader: ReaderInfo?
        get() = readers.firstOrNull { it.id == selectedReaderId }
}

