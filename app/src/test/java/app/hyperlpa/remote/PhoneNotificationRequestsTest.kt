package app.hyperlpa.remote

import org.junit.Assert.*
import org.junit.Test

class PhoneNotificationRequestsTest {
    private val saved = listOf(PhoneNotificationEntry("saved", "org.example.sms", "Code", "1234", 42))
    private fun loaded() = PhoneNotificationRequests().apply {
        val token = requireNotNull(begin("phone", false, false))
        complete("phone", token, saved, true, "Unavailable")
    }

    @Test fun clearKeepsHistoryUntilAuthoritativeCompletionAndRejectsOverlap() {
        val requests = loaded()
        val token = requireNotNull(requests.begin("phone", true, false))
        assertEquals(saved, requests.state.value.entries)
        assertNull(requests.begin("phone", false, true))
        assertNull(requests.begin("phone", true, false))
        assertFalse(requests.complete("phone", "older", emptyList(), true, "Unavailable"))
        assertEquals(saved, requests.state.value.entries)
        assertTrue(requests.complete("phone", token, emptyList(), true, "Unavailable"))
        assertTrue(requests.state.value.entries.isEmpty())
        assertFalse(requests.state.value.clearing)
    }

    @Test fun oldTimeoutAndRepliesCannotFinishNewerRequest() {
        val requests = loaded()
        val old = requireNotNull(requests.begin("phone", false, true))
        assertNull(requests.begin("phone", false, true))
        assertNull(requests.begin("phone", true, false))
        requests.fail(old, "Timeout")
        assertEquals(saved, requests.state.value.entries)
        val current = requireNotNull(requests.begin("phone", false, true))
        requests.fail(old, "Old timeout")
        assertTrue(requests.state.value.refreshing)
        assertFalse(requests.complete("phone", old, emptyList(), true, "Unavailable"))
        assertFalse(requests.complete("different", current, emptyList(), true, "Unavailable"))
        assertTrue(requests.complete("phone", current, saved + saved, true, "Unavailable"))
        assertEquals(saved, requests.state.value.entries)
    }

    @Test fun failedClearDisconnectAndRevocationHaveExplicitResults() {
        val requests = loaded()
        val token = requireNotNull(requests.begin("phone", true, false))
        requests.fail(token, "Unknown outcome")
        assertEquals(saved, requests.state.value.entries)
        assertEquals("Unknown outcome", requests.state.value.error)
        val next = requireNotNull(requests.begin("phone", false, true))
        requests.disconnected("Disconnected")
        assertEquals(saved, requests.state.value.entries)
        assertFalse(requests.state.value.loading)
        assertFalse(requests.complete("phone", next, emptyList(), true, "Unavailable"))
        assertTrue(requests.complete("phone", "", emptyList(), false, "Unavailable"))
        assertFalse(requests.state.value.available)
        assertTrue(requests.state.value.entries.isEmpty())
        assertTrue(requests.close("phone"))
        assertFalse(requests.complete("phone", next, saved, true, "Unavailable"))
    }
    @Test fun revocationCancelsAnActiveRequestAndRejectsItsLateReply() {
        val requests = loaded()
        val token = requireNotNull(requests.begin("phone", false, true))
        assertTrue(requests.complete("phone", "", emptyList(), false, "Unavailable"))
        assertFalse(requests.state.value.loading)
        assertFalse(requests.state.value.available)
        assertTrue(requests.state.value.entries.isEmpty())
        assertFalse(requests.complete("phone", token, saved, true, "Unavailable"))
    }

}
