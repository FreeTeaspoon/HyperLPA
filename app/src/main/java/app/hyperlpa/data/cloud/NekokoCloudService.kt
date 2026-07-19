package app.hyperlpa.data.cloud

import android.content.Context
import app.hyperlpa.BuildConfig
import app.hyperlpa.domain.model.ProfileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class NekokoCloudService(context: Context) {
    private val cacheRoot = File(context.applicationContext.cacheDir, "nekoko-cloud")
    private val iconResolver = OperatorIconResolver(File(cacheRoot, "operator-icons"))
    private val sizePredictor = ProfileSizePredictor(File(cacheRoot, "reference-sizes.json"))

    suspend fun loadOperatorIcon(profile: ProfileInfo): ByteArray? =
        iconResolver.resolve(profile)

    suspend fun predictProfileSize(profile: ProfileInfo, eid: String?): Long? =
        sizePredictor.predict(profile = profile, eid = eid)

    suspend fun clearOperatorIconCache() {
        iconResolver.clear()
    }
}

internal data class MobileNetworkCode(
    val mcc: String,
    val mnc: String,
)

internal fun decodeMccMnc(encoded: String?): MobileNetworkCode? {
    val hex = encoded?.trim().orEmpty()
    if (hex.length < 6 || hex.length % 2 != 0) return null
    val bytes = runCatching {
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull() ?: return null
    if (bytes.size < 3) return null

    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    val third = bytes[2].toInt() and 0xff
    val mcc1 = first and 0x0f
    val mcc2 = first ushr 4
    val mcc3 = second and 0x0f
    val mnc3 = second ushr 4
    val mnc1 = third and 0x0f
    val mnc2 = third ushr 4
    if (listOf(mcc1, mcc2, mcc3, mnc1, mnc2).any { it !in 0..9 }) return null
    if (mnc3 !in 0..9 && mnc3 != 0x0f) return null

    return MobileNetworkCode(
        mcc = "$mcc1$mcc2$mcc3",
        mnc = if (mnc3 == 0x0f) "$mnc1$mnc2" else "$mnc1$mnc2$mnc3",
    )
}

internal class OperatorIconResolver(
    private val cacheDirectory: File,
) {
    private val catalogCache = mutableMapOf<String, OperatorCatalog?>()
    private val catalogLocks = ConcurrentHashMap<String, Mutex>()
    private val iconLocks = ConcurrentHashMap<String, Mutex>()
    private val memoryIcons = ConcurrentHashMap<String, ByteArray>()
    private val cacheMutationMutex = Mutex()
    private val cacheGeneration = AtomicLong()

    suspend fun resolve(profile: ProfileInfo): ByteArray? = withContext(Dispatchers.IO) {
        val generation = cacheGeneration.get()
        val mcc = profile.mcc?.takeIf(::isNumericCode) ?: return@withContext null
        val mnc = profile.mnc?.takeIf(::isNumericCode) ?: return@withContext null
        val cacheKey = sha256(
            listOf(
                mcc,
                mnc,
                profile.gid1.orEmpty(),
                profile.gid2.orEmpty(),
                profile.name,
                profile.providerName,
            ).joinToString("|"),
        )
        memoryIcons[cacheKey]?.takeIf { generation == cacheGeneration.get() }
            ?.let { return@withContext it }

        iconLocks.getOrPut(cacheKey) { Mutex() }.withLock {
            if (generation != cacheGeneration.get()) return@withLock null
            memoryIcons[cacheKey]?.let { return@withLock it }
            val iconFile = File(File(cacheDirectory, "images"), "$cacheKey.png")
            readCached(iconFile)?.let { bytes ->
                val accepted = cacheMutationMutex.withLock {
                    if (generation != cacheGeneration.get()) {
                        false
                    } else {
                        memoryIcons[cacheKey] = bytes
                        true
                    }
                }
                return@withLock bytes.takeIf { accepted }
            }

            val reference = loadCatalog(mcc, generation)?.resolve(
                mnc = mnc,
                gid1 = profile.gid1,
                gid2 = profile.gid2,
                profileName = profile.name,
                providerName = profile.providerName,
            ) ?: return@withLock null
            if (generation != cacheGeneration.get()) return@withLock null
            val scope = encodePathSegment(reference.scope)
            val name = encodePathSegment(reference.name)
            val bytes = httpGet("$IconBaseUrl/$scope/$name.png", MaxIconBytes)
                ?: return@withLock null
            val accepted = cacheMutationMutex.withLock {
                if (generation != cacheGeneration.get()) {
                    false
                } else {
                    writeCached(iconFile, bytes)
                    memoryIcons[cacheKey] = bytes
                    true
                }
            }
            bytes.takeIf { accepted }
        }.also {
            iconLocks.remove(cacheKey)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        cacheGeneration.incrementAndGet()
        cacheMutationMutex.withLock {
            memoryIcons.clear()
            synchronized(catalogCache) { catalogCache.clear() }
            cacheDirectory.deleteRecursively()
        }
    }

    private suspend fun loadCatalog(mcc: String, generation: Long): OperatorCatalog? {
        synchronized(catalogCache) {
            if (catalogCache.containsKey(mcc)) return catalogCache[mcc]
        }
        return catalogLocks.getOrPut(mcc) { Mutex() }.withLock {
            synchronized(catalogCache) {
                if (catalogCache.containsKey(mcc)) return@withLock catalogCache[mcc]
            }

            val cacheFile = File(File(cacheDirectory, "catalog"), "$mcc.toml")
            val freshCache = cacheFile
                .takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < CatalogMaxAgeMillis }
                ?.let { file -> runCatching(file::readText).getOrNull() }
            var definitiveMissing = false
            var fetchedSource: String? = null
            val source = if (freshCache != null) {
                freshCache
            } else {
                when (val result = httpFetch("$CatalogBaseUrl/$mcc.toml", MaxCatalogBytes)) {
                    is HttpFetchResult.Success -> result.bytes.decodeToString()
                        .also { fetchedSource = it }
                    HttpFetchResult.NotFound -> {
                        definitiveMissing = true
                        null
                    }
                    HttpFetchResult.Failure ->
                        cacheFile.takeIf(File::isFile)
                            ?.let { file -> runCatching(file::readText).getOrNull() }
                }
            }
            val catalog = source?.let(OperatorCatalog::parse)
            cacheMutationMutex.withLock {
                if (generation == cacheGeneration.get() && (catalog != null || definitiveMissing)) {
                    fetchedSource?.let { writeCached(cacheFile, it.encodeToByteArray()) }
                    synchronized(catalogCache) { catalogCache[mcc] = catalog }
                }
            }
            catalog
        }.also {
            catalogLocks.remove(mcc)
        }
    }

    private fun readCached(file: File): ByteArray? =
        runCatching { file.takeIf { it.isFile && it.length() in 1..MaxIconBytes.toLong() }?.readBytes() }
            .getOrNull()

    private fun writeCached(file: File, bytes: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) {
                file.writeBytes(bytes)
                temporary.delete()
            }
        }
    }

    private companion object {
        const val BaseUrl = "https://operator-icons.pages.dev"
        const val CatalogBaseUrl = "$BaseUrl/catalog"
        const val IconBaseUrl = "$BaseUrl/icons"
        const val MaxCatalogBytes = 512 * 1024
        const val MaxIconBytes = 1024 * 1024
        const val CatalogMaxAgeMillis = 7L * 24 * 60 * 60 * 1000
    }
}

