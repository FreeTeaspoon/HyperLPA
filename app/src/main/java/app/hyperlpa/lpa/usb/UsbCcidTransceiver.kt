package app.hyperlpa.lpa.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder


/**
 * Provides raw, APDU-agnostic transmission to the CCID reader
 * Adapted from <https://github.com/open-keychain/open-keychain/blob/master/OpenKeychain/src/main/java/org/sufficientlysecure/keychain/securitytoken/usb/CcidTransceiver.java>
 */
@Suppress("unused")
class UsbCcidTransceiver(
    private val usbConnection: UsbDeviceConnection,
    private val usbBulkIn: UsbEndpoint,
    private val usbBulkOut: UsbEndpoint,
    private val usbCcidDescription: UsbCcidDescription,
    private val verboseLoggingFlow: Flow<Boolean>
) {
    companion object {
        private const val TAG = "UsbCcidTransceiver"

        private const val CCID_HEADER_LENGTH = 10

        private const val MESSAGE_TYPE_RDR_TO_PC_DATA_BLOCK = 0x80
        private const val MESSAGE_TYPE_PC_TO_RDR_ICC_POWER_ON = 0x62
        private const val MESSAGE_TYPE_PC_TO_RDR_ICC_POWER_OFF = 0x63
        private const val MESSAGE_TYPE_PC_TO_RDR_XFR_BLOCK = 0x6f

        private const val COMMAND_STATUS_SUCCESS: Byte = 0
        private const val COMMAND_STATUS_TIME_EXTENSION_RQUESTED: Byte = 2

        /**
         * Level Parameter: APDU is a single command.
         *
         * "the command APDU begins and ends with this command"
         * -- DWG Smart-Card USB Integrated Circuit(s) Card Devices rev 1.0
         * § 6.1.1.3
         */
        const val LEVEL_PARAM_START_SINGLE_CMD_APDU: Short = 0x0000

        /**
         * Level Parameter: First APDU in a multi-command APDU.
         *
         * "the command APDU begins with this command, and continue in the
         * next PC_to_RDR_XfrBlock"
         * -- DWG Smart-Card USB Integrated Circuit(s) Card Devices rev 1.0
         * § 6.1.1.3
         */
        const val LEVEL_PARAM_START_MULTI_CMD_APDU: Short = 0x0001

        /**
         * Level Parameter: Final APDU in a multi-command APDU.
         *
         * "this abData field continues a command APDU and ends the command APDU"
         * -- DWG Smart-Card USB Integrated Circuit(s) Card Devices rev 1.0
         * § 6.1.1.3
         */
        const val LEVEL_PARAM_END_MULTI_CMD_APDU: Short = 0x0002

        /**
         * Level Parameter: Next command in a multi-command APDU.
         *
         * "the abData field continues a command APDU and another block is to follow"
         * -- DWG Smart-Card USB Integrated Circuit(s) Card Devices rev 1.0
         * § 6.1.1.3
         */
        const val LEVEL_PARAM_CONTINUE_MULTI_CMD_APDU: Short = 0x0003

        /**
         * Level Parameter: Request the device continue sending APDU.
         *
         * "empty abData field, continuation of response APDU is expected in the next
         * RDR_to_PC_DataBlock"
         * -- DWG Smart-Card USB Integrated Circuit(s) Card Devices rev 1.0
         * § 6.1.1.3
         */
        const val LEVEL_PARAM_CONTINUE_RESPONSE: Short = 0x0010

        private const val SLOT_NUMBER = 0x00

        private const val ICC_STATUS_SUCCESS: Byte = 0

        private const val DEVICE_COMMUNICATE_TIMEOUT_MILLIS = 5000
        private const val DEVICE_SKIP_TIMEOUT_MILLIS = 100
        private const val COMMAND_TIMEOUT_MILLIS = 120_000L
        private const val MAX_TIMEOUT_EXTENSIONS = 64
        private const val MAX_CCID_PAYLOAD_BYTES = 1024 * 1024
    }

    data class UsbCcidErrorException(val msg: String, val errorResponse: CcidDataBlock) :
        Exception(msg)

    @Suppress("ArrayInDataClass")
    data class CcidDataBlock(
        val dwLength: Int,
        val bSlot: Byte,
        val bSeq: Byte,
        val bStatus: Byte,
        val bError: Byte,
        val bChainParameter: Byte,
        val data: ByteArray?
    ) {
        companion object {
            fun parseHeaderFromBytes(headerBytes: ByteArray): CcidDataBlock {
                require(headerBytes.size >= CCID_HEADER_LENGTH) { "CCID header is truncated" }
                val buf = ByteBuffer.wrap(headerBytes)
                buf.order(ByteOrder.LITTLE_ENDIAN)

                val type = buf.get()
                require(type == MESSAGE_TYPE_RDR_TO_PC_DATA_BLOCK.toByte()) { "Header has incorrect type value!" }
                val unsignedLength = buf.int.toLong() and 0xFFFF_FFFFL
                require(unsignedLength <= MAX_CCID_PAYLOAD_BYTES) {
                    "CCID payload length $unsignedLength exceeds the safety limit"
                }
                val dwLength = unsignedLength.toInt()
                val bSlot = buf.get()
                val bSeq = buf.get()
                val bStatus = buf.get()
                val bError = buf.get()
                val bChainParameter = buf.get()

                return CcidDataBlock(dwLength, bSlot, bSeq, bStatus, bError, bChainParameter, null)
            }
        }

        fun withData(d: ByteArray): CcidDataBlock {
            require(data == null) { "Cannot add data twice" }
            require(d.size == dwLength) { "CCID payload does not match the declared length" }
            return CcidDataBlock(dwLength, bSlot, bSeq, bStatus, bError, bChainParameter, d)
        }

        val iccStatus: Byte
            get() = (bStatus.toInt() and 0x03).toByte()

        val commandStatus: Byte
            get() = ((bStatus.toInt() shr 6) and 0x03).toByte()

        val isStatusTimeoutExtensionRequest: Boolean
            get() = commandStatus == COMMAND_STATUS_TIME_EXTENSION_RQUESTED

        val isStatusSuccess: Boolean
            get() = iccStatus == ICC_STATUS_SUCCESS && commandStatus == COMMAND_STATUS_SUCCESS
    }

    val hasAutomaticPps = usbCcidDescription.hasAutomaticPps

    private val inputBuffer = ByteArray(usbBulkIn.maxPacketSize.coerceAtLeast(CCID_HEADER_LENGTH))

    private var currentSequenceNumber: Byte = 0

    private inline fun logVerbose(message: () -> String) {
        if (runBlocking { verboseLoggingFlow.first() }) Log.d(TAG, message())
    }

    private fun sendRaw(data: ByteArray, offset: Int, length: Int, deadline: Long) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "Invalid USB write range" }
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) throw UsbTransportException("USB-CCID command timed out while transmitting")
        val tr1 = usbConnection.bulkTransfer(
            usbBulkOut,
            data,
            offset,
            length,
            minOf(DEVICE_COMMUNICATE_TIMEOUT_MILLIS.toLong(), remaining).coerceAtLeast(1).toInt(),
        )
        if (tr1 != length) {
            throw UsbTransportException(
                "USB error - failed to transmit data ($tr1/$length)"
            )
        }
    }

    private fun receiveParamBlock(expectedSequenceNumber: Byte, deadline: Long): ByteArray {
        var extensions = 0
        var response: ByteArray
        do {
            response = receiveMessage(0x82, expectedSequenceNumber, deadline)
            val commandStatus = (response[7].toInt() ushr 6) and 0x03
            if (commandStatus == COMMAND_STATUS_TIME_EXTENSION_RQUESTED.toInt()) {
                check(++extensions <= MAX_TIMEOUT_EXTENSIONS) { "USB-CCID parameter timeout-extension limit reached" }
            } else if (commandStatus != COMMAND_STATUS_SUCCESS.toInt() || (response[7].toInt() and 0x03) != 0) {
                throw UsbTransportException(
                    "USB-CCID parameter command failed with error ${response[8].toUByte().toInt()}",
                )
            }
        } while (((response[7].toInt() ushr 6) and 0x03) == COMMAND_STATUS_TIME_EXTENSION_RQUESTED.toInt())
        return response
    }

    private fun receiveMessage(expectedType: Int, expectedSequenceNumber: Byte, deadline: Long): ByteArray {
        logVerbose { "Receive CCID message type=$expectedType seq=$expectedSequenceNumber" }
        var readBytes = readPacket(deadline)
        if (readBytes < CCID_HEADER_LENGTH) {
            throw UsbTransportException("USB-CCID error - failed to receive a complete header")
        }
        val actualType = inputBuffer[0].toUByte().toInt()
        if (actualType != expectedType) {
            throw UsbTransportException("USB-CCID error - got type $actualType, expected $expectedType")
        }
        if (inputBuffer[5].toUByte().toInt() != SLOT_NUMBER) {
            throw UsbTransportException("USB-CCID error - response used an unexpected slot")
        }
        if (inputBuffer[6] != expectedSequenceNumber) {
            throw UsbTransportException(
                "USB-CCID error - expected sequence $expectedSequenceNumber, got ${inputBuffer[6]}",
            )
        }

        val unsignedLength = ByteBuffer.wrap(inputBuffer, 1, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xFFFF_FFFFL
        if (unsignedLength > MAX_CCID_PAYLOAD_BYTES) {
            throw UsbTransportException("USB-CCID response length $unsignedLength exceeds the safety limit")
        }
        val totalLength = CCID_HEADER_LENGTH + unsignedLength.toInt()
        if (readBytes > totalLength) {
            throw UsbTransportException("USB-CCID response contains bytes beyond its declared length")
        }
        val result = ByteArray(totalLength)
        inputBuffer.copyInto(result, endIndex = readBytes)
        var received = readBytes
        while (received < totalLength) {
            readBytes = readPacket(deadline)
            val remaining = totalLength - received
            if (readBytes > remaining) {
                throw UsbTransportException("USB-CCID response chunk exceeds its declared length")
            }
            inputBuffer.copyInto(result, destinationOffset = received, endIndex = readBytes)
            received += readBytes
        }
        return result
    }

    private fun readPacket(deadline: Long): Int {
        var attempts = 0
        while (attempts < 4) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw UsbTransportException("USB-CCID response timed out")
            val readBytes = usbConnection.bulkTransfer(
                usbBulkIn,
                inputBuffer,
                inputBuffer.size,
                minOf(DEVICE_COMMUNICATE_TIMEOUT_MILLIS.toLong(), remaining).coerceAtLeast(1).toInt(),
            )
            logVerbose { "USB receive result: $readBytes bytes" }
            if (readBytes > 0) return readBytes
            attempts++
        }
        throw UsbTransportException("USB-CCID reader returned no response data")
    }

    private fun receiveDataBlock(expectedSequenceNumber: Byte, deadline: Long): CcidDataBlock {
        var extensions = 0
        var response: CcidDataBlock
        do {
            response = receiveDataBlockImmediate(expectedSequenceNumber, deadline)
            if (response.isStatusTimeoutExtensionRequest) {
                check(++extensions <= MAX_TIMEOUT_EXTENSIONS) { "USB-CCID timeout-extension limit reached" }
            }
        } while (response.isStatusTimeoutExtensionRequest)
        if (!response.isStatusSuccess) {
            throw UsbCcidErrorException("USB-CCID error!", response)
        }
        return response
    }

    private fun receiveDataBlockImmediate(expectedSequenceNumber: Byte, deadline: Long): CcidDataBlock {
        val message = receiveMessage(MESSAGE_TYPE_RDR_TO_PC_DATA_BLOCK, expectedSequenceNumber, deadline)
        val header = CcidDataBlock.parseHeaderFromBytes(message)
        val data = message.copyOfRange(CCID_HEADER_LENGTH, message.size)
        return header.withData(data)
    }


    private fun skipAvailableInput() {
        var ignoredBytes: Int
        var packets = 0
        do {
            ignoredBytes = usbConnection.bulkTransfer(
                usbBulkIn, inputBuffer, inputBuffer.size, DEVICE_SKIP_TIMEOUT_MILLIS
            )
            if (ignoredBytes > 0) {
                logVerbose { "Skipped $ignoredBytes stale USB bytes" }
            }
        } while (ignoredBytes > 0 && ++packets < 64)
        if (ignoredBytes > 0) {
            throw UsbTransportException("USB reader continuously streamed stale input")
        }
    }

    /**
     * Receives a continued XfrBlock. Should be called when a multiblock response is indicated
     * 6.1.4 PC_to_RDR_XfrBlock
     */
    fun receiveContinuedResponse(): CcidDataBlock {
        return sendXfrBlock(ByteArray(0), LEVEL_PARAM_CONTINUE_RESPONSE)
    }

    /**
     * Transmits XfrBlock
     * 6.1.4 PC_to_RDR_XfrBlock
     *
     * @param payload payload to transmit
     * @param levelParam Level parameter
     */
    fun sendXfrBlock(
        payload: ByteArray,
        levelParam: Short = LEVEL_PARAM_START_SINGLE_CMD_APDU
    ): CcidDataBlock {
        require(payload.size <= MAX_CCID_PAYLOAD_BYTES) { "USB APDU exceeds the safety limit" }
        val startTime = SystemClock.elapsedRealtime()
        val deadline = startTime + COMMAND_TIMEOUT_MILLIS
        val l = payload.size
        val sequenceNumber: Byte = currentSequenceNumber++
        val headerData = byteArrayOf(
            MESSAGE_TYPE_PC_TO_RDR_XFR_BLOCK.toByte(),
            l.toByte(),
            (l shr 8).toByte(),
            (l shr 16).toByte(),
            (l shr 24).toByte(),
            SLOT_NUMBER.toByte(),
            sequenceNumber,
            0x00.toByte(),
            (levelParam.toInt() and 0x00ff).toByte(),
            (levelParam.toInt() shr 8).toByte()
        )
        val data: ByteArray = headerData + payload
        var sentBytes = 0
        while (sentBytes < data.size) {
            val bytesToSend = usbBulkOut.maxPacketSize.coerceAtLeast(1).coerceAtMost(data.size - sentBytes)
            sendRaw(data, sentBytes, bytesToSend, deadline)
            sentBytes += bytesToSend
        }
        val ccidDataBlock = receiveDataBlock(sequenceNumber, deadline)
        val elapsedTime = SystemClock.elapsedRealtime() - startTime
        logVerbose { "USB XferBlock call took ${elapsedTime}ms" }
        return ccidDataBlock
    }

    fun sendParamBlock(
        payload: ByteArray
    ): ByteArray {
        require(payload.size <= MAX_CCID_PAYLOAD_BYTES) { "USB parameter block exceeds the safety limit" }
        val startTime = SystemClock.elapsedRealtime()
        val deadline = startTime + COMMAND_TIMEOUT_MILLIS
        val l = payload.size
        val sequenceNumber: Byte = currentSequenceNumber++
        val headerData = byteArrayOf(
            0x61.toByte(),
            l.toByte(),
            (l shr 8).toByte(),
            (l shr 16).toByte(),
            (l shr 24).toByte(),
            SLOT_NUMBER.toByte(),
            sequenceNumber,
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte()
        )
        val data: ByteArray = headerData + payload
        logVerbose { "Sending USB parameter block (${data.size} bytes)" }
        var sentBytes = 0
        while (sentBytes < data.size) {
            val bytesToSend = usbBulkOut.maxPacketSize.coerceAtLeast(1).coerceAtMost(data.size - sentBytes)
            sendRaw(data, sentBytes, bytesToSend, deadline)
            sentBytes += bytesToSend
        }
        val ccidDataBlock = receiveParamBlock(sequenceNumber, deadline)
        val elapsedTime = SystemClock.elapsedRealtime() - startTime
        logVerbose { "USB ParamBlock call took ${elapsedTime}ms" }
        return ccidDataBlock
    }

    fun iccPowerOn(): CcidDataBlock {
        val startTime = SystemClock.elapsedRealtime()
        skipAvailableInput()
        var response: CcidDataBlock? = null
        for (voltage in usbCcidDescription.voltages) {
            logVerbose { "CCID: attempting to power on with voltage $voltage" }
            response = try {
                iccPowerOnVoltage(voltage.powerOnValue)
            } catch (e: UsbCcidErrorException) {
                if (e.errorResponse.bError.toInt() == 7) { // Power select error
                    logVerbose { "CCID: failed to power on with voltage $voltage" }
                    iccPowerOff()
                    logVerbose { "CCID: powered off" }
                    continue
                }
                throw e
            }
            break
        }
        if (response == null) {
            throw UsbTransportException("Couldn't power up ICC2")
        }
        val elapsedTime = SystemClock.elapsedRealtime() - startTime
        logVerbose {
            buildString {
                append("Usb transport connected")
                append(", took ", elapsedTime, "ms")
                append(", ATR length=", response.data?.size ?: 0, " bytes")
            }
        }
        return response
    }

    private fun iccPowerOnVoltage(voltage: Byte): CcidDataBlock {
        val deadline = SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MILLIS
        val sequenceNumber = currentSequenceNumber++
        val iccPowerCommand = byteArrayOf(
            MESSAGE_TYPE_PC_TO_RDR_ICC_POWER_ON.toByte(),
            0x00, 0x00, 0x00, 0x00,
            SLOT_NUMBER.toByte(),
            sequenceNumber,
            voltage,
            0x00, 0x00 // reserved for future use
        )
        sendRaw(iccPowerCommand, 0, iccPowerCommand.size, deadline)
        return receiveDataBlock(sequenceNumber, deadline)
    }

    private fun iccPowerOff() {
        val deadline = SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MILLIS
        val sequenceNumber = currentSequenceNumber++
        val iccPowerCommand = byteArrayOf(
            MESSAGE_TYPE_PC_TO_RDR_ICC_POWER_OFF.toByte(),
            0x00, 0x00, 0x00, 0x00,
            0x00,
            sequenceNumber,
            0x00,
            0x00,
            0x00,
        )
        sendRaw(iccPowerCommand, 0, iccPowerCommand.size, deadline)
        val response = receiveMessage(
            expectedType = 0x81,
            expectedSequenceNumber = sequenceNumber,
            deadline = deadline,
        )
        val commandStatus = (response[7].toInt() ushr 6) and 0x03
        if (commandStatus != COMMAND_STATUS_SUCCESS.toInt()) {
            throw UsbTransportException("USB-CCID power-off command failed")
        }
    }
}
