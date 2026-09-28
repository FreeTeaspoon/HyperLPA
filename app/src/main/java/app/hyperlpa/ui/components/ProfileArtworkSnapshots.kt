package app.hyperlpa.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.hyperlpa.domain.model.ProfileInfo
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Small, disposable render snapshots. The eUICC and icon sources remain authoritative. */
internal object ProfileArtworkSnapshots {
    private const val MaxSnapshotMemoryBytes = 24 * 1024 * 1024
    private const val MaxSnapshotDiskBytes = 48L * 1024 * 1024
    private const val MaxSnapshotFiles = 512
    private const val MaxWarmupFiles = 256
    private const val WarmupReadyFiles = 32
    private const val MaxThumbnailDimension = 128
    private val filename = Regex("[a-f0-9]{64}\\.png")
    private val warmed = object : LruCache<String, Bitmap>(MaxSnapshotMemoryBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount.coerceAtLeast(1)
    }
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()

    suspend fun prewarm(context: Context) = withContext(Dispatchers.IO) {
        val files = directory(context).listFiles()
            .orEmpty()
            .filter { it.isFile && filename.matches(it.name) }
            .sortedByDescending(File::lastModified)
            .take(MaxWarmupFiles)
        // Make the most recently saved thumbnails available first. The page can then leave its
        // initial loading state without waiting for every reader's artwork to be decoded.
        try {
            if (files.isEmpty()) mutableReady.value = true
            files.forEachIndexed { index, file ->
                decode(file)?.let { bitmap -> warmed.put(file.name, bitmap) }
                if (index + 1 >= WarmupReadyFiles) mutableReady.value = true
            }
        } finally {
            mutableReady.value = true
        }
    }

    internal fun clearMemoryForTesting() = warmed.evictAll()

    fun get(readerId: String?, profile: ProfileInfo): Bitmap? =
        readerId?.let { warmed.get(key(it, profile)) }

    fun load(context: Context, readerId: String?, profile: ProfileInfo): Bitmap? {
        val name = readerId?.let { key(it, profile) } ?: return null
        warmed.get(name)?.let { return it }
        return decode(File(directory(context), name))?.also { warmed.put(name, it) }
    }

    fun put(context: Context, readerId: String?, profile: ProfileInfo, bitmap: Bitmap) {
        if (readerId == null) return
        val name = key(readerId, profile)
        warmed.put(name, bitmap)
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs()) return
        val destination = File(directory, name)
        val temporary = runCatching { File.createTempFile("artwork-", ".tmp", directory) }.getOrNull()
            ?: return
        try {
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            if (temporary.length() == 0L || !temporary.renameTo(destination)) return
            prune(directory)
        } finally {
            temporary.delete()
        }
    }

    private fun decode(file: File): Bitmap? {
        if (!file.isFile || file.length() !in 1..1_000_000) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..MaxThumbnailDimension ||
            bounds.outHeight !in 1..MaxThumbnailDimension
        ) return null
        return BitmapFactory.decodeFile(file.path)
    }

    private fun prune(directory: File) {
        val files = directory.listFiles().orEmpty()
            .filter { it.isFile && filename.matches(it.name) }
            .sortedByDescending(File::lastModified)
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= MaxSnapshotFiles || bytes > MaxSnapshotDiskBytes) file.delete()
        }
    }

    private fun directory(context: Context) = File(context.cacheDir, "profile-artwork-snapshots")

    private fun key(readerId: String, profile: ProfileInfo): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(readerId, profile.iccid, profile.customIconUri.orEmpty(), profile.iconBase64.orEmpty())
            .forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(byteArrayOf(
                    (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(), bytes.size.toByte(),
                ))
                digest.update(bytes)
            }
        return digest.digest().joinToString("") { "%02x".format(it) } + ".png"
    }
}
