package app.hyperlpa.data.cloud

import app.hyperlpa.domain.model.ProfileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

internal class ProfileSizePredictor(
    private val cacheFile: File,
) {
    private val loadMutex = Mutex()
    private var catalog: ProfileSizeCatalog? = null

    suspend fun predict(profile: ProfileInfo, eid: String?): Long? = withContext(Dispatchers.IO) {
        loadCatalog()?.predict(
            eid = eid,
            smdpAddress = profile.smdpAddress,
            plmn = profile.mcc?.let { mcc -> profile.mnc?.let { mnc -> mcc + mnc } },
            providerName = profile.providerName,
        )
    }

    private suspend fun loadCatalog(): ProfileSizeCatalog? {
        catalog?.let { return it }
        return loadMutex.withLock {
            catalog?.let { return@withLock it }
            val freshCache = cacheFile
                .takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < DatasetMaxAgeMillis }
                ?.readText()
            val source = freshCache
                ?: httpGet(DatasetUrl, MaxDatasetBytes)?.decodeToString()
                    ?.also(::writeCache)
                ?: cacheFile.takeIf(File::isFile)?.readText()
            source?.let(ProfileSizeCatalog::parse)?.also { catalog = it }
        }
    }

    private fun writeCache(source: String) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            temporary.writeText(source)
            if (!temporary.renameTo(cacheFile)) {
                cacheFile.writeText(source)
                temporary.delete()
            }
        }
    }

    private companion object {
        const val DatasetUrl =
            "https://raw.githubusercontent.com/iebb/NekokoLPA2/master/data/reference_sizes_simple.json"
        const val MaxDatasetBytes = 2 * 1024 * 1024
        const val DatasetMaxAgeMillis = 7L * 24 * 60 * 60 * 1000
    }
}

internal data class ProfileSizeCatalog(
    val referenceEum: String?,
    val entries: List<ProfileSizeEntry>,
) {
    fun predict(
        eid: String?,
        smdpAddress: String?,
        plmn: String?,
        providerName: String?,
    ): Long? {
        val normalizedSmdp = smdpAddress.normalizedValue()
        var bestMatch: ProfileSizeEntry? = null
        var bestScore = 0

        entries.forEach { entry ->
            var score = 0
            if (normalizedSmdp != null) {
                if (!entry.rsp.equals(normalizedSmdp, ignoreCase = true)) return@forEach
                score += 1
            }
            if (plmn != null && entry.plmn == plmn) score += 2
            score += providerMatchScore(providerName, entry.providerName)
            if (normalizedSmdp == null && score == 0) return@forEach
            if (score > bestScore) {
                bestScore = score
                bestMatch = entry
            }
        }

        val match = bestMatch ?: return null
        val eumPrefix = eid?.takeIf { it.length >= 8 }?.take(8)
        return eumPrefix?.let(match.eumSizes::get)
            ?: match.referenceSize.takeIf { it > 0 }
    }

    companion object {
        fun parse(source: String): ProfileSizeCatalog? = runCatching {
            val root = Json.parseToJsonElement(source).jsonObject
            val referenceEum = root["reference_eum"]?.jsonPrimitive?.contentOrNull
            val entries = root["results"]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { element ->
                    val item = element.jsonObject
                    val rsp = item["rsp"]?.jsonPrimitive?.contentOrNull
                        ?.normalizedValue()
                        ?: return@mapNotNull null
                    val referenceSize = item["reference_size"]?.jsonPrimitive?.longOrNull
                        ?: item["reference_size"]?.jsonPrimitive?.intOrNull?.toLong()
                        ?: return@mapNotNull null
                    val eumSizes = item["eum_sizes"]
                        ?.jsonObject
                        ?.mapValues { (_, value) -> value.jsonPrimitive.longOrNull ?: 0L }
                        .orEmpty()
                        .filterValues { it > 0 }
                    ProfileSizeEntry(
                        rsp = rsp,
                        plmn = item["plmn"]?.jsonPrimitive?.contentOrNull,
                        providerName = item["serviceProviderName"]?.jsonPrimitive?.contentOrNull,
                        referenceSize = referenceSize,
                        eumSizes = eumSizes,
                    )
                }
            ProfileSizeCatalog(referenceEum = referenceEum, entries = entries)
        }.getOrNull()
    }
}

internal data class ProfileSizeEntry(
    val rsp: String,
    val plmn: String?,
    val providerName: String?,
    val referenceSize: Long,
    val eumSizes: Map<String, Long>,
)

private fun String?.normalizedValue(): String? =
    this?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

private fun providerMatchScore(candidate: String?, expected: String?): Int {
    val candidateValue = candidate.normalizedValue() ?: return 0
    val expectedValue = expected.normalizedValue() ?: return 0
    return when {
        candidateValue == expectedValue -> 3
        expectedValue.length >= 4 && candidateValue.contains(expectedValue) -> 1
        else -> 0
    }
}