internal data class OperatorIconReference(
    val name: String,
    val scope: String,
)

internal data class OperatorCatalog(
    val operators: List<OperatorEntry>,
) {
    fun resolve(
        mnc: String,
        gid1: String?,
        gid2: String?,
        profileName: String?,
        providerName: String?,
    ): OperatorIconReference? {
        val candidates = buildSet {
            val trimmed = mnc.trim()
            if (trimmed.isNotEmpty()) {
                add(trimmed)
                if (trimmed.length < 2) add(trimmed.padStart(2, '0'))
                if (trimmed.length < 3) add(trimmed.padStart(3, '0'))
            }
        }
        return operators
            .mapNotNull { entry ->
                val score = entry.score(candidates, gid1, gid2, profileName, providerName)
                if (score < 0) null else entry to score
            }
            .maxByOrNull(Pair<OperatorEntry, Int>::second)
            ?.first
            ?.resolveReference(
                gid1 = gid1,
                gid2 = gid2,
                profileName = profileName,
                providerName = providerName,
            )
    }

    companion object {
        fun parse(source: String): OperatorCatalog {
            val operators = mutableListOf<OperatorEntry>()
            var operator: MutableOperatorEntry? = null
            var gid: MutableOperatorGid? = null

            fun finishOperator() {
                operator?.freeze()?.let(operators::add)
                operator = null
                gid = null
            }

            source.lineSequence().forEach { rawLine ->
                val line = stripTomlComment(rawLine).trim()
                when {
                    line.isEmpty() -> Unit
                    line == "[[operators]]" -> {
                        finishOperator()
                        operator = MutableOperatorEntry()
                    }
                    line == "[[operators.gids]]" -> {
                        val current = operator ?: return@forEach
                        gid = MutableOperatorGid().also(current.gids::add)
                    }
                    '=' in line -> {
                        val key = line.substringBefore('=').trim()
                        val value = line.substringAfter('=').trim()
                        val currentGid = gid
                        if (currentGid != null) {
                            when (key) {
                                "gid1" -> currentGid.gid1 = parseTomlString(value)
                                "gid2" -> currentGid.gid2 = parseTomlString(value)
                                "profile_names" -> currentGid.profileNames = parseTomlStringList(value)
                                "profile_provider_names" -> currentGid.providerNames = parseTomlStringList(value)
                                "icon" -> currentGid.icon = parseTomlString(value)
                                "icon_scope" -> currentGid.iconScope = parseTomlString(value)
                            }
                        } else {
                            when (key) {
                                "mnc" -> operator?.mnc = parseTomlString(value)
                                "operator" -> operator?.operatorName = parseTomlString(value)
                                "brand" -> operator?.brand = parseTomlString(value)
                                "icon" -> operator?.icon = parseTomlString(value)
                                "icon_scope" -> operator?.iconScope = parseTomlString(value)
                            }
                        }
                    }
                }
            }
            finishOperator()
            return OperatorCatalog(operators)
        }
    }
}

