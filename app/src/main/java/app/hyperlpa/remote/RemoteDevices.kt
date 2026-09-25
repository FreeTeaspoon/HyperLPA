package app.hyperlpa.remote

import android.content.Context
import android.os.Build
import android.provider.Telephony
import app.hyperlpa.R
import app.hyperlpa.data.LpaRepository
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.ReaderAffinity
import app.hyperlpa.data.history.NotificationHistoryStore
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.settings.AppSettingsStore
import app.hyperlpa.domain.model.DownloadStage
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.OperationFailure
import app.hyperlpa.domain.model.OperationOutcome
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
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
    // Last confirmed card per reader, so reselecting one shows its profiles while it refreshes.
    private val readerSnapshots = object : LinkedHashMap<String, DeviceSnapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DeviceSnapshot>) = size > 8
    }
    @Volatile private var verification: Job? = null
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
                includeDefaultMessagesApp()
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

    private fun includeDefaultMessagesApp() {
        val messages = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
            ?.takeIf { it.length in 1..255 && it != context.packageName } ?: return
        // Once listed, the user's choice for the app wins over this default.
        if (messages in config.notificationApps) return
        update { it.copy(notificationApps = it.notificationApps + messages,
            allowedNotificationApps = it.allowedNotificationApps + messages) }
    }

    fun setPhoneNotificationSharing(enabled: Boolean) = action {
        update { it.copy(sharePhoneNotifications = enabled) }
        if (enabled) { includeDefaultMessagesApp(); publishPhoneNotifications() }
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

    fun recordPhoneNotification(packageName: String, notificationKey: String, title: String, text: String, timestamp: Long, appLabel: String = "") {
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
                    phoneNotifications.record(packageName, notificationKey, title, text, timestamp, appLabel)
                }.isSuccess
            }
            if (recorded) publishPhoneNotifications()
        }
    }

    fun refreshPhoneNotifications(id: String, userInitiated: Boolean = false) {
        val device = peer(id)?.takeIf { it.approved } ?: return
        mutablePhoneView.update { current ->
            if (current.deviceId == id) current.copy(loading = true, refreshing = current.refreshing || userInitiated)
            else PhoneNotificationView(deviceId = id, loading = true)
        }
        if (!connected || !send(device, DeviceMessage("phone_notifications_request"))) {
            mutablePhoneView.update { if (it.deviceId == id) it.copy(loading = false, refreshing = false) else it }
        } else scope.launch {
            delay(12_000)
            mutablePhoneView.update { if (it.deviceId == id && it.loading) it.copy(loading = false, refreshing = false) else it }
        }
    }

    fun closePhoneNotifications(deviceId: String) {
        mutablePhoneView.update { if (it.deviceId == deviceId) PhoneNotificationView() else it }
    }

    fun clearPhoneNotifications(deviceId: String = "local") {
        if (deviceId == "local" || deviceId == config.id) {
            action { phoneNotifications.clear(); publishPhoneNotifications() }
        } else {
            val device = peer(deviceId)?.takeIf { it.approved && connected } ?: return
            if (send(device, DeviceMessage("phone_notifications_clear"))) {
                mutablePhoneView.update { if (it.deviceId == deviceId) it.copy(entries = emptyList()) else it }
            }
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        ready.await()
        if (!initialized || !config.enabled || config.relay.isBlank()) return@withContext
        val reconnecting = actionMutex.withLock {
            (running && !connected).also { if (it) connection.start(config) }
        }
        if (reconnecting) withTimeoutOrNull(5_000) { while (!connected) delay(100) }
        if (!connected) { publishUi(); return@withContext }
        val started = System.currentTimeMillis()
        val approved = config.peers.filter { it.approved }
        approved.forEach { send(it, DeviceMessage("hello", name = config.name)) }
        discover()
        withTimeoutOrNull(3_000) {
            while (approved.any { (livePeers[it.id] ?: 0) < started }) delay(100)
        }
        publishUi()
        updateAvailability()
    }

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
        synchronized(readerSnapshots) { readerSnapshots.keys.removeAll { it.startsWith("device:$id:") } }
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
        synchronized(readerSnapshots) { readerSnapshots.clear() }
        mutableView.value = null
    }

    private fun peer(id: String): PairedDevice? = config.peers.firstOrNull { it.id == id }
    private fun onConnection(value: Boolean) {
        connected = value
        if (!value) mutablePhoneView.update { it.copy(entries = emptyList(), available = false, loading = false, refreshing = false) }
        publishUi()
        updateAvailability()
        if (value) scope.launch {
            config.peers.forEach { device ->
                send(device, DeviceMessage(if (device.approved) "hello" else if (device.incoming) "pair_pending" else "pair", name = config.name))
            }
            // A result lost with the old connection is recovered now, not at the next periodic check.
            config.pending.forEach { pending ->
                peer(pending.peer)?.let { send(it, DeviceMessage("status", requestId = pending.id)) }
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
                                        it.title.length <= 256 && it.text.length <= 2048 && it.appLabel.length <= 80 &&
                                        (it.title.isNotBlank() || it.text.isNotBlank())
                                } else emptyList(), message.phoneNotificationsAvailable, loaded = true)
                        }
                        "phone_notifications_delete" -> if (config.sharePhoneNotifications && existing.id in config.notificationPeers &&
                            message.notificationId.length == 36) {
                            phoneNotifications.delete(message.notificationId)
                            publishPhoneNotifications()
                        }
                        "phone_notifications_clear" -> if (config.sharePhoneNotifications && existing.id in config.notificationPeers) {
                            phoneNotifications.clear()
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
        val awaited = requestResults.containsKey(message.requestId)
        val target = mutableView.value?.lpa?.selectedReader
        val applies = target != null && target.deviceId == device.id && (pending == null || pending.readerId == target.sourceReaderId)
        val snapshot = message.snapshot?.takeIf { snapshot ->
            applies && snapshot.lpa.selectedReaderId == target?.sourceReaderId &&
                (pending?.eid.isNullOrBlank() || snapshot.lpa.euiccInfo?.eid == pending?.eid)
        }?.let(::materialize)
        val stale = snapshot == null || message.outcome is OperationOutcome.Unverified
        if (applies) mutableView.update { current ->
            if (current == null || current.lpa.selectedReaderId != target?.id) return@update current
            // This phone owns the displayed operation, failure and download result. The host's
            // copies describe its own session, and adopting them made the progress UI jump.
            val next = snapshot?.let { fresh -> fresh.copy(lpa = fresh.lpa.copy(
                readers = mutableReaders.value, selectedReaderId = target?.id, initialized = true,
                operation = current.lpa.operation.let { own ->
                    if (own is LpaOperation.Downloading && fresh.lpa.operation is LpaOperation.Downloading) fresh.lpa.operation else own
                },
                failure = current.lpa.failure,
                completedProfileDownload = current.lpa.completedProfileDownload,
                readerSnapshotPendingRefresh = current.lpa.readerSnapshotPendingRefresh,
            )) } ?: current
            if (!message.done) next else next.copy(lpa = next.lpa.copy(
                pendingProfileDownload = null,
                completedProfileDownload = message.downloadResult ?: next.lpa.completedProfileDownload,
                // An awaited request publishes this together with the end of its operation.
                readerSnapshotPendingRefresh = when {
                    stale -> true
                    awaited -> next.lpa.readerSnapshotPendingRefresh
                    else -> next.lpa.readerSnapshotPendingRefresh && next.lpa.operation !is LpaOperation.Idle
                },
            ))
        }
        if (message.done) {
            val key = pending?.let { "${it.peer}/${it.readerId}" }
            val unconfirmed = key != null && (message.outcome is OperationOutcome.Unverified ||
                message.outcome is OperationOutcome.Success && snapshot == null)
            update { saved -> saved.copy(
                pending = saved.pending.filterNot { p -> p.id == message.requestId && p.peer == device.id },
                refreshRequired = if (unconfirmed) saved.refreshRequired + requireNotNull(key) else saved.refreshRequired,
            ) }
            requestResults[message.requestId]?.complete(if (snapshot == null) message.copy(snapshot = null) else message)
            if (unconfirmed) scheduleVerification()
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
            if (!file.exists()) file.writeBytes(bytes) else file.setLastModified(System.currentTimeMillis())
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

    suspend fun select(id: String): OperationOutcome = withContext(Dispatchers.IO) {
        if (!requestMutex.tryLock()) return@withContext failure(R.string.remote_operation_running)
        try {
            val reader = mutableReaders.value.firstOrNull { it.id == id }
                ?: return@withContext failure(R.string.remote_reader_unavailable)
            rememberView(mutableView.value)
            val cached = synchronized(readerSnapshots) { readerSnapshots[id] }
            mutableView.value = (cached ?: DeviceSnapshot()).let { shown -> shown.copy(lpa = shown.lpa.copy(
                readers = mutableReaders.value, selectedReaderId = id, initialized = true,
                operation = LpaOperation.Connecting(reader.name), failure = null,
                pendingProfileDownload = null, completedProfileDownload = null,
                readerSnapshotPendingRefresh = cached != null,
            )) }
            val outcome = executeLocked(DeviceCommand(DeviceAction.REFRESH))
            if (outcome !is OperationOutcome.Success) mutableView.update { current ->
                // Like a local reader that fails to open, never leave the cached card on screen.
                if (current?.lpa?.selectedReaderId != id || !current.lpa.readerSnapshotPendingRefresh) current
                else DeviceSnapshot(lpa = LpaRepositoryState(readers = mutableReaders.value, selectedReaderId = id,
                    initialized = true, failure = current.lpa.failure))
            }
            outcome
        } finally { requestMutex.unlock() }
    }

    /** Releases the remote reader. [beforeRelease] runs first, while this reader is still shown. */
    fun deselect(beforeRelease: () -> Unit = {}): Boolean {
        if (!requestMutex.tryLock()) return false
        try {
            rememberView(mutableView.value)
            beforeRelease()
            mutableView.value = null
            return true
        } finally { requestMutex.unlock() }
    }

    private fun rememberView(view: DeviceSnapshot?) {
        val lpa = view?.lpa ?: return
        val id = lpa.selectedReaderId ?: return
        if (lpa.euiccInfo == null || lpa.readerSnapshotPendingRefresh || lpa.operation !is LpaOperation.Idle) return
        synchronized(readerSnapshots) { readerSnapshots[id] = view.copy(lpa = lpa.copy(failure = null, logs = emptyList())) }
    }

    fun affinity(): ReaderAffinity? = mutableView.value?.lpa?.let { state ->
        val id = state.selectedReaderId ?: return@let null
        val eid = state.euiccInfo?.eid ?: return@let null
        ReaderAffinity(id, eid)
    }

    suspend fun execute(command: DeviceCommand, expectedAffinity: ReaderAffinity? = affinity()): OperationOutcome =
        withContext(Dispatchers.IO) {
            requestMutex.withLock {
                if (expectedAffinity != affinity()) return@withLock failure(R.string.remote_card_changed)
                executeLocked(command)
            }
        }

    /** Runs [command] on the selected reader, showing the same operation a local reader would. */
    private suspend fun executeLocked(command: DeviceCommand, reportFailure: Boolean = true): OperationOutcome {
        val operation = operationFor(command)
        // Publish the operation before any journal write, so the UI reacts in the same frame.
        mutableView.update { current -> current?.copy(lpa = current.lpa.copy(
            operation = if (current.lpa.operation is LpaOperation.Connecting) current.lpa.operation else operation,
            failure = if (reportFailure) null else current.lpa.failure,
            completedProfileDownload = current.lpa.completedProfileDownload.takeIf { command.action != DeviceAction.DOWNLOAD },
        )) }
        val (outcome, response) = try { request(command) } catch (error: CancellationException) {
            mutableView.update { current -> current?.copy(lpa = current.lpa.copy(operation = LpaOperation.Idle)) }
            throw error
        }
        val confirmed = response?.snapshot != null && outcome !is OperationOutcome.Unverified
        mutableView.update { current -> current?.copy(lpa = current.lpa.copy(
            operation = LpaOperation.Idle,
            readerSnapshotPendingRefresh = when {
                confirmed -> false
                response != null -> true
                else -> current.lpa.readerSnapshotPendingRefresh
            },
            failure = if (reportFailure) outcome.failureOrNull() else current.lpa.failure,
        )) }
        if (confirmed) rememberView(mutableView.value)
        return outcome
    }

    /** Sends [command] once and waits for its final result, which is null if it never arrived. */
    private suspend fun request(command: DeviceCommand): Pair<OperationOutcome, DeviceMessage?> {
        val selected = mutableView.value?.lpa?.selectedReader ?: return failure(R.string.remote_reader_unavailable) to null
        val device = peer(selected.deviceId.orEmpty())?.takeIf { it.approved } ?: return failure(R.string.remote_reader_unavailable) to null
        if (!connected) return failure(R.string.remote_device_offline) to null
        if (!awaitPendingResults(device)) return failure(R.string.remote_operation_running) to null
        val targetKey = "${device.id}/${selected.sourceReaderId}"
        if (command.action != DeviceAction.REFRESH && targetKey in config.refreshRequired) {
            // An earlier change on this card has no confirmed result. Read the card first, as a
            // local reader requires before another change.
            val eid = mutableView.value?.lpa?.euiccInfo?.eid
            val (refreshed, _) = request(DeviceCommand(DeviceAction.REFRESH))
            if (refreshed !is OperationOutcome.Success) return refreshed to null
            if (targetKey in config.refreshRequired) return unknownOutcome() to null
            if (mutableView.value?.lpa?.euiccInfo?.eid != eid) return failure(R.string.remote_card_changed) to null
        }
        // A refresh reads whichever card is present, as a local refresh does.
        val bound = command.copy(readerId = selected.sourceReaderId.orEmpty(), eid = if (command.action == DeviceAction.REFRESH) ""
            else mutableView.value?.lpa?.euiccInfo?.eid ?: selected.eid.orEmpty())
        val id = newDeviceId()
        val result = CompletableDeferred<DeviceMessage>()
        try {
            update { it.copy(pending = it.pending + PendingDeviceRequest(device.id, id, bound.readerId, bound.eid, System.currentTimeMillis())) }
        } catch (_: Exception) { return failure(R.string.remote_storage_unavailable) to null }
        requestPeers[id] = device.id
        requestResults[id] = result
        activeRequest.value = id
        try {
            if (!send(device, DeviceMessage("command", requestId = id, command = bound))) {
                runCatching { update { it.copy(pending = it.pending.filterNot { p -> p.id == id }) } }
                return failure(R.string.remote_delivery_failed) to null
            }
            val response = withTimeoutOrNull(if (command.action == DeviceAction.DOWNLOAD) 1_800_000L else 180_000L) {
                while (!result.isCompleted) {
                    if (withTimeoutOrNull(5_000) { result.await() } != null) break
                    // A request is sent once. Status polling never repeats the operation.
                    send(device, DeviceMessage("status", requestId = id))
                }
                result.await()
            } ?: return unknownOutcome() to null
            val outcome = response.outcome ?: unknownOutcome()
            if (command.action == DeviceAction.REFRESH && outcome is OperationOutcome.Success && response.snapshot != null) {
                runCatching { update { it.copy(refreshRequired = it.refreshRequired - targetKey) } }
            }
            return outcome to response
        } finally {
            activeRequest.value = null
            requestPeers.remove(id)
            requestResults.remove(id)
            requestRevisions.remove(id)
        }
    }

    /** Gives an earlier request's result, for example one lost with a connection, a moment to arrive. */
    private suspend fun awaitPendingResults(device: PairedDevice): Boolean {
        val waiting = config.pending.filter { it.peer == device.id }
        if (waiting.isEmpty()) return true
        waiting.forEach { send(device, DeviceMessage("status", requestId = it.id)) }
        return withTimeoutOrNull(4_000) { while (config.pending.any { it.peer == device.id }) delay(100) } != null
    }

    /** Re-reads the selected card after a change whose final state did not arrive with its result. */
    private fun scheduleVerification() {
        if (verification?.isActive == true) return
        verification = scope.launch {
            delay(1_000)
            requestMutex.withLock {
                val selected = mutableView.value?.lpa?.selectedReader ?: return@withLock
                if (!connected || "${selected.deviceId}/${selected.sourceReaderId}" !in config.refreshRequired) return@withLock
                executeLocked(DeviceCommand(DeviceAction.REFRESH), reportFailure = false)
            }
        }
    }

    private fun operationFor(command: DeviceCommand): LpaOperation = when (command.action) {
        DeviceAction.REFRESH -> LpaOperation.Refreshing(context.getString(R.string.operation_reading_profiles))
        DeviceAction.SWITCH -> LpaOperation.Switching(command.iccid, command.enabled)
        DeviceAction.DELETE -> LpaOperation.Deleting(command.iccid)
        DeviceAction.RENAME -> LpaOperation.Renaming(command.iccid)
        DeviceAction.DOWNLOAD -> LpaOperation.Downloading(DownloadStage.PREPARING)
        DeviceAction.PROCESS_NOTIFICATION, DeviceAction.DELETE_NOTIFICATION -> LpaOperation.ProcessingNotification(command.number ?: 0)
        DeviceAction.RESEND_NOTIFICATION -> LpaOperation.ProcessingNotification(command.history?.sequenceNumber ?: 0)
        DeviceAction.RESET -> LpaOperation.Resetting(context.getString(R.string.operation_resetting_memory))
        DeviceAction.SET_SMDP -> LpaOperation.Refreshing(context.getString(R.string.operation_updating_default_smdp))
        DeviceAction.DISCOVER -> LpaOperation.Refreshing(context.getString(R.string.operation_discovering_profiles))
        // Profile metadata edits are instant for a local reader and show no card operation.
        else -> LpaOperation.Idle
    }

    fun decideDownload(confirmed: Boolean) {
        val id = activeRequest.value ?: return
        val device = requestPeers[id]?.let(::peer) ?: return
        scope.launch { send(device, DeviceMessage(if (confirmed) "decision" else "cancel", requestId = id, confirmed = confirmed)) }
    }

    fun clearFailure() { mutableView.update { it?.copy(lpa = it.lpa.copy(failure = null)) } }
    fun clearDownloadResult() { mutableView.update { it?.copy(lpa = it.lpa.copy(completedProfileDownload = null)) } }
    private fun OperationOutcome.failureOrNull() = when (this) {
        is OperationOutcome.Failed -> failure
        is OperationOutcome.Unverified -> failure
        else -> null
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
