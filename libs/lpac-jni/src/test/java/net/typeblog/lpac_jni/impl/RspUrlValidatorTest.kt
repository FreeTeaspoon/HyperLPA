package net.typeblog.lpac_jni.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RspUrlValidatorTest {
    @Test
    fun acceptsGeneratedHttpsRspEndpoint() {
        val parsed = validateRspRequestUrl(
            "https://rsp.example:8443/gsma/rsp2/es9plus/initiateAuthentication",
        )

        assertEquals("rsp.example", parsed.host)
        assertEquals(8443, parsed.port)
    }

    @Test
    fun rejectsCleartextCredentialsAndInjectedPaths() {
        listOf(
            "http://rsp.example/gsma/rsp2/es9plus/authenticateClient",
            "https://credential@rsp.example/gsma/rsp2/es9plus/authenticateClient",
            "https://rsp.example/other/gsma/rsp2/es9plus/authenticateClient",
            "https://rsp.example/gsma/rsp2/es9plus/authenticateClient?redirect=1",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                validateRspRequestUrl(value)
            }
        }
    }
}
