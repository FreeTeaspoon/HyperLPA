package app.hyperlpa

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.hyperlpa.data.history.NotificationHistoryStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationHistoryMigrationTest {
    @Test fun legacyAndDuplicateEntriesKeepDistinctIdentityAcrossReloads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "history-migration-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
        }
        try {
            val entry = """{"timestampEpochMillis":42,"action":"SEND","status":"SUCCEEDED","trigger":"MANUAL","notificationOperation":"INSTALL"}"""
            val file = File(directory, "notification-history-v1.json")
            file.writeText("""{"version":1,"entries":[$entry,$entry]}""")
            val first = NotificationHistoryStore(isolated).apply { initialize() }.history.value
            assertEquals(2, first.size)
            assertNotEquals(first[0].id, first[1].id)
            val restarted = NotificationHistoryStore(isolated).apply { initialize() }
            assertEquals(first.map { it.id }, restarted.history.value.map { it.id })
            restarted.reload()
            assertEquals(first.map { it.id }, restarted.history.value.map { it.id })
            // A malformed peer/file must not cause duplicate lazy keys.
            file.writeText(file.readText().replace(first[1].id, first[0].id))
            restarted.reload()
            val repaired = restarted.history.value
            assertNotEquals(repaired[0].id, repaired[1].id)
            assertEquals(first[0].id, repaired[0].id)
            assertEquals(repaired, NotificationHistoryStore(isolated).apply { initialize() }.history.value)
        } finally { directory.deleteRecursively() }
    }
}
