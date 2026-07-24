package app.hyperlpa.data.metadata

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * App-private storage for user-selected profile artwork.
 *
 * Imports are first written under no-backup storage. They are only moved into the live directory
 * after validation, so cancellation cannot expose a partial image. A promoted image can still be
 * left behind if the process dies before its DataStore transaction commits; startup reconciliation
 * removes any live file that is not referenced by the coherent metadata snapshot.
 */
internal class ProfileIconStorage(context: Context) {
    private val appContext = context.applicationContext
    private val liveDirectory = File(appContext.filesDir, LiveDirectoryName)
    private val stagingDirectory = File(appContext.noBackupFilesDir, StagingDirectoryName)

    fun createPendingImport(prefix: String): PendingProfileIconImport {
        check(stagingDirectory.mkdirs() || stagingDirectory.isDirectory) {
            "Could not create the profile icon staging directory"
        }
        check(liveDirectory.mkdirs() || liveDirectory.isDirectory) {
            "Could not create the profile icon directory"
        }
        val safePrefix = prefix.filter(Char::isLetterOrDigit).take(96).ifBlank { "icon" }
        val identifier = UUID.randomUUID().toString()
        return PendingProfileIconImport(
            stagingFile = File(stagingDirectory, "$safePrefix-$identifier.pending"),
            liveFile = File(liveDirectory, "$safePrefix-$identifier.img"),
        )
    }

    fun promote(pending: PendingProfileIconImport): String {
        requireOwnedChild(pending.stagingFile, stagingDirectory)
        requireOwnedChild(pending.liveFile, liveDirectory)
        require(pending.stagingFile.isFile) { "The staged profile icon is missing" }
        require(!pending.liveFile.exists()) { "The profile icon destination already exists" }
        try {
            Files.move(
                pending.stagingFile.toPath(),
                pending.liveFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pending.stagingFile.toPath(), pending.liveFile.toPath())
        }
        return pending.liveFile.toUri().toString()
    }

    fun discard(pending: PendingProfileIconImport) {
        deleteOwnedChild(pending.stagingFile, stagingDirectory)
        deleteOwnedChild(pending.liveFile, liveDirectory)
    }

    /** Always safe: a live metadata transaction never points at a staging path. */
    fun deleteStagedImports() {
        stagingDirectory.listFiles().orEmpty().forEach { file ->
            deleteOwnedChild(file, stagingDirectory)
        }
    }

    fun deleteUnreferencedLiveIcons(referencedUris: Collection<String>) {
        unreferencedOwnedIconFiles(liveDirectory, referencedUris).forEach(File::delete)
    }

    private fun requireOwnedChild(file: File, parent: File) {
        val canonicalParent = parent.canonicalFile
        require(file.canonicalFile.parentFile == canonicalParent) { "Invalid profile icon path" }
    }

    private fun deleteOwnedChild(file: File, parent: File) {
        runCatching {
            val canonicalParent = parent.canonicalFile
            val candidate = file.canonicalFile
            if (candidate.isFile && candidate.parentFile == canonicalParent) candidate.delete()
        }
    }

    private companion object {
        const val LiveDirectoryName = "profile-icons"
        const val StagingDirectoryName = "profile-icon-staging"
    }
}

internal data class PendingProfileIconImport(
    val stagingFile: File,
    val liveFile: File,
)

/** Pure file-selection logic kept separate so process-death reconciliation is unit-testable. */
internal fun unreferencedOwnedIconFiles(
    liveDirectory: File,
    referencedUris: Collection<String>,
): Set<File> {
    val canonicalDirectory = runCatching { liveDirectory.canonicalFile }.getOrNull()
        ?: return emptySet()
    val referencedPaths = referencedUris.mapNotNullTo(hashSetOf()) { uri ->
        ownedIconFile(uri, canonicalDirectory)?.path
    }
    return liveDirectory.listFiles()
        .orEmpty()
        .mapNotNullTo(linkedSetOf()) { file ->
            val candidate = runCatching { file.canonicalFile }.getOrNull()
                ?: return@mapNotNullTo null
            candidate.takeIf {
                it.isFile &&
                    it.parentFile == canonicalDirectory &&
                    it.path !in referencedPaths
            }
        }
}

private fun ownedIconFile(uri: String, canonicalDirectory: File): File? = runCatching {
    val parsed = URI(uri)
    if (parsed.scheme != "file") return null
    File(parsed).canonicalFile.takeIf { candidate ->
        candidate.parentFile == canonicalDirectory
    }
}.getOrNull()
