package app.hyperlpa.remote

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import app.hyperlpa.HyperLpaApplication
import app.hyperlpa.data.LpaRepository
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.history.NotificationHistoryStore
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.settings.AppSettingsStore
import app.hyperlpa.domain.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in live HTTPS/WebSocket test. Only the card engine is simulated; no real SIM is changed. */
@RunWith(AndroidJUnit4::class)
class RemoteDevicesIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun foregroundRelayConnectionSurvivesActivityStop() = runBlocking {
        val configFile = File(context.filesDir, "remote-test-config.json")
        assumeTrue("Supply the ignored live relay test configuration", configFile.exists())
        val config = DeviceJson.parseToJsonElement(configFile.readText()) as kotlinx.serialization.json.JsonObject
        val devices = (context as HyperLpaApplication).remoteDevices
        assumeTrue("Foreground test requires an unconfigured debug app", devices.ui.value.relay.isBlank())
        val activity = ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            await("foreground journal") { devices.ui.value.loaded }
            devices.configure(config.getValue("relay").jsonPrimitive.content, config.getValue("enrollment").jsonPrimitive.content, "Service test")
            await("foreground registration") { devices.ui.value.relay.isNotBlank() && !devices.ui.value.busy }
            devices.setEnabled(true)
            await("foreground connection") { devices.ui.value.connected }
            activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            delay(2_000)
            assertTrue(devices.ui.value.connected)
            devices.setEnabled(false)
            await("foreground stopped") { !devices.ui.value.connected && !devices.ui.value.busy }
        } finally {
            devices.unregister()
            await("foreground unregistered") { devices.ui.value.relay.isBlank() && !devices.ui.value.busy }
            activity.close()
        }
    }

    @Test
    fun encryptedJournalRejectsTamperingAndContainsNoPairingSecrets() {
        val isolated = isolatedContext()
        val store = DeviceStore(isolated)
        val secret = DeviceCrypto.secret()
        val stored = StoredDevices(relay = "https://relay.example", peers = listOf(
            PairedDevice(newDeviceId(), "Phone", newDeviceId(), secret, approved = true),
        ))
        try {
            store.write(stored)
            assertEquals(stored, store.read())
            val file = File(isolated.noBackupFilesDir, "remote-devices.v1")
            assertFalse(file.readText(Charsets.ISO_8859_1).contains(secret))
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertTrue(runCatching { store.read() }.isFailure)
        } finally { isolated.noBackupFilesDir.deleteRecursively() }
    }

    @Test
    fun livePairingReaderSelectionMutationsDownloadConfirmationReconnectAndRevocation() = runBlocking {
        val configFile = File(context.filesDir, "remote-test-config.json")
        assumeTrue("Supply the ignored live relay test configuration", configFile.exists())
        val config = DeviceJson.parseToJsonElement(configFile.readText()) as kotlinx.serialization.json.JsonObject
        val relay = config.getValue("relay").jsonPrimitive.content
        val enrollment = config.getValue("enrollment").jsonPrimitive.content
        val first = client("Controller")
        val second = client("Test phone")
        try {
            for (client in listOf(first, second)) {
                await("journal initialized") { client.devices.ui.value.loaded }
                client.devices.configure(relay, enrollment, client.name)
                await("registered") { client.devices.ui.value.relay == relay && !client.devices.ui.value.busy }
                client.devices.setEnabled(true)
                await("enabled") { client.devices.ui.value.enabled && !client.devices.ui.value.busy }
                client.devices.startRuntime()
                await("connected") { client.devices.ui.value.connected }
            }
            second.devices.createInvitation()
            await("invitation") { second.devices.ui.value.invitation != null }
            first.devices.pair(requireNotNull(second.devices.ui.value.invitation))
            await("approval requested") { second.devices.ui.value.peers.any { it.incoming && !it.approved } }
            assertTrue(first.devices.readers.value.isEmpty())
            assertTrue(second.host.calls.get() == 0)
            second.devices.approve(second.devices.ui.value.peers.single().id)
            await("paired and discovered") { first.devices.readers.value.size == 2 }
            assertEquals(listOf("SIM1 on Test phone", "SIM2 on Test phone"), first.devices.readers.value.map { it.name })
            val reader = first.devices.readers.value.first()
            assertTrue(first.devices.select(reader.id) is OperationOutcome.Success)
            await("shared reader dropdown") { first.repository.state.value.selectedReaderId == reader.id }
            assertEquals(first.devices.readers.value, first.repository.state.value.readers)
            assertEquals(Eid, first.devices.affinity()?.eid)

            assertTrue(first.devices.execute(DeviceCommand(DeviceAction.RENAME, iccid = Iccid, text = "Travel")) is OperationOutcome.Success)
            assertEquals("Travel", first.devices.view.value?.lpa?.profiles?.single()?.nickname)
            assertTrue(first.devices.execute(DeviceCommand(DeviceAction.SWITCH, iccid = Iccid, enabled = true)) is OperationOutcome.Success)
            assertEquals(ProfileState.ENABLED, first.devices.view.value?.lpa?.profiles?.single()?.state)

            val download = async { first.devices.execute(DeviceCommand(DeviceAction.DOWNLOAD,
                download = DownloadRequest("test.example", matchingId = "test-secret"))) }
            await("download preview") { first.devices.view.value?.lpa?.pendingProfileDownload != null }
            assertFalse(download.isCompleted)
            assertTrue(first.devices.select(first.devices.readers.value.last().id) is OperationOutcome.Failed)
            first.devices.decideDownload(true)
            assertTrue(download.await() is OperationOutcome.Success)
            assertNotNull(first.devices.view.value?.lpa?.completedProfileDownload)

            // A lost final reply is recovered using status, never by repeating the mutation.
            second.host.suppressNextResult = true
            val before = second.host.calls.get()
            val rename = async { first.devices.execute(DeviceCommand(DeviceAction.RENAME, iccid = Iccid, text = "Recovered")) }
            await("target committed") { second.host.calls.get() == before + 1 }
            first.devices.stopRuntime()
            first.devices.startRuntime()
            assertTrue(rename.await() is OperationOutcome.Success)
            assertEquals(before + 1, second.host.calls.get())
            // The recovered result carried no card state, so the card is read before the next change.
            assertTrue(first.devices.execute(DeviceCommand(DeviceAction.RENAME, iccid = Iccid, text = "Verified")) is OperationOutcome.Success)
            assertEquals(before + 2, second.host.calls.get())
            assertTrue(second.host.refreshes.get() > 0)
            assertEquals("Verified", first.devices.view.value?.lpa?.profiles?.single()?.nickname)

            first.devices.forget(first.devices.ui.value.peers.single().id)
            await("revoked on both devices") { first.devices.ui.value.peers.isEmpty() && second.devices.ui.value.peers.isEmpty() }
            assertTrue(first.devices.readers.value.isEmpty())
            assertNull(first.devices.view.value)
        } finally {
            for (client in listOf(first, second)) {
                if (client.devices.ui.value.relay.isNotBlank()) {
                    client.devices.unregister()
                    runCatching { await("unregistered") { client.devices.ui.value.relay.isBlank() } }
                }
                client.devices.close()
                client.repository.close()
                client.context.noBackupFilesDir.deleteRecursively()
            }
        }
    }

    private data class Client(val name: String, val context: Context, val devices: RemoteDevices, val repository: LpaRepository, val host: CardHost)
    private fun client(name: String): Client {
        val isolated = isolatedContext()
        val metadata = ProfileMetadataStore(context)
        val history = NotificationHistoryStore(isolated)
        val repository = LpaRepository(context, metadata, history)
        lateinit var host: CardHost
        val devices = RemoteDevices(isolated, repository, AppSettingsStore(context), metadata, history,
            hostFactory = { remote, scope -> CardHost(remote, scope).also { host = it } }, serviceControl = {})
        repository.attachDevices(devices)
        return Client(name, isolated, devices, repository, host)
    }

    private fun isolatedContext(): Context = object : ContextWrapper(context) {
        private val directory = File(context.noBackupFilesDir, "remote-test-${newDeviceId()}").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = directory
        override fun getPackageName(): String = context.packageName + "." + directory.name
    }

    private suspend fun await(label: String, condition: () -> Boolean) {
        android.util.Log.i("RemoteIntegration", "Waiting for $label")
        try { withTimeout(45_000) { while (!condition()) delay(50) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
        assertTrue(label, condition())
    }

    private class CardHost(val devices: RemoteDevices, val scope: CoroutineScope) : DeviceHost {
        val calls = AtomicInteger()
        val refreshes = AtomicInteger()
        @Volatile var suppressNextResult = false
        private var profile = ProfileInfo(Iccid, ProfileState.DISABLED, "Test", "Test", "Test carrier", "", ProfileClass.TESTING)
        private var decision: CompletableDeferred<Boolean>? = null
        override val busy get() = decision != null
        override fun isBusyWith(peer: String) = busy
        private fun snapshot(reader: String = "sim1") = DeviceSnapshot(lpa = LpaRepositoryState(
            readers = listOf(ReaderInfo("sim1", "SIM1", ReaderKind.OMAPI, eid = Eid), ReaderInfo("sim2", "SIM2", ReaderKind.OMAPI, eid = Eid)),
            selectedReaderId = reader, euiccInfo = EuiccInfo(Eid), profiles = listOf(profile), initialized = true,
        ))
        override fun accept(peer: PairedDevice, message: DeviceMessage, expires: Long) {
            scope.launch {
                val command = requireNotNull(message.command)
                if (command.action == DeviceAction.READERS) {
                    devices.send(peer, DeviceMessage("result", requestId = message.requestId, snapshot = snapshot(), done = true, outcome = OperationOutcome.Success))
                    return@launch
                }
                devices.record(peer.id, message.requestId)?.let { status(peer, message.requestId); return@launch }
                val record = DeviceOperationRecord(peer.id, message.requestId, DeviceCrypto.fingerprint(DeviceJson.encodeToString(DeviceCommand.serializer(), command)),
                    System.currentTimeMillis(), command.readerId, command.eid)
                devices.saveRecord(record)
                var downloadResult: ProfileDownloadResult? = null
                when (command.action) {
                    DeviceAction.RENAME -> profile = profile.copy(nickname = command.text.orEmpty())
                    DeviceAction.SWITCH -> profile = profile.copy(state = if (command.enabled) ProfileState.ENABLED else ProfileState.DISABLED)
                    DeviceAction.DOWNLOAD -> {
                        decision = CompletableDeferred()
                        devices.send(peer, DeviceMessage("result", requestId = message.requestId,
                            snapshot = snapshot(command.readerId).let { it.copy(lpa = it.lpa.copy(
                                operation = LpaOperation.Downloading(DownloadStage.CONFIRMING),
                                pendingProfileDownload = ProfileDownloadPreview(profile, requireNotNull(command.download)),
                            )) }, revision = 1))
                        check(withTimeout(30_000) { requireNotNull(decision).await() })
                        decision = null
                        downloadResult = ProfileDownloadResult(profile)
                    }
                    else -> Unit
                }
                val result = DeviceMessage("result", requestId = message.requestId, snapshot = snapshot(command.readerId),
                    outcome = OperationOutcome.Success, done = true, revision = 2, downloadResult = downloadResult)
                devices.saveRecord(record.copy(result = result.copy(snapshot = null)))
                // Only changes count: this phone may also re-read the card on its own.
                if (command.action == DeviceAction.REFRESH) refreshes.incrementAndGet() else calls.incrementAndGet()
                if (suppressNextResult) suppressNextResult = false else devices.send(peer, result)
            }
        }
        override fun status(peer: PairedDevice, id: String) { devices.record(peer.id, id)?.result?.let { devices.send(peer, it) } }
        override fun decision(peer: String, id: String, confirmed: Boolean) { decision?.complete(confirmed) }
        override fun cancelPendingConfirmation() { decision?.complete(false) }
    }

    companion object {
        private const val Eid = "89049032000000000000000000000001"
        private const val Iccid = "89010000000000000001"
    }
}
