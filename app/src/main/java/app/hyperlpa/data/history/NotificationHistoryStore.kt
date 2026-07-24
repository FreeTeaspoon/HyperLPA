package app.hyperlpa.data.history

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.Immutable
import app.hyperlpa.domain.model.NotificationOperation
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.IDN
import java.net.URI
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class NotificationHistoryAction {
    SEND,
    DELETE,
}

@Serializable
enum class NotificationHistoryStatus {
    SUCCEEDED,
    FAILED,
}

@Serializable
enum class NotificationHistoryTrigger {
    MANUAL,
    AUTOMATIC,
}

@Serializable
@Immutable
data class NotificationHistoryEntry(
    val timestampEpochMillis: Long,
    val action: NotificationHistoryAction,
    val status: NotificationHistoryStatus,
    val trigger: NotificationHistoryTrigger,
    val notificationOperation: String,
    val endpointHost: String? = null,
    /** A short, controlled diagnostic code. Never a raw exception or server message. */
    val failureCode: String? = null,
) {
    val timestamp: Instant
        get() = Instant.ofEpochMilli(timestampEpochMillis)
}

/**
 * A small, durable audit trail for notification actions.
 *
 * The file deliberately lives under [Context.getNoBackupFilesDir], contains no ICCID, EID,
 * activation code, sequence number, reader credential or full notification URL, and is written
 * through [AtomicFile] so a process interruption cannot leave a partially-written document.
 */
class NotificationHistoryStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, HistoryFileName))
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val mutableHistory = MutableStateFlow<List<NotificationHistoryEntry>>(emptyList())
    private var initialized = false

    val history: StateFlow<List<NotificationHistoryEntry>> = mutableHistory.asStateFlow()

    suspend fun initialize() = withContext(ioDispatcher) {
        mutex.withLock { ensureLoadedLocked() }
    }

    suspend fun record(
        action: NotificationHistoryAction,
        status: NotificationHistoryStatus,
        trigger: NotificationHistoryTrigger,
        notificationOperation: NotificationOperation,
        endpointAddress: String?,
        failureCode: String? = null,
        timestamp: Instant = Instant.now(),
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoadedLocked()
            val entry = NotificationHistoryEntry(
                timestampEpochMillis = timestamp.toEpochMilli().coerceAtLeast(0),
                action = action,
                status = status,
                trigger = trigger,
                notificationOperation = notificationOperation.name,
                endpointHost = sanitizeNotificationHost(endpointAddress),
                failureCode = failureCode
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                    ?.take(MaxFailureCodeLength)
                    ?.takeIf(String::isNotEmpty),
            )
            val updated = boundedHistory(mutableHistory.value + entry)
            writeToDisk(updated)
            mutableHistory.value = updated
        }
    }

    suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoadedLocked()
            writeToDisk(emptyList())
            mutableHistory.value = emptyList()
        }
    }

    private fun ensureLoadedLocked() {
        if (initialized) return
        mutableHistory.value = loadFromDisk()
        initialized = true
    }

    private fun loadFromDisk(): List<NotificationHistoryEntry> {
        // AtomicFile.openRead() restores a pending .bak automatically, including the
        // crash-recovery case where only the backup remains. Do not short-circuit on
        // a missing base file while that recovery artifact exists.
        val backupFile = File("${file.baseFile.path}.bak")
        if (!file.baseFile.isFile && !backupFile.isFile) return emptyList()
        return runCatching {
            val bytes = file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MaxHistoryFileBytes) {
                        "Notification history is too large"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val document = json.decodeFromString<NotificationHistoryDocument>(bytes.decodeToString())
            require(document.version == HistoryVersion) { "Unsupported notification history version" }
            boundedHistory(
                document.entries.map { entry ->
                    entry.copy(
                        timestampEpochMillis = entry.timestampEpochMillis.coerceAtLeast(0),
                        notificationOperation = entry.notificationOperation
                            .takeIf(AllowedOperations::contains)
                            ?: NotificationOperation.UNKNOWN.name,
                        endpointHost = sanitizeNotificationHost(entry.endpointHost),
                        failureCode = entry.failureCode
                            ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                            ?.take(MaxFailureCodeLength)
                            ?.takeIf(String::isNotEmpty),
                    )
                },
            )
        }.getOrDefault(emptyList())
    }

    private fun writeToDisk(entries: List<NotificationHistoryEntry>) {
        val bytes = json.encodeToString(
            NotificationHistoryDocument(version = HistoryVersion, entries = entries),
        ).encodeToByteArray()
        check(bytes.size <= MaxHistoryFileBytes) { "Notification history exceeded its storage bound" }
        var output = file.startWrite()
        try {
            output.write(bytes)
            output.flush()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private companion object {
        const val HistoryFileName = "notification-history-v1.json"
        const val HistoryVersion = 1
        const val MaxHistoryFileBytes = 256 * 1024
        val AllowedOperations = NotificationOperation.entries.mapTo(mutableSetOf(), NotificationOperation::name)
    }
}

@Serializable
private data class NotificationHistoryDocument(
    val version: Int,
    val entries: List<NotificationHistoryEntry>,
)

internal const val MaxNotificationHistoryEntries = 200
private const val MaxFailureCodeLength = 48

internal fun boundedHistory(entries: List<NotificationHistoryEntry>): List<NotificationHistoryEntry> =
    entries.takeLast(MaxNotificationHistoryEntries)

internal fun sanitizeNotificationHost(address: String?): String? {
    val value = address?.trim()?.takeIf(String::isNotEmpty)?.take(2_048) ?: return null
    val parsed = runCatching {
        val uri = URI(if ("://" in value) value else "https://$value")
        require(uri.userInfo == null || uri.host != null)
        val rawHost = uri.host ?: return@runCatching null
        val ascii = IDN.toASCII(rawHost.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
            .lowercase(Locale.ROOT)
        require(ascii.length in 1..253)
        require(ascii.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' })
        ascii
    }.getOrNull()
    return parsed?.takeIf(String::isNotBlank)
}
