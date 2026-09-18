package app.hyperlpa.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** RFC 5869 HKDF-SHA256 and AES-256-GCM with independently derived directional keys. */
internal object DeviceCrypto {
    private val random = SecureRandom()
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    fun secret(): String = encode(ByteArray(32).also(random::nextBytes))
    fun fingerprint(value: String): String = encode(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    fun hkdf(input: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(input)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac.doFinal(info + byteArrayOf(1))
    }

    private fun key(secret: String, e: DeviceEnvelope): ByteArray {
        val raw = decode(secret)
        require(raw.size == 32)
        return hkdf(raw, "HyperLPA/pair/v1/${e.pair}".toByteArray(Charsets.UTF_8),
            "${e.from}/${e.to}".toByteArray(Charsets.UTF_8))
    }

    private fun aad(e: DeviceEnvelope): ByteArray =
        "${e.v}\n${e.id}\n${e.from}\n${e.to}\n${e.pair}\n${e.issued}\n${e.expires}".toByteArray(Charsets.UTF_8)

    fun encrypt(secret: String, from: String, to: String, pair: String, plaintext: String, now: Long = System.currentTimeMillis()): DeviceEnvelope {
        require(plaintext.toByteArray(Charsets.UTF_8).size < 1_000_000)
        val nonce = ByteArray(12).also(random::nextBytes)
        val e = DeviceEnvelope(id = newDeviceId(), from = from, to = to, pair = pair,
            issued = now, expires = now + DeviceMessageLifetime, nonce = encode(nonce), body = "")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(secret, e), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(e))
        return e.copy(body = encode(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))))
    }

    fun decrypt(secret: String, e: DeviceEnvelope, recipient: String, now: Long = System.currentTimeMillis()): String {
        require(e.v == 1 && e.to == recipient && e.from != recipient &&
            listOf(e.id, e.from, e.to, e.pair).all { it.matches(Regex("[a-f0-9-]{36}")) })
        require(e.expires > now && e.expires > e.issued && e.expires <= e.issued + DeviceMessageLifetime && e.issued <= now + 30_000)
        require(e.body.length <= DeviceFrameLimit - 1024)
        val nonce = decode(e.nonce)
        require(nonce.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(secret, e), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(e))
        return String(cipher.doFinal(decode(e.body)), Charsets.UTF_8)
    }

    fun invitationCode(invitation: PairInvitation): String =
        "hyperlpa-pair:" + encode(DeviceJson.encodeToString(PairInvitation.serializer(), invitation).toByteArray(Charsets.UTF_8))

    fun parseInvitation(code: String, now: Long = System.currentTimeMillis()): PairInvitation {
        require(code.startsWith("hyperlpa-pair:") && code.length <= 4096) { "Invalid pairing code" }
        val invitation = DeviceJson.decodeFromString(PairInvitation.serializer(), String(decode(code.substringAfter(':')), Charsets.UTF_8))
        require(invitation.version == 1 && invitation.expires > now && invitation.expires <= now + 600_000 &&
            invitation.name.length in 1..80 && decode(invitation.secret).size == 32 &&
            listOf(invitation.device, invitation.pair).all { it.matches(Regex("[a-f0-9-]{36}")) }) { "This pairing code is invalid or expired" }
        relayAddress(invitation.relay)
        return invitation
    }
}
