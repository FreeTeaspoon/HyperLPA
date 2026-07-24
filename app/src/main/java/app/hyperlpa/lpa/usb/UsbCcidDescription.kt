package app.hyperlpa.lpa.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("unused")
data class UsbCcidDescription(
    private val bMaxSlotIndex: Byte,
    private val bVoltageSupport: Byte,
    private val dwProtocols: Int,
    private val dwFeatures: Int
) {
    companion object {
        private const val DESCRIPTOR_LENGTH = 0x36
        private const val DESCRIPTOR_TYPE = 0x21

        // dwFeatures Masks
        private const val FEATURE_AUTOMATIC_VOLTAGE = 0x00008
        private const val FEATURE_AUTOMATIC_PPS = 0x00080

        private const val FEATURE_EXCHANGE_LEVEL_TPDU = 0x10000
        private const val FEATURE_EXCHANGE_LEVEL_SHORT_APDU = 0x20000
        private const val FEATURE_EXCHANGE_LEVEL_EXTENDED_APDU = 0x40000

        // bVoltageSupport Masks
        private const val VOLTAGE_5V0: Byte = 1
        private const val VOLTAGE_3V0: Byte = 2
        private const val VOLTAGE_1V8: Byte = 4

        private const val SLOT_OFFSET = 4
        private const val FEATURES_OFFSET = 40
        private const val MASK_T0_PROTO = 1
        private const val MASK_T1_PROTO = 2

        fun fromRawDescriptors(desc: ByteArray): UsbCcidDescription? {
            var offset = 0
            val buffer = ByteBuffer.wrap(desc).order(ByteOrder.LITTLE_ENDIAN)
            while (offset + 2 <= desc.size) {
                val length = desc[offset].toUByte().toInt()
                val type = desc[offset + 1].toUByte().toInt()
                if (length < 2 || offset + length > desc.size) return null
                if (type == DESCRIPTOR_TYPE && length >= DESCRIPTOR_LENGTH) {
                    return UsbCcidDescription(
                        bMaxSlotIndex = desc[offset + SLOT_OFFSET],
                        bVoltageSupport = desc[offset + SLOT_OFFSET + 1],
                        dwProtocols = buffer.getInt(offset + SLOT_OFFSET + 2),
                        dwFeatures = buffer.getInt(offset + FEATURES_OFFSET),
                    )
                }
                offset += length
            }
            return null
        }
    }

    enum class Voltage(powerOnValue: Int, mask: Int) {
        // @formatter:off
        AUTO(0, 0),
        V50(1, VOLTAGE_5V0.toInt()),
        V30(2, VOLTAGE_3V0.toInt()),
        V18(3, VOLTAGE_1V8.toInt());
        // @formatter:on

        val powerOnValue = powerOnValue.toByte()
        val mask = mask.toByte()
    }

    private fun hasFeature(feature: Int) = (dwFeatures and feature) != 0

    val voltages: List<Voltage>
        get() {
            if (hasFeature(FEATURE_AUTOMATIC_VOLTAGE)) return listOf(Voltage.AUTO)
            return Voltage.entries.filter { (it.mask.toInt() and bVoltageSupport.toInt()) != 0 }
        }

    val hasAutomaticPps: Boolean
        get() = hasFeature(FEATURE_AUTOMATIC_PPS)

    val hasT0Protocol: Boolean
        get() = (dwProtocols and MASK_T0_PROTO) != 0
}
