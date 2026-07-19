package app.hyperlpa.ui.components

import app.hyperlpa.data.settings.RedactionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionTest {
    @Test
    fun middleRedactionKeepsBothEnds() {
        val redacted = redactIdentifier("8901234567890123456", RedactionMode.MIDDLE, reveal = false)

        assertTrue(redacted.startsWith("8901"))
        assertTrue(redacted.endsWith("3456"))
        assertTrue('•' in redacted)
    }

    @Test
    fun revealOverridesRedaction() {
        val value = "8901234567890123456"
        assertEquals(value, redactIdentifier(value, RedactionMode.FULL, reveal = true))
    }

    @Test
    fun fullRedactionNeverLeaksDigits() {
        val redacted = redactIdentifier("8901234567890123456", RedactionMode.FULL, reveal = false)
        assertTrue(redacted.all { it == '•' })
    }
}