internal data class OperatorEntry(
    val mnc: String?,
    val operatorName: String?,
    val brand: String?,
    val icon: String?,
    val iconScope: String?,
    val gids: List<OperatorGid>,
) {
    fun score(
        mncCandidates: Set<String>,
        gid1: String?,
        gid2: String?,
        profileName: String?,
        providerName: String?,
    ): Int {
        val hasArtwork = (!icon.isNullOrBlank() && !iconScope.isNullOrBlank()) ||
            gids.any { !it.icon.isNullOrBlank() && !it.iconScope.isNullOrBlank() }
        if (!hasArtwork || mnc !in mncCandidates) return -1
        var score = 100
        if (namesMatch(profileName, operatorName) ||
            namesMatch(profileName, brand) ||
            namesMatch(providerName, operatorName) ||
            namesMatch(providerName, brand) ||
            gids.any { it.matchesName(profileName, providerName) }
        ) {
            score += 20
        }

        val normalizedGid1 = gid1.normalized()
        val normalizedGid2 = gid2.normalized()
        score += gids.maxOfOrNull {
            it.matchScore(normalizedGid1, normalizedGid2, profileName, providerName)
        }?.coerceAtLeast(0) ?: 0
        return score
    }

    fun resolveReference(
        gid1: String?,
        gid2: String?,
        profileName: String?,
        providerName: String?,
    ): OperatorIconReference? {
        val normalizedGid1 = gid1.normalized()
        val normalizedGid2 = gid2.normalized()
        val override = gids
            .filter { !it.icon.isNullOrBlank() || !it.iconScope.isNullOrBlank() }
            .map { gid ->
                gid to gid.matchScore(normalizedGid1, normalizedGid2, profileName, providerName)
            }
            .filter { (_, score) -> score > 0 }
            .maxByOrNull { (_, score) -> score }
            ?.first
        val selectedIcon = override?.icon ?: icon
        val selectedScope = override?.iconScope ?: iconScope
        if (selectedIcon.isNullOrBlank() || selectedScope.isNullOrBlank()) return null
        return OperatorIconReference(name = selectedIcon, scope = selectedScope)
    }
}

internal data class OperatorGid(
    val gid1: String?,
    val gid2: String?,
    val profileNames: List<String>,
    val providerNames: List<String>,
    val icon: String?,
    val iconScope: String?,
) {
    fun matchScore(
        requestedGid1: String?,
        requestedGid2: String?,
        profileName: String?,
        providerName: String?,
    ): Int {
        val expectedGid1 = gid1.normalized()
        val expectedGid2 = gid2.normalized()
        val hasExpectedGid = expectedGid1 != null || expectedGid2 != null
        val gidsMatch = hasExpectedGid &&
            ((expectedGid1 == null || expectedGid1 == requestedGid1) &&
                (expectedGid2 == null || expectedGid2 == requestedGid2))
        return when {
            gidsMatch -> 30
            matchesName(profileName, providerName) -> 20
            else -> 0
        }
    }

    fun matchesName(profileName: String?, providerName: String?): Boolean =
        profileNames.any { namesMatch(profileName, it) } ||
            providerNames.any { namesMatch(providerName, it) }
}

private class MutableOperatorEntry {
    var mnc: String? = null
    var operatorName: String? = null
    var brand: String? = null
    var icon: String? = null
    var iconScope: String? = null
    val gids = mutableListOf<MutableOperatorGid>()

