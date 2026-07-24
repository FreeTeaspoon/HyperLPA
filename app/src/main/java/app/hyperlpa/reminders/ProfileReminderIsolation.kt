package app.hyperlpa.reminders

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide barrier for reminder delivery and durable reminder mutations.
 *
 * WorkManager creates workers independently from the UI and backup components. Without a shared
 * barrier a worker could validate a reminder, a restore could replace or roll back metadata, and
 * the worker could then post a notification from the superseded generation. The context marker
 * makes this barrier re-entrant so a restore can hold it while calling store helpers that also use
 * the barrier.
 */
internal suspend fun <T> withProfileReminderIsolation(block: suspend () -> T): T {
    if (currentCoroutineContext()[ReminderIsolationKey] != null) return block()
    currentCoroutineContext().ensureActive()
    return ReminderIsolationMutex.withLock {
        currentCoroutineContext().ensureActive()
        withContext(ReminderIsolationMarker) { block() }
    }
}

private val ReminderIsolationMutex = Mutex()

private object ReminderIsolationKey : CoroutineContext.Key<ReminderIsolationMarker>

private object ReminderIsolationMarker : AbstractCoroutineContextElement(ReminderIsolationKey)
