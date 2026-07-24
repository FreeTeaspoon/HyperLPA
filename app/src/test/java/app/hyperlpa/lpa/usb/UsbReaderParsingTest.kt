package app.hyperlpa.lpa.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbReaderParsingTest {
    @Test
    fun mapsOnePointEightVoltMaskAndCommandSeparately() {
        assertEquals(4, UsbCcidDescription.Voltage.V18.mask.toInt())
        assertEquals(3, UsbCcidDescription.Voltage.V18.powerOnValue.toInt())
    }

    @Test
    fun parsesBoundedCcidDescriptor() {
        val descriptor = ByteArray(0x36)
        descriptor[0] = 0x36
        descriptor[1] = 0x21
        descriptor[5] = 0x04
        ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN).putInt(6, 1)
        val parsed = UsbCcidDescription.fromRawDescriptors(descriptor)

        assertEquals(listOf(UsbCcidDescription.Voltage.V18), parsed?.voltages)
        assertTrue(parsed?.hasT0Protocol == true)
    }

    @Test
    fun rejectsMalformedDescriptorLength() {
        assertNull(UsbCcidDescription.fromRawDescriptors(byteArrayOf(0, 0)))
        assertNull(UsbCcidDescription.fromRawDescriptors(byteArrayOf(10, 0x21, 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedAtr() {
        UsbApduInterface.ParsedAtr.parse(byteArrayOf(0x3B))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAtrWithTruncatedInterfaceGroup() {
        UsbApduInterface.ParsedAtr.parse(byteArrayOf(0x3B, 0x10))
    }

    @Test
    fun buildsPpsWithExclusiveOrChecksum() {
        assertEquals(
            listOf(0xFF, 0x10, 0x11, 0xFE),
            UsbApduInterface.buildPps(0x11).map { it.toUByte().toInt() },
        )
        val checksum = UsbApduInterface.buildPps(0x95.toByte()).fold(0) { sum, byte ->
            sum xor byte.toUByte().toInt()
        }
        assertEquals(0, checksum)
    }

    @Test
    fun skipsManualPpsWhenReaderAdvertisesAutomaticNegotiation() {
        assertTrue(UsbApduInterface.shouldExchangePps(useTpdu = true, hasAutomaticPps = false))
        assertFalse(UsbApduInterface.shouldExchangePps(useTpdu = true, hasAutomaticPps = true))
        assertFalse(UsbApduInterface.shouldExchangePps(useTpdu = false, hasAutomaticPps = false))
    }

    @Test
    fun acceptsOnlyAnExactPpsEcho() {
        val request = UsbApduInterface.buildPps(0x11)
        assertTrue(UsbApduInterface.isValidPpsResponse(request, request.copyOf()))
        assertFalse(UsbApduInterface.isValidPpsResponse(request, null))
        assertFalse(
            UsbApduInterface.isValidPpsResponse(
                request,
                request.copyOf().apply { this[lastIndex] = 0 },
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedCcidResponseLength() {
        UsbCcidTransceiver.CcidDataBlock.parseHeaderFromBytes(
            byteArrayOf(
                0x80.toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F,
                0, 0, 0, 0, 0,
            ),
        )
    }
}
