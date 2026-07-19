package app.hyperlpa.data

import android.content.Context
import androidx.compose.runtime.Immutable
import app.hyperlpa.data.cloud.decodeMccMnc
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ActivityLogEntry
import app.hyperlpa.domain.model.DownloadRequest
import app.hyperlpa.domain.model.DownloadStage
import app.hyperlpa.domain.model.EuiccInfo
import app.hyperlpa.domain.model.LogLevel
import app.hyperlpa.domain.model.LpaNotification
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.NotificationOperation
import app.hyperlpa.domain.model.OperationFailure
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.lpa.LpaSession
import app.hyperlpa.lpa.LpaSessionFactory
import app.hyperlpa.lpa.ReaderEndpoint
import app.hyperlpa.lpa.ReaderProvider
import app.hyperlpa.lpa.platform.NBridgeReaderProvider
import app.hyperlpa.lpa.platform.BluetoothLeReaderProvider
import app.hyperlpa.lpa.platform.OmapiReaderProvider
import app.hyperlpa.lpa.platform.RemoteReaderProvider
import app.hyperlpa.lpa.platform.TelephonyReaderProvider
import app.hyperlpa.lpa.platform.UsbCcidReaderProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.LocalProfileNotification
import net.typeblog.lpac_jni.ProfileDownloadInput
import net.typeblog.lpac_jni.ProfileDownloadState
import net.typeblog.lpac_jni.RemoteProfileInfo
import java.time.Instant

@Immutable
data class LpaRepositoryState(
    val readers: List<ReaderInfo> = emptyList(),
    val selectedReaderId: String? = null,
    val profiles: List<ProfileInfo> = emptyList(),
    val notifications: List<LpaNotification> = emptyList(),
    val euiccInfo: EuiccInfo? = null,
    val operation: LpaOperation = LpaOperation.Idle,
    val failure: OperationFailure? = null,
    val initialized: Boolean = false,
    val logs: List<ActivityLogEntry> = emptyList(),
) {
    val selectedReader: ReaderInfo?
        get() = readers.firstOrNull { it.id == selectedReaderId }
}

