package app.hyperlpa.remote

import org.junit.Assert.*
import org.junit.Test

class DevicePacketsTest {
    @Test fun largeUnicodeSnapshotReassemblesOutOfOrderOnlyWhenComplete() {
        val message = DeviceMessage("result", name = "資料".repeat(250_000))
        val parts = DevicePackets.encode(message).map { requireNotNull(DeviceJson.decodeFromString(DeviceMessage.serializer(), it).part) }
        assertTrue(parts.size > 1)
        val decoder = DevicePackets()
        val now = System.currentTimeMillis()
        parts.drop(1).reversed().forEach { assertNull(decoder.accept("peer", it, now + 120_000, now)) }
        assertEquals(message, decoder.accept("peer", parts.first(), now + 120_000, now))
    }

    @Test fun assembliesAreIsolatedByPeerAndRejectConflictingParts() {
        val parts = DevicePackets.encode(DeviceMessage("result", name = "a".repeat(700_000)))
            .map { requireNotNull(DeviceJson.decodeFromString(DeviceMessage.serializer(), it).part) }
        val decoder = DevicePackets()
        val expires = System.currentTimeMillis() + 120_000
        assertNull(decoder.accept("first", parts[0], expires))
        assertNull(decoder.accept("second", parts[1], expires))
        assertTrue(runCatching { decoder.accept("first", parts[0].copy(data = DeviceCrypto.encode(byteArrayOf(1))), expires) }.isFailure)
        assertNotNull(decoder.accept("first", parts[1], expires))
    }
}
