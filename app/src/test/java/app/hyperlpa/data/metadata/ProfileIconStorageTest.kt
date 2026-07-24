package app.hyperlpa.data.metadata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileIconStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun processDeathReconciliationSelectsOnlyUnreferencedOwnedFiles() {
        val liveDirectory = temporaryFolder.newFolder("profile-icons")
        val referenced = File(liveDirectory, "referenced.img").apply { writeText("keep") }
        val orphan = File(liveDirectory, "promoted-before-commit.img").apply { writeText("remove") }
        val outside = temporaryFolder.newFile("outside.img").apply { writeText("outside") }
        val nestedDirectory = File(liveDirectory, "unexpected-directory").apply { mkdirs() }
        File(nestedDirectory, "nested.img").writeText("leave unexpected structures alone")

        val selected = unreferencedOwnedIconFiles(
            liveDirectory = liveDirectory,
            referencedUris = listOf(
                referenced.toURI().toString(),
                outside.toURI().toString(),
                "content://untrusted/icon",
                "not a uri",
            ),
        )

        assertEquals(setOf(orphan.canonicalFile), selected)
    }

    @Test
    fun aliasesOfAReferencedOwnedFileAreRetained() {
        val liveDirectory = temporaryFolder.newFolder("profile-icons-alias")
        val referenced = File(liveDirectory, "referenced.img").apply { writeText("keep") }
        val aliasedUri = File(liveDirectory, "subdirectory/../referenced.img").toURI().toString()

        assertEquals(
            emptySet<File>(),
            unreferencedOwnedIconFiles(liveDirectory, listOf(aliasedUri)),
        )
    }
}
