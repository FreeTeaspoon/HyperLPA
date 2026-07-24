package app.hyperlpa.lpa.platform

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import app.hyperlpa.R
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
                    detail = appContext.getString(R.string.reader_nbridge_detail),
                ),
                openApduInterface = { NBridgeApduInterface(client, id) },
            )
        }
    }
}

private class NBridgeClient(private val context: Context) {
    companion object {
        private const val MaxSlots = 32
        private const val MaxIdentifierLength = 256
        private const val MaxApduBytes = 1024 * 1024
        // OTBridge currently signs releases with its CI-generated Android debug
        // certificate. Keep this fail-closed allowlist synchronized with verified
        // upstream release APKs; certificate rotation requires an app update.
        // v1.1.0, v1.2.0 and the historical `latest` prerelease respectively.
        private val TrustedCertificateSha256 = listOf(
            "95562fda2ff43c7cb5f4ea1e57f63e6bec01f5319cba546895f56e032885d756",
            "a37ac77ef34cb60363b57ccc4f677c6b9be69c640f7fd41e7a4e6784a4b7a9ba",
            "267668eaf061687b7ed43dd58d0a00a8c37958f6b328e3c55569433a1725d2f6",
        ).map(String::hexToByteArray)
    }

    private val providerUri = "content://$NBridgeAuthority".toUri()

    fun isAvailable(): Boolean = trustedProvider() != null

    fun listSlots(): List<Bundle> {
        val response = call("listSlots")
        return BundleCompat.getParcelableArrayList(response, "slots", Bundle::class.java)
            .orEmpty()
            .take(MaxSlots)
            .filter { slot ->
                slot.getString("id").isSafeIdentifier() &&
                    slot.getString("displayName").orEmpty().let { name ->
                        name.length <= MaxIdentifierLength && name.none(Char::isISOControl)
                    }
            }
    }

    fun openLogicalChannel(slotId: String, aid: String): NBridgeConnection {
        require(slotId.isSafeIdentifier()) { "Invalid NBridge slot ID" }
        require(isValidAid(aid)) { "Invalid NBridge AID" }
        val response = call(
            method = "connectLogicalChannel",
            extras = Bundle().apply {
                putString("slotId", slotId)
                putStringArrayList("aids", arrayListOf(aid))
            },
        )
        val connectionId = response.getString("connectionId")
            .takeIf { it.isSafeIdentifier() }
            ?: throw IllegalStateException("NBridge did not return a valid connection ID")
        val selectedAid = response.getString("selectedAid")?.takeIf(::isValidAid) ?: aid
        return NBridgeConnection(id = connectionId, selectedAid = selectedAid)
    }

    fun transmitLogical(connectionId: String, apdu: ByteArray): ByteArray {
        require(connectionId.isSafeIdentifier()) { "Invalid NBridge connection ID" }
        require(apdu.isNotEmpty() && apdu.size <= MaxApduBytes) { "Invalid NBridge APDU length" }
        val response = call(
            method = "transmitLogical",
            extras = Bundle().apply {
                putString("connectionId", connectionId)
                putString("apdu", apdu.toHexString())
            },
        )
        val responseHex = response.getString("responseApdu")
            ?: throw IllegalStateException("NBridge returned no APDU response")
        require(responseHex.length <= MaxApduBytes * 2 && responseHex.length % 2 == 0) {
            "NBridge returned an invalid APDU response"
        }
        return runCatching { responseHex.hexToByteArray() }
            .getOrElse { throw IllegalStateException("NBridge returned an invalid APDU response", it) }
    }

    fun closeLogicalChannel(connectionId: String) {
        require(connectionId.isSafeIdentifier()) { "Invalid NBridge connection ID" }
        call(
            method = "closeLogicalChannel",
            extras = Bundle().apply { putString("connectionId", connectionId) },
        )
    }

    private fun call(method: String, extras: Bundle? = null): Bundle {
        check(trustedProvider() != null) { "The trusted OTBridge provider is not installed" }
        val response = context.contentResolver.call(providerUri, method, null, extras) ?: Bundle.EMPTY
        if (response.containsKey("ok") && !response.getBoolean("ok")) {
            val code = response.getString("errorCode").orEmpty()
            val message = response.getString("errorMessage").orEmpty()
            val safeMessage = listOf(code, message)
                .filter(String::isNotBlank)
                .joinToString(": ")
                .filterNot(Char::isISOControl)
                .take(MaxIdentifierLength)
            throw IllegalStateException(safeMessage.ifBlank { "NBridge request failed" })
        }
        return response
    }

    private fun trustedProvider(): ProviderInfo? {
        val packageManager = context.packageManager
        val provider = packageManager.resolveContentProvider(
            NBridgeAuthority,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        ) ?: return null
        val trustedSigner = provider.packageName == NBridgeTrustedPackage &&
            TrustedCertificateSha256.any { digest ->
                runCatching {
                    packageManager.hasSigningCertificate(
                        NBridgeTrustedPackage,
                        digest,
                        PackageManager.CERT_INPUT_SHA256,
                    )
                }.getOrDefault(false)
            }
        return provider.takeIf {
            matchesNBridgeTrustPolicy(
                packageName = it.packageName,
                authorities = it.authority,
                exported = it.exported,
                providerEnabled = it.enabled,
                applicationEnabled = it.applicationInfo?.enabled != false,
                trustedSigner = trustedSigner,
            )
        }
    }

    private fun String?.isSafeIdentifier(): Boolean =
        this != null && length in 1..MaxIdentifierLength && none(Char::isISOControl)

    private fun isValidAid(value: String): Boolean =
        value.length in 10..32 && value.length % 2 == 0 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}

private const val NBridgeAuthority = "ee.nekoko.nbridge.provider"
private const val NBridgeTrustedPackage = "ee.nekoko.nbridge"

internal fun matchesNBridgeTrustPolicy(
    packageName: String?,
    authorities: String?,
    exported: Boolean,
    providerEnabled: Boolean,
    applicationEnabled: Boolean,
    trustedSigner: Boolean,
): Boolean = packageName == NBridgeTrustedPackage &&
    authorities?.split(';')?.contains(NBridgeAuthority) == true &&
    exported && providerEnabled && applicationEnabled && trustedSigner

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
        val connection = synchronized(connections) { connections[handle] }
            ?: throw IllegalArgumentException("Unknown NBridge channel $handle")
        client.closeLogicalChannel(connection.id)
        synchronized(connections) { connections.remove(handle) }
    }

    override fun transmit(handle: Int, tx: ByteArray): ByteArray {
        val connection = synchronized(connections) { connections[handle] }
            ?: throw IllegalArgumentException("Unknown NBridge channel $handle")
        return client.transmitLogical(connection.id, tx)
    }
}
