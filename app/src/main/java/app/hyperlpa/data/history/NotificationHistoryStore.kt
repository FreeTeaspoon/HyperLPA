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
    /** The profile label captured at the time of the notification, when available. */
    val profileName: String? = null,
    /** The provider label captured at the time of the notification, when available. */
    val providerName: String? = null,
    /** The eUICC identity captured at the time of the notification, when available. */
    val eid: String? = null,
    /** The full ICCID captured with the notification. */
    val iccid: String? = null,
    /** Legacy masked field retained so history written by the previous build remains readable. */
    val redactedIccid: String? = null,
    /** A sanitized notification origin without credentials, paths, queries or fragments. */
    val notificationAddress: String? = null,
    val sequenceNumber: Long? = null,
    /** Base64 signed notification content retained for Nekoko-style resend after card removal. */
    val pendingNotificationPayload: String? = null,
) {
    val timestamp: Instant
        get() = Instant.ofEpochMilli(timestampEpochMillis)
}

/**
 * A small, durable audit trail for notification actions.
 *
 * The file deliberately lives under [Context.getNoBackupFilesDir]. It contains the full ICCID,
 * EID, bounded profile/provider labels, a sequence number, a sanitized notification origin, and
 * a bounded signed notification payload for resend; it never contains an activation code,
 * reader credential or full notification URL. It is written through [AtomicFile] so a process
 * interruption cannot leave a partially-written document.
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
        profileName: String? = null,
        providerName: String? = null,
        eid: String? = null,
        iccid: String? = null,
        sequenceNumber: Long? = null,
        pendingNotificationPayload: String? = null,
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
                profileName = sanitizeHistoryLabel(profileName),
                providerName = sanitizeHistoryLabel(providerName),
                eid = sanitizeNotificationEid(eid),
                iccid = sanitizeNotificationIccid(iccid),
                notificationAddress = sanitizeNotificationAddress(endpointAddress),
                sequenceNumber = sequenceNumber?.takeIf { it >= 0 },
                pendingNotificationPayload = sanitizePendingNotificationPayload(pendingNotificationPayload),
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

    suspend fun delete(entry: NotificationHistoryEntry): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoadedLocked()
            val index = mutableHistory.value.indexOfFirst { candidate ->
                candidate === entry
            }.takeIf { it >= 0 }
                ?: mutableHistory.value.indexOf(entry).takeIf { it >= 0 }
                ?: return@withLock false
            val updated = mutableHistory.value.toMutableList().apply { removeAt(index) }
            writeToDisk(updated)
            mutableHistory.value = updated
            true
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
                        profileName = sanitizeHistoryLabel(entry.profileName),
                        providerName = sanitizeHistoryLabel(entry.providerName),
                        eid = sanitizeNotificationEid(entry.eid),
                        iccid = sanitizeNotificationIccid(entry.iccid),
                        redactedIccid = sanitizeRedactedIccid(entry.redactedIccid),
                        notificationAddress = sanitizeNotificationAddress(entry.notificationAddress),
                        sequenceNumber = entry.sequenceNumber?.takeIf { it >= 0 },
                        pendingNotificationPayload = sanitizePendingNotificationPayload(
                            entry.pendingNotificationPayload,
                        ),
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
        const val MaxHistoryFileBytes = 8 * 1024 * 1024
        val AllowedOperations = NotificationOperation.entries.mapTo(mutableSetOf(), NotificationOperation::name)
    }
}

@Serializable
private data class NotificationHistoryDocument(
    val version: Int,
    val entries: List<NotificationHistoryEntry>,
)

internal const val MaxNotificationHistoryEntries = 1_000
private const val MaxFailureCodeLength = 48
private const val MaxHistoryLabelLength = 256
private const val MaxIccidLength = 64
private const val MaxRedactedIccidLength = 64
private const val MaxEidLength = 32
private const val MaxPendingNotificationPayloadLength = 256 * 1024

internal fun boundedHistory(entries: List<NotificationHistoryEntry>): List<NotificationHistoryEntry> =
    entries.takeLast(MaxNotificationHistoryEntries)

internal fun sanitizeNotificationHost(address: String?): String? {
    return parseNotificationUri(address)
        ?.let { normalizeNotificationHost(it.host) }
}

/**
 * Keeps the useful server origin while dropping credentials and anything that could contain a
 * notification token. This intentionally does not preserve a path, query, or fragment.
 */
internal fun sanitizeNotificationAddress(address: String?): String? {
    val uri = parseNotificationUri(address) ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
        ?.takeIf { it == "https" || it == "http" }
        ?: return null
    val host = normalizeNotificationHost(uri.host) ?: return null
    val port = when {
        uri.port == -1 -> null
        uri.port in 1..65_535 -> uri.port
        else -> return null
    }
    return buildString {
        append(scheme)
        append("://")
        append(host)
        port?.let {
            append(':')
            append(it)
        }
    }
}

private fun sanitizeHistoryLabel(value: String?): String? = value
    ?.trim()
    ?.take(MaxHistoryLabelLength)
    ?.takeIf(String::isNotEmpty)

internal fun sanitizeNotificationIccid(value: String?): String? = value
    ?.filter { it.isDigit() }
    ?.take(MaxIccidLength)
    ?.takeIf(String::isNotEmpty)

internal fun sanitizeNotificationEid(value: String?): String? = value
    ?.trim()
    ?.take(MaxEidLength)
    ?.takeIf { candidate -> candidate.length == MaxEidLength && candidate.all(Char::isDigit) }

internal fun sanitizePendingNotificationPayload(value: String?): String? = value
    ?.trim()
    ?.take(MaxPendingNotificationPayloadLength)
    ?.takeIf { candidate ->
        candidate.isNotEmpty() &&
            candidate.length % 4 == 0 &&
            candidate.all { character ->
                character in 'A'..'Z' ||
                    character in 'a'..'z' ||
                    character in '0'..'9' ||
                    character == '+' ||
                    character == '/' ||
                    character == '='
            }
    }

private fun sanitizeRedactedIccid(value: String?): String? = value
    ?.trim()
    ?.filter { it.isDigit() || it == '•' || it == '*' }
    ?.take(MaxRedactedIccidLength)
    ?.takeIf(String::isNotEmpty)

private fun parseNotificationUri(address: String?): URI? {
    val value = address?.trim()?.takeIf(String::isNotEmpty)?.take(2_048) ?: return null
    return runCatching {
        URI(if ("://" in value) value else "https://$value")
    }.getOrNull()
}

private fun normalizeNotificationHost(rawHost: String?): String? = runCatching {
    val ascii = IDN.toASCII(rawHost?.trim()?.trimEnd('.') ?: return@runCatching null,
        IDN.USE_STD3_ASCII_RULES,
    ).lowercase(Locale.ROOT)
    require(ascii.length in 1..253)
    require(ascii.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' })
    ascii
}.getOrNull()?.takeIf(String::isNotBlank)
