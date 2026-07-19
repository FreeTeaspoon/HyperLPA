package app.hyperlpa.ui.components

import app.hyperlpa.data.settings.RedactionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionTest {
    @Test
    fun middleRedactionKeepsBothEnds() {
        val redacted = redactIdentifier("8901234567890123456", RedactionMode.MIDDLE)

        assertTrue(redacted.startsWith("8901"))
        assertTrue(redacted.endsWith("3456"))
        assertEquals(4, redacted.count { it == '•' })
    }

    @Test
    fun noRedactionShowsIdentifier() {
        val value = "8901234567890123456"
        assertEquals(value, redactIdentifier(value, RedactionMode.NONE))
    }

    @Test
    fun fullRedactionNeverLeaksDigits() {
        val redacted = redactIdentifier("8901234567890123456", RedactionMode.FULL)
        assertEquals(8, redacted.length)
        assertTrue(redacted.all { it == '•' })
    }
}
