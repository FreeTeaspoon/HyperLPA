package app.hyperlpa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudIconBoundsTest {
    @Test
    fun operatorIconMapIsBoundedByBytesEntriesAndUniqueIccid() {
        val bounded = boundedOperatorIconMap(
            entries = listOf(
                "a" to ByteArray(3),
                "a" to ByteArray(1),
                "b" to ByteArray(3),
                "c" to ByteArray(3),
            ),
            maxBytes = 6,
            maxEntries = 2,
        )

        assertEquals(setOf("a", "b"), bounded.keys)
        assertEquals(6, bounded.values.sumOf { bytes -> bytes.size })
        assertTrue("a" in bounded)
        assertFalse("c" in bounded)
    }
}
