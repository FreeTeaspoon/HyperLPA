package app.hyperlpa.provisioning

import android.content.Context
import app.hyperlpa.R
import app.hyperlpa.data.BoundProfileDownloadResult
import app.hyperlpa.data.LpaRepository
import app.hyperlpa.data.ReaderAffinity
import app.hyperlpa.domain.model.DownloadRequest
import app.hyperlpa.domain.model.OperationOutcome
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Application-owned provisioning lifecycle. UI objects only submit decisions; they do not own
 * download jobs, so an Activity or ViewModel recreation cannot cancel an in-flight operation.
 */
class ProvisioningCoordinator(
    context: Context,
    private val repository: LpaRepository,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val store = EncryptedProvisioningQueueStore(applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val storeMutex = Mutex()
    private val mutableBatchState = MutableStateFlow(BatchDownloadUiState())
    private val mutableSingleDownloadActive = MutableStateFlow(false)
    private var storedQueue: StoredBatchQueue? = null
    private var singleDownloadJob: Job? = null
    private var batchDownloadJob: Job? = null
    private var queueUnreadable: Boolean = false
    private var queueClearInProgress: Boolean = false
    private var provisioningPauseCount: Int = 0

    val batchState: StateFlow<BatchDownloadUiState> = mutableBatchState.asStateFlow()
    val singleDownloadActive: StateFlow<Boolean> = mutableSingleDownloadActive.asStateFlow()

    init {
        scope.launch { loadPersistedQueue() }
    }

    fun startSingleDownload(request: DownloadRequest): Boolean {
        if (request.smdpAddress.isBlank() || !request.hasRequiredConfirmationCode) return false
        // Capture at submission time. A lazy job must not silently bind itself to a reader the
        // user selected after tapping Download.
        val affinity = repository.selectedReaderAffinitySnapshot()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                repository.downloadProfileBoundToReader(
                    request = request,
                    expectedAffinity = affinity,
                    confirmBeforeInstall = true,
                )
            } finally {
                synchronized(lifecycleLock) { singleDownloadJob = null }
                mutableSingleDownloadActive.value = false
                stopForegroundServiceIfIdle()
            }
        }
        val accepted = synchronized(lifecycleLock) {
            if (
                provisioningPauseCount > 0 ||
                singleDownloadJob != null ||
                batchDownloadJob != null
            ) {
                job.cancel()
                return false
            }
            singleDownloadJob = job
            try {
                ProvisioningForegroundService.startSingle(applicationContext)
                mutableSingleDownloadActive.value = true
                job.start().also { started ->
                    if (!started) {
                        singleDownloadJob = null
                        mutableSingleDownloadActive.value = false
                    }
                }
            } catch (_: Throwable) {
                if (singleDownloadJob === job) singleDownloadJob = null
                mutableSingleDownloadActive.value = false
                false
            }
        }
        if (!accepted) {
            job.cancel()
            stopForegroundServiceIfIdle()
        }
        return accepted
    }

    fun startBatchDownload(requests: List<DownloadRequest>): Boolean {
        if (mutableBatchState.value.loading) return false
        if (synchronized(lifecycleLock) { queueUnreadable }) {
            mutableBatchState.value = mutableBatchState.value.copy(
                notice = applicationContext.getString(R.string.provisioning_queue_clear_first_notice),
            )
            return false
        }
        if (requests.isEmpty() || requests.size > MaxProvisioningQueueItems) {
            mutableBatchState.value = mutableBatchState.value.copy(
                notice = applicationContext.getString(
                    R.string.provisioning_batch_limit_notice,
                    MaxProvisioningQueueItems,
                ),
            )
            return false
        }
        if (requests.any { it.smdpAddress.isBlank() || !it.hasRequiredConfirmationCode }) {
            mutableBatchState.value = mutableBatchState.value.copy(
                notice = applicationContext.getString(R.string.provisioning_batch_invalid_notice),
            )
            return false
        }
        val affinity = repository.selectedReaderAffinitySnapshot()
        if (affinity == null) {
            mutableBatchState.value = mutableBatchState.value.copy(
                notice = applicationContext.getString(R.string.failure_select_reader),
            )
            return false
        }
        val queue = StoredBatchQueue(
            readerId = affinity.readerId,
            eid = affinity.eid,
            entries = requests.mapIndexed { index, request -> request.toStoredEntry(index) },
            updatedAtEpochMillis = Instant.now().toEpochMilli(),
        )
        return beginBatch(queue, queue.entries.indices.toSet(), restored = false)
    }

    fun resumeInterruptedBatch(): Boolean {
        val queue = synchronized(lifecycleLock) {
            if (queueClearInProgress) return false
            storedQueue
        } ?: return false
        val eligible = queue.entries
            .filter { entry ->
                entry.status == BatchDownloadStatus.WAITING ||
                    entry.status == BatchDownloadStatus.CANCELLED
            }
            .map(StoredBatchEntry::index)
            .toSet()
        if (eligible.isEmpty()) return false
        val resumed = queue.copy(
            entries = queue.entries.map { entry ->
                if (entry.index in eligible) {
                    entry.copy(status = BatchDownloadStatus.WAITING, error = null)
                } else {
                    entry
                }
            },
            updatedAtEpochMillis = Instant.now().toEpochMilli(),
        )
        return beginBatch(resumed, eligible, restored = true)
    }

    fun retryFailedBatch(): Boolean {
        val queue = synchronized(lifecycleLock) {
            if (queueClearInProgress) return false
            storedQueue
        } ?: return false
        val eligible = queue.entries
            .filter { entry -> entry.status == BatchDownloadStatus.FAILED }
            .map(StoredBatchEntry::index)
            .toSet()
        if (eligible.isEmpty()) return false
        val retried = queue.copy(
            entries = queue.entries.map { entry ->
                if (entry.index in eligible) {
                    entry.copy(status = BatchDownloadStatus.WAITING, error = null)
                } else {
                    entry
                }
            },
            updatedAtEpochMillis = Instant.now().toEpochMilli(),
        )
        return beginBatch(retried, eligible, restored = true)
    }

    fun cancelSingleDownload() {
        repository.cancelProfileDownload()
        synchronized(lifecycleLock) { singleDownloadJob }?.cancel()
    }

    fun cancelBatchDownload() {
        repository.cancelProfileDownload()
        synchronized(lifecycleLock) { batchDownloadJob }?.cancel()
    }

    fun cancelActiveProvisioning(): Boolean {
        repository.cancelProfileDownload()
        val jobs = synchronized(lifecycleLock) { singleDownloadJob to batchDownloadJob }
        jobs.first?.cancel()
        jobs.second?.cancel()
        return jobs.first != null || jobs.second != null
    }

    /**
     * Prevents new provisioning work, cancels existing downloads, and waits for their durable
     * finalization before replacing application settings or reader sessions. The pause remains in
     * force for [block], so a foreground service or recreated UI cannot race a restore by starting
     * another download after cancellation but before the reader state has been rebuilt.
     */
    internal suspend fun <T> withProvisioningQuiesced(block: suspend () -> T): T {
        val jobs = synchronized(lifecycleLock) {
            provisioningPauseCount += 1
            singleDownloadJob to batchDownloadJob
        }
        repository.cancelProfileDownload()
        jobs.first?.cancel()
        jobs.second?.cancel()
        return try {
            jobs.first?.join()
            jobs.second?.join()
            synchronized(lifecycleLock) {
                // A lazy coroutine cancelled before start does not execute its finally block.
                if (singleDownloadJob === jobs.first) {
                    singleDownloadJob = null
                    mutableSingleDownloadActive.value = false
                }
                if (batchDownloadJob === jobs.second) batchDownloadJob = null
            }
            // Also wait for queue loading or an already accepted clear to leave encrypted storage
            // idle. New queue mutations are rejected while provisioningPauseCount is non-zero.
            storeMutex.withLock { Unit }
            block()
        } finally {
            synchronized(lifecycleLock) {
                provisioningPauseCount = (provisioningPauseCount - 1).coerceAtLeast(0)
            }
            stopForegroundServiceIfIdle()
        }
    }

    fun confirmSingleDownload() {
        repository.confirmProfileDownload()
    }

    fun clearBatchDownload() {
        synchronized(lifecycleLock) {
            // batchDownloadJob is cleared only after its NonCancellable final save completes, so
            // accepting clear here also guarantees there is no active writer left to join.
            if (
                provisioningPauseCount > 0 ||
                batchDownloadJob != null ||
                queueClearInProgress
            ) return
            queueClearInProgress = true
        }
        val previousState = mutableBatchState.value
        mutableBatchState.value = previousState.copy(loading = true)
        scope.launch {
            val cleared = try {
                storeMutex.withLock { store.clear() }
                true
            } catch (_: Throwable) {
                false
            }
            synchronized(lifecycleLock) {
                if (cleared) {
                    storedQueue = null
                    queueUnreadable = false
                }
                queueClearInProgress = false
            }
            mutableBatchState.value = if (cleared) {
                BatchDownloadUiState(loading = false)
            } else {
                previousState.copy(
                    loading = false,
                    notice = applicationContext.getString(R.string.provisioning_queue_clear_failed),
                )
            }
        }
    }

    internal fun onForegroundServiceDestroyed() {
        val active = synchronized(lifecycleLock) {
            singleDownloadJob != null || batchDownloadJob != null
        }
        if (active) cancelActiveProvisioning()
    }

    private fun beginBatch(
        queue: StoredBatchQueue,
        eligibleIndexes: Set<Int>,
        restored: Boolean,
    ): Boolean {
        if (repository.requiresAuthoritativeRefreshBeforeDownload()) {
            mutableBatchState.value = mutableBatchState.value.copy(
                running = false,
                notice = applicationContext.getString(R.string.failure_download_outcome_unverified),
            )
            return false
        }
        if (!repository.matchesSelectedReaderAffinity(queue.readerAffinity)) {
            mutableBatchState.value = mutableBatchState.value.copy(
                running = false,
                notice = applicationContext.getString(R.string.provisioning_reader_mismatch_notice),
            )
            return false
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runBatch(queue, eligibleIndexes, restored)
        }
        val accepted = synchronized(lifecycleLock) {
            if (
                provisioningPauseCount > 0 ||
                singleDownloadJob != null ||
                batchDownloadJob != null ||
                queueClearInProgress
            ) {
                job.cancel()
                return false
            }
            batchDownloadJob = job
            storedQueue = queue
            publish(queue, running = true, restored = restored, notice = null)
            try {
                ProvisioningForegroundService.startBatch(
                    context = applicationContext,
                    completed = queue.entries.count { it.status == BatchDownloadStatus.SUCCEEDED },
                    total = queue.entries.size,
                )
                job.start()
            } catch (_: Throwable) {
                if (batchDownloadJob === job) batchDownloadJob = null
                false
            }
        }
        if (!accepted) {
            job.cancel()
            publish(
                queue,
                running = false,
                restored = restored,
                notice = applicationContext.getString(R.string.provisioning_service_start_failed),
            )
            stopForegroundServiceIfIdle()
        }
        return accepted
    }

    private suspend fun runBatch(
        startingQueue: StoredBatchQueue,
        eligibleIndexes: Set<Int>,
        restored: Boolean,
    ) {
        var queue = startingQueue
        var notice: String? = null
        var cancelled = false
        try {
            storeMutex.withLock { store.save(queue) }
            synchronized(lifecycleLock) { storedQueue = queue }
            for (index in eligibleIndexes.sorted()) {
                currentCoroutineContext().ensureActive()
                val entry = queue.entries[index]
                val boundResult = repository.downloadProfileBoundToReader(
                    request = entry.toRequest(),
                    expectedAffinity = queue.readerAffinity,
                    confirmBeforeInstall = false,
                    onReady = {
                        val starting = queue.updateEntry(index) { current ->
                            current.copy(status = BatchDownloadStatus.DOWNLOADING, error = null)
                        }
                        queue = persistAndPublish(starting, running = true, restored = restored)
                        ProvisioningForegroundService.updateBatch(
                            context = applicationContext,
                            completed = queue.finishedCount,
                            total = queue.entries.size,
                        )
                    },
                )
                if (boundResult == BoundProfileDownloadResult.ReaderMismatch) {
                    notice = applicationContext.getString(R.string.provisioning_reader_mismatch_notice)
                    break
                }
                val outcome = (boundResult as BoundProfileDownloadResult.Attempted).outcome
                var outcomeUnverified = false
                queue = when (outcome) {
                    OperationOutcome.Success -> queue.updateEntry(index) { current ->
                        current.copy(status = BatchDownloadStatus.SUCCEEDED, error = null)
                    }
                    is OperationOutcome.Failed -> queue.updateEntry(index) { current ->
                        current.copy(
                            status = BatchDownloadStatus.FAILED,
                            // A remote error may echo activation data. Persist only a stable,
                            // non-sensitive category; the repository failure remains volatile.
                            error = BatchDownloadError.DOWNLOAD_FAILED,
                        )
                    }
                    is OperationOutcome.Unverified -> {
                        outcomeUnverified = true
                        queue.updateEntry(index) { current ->
                            current.copy(
                                status = BatchDownloadStatus.INTERRUPTED,
                                error = BatchDownloadError.OUTCOME_UNVERIFIED,
                            )
                        }
                    }
                }
                repository.clearFailure()
                queue = persistAndPublish(queue, running = true, restored = restored)
                if (outcomeUnverified) {
                    notice = applicationContext.getString(R.string.failure_download_outcome_unverified)
                    break
                }
            }
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        } catch (_: Throwable) {
            queue = queue.copy(
                entries = queue.entries.map { entry ->
                    if (entry.status == BatchDownloadStatus.DOWNLOADING) {
                        entry.copy(
                            status = BatchDownloadStatus.INTERRUPTED,
                            error = BatchDownloadError.INTERRUPTED_UNVERIFIED,
                        )
                    } else {
                        entry
                    }
                },
                updatedAtEpochMillis = Instant.now().toEpochMilli(),
            )
            notice = applicationContext.getString(R.string.provisioning_queue_update_failed)
        } finally {
            if (cancelled) {
                queue = queue.copy(
                    entries = queue.entries.map { entry ->
                        if (entry.index !in eligibleIndexes) return@map entry
                        when (entry.status) {
                            BatchDownloadStatus.DOWNLOADING -> entry.copy(
                                status = BatchDownloadStatus.INTERRUPTED,
                                error = BatchDownloadError.CANCELLED_UNVERIFIED,
                            )
                            BatchDownloadStatus.WAITING -> entry.copy(
                                status = BatchDownloadStatus.CANCELLED,
                                error = null,
                            )
                            else -> entry
                        }
                    },
                    updatedAtEpochMillis = Instant.now().toEpochMilli(),
                )
            }
            withContext(NonCancellable) {
                try {
                    storeMutex.withLock { store.save(queue) }
                    synchronized(lifecycleLock) { storedQueue = queue }
                } catch (_: Throwable) {
                    notice = notice ?: applicationContext.getString(
                        R.string.provisioning_queue_final_status_failed,
                    )
                }
                synchronized(lifecycleLock) { batchDownloadJob = null }
                publish(queue, running = false, restored = restored, notice = notice)
                stopForegroundServiceIfIdle()
            }
        }
    }

    private suspend fun persistAndPublish(
        queue: StoredBatchQueue,
        running: Boolean,
        restored: Boolean,
    ): StoredBatchQueue {
        val updated = queue.copy(updatedAtEpochMillis = Instant.now().toEpochMilli())
        storeMutex.withLock { store.save(updated) }
        synchronized(lifecycleLock) { storedQueue = updated }
        publish(updated, running = running, restored = restored, notice = null)
        return updated
    }

    private fun publish(
        queue: StoredBatchQueue,
        running: Boolean,
        restored: Boolean,
        notice: String?,
    ) {
        mutableBatchState.value = BatchDownloadUiState(
            items = queue.entries.map { entry ->
                BatchDownloadItem(
                    index = entry.index,
                    address = entry.smdpAddress,
                    status = entry.status,
                    error = entry.error,
                )
            },
            running = running,
            loading = false,
            restored = restored,
            hasSavedQueue = true,
            notice = notice,
        )
    }

    private suspend fun loadPersistedQueue() {
        val loadResult = storeMutex.withLock { store.load() }
        when (val result = loadResult) {
            StoredQueueLoadResult.Empty -> {
                synchronized(lifecycleLock) { queueUnreadable = false }
                mutableBatchState.value = BatchDownloadUiState(loading = false)
            }
            StoredQueueLoadResult.Unreadable -> {
                synchronized(lifecycleLock) { queueUnreadable = true }
                mutableBatchState.value = BatchDownloadUiState(
                    loading = false,
                    restored = true,
                    hasSavedQueue = true,
                    requiresClearBeforeNewBatch = true,
                    notice = applicationContext.getString(R.string.provisioning_queue_unreadable),
                )
            }
            is StoredQueueLoadResult.Loaded -> {
                synchronized(lifecycleLock) { queueUnreadable = false }
                val interruptedItems = interruptInFlightItems(
                    result.queue.entries.map { entry ->
                        BatchDownloadItem(
                            index = entry.index,
                            address = entry.smdpAddress,
                            status = entry.status,
                            error = entry.error,
                        )
                    },
                )
                val wasInterrupted = result.queue.entries.any { entry ->
                    entry.status == BatchDownloadStatus.DOWNLOADING
                }
                val recovered = result.queue.copy(
                    entries = result.queue.entries.mapIndexed { index, entry ->
                        val item = interruptedItems[index]
                        entry.copy(status = item.status, error = item.error)
                    },
                    updatedAtEpochMillis = if (wasInterrupted) {
                        Instant.now().toEpochMilli()
                    } else {
                        result.queue.updatedAtEpochMillis
                    },
                )
                val notice = if (wasInterrupted) {
                    try {
                        storeMutex.withLock { store.save(recovered) }
                        applicationContext.getString(R.string.provisioning_queue_interrupted)
                    } catch (_: Throwable) {
                        applicationContext.getString(R.string.provisioning_queue_recovery_unsaved)
                    }
                } else {
                    null
                }
                synchronized(lifecycleLock) { storedQueue = recovered }
                publish(recovered, running = false, restored = true, notice = notice)
            }
        }
    }

    private fun stopForegroundServiceIfIdle() {
        val idle = synchronized(lifecycleLock) {
            singleDownloadJob == null && batchDownloadJob == null
        }
        if (idle) ProvisioningForegroundService.stop(applicationContext)
    }

    override fun close() {
        cancelActiveProvisioning()
        scope.cancel()
    }
}

private fun StoredBatchQueue.updateEntry(
    index: Int,
    transform: (StoredBatchEntry) -> StoredBatchEntry,
): StoredBatchQueue = copy(
    entries = entries.map { entry -> if (entry.index == index) transform(entry) else entry },
    updatedAtEpochMillis = Instant.now().toEpochMilli(),
)

private val StoredBatchQueue.finishedCount: Int
    get() = entries.count { entry ->
        entry.status == BatchDownloadStatus.SUCCEEDED || entry.status == BatchDownloadStatus.FAILED
    }

private val StoredBatchQueue.readerAffinity: ReaderAffinity
    get() = ReaderAffinity(readerId = readerId, eid = eid)
