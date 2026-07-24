package app.hyperlpa.lpa

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LpaEngineTest {
    @Test
    fun decodesValidAid() {
        assertArrayEquals(
            byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x05, 0x59),
            decodeIsdrAid("a000000559"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortAid() {
        decodeIsdrAid("A0000005")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedAid() {
        decodeIsdrAid("A0000005591010FFFFFFFF890000010000")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHexAid() {
        decodeIsdrAid("A00000055Z")
    }
}
