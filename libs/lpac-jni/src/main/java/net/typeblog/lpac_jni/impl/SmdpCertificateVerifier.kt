package net.typeblog.lpac_jni.impl

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Base64

/** Raised when the optional activation-code SM-DP+ identity cannot be proven. */
internal class SmdpOidVerificationException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * SGP.22 requires an activation-code SM-DP+ OID to match the registeredID in
 * CERT.DPauth.ECDSA returned by ES9+ initiateAuthentication.
 */
internal fun verifySmdpCertificateOid(
    encodedServerCertificate: String?,
    expectedOid: String,
) {
    val expected = try {
        canonicalizeObjectIdentifier(expectedOid)
    } catch (error: IllegalArgumentException) {
        throw SmdpOidVerificationException("The activation code contains an invalid SM-DP+ OID", error)
    }
    val certificate = try {
        val encodedCertificate = requireNotNull(encodedServerCertificate)
        require(encodedCertificate.isNotBlank() && encodedCertificate.length <= MaximumEncodedCertificateCharacters)
        val der = Base64.getMimeDecoder().decode(encodedCertificate)
        require(der.size in 1..MaximumCertificateBytes)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    } catch (error: Throwable) {
        throw SmdpOidVerificationException(
            "The SM-DP+ authentication certificate OID could not be verified",
            error,
        )
    }

    val registeredIds = try {
        certificate.subjectAlternativeNames
            .orEmpty()
            .asSequence()
            .mapNotNull { name ->
                if ((name.getOrNull(0) as? Int) != RegisteredIdGeneralName) return@mapNotNull null
                (name.getOrNull(1) as? String)?.let { oid ->
                    try {
                        canonicalizeObjectIdentifier(oid)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
            .toList()
    } catch (error: CertificateParsingException) {
        throw SmdpOidVerificationException(
            "The SM-DP+ authentication certificate subject alternative name is invalid",
            error,
        )
    }
    if (registeredIds.size != 1 || registeredIds.single() != expected) {
        throw SmdpOidVerificationException(
            "The SM-DP+ authentication certificate does not match the activation code",
        )
    }
}

internal fun canonicalizeObjectIdentifier(value: String): String {
    val normalized = value.trim()
    require(normalized.length in 3..MaximumOidCharacters)
    require(normalized.all { character -> character == '.' || character in '0'..'9' })
    val arcs = normalized.split('.')
    require(arcs.size >= 2 && arcs.none(String::isEmpty))
    val values = arcs.map { arc ->
        require(arc.length <= MaximumOidArcCharacters)
        BigInteger(arc).also { require(it.signum() >= 0) }
    }
    val two = BigInteger.valueOf(2)
    require(values.first() <= two)
    if (values.first() < two) require(values[1] <= BigInteger.valueOf(39))
    return values.joinToString(".")
}

private const val RegisteredIdGeneralName = 8
private const val MaximumOidCharacters = 256
private const val MaximumOidArcCharacters = 78
private const val MaximumEncodedCertificateCharacters = 96 * 1024
private const val MaximumCertificateBytes = 64 * 1024
