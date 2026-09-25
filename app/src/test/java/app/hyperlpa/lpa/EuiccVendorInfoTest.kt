package app.hyperlpa.lpa

import net.typeblog.lpac_jni.ApduInterface
import net.typeblog.lpac_jni.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EuiccVendorInfoTest {
    @Test
    fun namesNineEsimModelFromFirmwareRelease() {
        assertEquals(
            "9eSIM v3.2 (beta 1)",
            nineEsimProductName("89044045216727494800000011661093", Version(37, 4, 3)),
        )
        assertEquals(
            "9eSIM v3 (final)",
            nineEsimProductName("89044045846727494800000000000000", Version(36, 18, 5)),
        )
        assertEquals(
            "9eSIM v2s",
            nineEsimProductName("89044045216727494800000000000000", Version(36, 17, 38)),
        )
    }

    @Test
    fun namesUnknownNineEsimFirmwareWithoutModel() {
        assertEquals(
            "9eSIM",
            nineEsimProductName("89044045216727494800000000000000", Version(36, 7, 1)),
        )
    }

    @Test
    fun ignoresOtherVendorsAndMissingFirmware() {
        assertNull(nineEsimProductName("89049032000000000000000000000000", Version(37, 4, 3)))
        assertNull(nineEsimProductName("89044045216727494800000011661093", null))
    }

    @Test
    fun decodesEstkmeProductNameOnlyFromSuccessfulResponses() {
        assertEquals("eSTK.me RED", decodeEstkmeProductName("eSTK.me RED\u0000".toByteArray() + ok))
        assertNull(decodeEstkmeProductName("eSTK.me".toByteArray() + byteArrayOf(0x6A, 0x82.toByte())))
        assertNull(decodeEstkmeProductName(ok))
        assertNull(decodeEstkmeProductName(byteArrayOf(0x00)))
    }

    @Test
    fun readsEstkmeProductNameAndClosesChannel() {
        val apdu = FakeApdu(openResult = { 3 }, response = { "eSTK.me".toByteArray() + ok })

        assertEquals("eSTK.me", readEstkmeProductName(apdu))
        assertEquals(listOf("A06573746B6D65FFFFFFFFFFFF6D6774"), apdu.opened)
        assertEquals(listOf("0000030000"), apdu.transmitted)
        assertEquals(listOf(3), apdu.closed)
    }

    @Test
    fun closesChannelWhenEstkmeQueryFails() {
        val apdu = FakeApdu(openResult = { 3 }, response = { error("transport lost") })

        assertNull(readEstkmeProductName(apdu))
        assertEquals(listOf(3), apdu.closed)
    }

    @Test
    fun treatsMissingEstkmeAppletAsUnknownProduct() {
        val rejected = FakeApdu(openResult = { -1 }, response = { error("Unexpected transmit") })
        val missing = FakeApdu(openResult = { error("No such applet") }, response = { error("Unexpected transmit") })

        assertNull(readEstkmeProductName(rejected))
        assertNull(readEstkmeProductName(missing))
        assertEquals(emptyList<Int>(), rejected.closed)
        assertEquals(emptyList<Int>(), missing.closed)
    }

    private val ok = byteArrayOf(0x90.toByte(), 0x00)

    private class FakeApdu(
        private val openResult: () -> Int,
        private val response: () -> ByteArray,
    ) : ApduInterface {
        val opened = mutableListOf<String>()
        val transmitted = mutableListOf<String>()
        val closed = mutableListOf<Int>()

        override val valid = true

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun logicalChannelOpen(aid: ByteArray): Int {
            opened += aid.toHexString()
            return openResult()
        }

        override fun logicalChannelClose(handle: Int) {
            closed += handle
        }

        override fun transmit(handle: Int, tx: ByteArray): ByteArray {
            transmitted += tx.toHexString()
            return response()
        }
    }
}
