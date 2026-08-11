package app.hyperlpa.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Immutable
import app.hyperlpa.BuildConfig
import app.hyperlpa.R
import app.hyperlpa.data.cloud.decodeMccMnc
import app.hyperlpa.data.history.NotificationHistoryAction
import app.hyperlpa.data.history.NotificationHistoryStatus
import app.hyperlpa.data.history.NotificationHistoryStore
import app.hyperlpa.data.history.NotificationHistoryTrigger
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
import app.hyperlpa.domain.model.OperationOutcome
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileDownloadPreview
import app.hyperlpa.domain.model.ProfileDownloadResult
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.domain.model.normalizeRspServerAddress
import app.hyperlpa.lpa.LpaSession
import app.hyperlpa.lpa.LpaSessionFactory
import app.hyperlpa.lpa.ReaderEndpoint
import app.hyperlpa.lpa.ReaderProvider
import app.hyperlpa.lpa.platform.NBridgeReaderProvider
import app.hyperlpa.lpa.platform.BluetoothLeReaderProvider
import app.hyperlpa.lpa.platform.OmapiReaderProvider
import app.hyperlpa.lpa.platform.RemoteReaderConfig
import app.hyperlpa.lpa.platform.RemoteReaderProvider
import app.hyperlpa.lpa.platform.TelephonyReaderProvider
import app.hyperlpa.lpa.platform.UsbCcidReaderProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.LocalProfileNotification
import net.typeblog.lpac_jni.ProfileDownloadInput
import net.typeblog.lpac_jni.ProfileDownloadState
import net.typeblog.lpac_jni.RemoteProfileInfo
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

private const val MaxReaderProfiles = 128
private const val MaxReaderNotifications = 128

internal fun shouldAttemptInitialNotificationDelivery(
    notificationAutoSend: Boolean,
    hasValidatedInternet: Boolean,
): Boolean = notificationAutoSend && hasValidatedInternet

private enum class SessionRefreshScope {
    FULL,
    PROFILE_SWITCH_STATE,
}

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
) {
    val selectedReader: ReaderInfo?
        get() = readers.firstOrNull { it.id == selectedReaderId }
}

/** Identifies both the logical reader and the physical eUICC currently behind it. */
internal data class ReaderAffinity(
    val readerId: String,
    val eid: String,
)

internal sealed interface BoundProfileDownloadResult {
    data class Attempted(val outcome: OperationOutcome) : BoundProfileDownloadResult
    data object ReaderMismatch : BoundProfileDownloadResult
}

