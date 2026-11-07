package app.hyperlpa.remote

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

/** Saved remote messages and local dispositions live in the encrypted device journal. */
@Serializable
internal data class PhoneInboxDevice(
    val entries: List<PhoneNotificationEntry> = emptyList(),
    @kotlinx.serialization.SerialName("readVersions")
    val acknowledgedVersions: Map<String, String> = emptyMap(),
    val seenVersions: Map<String, String> = emptyMap(),
    val available: Boolean = false,
    val loaded: Boolean = false,
    val dispositions: Map<String, PhoneInboxDisposition> = emptyMap(),
) {
    @kotlinx.serialization.Transient
    private val versions = entries.associate { it.id to it.inboxVersion() }

    fun isArchived(entry: PhoneNotificationEntry): Boolean =
        dispositions[entry.id]?.let { it.state == PhoneInboxMessageState.ARCHIVED && it.version == entry.inboxVersion() } == true

    fun isNew(entry: PhoneNotificationEntry): Boolean = entry.id in versions && !isArchived(entry) &&
        acknowledgedVersions[entry.id] != entry.inboxVersion()

    fun snapshot(incoming: List<PhoneNotificationEntry>, available: Boolean, now: Long): PhoneInboxDevice {
        if (!available) return PhoneInboxDevice()
        val source = incoming.distinctBy { it.id }.filter { it.timestamp > now - PhoneInboxRetentionMillis }
            .sortedByDescending { it.timestamp }.take(150).map { it.copy(notificationKey = "") }
        val sourceIds = source.map { it.id }.toSet()
        val retained = dispositions.filterValues { it.timestamp > now - PhoneInboxRetentionMillis }
            .entries.sortedByDescending { it.value.timestamp }.take(150).associate { it.toPair() }
        val archived = entries.filter { it.id !in sourceIds && isArchived(it) && it.timestamp > now - PhoneInboxRetentionMillis }
        val saved = (source + archived).filter { entry ->
            retained[entry.id]?.let { it.state == PhoneInboxMessageState.DELETED && it.version == entry.inboxVersion() } != true
        }.sortedByDescending { it.timestamp }.take(150)
        return copy(entries = saved, acknowledgedVersions = acknowledgedVersions.filterKeys { it in sourceIds || saved.any { entry -> entry.id == it } },
            seenVersions = source.associate { it.id to it.inboxVersion() }, available = true, loaded = true,
            dispositions = retained)
    }

    /** Opening Notifications clears its arrival indicator without changing the message list. */
    fun acknowledgeArrivals(receipts: Map<String, String> = seenVersions): PhoneInboxDevice = copy(
        acknowledgedVersions = acknowledgedVersions + receipts.filter { (id, version) -> seenVersions[id] == version })

    fun archive(entry: PhoneNotificationEntry): PhoneInboxDevice = dispose(entry, PhoneInboxMessageState.ARCHIVED)
    fun delete(entry: PhoneNotificationEntry): PhoneInboxDevice = dispose(entry, PhoneInboxMessageState.DELETED)

    fun restore(entry: PhoneNotificationEntry): PhoneInboxDevice =
        if (versions[entry.id] == entry.inboxVersion()) copy(dispositions = dispositions - entry.id) else this

    private fun dispose(entry: PhoneNotificationEntry, state: PhoneInboxMessageState): PhoneInboxDevice {
        val version = entry.inboxVersion()
        if (versions[entry.id] != version) return this
        return copy(entries = if (state == PhoneInboxMessageState.DELETED) entries.filterNot { it.id == entry.id } else entries,
            dispositions = (dispositions + (entry.id to PhoneInboxDisposition(state, version, entry.timestamp)))
                .entries.sortedByDescending { it.value.timestamp }.take(150).associate { it.toPair() },
            acknowledgedVersions = acknowledgedVersions + (entry.id to version))
    }

    /** Initial history must never generate a burst of old Android alerts. */
    fun arrivals(next: PhoneInboxDevice): List<PhoneNotificationEntry> {
        if (!loaded || !next.available) return emptyList()
        val previous = seenVersions.ifEmpty { versions }
        return next.entries.filter { !next.isArchived(it) && previous[it.id] != it.inboxVersion() }
    }
}

@Serializable
internal enum class PhoneInboxMessageState { ARCHIVED, DELETED }

@Serializable
internal data class PhoneInboxDisposition(val state: PhoneInboxMessageState, val version: String, val timestamp: Long)

internal const val PhoneInboxRetentionMillis = 7 * 24 * 60 * 60 * 1000L

internal fun PhoneNotificationEntry.inboxVersion(): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
    .digest(DeviceJson.encodeToString(PhoneNotificationEntry.serializer(),
        copy(timestamp = 0, notificationKey = "")).toByteArray()))

/** Bound the encrypted journal even when a controller is paired with many busy phones. */
internal fun boundedPhoneInbox(inbox: Map<String, PhoneInboxDevice>, now: Long): Map<String, PhoneInboxDevice> {
    val retained = inbox.flatMap { (device, history) -> history.entries.map { device to it } }
        .filter { it.second.timestamp > now - PhoneInboxRetentionMillis }
        .sortedByDescending { it.second.timestamp }.take(600)
        .groupBy({ it.first }, { it.second })
    return inbox.mapValues { (id, history) ->
        val entries = retained[id].orEmpty()
        // Arrival receipts and dispositions survive eviction of older message bodies.
        history.copy(entries = entries)
    }
}

/** Owners and group-provisioned peers are browser controllers, not notification hosts. */
internal fun PairedDevice.canHostPhoneNotifications(): Boolean = approved && !groupOwner && managedBy.isEmpty()

internal data class PhoneInboxRefreshFailure(val deviceId: String?, val message: String)
