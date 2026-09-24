package app.hyperlpa.remote

import android.content.Context
import android.os.Build
import app.hyperlpa.R
import app.hyperlpa.data.LpaRepository
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.ReaderAffinity
import app.hyperlpa.data.history.NotificationHistoryStore
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.settings.AppSettingsStore
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.OperationFailure
import app.hyperlpa.domain.model.OperationOutcome
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class RemotePeerUi(val id: String, val name: String, val approved: Boolean, val incoming: Boolean, val online: Boolean)
data class RemoteDevicesUiState(
    val loaded: Boolean = false,
    val relay: String = "",
    val name: String = "",
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val peers: List<RemotePeerUi> = emptyList(),
    val invitation: String? = null,
    val error: String? = null,
    val pendingOperations: Int = 0,
    val sharePhoneNotifications: Boolean = false,
    val notificationApps: Set<String> = emptySet(),
    val allowedNotificationApps: Set<String> = emptySet(),
    val notificationPeers: Set<String> = emptySet(),
)

data class PhoneNotificationView(
    val deviceId: String = "",
    val entries: List<PhoneNotificationEntry> = emptyList(),
    val available: Boolean = false,
    val loading: Boolean = false,
)

internal class RemoteDevices(
    private val context: Context,
    private val repository: LpaRepository,
    settings: AppSettingsStore,
    metadata: ProfileMetadataStore,
    history: NotificationHistoryStore,
    hostFactory: ((RemoteDevices, CoroutineScope) -> DeviceHost)? = null,
    private val serviceControl: (Boolean) -> Unit = { enabled ->
        if (enabled) RemoteAccessService.start(context) else RemoteAccessService.stop(context)
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = DeviceStore(context)
    private val phoneNotifications = PhoneNotificationStore(context)
    private val mutablePhoneView = MutableStateFlow(PhoneNotificationView())
    val phoneView = mutablePhoneView.asStateFlow()
    private val mutableLocalNotifications = MutableStateFlow<List<PhoneNotificationEntry>>(emptyList())
    val localNotifications = mutableLocalNotifications.asStateFlow()
    private val configLock = Any()
    private val actionMutex = Mutex()
    @Volatile private var config = StoredDevices()
    @Volatile private var initialized = false
    @Volatile private var connected = false
    @Volatile private var running = false
    private val ready = CompletableDeferred<Unit>()
    private val mutableUi = MutableStateFlow(RemoteDevicesUiState())
    val ui = mutableUi.asStateFlow()
    private val mutableReaders = MutableStateFlow<List<ReaderInfo>>(emptyList())
    val readers = mutableReaders.asStateFlow()
    private val mutableView = MutableStateFlow<DeviceSnapshot?>(null)
    val view = mutableView.asStateFlow()
    val isSelected: Boolean get() = mutableView.value != null
    val hasActiveOperation: Boolean get() = activeRequest.value != null
    val needsRefresh: Boolean get() {
        val selected = mutableView.value?.lpa?.selectedReader ?: return true
        return "${selected.deviceId}/${selected.sourceReaderId}" in config.refreshRequired ||
            config.pending.any { it.peer == selected.deviceId }
    }
    private val livePeers = ConcurrentHashMap<String, Long>()
    private val readerAvailability = ConcurrentHashMap<String, Boolean>()
    private val requestResults = ConcurrentHashMap<String, CompletableDeferred<DeviceMessage>>()
    private val requestPeers = ConcurrentHashMap<String, String>()
    private val requestRevisions = ConcurrentHashMap<String, Long>()
    private val seenMessages = LinkedHashMap<String, Long>()
    private val envelopeMutex = Mutex()
    private val packets = DevicePackets()
    private val requestMutex = Mutex()
    private val catalogRequests = ConcurrentHashMap<String, String>()
    private val activeRequest = MutableStateFlow<String?>(null)
    private val connection: RelayConnection = RelayConnection(scope, ::onConnection,
        { envelope -> scope.launch { receive(envelope) } },
        { _, _ -> mutableUi.value = mutableUi.value.copy(error = context.getString(R.string.remote_delivery_failed)) })
    private val agent: DeviceHost = hostFactory?.invoke(this, scope) ?: DeviceAgent(context, repository, settings, metadata, history, this, scope)

    init {
        scope.launch {
            try {
                config = store.read().let { stored -> stored.copy(name = stored.name.ifBlank { Build.MODEL.take(80) }) }
                initialized = true
                runCatching { phoneNotifications.list() }
                    .onSuccess { mutableLocalNotifications.value = it }
                publishUi()
                // In-flight operations from an earlier process are never executed again.
                update { saved -> saved.copy(operations = saved.operations.map { record ->
                    if (record.result != null) record else record.copy(result = DeviceMessage(
                        kind = "result", requestId = record.id, done = true,
                        outcome = unknownOutcome(),
                    ))
                }) }
                ready.complete(Unit)
            } catch (_: Exception) {
                initialized = false
                mutableUi.value = RemoteDevicesUiState(loaded = true, error = context.getString(R.string.remote_storage_unavailable))
                ready.complete(Unit)
            }
        }
        scope.launch {
            ready.await()
            while (isActive) {
                if (connected) {
                    config.peers.filter { it.approved }.forEach { peer ->
                        send(peer, DeviceMessage("hello", name = config.name))
                    }
                    config.pending.forEach { pending ->
                        peer(pending.peer)?.let { send(it, DeviceMessage("status", requestId = pending.id)) }
                    }
                }
                publishUi()
                updateAvailability()
                delay(20_000)
            }
        }
    }

    private fun update(transform: (StoredDevices) -> StoredDevices) = synchronized(configLock) {
        check(initialized) { context.getString(R.string.remote_storage_unavailable) }
        val next = transform(config)
        store.write(next)
        config = next
        publishUi()
    }

    private fun publishUi() {
        val now = System.currentTimeMillis()
        val saved = config
        mutableUi.update { it.copy(loaded = true, relay = saved.relay, name = saved.name,
            enabled = saved.enabled, connected = connected,
            peers = saved.peers.map { RemotePeerUi(it.id, it.name, it.approved, it.incoming,
                connected && now - (livePeers[it.id] ?: 0) < 60_000) },
            invitation = saved.invitation?.takeIf { it.expires > now }?.let(DeviceCrypto::invitationCode),
            pendingOperations = saved.pending.size, sharePhoneNotifications = saved.sharePhoneNotifications,
            notificationApps = saved.notificationApps, allowedNotificationApps = saved.allowedNotificationApps,
            notificationPeers = saved.notificationPeers) }
    }

    private fun action(block: suspend () -> Unit) {
        scope.launch {
            ready.await()
            actionMutex.withLock {
                mutableUi.update { it.copy(busy = true, error = null) }
                try { check(initialized); block() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { mutableUi.update { it.copy(error = error.message?.take(160) ?: context.getString(R.string.remote_action_failed)) } }
                finally { mutableUi.update { it.copy(busy = false) } }
            }
        }
    }

    fun configure(address: String, enrollmentKey: String, name: String) = action {
        check(!running && config.peers.isEmpty()) { context.getString(R.string.remote_disconnect_first) }
        require(name.trim().length in 1..80)
        val next = config.copy(relay = relayAddress(address), name = name.trim())
        connection.register(next, enrollmentKey.trim())
        update { next }
    }

    fun setName(name: String) = action {
        require(name.trim().length in 1..80)
        update { it.copy(name = name.trim()) }
        discover()
    }

    suspend fun shouldResume(): Boolean { ready.await(); return initialized && config.enabled && config.relay.isNotBlank() }

    fun setEnabled(enabled: Boolean) = action {
        check(config.relay.isNotBlank()) { context.getString(R.string.remote_configure_first) }
        update { it.copy(enabled = enabled) }
        withContext(Dispatchers.Main) { serviceControl(enabled) }
    }

    fun setPhoneNotificationSharing(enabled: Boolean) = action {
        update { it.copy(sharePhoneNotifications = enabled) }
        if (enabled) publishPhoneNotifications()
        else if (connected) config.peers.filter { it.approved && it.id in config.notificationPeers }.forEach {
            send(it, DeviceMessage("phone_notifications"))
        }
    }

    fun setNotificationApp(packageName: String, enabled: Boolean) = action {
        check(packageName in config.notificationApps)
        update { it.copy(allowedNotificationApps = if (enabled) it.allowedNotificationApps + packageName
            else it.allowedNotificationApps - packageName) }
        if (!enabled) {
            phoneNotifications.list().filter { it.packageName == packageName }.forEach { phoneNotifications.delete(it.id) }
            publishPhoneNotifications()
        }
    }

    fun setNotificationPeer(id: String, enabled: Boolean) = action {
        check(peer(id)?.approved == true)
        update { it.copy(notificationPeers = if (enabled) it.notificationPeers + id else it.notificationPeers - id) }
        send(requireNotNull(peer(id)), if (enabled && config.sharePhoneNotifications)
            DeviceMessage("phone_notifications_changed") else DeviceMessage("phone_notifications"))
    }

    fun recordPhoneNotification(packageName: String, notificationKey: String, title: String, text: String, timestamp: Long) {
        scope.launch {
            ready.await()
            if (!initialized || !config.sharePhoneNotifications || packageName == context.packageName ||
                packageName.length !in 1..255 || notificationKey.length !in 1..512) return@launch
            if (packageName !in config.notificationApps) synchronized(configLock) {
                if (packageName !in config.notificationApps) update {
                    it.copy(notificationApps = (it.notificationApps + packageName).take(200).toSet())
                }
            }
            val recorded = synchronized(configLock) {
                if (!config.sharePhoneNotifications || packageName !in config.allowedNotificationApps ||
                    (title.isBlank() && text.isBlank())) false
                else runCatching {
                    phoneNotifications.record(packageName, notificationKey, title, text, timestamp)
                }.isSuccess
            }
            if (recorded) publishPhoneNotifications()
        }
    }

    fun refreshPhoneNotifications(id: String) {
        val device = peer(id)?.takeIf { it.approved } ?: return
        mutablePhoneView.value = PhoneNotificationView(deviceId = id, loading = true)
        if (!connected || !send(device, DeviceMessage("phone_notifications_request"))) {
            mutablePhoneView.value = mutablePhoneView.value.copy(loading = false)
        } else scope.launch {
            delay(12_000)
            mutablePhoneView.update { if (it.deviceId == id && it.loading) it.copy(loading = false) else it }
        }
    }

    fun closePhoneNotifications(deviceId: String) {
        mutablePhoneView.update { if (it.deviceId == deviceId) PhoneNotificationView() else it }
    }

    fun deletePhoneNotification(id: String, deviceId: String) {
        if (deviceId == "local" || deviceId == config.id) {
            action { phoneNotifications.delete(id); publishPhoneNotifications() }
        } else {
            peer(deviceId)?.takeIf { it.approved && connected }?.let { send(it, DeviceMessage("phone_notifications_delete", notificationId = id)) }
        }
    }

    fun clearPhoneNotifications() = action { phoneNotifications.clear(); publishPhoneNotifications() }

    private fun publishPhoneNotifications() {
        val entries = runCatching { phoneNotifications.list() }.getOrElse {
            mutableUi.update { state -> state.copy(error = context.getString(R.string.remote_storage_unavailable)) }
            return
        }
        mutableLocalNotifications.value = entries
        if (connected && config.sharePhoneNotifications) config.peers.filter { it.approved && it.id in config.notificationPeers }
            .forEach { send(it, DeviceMessage("phone_notifications_changed")) }
    }

    fun startRuntime() = action {
        if (!config.enabled || config.relay.isBlank() || running) return@action
        running = true
        connection.start(config)
    }

    fun stopRuntime() {
        running = false
        connection.stop()
        agent.cancelPendingConfirmation()
    }

    fun createInvitation() = action {
        check(connected) { context.getString(R.string.remote_connect_first) }
        check(config.peers.size < 32)
        val invitation = PairInvitation(relay = config.relay, device = config.id, name = config.name,
            pair = newDeviceId(), secret = DeviceCrypto.secret(), expires = System.currentTimeMillis() + 600_000)
        update { it.copy(invitation = invitation) }
    }

    fun pair(code: String) = action {
        check(connected) { context.getString(R.string.remote_connect_first) }
        val invite = DeviceCrypto.parseInvitation(code.trim())
        check(invite.relay == config.relay && invite.device != config.id) { context.getString(R.string.remote_pair_wrong_relay) }
        check(config.peers.size < 32 && config.peers.none { it.id == invite.device }) { context.getString(R.string.remote_already_paired) }
        val device = PairedDevice(invite.device, invite.name, invite.pair, invite.secret)
        update { it.copy(peers = it.peers + device) }
        send(device, DeviceMessage("pair", name = config.name))
    }

    fun approve(id: String) = action {
        val device = peer(id) ?: return@action
        check(device.incoming && !device.approved)
        update { it.copy(peers = it.peers.map { p -> if (p.id == id) p.copy(approved = true) else p }) }
        send(device, DeviceMessage("paired", name = config.name))
        discover()
    }

    fun forget(id: String) = action {
        val device = peer(id) ?: return@action
        send(device, DeviceMessage("unpair"))
        removePeer(id)
    }

    private fun removePeer(id: String) {
        if (agent.isBusyWith(id)) agent.cancelPendingConfirmation()
        config.pending.filter { it.peer == id }.forEach { pending ->
            requestResults[pending.id]?.complete(DeviceMessage("result", requestId = pending.id,
                done = true, outcome = unknownOutcome()))
        }
        update { it.copy(peers = it.peers.filterNot { p -> p.id == id }, pending = it.pending.filterNot { p -> p.peer == id },
            refreshRequired = it.refreshRequired.filterNot { key -> key.startsWith("$id/") }.toSet(),
            notificationPeers = it.notificationPeers - id) }
        if (mutablePhoneView.value.deviceId == id) mutablePhoneView.value = PhoneNotificationView()
        livePeers.remove(id)
        mutableReaders.value = mutableReaders.value.filterNot { it.deviceId == id }
        if (mutableView.value?.lpa?.selectedReader?.deviceId == id) mutableView.value = null
    }

    fun unregister() = action {
        check(!agent.busy && config.pending.isEmpty()) { context.getString(R.string.remote_operation_running) }
        if (config.relay.isNotBlank()) connection.unregister(config)
        stopRuntime()
        withContext(Dispatchers.Main) { serviceControl(false) }
        update { StoredDevices(name = config.name) }
        phoneNotifications.clear()
        mutableLocalNotifications.value = emptyList()
        mutablePhoneView.value = PhoneNotificationView()
        mutableReaders.value = emptyList()
        mutableView.value = null
    }

    private fun peer(id: String): PairedDevice? = config.peers.firstOrNull { it.id == id }
    private fun onConnection(value: Boolean) {
        connected = value
        if (!value) mutablePhoneView.update { it.copy(entries = emptyList(), available = false, loading = false) }
        publishUi()
        updateAvailability()
        if (value) scope.launch {
            config.peers.forEach { device ->
                send(device, DeviceMessage(if (device.approved) "hello" else if (device.incoming) "pair_pending" else "pair", name = config.name))
            }
            discover()
        }
    }

    private fun updateAvailability() {
        val now = System.currentTimeMillis()
        mutableReaders.value = mutableReaders.value.map { it.copy(available = readerAvailability[it.id] == true &&
            connected && now - (livePeers[it.deviceId.orEmpty()] ?: 0) < 60_000) }
        mutableView.update { selected -> selected?.copy(lpa = selected.lpa.copy(
            readers = mutableReaders.value,
            readerSnapshotPendingRefresh = selected.lpa.readerSnapshotPendingRefresh || !connected ||
                now - (livePeers[selected.lpa.selectedReader?.deviceId.orEmpty()] ?: 0) >= 60_000,
        )) }
    }

    internal fun send(device: PairedDevice, message: DeviceMessage): Boolean {
        if (!connected) return false
        return runCatching {
            DevicePackets.encode(message).all { plaintext ->
                connection.send(DeviceCrypto.encrypt(device.secret, config.id, device.id, device.pair, plaintext))
            }
        }.getOrDefault(false)
    }

    private suspend fun receive(envelope: DeviceEnvelope) = envelopeMutex.withLock {
        ready.await()
        try {
            if (!initialized || !config.enabled) return@withLock
            val now = System.currentTimeMillis()
            synchronized(seenMessages) {
                seenMessages.entries.removeAll { it.value <= now }
                if (envelope.id in seenMessages) { connection.acknowledge(envelope.id); return@withLock }
            }
            val existing = peer(envelope.from)?.takeIf { it.pair == envelope.pair }
            val invitation = config.invitation?.takeIf { it.pair == envelope.pair && it.expires > now }
            val secret = existing?.secret ?: invitation?.secret ?: return@withLock
            val decoded = DeviceJson.decodeFromString(DeviceMessage.serializer(), DeviceCrypto.decrypt(secret, envelope, config.id))
            val message = if (decoded.kind == "part") {
                check(existing?.approved == true)
                packets.accept(existing.id, requireNotNull(decoded.part), envelope.expires) ?: run {
                    connection.acknowledge(envelope.id)
                    return@withLock
                }
            } else decoded
            if (existing == null) {
                if (message.kind != "pair" || message.name.length !in 1..80 || config.peers.size >= 32) return@withLock
                val incoming = PairedDevice(envelope.from, message.name, envelope.pair, secret, incoming = true)
                update { it.copy(invitation = null, peers = it.peers + incoming) }
                send(incoming, DeviceMessage("pair_pending", name = config.name))
            } else when (message.kind) {
                "pair" -> if (existing.incoming) send(existing, DeviceMessage(if (existing.approved) "paired" else "pair_pending", name = config.name))
                "paired" -> if (!existing.incoming) {
                    update { it.copy(peers = it.peers.map { p -> if (p.id == existing.id) p.copy(approved = true) else p }) }
                    discover()
                }
                "unpair" -> removePeer(existing.id)
                else -> if (existing.approved) {
                    livePeers[existing.id] = now
                    when (message.kind) {
                        "hello" -> {
                            if (message.name.length in 1..80 && message.name != existing.name) {
                                update { saved -> saved.copy(peers = saved.peers.map { if (it.id == existing.id) it.copy(name = message.name) else it }) }
                                mutableReaders.value = mutableReaders.value.map {
                                    if (it.deviceId == existing.id) it.copy(name = message.name + " · " + it.name.substringAfter(" · ")) else it
                                }
                            }
                            send(existing, DeviceMessage("hello_reply", name = config.name))
                            if (mutableReaders.value.none { it.deviceId == existing.id }) requestCatalog(existing)
                        }
                        "hello_reply" -> if (mutablePhoneView.value.deviceId == existing.id) refreshPhoneNotifications(existing.id)
                        "phone_notifications_request" -> send(existing, DeviceMessage("phone_notifications",
                            phoneNotifications = if (config.sharePhoneNotifications && existing.id in config.notificationPeers)
                                phoneNotifications.list().map { it.copy(notificationKey = "") } else emptyList(),
                            phoneNotificationsAvailable = config.sharePhoneNotifications && existing.id in config.notificationPeers))
                        "phone_notifications_changed" -> if (mutablePhoneView.value.deviceId == existing.id)
                            refreshPhoneNotifications(existing.id)
                        "phone_notifications" -> if (mutablePhoneView.value.deviceId == existing.id) {
                            mutablePhoneView.value = PhoneNotificationView(existing.id,
                                if (message.phoneNotificationsAvailable) message.phoneNotifications.take(150).filter {
                                    it.id.length == 36 && it.packageName.length in 1..255 &&
                                        it.title.length <= 256 && it.text.length <= 2048 &&
                                        (it.title.isNotBlank() || it.text.isNotBlank())
                                } else emptyList(), message.phoneNotificationsAvailable)
                        }
                        "phone_notifications_delete" -> if (config.sharePhoneNotifications && existing.id in config.notificationPeers &&
                            message.notificationId.length == 36) {
                            phoneNotifications.delete(message.notificationId)
                            publishPhoneNotifications()
                        }
                        "command" -> agent.accept(existing, message, envelope.expires)
                        "status" -> agent.status(existing, message.requestId)
                        "decision" -> agent.decision(existing.id, message.requestId, message.confirmed)
                        "cancel" -> agent.decision(existing.id, message.requestId, false)
                        "result" -> receiveResult(existing, message)
                        "missing" -> {
                            val pending = config.pending.firstOrNull { it.peer == existing.id && it.id == message.requestId }
                            if (pending != null && now - pending.started > DeviceMessageLifetime + 30_000) {
                                receiveResult(existing, DeviceMessage("result", requestId = message.requestId, done = true,
                                    outcome = unknownOutcome()))
                            }
                        }
                    }
                }
            }
            synchronized(seenMessages) {
                if (seenMessages.size >= 4096) seenMessages.remove(seenMessages.keys.first())
                seenMessages[envelope.id] = envelope.expires
            }
            connection.acknowledge(envelope.id)
            publishUi()
            updateAvailability()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Unauthenticated, expired, or malformed frames never reach the LPA. */ }
    }

    fun discover() { scope.launch { config.peers.filter { it.approved }.forEach { requestCatalog(it) } } }

    private fun requestCatalog(device: PairedDevice) {
        if (!connected || catalogRequests.containsValue(device.id)) return
        val id = newDeviceId()
        catalogRequests[id] = device.id
        send(device, DeviceMessage("command", requestId = id, command = DeviceCommand(DeviceAction.READERS)))
        scope.launch { delay(30_000); catalogRequests.remove(id) }
    }

    private fun receiveResult(device: PairedDevice, message: DeviceMessage) {
        if (catalogRequests[message.requestId] == device.id) {
            if (message.done) {
                catalogRequests.remove(message.requestId)
                message.snapshot?.let { snapshot ->
                    val remote = snapshot.lpa.readers.filter { it.deviceId == null }.take(64).map { reader -> remoteReader(device, reader) }
                    remote.forEach { readerAvailability[it.id] = it.available }
                    mutableReaders.value = mutableReaders.value.filterNot { it.deviceId == device.id } + remote
                }
            }
            return
        }
        val pending = config.pending.firstOrNull { it.id == message.requestId && it.peer == device.id }
        if (requestPeers[message.requestId] != device.id && pending == null) return
        if (requestResults[message.requestId]?.isCompleted == true) return
        if ((requestRevisions[message.requestId] ?: -1) > message.revision && !message.done) return
        requestRevisions[message.requestId] = message.revision
        val target = mutableView.value?.lpa?.selectedReader
        if (target?.deviceId == device.id && (pending == null || pending.readerId == target.sourceReaderId)) {
            message.snapshot?.let { snapshot ->
                if (snapshot.lpa.selectedReaderId == target.sourceReaderId &&
                    (pending?.eid.isNullOrBlank() || snapshot.lpa.euiccInfo?.eid == pending?.eid)) {
                    mutableView.value = materialize(snapshot).let { materialized -> materialized.copy(lpa = materialized.lpa.copy(
                        readers = mutableReaders.value, selectedReaderId = target.id, initialized = true,
                    )) }
                }
            }
            if (message.done) {
                mutableView.value = mutableView.value?.let { current -> current.copy(lpa = current.lpa.copy(
                    operation = LpaOperation.Idle,
                    readerSnapshotPendingRefresh = message.outcome is OperationOutcome.Unverified ||
                        message.snapshot == null || current.lpa.readerSnapshotPendingRefresh,
                    pendingProfileDownload = null,
                    completedProfileDownload = message.downloadResult ?: current.lpa.completedProfileDownload,
                    failure = when (val outcome = message.outcome) {
                        is OperationOutcome.Failed -> outcome.failure
                        is OperationOutcome.Unverified -> outcome.failure
                        else -> null
                    },
                )) }
            }
        }
        if (message.done) {
            update { saved -> saved.copy(
                pending = saved.pending.filterNot { p -> p.id == message.requestId && p.peer == device.id },
                refreshRequired = if (pending != null && (message.outcome is OperationOutcome.Unverified ||
                    message.outcome is OperationOutcome.Success && message.snapshot == null))
                    saved.refreshRequired + "${pending.peer}/${pending.readerId}" else saved.refreshRequired,
            ) }
            requestResults[message.requestId]?.complete(message)
        }
    }

    private fun materialize(snapshot: DeviceSnapshot): DeviceSnapshot {
        if (!snapshot.artworkIncluded) {
            val previous = mutableView.value
            return snapshot.copy(metadata = snapshot.metadata.mapValues { (id, value) ->
                value.copy(iconUri = previous?.metadata?.get(id)?.iconUri)
            }, providerIcons = previous?.providerIcons.orEmpty(), history = previous?.history.orEmpty(), lpa = snapshot.lpa.copy(profiles = snapshot.lpa.profiles.map { profile ->
                profile.copy(iconBase64 = previous?.lpa?.profiles?.firstOrNull { it.iccid == profile.iccid }?.iconBase64)
            }))
        }
        val directory = File(context.noBackupFilesDir, "remote-artwork").apply { mkdirs() }
        // Bound this cache independently of peer-controlled names or history length.
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(256)?.forEach { it.delete() }
        val uris = snapshot.images.mapNotNull { (key, encoded) ->
            if (!key.matches(Regex("[A-Za-z0-9_-]{43}")) || encoded.length > 350_000) return@mapNotNull null
            val bytes = DeviceCrypto.decode(encoded)
            if (DeviceCrypto.fingerprint(DeviceCrypto.encode(bytes)) != key) return@mapNotNull null
            val file = File(directory, key)
            if (!file.exists()) file.writeBytes(bytes)
            key to android.net.Uri.fromFile(file).toString()
        }.toMap()
        // Never treat a remote file/content URI as a URI on this phone.
        return snapshot.copy(metadata = snapshot.metadata.mapValues { (_, value) -> value.copy(iconUri = value.iconUri?.let(uris::get)) },
            providerIcons = snapshot.providerIcons.mapNotNull { (key, icon) -> uris[icon]?.let { key to it } }.toMap(), images = emptyMap())
    }

    private fun remoteReader(device: PairedDevice, reader: ReaderInfo) = reader.copy(
        id = "device:${device.id}:${reader.id}", name = "${device.name} · ${reader.name}",
        kind = ReaderKind.REMOTE, deviceId = device.id, sourceReaderId = reader.id,
    )

    suspend fun select(id: String): OperationOutcome {
        if (!requestMutex.tryLock()) return failure(R.string.remote_operation_running)
        try {
            val reader = mutableReaders.value.firstOrNull { it.id == id } ?: return failure(R.string.remote_reader_unavailable)
            mutableView.value = DeviceSnapshot(lpa = LpaRepositoryState(readers = mutableReaders.value,
                selectedReaderId = id, initialized = true, operation = LpaOperation.Connecting(reader.name)))
            return executeLocked(DeviceCommand(DeviceAction.REFRESH))
        } finally { requestMutex.unlock() }
    }

    fun deselect(): Boolean {
        if (!requestMutex.tryLock()) return false
        try { mutableView.value = null; return true }
        finally { requestMutex.unlock() }
    }

    fun affinity(): ReaderAffinity? = mutableView.value?.lpa?.let { state ->
        val id = state.selectedReaderId ?: return@let null
        val eid = state.euiccInfo?.eid ?: return@let null
        ReaderAffinity(id, eid)
    }

    suspend fun execute(command: DeviceCommand, expectedAffinity: ReaderAffinity? = affinity()): OperationOutcome {
        return requestMutex.withLock {
            if (expectedAffinity != affinity()) return@withLock failure(R.string.remote_card_changed)
            executeLocked(command)
        }
    }

    private suspend fun executeLocked(command: DeviceCommand): OperationOutcome {
        val selected = mutableView.value?.lpa?.selectedReader ?: return failure(R.string.remote_reader_unavailable)
        val device = peer(selected.deviceId.orEmpty())?.takeIf { it.approved } ?: return failure(R.string.remote_reader_unavailable)
        if (!connected) return publishFailure(failure(R.string.remote_device_offline))
        if (config.pending.any { it.peer == device.id }) return publishFailure(unknownOutcome())
        val targetKey = "${device.id}/${selected.sourceReaderId}"
        if (command.action != DeviceAction.REFRESH && targetKey in config.refreshRequired)
            return publishFailure(unknownOutcome())
        val bound = command.copy(readerId = selected.sourceReaderId.orEmpty(), eid = mutableView.value?.lpa?.euiccInfo?.eid ?: selected.eid.orEmpty())
        val id = newDeviceId()
        val result = CompletableDeferred<DeviceMessage>()
        try {
            update { it.copy(pending = it.pending + PendingDeviceRequest(device.id, id, bound.readerId, bound.eid, System.currentTimeMillis())) }
        } catch (_: Exception) { return publishFailure(failure(R.string.remote_storage_unavailable)) }
        requestPeers[id] = device.id
        requestResults[id] = result
        activeRequest.value = id
        mutableView.value = mutableView.value?.let { it.copy(lpa = it.lpa.copy(
            failure = null, completedProfileDownload = null,
            operation = if (command.action == DeviceAction.DOWNLOAD) LpaOperation.Downloading(app.hyperlpa.domain.model.DownloadStage.PREPARING)
                else LpaOperation.Refreshing(context.getString(R.string.remote_working)),
        )) }
        try {
            if (!send(device, DeviceMessage("command", requestId = id, command = bound))) {
                update { it.copy(pending = it.pending.filterNot { p -> p.id == id }) }
                return publishFailure(failure(R.string.remote_delivery_failed))
            }
            val response = withTimeoutOrNull(if (command.action == DeviceAction.DOWNLOAD) 1_800_000L else 180_000L) {
                while (!result.isCompleted) {
                    if (withTimeoutOrNull(5_000) { result.await() } != null) break
                    // A request is sent once. Status polling never repeats the operation.
                    send(device, DeviceMessage("status", requestId = id))
                }
                result.await()
            }
            val outcome = response?.outcome ?: publishFailure(unknownOutcome())
            if (command.action == DeviceAction.REFRESH && outcome is OperationOutcome.Success && response?.snapshot != null) {
                update { it.copy(refreshRequired = it.refreshRequired - targetKey) }
            }
            return outcome
        } finally {
            activeRequest.value = null
            requestPeers.remove(id)
            requestResults.remove(id)
            requestRevisions.remove(id)
        }
    }

    fun decideDownload(confirmed: Boolean) {
        val id = activeRequest.value ?: return
        val device = requestPeers[id]?.let(::peer) ?: return
        scope.launch { send(device, DeviceMessage(if (confirmed) "decision" else "cancel", requestId = id, confirmed = confirmed)) }
    }

    fun clearFailure() { mutableView.value = mutableView.value?.let { it.copy(lpa = it.lpa.copy(failure = null)) } }
    fun clearDownloadResult() { mutableView.value = mutableView.value?.let { it.copy(lpa = it.lpa.copy(completedProfileDownload = null)) } }
    private fun publishFailure(outcome: OperationOutcome): OperationOutcome {
        mutableView.value = mutableView.value?.let { it.copy(lpa = it.lpa.copy(operation = LpaOperation.Idle,
            failure = when (outcome) { is OperationOutcome.Failed -> outcome.failure; is OperationOutcome.Unverified -> outcome.failure; else -> null })) }
        return outcome
    }
    private fun failure(message: Int) = OperationOutcome.Failed(OperationFailure(context.getString(R.string.remote_title), context.getString(message)))
    private fun unknownOutcome() = OperationOutcome.Unverified(OperationFailure(context.getString(R.string.remote_title), context.getString(R.string.remote_outcome_unknown)))

    internal fun close() { connection.stop(); scope.cancel() }

    internal fun record(peer: String, id: String): DeviceOperationRecord? = config.operations.firstOrNull { it.peer == peer && it.id == id }
    internal fun saveRecord(record: DeviceOperationRecord) {
        update { current ->
            val retained = current.operations.filterNot { it.peer == record.peer && it.id == record.id }
                .filter { it.result == null || System.currentTimeMillis() - it.started < 86_400_000 }
            check(retained.size < 1024) { context.getString(R.string.remote_operation_limit) }
            current.copy(operations = retained + record)
        }
    }
}