class LpaRepository(
    context: Context,
    private val metadataStore: ProfileMetadataStore,
    private val notificationHistoryStore: NotificationHistoryStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val verboseLoggingEnabled = MutableStateFlow(false)
    private val providers = listOf<Pair<ReaderKind, ReaderProvider>>(
        ReaderKind.NBRIDGE to NBridgeReaderProvider(appContext),
        ReaderKind.OMAPI to OmapiReaderProvider(appContext),
        ReaderKind.TELEPHONY to TelephonyReaderProvider(appContext),
        ReaderKind.USB_CCID to UsbCcidReaderProvider(appContext, verboseLoggingEnabled),
        ReaderKind.BLE to BluetoothLeReaderProvider(appContext),
        ReaderKind.REMOTE to RemoteReaderProvider(appContext) {
            settings.remoteReaderUrls.map { endpointUrl ->
                RemoteReaderConfig(
                    endpointUrl = endpointUrl,
                    bearerToken = settings.remoteReaderTokens[endpointUrl],
                )
            }
        },
    )
    private val endpointById = linkedMapOf<String, ReaderEndpoint>()
    private val operationMutex = Mutex()
    private val downloadDecisionLock = Any()
    private val downloadCancellationRequested = AtomicBoolean(false)
    private val downloadOutcomeRequiresRefresh = AtomicBoolean(false)
    private val mutationOutcomeRequiresRefresh = AtomicBoolean(false)
    private var pendingDownloadDecision: CompletableFuture<Boolean>? = null
    @Volatile
    private var settings = AppSettings()
    private var session: LpaSession? = null
    // Survives a failed refresh/reconnect so later discovery cycles cannot silently choose a
    // different card. Explicit disconnect or a new manual selection changes this target.
    private var selectedReaderTargetId: String? = null
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(LpaRepositoryState())

    val state: StateFlow<LpaRepositoryState> = mutableState.asStateFlow()

    fun updateSettings(value: AppSettings) {
        settings = value
        verboseLoggingEnabled.value = value.developerMode && value.apduLogging
    }

    /**
     * Serializes an external application-state transaction (such as backup restore) against all
     * reader and eUICC operations. The block must not call another repository operation because
     * [operationMutex] is deliberately non-reentrant.
     */
    internal suspend fun <T> withExclusiveOperation(block: suspend () -> T): T =
        operationMutex.withLock { block() }

    /**
     * Replaces application state with every reader disconnected and no previously discovered
     * endpoint retained. In particular, remote endpoints capture an authenticated client when
     * discovered; clearing [endpointById] ensures a client holding a pre-restore bearer token can
     * never be reused after the settings transaction. The authoritative committed settings are
     * re-read and readers are rediscovered without reconnecting whether [block] commits or rolls
     * back.
     */
    internal suspend fun <T> withReadersDisconnectedForStateReplacement(
        readCommittedSettings: suspend () -> AppSettings,
        block: suspend () -> T,
    ): T = operationMutex.withLock {
        disconnectReadersForStateReplacementLocked()
        var blockFailure: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            blockFailure = error
            throw error
        } finally {
            try {
                val committedSettings = readCommittedSettings()
                updateSettings(committedSettings)
                discoverReadersInternal(
                    autoConnect = false,
                    includeRemoteReaders = committedSettings.autoLoadRemoteReaders,
                )
            } catch (rebuildFailure: Throwable) {
                if (blockFailure != null) {
                    blockFailure.addSuppressed(rebuildFailure)
                } else {
                    // The replacement already committed and every credential-capturing endpoint
                    // was cleared before it began. A discovery failure must not misreport that
                    // durable commit as failed; the next explicit/observed refresh can retry.
                    log(
                        LogLevel.WARNING,
                        "Reader",
                        "Application state was replaced, but readers could not be rebuilt: " +
                            (rebuildFailure.message ?: rebuildFailure.javaClass.simpleName),
                    )
                }
            }
        }
    }

    suspend fun discoverReaders(
        autoConnect: Boolean = true,
        includeRemoteReaders: Boolean = settings.autoLoadRemoteReaders,
    ) = operationMutex.withLock {
        withOperation(LpaOperation.DiscoveringReaders(appContext.getString(R.string.reader_loading))) {
            discoverReadersInternal(autoConnect, includeRemoteReaders)
        }
    }

    /**
     * Drops every endpoint that captured an old remote-reader credential and rebuilds it from
     * the committed settings. If a remote reader was selected, reconnect only that exact reader;
     * never fall through to a different attached card because a rotated credential was rejected.
     * Waiting on [operationMutex] lets an in-flight state-changing eUICC command finish first.
     */
    suspend fun reloadRemoteReadersAfterConfigurationChange() = operationMutex.withLock {
        withOperation(LpaOperation.DiscoveringReaders(appContext.getString(R.string.reader_loading))) {
            val selectedRemoteId = mutableState.value.selectedReader
                ?.takeIf { reader -> reader.kind == ReaderKind.REMOTE }
                ?.id
            val preservedNonRemoteEndpoint = if (selectedRemoteId == null) {
                mutableState.value.selectedReaderId
                    ?.let(endpointById::get)
                    ?.takeIf { endpoint -> endpoint.info.kind.enabledBy(settings) }
            } else {
                null
            }
            if (selectedRemoteId != null) {
                closeSession()
                mutableState.value = mutableState.value.copy(
                    selectedReaderId = null,
                    profiles = emptyList(),
                    notifications = emptyList(),
                    euiccInfo = null,
                    pendingProfileDownload = null,
                    completedProfileDownload = null,
                    discoveredSmdpAddresses = emptyList(),
                )
            }
            discoverReadersInternal(
                autoConnect = false,
                includeRemoteReaders = true,
                preserveSelectedSession = preservedNonRemoteEndpoint != null,
            )
            if (
                preservedNonRemoteEndpoint != null &&
                session != null &&
                preservedNonRemoteEndpoint.info.id !in endpointById
            ) {
                endpointById[preservedNonRemoteEndpoint.info.id] = preservedNonRemoteEndpoint
                mutableState.value = mutableState.value.copy(
                    readers = (mutableState.value.readers + preservedNonRemoteEndpoint.info)
                        .distinctBy(ReaderInfo::id),
                )
            }
            selectedRemoteId
                ?.let(endpointById::get)
                ?.takeIf { endpoint -> endpoint.info.available }
                ?.let { endpoint -> connectInternal(endpoint) }
        }
    }

    private suspend fun discoverReadersInternal(
        autoConnect: Boolean,
        includeRemoteReaders: Boolean,
        preserveSelectedSession: Boolean = false,
    ) {
            val previouslySelectedId = mutableState.value.selectedReaderId ?: selectedReaderTargetId
            val previouslySelectedEndpoint = previouslySelectedId?.let(endpointById::get)
            endpointById.clear()
            val enabledProviders = providers
                .filter { (kind, _) ->
                    kind.enabledBy(settings) && (kind != ReaderKind.REMOTE || includeRemoteReaders)
                }
            val discoveredByProvider = supervisorScope {
                enabledProviders.map { (kind, provider) ->
                    async {
                        val result = try {
                            Result.success(provider.listReaders())
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            Result.failure(error)
                        }
                        kind to result
                    }
                }.awaitAll()
            }
            val discoveredEndpoints = buildList {
                discoveredByProvider.forEach { (kind, result) ->
                    result.onFailure { error ->
                        log(
                            LogLevel.WARNING,
                            "Reader",
                            "$kind discovery failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }.getOrDefault(emptyList()).forEach { endpoint ->
                        val existing = endpointById[endpoint.info.id]
                        if (existing == null) {
                            endpointById[endpoint.info.id] = endpoint
                            add(endpoint)
                        } else {
                            log(
                                LogLevel.WARNING,
                                "Reader",
                                "Ignoring a duplicate reader identifier from $kind; keeping the first reader",
                            )
                        }
                    }
                }
            }
            // A manually selected reader remains part of discovery while its live session is
            // usable, even when that provider is omitted from automatic discovery (notably a
            // remote reader when auto-load is disabled). A refresh below still proves whether
            // the session remains usable; it can never cause selection of a different reader.
            val retainedSelectedEndpoint = previouslySelectedEndpoint
                ?.takeIf { endpoint ->
                    session != null &&
                        endpoint.info.kind.enabledBy(settings) &&
                        discoveredEndpoints.none { discovered -> discovered.info.id == endpoint.info.id }
                }
            retainedSelectedEndpoint?.let { endpoint -> endpointById[endpoint.info.id] = endpoint }
            val endpoints = if (retainedSelectedEndpoint == null) {
                discoveredEndpoints
            } else {
                discoveredEndpoints + retainedSelectedEndpoint
            }
            mutableState.value = mutableState.value.copy(
                readers = endpoints.map(ReaderEndpoint::info),
                initialized = true,
                failure = null,
            )

            if (autoConnect && endpoints.isNotEmpty()) {
                val available = endpoints.filter { endpoint -> endpoint.info.available }
                val current = previouslySelectedId
                    ?.let(endpointById::get)
                val candidates = readerReconnectCandidateIds(
                    selectedReaderId = previouslySelectedId,
                    preferredReaderId = settings.lastReaderId,
                    availableReaderIds = available.map { endpoint -> endpoint.info.id },
                ).mapNotNull(endpointById::get)
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
                    val failure = lastFailure?.toFailure(appContext)
                    mutableState.value = mutableState.value.copy(
                        selectedReaderId = null,
                        profiles = emptyList(),
                        notifications = emptyList(),
                        euiccInfo = null,
                        failure = failure,
                    )
                }
            } else if (
                !preserveSelectedSession &&
                (endpoints.isEmpty() || endpoints.none { endpoint -> endpoint.info.available })
            ) {
                closeSession()
                mutableState.value = mutableState.value.copy(
                    selectedReaderId = null,
                    profiles = emptyList(),
                    notifications = emptyList(),
                    euiccInfo = null,
                )
            }
    }

    suspend fun connect(readerId: String) = operationMutex.withLock {
        val endpoint = endpointById[readerId]
            ?: throw IllegalArgumentException(appContext.getString(R.string.failure_reader_unavailable))
        selectedReaderTargetId = readerId
        withOperation(LpaOperation.Connecting(endpoint.info.name)) {
            connectInternal(endpoint)
        }
    }

    suspend fun refresh() = operationMutex.withLock {
        withOperation(LpaOperation.Refreshing(appContext.getString(R.string.operation_reading_profiles))) {
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
            if (mutationOutcomeRequiresRefresh.get()) {
                throw OutcomeUnverifiedException(
                    titleRes = R.string.failure_lpa_title,
                    message = appContext.getString(R.string.failure_mutation_refresh_required),
                )
            }
            val switchAffinity = prepareProfileSwitchSession(iccid)
            var assistant = requireSession().assistant
            var modemRefreshRequested = requireSession().requiresProfileSwitchRefresh
            val desiredState = if (enabled) ProfileState.ENABLED else ProfileState.DISABLED
            var switchFailure: Throwable? = null
            var successful = try {
                assistant.switchProfile(iccid, enabled, refresh = modemRefreshRequested)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    mutationOutcomeRequiresRefresh.set(true)
                    throw error
                }
                switchFailure = error
                false
            }
            if (!successful) {
                modemRefreshRequested = false
                log(
                    LogLevel.WARNING,
                    "Profile",
                    "The profile switch response was inconclusive; reconnecting to verify card state",
                )
                val observedState = verifyProfileSwitchState(
                    iccid = iccid,
                    expectedAffinity = switchAffinity,
                    originalFailure = switchFailure,
                )
                successful = observedState == desiredState
                if (!successful) {
                    // The authoritative reconnect proved that the first command did not
                    // change the requested profile, so a single no-refresh retry is safe.
                    assistant = requireSession().assistant
                    switchFailure = null
                    successful = try {
                        assistant.switchProfile(iccid, enabled, refresh = false)
                    } catch (error: Throwable) {
                        if (error is CancellationException) {
                            mutationOutcomeRequiresRefresh.set(true)
                            throw error
                        }
                        switchFailure = error
                        false
                    }
                    if (!successful) {
                        // The retry itself may have applied the mutation before its response was
                        // lost. Reconcile again before reporting a retryable failure.
                        successful = verifyProfileSwitchState(
                            iccid = iccid,
                            expectedAffinity = switchAffinity,
                            originalFailure = switchFailure,
                        ) == desiredState
                    }
                }
            }
            if (!successful) {
                throw IllegalStateException(
                    appContext.getString(
                        if (enabled) R.string.failure_profile_enable else R.string.failure_profile_disable,
                    ),
                    switchFailure,
                )
            }
            updateProfileState(iccid, enabled)
            refreshAfterMutation("Profile switch", reconnectFirst = modemRefreshRequested)
            if (settings.notificationAfterSwitch) {
                processNotificationsSafely("profile switch")
                refreshNotificationsSafely("profile switch")
            }
        }
    }

    suspend fun deleteProfile(iccid: String) = operationMutex.withLock {
        withOperation(LpaOperation.Deleting(iccid)) {
            if (mutationOutcomeRequiresRefresh.get()) {
                throw OutcomeUnverifiedException(
                    titleRes = R.string.failure_lpa_title,
                    message = appContext.getString(R.string.failure_mutation_refresh_required),
                )
            }
            // Deletion is irreversible. Refresh first so the target and the card identity are
            // authoritative, rather than trusting a profile list left by an older session.
            prepareMutationSession()
            val deletionAffinity = currentReaderAffinity()
                ?: throw IllegalStateException(appContext.getString(R.string.failure_select_reader))
            val targetProfile = mutableState.value.profiles.singleOrNull { profile ->
                profile.iccid == iccid
            } ?: throw IllegalStateException(
                appContext.getString(R.string.failure_profile_not_found_on_selected_reader),
            )
            if (requiresDisableBeforeDeletion(targetProfile.state)) {
                disableProfileForDeletion(iccid, deletionAffinity)
            }
            var deletionFailure: Throwable? = null
            var deleted = try {
                requireSession().assistant.deleteProfile(iccid)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    mutationOutcomeRequiresRefresh.set(true)
                    throw error
                }
                deletionFailure = error
                false
            }
            if (!deleted) {
                val reconciliation = try {
                    reconnectSelectedWithRetry(attempts = 8)
                    classifyProfileDeletionReconciliation(
                        expectedAffinity = deletionAffinity,
                        observedAffinity = currentReaderAffinity(),
                        targetIccid = iccid,
                        profilesAfterReconnect = mutableState.value.profiles
                            .mapTo(hashSetOf(), ProfileInfo::iccid),
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException) {
                        mutationOutcomeRequiresRefresh.set(true)
                        throw error
                    }
                    mutationOutcomeRequiresRefresh.set(true)
                    val cause = deletionFailure?.let { original ->
                        IllegalStateException("The delete response and authoritative refresh both failed", original)
                            .apply { addSuppressed(error) }
                    } ?: error
                    throw OutcomeUnverifiedException(
                        titleRes = R.string.failure_lpa_title,
                        message = appContext.getString(R.string.failure_profile_delete_outcome_unverified),
                        cause = cause,
                    )
                }
                deleted = when (reconciliation) {
                    ProfileDeletionReconciliation.Deleted -> true
                    ProfileDeletionReconciliation.NotDeleted -> false
                    ProfileDeletionReconciliation.ReaderMismatch -> {
                        mutationOutcomeRequiresRefresh.set(true)
                        throw OutcomeUnverifiedException(
                            titleRes = R.string.failure_lpa_title,
                            message = appContext.getString(R.string.failure_profile_delete_outcome_unverified),
                            cause = deletionFailure,
                        )
                    }
                }
            }
            if (!deleted) {
                throw IllegalStateException(
                    appContext.getString(R.string.failure_profile_delete),
                    deletionFailure,
                )
            }
            mutableState.value = mutableState.value.copy(
                profiles = mutableState.value.profiles.filterNot { profile -> profile.iccid == iccid },
            )
            clearProfileMetadataAfterCardMutation(listOf(iccid), "profile deletion")
            if (settings.notificationAfterDelete) processNotificationsSafely("profile deletion")
            refreshAfterMutation("Profile deletion")
        }
    }

    /**
     * SGP.22 only permits deleting a disabled profile. Keep the profile-switch refresh flag off
     * so the same ISD-R session can issue the delete immediately after the disable command.
     */
    private suspend fun disableProfileForDeletion(
        iccid: String,
        deletionAffinity: ReaderAffinity,
    ) {
        var disableFailure: Throwable? = null
        var disabled = try {
            requireSession().assistant.switchProfile(iccid, enabled = false, refresh = false)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                mutationOutcomeRequiresRefresh.set(true)
                throw error
            }
            disableFailure = error
            false
        }
        if (!disabled) {
            log(
                LogLevel.WARNING,
                "Profile",
                "The pre-delete disable response was inconclusive; reconnecting to verify card state",
            )
            disabled = verifyProfileSwitchState(
                iccid = iccid,
                expectedAffinity = deletionAffinity,
                originalFailure = disableFailure,
            ) == ProfileState.DISABLED
        }
        if (!disabled) {
            throw IllegalStateException(
                appContext.getString(R.string.failure_profile_disable),
                disableFailure,
            )
        }
        updateProfileState(iccid, enabled = false)
    }

    suspend fun renameProfile(iccid: String, nickname: String) = operationMutex.withLock {
        withOperation(LpaOperation.Renaming(iccid)) {
            prepareMutationSession()
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

    suspend fun downloadProfile(
        request: DownloadRequest,
        confirmBeforeInstall: Boolean = true,
    ) = operationMutex.withLock {
        downloadProfileLocked(request, confirmBeforeInstall)
    }

    /**
     * Runs a download only while the same reader and physical eUICC that initiated it are still
     * selected. [onReady] executes under [operationMutex] after an authoritative refresh and
     * before any download-side effect, allowing a durable queue to mark the item in flight
     * without opening a reader-switch race or a process-death ambiguity window.
     */
    internal suspend fun downloadProfileBoundToReader(
        request: DownloadRequest,
        expectedAffinity: ReaderAffinity?,
        confirmBeforeInstall: Boolean,
        onReady: suspend () -> Unit = {},
    ): BoundProfileDownloadResult = operationMutex.withLock {
        val affinityMatches = expectedAffinity != null &&
            verifyReaderAffinityForProvisioning(expectedAffinity)
        if (!affinityMatches) {
            publishReaderAffinityFailure(expectedAffinity)
            return@withLock BoundProfileDownloadResult.ReaderMismatch
        }
        onReady()
        BoundProfileDownloadResult.Attempted(
            downloadProfileLocked(request, confirmBeforeInstall),
        )
    }

    internal fun selectedReaderAffinitySnapshot(): ReaderAffinity? = currentReaderAffinity()

    internal fun matchesSelectedReaderAffinity(expectedAffinity: ReaderAffinity): Boolean =
        currentReaderAffinity() == expectedAffinity

    private suspend fun downloadProfileLocked(
        request: DownloadRequest,
        confirmBeforeInstall: Boolean,
    ): OperationOutcome {
        downloadCancellationRequested.set(false)
        mutableState.value = mutableState.value.copy(completedProfileDownload = null)
        return try {
            withOperation(LpaOperation.Downloading(DownloadStage.PREPARING)) {
                if (downloadOutcomeRequiresRefresh.get()) {
                    throw OutcomeUnverifiedException(
                        titleRes = R.string.failure_download_title,
                        message = appContext.getString(R.string.failure_download_outcome_unverified),
                    )
                }
                require(request.smdpAddress.isNotBlank()) {
                    appContext.getString(R.string.failure_smdp_address_required)
                }
                require(request.hasRequiredConfirmationCode) {
                    appContext.getString(R.string.failure_confirmation_code_required)
                }
                val downloadJob = currentCoroutineContext()[Job]
                val smdpAddress = normalizeRspServerAddress(request.smdpAddress)
                prepareMutationSession()
                if (settings.notificationBeforeDownload) {
                    processNotificationsSafely("profile download preparation")
                }
                var assistant = requireSession().assistant
                val downloadAffinity = currentReaderAffinity()
                    ?: throw IllegalStateException(appContext.getString(R.string.failure_select_reader))
                val profilesBeforeDownload = mutableState.value.profiles.map(ProfileInfo::iccid).toSet()
                val initialFreeMemory = try {
                    assistant.euiccInfo2?.freeNvram
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    mutableState.value.euiccInfo?.freeNonVolatileMemory
                }
                var remoteProfile: RemoteProfileInfo? = null
                var latestStage = DownloadStage.PREPARING
                var reconciledInstalledIccid: String? = null
                try {
                    assistant.downloadProfile(
                        ProfileDownloadInput(
                            address = smdpAddress,
                            matchingId = request.matchingId,
                            smdpOid = request.smdpOid,
                            imei = request.imei,
                            confirmationCode = request.confirmationCode?.trim()?.takeIf(String::isNotEmpty),
                        ),
                    ) { stage ->
                    if (
                        downloadCancellationRequested.get() ||
                        downloadJob?.isActive == false ||
                        Thread.currentThread().isInterrupted
                    ) {
                        return@downloadProfile false
                    }
                    val mapped = when (stage) {
                        is ProfileDownloadState.Preparing -> DownloadStage.PREPARING
                        is ProfileDownloadState.Connecting -> DownloadStage.CONNECTING
                        is ProfileDownloadState.Authenticating -> DownloadStage.AUTHENTICATING
                        is ProfileDownloadState.ConfirmingDownload -> DownloadStage.CONFIRMING
                        is ProfileDownloadState.Downloading -> DownloadStage.DOWNLOADING
                        is ProfileDownloadState.Finalizing -> DownloadStage.FINALIZING
                        is ProfileDownloadState.Installing -> DownloadStage.INSTALLING
                    }
                    latestStage = mapped
                    val metadata = (stage as? ProfileDownloadState.ConfirmingDownload)?.metadata
                    val installProgress = stage as? ProfileDownloadState.Installing
                    if (metadata != null) remoteProfile = metadata
                    if (confirmBeforeInstall && stage is ProfileDownloadState.ConfirmingDownload) {
                        val decision = CompletableFuture<Boolean>()
                        synchronized(downloadDecisionLock) {
                            pendingDownloadDecision = decision
                        }
                        val cancellationHandle = downloadJob?.invokeOnCompletion {
                            decision.complete(false)
                        }
                        mutableState.value = mutableState.value.copy(
                            operation = LpaOperation.Downloading(mapped, metadata?.name),
                            pendingProfileDownload = metadata?.toPreview(
                                request,
                                initialFreeMemory,
                        ) ?: request.toPreview(
                            initialFreeMemory,
                            appContext.getString(R.string.profile_default_name),
                        ),
                        )
                        val confirmed = try {
                            runCatching(decision::get).getOrDefault(false)
                        } finally {
                            cancellationHandle?.dispose()
                            synchronized(downloadDecisionLock) {
                                if (pendingDownloadDecision === decision) pendingDownloadDecision = null
                            }
                        }
                        if (!confirmed) {
                            mutableState.value = mutableState.value.copy(pendingProfileDownload = null)
                        }
                        confirmed
                    } else {
                        mutableState.value = mutableState.value.copy(
                            operation = LpaOperation.Downloading(
                                stage = mapped,
                                profileName = metadata?.name ?: remoteProfile?.name,
                                sentBytes = installProgress?.sentBytes,
                                totalBytes = installProgress?.totalBytes,
                            ),
                        )
                        true
                    }
                    }
                } catch (error: Throwable) {
                    val installMayHaveCompleted = latestStage == DownloadStage.FINALIZING ||
                        latestStage == DownloadStage.INSTALLING
                    if (error is CancellationException) {
                        if (installMayHaveCompleted) downloadOutcomeRequiresRefresh.set(true)
                        throw error
                    }
                    if (!installMayHaveCompleted) throw error
                    when (
                        val reconciliation = reconcileDownloadedProfile(
                            expectedAffinity = downloadAffinity,
                            profilesBeforeDownload = profilesBeforeDownload,
                            expectedIccid = remoteProfile?.iccid,
                        )
                    ) {
                        is DownloadReconciliation.Installed -> {
                            reconciledInstalledIccid = reconciliation.iccid
                            assistant = requireSession().assistant
                            log(
                                LogLevel.WARNING,
                                "Profile download",
                                "The final response was lost, but an authoritative refresh confirmed installation",
                            )
                        }
                        DownloadReconciliation.NotInstalled -> throw error
                        DownloadReconciliation.Unverified -> {
                            downloadOutcomeRequiresRefresh.set(true)
                            throw OutcomeUnverifiedException(
                                titleRes = R.string.failure_download_title,
                                message = appContext.getString(R.string.failure_download_outcome_unverified),
                                cause = error,
                            )
                        }
                    }
                }
                val postInstallFreeMemory = try {
                    assistant.euiccInfo2?.freeNvram
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    null
                }
                if (settings.notificationAfterDownload) processNotificationsSafely("profile download")
                refreshAfterMutation("Profile download", guardFutureDownloads = true)
                val installedIccid = reconciledInstalledIccid ?: remoteProfile
                    ?.iccid
                    ?.takeIf(String::isNotBlank)
                    ?: mutableState.value.profiles
                        .firstOrNull { it.iccid !in profilesBeforeDownload }
                        ?.iccid
                val finalFreeMemory =
                    postInstallFreeMemory ?: mutableState.value.euiccInfo?.freeNonVolatileMemory
                val installedBytes = if (initialFreeMemory != null && finalFreeMemory != null) {
                    (initialFreeMemory - finalFreeMemory).toLong().takeIf { it > 0 }
                } else {
                    null
                }
                if (installedIccid != null) {
                    try {
                        metadataStore.setCloudData(
                            iccid = installedIccid,
                            smdpAddress = smdpAddress,
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
                if (confirmBeforeInstall) {
                    val previewProfile = mutableState.value.pendingProfileDownload?.profile
                        ?: remoteProfile?.toPreview(request, initialFreeMemory)?.profile
                        ?: request.toPreview(
                            initialFreeMemory,
                            appContext.getString(R.string.profile_default_name),
                        ).profile
                    val installedProfile = installedIccid
                        ?.let { iccid -> mutableState.value.profiles.firstOrNull { it.iccid == iccid } }
                        ?: previewProfile.copy(iccid = installedIccid ?: previewProfile.iccid)
                    mutableState.value = mutableState.value.copy(
                        pendingProfileDownload = null,
                        completedProfileDownload = ProfileDownloadResult(
                            profile = installedProfile,
                            installedBytes = installedBytes,
                            freeNonVolatileMemory = finalFreeMemory,
                        ),
                    )
                }
            }
        } finally {
            downloadCancellationRequested.set(false)
            synchronized(downloadDecisionLock) {
                pendingDownloadDecision = null
            }
            if (confirmBeforeInstall && mutableState.value.completedProfileDownload == null) {
                mutableState.value = mutableState.value.copy(pendingProfileDownload = null)
            }
        }
    }

    suspend fun processNotification(sequenceNumber: Long) = operationMutex.withLock {
        withOperation(LpaOperation.ProcessingNotification(sequenceNumber)) {
            prepareMutationSession()
            val assistant = requireSession().assistant
            val notification = mutableState.value.notifications
                .firstOrNull { it.sequenceNumber == sequenceNumber }
            val sent = try {
                assistant.handleNotification(sequenceNumber)
            } catch (error: Throwable) {
                recordNotificationOutcomeSafely(
                    action = NotificationHistoryAction.SEND,
                    status = NotificationHistoryStatus.FAILED,
                    trigger = NotificationHistoryTrigger.MANUAL,
                    operation = notification?.operation ?: NotificationOperation.UNKNOWN,
                    endpointAddress = notification?.address,
                    failureCode = "exception",
                )
                throw error
            }
            recordNotificationOutcomeSafely(
                action = NotificationHistoryAction.SEND,
                status = if (sent) NotificationHistoryStatus.SUCCEEDED else NotificationHistoryStatus.FAILED,
                trigger = NotificationHistoryTrigger.MANUAL,
                operation = notification?.operation ?: NotificationOperation.UNKNOWN,
                endpointAddress = notification?.address,
                failureCode = if (sent) null else "rejected",
            )
            check(sent) { appContext.getString(R.string.failure_notification_send) }
            if (settings.notificationAutoRemove) {
                val removed = try {
                    assistant.deleteNotification(sequenceNumber)
                } catch (error: Throwable) {
                    recordNotificationOutcomeSafely(
                        action = NotificationHistoryAction.DELETE,
                        status = NotificationHistoryStatus.FAILED,
                        trigger = NotificationHistoryTrigger.MANUAL,
                        operation = notification?.operation ?: NotificationOperation.UNKNOWN,
                        endpointAddress = notification?.address,
                        failureCode = "exception",
                    )
                    throw error
                }
                recordNotificationOutcomeSafely(
                    action = NotificationHistoryAction.DELETE,
                    status = if (removed) NotificationHistoryStatus.SUCCEEDED else NotificationHistoryStatus.FAILED,
                    trigger = NotificationHistoryTrigger.MANUAL,
                    operation = notification?.operation ?: NotificationOperation.UNKNOWN,
                    endpointAddress = notification?.address,
                    failureCode = if (removed) null else "rejected",
                )
                check(removed) { appContext.getString(R.string.failure_notification_sent_remove) }
                removeNotification(sequenceNumber)
            }
            refreshAfterMutation("Notification processing")
        }
    }

    suspend fun deleteNotification(sequenceNumber: Long) = operationMutex.withLock {
        withOperation(LpaOperation.ProcessingNotification(sequenceNumber)) {
            prepareMutationSession()
            val notification = mutableState.value.notifications
                .firstOrNull { it.sequenceNumber == sequenceNumber }
            val removed = try {
                requireSession().assistant.deleteNotification(sequenceNumber)
            } catch (error: Throwable) {
                recordNotificationOutcomeSafely(
                    action = NotificationHistoryAction.DELETE,
                    status = NotificationHistoryStatus.FAILED,
                    trigger = NotificationHistoryTrigger.MANUAL,
                    operation = notification?.operation ?: NotificationOperation.UNKNOWN,
                    endpointAddress = notification?.address,
                    failureCode = "exception",
                )
                throw error
            }
            recordNotificationOutcomeSafely(
                action = NotificationHistoryAction.DELETE,
                status = if (removed) NotificationHistoryStatus.SUCCEEDED else NotificationHistoryStatus.FAILED,
                trigger = NotificationHistoryTrigger.MANUAL,
                operation = notification?.operation ?: NotificationOperation.UNKNOWN,
                endpointAddress = notification?.address,
                failureCode = if (removed) null else "rejected",
            )
            check(removed) { appContext.getString(R.string.failure_notification_remove) }
            removeNotification(sequenceNumber)
            refreshAfterMutation("Notification removal")
        }
    }

    suspend fun resetEuiccMemory() = operationMutex.withLock {
        withOperation(LpaOperation.Resetting(appContext.getString(R.string.operation_resetting_memory))) {
            if (mutationOutcomeRequiresRefresh.get()) {
                throw OutcomeUnverifiedException(
                    titleRes = R.string.failure_lpa_title,
                    message = appContext.getString(R.string.failure_mutation_refresh_required),
                )
            }
            prepareMutationSession()
            val resetAffinity = currentReaderAffinity()
                ?: throw IllegalStateException(appContext.getString(R.string.failure_select_reader))
            val affectedIccids = mutableState.value.profiles.map(ProfileInfo::iccid)
            val affectedEid = resetAffinity.eid
            var resetFailure: Throwable? = null
            var reconciledReset = false
            val resetReportedSuccess = try {
                requireSession().assistant.euiccMemoryReset()
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    mutationOutcomeRequiresRefresh.set(true)
                    throw error
                }
                resetFailure = error
                false
            }
            if (!resetReportedSuccess) {
                val reconciliation = try {
                    reconnectSelectedWithRetry(attempts = 8)
                    classifyMemoryResetReconciliation(
                        expectedAffinity = resetAffinity,
                        observedAffinity = currentReaderAffinity(),
                        affectedIccids = affectedIccids.toSet(),
                        profilesAfterReconnect = mutableState.value.profiles
                            .mapTo(hashSetOf(), ProfileInfo::iccid),
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException) {
                        markReconciliationCancelled(ReconciliationOperation.MEMORY_RESET)
                        throw error
                    }
                    mutationOutcomeRequiresRefresh.set(true)
                    val cause = resetFailure?.let { original ->
                        IllegalStateException("The reset response and authoritative refresh both failed", original)
                            .apply { addSuppressed(error) }
                    } ?: error
                    throw OutcomeUnverifiedException(
                        titleRes = R.string.failure_lpa_title,
                        message = appContext.getString(R.string.failure_memory_reset_outcome_unverified),
                        cause = cause,
                    )
                }
                if (reconciliation != MemoryResetReconciliation.Reset) {
                    mutationOutcomeRequiresRefresh.set(true)
                    throw OutcomeUnverifiedException(
                        titleRes = R.string.failure_lpa_title,
                        message = appContext.getString(R.string.failure_memory_reset_outcome_unverified),
                        cause = resetFailure,
                    )
                }
                reconciledReset = true
            }
            mutableState.value = mutableState.value.copy(
                profiles = emptyList(),
                notifications = emptyList(),
            )
            clearEuiccMetadataAfterReset(affectedEid, affectedIccids)
            refreshAfterMutation("eUICC memory reset", reconnectFirst = !reconciledReset)
        }
    }

    suspend fun setDefaultSmdpAddress(address: String) = operationMutex.withLock {
        withOperation(
            LpaOperation.Refreshing(appContext.getString(R.string.operation_updating_default_smdp)),
        ) {
            prepareMutationSession()
            val normalized = normalizeRspServerAddress(address)
            check(requireSession().assistant.setDefaultSmdpAddress(normalized)) {
                appContext.getString(R.string.failure_default_smdp_rejected)
            }
            refreshAfterMutation("Default SM-DP+ address update")
        }
    }

    suspend fun discoverProfiles(smdsAddress: String? = null) = operationMutex.withLock {
        withOperation(
            LpaOperation.Refreshing(appContext.getString(R.string.operation_discovering_profiles)),
        ) {
            prepareOperationSession()
            val configuredAddress = smdsAddress
                ?.takeIf(String::isNotBlank)
                ?: mutableState.value.euiccInfo?.rootSmdsAddress
            val normalized = normalizeRspServerAddress(configuredAddress.orEmpty())
            val imei = settings.imei.trim().takeIf(String::isNotEmpty)
            val discovered = requireSession().assistant
                .discoverSmdpAddresses(normalized, imei)
                .map(::normalizeRspServerAddress)
                .distinct()
            mutableState.value = mutableState.value.copy(discoveredSmdpAddresses = discovered)
        }
    }

    suspend fun disconnectSession(): OperationOutcome {
        cancelProfileDownload()
        return operationMutex.withLock {
            withOperation(
                LpaOperation.Refreshing(appContext.getString(R.string.operation_disconnecting_reader)),
            ) {
                selectedReaderTargetId = null
                closeSession()
                mutableState.value = mutableState.value.copy(
                    selectedReaderId = null,
                    profiles = emptyList(),
                    notifications = emptyList(),
                    euiccInfo = null,
                    pendingProfileDownload = null,
                    completedProfileDownload = null,
                    discoveredSmdpAddresses = emptyList(),
                )
            }
        }
    }

    private fun disconnectReadersForStateReplacementLocked() {
        cancelProfileDownload()
        selectedReaderTargetId = null
        closeSession()
        endpointById.clear()
        mutableState.value = mutableState.value.copy(
            readers = emptyList(),
            selectedReaderId = null,
            profiles = emptyList(),
            notifications = emptyList(),
            euiccInfo = null,
            pendingProfileDownload = null,
            completedProfileDownload = null,
            discoveredSmdpAddresses = emptyList(),
            initialized = false,
        )
    }

    fun clearFailure() {
        mutableState.value = mutableState.value.copy(failure = null)
    }

    fun clearProfileDownloadResult() {
        mutableState.value = mutableState.value.copy(completedProfileDownload = null)
    }

    fun requiresAuthoritativeRefreshBeforeDownload(): Boolean =
        downloadOutcomeRequiresRefresh.get()

    fun confirmProfileDownload() = resolvePendingProfileDownload(true)

    fun cancelProfileDownload() {
        downloadCancellationRequested.set(true)
        resolvePendingProfileDownload(false)
    }

    private fun resolvePendingProfileDownload(confirmed: Boolean) {
        val decision = synchronized(downloadDecisionLock) { pendingDownloadDecision }
        decision?.complete(confirmed)
    }

    private suspend fun connectInternal(
        endpoint: ReaderEndpoint,
        refreshScope: SessionRefreshScope = SessionRefreshScope.FULL,
    ) = withContext(ioDispatcher) {
        val reconnectingSelectedReader = mutableState.value.selectedReaderId == endpoint.info.id
        closeSession()
        if (!reconnectingSelectedReader) {
            // Do not expose a hybrid affinity (the previous reader ID with the next card's EID)
            // while opening and refreshing a different reader. A reconnect to the currently
            // selected reader keeps its last valid snapshot until refreshInternal atomically
            // replaces it, avoiding transient empty states during profile switches.
            mutableState.value = mutableState.value.copy(
                selectedReaderId = null,
                profiles = emptyList(),
                notifications = emptyList(),
                euiccInfo = null,
            )
        }
        log(LogLevel.INFO, "Reader", "Connecting to ${endpoint.info.name}")
        val opened = LpaSessionFactory.open(endpoint, settings, verboseLoggingEnabled)
        try {
            session = opened
            when (refreshScope) {
                SessionRefreshScope.FULL -> refreshInternal()
                SessionRefreshScope.PROFILE_SWITCH_STATE -> refreshProfileSwitchStateInternal()
            }
            if (refreshScope == SessionRefreshScope.FULL && settings.notificationInitialLoad) {
                val notificationsChanged = processInitialNotificationsIfOnline()
                if (notificationsChanged) refreshInternal()
            }
            selectedReaderTargetId = endpoint.info.id
            mutableState.value = mutableState.value.copy(selectedReaderId = endpoint.info.id)
            log(LogLevel.INFO, "Reader", "Connected to ${endpoint.info.name}")
        } catch (error: Throwable) {
            if (session === opened) session = null
            opened.close()
            if (!reconnectingSelectedReader) {
                mutableState.value = mutableState.value.copy(
                    selectedReaderId = null,
                    profiles = emptyList(),
                    notifications = emptyList(),
                    euiccInfo = null,
                )
            }
            throw error
        }
    }

    private suspend fun reconnectSelected() {
        val selectedId = mutableState.value.selectedReaderId
            ?: selectedReaderTargetId
            ?: throw IllegalStateException(appContext.getString(R.string.failure_select_reader))
        val endpoint = endpointById[selectedId]
            ?: throw IllegalStateException(appContext.getString(R.string.failure_selected_reader_unavailable))
        connectInternal(endpoint)
    }

    private suspend fun reconnectSelectedWithRetry(
        attempts: Int = 5,
        refreshScope: SessionRefreshScope = SessionRefreshScope.FULL,
    ) {
        require(attempts > 0)
        val selectedId = mutableState.value.selectedReaderId
            ?: selectedReaderTargetId
            ?: throw IllegalStateException(appContext.getString(R.string.failure_select_reader))
        val endpoint = endpointById[selectedId]
            ?: throw IllegalStateException(appContext.getString(R.string.failure_selected_reader_unavailable))
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                connectInternal(endpoint, refreshScope)
                return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastFailure = error
                if (attempt < attempts - 1) delay(300L * (attempt + 1))
            }
        }
        throw IllegalStateException(appContext.getString(R.string.failure_reconnect), lastFailure)
    }

    private suspend fun reconcileDownloadedProfile(
        expectedAffinity: ReaderAffinity,
        profilesBeforeDownload: Set<String>,
        expectedIccid: String?,
    ): DownloadReconciliation {
        try {
            reconnectSelectedWithRetry(attempts = 8)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                markReconciliationCancelled(ReconciliationOperation.PROFILE_DOWNLOAD)
                throw error
            }
            return DownloadReconciliation.Unverified
        }
        return classifyDownloadReconciliation(
            expectedAffinity = expectedAffinity,
            observedAffinity = currentReaderAffinity(),
            profilesBeforeDownload = profilesBeforeDownload,
            profilesAfterReconnect = mutableState.value.profiles.mapTo(hashSetOf(), ProfileInfo::iccid),
            expectedIccid = expectedIccid,
        )
    }

    private suspend fun prepareOperationSession() {
        if (session == null) reconnectSelectedWithRetry(attempts = 3)
    }

    /**
     * Proves that a live session still points at the eUICC whose profile list is on screen without
     * paying for a complete card inventory before every switch. If the session has gone stale we
     * retain the full reconnect fallback; if the EID changed, refresh the UI but never send the
     * command to the replacement card.
     */
    private suspend fun prepareProfileSwitchSession(iccid: String): ReaderAffinity {
        val expectedAffinity = currentReaderAffinity()
        var lightweightIdentityRead = false
        val observedAffinity = when {
            session == null -> {
                reconnectSelectedWithRetry(attempts = 3)
                currentReaderAffinity()
            }
            expectedAffinity == null -> {
                refreshInternal()
                currentReaderAffinity()
            }
            else -> {
                try {
                    val readerId = mutableState.value.selectedReaderId
                        ?.takeIf(String::isNotBlank)
                        ?: throw IllegalStateException(appContext.getString(R.string.failure_select_reader))
                    val eid = requireSession().assistant.eID.takeIf(String::isNotBlank)
                        ?: throw IllegalStateException("The eUICC returned an empty EID")
                    lightweightIdentityRead = true
                    ReaderAffinity(readerId = readerId, eid = eid)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    reconnectSelectedWithRetry(attempts = 3)
                    currentReaderAffinity()
                }
            }
        }

        val affinity = when (
            val preflight = classifyProfileSwitchPreflight(expectedAffinity, observedAffinity)
        ) {
            is ProfileSwitchPreflight.Ready -> preflight.affinity
            ProfileSwitchPreflight.ReaderMismatch -> {
                var refreshFailure: Throwable? = null
                if (lightweightIdentityRead) {
                    try {
                        refreshInternal()
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        refreshFailure = error
                    }
                }
                log(
                    LogLevel.WARNING,
                    "Profile",
                    "The selected eUICC changed before the profile switch; the command was not sent",
                )
                throw IllegalStateException(
                    appContext.getString(R.string.failure_selected_euicc_changed),
                    refreshFailure,
                )
            }
            ProfileSwitchPreflight.Unverified -> throw IllegalStateException(
                appContext.getString(R.string.failure_selected_reader_unavailable),
            )
        }
        check(mutableState.value.profiles.any { profile -> profile.iccid == iccid }) {
            appContext.getString(R.string.failure_profile_not_found_on_selected_reader)
        }
        return affinity
    }

    /**
     * Establishes authoritative state immediately before a card-changing command. A live
     * transport is not sufficient: a removable card may have changed since the previous read.
     */
    private suspend fun prepareMutationSession() {
        if (session == null) {
            reconnectSelectedWithRetry(attempts = 3)
        } else {
            refreshInternal()
        }
    }

    private fun currentReaderAffinity(): ReaderAffinity? {
        val snapshot = mutableState.value
        val readerId = snapshot.selectedReaderId?.takeIf(String::isNotBlank) ?: return null
        val eid = snapshot.euiccInfo?.eid?.takeIf(String::isNotBlank) ?: return null
        return ReaderAffinity(readerId = readerId, eid = eid)
    }

    private fun markReconciliationCancelled(operation: ReconciliationOperation) {
        val requirements = reconciliationCancellationRequirements(operation)
        if (requirements.mutationRefreshRequired) mutationOutcomeRequiresRefresh.set(true)
        if (requirements.downloadRefreshRequired) downloadOutcomeRequiresRefresh.set(true)
    }

    private suspend fun verifyReaderAffinityForProvisioning(expected: ReaderAffinity): Boolean {
        // Never probe or reconnect a different selected reader merely to discover its EID.
        if (mutableState.value.selectedReaderId != expected.readerId) return false
        try {
            if (session == null) {
                reconnectSelectedWithRetry(attempts = 3)
            } else {
                try {
                    refreshInternal()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    reconnectSelectedWithRetry(attempts = 3)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log(
                LogLevel.WARNING,
                "Provisioning",
                "The batch-bound eUICC could not be refreshed before download",
            )
            return false
        }
        return currentReaderAffinity() == expected
    }

    private fun publishReaderAffinityFailure(expected: ReaderAffinity?) {
        val failure = OperationFailure(
            title = appContext.getString(R.string.failure_download_title),
            message = appContext.getString(
                if (expected == null) {
                    R.string.failure_select_reader
                } else {
                    R.string.failure_selected_reader_unavailable
                },
            ),
        )
        log(LogLevel.ERROR, failure.title, failure.message)
        mutableState.value = mutableState.value.copy(failure = failure)
    }

    private suspend fun verifyProfileSwitchState(
        iccid: String,
        expectedAffinity: ReaderAffinity,
        originalFailure: Throwable?,
    ): ProfileState {
        try {
            reconnectSelectedWithRetry(
                attempts = 8,
                refreshScope = SessionRefreshScope.PROFILE_SWITCH_STATE,
            )
            return when (
                val reconciliation = classifyProfileSwitchReconciliation(
                    expectedAffinity = expectedAffinity,
                    observedAffinity = currentReaderAffinity(),
                    targetIccid = iccid,
                    profileStatesAfterReconnect = mutableState.value.profiles
                        .associate { profile -> profile.iccid to profile.state },
                )
            ) {
                is ProfileSwitchReconciliation.Observed -> reconciliation.state
                ProfileSwitchReconciliation.Unverified -> {
                    throw IllegalStateException(
                        "The target profile state could not be verified on the original eUICC",
                    )
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                markReconciliationCancelled(ReconciliationOperation.PROFILE_SWITCH)
                throw error
            }
            mutationOutcomeRequiresRefresh.set(true)
            val cause = if (originalFailure == null) {
                error
            } else {
                IllegalStateException("The switch response and authoritative refresh both failed", originalFailure)
                    .apply { addSuppressed(error) }
            }
            throw OutcomeUnverifiedException(
                titleRes = R.string.failure_lpa_title,
                message = appContext.getString(R.string.failure_profile_switch_outcome_unverified),
                cause = cause,
            )
        }
    }

    private suspend fun refreshAfterMutation(
        operationName: String,
        reconnectFirst: Boolean = false,
        guardFutureDownloads: Boolean = false,
    ) {
        lateinit var refreshFailure: Throwable
        try {
            if (reconnectFirst) reconnectSelectedWithRetry(attempts = 8) else refreshInternal()
            return
        } catch (error: Throwable) {
            if (error is CancellationException) {
                mutationOutcomeRequiresRefresh.set(true)
                if (guardFutureDownloads) downloadOutcomeRequiresRefresh.set(true)
                throw error
            }
            refreshFailure = error
        }

        if (!reconnectFirst) {
            try {
                reconnectSelectedWithRetry(attempts = 3)
                return
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    mutationOutcomeRequiresRefresh.set(true)
                    if (guardFutureDownloads) downloadOutcomeRequiresRefresh.set(true)
                    throw error
                }
                refreshFailure = error
            }
        }

        // The command reported success, but the state used to decide subsequent destructive
        // operations is no longer authoritative. A later explicit refresh clears these guards.
        mutationOutcomeRequiresRefresh.set(true)
        if (guardFutureDownloads) downloadOutcomeRequiresRefresh.set(true)

        log(
            LogLevel.WARNING,
            "Reader",
            "$operationName succeeded, but the card state could not be refreshed: " +
                (refreshFailure.message ?: refreshFailure.javaClass.simpleName),
        )
    }

    /** Reads only the identity and profile state needed to reconcile an uncertain switch. */
    private suspend fun refreshProfileSwitchStateInternal() = withContext(ioDispatcher) {
        val assistant = requireSession().assistant
        val eid = assistant.eID
        val profiles = readValidatedProfiles(assistant)
        val previousInfo = mutableState.value.euiccInfo
        mutableState.value = mutableState.value.copy(
            profiles = profiles,
            euiccInfo = previousInfo
                ?.takeIf { info -> info.eid == eid }
                ?: EuiccInfo(eid = eid),
            failure = null,
        )
    }

    private suspend fun refreshInternal() = withContext(ioDispatcher) {
        val active = requireSession()
        val assistant = active.assistant
        val eid = assistant.eID
        val info = assistant.euiccInfo2
        val profiles = readValidatedProfiles(assistant)
        val localNotifications = assistant.notifications
        require(localNotifications.size <= MaxReaderNotifications) {
            "The eUICC returned too many notifications"
        }
        require(
            localNotifications.map(LocalProfileNotification::seqNumber).toSet().size ==
                localNotifications.size,
        ) { "The eUICC returned duplicate notification sequence numbers" }
        val addresses = assistant.euiccConfiguredAddresses
        mutableState.value = mutableState.value.copy(
            profiles = profiles,
            notifications = localNotifications.map(::mapNotification),
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
                installedApplicationCount = info?.installedApplicationCount,
                uiccCapabilities = info?.uiccCapabilities.orEmpty(),
                ts102241Version = info?.ts102241Version.orEmpty(),
                rspCapabilities = info?.rspCapabilities.orEmpty(),
                euiccCategory = info?.euiccCategory.orEmpty(),
                forbiddenProfilePolicyRules = info?.forbiddenProfilePolicyRules.orEmpty(),
                platformLabel = info?.platformLabel.orEmpty(),
                discoveryBaseUrl = info?.discoveryBaseUrl.orEmpty(),
                defaultSmdpAddress = addresses?.defaultDpAddress.orEmpty(),
                rootSmdsAddress = addresses?.rootDsAddress.orEmpty(),
                refreshedAt = Instant.now(),
            ),
            failure = null,
        )
        downloadOutcomeRequiresRefresh.set(false)
        mutationOutcomeRequiresRefresh.set(false)
    }

    private fun readValidatedProfiles(assistant: LocalProfileAssistant): List<ProfileInfo> {
        val localProfiles = assistant.profiles
        require(localProfiles.size <= MaxReaderProfiles) { "The eUICC returned too many profiles" }
        require(localProfiles.map(LocalProfileInfo::iccid).toSet().size == localProfiles.size) {
            "The eUICC returned duplicate profile ICCIDs"
        }
        return localProfiles.map(::mapProfile)
    }

    private suspend fun refreshNotificationsInternal() = withContext(ioDispatcher) {
        val localNotifications = requireSession().assistant.notifications
        require(localNotifications.size <= MaxReaderNotifications) {
            "The eUICC returned too many notifications"
        }
        require(
            localNotifications.map(LocalProfileNotification::seqNumber).toSet().size ==
                localNotifications.size,
        ) { "The eUICC returned duplicate notification sequence numbers" }
        mutableState.value = mutableState.value.copy(
            notifications = localNotifications.map(::mapNotification),
            failure = null,
        )
    }

    private suspend fun processNotificationsInternal(): Boolean {
        if (!settings.notificationAutoSend) return false
        val assistant = requireSession().assistant
        var changed = false
        val notifications = assistant.notifications
        require(notifications.size <= MaxReaderNotifications) {
            "The eUICC returned too many notifications"
        }
        require(notifications.map(LocalProfileNotification::seqNumber).toSet().size == notifications.size) {
            "The eUICC returned duplicate notification sequence numbers"
        }
        notifications.forEach { notification ->
            val mapped = mapNotification(notification)
            val sent = try {
                assistant.handleNotification(notification.seqNumber)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                recordNotificationOutcomeSafely(
                    action = NotificationHistoryAction.SEND,
                    status = NotificationHistoryStatus.FAILED,
                    trigger = NotificationHistoryTrigger.AUTOMATIC,
                    operation = mapped.operation,
                    endpointAddress = mapped.address,
                    failureCode = "exception",
                )
                log(
                    LogLevel.WARNING,
                    "Notifications",
                    "Notification ${notification.seqNumber} failed: " +
                        (error.message ?: error.javaClass.simpleName),
                )
                return@forEach
            }
            recordNotificationOutcomeSafely(
                action = NotificationHistoryAction.SEND,
                status = if (sent) NotificationHistoryStatus.SUCCEEDED else NotificationHistoryStatus.FAILED,
                trigger = NotificationHistoryTrigger.AUTOMATIC,
                operation = mapped.operation,
                endpointAddress = mapped.address,
                failureCode = if (sent) null else "rejected",
            )
            if (!sent) {
                log(
                    LogLevel.WARNING,
                    "Notifications",
                    "Notification ${notification.seqNumber} could not be sent",
                )
                return@forEach
            }
            if (settings.notificationAutoRemove) {
                val removed = try {
                    assistant.deleteNotification(notification.seqNumber)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    recordNotificationOutcomeSafely(
                        action = NotificationHistoryAction.DELETE,
                        status = NotificationHistoryStatus.FAILED,
                        trigger = NotificationHistoryTrigger.AUTOMATIC,
                        operation = mapped.operation,
                        endpointAddress = mapped.address,
                        failureCode = "exception",
                    )
                    log(
                        LogLevel.WARNING,
                        "Notifications",
                        "Notification ${notification.seqNumber} was sent but removal failed: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                    return@forEach
                }
                recordNotificationOutcomeSafely(
                    action = NotificationHistoryAction.DELETE,
                    status = if (removed) NotificationHistoryStatus.SUCCEEDED else NotificationHistoryStatus.FAILED,
                    trigger = NotificationHistoryTrigger.AUTOMATIC,
                    operation = mapped.operation,
                    endpointAddress = mapped.address,
                    failureCode = if (removed) null else "rejected",
                )
                if (removed) {
                    changed = true
                } else {
                    log(
                        LogLevel.WARNING,
                        "Notifications",
                        "Notification ${notification.seqNumber} was sent but could not be removed",
                    )
                }
            }
        }
        return changed
    }

    private suspend fun processNotificationsSafely(operationName: String): Boolean =
        try {
            processNotificationsInternal()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log(
                LogLevel.WARNING,
                "Notifications",
                "Could not process notifications after $operationName: ${error.message ?: error.javaClass.simpleName}",
            )
            false
        }

    /**
     * Initial profile loading must not wait for a mobile data route that is present but unusable.
     * Pending notifications remain on the eUICC and can be sent manually or on a later
     * connection with validated internet access.
     */
    private suspend fun processInitialNotificationsIfOnline(): Boolean {
        if (!shouldAttemptInitialNotificationDelivery(settings.notificationAutoSend, hasValidatedInternet())) {
            if (settings.notificationAutoSend && hasPendingNotifications()) {
                log(
                    LogLevel.INFO,
                    "Notifications",
                    "Skipped automatic delivery after reader connection because validated internet " +
                        "access is unavailable",
                )
            }
            return false
        }
        return processNotificationsSafely("reader connection")
    }

    private fun hasPendingNotifications(): Boolean = runCatching {
        requireSession().assistant.notifications.isNotEmpty()
    }.getOrDefault(false)

    private fun hasValidatedInternet(): Boolean {
        val manager = connectivityManager ?: return false
        val network = runCatching { manager.activeNetwork }.getOrNull() ?: return false
        val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun refreshNotificationsSafely(operationName: String) {
        try {
            refreshNotificationsInternal()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log(
                LogLevel.WARNING,
                "Notifications",
                "Could not refresh notifications after $operationName: " +
                    (error.message ?: error.javaClass.simpleName),
            )
        }
    }

    private suspend fun recordNotificationOutcomeSafely(
        action: NotificationHistoryAction,
        status: NotificationHistoryStatus,
        trigger: NotificationHistoryTrigger,
        operation: NotificationOperation,
        endpointAddress: String?,
        failureCode: String?,
    ) = withContext(NonCancellable) {
        try {
            notificationHistoryStore.record(
                action = action,
                status = status,
                trigger = trigger,
                notificationOperation = operation,
                endpointAddress = endpointAddress,
                failureCode = failureCode,
            )
        } catch (_: Throwable) {
            // History is diagnostic only. A storage problem must never change the
            // notification command's success/failure semantics on the eUICC.
            log(LogLevel.WARNING, "Notification history", "The notification outcome could not be saved")
        }
    }

    private suspend fun clearProfileMetadataAfterCardMutation(
        iccids: Collection<String>,
        operationName: String,
    ) = withContext(NonCancellable) {
        iccids.distinct().forEach { iccid ->
            try {
                metadataStore.clear(iccid)
            } catch (error: Throwable) {
                log(
                    LogLevel.WARNING,
                    "Profile metadata",
                    "$operationName succeeded, but associated local metadata could not be removed: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
        }
    }

    private suspend fun clearEuiccMetadataAfterReset(
        eid: String?,
        knownIccids: Collection<String>,
    ) = withContext(NonCancellable) {
        try {
            metadataStore.clearForEuicc(eid, knownIccids)
        } catch (error: Throwable) {
            log(
                LogLevel.WARNING,
                "Profile metadata",
                "eUICC memory reset succeeded, but its local metadata could not be removed: " +
                    (error.message ?: error.javaClass.simpleName),
            )
        }
    }

    private fun updateProfileState(iccid: String, enabled: Boolean) {
        // Do not assume single-enabled-profile behavior here. MEP-capable eUICCs may
        // keep another profile enabled; the authoritative refresh below supplies the
        // final state for every port/profile.
        mutableState.value = mutableState.value.copy(
            profiles = mutableState.value.profiles.map { profile ->
                if (profile.iccid == iccid) {
                    profile.copy(
                        state = if (enabled) ProfileState.ENABLED else ProfileState.DISABLED,
                    )
                } else profile
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

    private suspend fun withOperation(
        operation: LpaOperation,
        block: suspend () -> Unit,
    ): OperationOutcome {
        mutableState.value = mutableState.value.copy(operation = operation, failure = null)
        val operationName = operation.logName()
        log(LogLevel.INFO, "LPA", "$operationName started")
        try {
            withContext(ioDispatcher) { block() }
            log(LogLevel.INFO, "LPA", "$operationName completed")
            return OperationOutcome.Success
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val failure = error.toFailure(appContext)
            // Server-provided download errors have been observed to echo request
            // fields. Keep detailed diagnostics volatile in the operation UI, but
            // never copy them into the activity log/support-report pipeline.
            val logMessage = if (operation is LpaOperation.Downloading) {
                "The secure profile download session failed"
            } else {
                failure.message
            }
            log(LogLevel.ERROR, failure.title, logMessage)
            mutableState.value = mutableState.value.copy(failure = failure)
            return if (error is OutcomeUnverifiedException) {
                OperationOutcome.Unverified(failure)
            } else {
                OperationOutcome.Failed(failure)
            }
        } finally {
            mutableState.value = mutableState.value.copy(operation = LpaOperation.Idle)
        }
    }

    private fun requireSession(): LpaSession = session
        ?: throw IllegalStateException(appContext.getString(R.string.failure_select_reader))

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
        if (!closed.compareAndSet(false, true)) return
        cancelProfileDownload()
        closeSession()
        providers.forEach { (_, provider) -> provider.close() }
    }
}

private fun RemoteProfileInfo.toPreview(
    request: DownloadRequest,
    freeNonVolatileMemory: Int?,
): ProfileDownloadPreview {
    val network = decodeMccMnc(mccMnc)
    return ProfileDownloadPreview(
        profile = ProfileInfo(
            iccid = iccid,
            state = ProfileState.DISABLED,
            name = name,
            nickname = "",
            providerName = providerName,
            isdPAid = "",
            profileClass = when (profileClass) {
                net.typeblog.lpac_jni.ProfileClass.Operational -> ProfileClass.OPERATIONAL
                net.typeblog.lpac_jni.ProfileClass.Testing -> ProfileClass.TESTING
                net.typeblog.lpac_jni.ProfileClass.Provisioning -> ProfileClass.PROVISIONING
            },
            iconBase64 = iconBase64,
            mcc = network?.mcc,
            mnc = network?.mnc,
            gid1 = gid1,
            gid2 = gid2,
            smdpAddress = request.smdpAddress,
        ),
        request = request,
        freeNonVolatileMemory = freeNonVolatileMemory,
    )
}

private fun DownloadRequest.toPreview(
    freeNonVolatileMemory: Int?,
    fallbackProfileName: String,
) = ProfileDownloadPreview(
    profile = ProfileInfo(
        iccid = "",
        state = ProfileState.DISABLED,
        name = fallbackProfileName,
        nickname = "",
        providerName = "",
        isdPAid = "",
        profileClass = ProfileClass.UNKNOWN,
        smdpAddress = smdpAddress,
    ),
    request = this,
    freeNonVolatileMemory = freeNonVolatileMemory,
)

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
    ReaderKind.TELEPHONY -> BuildConfig.HAS_PRIVILEGED_TELEPHONY && settings.enableTelephony
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
        notificationOperations = profile.notificationOperations,
        dpOid = profile.dpOid,
        profilePolicyRules = profile.profilePolicyRules,
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

/**
 * Once a reader has been selected, discovery may only refresh or reconnect that exact reader.
 * Preferred-reader and general fallback ordering is used solely for initial selection.
 */
internal fun readerReconnectCandidateIds(
    selectedReaderId: String?,
    preferredReaderId: String?,
    availableReaderIds: List<String>,
): List<String> {
    if (selectedReaderId != null) {
        return availableReaderIds.filter { readerId -> readerId == selectedReaderId }.take(1)
    }
    return buildList {
        preferredReaderId
            ?.takeIf { preferred -> preferred in availableReaderIds }
            ?.let(::add)
        availableReaderIds.filterTo(this) { readerId -> readerId != preferredReaderId }
    }.distinct()
}

internal enum class ReconciliationOperation {
    PROFILE_SWITCH,
    MEMORY_RESET,
    PROFILE_DOWNLOAD,
}

internal data class ReconciliationRefreshRequirements(
    val mutationRefreshRequired: Boolean,
    val downloadRefreshRequired: Boolean,
)

internal fun reconciliationCancellationRequirements(
    operation: ReconciliationOperation,
): ReconciliationRefreshRequirements = when (operation) {
    ReconciliationOperation.PROFILE_SWITCH,
    ReconciliationOperation.MEMORY_RESET -> ReconciliationRefreshRequirements(
        mutationRefreshRequired = true,
        downloadRefreshRequired = false,
    )
    ReconciliationOperation.PROFILE_DOWNLOAD -> ReconciliationRefreshRequirements(
        // An unresolved install changes the card state and is also unsafe to retry.
        mutationRefreshRequired = true,
        downloadRefreshRequired = true,
    )
}

internal sealed interface ProfileSwitchReconciliation {
    data class Observed(val state: ProfileState) : ProfileSwitchReconciliation
    data object Unverified : ProfileSwitchReconciliation
}

internal sealed interface ProfileSwitchPreflight {
    data class Ready(val affinity: ReaderAffinity) : ProfileSwitchPreflight
    data object ReaderMismatch : ProfileSwitchPreflight
    data object Unverified : ProfileSwitchPreflight
}

internal fun classifyProfileSwitchPreflight(
    expectedAffinity: ReaderAffinity?,
    observedAffinity: ReaderAffinity?,
): ProfileSwitchPreflight = when {
    observedAffinity == null -> ProfileSwitchPreflight.Unverified
    expectedAffinity == null || observedAffinity == expectedAffinity ->
        ProfileSwitchPreflight.Ready(observedAffinity)
    else -> ProfileSwitchPreflight.ReaderMismatch
}

internal fun classifyProfileSwitchReconciliation(
    expectedAffinity: ReaderAffinity,
    observedAffinity: ReaderAffinity?,
    targetIccid: String,
    profileStatesAfterReconnect: Map<String, ProfileState>,
): ProfileSwitchReconciliation {
    if (observedAffinity != expectedAffinity) return ProfileSwitchReconciliation.Unverified
    val state = profileStatesAfterReconnect[targetIccid]
        ?: return ProfileSwitchReconciliation.Unverified
    return ProfileSwitchReconciliation.Observed(state)
}

internal sealed interface MemoryResetReconciliation {
    data object Reset : MemoryResetReconciliation
    data object Unverified : MemoryResetReconciliation
}

internal fun classifyMemoryResetReconciliation(
    expectedAffinity: ReaderAffinity,
    observedAffinity: ReaderAffinity?,
    affectedIccids: Set<String>,
    profilesAfterReconnect: Set<String>,
): MemoryResetReconciliation {
    if (observedAffinity != expectedAffinity || affectedIccids.isEmpty()) {
        return MemoryResetReconciliation.Unverified
    }
    return if (profilesAfterReconnect.none(affectedIccids::contains)) {
        MemoryResetReconciliation.Reset
    } else {
        MemoryResetReconciliation.Unverified
    }
}

internal sealed interface ProfileDeletionReconciliation {
    data object Deleted : ProfileDeletionReconciliation
    data object NotDeleted : ProfileDeletionReconciliation
    data object ReaderMismatch : ProfileDeletionReconciliation
}

internal fun requiresDisableBeforeDeletion(profileState: ProfileState): Boolean =
    profileState == ProfileState.ENABLED

internal fun classifyProfileDeletionReconciliation(
    expectedAffinity: ReaderAffinity,
    observedAffinity: ReaderAffinity?,
    targetIccid: String,
    profilesAfterReconnect: Set<String>,
): ProfileDeletionReconciliation {
    if (observedAffinity != expectedAffinity) return ProfileDeletionReconciliation.ReaderMismatch
    return if (targetIccid in profilesAfterReconnect) {
        ProfileDeletionReconciliation.NotDeleted
    } else {
        ProfileDeletionReconciliation.Deleted
    }
}

internal sealed interface DownloadReconciliation {
    data class Installed(val iccid: String) : DownloadReconciliation
    data object NotInstalled : DownloadReconciliation
    data object Unverified : DownloadReconciliation
}

internal fun classifyDownloadReconciliation(
    expectedAffinity: ReaderAffinity,
    observedAffinity: ReaderAffinity?,
    profilesBeforeDownload: Set<String>,
    profilesAfterReconnect: Set<String>,
    expectedIccid: String?,
): DownloadReconciliation {
    if (observedAffinity != expectedAffinity) return DownloadReconciliation.Unverified
    val expected = expectedIccid?.takeIf(String::isNotBlank)
    if (
        expected != null &&
        expected !in profilesBeforeDownload &&
        expected in profilesAfterReconnect
    ) {
        return DownloadReconciliation.Installed(expected)
    }
    val newlyInstalled = profilesAfterReconnect - profilesBeforeDownload
    // When authenticated SM-DP+ metadata names the expected ICCID, a different
    // concurrently-added profile is not evidence that this download succeeded.
    // The single-new-profile fallback is safe only when no expected ICCID exists.
    if (expected == null && newlyInstalled.size == 1) {
        return DownloadReconciliation.Installed(newlyInstalled.single())
    }
    if (newlyInstalled.isEmpty() && (expected == null || expected !in profilesBeforeDownload)) {
        return DownloadReconciliation.NotInstalled
    }
    return DownloadReconciliation.Unverified
}

private class OutcomeUnverifiedException(
    val titleRes: Int,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun Throwable.toFailure(context: Context): OperationFailure {
    val root = generateSequence(this) { it.cause }.last()
    return when (this) {
        is OutcomeUnverifiedException -> OperationFailure(
            title = context.getString(titleRes),
            message = message ?: context.getString(R.string.failure_download_outcome_unverified),
            recoverable = false,
        )
        is LocalProfileAssistant.ProfileDownloadException -> OperationFailure(
            title = context.getString(R.string.failure_download_title),
            message = lpaErrorReason.ifBlank {
                root.message ?: context.getString(R.string.failure_download_session)
            },
            diagnostic = buildString {
                lastHttpResponse?.let { append("HTTP ${it.rcode}\n") }
                lastHttpException?.message?.let { append("HTTP: $it\n") }
                lastApduResponse?.let { append("APDU: ${it.joinToString("") { byte -> "%02X".format(byte) }}\n") }
                lastApduException?.message?.let { append("Card: $it") }
            }.trim().ifEmpty { null },
        )
        else -> if (root is SecurityException) {
            OperationFailure(
                title = context.getString(R.string.failure_sim_access_title),
                message = context.getString(R.string.failure_sim_access_message),
                diagnostic = root.message,
            )
        } else {
            OperationFailure(
                title = context.getString(R.string.failure_lpa_title),
                message = root.message ?: root::class.java.simpleName,
                diagnostic = stackTraceToString(),
            )
        }
    }
}
