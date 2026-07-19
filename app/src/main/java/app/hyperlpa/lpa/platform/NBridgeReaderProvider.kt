package app.hyperlpa.lpa.platform

import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.lpa.ReaderEndpoint
import app.hyperlpa.lpa.ReaderProvider
import app.hyperlpa.lpa.hexToByteArray
import app.hyperlpa.lpa.toHexString
import net.typeblog.lpac_jni.ApduInterface
import java.util.concurrent.atomic.AtomicInteger

internal class NBridgeReaderProvider(context: Context) : ReaderProvider {
    private val appContext = context.applicationContext
    private val client = NBridgeClient(appContext)

    override suspend fun listReaders(): List<ReaderEndpoint> {
        if (!client.isAvailable()) return emptyList()
        return client.listSlots().mapNotNull { slot ->
            val id = slot.getString("id").orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            val name = slot.getString("displayName").orEmpty().ifEmpty { id }
            ReaderEndpoint(
                info = ReaderInfo(
                    id = "nbridge:$id",
                    name = name,
                    kind = ReaderKind.NBRIDGE,
                    detail = "NBridge secure-element provider",
                ),
                openApduInterface = { NBridgeApduInterface(client, id) },
            )
        }
    }
}

private class NBridgeClient(private val context: Context) {
    companion object {
        private const val Authority = "ee.nekoko.nbridge.provider"
    }

    private val providerUri = "content://$Authority".toUri()

    fun isAvailable(): Boolean = context.packageManager.resolveContentProvider(Authority, 0) != null

    fun listSlots(): List<Bundle> {
        val response = call("listSlots")
        return BundleCompat.getParcelableArrayList(response, "slots", Bundle::class.java).orEmpty()
    }

    fun openLogicalChannel(slotId: String, aid: String): NBridgeConnection {
        val response = call(
            method = "connectLogicalChannel",
            extras = Bundle().apply {
                putString("slotId", slotId)
                putStringArrayList("aids", arrayListOf(aid))
            },
        )
        return NBridgeConnection(
            id = response.getString("connectionId")
                ?: throw IllegalStateException("NBridge did not return a connection ID"),
            selectedAid = response.getString("selectedAid") ?: aid,
        )
    }

    fun transmitLogical(connectionId: String, apdu: ByteArray): ByteArray {
        val response = call(
            method = "transmitLogical",
            extras = Bundle().apply {
                putString("connectionId", connectionId)
                putString("apdu", apdu.toHexString())
            },
        )
        return response.getString("responseApdu")
            ?.hexToByteArray()
            ?: throw IllegalStateException("NBridge returned no APDU response")
    }

    fun closeLogicalChannel(connectionId: String) {
        call(
            method = "closeLogicalChannel",
            extras = Bundle().apply { putString("connectionId", connectionId) },
        )
    }

    private fun call(method: String, extras: Bundle? = null): Bundle {
        val response = context.contentResolver.call(providerUri, method, null, extras) ?: Bundle.EMPTY
        if (response.containsKey("ok") && !response.getBoolean("ok")) {
            val code = response.getString("errorCode").orEmpty()
            val message = response.getString("errorMessage").orEmpty()
            throw IllegalStateException(listOf(code, message).filter(String::isNotBlank).joinToString(": "))
        }
        return response
    }
}

private data class NBridgeConnection(
    val id: String,
    val selectedAid: String,
)

private class NBridgeApduInterface(
    private val client: NBridgeClient,
    private val slotId: String,
) : ApduInterface {
    private val nextHandle = AtomicInteger(1)
    private val connections = mutableMapOf<Int, NBridgeConnection>()
    private var connected = false

    override val valid: Boolean
        get() = connected

    override fun connect() {
        connected = true
    }

    override fun disconnect() {
        synchronized(connections) {
            connections.values.forEach { connection -> runCatching { client.closeLogicalChannel(connection.id) } }
            connections.clear()
        }
        connected = false
    }

    override fun logicalChannelOpen(aid: ByteArray): Int {
        check(connected) { "NBridge is not connected" }
        val connection = client.openLogicalChannel(slotId, aid.toHexString())
        val handle = nextHandle.getAndIncrement()
        synchronized(connections) { connections[handle] = connection }
        return handle
    }

    override fun logicalChannelClose(handle: Int) {
        val connection = synchronized(connections) { connections.remove(handle) }
            ?: throw IllegalArgumentException("Unknown NBridge channel $handle")
        client.closeLogicalChannel(connection.id)
    }

    override fun transmit(handle: Int, tx: ByteArray): ByteArray {
        val connection = synchronized(connections) { connections[handle] }
            ?: throw IllegalArgumentException("Unknown NBridge channel $handle")
        return client.transmitLogical(connection.id, tx)
    }
}
