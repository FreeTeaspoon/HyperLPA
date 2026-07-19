package app.hyperlpa.lpa.platform

import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.lpa.ReaderEndpoint
import app.hyperlpa.lpa.ReaderProvider
import app.hyperlpa.lpa.hexToByteArray
import app.hyperlpa.lpa.toHexString
import net.typeblog.lpac_jni.ApduInterface
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class RemoteReaderProvider(
    private val configuredUrls: () -> List<String>,
) : ReaderProvider {
    override suspend fun listReaders(): List<ReaderEndpoint> = configuredUrls()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .flatMap { configuredUrl ->
            val client = RemoCardClient(configuredUrl)
            client.listSlots().map { slot ->
                ReaderEndpoint(
                    info = ReaderInfo(
                        id = "remote:${client.cleanBaseUrl}|${slot.name}",
                        name = "${client.displayHost}/${slot.name}",
                        kind = ReaderKind.REMOTE,
                        detail = "RemoCard v2",
                        eid = slot.eid,
                    ),
                    openApduInterface = { RemoCardApduInterface(client, slot.name) },
                )
            }
        }
}

private data class RemoCardSlot(
    val name: String,
    val eid: String?,
)

private class RemoCardClient(configuredUrl: String) {
    private val sourceUri = URI(configuredUrl.trim().trimEnd('/'))
    private val password = sourceUri.userInfo?.let { userInfo ->
        userInfo.substringAfter(':', userInfo).takeIf(String::isNotBlank)
    }
    private val uri = URI(
        sourceUri.scheme ?: "https",
        null,
        sourceUri.host,
        sourceUri.port,
        sourceUri.path,
        sourceUri.query,
        null,
    )

    val cleanBaseUrl: String = uri.toString().trimEnd('/')
    val displayHost: String = buildString {
        append(uri.host ?: cleanBaseUrl)
        if (uri.port != -1) append(':').append(uri.port)
    }

    init {
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Remote readers require an HTTP or HTTPS URL"
        }
        require(!uri.host.isNullOrBlank()) { "Remote reader URL has no host" }
    }

    fun listSlots(): List<RemoCardSlot> {
        val response = request("GET", "/listSlots")
        val slots = JSONObject(response).optJSONArray("slots") ?: JSONArray()
        return buildList {
            for (index in 0 until slots.length()) {
                val slot = slots.optJSONObject(index) ?: continue
                val name = slot.optString("name").takeIf(String::isNotBlank) ?: continue
                add(RemoCardSlot(name = name, eid = slot.optString("eid").takeIf(String::isNotBlank)))
            }
        }
    }

    fun post(path: String, body: JSONObject): JSONObject = JSONObject(request("POST", path, body.toString()))

    private fun request(method: String, path: String, body: String? = null): String {
        val connection = URL(cleanBaseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 100_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Remocard-Version", "2")
        password?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            connection.doOutput = true
            val encodedBody = password?.let { secret ->
                JSONObject().put("data", RemoCardCrypto.encrypt(body, secret)).toString()
            } ?: body
            connection.outputStream.use { output ->
                output.write(encodedBody.toByteArray(StandardCharsets.UTF_8))
            }
        }

        val status = connection.responseCode
        val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        connection.disconnect()

        val decoded = password?.let { secret ->
            runCatching {
                JSONObject(raw).optString("data").takeIf(String::isNotBlank)?.let { RemoCardCrypto.decrypt(it, secret) }
            }.getOrNull()
        } ?: raw

        if (status !in 200..299) {
            val message = runCatching {
                val error = JSONObject(decoded)
                error.optString("message").ifBlank { error.optString("error") }
            }.getOrNull().orEmpty().ifBlank { decoded.ifBlank { "HTTP $status" } }
            throw IllegalStateException("Remote reader request failed: $message")
        }
        return decoded.ifBlank { "{}" }
    }
}

private object RemoCardCrypto {
    private val random = SecureRandom()

    fun encrypt(plaintext: String, password: String): String {
        val iv = ByteArray(16).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password), IvParameterSpec(iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decrypt(encoded: String, password: String): String {
        val combined = Base64.getDecoder().decode(encoded)
        require(combined.size > 16) { "Encrypted response is too short" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key(password), IvParameterSpec(combined.copyOfRange(0, 16)))
        return String(cipher.doFinal(combined.copyOfRange(16, combined.size)), StandardCharsets.UTF_8)
    }

    private fun key(password: String): SecretKeySpec = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(password.toByteArray(StandardCharsets.UTF_8)),
        "AES",
    )
}

private class RemoCardApduInterface(
    private val client: RemoCardClient,
    private val readerName: String,
) : ApduInterface {
    private val nextHandle = AtomicInteger(1)
    private val aids = linkedMapOf<Int, String>()
    private var connected = false

    override val valid: Boolean
        get() = connected

    override fun connect() {
        connected = true
    }

    override fun disconnect() {
        synchronized(aids) {
            aids.toMap().forEach { (handle, _) -> runCatching { logicalChannelClose(handle) } }
            aids.clear()
        }
        connected = false
    }

    override fun logicalChannelOpen(aid: ByteArray): Int {
        check(connected) { "Remote reader is not connected" }
        val requestedAid = aid.toHexString()
        val response = client.post(
            "/openChannel",
            JSONObject().put("reader", readerName).put("aids", JSONArray().put(requestedAid)),
        )
        check(response.optBoolean("success")) { "Remote reader rejected ISD-R AID $requestedAid" }
        val selectedAid = response.optString("aid").ifBlank { requestedAid }
        val handle = nextHandle.getAndIncrement()
        synchronized(aids) { aids[handle] = selectedAid }
        return handle
    }

    override fun logicalChannelClose(handle: Int) {
        val aid = synchronized(aids) { aids.remove(handle) }
            ?: throw IllegalArgumentException("Unknown remote channel $handle")
        client.post(
            "/closeChannel",
            JSONObject().put("reader", readerName).put("aids", JSONArray().put(aid)),
        )
    }

    override fun transmit(handle: Int, tx: ByteArray): ByteArray {
        val aid = synchronized(aids) { aids[handle] }
            ?: throw IllegalArgumentException("Unknown remote channel $handle")
        val response = client.post(
            "/sendApdu",
            JSONObject()
                .put("reader", readerName)
                .put("aid", aid)
                .put("apdu", tx.toHexString()),
        )
        return response.getString("response").hexToByteArray()
    }
}

