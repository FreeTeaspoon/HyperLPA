package app.hyperlpa.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationOtpTest {
    private fun code(text: String) = detectOtp(text)?.let(text::substring)

    @Test
    fun findsCodeAfterKeyword() {
        assertEquals("482913", code("Your verification code is 482913. Do not share it."))
        assertEquals("7731", code("OTP: 7731"))
    }

    @Test
    fun findsCodeBeforeKeyword() {
        assertEquals("55120", code("55120 is your login code"))
    }

    @Test
    fun preservesLeadingZeroesAndRequiresProximityOnBothSides() {
        assertEquals("001234", code("Your code: 001234"))
        assertEquals("0007", code("0007 is your PIN"))
        assertNull(code("123456 " + "unrelated message ".repeat(4) + "security notice"))
        assertNull(code("verification notice " + "unrelated message ".repeat(4) + "123456"))
        assertNull(code("PIN 123456789012"))
        assertNull(code("123456789012 is your code"))
        assertEquals("004321", code("Order 123456789012. Your verification code is 004321"))
    }

    @Test
    fun ignoresNumbersWithoutKeyword() {
        assertNull(code("Meet at 1930 near gate 12"))
        assertNull(code("Order 12345678901 has shipped"))
    }

    @Test
    fun ignoresNumbersThatAreTooLongOrShort() {
        assertNull(code("Your code is 123"))
        assertNull(code("Security alert for card 1234567890"))
    }
}
