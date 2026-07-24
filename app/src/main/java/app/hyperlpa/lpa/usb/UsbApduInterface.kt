package app.hyperlpa.lpa.usb

import android.util.Log
import app.hyperlpa.lpa.ApduInterfaceAtrProvider
import java.util.Locale
import net.typeblog.lpac_jni.ApduInterface

class UsbApduInterface(
    private val ccidCtx: UsbCcidContext
) : ApduInterface, ApduInterfaceAtrProvider {
    companion object {
        private const val TAG = "UsbApduInterface"
        private const val MaxApduBytes = 1024 * 1024
        private const val MaxGetResponseRounds = 256

        internal fun buildPps(pts1: Byte): ByteArray = byteArrayOf(
            0xFF.toByte(),
            0x10,
            pts1,
            (0xFF xor 0x10 xor pts1.toUByte().toInt()).toByte(),
        )

        internal fun shouldExchangePps(useTpdu: Boolean, hasAutomaticPps: Boolean): Boolean =
            useTpdu && !hasAutomaticPps

        internal fun isValidPpsResponse(request: ByteArray, response: ByteArray?): Boolean =
            response != null && response.contentEquals(request)
    }

    override val atr: ByteArray?
        get() = ccidCtx.atr

    override val valid: Boolean
        get() = ccidCtx.isConnected

    private var channels = mutableSetOf<Int>()

    private fun logVerbose(message: String) {
        if (ccidCtx.isVerboseLoggingEnabled()) Log.d(TAG, message)
    }

    // ATR parser
    // Specs: ISO/IEC 7816-3:2006 8.2 Answer-to-Reset
    // See also: https://en.wikipedia.org/wiki/Answer_to_reset
    class ParsedAtr private constructor(
        val ts: Byte?,
        val t0: Byte?,
        val ta1: Byte?,
        val tb1: Byte?,
        val tc1: Byte?,
        val td1: Byte?,
        val ta2: Byte?,
        val tb2: Byte?,
        val tc2: Byte?,
        val td2: Byte?
    ) {
        companion object {
            fun parse(atr: ByteArray): ParsedAtr {
                require(atr.size >= 2) { "ATR is shorter than TS and T0" }
                val ts = atr[0]
                val t0 = atr[1]
                val tx1 = arrayOf<Byte?>(null, null, null, null)
                val tx2 = arrayOf<Byte?>(null, null, null, null)
                var pointer = 2

                for (i in 0..3) {
                    if (t0.toInt() and (0x10 shl i) != 0) {
                        require(pointer < atr.size) { "ATR is truncated in the first interface group" }
                        tx1[i] = atr[pointer]
                        pointer++
                    }
                }

                val td1 = tx1[3] ?: 0

                for (i in 0..3) {
                    if (td1.toInt() and (0x10 shl i) != 0) {
                        require(pointer < atr.size) { "ATR is truncated in the second interface group" }
                        tx2[i] = atr[pointer]
                        pointer++
                    }
                }

                return ParsedAtr(
                    ts = ts, t0 = t0, ta1 = tx1[0], tb1 = tx1[1], tc1 = tx1[2], td1 = tx1[3],
                    ta2 = tx2[0], tb2 = tx2[1], tc2 = tx2[2], td2 = tx2[3],
                )
            }
        }
    }

    override fun connect() {
        ccidCtx.connect()

        if (ccidCtx.useTpdu) {
            // Send parameter selection
            // Specs: USB-CCID 3.2.1 TPDU level of exchange
            val parsedAtr = ParsedAtr.parse(requireNotNull(atr) { "USB reader returned no ATR" })
            val ta1 = parsedAtr.ta1 ?: 0x11.toByte()
            val pts1 = ta1 // TODO: Check that reader supports baud rate proposed by the card
            if (shouldExchangePps(ccidCtx.useTpdu, ccidCtx.hasAutomaticPps)) {
                val pps = buildPps(pts1)
                logVerbose("Configuring USB TPDU PPS (${pps.size} bytes)")
                val ppsResponse = ccidCtx.transceiver.sendXfrBlock(pps).data
                require(isValidPpsResponse(pps, ppsResponse)) {
                    "The card rejected or returned an invalid PPS response"
                }
            }

            // Send Set Parameters
            // Specs: USB-CCID 6.1.7 PC_to_RDR_SetParameters

            val param = byteArrayOf(
                pts1,
                (if (parsedAtr.ts == 0x3F.toByte()) 0x02 else 0x00),
                parsedAtr.tc1 ?: 0,
                parsedAtr.tc2 ?: 0x0A,
                0x00
            )

            logVerbose("Configuring USB TPDU parameters (${param.size} bytes)")

            ccidCtx.transceiver.sendParamBlock(param)
        }

        // Send Terminal Capabilities
        // Specs: ETSI TS 102 221 v15.0.0 - 11.1.19 TERMINAL CAPABILITY
        val terminalCapabilities = buildCmd(
            0x80.toByte(), 0xaa.toByte(), 0x00, 0x00,
            "A9088100820101830107".decodeHex(),
            le = null,
        )
        transmitApduByChannel(terminalCapabilities, 0)
    }

    override fun disconnect() {
        synchronized(channels) {
            channels.toList().forEach { channel -> runCatching { logicalChannelClose(channel) } }
            channels.clear()
        }
        ccidCtx.disconnect()
    }

    override fun logicalChannelOpen(aid: ByteArray): Int {
        require(aid.size in 5..16) { "USB AID must be 5 to 16 bytes" }
        // OPEN LOGICAL CHANNEL
        val req = manageChannelCmd(true, 0)

        val resp = try {
            transmitApduByChannel(req, 0)
        } catch (e: Exception) {
            return -1
        }

        if (!isSuccessResponse(resp)) {
            logVerbose("OPEN LOGICAL CHANNEL failed (${resp.size} bytes, status=${responseStatus(resp)})")
            return -1
        }

        require(resp.size >= 3) { "OPEN LOGICAL CHANNEL returned no channel number" }
        val channelId = resp[0].toUByte().toInt()
        require(channelId in 1..19) { "OPEN LOGICAL CHANNEL returned invalid channel $channelId" }
        logVerbose("channelId = $channelId")
        synchronized(channels) { channels.add(channelId) }

        // Then, select AID
        val selectAid = selectByDfCmd(aid, channelId.toByte())
        val selectAidResp = transmitApduByChannel(selectAid, channelId.toByte())

        if (!isSuccessResponse(selectAidResp)) {
            logVerbose("Select DF failed (${selectAidResp.size} bytes, status=${responseStatus(selectAidResp)})")
            logicalChannelClose(channelId)
            logVerbose("Closed logical channel $channelId due to select DF failure")
            return -1
        }

        return channelId
    }

    override fun logicalChannelClose(handle: Int) {
        check(synchronized(channels) { channels.contains(handle) }) {
            "Invalid logical channel handle $handle"
        }
        // CLOSE LOGICAL CHANNEL
        val req = manageChannelCmd(false, handle.toByte())
        val resp = transmitApduByChannel(req, handle.toByte())

        if (!isSuccessResponse(resp)) {
            logVerbose("CLOSE LOGICAL CHANNEL failed (${resp.size} bytes, status=${responseStatus(resp)})")
        }
        synchronized(channels) { channels.remove(handle) }
    }

    override fun transmit(handle: Int, tx: ByteArray): ByteArray {
        check(synchronized(channels) { channels.contains(handle) }) {
            "Invalid logical channel handle $handle"
        }
        return transmitApduByChannel(tx, handle.toByte())
    }

    private fun isSuccessResponse(resp: ByteArray): Boolean =
        resp.size >= 2 && resp[resp.size - 2] == 0x90.toByte() && resp[resp.size - 1] == 0x00.toByte()

    private fun responseStatus(resp: ByteArray): String = if (resp.size >= 2) {
        String.format(
            Locale.ROOT,
            "%02X%02X",
            resp[resp.size - 2].toUByte().toInt(),
            resp[resp.size - 1].toUByte().toInt(),
        )
    } else {
        "unavailable"
    }

    private fun buildCmd(cla: Byte, ins: Byte, p1: Byte, p2: Byte, data: ByteArray?, le: Byte?): ByteArray {
        require(data == null || data.size <= 0xFF) { "Short APDU data exceeds 255 bytes" }
        return byteArrayOf(cla, ins, p1, p2).let {
            if (data != null) {
                it + data.size.toByte() + data
            } else {
                it
            }
        }.let {
            if (le != null) {
                it + byteArrayOf(le)
            } else {
                it
            }
        }
    }

    private fun manageChannelCmd(open: Boolean, channel: Byte) =
        if (open) {
            buildCmd(0x00, 0x70, 0x00, 0x00, null, 0x01)
        } else {
            buildCmd(channel, 0x70, 0x80.toByte(), channel, null, null)
        }

    private fun selectByDfCmd(aid: ByteArray, channel: Byte) =
        buildCmd(channel, 0xA4.toByte(), 0x04, 0x00, aid, null)

    private fun transmitApduByChannel(tx: ByteArray, channel: Byte): ByteArray {
        require(tx.isNotEmpty() && tx.size <= MaxApduBytes) { "Invalid APDU length ${tx.size}" }
        val realTx = tx.copyOf()
        realTx[0] = mapCla(realTx[0].toUByte().toInt(), channel.toUByte().toInt()).toByte()

        var resp = requireNotNull(ccidCtx.transceiver.sendXfrBlock(realTx).data) {
            "USB reader returned no APDU response"
        }

        if (resp.size < 2) throw RuntimeException("APDU response smaller than 2 (sw1 + sw2)!")

        var sw1 = resp[resp.size - 2].toInt() and 0xFF
        var sw2 = resp[resp.size - 1].toInt() and 0xFF

        if (sw1 == 0x6C) {
            // 0x6C = wrong le
            // so we fix the le field here
            require(realTx.size >= 5) { "Card requested a corrected Le for an APDU without Le" }
            realTx[realTx.size - 1] = resp[resp.size - 1]
            resp = requireNotNull(ccidCtx.transceiver.sendXfrBlock(realTx).data) {
                "USB reader returned no corrected APDU response"
            }
            require(resp.size >= 2) { "Corrected APDU response is truncated" }
            require(resp.size <= MaxApduBytes) { "APDU response exceeds the safety limit" }
        } else if (sw1 == 0x61) {
            // 0x61 = X bytes available
            // continue reading by GET RESPONSE
            var rounds = 0
            do {
                check(++rounds <= MaxGetResponseRounds) { "GET RESPONSE retry limit reached" }
                // GET RESPONSE
                val getResponseCmd = byteArrayOf(
                    realTx[0], 0xC0.toByte(), 0x00, 0x00, sw2.toByte()
                )

                val tmp = requireNotNull(ccidCtx.transceiver.sendXfrBlock(getResponseCmd).data) {
                    "USB reader returned no continued APDU response"
                }
                require(tmp.size >= 2) { "Continued APDU response is truncated" }

                resp = resp.sliceArray(0 until (resp.size - 2)) + tmp
                require(resp.size <= MaxApduBytes) { "APDU response exceeds the safety limit" }

                sw1 = resp[resp.size - 2].toInt() and 0xFF
                sw2 = resp[resp.size - 1].toInt() and 0xFF
            } while (sw1 == 0x61)
        }

        return resp
    }

    private fun mapCla(cla: Int, channel: Int): Int = when (channel) {
        in 0..3 -> (cla and 0xFC) or channel
        in 4..19 -> (cla and 0xF0) or 0x40 or (channel - 4)
        else -> throw IllegalArgumentException("Unsupported logical channel $channel")
    }
}