    fun freeze() = OperatorEntry(
        mnc = mnc,
        operatorName = operatorName,
        brand = brand,
        icon = icon,
        iconScope = iconScope,
        gids = gids.map(MutableOperatorGid::freeze),
    )
}

private class MutableOperatorGid {
    var gid1: String? = null
    var gid2: String? = null
    var profileNames: List<String> = emptyList()
    var providerNames: List<String> = emptyList()
    var icon: String? = null
    var iconScope: String? = null

    fun freeze() = OperatorGid(gid1, gid2, profileNames, providerNames, icon, iconScope)
}

private fun String?.normalized(): String? =
    this?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

private fun namesMatch(candidate: String?, expected: String?): Boolean {
    val left = candidate.normalized() ?: return false
    val right = expected.normalized() ?: return false
    return left == right || left.contains(right) || right.contains(left)
}

private fun parseTomlString(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.length < 2 || !trimmed.startsWith('"')) return null
    val result = StringBuilder()
    var escaped = false
    for (index in 1 until trimmed.length) {
        val character = trimmed[index]
        if (escaped) {
            result.append(
                when (character) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> character
                },
            )
            escaped = false
        } else if (character == '\\') {
            escaped = true
        } else if (character == '"') {
            return result.toString()
        } else {
            result.append(character)
        }
    }
    return null
}

private fun parseTomlStringList(value: String): List<String> {
    val values = mutableListOf<String>()
    var index = 0
    while (index < value.length) {
        val start = value.indexOf('"', index)
        if (start < 0) break
        var end = start + 1
        var escaped = false
        while (end < value.length) {
            val character = value[end]
            if (!escaped && character == '"') break
            escaped = !escaped && character == '\\'
            if (character != '\\') escaped = false
            end++
        }
        if (end >= value.length) break
        parseTomlString(value.substring(start, end + 1))?.let(values::add)
        index = end + 1
    }
    return values
}

private fun stripTomlComment(line: String): String {
    var inString = false
    var escaped = false
    line.forEachIndexed { index, character ->
        when {
            escaped -> escaped = false
            character == '\\' -> escaped = true
            character == '"' -> inString = !inString
            character == '#' && !inString -> return line.substring(0, index)
        }
    }
    return line
}

private fun isNumericCode(value: String): Boolean =
    value.isNotEmpty() && value.all(Char::isDigit)

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

private fun encodePathSegment(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private sealed interface HttpFetchResult {
    data class Success(val bytes: ByteArray) : HttpFetchResult
    data object NotFound : HttpFetchResult
    data object Failure : HttpFetchResult
}

internal suspend fun httpGet(url: String, maxBytes: Int): ByteArray? =
    (httpFetch(url, maxBytes) as? HttpFetchResult.Success)?.bytes

private suspend fun httpFetch(url: String, maxBytes: Int): HttpFetchResult = coroutineScope {
    suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpURLConnection?>()
        val worker = launch(Dispatchers.IO) {
            val connection = runCatching {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("User-Agent", "HyperLPA/${BuildConfig.VERSION_NAME} (Android)")
                }
            }.getOrNull()
            activeConnection.set(connection)
            if (!isActive) {
                activeConnection.getAndSet(null)?.disconnect()
                return@launch
            }
            val result = if (connection == null) {
                HttpFetchResult.Failure
            } else {
                try {
                    when (connection.responseCode) {
                        HttpURLConnection.HTTP_NOT_FOUND -> HttpFetchResult.NotFound
                        HttpURLConnection.HTTP_OK -> {
                            if (connection.contentLengthLong > maxBytes) {
                                HttpFetchResult.Failure
                            } else {
                                connection.inputStream.use { input ->
                                    val output = ByteArrayOutputStream()
                                    val buffer = ByteArray(8 * 1024)
                                    var total = 0
                                    var exceededLimit = false
                                    while (!exceededLimit) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        total += read
                                        if (total > maxBytes) {
                                            exceededLimit = true
                                        } else {
                                            output.write(buffer, 0, read)
                                        }
                                    }
                                    if (exceededLimit) {
                                        HttpFetchResult.Failure
                                    } else {
                                        HttpFetchResult.Success(output.toByteArray())
                                    }
                                }
                            }
                        }
                        else -> HttpFetchResult.Failure
                    }
                } catch (_: Exception) {
                    HttpFetchResult.Failure
                } finally {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                }
            }
            if (continuation.isActive) continuation.resume(result)
        }
        continuation.invokeOnCancellation {
            activeConnection.getAndSet(null)?.disconnect()
            worker.cancel()
        }
    }
}
