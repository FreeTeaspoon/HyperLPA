package app.hyperlpa.remote

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneNotificationStoreTest {
    @Test
    fun historySurvivesRestartExpiresAndRejectsTampering() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "phone-notification-test-${newDeviceId()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = directory
        }
        try {
            val store = PhoneNotificationStore(isolated)
            val now = System.currentTimeMillis()
            store.record("org.example.sms", "blank", "", "", now)
            assertTrue(store.list().isEmpty())
            store.record("org.example.sms", "old", "Old", "Expired", now - 8L * 24 * 60 * 60 * 1000)
            store.record("org.example.sms", "new", "New", "Private message", now)
            assertEquals(listOf("Private message"), PhoneNotificationStore(isolated).list().map { it.text })
            val file = File(directory, "phone-notifications.v1")
            assertFalse(file.readText(Charsets.ISO_8859_1).contains("Private message"))
            val id = store.list().single().id
            store.record("org.example.sms", "new", "Updated", "Private update", now)
            assertEquals(id, store.list().single().id)
            store.delete(id)
            assertTrue(store.list().isEmpty())
            store.record("org.example.sms", "new", "Again", "Private message", now)
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertTrue(runCatching { store.list() }.isFailure)
            store.clear()
            assertTrue(store.list().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
