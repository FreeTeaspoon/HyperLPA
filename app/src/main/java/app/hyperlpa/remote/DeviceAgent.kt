package app.hyperlpa.remote

import android.content.Context
import app.hyperlpa.R
import app.hyperlpa.data.LpaRepository
import app.hyperlpa.data.history.NotificationHistoryStore
import app.hyperlpa.data.metadata.ProfileIconStorage
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.metadata.providerIconKey
import app.hyperlpa.data.settings.AppSettingsStore
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.OperationFailure
import app.hyperlpa.domain.model.OperationOutcome
import app.hyperlpa.domain.model.ProfileState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Owns accepted commands independently of the controller connection and Android Activity. */
internal interface DeviceHost {
    val busy: Boolean
    fun isBusyWith(peer: String): Boolean
    fun accept(peer: PairedDevice, message: DeviceMessage, expires: Long)
    fun status(peer: PairedDevice, id: String)
    fun decision(peer: String, id: String, confirmed: Boolean)
    fun cancelPendingConfirmation()
}

internal class DeviceAgent(
    private val context: Context,
    private val local: LpaRepository,
    private val settings: AppSettingsStore,
    private val metadata: ProfileMetadataStore,
    private val history: NotificationHistoryStore,
    private val devices: RemoteDevices,
    private val scope: CoroutineScope,
) : DeviceHost {
    private val repository = LpaRepository(context, metadata, history)
    private val operationLock = Mutex()
    @Volatile private var activePeer: String? = null
    @Volatile private var activeId: String? = null
    @Volatile private var latest: DeviceMessage? = null
    private var previewTimeout: Job? = null
    private val cancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    override val busy: Boolean get() = activeId != null
    override fun isBusyWith(peer: String): Boolean = activePeer == peer

    override fun accept(peer: PairedDevice, message: DeviceMessage, expires: Long) {
        val command = message.command ?: return
        if (!message.requestId.matches(Regex("[a-f0-9-]{36}"))) return
        if (command.action == DeviceAction.READERS) {
            scope.launch {
                if (!operationLock.tryLock()) {
                    devices.send(peer, failed(message.requestId, R.string.remote_operation_running))
                    return@launch
                }
                val snapshot = try { runCatching {
                    local.withDeviceHostingSession {
                        repository.updateSettings(settings.settings.first())
                        repository.discoverReaders(autoConnect = false, includeRemoteReaders = true)
                        DeviceSnapshot(lpa = app.hyperlpa.data.LpaRepositoryState(
                            readers = repository.state.value.readers, initialized = true,
                        ))
                    }
                }.getOrNull() } finally { operationLock.unlock() }
                devices.send(peer, DeviceMessage("result", requestId = message.requestId, snapshot = snapshot,
                    outcome = OperationOutcome.Success, done = true))
            }
            return
        }
        val fingerprint = DeviceCrypto.fingerprint(DeviceJson.encodeToString(DeviceCommand.serializer(), command))
        val previous = devices.record(peer.id, message.requestId)
        if (previous != null) {
            if (previous.fingerprint == fingerprint) status(peer, message.requestId)
            else devices.send(peer, failed(message.requestId, R.string.remote_request_conflict))
            return
        }
        if (!operationLock.tryLock()) { devices.send(peer, failed(message.requestId, R.string.remote_operation_running)); return }
        val record = DeviceOperationRecord(peer.id, message.requestId, fingerprint, System.currentTimeMillis(), command.readerId, command.eid)
        try {
            devices.saveRecord(record)
        } catch (_: Exception) {
            operationLock.unlock()
            devices.send(peer, failed(message.requestId, R.string.remote_storage_unavailable))
            return
        }
        activePeer = peer.id
        activeId = message.requestId
        cancelRequested.set(false)
        latest = DeviceMessage("result", requestId = message.requestId)
        devices.send(peer, requireNotNull(latest))
        scope.launch {
            val wakeLock = context.getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "HyperLPA:remote-operation")
            var progress: Job? = null
            var cancellation: Job? = null
            val revision = AtomicLong(0)
            var executionStarted = false
            var committedResult: DeviceMessage? = null
            try {
                wakeLock.acquire(30 * 60 * 1000L)
                var finalSnapshot: DeviceSnapshot? = null
                val outcome = local.withDeviceHostingSession {
                    try {
                        check(System.currentTimeMillis() < expires) { context.getString(R.string.remote_command_expired) }
                        validate(command)
                        repository.updateSettings(settings.settings.first())
                        repository.discoverReaders(autoConnect = false, includeRemoteReaders = true)
                        val reader = repository.state.value.readers.firstOrNull { it.id == command.readerId && it.deviceId == null }
                            ?: error(context.getString(R.string.remote_reader_unavailable))
                        val connection = repository.connect(reader.id)
                        if (connection !is OperationOutcome.Success) return@withDeviceHostingSession connection
                        val actualEid = repository.state.value.euiccInfo?.eid
                        check(actualEid != null && (command.eid.isBlank() && command.action == DeviceAction.REFRESH || actualEid == command.eid)) {
                            context.getString(R.string.remote_card_changed)
                        }
                        progress = scope.launch {
                            repository.state.collect { state ->
                                // Keep the preview and installation progress tied to this operation.
                                val snapshot = snapshot(includeArtwork = false)
                                val update = DeviceMessage("result", requestId = message.requestId,
                                    snapshot = snapshot, revision = revision.incrementAndGet())
                                latest = update
                                devices.send(peer, update)
                                if (state.pendingProfileDownload != null && previewTimeout == null) {
                                    previewTimeout = scope.launch { delay(120_000); repository.cancelProfileDownload() }
                                }
                                delay(500)
                            }
                        }
                        check(!cancelRequested.get()) { context.getString(R.string.remote_download_cancelled) }
                        if (command.action == DeviceAction.DOWNLOAD) cancellation = scope.launch {
                            while (true) {
                                if (cancelRequested.get()) repository.cancelProfileDownload()
                                delay(100)
                            }
                        }
                        executionStarted = true
                        val result = execute(command)
                        progress.cancelAndJoin()
                        val completed = DeviceMessage("result", requestId = message.requestId,
                            outcome = result, done = true, revision = revision.incrementAndGet(),
                            downloadResult = repository.state.value.completedProfileDownload)
                        devices.saveRecord(record.copy(result = completed))
                        committedResult = completed
                        finalSnapshot = snapshot()
                        result
                    } finally {
                        withContext(NonCancellable) {
                            progress?.cancelAndJoin()
                            repository.disconnectSession()
                        }
                    }
                }
                val snapshot = finalSnapshot
                val result = DeviceMessage("result", requestId = message.requestId, snapshot = snapshot,
                    outcome = outcome, done = true, revision = revision.incrementAndGet(),
                    downloadResult = snapshot?.lpa?.completedProfileDownload)
                // Commit before reporting success. A repeated command can only retrieve this result.
                if (committedResult == null) devices.saveRecord(record.copy(result = result.copy(snapshot = null)))
                latest = result
                devices.send(peer, result)
            } catch (error: Exception) {
                progress?.cancel()
                cancellation?.cancel()
                val result = committedResult ?: if (executionStarted || error is CancellationException) DeviceMessage("result", requestId = message.requestId,
                    outcome = OperationOutcome.Unverified(OperationFailure(context.getString(R.string.remote_title), context.getString(R.string.remote_outcome_unknown))), done = true)
                else DeviceMessage("result", requestId = message.requestId,
                    outcome = OperationOutcome.Failed(OperationFailure(context.getString(R.string.remote_title),
                        error.message?.take(200) ?: context.getString(R.string.remote_action_failed))), done = true)
                if (committedResult == null) runCatching { devices.saveRecord(record.copy(result = result)) }
                devices.send(peer, result)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                progress?.cancel()
                cancellation?.cancel()
                previewTimeout?.cancel(); previewTimeout = null
                activeId = null; activePeer = null; latest = null
                operationLock.unlock()
            }
        }
    }

    override fun status(peer: PairedDevice, id: String) {
        val record = devices.record(peer.id, id)
        when {
            record?.result != null -> devices.send(peer, record.result)
            activePeer == peer.id && activeId == id -> latest?.let { devices.send(peer, it) }
            record == null -> devices.send(peer, DeviceMessage("missing", requestId = id))
        }
    }

    override fun decision(peer: String, id: String, confirmed: Boolean) {
        if (peer != activePeer || id != activeId) return
        if (confirmed && repository.state.value.pendingProfileDownload == null) return
        previewTimeout?.cancel(); previewTimeout = null
        if (confirmed) repository.confirmProfileDownload() else { cancelRequested.set(true); repository.cancelProfileDownload() }
    }

    override fun cancelPendingConfirmation() { if (repository.state.value.pendingProfileDownload != null) repository.cancelProfileDownload() }

    private fun validate(command: DeviceCommand) {
        require(command.readerId.length in 1..1024 && command.eid.length <= 32 && command.iccid.length <= 64)
        require(command.text.orEmpty().length <= 1024 && command.tags.size <= 32 && command.tags.all { it.length <= 80 })
        require(command.image.orEmpty().length <= 350_000)
        command.download?.let { download ->
            app.hyperlpa.domain.model.normalizeRspServerAddress(download.smdpAddress)
            require(download.matchingId.orEmpty().length <= 4096 && download.smdpOid.orEmpty().length <= 256)
            download.withConfirmationCode(download.confirmationCode)
            require(download.hasRequiredConfirmationCode && download.imei.orEmpty().length <= 16)
        }
        if (command.action in setOf(DeviceAction.DELETE, DeviceAction.RESET, DeviceAction.DELETE_NOTIFICATION, DeviceAction.DELETE_HISTORY))
            check(command.confirmed) { context.getString(R.string.remote_confirmation_required) }
        if (command.action != DeviceAction.REFRESH) require(command.eid.matches(Regex("[0-9]{32}")))
    }

    private suspend fun execute(command: DeviceCommand): OperationOutcome {
        val state = repository.state.value
        val profile = state.profiles.firstOrNull { it.iccid == command.iccid }
        if (command.action in setOf(DeviceAction.SWITCH, DeviceAction.DELETE, DeviceAction.RENAME,
                DeviceAction.TAGS, DeviceAction.PIN, DeviceAction.REMINDER, DeviceAction.ICON,
                DeviceAction.HIDE_PROVIDER_ICON, DeviceAction.APPLY_PROVIDER_ICON)) check(profile != null) {
            context.getString(R.string.remote_profile_missing)
        }
        return when (command.action) {
            DeviceAction.READERS, DeviceAction.REFRESH -> OperationOutcome.Success
            DeviceAction.SWITCH -> repository.setProfileEnabled(command.iccid, command.enabled, command.confirmed)
            DeviceAction.DELETE -> repository.deleteProfile(command.iccid)
            DeviceAction.RENAME -> repository.renameProfile(command.iccid, command.text.orEmpty())
            DeviceAction.DOWNLOAD -> repository.downloadProfile(requireNotNull(command.download), command.confirmBeforeInstall)
            DeviceAction.PROCESS_NOTIFICATION -> repository.processNotification(requireNotNull(command.number))
            DeviceAction.DELETE_NOTIFICATION -> repository.deleteNotification(requireNotNull(command.number))
            DeviceAction.RESEND_NOTIFICATION -> {
                val entry = requireNotNull(command.history)
                check(entry.eid == command.eid && history.history.value.contains(entry))
                repository.resendNotification(entry)
            }
            DeviceAction.DELETE_HISTORY -> {
                val entry = requireNotNull(command.history)
                check(entry.eid == command.eid && history.history.value.contains(entry))
                history.delete(entry)
                OperationOutcome.Success
            }
            DeviceAction.RESET -> repository.resetEuiccMemory()
            DeviceAction.SET_SMDP -> repository.setDefaultSmdpAddress(command.text.orEmpty())
            DeviceAction.DISCOVER -> repository.discoverProfiles(command.text)
            else -> {
                when (command.action) {
                    DeviceAction.TAGS -> metadata.setTags(command.iccid, command.tags)
                    DeviceAction.PIN -> metadata.setPinned(command.iccid, command.enabled)
                    DeviceAction.EUICC_NAME -> metadata.setEuiccName(command.eid, command.text)
                    DeviceAction.REMINDER -> {
                        if (command.number != null) settings.setScheduledReminders(true)
                        metadata.setReminder(command.iccid, command.text.orEmpty(), command.number?.let(Instant::ofEpochMilli), command.number != null)
                    }
                    DeviceAction.ICON -> {
                        val imageStore = ProfileIconStorage(context)
                        val pending = command.image?.let { image ->
                            val bytes = RemoteArtwork.normalize(DeviceCrypto.decode(image))
                            imageStore.createPendingImport("remote-profile").also { it.stagingFile.writeBytes(bytes) }
                        }
                        var committed = false
                        try {
                            val uri = pending?.let(imageStore::promote)
                            if (command.applyToProvider) {
                                val provider = requireNotNull(providerIconKey(profile?.providerName))
                                metadata.setProviderIconAndClearProfileOverrides(provider, uri,
                                    state.profiles.filter { providerIconKey(it.providerName) == provider }.map { it.iccid })
                            } else metadata.setIconUri(command.iccid, uri, profile?.providerName)
                            committed = true
                        } finally { if (!committed && pending != null) imageStore.discard(pending) }
                        metadata.cleanupOrphanedIconFiles()
                    }
                    DeviceAction.HIDE_PROVIDER_ICON -> metadata.setProviderIconHidden(command.iccid, command.enabled, profile?.providerName)
                    DeviceAction.APPLY_PROVIDER_ICON -> {
                        val provider = requireNotNull(providerIconKey(profile?.providerName))
                        val metadataSnapshot = metadata.snapshot()
                        val uri = metadataSnapshot.metadata[command.iccid]?.iconUri ?: metadataSnapshot.providerIcons[provider]
                        check(uri != null)
                        metadata.setProviderIconAndClearProfileOverrides(provider, uri,
                            state.profiles.filter { providerIconKey(it.providerName) == provider }.map { it.iccid })
                    }
                    else -> error("Unsupported operation")
                }
                OperationOutcome.Success
            }
        }
    }

    private suspend fun snapshot(includeArtwork: Boolean = true): DeviceSnapshot {
        val state = repository.state.value
        val ids = state.profiles.map { it.iccid }.toSet()
        val extras = metadata.metadata.first().filterKeys { it in ids }
        val providers = metadata.providerIcons.first().filterKeys { provider -> state.profiles.any { providerIconKey(it.providerName) == provider } }
        val images = linkedMapOf<String, String>()
        fun image(uri: String?): String? = uri?.let {
            if (!includeArtwork) return@let null
            runCatching {
                val bytes = RemoteArtwork.read(context, it, 256)
                val encoded = DeviceCrypto.encode(bytes)
                val key = DeviceCrypto.fingerprint(encoded)
                images[key] = encoded
                key
            }.getOrNull()
        }
        return DeviceSnapshot(
            lpa = state.copy(logs = state.logs.takeLast(30), profiles = state.profiles.map {
                if (includeArtwork) it else it.copy(iconBase64 = null)
            }),
            metadata = extras.mapValues { (_, value) -> value.copy(iconUri = image(value.iconUri)) },
            providerIcons = providers.mapNotNull { (key, uri) -> image(uri)?.let { key to it } }.toMap(),
            euiccNames = metadata.euiccNames.first().filterKeys { it == state.euiccInfo?.eid },
            history = if (includeArtwork) history.history.value.filter { it.eid == state.euiccInfo?.eid } else emptyList(), images = images,
            artworkIncluded = includeArtwork,
        )
    }

    private fun failed(id: String, message: Int) = DeviceMessage("result", requestId = id, done = true,
        outcome = OperationOutcome.Failed(OperationFailure(context.getString(R.string.remote_title), context.getString(message))))
}
