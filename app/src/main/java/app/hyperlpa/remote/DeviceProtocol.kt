package app.hyperlpa.remote

import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.history.NotificationHistoryEntry
import app.hyperlpa.data.metadata.ProfileMetadata
import app.hyperlpa.domain.model.DownloadRequest
import app.hyperlpa.domain.model.OperationOutcome
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant
import java.util.UUID

internal val DeviceJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
internal const val DeviceFrameLimit = 1_500_000
internal const val DeviceMessageLifetime = 120_000L
internal fun newDeviceId(): String = UUID.randomUUID().toString()

object DeviceInstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

internal fun relayAddress(value: String): String {
    val uri = URI(value.trim().trimEnd('/'))
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty() &&
        (uri.port == -1 || uri.port in 1..65535)) { "Use an HTTPS relay address without a path or credentials" }
    return uri.toASCIIString()
}

@Serializable
internal data class DeviceEnvelope(
    val v: Int = 1,
    val id: String,
    val from: String,
    val to: String,
    val pair: String,
    val issued: Long,
    val expires: Long,
    val nonce: String,
    val body: String,
)

@Serializable
internal data class RelayFrame(
    val type: String,
    val message: DeviceEnvelope? = null,
    val id: String? = null,
    val code: String? = null,
)

@Serializable
internal data class PairInvitation(
    val version: Int = 1,
    val relay: String,
    val device: String,
    val name: String,
    val pair: String,
    val secret: String,
    val expires: Long,
)

@Serializable
internal data class PairedDevice(
    val id: String,
    val name: String,
    val pair: String,
    val secret: String,
    val approved: Boolean = false,
    val incoming: Boolean = false,
)

@Serializable
internal enum class DeviceAction {
    READERS, REFRESH, SWITCH, DELETE, RENAME, DOWNLOAD, PROCESS_NOTIFICATION,
    DELETE_NOTIFICATION, RESEND_NOTIFICATION, DELETE_HISTORY, RESET, SET_SMDP, DISCOVER,
    TAGS, PIN, EUICC_NAME, REMINDER, ICON, HIDE_PROVIDER_ICON, APPLY_PROVIDER_ICON,
}

@Serializable
internal data class DeviceCommand(
    val action: DeviceAction,
    val readerId: String = "",
    val eid: String = "",
    val iccid: String = "",
    val enabled: Boolean = false,
    val text: String? = null,
    val number: Long? = null,
    val tags: Set<String> = emptySet(),
    val download: DownloadRequest? = null,
    val history: NotificationHistoryEntry? = null,
    val confirmed: Boolean = false,
    val confirmBeforeInstall: Boolean = true,
    val applyToProvider: Boolean = false,
    val image: String? = null,
)

@Serializable
internal data class DeviceSnapshot(
    val lpa: LpaRepositoryState = LpaRepositoryState(),
    val metadata: Map<String, ProfileMetadata> = emptyMap(),
    val providerIcons: Map<String, String> = emptyMap(),
    val euiccNames: Map<String, String> = emptyMap(),
    val history: List<NotificationHistoryEntry> = emptyList(),
    val images: Map<String, String> = emptyMap(),
    val artworkIncluded: Boolean = true,
)

@Serializable
internal data class DeviceMessage(
    val kind: String,
    val requestId: String = "",
    val name: String = "",
    val command: DeviceCommand? = null,
    val snapshot: DeviceSnapshot? = null,
    val outcome: OperationOutcome? = null,
    val done: Boolean = false,
    val confirmed: Boolean = false,
    val revision: Long = 0,
    val downloadResult: app.hyperlpa.domain.model.ProfileDownloadResult? = null,
    val part: DevicePart? = null,
)

@Serializable
internal data class DevicePart(val transfer: String, val index: Int, val count: Int, val data: String)

@Serializable
internal data class DeviceOperationRecord(
    val peer: String,
    val id: String,
    val fingerprint: String,
    val started: Long,
    val readerId: String,
    val eid: String,
    val result: DeviceMessage? = null,
)