class LpaRepository(
    context: Context,
    private val metadataStore: ProfileMetadataStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val providers = listOf<Pair<ReaderKind, ReaderProvider>>(
        ReaderKind.NBRIDGE to NBridgeReaderProvider(context),
        ReaderKind.OMAPI to OmapiReaderProvider(context),
        ReaderKind.TELEPHONY to TelephonyReaderProvider(context),
        ReaderKind.USB_CCID to UsbCcidReaderProvider(context),
        ReaderKind.BLE to BluetoothLeReaderProvider(context),
        ReaderKind.REMOTE to RemoteReaderProvider { settings.remoteReaderUrls },
    )
    private val endpointById = linkedMapOf<String, ReaderEndpoint>()
    private val operationMutex = Mutex()
    private var settings = AppSettings()
    private var session: LpaSession? = null
    private val mutableState = MutableStateFlow(LpaRepositoryState())

    val state: StateFlow<LpaRepositoryState> = mutableState.asStateFlow()

    fun updateSettings(value: AppSettings) {
        settings = value
    }

    suspend fun discoverReaders(autoConnect: Boolean = true) = operationMutex.withLock {
        withOperation(LpaOperation.DiscoveringReaders()) {
            val previouslySelectedId = mutableState.value.selectedReaderId
            endpointById.clear()
            val endpoints = providers
                .filter { (kind, _) -> kind.enabledBy(settings) }
                .flatMap { (_, provider) ->
                    try {
                        provider.listReaders()
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        log(LogLevel.WARNING, "Reader", error.message ?: error.toString())
                        emptyList()
                    }
                }
            endpoints.forEach { endpoint -> endpointById[endpoint.info.id] = endpoint }
            mutableState.value = mutableState.value.copy(
                readers = endpoints.map(ReaderEndpoint::info),
                initialized = true,
                failure = null,
            )

            if (autoConnect && endpoints.isNotEmpty()) {
                val available = endpoints.filter { endpoint -> endpoint.info.available }
                val preferred = settings.lastReaderId
                    ?.let(endpointById::get)
                    ?.takeIf { endpoint -> endpoint.info.available }
                val current = previouslySelectedId
                    ?.let(endpointById::get)
                    ?.takeIf { endpoint -> endpoint.info.available }
                val candidates = buildList {
                    preferred?.let(::add)
                    current?.takeIf { endpoint -> endpoint !== preferred }?.let(::add)
                    available.filterTo(this) { endpoint -> endpoint !== preferred && endpoint !== current }
                }
                var connected = false
                var lastFailure: Throwable? = null
                if (session != null && current != null) {
                    try {
                        refreshInternal()
                        connected = true
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        lastFailure = error
                    }
                }
                for (candidate in candidates) {
                    if (connected) break
                    try {
                        connectInternal(candidate)
                        connected = true
                        break
                    } catch (error: Throwable) {
                        lastFailure = error
                        log(
                            LogLevel.WARNING,
                            "Reader",
                            "${candidate.info.name} could not be opened: ${error.message ?: error::class.java.simpleName}",
                        )
                    }
                }
                if (!connected) {
                    closeSession()
                    val failure = lastFailure?.toFailure()
                    mutableState.value = mutableState.value.copy(
                        selectedReaderId = null,
                        profiles = emptyList(),
                        notifications = emptyList(),
                        euiccInfo = null,
                        failure = failure,
                    )
                }
            } else if (endpoints.isEmpty() || endpoints.none { endpoint -> endpoint.info.available }) {
                closeSession()
                mutableState.value = mutableState.value.copy(
                    selectedReaderId = null,
                    profiles = emptyList(),
                    notifications = emptyList(),
                    euiccInfo = null,
                )
            }
        }
    }

    suspend fun connect(readerId: String) = operationMutex.withLock {
        val endpoint = endpointById[readerId]
            ?: throw IllegalArgumentException("Reader $readerId is not available")
        withOperation(LpaOperation.Connecting(endpoint.info.name)) {
            connectInternal(endpoint)
        }
    }

    suspend fun refresh() = operationMutex.withLock {
        withOperation(LpaOperation.Refreshing()) {
            try {
                refreshInternal()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reconnectSelectedWithRetry()
            }
        }
    }

    suspend fun setProfileEnabled(iccid: String, enabled: Boolean) = operationMutex.withLock {
        withOperation(LpaOperation.Switching(iccid, enabled)) {
            prepareOperationSession()
            val assistant = requireSession().assistant
            var modemRefreshRequested = true
            var successful = assistant.switchProfile(iccid, enabled, refresh = true)
            if (!successful) {
                modemRefreshRequested = false
                log(
                    LogLevel.WARNING,
                    "Profile",
                    "The modem refresh request failed; retrying the profile switch without it",
                )
                successful = assistant.switchProfile(iccid, enabled, refresh = false)
            }
            check(successful) { if (enabled) "Profile could not be enabled" else "Profile could not be disabled" }
            updateProfileState(iccid, enabled)
            refreshAfterMutation("Profile switch", reconnectFirst = modemRefreshRequested)
            if (settings.notificationAfterSwitch) {
                processNotificationsSafely("profile switch")
                refreshAfterMutation("Profile switch notifications")
            }
        }
    }

    suspend fun deleteProfile(iccid: String) = operationMutex.withLock {
        withOperation(LpaOperation.Deleting(iccid)) {
            prepareOperationSession()
            check(requireSession().assistant.deleteProfile(iccid)) { "Profile could not be deleted" }
            mutableState.value = mutableState.value.copy(
                profiles = mutableState.value.profiles.filterNot { profile -> profile.iccid == iccid },
            )
            if (settings.notificationAfterDelete) processNotificationsSafely("profile deletion")
            refreshAfterMutation("Profile deletion")
        }
    }

    suspend fun renameProfile(iccid: String, nickname: String) = operationMutex.withLock {
        withOperation(LpaOperation.Renaming(iccid)) {
            prepareOperationSession()
            val trimmedNickname = nickname.trim()
            requireSession().assistant.setNickname(iccid, trimmedNickname)
            mutableState.value = mutableState.value.copy(
                profiles = mutableState.value.profiles.map { profile ->
                    if (profile.iccid == iccid) profile.copy(nickname = trimmedNickname) else profile
                },
            )
            refreshAfterMutation("Profile rename")
        }
    }

    suspend fun downloadProfile(request: DownloadRequest) = operationMutex.withLock {
        withOperation(LpaOperation.Downloading(DownloadStage.PREPARING)) {
            prepareOperationSession()
            if (settings.notificationBeforeDownload) processNotificationsSafely("profile download preparation")
            val assistant = requireSession().assistant
            val profilesBeforeDownload = mutableState.value.profiles.map(ProfileInfo::iccid).toSet()
            val initialFreeMemory = try {
                assistant.euiccInfo2?.freeNvram
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value.euiccInfo?.freeNonVolatileMemory
            }
            var remoteProfile: RemoteProfileInfo? = null
            assistant.downloadProfile(
                ProfileDownloadInput(
                    address = request.smdpAddress,
                    matchingId = request.matchingId,
                    imei = request.imei,
                    confirmationCode = request.confirmationCode,
                ),
            ) { stage ->
                val mapped = when (stage) {
                    is ProfileDownloadState.Preparing -> DownloadStage.PREPARING
                    is ProfileDownloadState.Connecting -> DownloadStage.CONNECTING
                    is ProfileDownloadState.Authenticating -> DownloadStage.AUTHENTICATING
                    is ProfileDownloadState.ConfirmingDownload -> DownloadStage.CONFIRMING
                    is ProfileDownloadState.Downloading -> DownloadStage.DOWNLOADING
                    is ProfileDownloadState.Finalizing -> DownloadStage.FINALIZING
                }
                val metadata = (stage as? ProfileDownloadState.ConfirmingDownload)?.metadata
                if (metadata != null) remoteProfile = metadata
                mutableState.value = mutableState.value.copy(
                    operation = LpaOperation.Downloading(mapped, metadata?.name),
                )
                true
            }
            val postInstallFreeMemory = try {
                assistant.euiccInfo2?.freeNvram
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                null
            }
            if (settings.notificationAfterDownload) processNotificationsSafely("profile download")
            refreshAfterMutation("Profile download")
            val installedIccid = remoteProfile
                ?.iccid
                ?.takeIf(String::isNotBlank)
                ?: mutableState.value.profiles
                    .firstOrNull { it.iccid !in profilesBeforeDownload }
                    ?.iccid
            if (installedIccid != null) {
                val finalFreeMemory =
                    postInstallFreeMemory ?: mutableState.value.euiccInfo?.freeNonVolatileMemory
                val installedBytes = if (initialFreeMemory != null && finalFreeMemory != null) {
                    (initialFreeMemory - finalFreeMemory).toLong().takeIf { it > 0 }
                } else {
                    null
                }
                try {
                    metadataStore.setCloudData(
                        iccid = installedIccid,
                        smdpAddress = request.smdpAddress,
                        installedBytes = installedBytes,
                        eid = mutableState.value.euiccInfo?.eid,
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    log(
                        LogLevel.WARNING,
                        "Nekoko Cloud",
                        "The profile was installed, but its measured size could not be saved",
                    )
                }
            }
        }
    }

    suspend fun processNotification(sequenceNumber: Long) = operationMutex.withLock {
        withOperation(LpaOperation.ProcessingNotification(sequenceNumber)) {
            prepareOperationSession()
            val assistant = requireSession().assistant
            check(assistant.handleNotification(sequenceNumber)) { "Notification could not be sent" }
            if (settings.notificationAutoRemove) {
                check(assistant.deleteNotification(sequenceNumber)) { "Notification was sent but could not be removed" }
                removeNotification(sequenceNumber)
            }
            refreshAfterMutation("Notification processing")
        }
    }

    suspend fun deleteNotification(sequenceNumber: Long) = operationMutex.withLock {
        withOperation(LpaOperation.ProcessingNotification(sequenceNumber)) {
            prepareOperationSession()
            check(requireSession().assistant.deleteNotification(sequenceNumber)) {
                "Notification could not be removed"
            }
            removeNotification(sequenceNumber)
            refreshAfterMutation("Notification removal")
        }
    }

    suspend fun resetEuiccMemory() = operationMutex.withLock {
        withOperation(LpaOperation.Resetting()) {
            prepareOperationSession()
            requireSession().assistant.euiccMemoryReset()
            mutableState.value = mutableState.value.copy(
                profiles = emptyList(),
                notifications = emptyList(),
            )
            refreshAfterMutation("eUICC memory reset", reconnectFirst = true)
        }
    }

    fun clearFailure() {
        mutableState.value = mutableState.value.copy(failure = null)
    }

    private suspend fun connectInternal(endpoint: ReaderEndpoint) = withContext(ioDispatcher) {
        closeSession()
        log(LogLevel.INFO, "Reader", "Connecting to ${endpoint.info.name}")
        val opened = LpaSessionFactory.open(endpoint, settings)
        session = opened
        mutableState.value = mutableState.value.copy(selectedReaderId = endpoint.info.id)
        refreshInternal()
        log(LogLevel.INFO, "Reader", "Connected to ${endpoint.info.name}")
    }

    private suspend fun reconnectSelected() {
        val selectedId = mutableState.value.selectedReaderId
            ?: throw IllegalStateException("Select an eUICC reader first")
        val endpoint = endpointById[selectedId]
            ?: throw IllegalStateException("The selected eUICC reader is no longer available")
        connectInternal(endpoint)
    }

    private suspend fun reconnectSelectedWithRetry(attempts: Int = 5) {
        require(attempts > 0)
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                reconnectSelected()
                return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastFailure = error
                if (attempt < attempts - 1) delay(300L * (attempt + 1))
            }
        }
        throw IllegalStateException("The eUICC did not reconnect", lastFailure)
    }

    private suspend fun prepareOperationSession() {
        if (session == null) reconnectSelectedWithRetry(attempts = 3)
    }

    private suspend fun refreshAfterMutation(
        operationName: String,
        reconnectFirst: Boolean = false,
    ) {
        lateinit var refreshFailure: Throwable
        try {
            if (reconnectFirst) reconnectSelectedWithRetry(attempts = 8) else refreshInternal()
            return
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            refreshFailure = error
        }

        if (!reconnectFirst) {
            try {
                reconnectSelectedWithRetry(attempts = 3)
                return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                refreshFailure = error
            }
        }

        log(
            LogLevel.WARNING,
            "Reader",
            "$operationName succeeded, but the card state could not be refreshed: " +
                (refreshFailure.message ?: refreshFailure.javaClass.simpleName),
        )
    }

    private suspend fun refreshInternal() = withContext(ioDispatcher) {
        val active = requireSession()
        val assistant = active.assistant
        val eid = assistant.eID
        val info = assistant.euiccInfo2
        mutableState.value = mutableState.value.copy(
            profiles = assistant.profiles.map(::mapProfile),
            notifications = assistant.notifications.map(::mapNotification),
            euiccInfo = EuiccInfo(
                eid = eid,
                sgp22Version = info?.sgp22Version?.toString().orEmpty(),
                profileVersion = info?.profileVersion?.toString().orEmpty(),
                firmwareVersion = info?.euiccFirmwareVersion?.toString().orEmpty(),
                globalPlatformVersion = info?.globalPlatformVersion?.toString().orEmpty(),
                sasAccreditationNumber = info?.sasAccreditationNumber.orEmpty(),
                protectionProfileVersion = info?.ppVersion?.toString().orEmpty(),
                freeNonVolatileMemory = info?.freeNvram,
                freeVolatileMemory = info?.freeRam,
                signingKeyIds = info?.euiccCiPKIdListForSigning.orEmpty(),
                verificationKeyIds = info?.euiccCiPKIdListForVerification.orEmpty(),
            ),
            failure = null,
        )
    }

    private fun processNotificationsInternal() {
        if (!settings.notificationAutoSend) return
        val assistant = requireSession().assistant
        assistant.notifications.forEach { notification ->
            if (assistant.handleNotification(notification.seqNumber) && settings.notificationAutoRemove) {
                assistant.deleteNotification(notification.seqNumber)
            }
        }
    }

    private fun processNotificationsSafely(operationName: String) {
        try {
            processNotificationsInternal()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log(
                LogLevel.WARNING,
                "Notifications",
                "Could not process notifications after $operationName: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun updateProfileState(iccid: String, enabled: Boolean) {
        mutableState.value = mutableState.value.copy(
            profiles = mutableState.value.profiles.map { profile ->
                when {
                    profile.iccid == iccid -> profile.copy(
                        state = if (enabled) ProfileState.ENABLED else ProfileState.DISABLED,
                    )
                    enabled && profile.state == ProfileState.ENABLED -> profile.copy(state = ProfileState.DISABLED)
                    else -> profile
                }
            },
        )
    }

    private fun removeNotification(sequenceNumber: Long) {
        mutableState.value = mutableState.value.copy(
            notifications = mutableState.value.notifications.filterNot { notification ->
                notification.sequenceNumber == sequenceNumber
            },
        )
    }

    private suspend fun withOperation(operation: LpaOperation, block: suspend () -> Unit) {
        mutableState.value = mutableState.value.copy(operation = operation, failure = null)
        val operationName = operation.logName()
        log(LogLevel.INFO, "LPA", "$operationName started")
        try {
            withContext(ioDispatcher) { block() }
            log(LogLevel.INFO, "LPA", "$operationName completed")
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val failure = error.toFailure()
            log(LogLevel.ERROR, failure.title, failure.message)
            mutableState.value = mutableState.value.copy(failure = failure)
        } finally {
            mutableState.value = mutableState.value.copy(operation = LpaOperation.Idle)
        }
    }

    private fun requireSession(): LpaSession = session ?: throw IllegalStateException("Select an eUICC reader first")

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        val entry = ActivityLogEntry(Instant.now(), level, tag, message)
        mutableState.value = mutableState.value.copy(
            logs = (mutableState.value.logs + entry).takeLast(500),
        )
    }

    override fun close() {
        closeSession()
        providers.forEach { (_, provider) -> provider.close() }
    }
}

