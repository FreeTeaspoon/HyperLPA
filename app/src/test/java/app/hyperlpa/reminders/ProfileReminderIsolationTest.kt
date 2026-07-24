package app.hyperlpa.reminders

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileReminderIsolationTest {
    @Test
    fun isolationIsReentrantWithinTheSameCoroutine() = runBlocking {
        val value = withTimeout(1_000) {
            withProfileReminderIsolation {
                withProfileReminderIsolation { 42 }
            }
        }

        assertEquals(42, value)
    }

    @Test
    fun independentReminderOperationsAreSerialized() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            withProfileReminderIsolation {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async(Dispatchers.Default) {
            secondStarted.complete(Unit)
            withProfileReminderIsolation { secondEntered.complete(Unit) }
        }

        secondStarted.await()
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        withTimeout(1_000) {
            first.await()
            second.await()
        }
        assertEquals(true, secondEntered.isCompleted)
    }
}
