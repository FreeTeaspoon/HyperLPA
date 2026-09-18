package app.hyperlpa.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** TLS authenticates the relay; DeviceCrypto separately authenticates paired devices. */
internal class RelayConnection(
    private val scope: CoroutineScope,
    private val onConnection: (Boolean) -> Unit,
    private val onEnvelope: (DeviceEnvelope) -> Unit,
    private val onRejected: (String?, String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .pingInterval(30, TimeUnit.SECONDS).build()
    @Volatile private var socket: WebSocket? = null
    private var job: Job? = null
    @Volatile private var generation = 0L
    private val queuedBytes = java.util.concurrent.atomic.AtomicLong()

    fun register(config: StoredDevices, enrollmentKey: String) {
        val body = DeviceJson.encodeToString(mapOf("id" to config.id, "token" to config.token))
        request(config, "/v1/register", body, enrollmentKey)
    }

    fun unregister(config: StoredDevices) { request(config, "/v1/unregister", "{}") }

    private fun request(config: StoredDevices, path: String, body: String, enrollmentKey: String? = null): JsonObject {
        val builder = Request.Builder().url(relayAddress(config.relay) + path)
            .header("X-Device-Id", config.id).header("Authorization", "Bearer ${config.token}")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (enrollmentKey != null) builder.header("X-Enrollment-Key", enrollmentKey)
        return client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "Relay request failed (${response.code})" }
            val source = requireNotNull(response.body).source()
            source.request(8193)
            check(source.buffer.size <= 8192) { "Invalid relay response" }
            DeviceJson.decodeFromString(JsonObject.serializer(), source.readUtf8())
        }
    }

    fun start(config: StoredDevices) {
        stop()
        val owner = generation
        job = scope.launch {
            var failures = 0
            while (isActive) {
                val closed = CompletableDeferred<Unit>()
                val ready = CompletableDeferred<Unit>()
                var ownedSocket: WebSocket? = null
                try {
                    val ticket = request(config, "/v1/session", "{}")["ticket"]?.jsonPrimitive?.content
                        ?: error("Relay returned no session")
                    check(ticket.matches(Regex("[A-Za-z0-9_-]{43}")))
                    val ws = client.newWebSocket(Request.Builder()
                        .url(config.relay + "/v1/socket?ticket=$ticket").build(), object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (owner != generation) return
                            if (text.length > DeviceFrameLimit) { webSocket.close(1009, null); return }
                            if (text == "pong") return
                            val frame = runCatching { DeviceJson.decodeFromString(RelayFrame.serializer(), text) }.getOrNull() ?: return
                            when (frame.type) {
                                "ready" -> ready.complete(Unit)
                                "message" -> frame.message?.let(onEnvelope)
                                "error" -> onRejected(frame.id, frame.code ?: "relay_error")
                            }
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); closed.complete(Unit) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ready.completeExceptionally(t); closed.complete(Unit) }
                    })
                    ownedSocket = ws
                    if (owner != generation) { ws.cancel(); return@launch }
                    socket = ws
                    withTimeout(30_000) { ready.await() }
                    failures = 0
                    onConnection(true)
                    closed.await()
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { failures++ }
                finally {
                    ownedSocket?.cancel()
                    if (owner == generation) { socket = null; onConnection(false) }
                }
                delay((1000L shl failures.coerceAtMost(5)) + kotlin.random.Random.nextLong(500))
            }
        }
    }

    fun send(envelope: DeviceEnvelope): Boolean {
        val target = socket ?: return false
        val frame = DeviceJson.encodeToString(RelayFrame.serializer(), RelayFrame("send", envelope))
        if (queuedBytes.addAndGet(frame.length.toLong()) > 32_000_000) {
            queuedBytes.addAndGet(-frame.length.toLong())
            return false
        }
        scope.launch {
            try {
                withTimeout(30_000) {
                    while (socket === target) {
                        val queued = synchronized(target) {
                            if (target.queueSize() > 2_000_000) false
                            else { target.send(frame); true }
                        }
                        if (queued) break
                        delay(25)
                    }
                }
            } finally { queuedBytes.addAndGet(-frame.length.toLong()) }
        }
        return true
    }
    fun acknowledge(id: String) { socket?.send(DeviceJson.encodeToString(RelayFrame.serializer(), RelayFrame("ack", id = id))) }
    fun stop() { generation++; job?.cancel(); job = null; socket?.cancel(); socket = null; onConnection(false) }
}
