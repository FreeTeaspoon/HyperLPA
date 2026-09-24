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
