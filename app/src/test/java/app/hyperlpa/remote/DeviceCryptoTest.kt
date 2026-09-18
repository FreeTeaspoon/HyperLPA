package app.hyperlpa.remote

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeviceCryptoTest {
    private val from = "11111111-1111-4111-8111-111111111111"
    private val to = "22222222-2222-4222-8222-222222222222"
    private val pair = "33333333-3333-4333-8333-333333333333"
    private val now = 1_700_000_000_000L
    private val key = DeviceCrypto.encode(ByteArray(32) { it.toByte() })

    @Test fun `decrypts the shared browser interoperability vector`() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/device-protocol-vector.json")).bufferedReader().use { it.readText() }
        val json = DeviceJson.parseToJsonElement(fixture).jsonObject
        val envelope = DeviceJson.decodeFromJsonElement(DeviceEnvelope.serializer(), json.getValue("envelope"))
        assertEquals(json.getValue("plaintext").jsonPrimitive.content,
            DeviceCrypto.decrypt(json.getValue("secret").jsonPrimitive.content, envelope, envelope.to, envelope.issued))
    }

    @Test fun `ciphertext authenticates all routing and lifetime fields`() {
        val message = DeviceCrypto.encrypt(key, from, to, pair, "activation code must stay private", now)
        assertEquals("activation code must stay private", DeviceCrypto.decrypt(key, message, to, now))
        assertFalse(message.body.contains("activation"))
        val mutations = listOf(message.copy(from = pair), message.copy(pair = from), message.copy(id = to),
            message.copy(issued = now - 1), message.copy(expires = message.expires - 1),
            message.copy(body = DeviceCrypto.encode(DeviceCrypto.decode(message.body).apply { this[0] = (this[0].toInt() xor 1).toByte() })))
        mutations.forEach { changed -> assertThrows(Exception::class.java) { DeviceCrypto.decrypt(key, changed, to, now) } }
        assertThrows(Exception::class.java) { DeviceCrypto.decrypt(DeviceCrypto.secret(), message, to, now) }
        assertThrows(Exception::class.java) { DeviceCrypto.decrypt(key, message, from, now) }
    }

    @Test fun `expired messages and future dated messages are rejected`() {
        val message = DeviceCrypto.encrypt(key, from, to, pair, "test", now)
        assertThrows(Exception::class.java) { DeviceCrypto.decrypt(key, message, to, now + DeviceMessageLifetime) }
        assertThrows(Exception::class.java) { DeviceCrypto.decrypt(key, message, to, now - 30_001) }
    }

    @Test fun `directional keys and nonces differ`() {
        val a = DeviceCrypto.encrypt(key, from, to, pair, "same", now)
        val b = DeviceCrypto.encrypt(key, to, from, pair, "same", now)
        val c = DeviceCrypto.encrypt(key, from, to, pair, "same", now)
        assertNotEquals(a.body, b.body)
        assertNotEquals(a.nonce, c.nonce)
        assertEquals("same", DeviceCrypto.decrypt(key, b, from, now))
    }

    @Test fun `HKDF matches RFC 5869 test case one prefix`() {
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val derived = DeviceCrypto.hkdf(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"))
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"), derived)
    }

    @Test fun `pairing invitation validates expiry and relay origin`() {
        val invite = PairInvitation(relay = "https://relay.example", device = from, name = "Phone", pair = pair, secret = key, expires = now + 600_000)
        assertEquals(invite, DeviceCrypto.parseInvitation(DeviceCrypto.invitationCode(invite), now))
        assertThrows(Exception::class.java) { DeviceCrypto.parseInvitation(DeviceCrypto.invitationCode(invite), now + 600_001) }
        listOf("http://relay.example", "https://user:password@relay.example", "https://relay.example/path", "https://relay.example?token=secret")
            .forEach { invalid -> assertThrows(Exception::class.java) { relayAddress(invalid) } }
    }
}