private fun LocalProfileAssistant.switchProfile(
    iccid: String,
    enabled: Boolean,
    refresh: Boolean,
): Boolean = if (enabled) {
    enableProfile(iccid, refresh)
} else {
    disableProfile(iccid, refresh)
}

private fun LpaOperation.logName(): String = when (this) {
    LpaOperation.Idle -> "LPA operation"
    is LpaOperation.DiscoveringReaders -> "Reader discovery"
    is LpaOperation.Connecting -> "Reader connection"
    is LpaOperation.Refreshing -> "Card refresh"
    is LpaOperation.Switching -> if (enable) "Profile enable" else "Profile disable"
    is LpaOperation.Deleting -> "Profile deletion"
    is LpaOperation.Renaming -> "Profile rename"
    is LpaOperation.Downloading -> "Profile download"
    is LpaOperation.ProcessingNotification -> "Notification processing"
    is LpaOperation.Resetting -> "eUICC memory reset"
}

private fun ReaderKind.enabledBy(settings: AppSettings): Boolean = when (this) {
    ReaderKind.NBRIDGE -> settings.enableNBridge
    ReaderKind.OMAPI -> settings.enableOmapi
    ReaderKind.TELEPHONY -> settings.enableTelephony
    ReaderKind.USB_CCID -> settings.enableUsbCcid
    ReaderKind.BLE -> settings.enableBle
    ReaderKind.REMOTE -> settings.enableRemote
}

