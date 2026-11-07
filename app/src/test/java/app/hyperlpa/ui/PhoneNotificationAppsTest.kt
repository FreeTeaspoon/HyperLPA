package app.hyperlpa.ui

import app.hyperlpa.remote.RemoteDevicesUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PhoneNotificationAppsTest {
    @Test fun delayedLabelsPublishOnlyFinalOrderAndReopeningKeepsIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val states = MutableStateFlow(RemoteDevicesUiState())
            val started = CompletableDeferred<Unit>()
            val labelsReady = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val labels = mapOf("z" to "Zulu", "a" to "Alpha", "sms" to "Messages")
            val apps = phoneNotificationApps(states, scope, { "sms" }) { pkg ->
                started.complete(Unit)
                labelsReady.await()
                loads.incrementAndGet()
                labels.getValue(pkg)
            }
            assertNull(apps.value)
            states.value = RemoteDevicesUiState(loaded = true, notificationApps = linkedSetOf("z", "a", "sms"))
            withTimeout(5_000) { started.await() }
            assertNull("Do not display stored order while labels are loading", apps.value)
            labelsReady.complete(Unit)
            val resolved = withTimeout(5_000) { apps.first { it != null } }
            assertEquals(listOf("sms", "a", "z"), resolved!!.map { it.first })
            // A new page subscriber receives the same prepared list immediately.
            assertSame(resolved, apps.first())
            assertEquals(3, loads.get())
        } finally { scope.cancel() }
    }

    @Test fun newSourcesReplaceTheListOnlyAfterTheirLabelsAreReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val states = MutableStateFlow(RemoteDevicesUiState(loaded = true, notificationApps = setOf("z")))
            val newLabelStarted = CompletableDeferred<Unit>()
            val newLabelReady = CompletableDeferred<Unit>()
            val apps = phoneNotificationApps(states, scope, { null }) { pkg ->
                if (pkg == "a") {
                    newLabelStarted.complete(Unit)
                    newLabelReady.await()
                }
                if (pkg == "a") "Alpha" else "Zulu"
            }
            val initial = withTimeout(5_000) { apps.first { it != null } }
            states.value = states.value.copy(notificationApps = linkedSetOf("z", "a"))
            withTimeout(5_000) { newLabelStarted.await() }
            assertSame("Keep the finished list while resolving new sources", initial, apps.value)
            newLabelReady.complete(Unit)
            val updated = withTimeout(5_000) { apps.first { it?.size == 2 } }
            assertEquals(listOf("a", "z"), updated!!.map { it.first })
        } finally { scope.cancel() }
    }
}
