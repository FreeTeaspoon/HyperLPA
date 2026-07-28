package net.typeblog.lpac_jni.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateParsingException

class SmdpCertificateVerifierTest {
    @Test
    fun canonicalizesObjectIdentifierArcs() {
        assertEquals("1.3.6.1.4.1.31746", canonicalizeObjectIdentifier(" 1.03.6.1.4.1.31746 "))
    }

    @Test
    fun rejectsInvalidFirstAndSecondArcs() {
        assertThrows(IllegalArgumentException::class.java) {
            canonicalizeObjectIdentifier("3.1.2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            canonicalizeObjectIdentifier("1.40.2")
        }
    }

    @Test
    fun rejectsNonNumericOrPathologicalObjectIdentifiers() {
        assertThrows(IllegalArgumentException::class.java) {
            canonicalizeObjectIdentifier("1.3.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            canonicalizeObjectIdentifier("1.3.${"9".repeat(79)}")
        }
    }

    @Test
    fun rejectsMalformedSubjectAlternativeName() {
        val error = assertThrows(SmdpOidVerificationException::class.java) {
            verifySmdpCertificateOid(MalformedSubjectAlternativeNameCertificate, "1.2.3.4")
        }

        // Some JDKs surface CertificateParsingException from getSubjectAlternativeNames();
        // others drop the malformed SAN and fail the OID match. Either path must reject.
        assertTrue(
            error.cause is CertificateParsingException ||
                error.message == "The SM-DP+ authentication certificate does not match the activation code",
        )
    }

    private companion object {
        /* A self-signed test certificate whose subjectAltName extension contains
         * a truncated registeredID GeneralName. CertificateFactory accepts the
         * certificate itself; X509Certificate.getSubjectAlternativeNames rejects
         * the malformed extension. */
        const val MalformedSubjectAlternativeNameCertificate =
            "MIICzjCCAbagAwIBAgIJAIuyMNYJFhqXMA0GCSqGSIb3DQEBCwUAMB0xGzAZBgNVBAMMEk1hbGZvcm1lZCBTQU4gdGVzdDAeFw0yNjA3MjAxNzIzNDhaFw0yNjA3MjExNzIzNDhaMB0xGzAZBgNVBAMMEk1hbGZvcm1lZCBTQU4gdGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALOvgebL05oIWWGuWZZZjmmMC2HLAAnVmqve1shIyeOFt/gezZ85dDQs2CGz/NQZRd9ZyCyPuy3HY56W8wJZscRuhhVE0JUnOoVRDXdc9Jd6iuJMzdsIhg+41gj3ShJoaC4oZU6x5dM3OtqnbGpnYuCO/92v1jCTtzsbsgVAwlKlkkEROAvVpOgQomLcwCX6zLuiGSXpEKgV3rLQh/ZpkNoZhrKJIpvwkOReOKqtU++vLWqenZb7uQGEXgFKh+UM8CleyA+y2PSLTga1+/Y40zWFJSA+u+3vl2YZRrO/QyhawSEfRBfObTVjaJ+7REX1LbTyGcZaP/uoHytswT02uOkCAwEAAaMRMA8wDQYDVR0RBAYwBIgDKgMwDQYJKoZIhvcNAQELBQADggEBAEEiDYIy0mdB4NXPW8tLRJNo7aJW4GsFrqSi+tO4qabh3vXV1QonzrD/5fUmm/2VrYLi8DA6aZ7tQVe8FKLMy/6OuK3YrdpCyJbrnPnjCzrIlvynKgMJOtHi712ZFxm5z5BCBk2iJsHadQkjCOfBNH0qlC+ZanWzf8JzQeVryK3ELJzYSRvdc7ExhTq1vIqE5xvVTyALoCP+rC8snnbIUHQ1wsCe97YxSwiHNnkO8KOzutsaNugrMU2qw8Zb7r7b+5HqbCkoWiMjDlRv+mqJPk3ixJbmm5/LwA/s3rgVioMUnIIjrJoS1zcCAk8jFv/mjUmgtAMfy/xTSr3kDIySZsY="
    }
}
