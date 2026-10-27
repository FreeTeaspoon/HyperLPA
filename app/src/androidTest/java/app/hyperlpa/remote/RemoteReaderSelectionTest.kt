package app.hyperlpa.remote

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hyperlpa.data.LpaRepository
import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.history.NotificationHistoryStore
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.settings.AppSettingsStore
import app.hyperlpa.domain.model.EuiccInfo
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.OperationOutcome
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises selection while the network request lock is held, without a relay or real SIM. */
@RunWith(AndroidJUnit4::class)
class RemoteReaderSelectionTest {
    @Test
    fun localReaderSelectionShowsItsOwnCacheWhileCardWorkIsStillLocked() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.ready()
            fixture.repository.attachDevices(fixture.devices)
            val local = ReaderInfo("usb:reader", "USB reader", ReaderKind.USB_CCID)
            val state = fixture.repositoryField<MutableStateFlow<LpaRepositoryState>>("mutableState")
            state.value = fixture.snapshot(fixture.first).lpa.copy(
                readers = listOf(local), selectedReaderId = null, euiccInfo = null, profiles = emptyList(),
            )
            val expected = fixture.snapshot(fixture.second).lpa
            fixture.repositoryField<MutableMap<String, app.hyperlpa.data.ReaderSnapshot>>("readerSnapshots")[local.id] =
                app.hyperlpa.data.ReaderSnapshot(expected.profiles, emptyList(), requireNotNull(expected.euiccInfo), emptyList())
            val cardLock = fixture.repositoryField<Mutex>("operationMutex")
            cardLock.lock()
            val selection = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.connect(local.id) }
            try {
                withTimeout(5_000) {
                    while (fixture.repository.state.value.selectedReaderId != local.id) delay(10)
                }
                assertFalse(selection.isCompleted)
                assertEquals(expected.euiccInfo?.eid, fixture.repository.state.value.euiccInfo?.eid)
                assertEquals(expected.profiles, fixture.repository.state.value.profiles)
                assertTrue(fixture.repository.state.value.operation is LpaOperation.Connecting)
            } finally {
                selection.cancel()
                selection.join()
                cardLock.unlock()
            }
        } finally { fixture.close() }
    }

    @Test
    fun cachedPhoneReaderAppearsBeforeThePreviousConnectionFinishes() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.ready()
            fixture.view.value = fixture.snapshot(fixture.first).let {
                it.copy(lpa = it.lpa.copy(operation = LpaOperation.Connecting(fixture.first.name)))
            }
            fixture.lock.lock()
            val selection = async(start = CoroutineStart.UNDISPATCHED) { fixture.devices.select(fixture.second.id) }
            assertFalse(selection.isCompleted)
            assertEquals(fixture.second.id, fixture.view.value?.lpa?.selectedReaderId)
            assertEquals("second-profile", fixture.view.value?.lpa?.profiles?.single()?.iccid)
            assertTrue(fixture.view.value?.lpa?.readerSnapshotPendingRefresh == true)
            assertTrue(fixture.view.value?.lpa?.operation is LpaOperation.Connecting)
            fixture.lock.unlock()
            // Offline reconnects preserve the cached card, with actions awaiting a fresh read.
            assertTrue(selection.await() is OperationOutcome.Failed)
            assertEquals("second-profile", fixture.view.value?.lpa?.profiles?.single()?.iccid)
            assertTrue(fixture.view.value?.lpa?.readerSnapshotPendingRefresh == true)
        } finally { fixture.close() }
    }

    @Test
    fun latestPhoneSelectionWinsAndAnOlderReplyCannotReplaceIt() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.ready()
            fixture.view.value = fixture.snapshot(fixture.first).let {
                it.copy(lpa = it.lpa.copy(operation = LpaOperation.Connecting(fixture.first.name)))
            }
            fixture.lock.lock()
            val old = async(start = CoroutineStart.UNDISPATCHED) { fixture.devices.select(fixture.second.id) }
            val latest = async(start = CoroutineStart.UNDISPATCHED) { fixture.devices.select(fixture.first.id) }
            assertEquals(fixture.first.id, fixture.view.value?.lpa?.selectedReaderId)

            // The old reply is for the same reader as the newest choice, but an earlier selection.
            val request = newDeviceId()
            fixture.field<MutableMap<String, String>>("requestPeers")[request] = fixture.peer.id
            fixture.field<MutableMap<String, CompletableDeferred<DeviceMessage>>>("requestResults")[request] = CompletableDeferred()
            fixture.field<MutableMap<String, Long>>("requestSelections")[request] = 0L
            val stale = fixture.snapshot(fixture.first).let { snapshot -> snapshot.copy(lpa = snapshot.lpa.copy(
                selectedReaderId = fixture.first.sourceReaderId,
                profiles = snapshot.lpa.profiles.map { it.copy(nickname = "Stale reply") },
            )) }
            RemoteDevices::class.java.getDeclaredMethod("receiveResult", PairedDevice::class.java, DeviceMessage::class.java)
                .apply { isAccessible = true }
                .invoke(fixture.devices, fixture.peer, DeviceMessage("result", requestId = request, snapshot = stale, revision = 1))
            assertEquals("", fixture.view.value?.lpa?.profiles?.single()?.nickname)
            assertTrue(fixture.view.value?.lpa?.operation is LpaOperation.Connecting)

            fixture.lock.unlock()
            old.join()
            assertTrue(old.isCancelled)
            latest.await()
            assertEquals(fixture.first.id, fixture.view.value?.lpa?.selectedReaderId)
            assertEquals("first-profile", fixture.view.value?.lpa?.profiles?.single()?.iccid)
        } finally { fixture.close() }
    }

    @Test
    fun activeMutationKeepsItsReaderAndSelectionDoesNotShowAWaitFailure() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.ready()
            fixture.view.value = fixture.snapshot(fixture.first).let {
                it.copy(lpa = it.lpa.copy(operation = LpaOperation.Switching("first-profile", true)))
            }
            fixture.lock.lock()
            assertTrue(fixture.devices.select(fixture.second.id) is OperationOutcome.Failed)
            assertEquals(fixture.first.id, fixture.view.value?.lpa?.selectedReaderId)
            assertNull(fixture.view.value?.lpa?.failure)
        } finally { fixture.close() }
    }

    @Test
    fun acceptedCommandLocksSelectionBeforeItsProgressStateIsPublished() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.ready()
            fixture.view.value = fixture.snapshot(fixture.first)
            RemoteDevices::class.java.getDeclaredField("cardCommandActive").apply { isAccessible = true }
                .setBoolean(fixture.devices, true)
            assertTrue(fixture.devices.select(fixture.second.id) is OperationOutcome.Failed)
            assertEquals(fixture.first.id, fixture.view.value?.lpa?.selectedReaderId)
            assertNull(fixture.view.value?.lpa?.failure)
        } finally { fixture.close() }
    }

    private class Fixture : AutoCloseable {
        private val app = ApplicationProvider.getApplicationContext<Context>()
        private val directory = File(app.noBackupFilesDir, "reader-selection-${newDeviceId()}").apply { mkdirs() }
        private val isolated = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = directory
            override fun getPackageName(): String = app.packageName + "." + directory.name
        }
        private val metadata = ProfileMetadataStore(app)
        private val history = NotificationHistoryStore(isolated)
        val repository = LpaRepository(app, metadata, history)
        val devices = RemoteDevices(isolated, repository, AppSettingsStore(app), metadata, history, serviceControl = {})
        val peer = PairedDevice("phone", "Phone", newDeviceId(), DeviceCrypto.secret(), approved = true)
        val first = ReaderInfo("device:phone:sim1", "SIM1 on Phone", ReaderKind.REMOTE,
            deviceId = peer.id, sourceReaderId = "sim1")
        val second = ReaderInfo("device:phone:sim2", "SIM2 on Phone", ReaderKind.REMOTE,
            deviceId = peer.id, sourceReaderId = "sim2")
        val view: MutableStateFlow<DeviceSnapshot?> = field("mutableView")
        val lock: Mutex = field("requestMutex")

        suspend fun ready() {
            withTimeout(10_000) { while (!devices.ui.value.loaded) delay(10) }
            field<MutableStateFlow<List<ReaderInfo>>>("mutableReaders").value = listOf(first, second)
            field<MutableMap<String, DeviceSnapshot>>("readerSnapshots").apply {
                put(first.id, snapshot(first))
                put(second.id, snapshot(second))
            }
        }

        fun snapshot(reader: ReaderInfo) = DeviceSnapshot(lpa = LpaRepositoryState(
            readers = listOf(first, second), selectedReaderId = reader.id,
            euiccInfo = EuiccInfo(if (reader == first) "89049032000000000000000000000001" else "89049032000000000000000000000002"),
            profiles = listOf(ProfileInfo(if (reader == first) "first-profile" else "second-profile",
                ProfileState.DISABLED, "Test", "", "Provider", "", ProfileClass.TESTING)),
            initialized = true,
        ))

        @Suppress("UNCHECKED_CAST")
        fun <T> field(name: String): T = RemoteDevices::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(devices) as T

        @Suppress("UNCHECKED_CAST")
        fun <T> repositoryField(name: String): T = LpaRepository::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(repository) as T

        override fun close() {
            if (lock.isLocked) lock.unlock()
            devices.close()
            repository.close()
            directory.deleteRecursively()
        }
    }
}
