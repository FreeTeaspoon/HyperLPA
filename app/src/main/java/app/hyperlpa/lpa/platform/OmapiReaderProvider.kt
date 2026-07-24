package app.hyperlpa.lpa.platform

import android.content.Context
import android.os.Build
import android.se.omapi.Channel
import android.se.omapi.Reader
import android.se.omapi.SEService
import android.se.omapi.Session
import android.telephony.TelephonyManager
import app.hyperlpa.R
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.lpa.ReaderEndpoint
import app.hyperlpa.lpa.ReaderProvider
import app.hyperlpa.lpa.toHexString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import net.typeblog.lpac_jni.ApduInterface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal class OmapiReaderProvider(context: Context) : ReaderProvider {
    private val appContext = context.applicationContext
    private val telephony = appContext.getSystemService(TelephonyManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val serviceMutex = Mutex()
    private var service: SEService? = null

    override suspend fun listReaders(): List<ReaderEndpoint> {
        val currentService = ensureService()
        return discoverUiccReaders(currentService)
            .map { reader ->
                val cardPresent = runCatching { reader.isSecureElementPresent }.getOrNull()
                ReaderEndpoint(
                    info = ReaderInfo(
                        id = "omapi:${reader.name}",
                        name = reader.name,
                        kind = ReaderKind.OMAPI,
                        detail = when (cardPresent) {
                            true -> appContext.getString(R.string.reader_omapi_card_detected)
                            false -> appContext.getString(R.string.reader_omapi_no_card)
                            null -> appContext.getString(R.string.reader_omapi_status_unavailable)
                        },
                        available = cardPresent != false,
                    ),
                    openApduInterface = {
                        OmapiApduInterface(currentService, reader)
                    },
                )
            }
            .sortedWith(
                compareByDescending<ReaderEndpoint> { it.info.available }
                    .thenBy { it.info.name },
            )
    }

    private fun discoverUiccReaders(currentService: SEService): List<Reader> {
        val readers = linkedMapOf<String, Reader>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val modemCount = runCatching { telephony?.activeModemCount ?: 0 }.getOrDefault(0)
            for (slotNumber in 1..modemCount) {
                runCatching { currentService.getUiccReader(slotNumber) }
                    .getOrNull()
                    ?.let { reader -> readers[reader.name] = reader }
            }
        }

        runCatching { currentService.readers.toList() }
            .getOrDefault(emptyList())
            .filter { reader ->
                reader.name.equals("SIM", ignoreCase = true) ||
                    reader.name.startsWith("SIM", ignoreCase = true)
            }
            .forEach { reader -> readers.putIfAbsent(reader.name, reader) }

        return readers.values.toList()
    }

    private suspend fun ensureService(): SEService = serviceMutex.withLock {
        service?.takeIf(SEService::isConnected)?.let { return@withLock it }
        service?.let { stale -> runCatching { stale.shutdown() } }
        service = null
        val connected = CompletableDeferred<Unit>()
        val created = SEService(appContext, executor) {
            if (!connected.isCompleted) connected.complete(Unit)
        }
        try {
            withTimeout(5_000) { connected.await() }
            check(created.isConnected) { "OMAPI service disconnected during startup" }
            service = created
            created
        } catch (error: Throwable) {
            runCatching { created.shutdown() }
            throw error
        }
    }

    override fun close() {
        service?.shutdown()
        service = null
        executor.shutdown()
    }
}

private class OmapiApduInterface(
    private val service: SEService,
    private val reader: Reader,
) : ApduInterface {
    companion object {
        private const val MaxApduBytes = 1024 * 1024
    }

    private val nextHandle = AtomicInteger(1)
    private val channels = mutableMapOf<Int, Channel>()
    private var session: Session? = null

    override val valid: Boolean
        get() = service.isConnected && session?.isClosed == false

    override fun connect() {
        if (valid) return
        check(runCatching { reader.isSecureElementPresent }.getOrDefault(true)) {
            "No SIM card is present in ${reader.name}"
        }
        session = reader.openSession()
    }

    override fun disconnect() {
        synchronized(channels) {
            channels.values.forEach { channel -> runCatching { if (channel.isOpen) channel.close() } }
            channels.clear()
        }
        runCatching { session?.close() }
        session = null
    }

    override fun logicalChannelOpen(aid: ByteArray): Int {
        val activeSession = session ?: throw IllegalStateException("OMAPI session is not connected")
        require(aid.size in 5..16) { "OMAPI AID must be 5 to 16 bytes" }
        val channel = activeSession.openLogicalChannel(aid)
            ?: throw IllegalStateException("Unable to open ISD-R channel ${aid.toHexString()}")
        val handle = nextHandle.getAndIncrement()
        synchronized(channels) { channels[handle] = channel }
        return handle
    }

    override fun logicalChannelClose(handle: Int) {
        val channel = synchronized(channels) { channels[handle] }
            ?: throw IllegalArgumentException("Unknown OMAPI channel $handle")
        if (channel.isOpen) channel.close()
        synchronized(channels) { channels.remove(handle) }
    }

    override fun transmit(handle: Int, tx: ByteArray): ByteArray {
        val channel = synchronized(channels) { channels[handle] }
            ?: throw IllegalArgumentException("Unknown OMAPI channel $handle")
        require(tx.isNotEmpty() && tx.size <= MaxApduBytes) { "Invalid OMAPI APDU length" }
        repeat(10) {
            val response = channel.transmit(tx)
            if (!(response.size == 2 && response[0] == 0x66.toByte() && response[1] == 0x01.toByte())) {
                return response
            }
        }
        throw IllegalStateException("OMAPI checksum retry limit reached")
    }
}