private fun mapProfile(profile: LocalProfileInfo): ProfileInfo {
    val network = decodeMccMnc(profile.mccMnc)
    return ProfileInfo(
        iccid = profile.iccid,
        state = when (profile.state) {
            LocalProfileInfo.State.Enabled -> ProfileState.ENABLED
            LocalProfileInfo.State.Disabled -> ProfileState.DISABLED
        },
        name = profile.name,
        nickname = profile.nickName,
        providerName = profile.providerName,
        isdPAid = profile.isdpAID,
        profileClass = when (profile.profileClass) {
            net.typeblog.lpac_jni.ProfileClass.Operational -> ProfileClass.OPERATIONAL
            net.typeblog.lpac_jni.ProfileClass.Testing -> ProfileClass.TESTING
            net.typeblog.lpac_jni.ProfileClass.Provisioning -> ProfileClass.PROVISIONING
        },
        iconBase64 = profile.iconBase64,
        mcc = network?.mcc,
        mnc = network?.mnc,
        gid1 = profile.gid1,
        gid2 = profile.gid2,
        smdpAddress = profile.notificationAddress,
    )
}

private fun mapNotification(notification: LocalProfileNotification): LpaNotification = LpaNotification(
    sequenceNumber = notification.seqNumber,
    operation = when (notification.profileManagementOperation) {
        LocalProfileNotification.Operation.Install -> NotificationOperation.INSTALL
        LocalProfileNotification.Operation.Enable -> NotificationOperation.ENABLE
        LocalProfileNotification.Operation.Disable -> NotificationOperation.DISABLE
        LocalProfileNotification.Operation.Delete -> NotificationOperation.DELETE
    },
    address = notification.notificationAddress,
    iccid = notification.iccid,
)

private fun Throwable.toFailure(): OperationFailure {
    val root = generateSequence(this) { it.cause }.last()
    return when (this) {
        is LocalProfileAssistant.ProfileDownloadException -> OperationFailure(
            title = "Profile download failed",
            message = lpaErrorReason.ifBlank { root.message ?: "The SM-DP+ session failed" },
            diagnostic = buildString {
                lastHttpResponse?.let { append("HTTP ${it.rcode}\n") }
                lastHttpException?.message?.let { append("HTTP: $it\n") }
                lastApduResponse?.let { append("APDU: ${it.joinToString("") { byte -> "%02X".format(byte) }}\n") }
                lastApduException?.message?.let { append("Card: $it") }
            }.trim().ifEmpty { null },
        )
        else -> if (root is SecurityException) {
            OperationFailure(
                title = "SIM access denied",
                message = "The eUICC did not authorize this APK signing certificate. Use its matching vendor/community build, NBridge, or privileged/root access.",
                diagnostic = root.message,
            )
        } else {
            OperationFailure(
                title = "LPA operation failed",
                message = root.message ?: root::class.java.simpleName,
                diagnostic = stackTraceToString(),
            )
        }
    }
}
