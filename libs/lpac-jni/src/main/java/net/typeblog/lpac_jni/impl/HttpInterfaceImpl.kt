package net.typeblog.lpac_jni.impl

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.typeblog.lpac_jni.HttpInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory


class HttpInterfaceImpl(
    private val verboseLoggingFlow: Flow<Boolean>,
    private val httpProxyFlow: Flow<String>
) : HttpInterface {
    companion object {
        private const val TAG = "HttpInterfaceImpl"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val NOTIFICATION_TIMEOUT_MS = 10_000
        private const val REQUEST_DEADLINE_MS = 180_000L
        private const val NOTIFICATION_DEADLINE_MS = 20_000L
        private const val MAX_HTTP_BODY_BYTES = 32 * 1024 * 1024
        private val HTTP_HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }

    private lateinit var trustManagers: Array<TrustManager>

    override fun transmit(
        url: String,
        tx: ByteArray,
        headers: Array<String>
    ): HttpInterface.HttpResponse {
        val parsedUrl = validateRspRequestUrl(url)
        require(tx.size <= MAX_HTTP_BODY_BYTES) { "HTTP request body is too large" }

        val verbose = runBlocking { verboseLoggingFlow.first() }
        if (verbose) {
            Log.d(TAG, "transmit(host = ${parsedUrl.host}, path = ${parsedUrl.path})")
            Log.d(TAG, "HTTP request body: ${tx.size} bytes")
        }

        val proxy = runBlocking { httpProxyFlow.first().toUri().normalizeScheme() }
        val conn = parsedUrl.openConnection(proxy) as HttpsURLConnection
        try {
            val isNotificationRequest = parsedUrl.path.contains("handleNotification", ignoreCase = true)
            val deadlineNanos = System.nanoTime() +
                (if (isNotificationRequest) NOTIFICATION_DEADLINE_MS else REQUEST_DEADLINE_MS) * 1_000_000L

            conn.connectTimeout = if (isNotificationRequest) NOTIFICATION_TIMEOUT_MS else CONNECT_TIMEOUT_MS
            conn.readTimeout = if (isNotificationRequest) NOTIFICATION_TIMEOUT_MS else READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false

            conn.sslSocketFactory = getSocketFactory()
            conn.requestMethod = "POST"
            conn.doInput = true
            conn.doOutput = true
            conn.useCaches = false
            conn.setFixedLengthStreamingMode(tx.size)

            for (h in headers) {
                val separator = h.indexOf(':')
                require(separator > 0) { "Malformed HTTP header" }
                val name = h.substring(0, separator).trim()
                val value = h.substring(separator + 1).trim()
                require(HTTP_HEADER_NAME.matches(name)) { "Malformed HTTP header name" }
                require('\n' !in value && '\r' !in value) { "Malformed HTTP header value" }
                conn.setRequestProperty(name, value)
            }

            conn.outputStream.use { output ->
                output.write(tx)
                output.flush()
            }

            val responseCode = conn.responseCode
            if (verbose) Log.d(TAG, "transmit responseCode = $responseCode")
            val declaredLength = conn.contentLengthLong
            if (declaredLength > MAX_HTTP_BODY_BYTES) {
                throw IOException("HTTP response body exceeds the ${MAX_HTTP_BODY_BYTES / (1024 * 1024)} MiB limit")
            }

            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { input ->
                input.readLimited(MAX_HTTP_BODY_BYTES, deadlineNanos)
            } ?: byteArrayOf()
            if (verbose) {
                Log.d(TAG, "HTTP response body: ${bytes.size} bytes")
            }
            return HttpInterface.HttpResponse(responseCode, bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun getSocketFactory(): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustManagers, SecureRandom())
        return sslContext.socketFactory
    }

    private fun URL.openConnection(proxy: Uri): URLConnection {
        if (proxy.scheme == null || proxy.host == null || proxy.port == -1) return openConnection()
        val type = when (proxy.scheme) {
            "http", "https" -> Proxy.Type.HTTP
            "socks", "socks5" -> Proxy.Type.SOCKS
            "direct" -> return openConnection(Proxy.NO_PROXY)
            else -> return openConnection() // fallback to system proxy
        }
        val proxy = Proxy(type, /* sa = */ InetSocketAddress(/* hostname = */ proxy.host, proxy.port))
        return openConnection(proxy)
    }

    override fun usePublicKeyIds(pkids: Array<String>) {
        val trustManagerFactory = TrustManagerFactory.getInstance("PKIX").apply {
            init(keyIdToKeystore(pkids))
        }
        trustManagers = trustManagerFactory.trustManagers
    }
}

private fun InputStream.readLimited(maxBytes: Int, deadlineNanos: Long): ByteArray {
    val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos) {
            throw InterruptedIOException("HTTP request was cancelled or exceeded its deadline")
        }
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (total > maxBytes - count) {
            throw IOException("HTTP response body exceeds the ${maxBytes / (1024 * 1024)} MiB limit")
        }
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}
