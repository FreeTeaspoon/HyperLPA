package app.hyperlpa.lpa.platform

import android.content.Context
import app.hyperlpa.data.settings.isValidRemoteReaderToken
import app.hyperlpa.R
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.lpa.ReaderEndpoint
import app.hyperlpa.lpa.ReaderProvider
import app.hyperlpa.lpa.hexToByteArray
import app.hyperlpa.lpa.toHexString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import net.typeblog.lpac_jni.ApduInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.net.ssl.HttpsURLConnection
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class RemoteReaderConfig(
    val endpointUrl: String,
    val bearerToken: String? = null,
)

internal class RemoteReaderProvider(
    context: Context,
    private val configuredReaders: () -> List<RemoteReaderConfig>,
) : ReaderProvider {
    private val appContext = context.applicationContext

    override suspend fun listReaders(): List<ReaderEndpoint> = supervisorScope {
        configuredReaders()
            .distinctBy { it.endpointUrl.trim() }
            .map { config ->
                async(Dispatchers.IO) {
                    val client = try {
                        RemoCardClient(config)
                    } catch (_: IllegalArgumentException) {
                        return@async listOf(
                            unavailableRemoteEndpoint(
                                config,
                                appContext.getString(R.string.reader_remote_invalid_endpoint),
                                appContext.getString(R.string.reader_remote_configured_name),
                            ),
                        )
                    }
                    try {
                        client.listSlots().map { slot ->
                            ReaderEndpoint(
                                info = ReaderInfo(
                                    id = stableRemoteReaderId(client.cleanBaseUrl, slot.name),
                                    name = "${client.displayHost}/${slot.name}",
                                    kind = ReaderKind.REMOTE,
                                    detail = appContext.getString(R.string.reader_remote_secure_detail),
                                    eid = slot.eid,
                                ),
                                openApduInterface = { RemoCardApduInterface(client, slot.name) },
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        listOf(
                            unavailableRemoteEndpoint(
                                config,
                                appContext.getString(R.string.reader_remote_unavailable_detail),
                                appContext.getString(R.string.reader_remote_configured_name),
                            ),
                        )
                    }
                }
            }
            .awaitAll()
            .flatten()
    }
}

private fun unavailableRemoteEndpoint(
    config: RemoteReaderConfig,
    reason: String,
    fallbackName: String,
): ReaderEndpoint {
    val safeUri = runCatching {
        val source = URI(config.endpointUrl.trim())
        URI(source.scheme, null, source.host, source.port, source.path, null, null)
    }.getOrNull()
    val safeIdentity = safeUri?.toString()?.takeIf(String::isNotBlank) ?: "invalid-remote-endpoint"
    val idHash = MessageDigest.getInstance("SHA-256")
        .digest(safeIdentity.toByteArray(StandardCharsets.UTF_8))
        .toHexString()
        .take(24)
    val displayHost = buildString {
        append(safeUri?.host?.takeIf(String::isNotBlank) ?: fallbackName)
        safeUri?.port?.takeIf { it != -1 }?.let { append(':').append(it) }
    }
    return ReaderEndpoint(
        info = ReaderInfo(
            id = "remote-unavailable:$idHash",
            name = displayHost,
            kind = ReaderKind.REMOTE,
            detail = reason,
            available = false,
        ),
        openApduInterface = { throw IllegalStateException(reason) },
    )
}

private data class RemoCardSlot(
    val name: String,
    val eid: String?,
)

private class RemoCardClient(config: RemoteReaderConfig) {
    companion object {
        private const val ConnectTimeoutMillis = 15_000
        private const val ReadTimeoutMillis = 30_000
        private const val MaxRequestBytes = 2 * 1024 * 1024
        private const val MaxResponseBytes = 2 * 1024 * 1024
        private const val MaxErrorChars = 512
        private const val MaxSlots = 64
        private const val MaxSlotNameChars = 128
        const val MaxApduBytes = 1024 * 1024
    }

    private val token = config.bearerToken?.takeIf(String::isNotEmpty)?.also {
        require(isValidRemoteReaderToken(it)) { "Remote reader token is invalid" }
    }
    private val sourceUri = runCatching { URI(config.endpointUrl.trim().trimEnd('/')) }
        .getOrElse { throw IllegalArgumentException("Remote reader endpoint is invalid") }
    private val uri = URI(
        sourceUri.scheme ?: "https",
        null,
        sourceUri.host,
        sourceUri.port,
        sourceUri.path,
        sourceUri.query,
        null,
    )
    @Volatile
    private var protocol: RemoCardProtocol? = null

    val cleanBaseUrl: String = uri.toString().trimEnd('/')
    val displayHost: String = buildString {
        append(uri.host ?: cleanBaseUrl)
        if (uri.port != -1) append(':').append(uri.port)
    }

    init {
        require(sourceUri.rawUserInfo == null) { "Put remote reader credentials in the protected token field" }
        require(uri.scheme.equals("https", true)) { "Remote readers require an HTTPS URL" }
        require(!uri.host.isNullOrBlank()) { "Remote reader URL has no host" }
        require(sourceUri.rawQuery == null && sourceUri.rawFragment == null) {
            "Remote reader endpoint cannot contain a query or fragment"
        }
    }

    fun listSlots(): List<RemoCardSlot> {
        val response = request("GET", "/listSlots")
        val slots = JSONObject(response).optJSONArray("slots") ?: JSONArray()
        return buildList {
            val seenNames = mutableSetOf<String>()
            for (index in 0 until minOf(slots.length(), MaxSlots)) {
                val slot = slots.optJSONObject(index) ?: continue
                val name = slot.optString("name").trim()
                    .takeIf { it.length in 1..MaxSlotNameChars && it.isSafeRemoteText() }
                    ?: continue
                if (!seenNames.add(name)) continue
                val eid = slot.optString("eid").takeIf { value ->
                    value.length == 32 && value.all { it in '0'..'9' }
                }
                add(RemoCardSlot(name = name, eid = eid))
            }
        }
    }

    fun post(path: String, body: JSONObject): JSONObject = JSONObject(request("POST", path, body.toString()))

    private fun request(method: String, path: String, body: String? = null): String {
        val activeProtocol = ensureProtocol()
        return performRequest(method, path, body, activeProtocol)
    }

    @Synchronized
    private fun ensureProtocol(): RemoCardProtocol {
        protocol?.let { return it }

        val version = JSONObject(performRequest("GET", "/", null, RemoCardProtocol.Plaintext))
        val versionNumber = version.optString("version")
        require(RemoCardV2Version.matches(versionNumber)) {
            "Remote reader does not advertise a compatible RemoCard v2 protocol"
        }
        val supportedCommands = version.optJSONArray("supportedCommands")?.let { commands ->
            buildSet {
                for (index in 0 until minOf(commands.length(), 64)) {
                    commands.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.orEmpty()
        require(
            supportedCommands.isEmpty() ||
                setOf("listSlots", "openChannel", "closeChannel", "sendApdu").all(supportedCommands::contains),
        ) { "Remote reader does not support the required RemoCard v2 commands" }

        val encryptionRequired = version.optBoolean("encryptionRequired", false)
        var session: RemoCardSession? = null
        val configuredToken = token
        if (encryptionRequired && configuredToken != null && version.optBoolean("sessionSupported", false)) {
            // A server advertising sessions must complete the handshake. Falling back silently
            // would reuse the long-lived bearer token as an encryption key.
            session = createSession(configuredToken)
        }
        val resolved = if (encryptionRequired) {
            val secret = requireNotNull(token) { "Remote reader requires a protected token" }
            session?.let { RemoCardProtocol.Encrypted(it.id, it.key) }
                ?: RemoCardProtocol.Encrypted(null, RemoCardLegacyCbc.key(secret))
        } else {
            RemoCardProtocol.Plaintext
        }
        protocol = resolved
        return resolved
    }

    private fun createSession(secret: String): RemoCardSession {
        val clientNonce = ByteArray(24).also(SecureRandom()::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        val response = JSONObject(
            performRequest(
                "POST",
                "/handshake",
                JSONObject().put("clientNonce", clientNonce).toString(),
                RemoCardProtocol.Plaintext,
            ),
        )
        val sessionId = response.optString("sessionId").takeIf {
            it.length in 1..128 && it.isSafeHeaderValue()
        }
            ?: error("Remote reader returned an invalid session ID")
        val serverNonce = response.optString("serverNonce").takeIf { it.length in 1..128 }
            ?: error("Remote reader returned an invalid server nonce")
        val key = MessageDigest.getInstance("SHA-256")
            .digest((secret + clientNonce + serverNonce).toByteArray(StandardCharsets.UTF_8))
        return RemoCardSession(sessionId, key)
    }

    private fun performRequest(
        method: String,
        path: String,
        body: String?,
        activeProtocol: RemoCardProtocol,
    ): String {
        val openedConnection = URL(cleanBaseUrl + path).openConnection()
        require(openedConnection is HttpsURLConnection) { "Remote reader transport is not authenticated HTTPS" }
        val connection: HttpURLConnection = openedConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = ConnectTimeoutMillis
            connection.readTimeout = ReadTimeoutMillis
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Remocard-Version", "2")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (activeProtocol is RemoCardProtocol.Encrypted && activeProtocol.sessionId != null) {
                connection.setRequestProperty("X-Remo-Session", activeProtocol.sessionId)
            }
            if (body != null) {
                connection.doOutput = true
                val encodedBody = when (activeProtocol) {
                    is RemoCardProtocol.Encrypted -> JSONObject()
                        .put("data", RemoCardLegacyCbc.encrypt(body, activeProtocol.key))
                        .toString()
                    RemoCardProtocol.Plaintext -> body
                }.toByteArray(StandardCharsets.UTF_8)
                require(encodedBody.size <= MaxRequestBytes) { "Remote reader request is too large" }
                connection.setFixedLengthStreamingMode(encodedBody.size)
                connection.outputStream.use { it.write(encodedBody) }
            }

            val status = connection.responseCode
            val length = connection.contentLengthLong
            require(length < 0 || length <= MaxResponseBytes) { "Remote reader response is too large" }
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.readUtf8Limited(MaxResponseBytes)
                .orEmpty()
            val decoded = when (activeProtocol) {
                is RemoCardProtocol.Encrypted -> try {
                    val encrypted = JSONObject(raw).getString("data")
                    require(encrypted.isNotBlank()) { "Encrypted response is empty" }
                    RemoCardLegacyCbc.decrypt(encrypted, activeProtocol.key)
                } catch (error: Exception) {
                    throw IllegalStateException(
                        "Remote reader returned an invalid encrypted response (HTTP $status)",
                        error,
                    )
                }
                RemoCardProtocol.Plaintext -> raw
            }

            if (status !in 200..299) {
                val message = runCatching {
                    val error = JSONObject(decoded)
                    error.optString("message").ifBlank { error.optString("error") }
                }.getOrNull().orEmpty().ifBlank { decoded.ifBlank { "HTTP $status" } }
                    .take(MaxErrorChars)
                throw IllegalStateException("Remote reader request failed: $message")
            }
            return decoded.ifBlank { "{}" }
        } finally {
            connection.disconnect()
        }
    }
}

private sealed interface RemoCardProtocol {
    data object Plaintext : RemoCardProtocol
    data class Encrypted(val sessionId: String?, val key: ByteArray) : RemoCardProtocol
}

private data class RemoCardSession(val id: String, val key: ByteArray)

/**
 * RemoCard v2's legacy optional payload envelope.
 *
 * AES-CBC has no message authentication and is not a security boundary. It is retained solely for
 * wire compatibility and may only be used inside the authenticated HTTPS connection enforced by
 * [RemoCardClient]. A future RemoCard protocol should negotiate an AEAD envelope instead.
 */
internal object RemoCardLegacyCbc {
    private val random = SecureRandom()
    private const val MaxPayloadBytes = 2 * 1024 * 1024

    fun encrypt(plaintext: String, key: ByteArray): String {
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        require(plaintextBytes.size <= MaxPayloadBytes) { "RemoCard payload is too large" }
        require(key.size == 32) { "RemoCard AES key must be 256 bits" }
        val iv = ByteArray(16).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintextBytes))
    }

    fun decrypt(encoded: String, key: ByteArray): String {
        require(encoded.length <= ((MaxPayloadBytes + 32) * 4 / 3) + 4) { "Encrypted response is too large" }
        require(key.size == 32) { "RemoCard AES key must be 256 bits" }
        val combined = Base64.getDecoder().decode(encoded)
        require(combined.size in 32..(MaxPayloadBytes + 32) && (combined.size - 16) % 16 == 0) {
            "Encrypted response is malformed"
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(combined.copyOfRange(0, 16)))
        return String(cipher.doFinal(combined.copyOfRange(16, combined.size)), StandardCharsets.UTF_8)
    }

    fun key(password: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(password.toByteArray(StandardCharsets.UTF_8))
}

private fun InputStream.readUtf8Limited(maxBytes: Int): String = use { input ->
    val output = ByteArrayOutputStream(minOf(8_192, maxBytes))
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        require(total <= maxBytes) { "Remote reader response is too large" }
        output.write(buffer, 0, read)
    }
    output.toString(StandardCharsets.UTF_8.name())
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
        require(aid.size in 5..16) { "Remote reader AID must be 5 to 16 bytes" }
        val requestedAid = aid.toHexString()
        val response = client.post(
            "/openChannel",
            JSONObject().put("reader", readerName).put("aids", JSONArray().put(requestedAid)),
        )
        check(response.optBoolean("success")) { "Remote reader rejected ISD-R AID $requestedAid" }
        val selectedAid = response.optString("aid").ifBlank { requestedAid }.also { value ->
            require(value.length in 10..32 && value.length % 2 == 0 && value.all(::isHexCharacter)) {
                "Remote reader returned an invalid selected AID"
            }
        }
        val handle = nextHandle.getAndIncrement()
        synchronized(aids) { aids[handle] = selectedAid }
        return handle
    }

    override fun logicalChannelClose(handle: Int) {
        val aid = synchronized(aids) { aids[handle] }
            ?: throw IllegalArgumentException("Unknown remote channel $handle")
        client.post(
            "/closeChannel",
            JSONObject().put("reader", readerName).put("aids", JSONArray().put(aid)),
        )
        synchronized(aids) { aids.remove(handle) }
    }

    override fun transmit(handle: Int, tx: ByteArray): ByteArray {
        val aid = synchronized(aids) { aids[handle] }
            ?: throw IllegalArgumentException("Unknown remote channel $handle")
        require(tx.isNotEmpty() && tx.size <= RemoCardClient.MaxApduBytes) { "Invalid remote APDU length" }
        val response = client.post(
            "/sendApdu",
            JSONObject()
                .put("reader", readerName)
                .put("aid", aid)
                .put("apdu", tx.toHexString()),
        )
        val responseHex = response.getString("response")
        require(responseHex.length <= RemoCardClient.MaxApduBytes * 2 && responseHex.length % 2 == 0) {
            "Remote reader returned an invalid APDU response"
        }
        return runCatching { responseHex.hexToByteArray() }
            .getOrElse { throw IllegalStateException("Remote reader returned an invalid APDU response", it) }
    }
}

private fun stableRemoteReaderId(baseUrl: String, slotName: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$baseUrl\u0000$slotName".toByteArray(StandardCharsets.UTF_8))
        .toHexString()
        .take(32)
    return "remote:$digest"
}

private fun String.isSafeHeaderValue(): Boolean =
    isNotEmpty() && none { it.isISOControl() || it == '\u007f' }

private fun String.isSafeRemoteText(): Boolean = none { character ->
    character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()
}

private fun isHexCharacter(value: Char): Boolean = value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'

private val RemoCardV2Version = Regex("2(?:\\.[0-9]+)*")
