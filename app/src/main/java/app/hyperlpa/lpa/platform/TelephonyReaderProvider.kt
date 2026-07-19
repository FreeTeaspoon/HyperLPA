package app.hyperlpa.lpa.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.IccOpenLogicalChannelResponse
import android.telephony.TelephonyManager
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.lpa.ReaderEndpoint
import app.hyperlpa.lpa.ReaderProvider
import app.hyperlpa.lpa.hexToByteArray
import app.hyperlpa.lpa.toHexString
import net.typeblog.lpac_jni.ApduInterface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal class TelephonyReaderProvider(context: Context) : ReaderProvider {
    private val appContext = context.applicationContext
    private val telephony = appContext.getSystemService(TelephonyManager::class.java)

    override suspend fun listReaders(): List<ReaderEndpoint> {
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE") != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val slots = discoverSlots()
        return slots.map { slot ->
            ReaderEndpoint(
                info = ReaderInfo(
                    id = "telephony:${slot.slotIndex}:${slot.portIndex}",
                    name = "Telephony ${if (slot.euicc) "eUICC" else "SIM"} ${slot.slotIndex + 1}",
                    kind = ReaderKind.TELEPHONY,
                    detail = buildString {
                        append("Port ${slot.portIndex}")
                        if (slot.removable) append(" · removable")
                        if (!slot.active) append(" · inactive")
                    },
                    available = slot.active,
                ),
                openApduInterface = {
                    TelephonyApduInterface(telephony, slot.slotIndex, slot.portIndex)
                },
            )
        }
    }

    private fun discoverSlots(): List<TelephonySlot> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@runCatching (0 until telephony.phoneCount).map { TelephonySlot(it, 0, false, true, true) }
        }
        val cards = telephony.uiccCardsInfo.orEmpty()
        buildList {
            cards.forEach { card ->
                val slotIndex = readInt(card, "getPhysicalSlotIndex", "getSlotIndex") ?: return@forEach
                val euicc = readBoolean(card, "isEuicc") ?: false
                val removable = readBoolean(card, "isRemovable") ?: true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val ports = runCatching { card.ports }.getOrDefault(emptyList())
                    if (ports.isEmpty()) {
                        add(TelephonySlot(slotIndex, 0, euicc, removable, true))
                    } else {
                        ports.forEach { port ->
                            val portIndex = readInt(port, "getPortIndex") ?: 0
                            val active = readBoolean(port, "isActive") ?: true
                            add(TelephonySlot(slotIndex, portIndex, euicc, removable, active))
                        }
                    }
                } else {
                    add(TelephonySlot(slotIndex, 0, euicc, removable, true))
                }
            }
        }
    }.getOrElse {
        (0 until telephony.phoneCount).map { TelephonySlot(it, 0, false, true, true) }
    }

    private fun readInt(target: Any, vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
        runCatching { target.javaClass.getMethod(name).invoke(target) as? Int }.getOrNull()
    }

    private fun readBoolean(target: Any, vararg names: String): Boolean? = names.firstNotNullOfOrNull { name ->
        runCatching { target.javaClass.getMethod(name).invoke(target) as? Boolean }.getOrNull()
    }
}

private data class TelephonySlot(
    val slotIndex: Int,
    val portIndex: Int,
    val euicc: Boolean,
    val removable: Boolean,
    val active: Boolean,
)

private class TelephonyApduInterface(
    private val telephony: TelephonyManager,
    private val slotIndex: Int,
    private val portIndex: Int,
) : ApduInterface {
    private var connected = false

    override val valid: Boolean
        get() = connected

    override fun connect() {
        connected = true
    }

    override fun disconnect() {
        for (channel in 1..19) runCatching { TelephonyHiddenApi.close(telephony, slotIndex, portIndex, channel) }
        connected = false
    }

    override fun logicalChannelOpen(aid: ByteArray): Int {
        check(connected) { "Telephony reader is not connected" }
        runCatching {
            TelephonyHiddenApi.transmitBasic(
                telephony = telephony,
                slotIndex = slotIndex,
                portIndex = portIndex,
                cla = 0x80,
                ins = 0xAA,
                p1 = 0x00,
                p2 = 0x00,
                p3 = 0x0A,
                data = "A9088100820101830107",
            )
        }
        val response = TelephonyHiddenApi.open(telephony, slotIndex, portIndex, aid.toHexString())
        check(response.status == IccOpenLogicalChannelResponse.STATUS_NO_ERROR) {
            "Telephony logical channel failed with status ${response.status}"
        }
        return response.channel
    }

    override fun logicalChannelClose(handle: Int) {
        TelephonyHiddenApi.close(telephony, slotIndex, portIndex, handle)
    }

    override fun transmit(handle: Int, tx: ByteArray): ByteArray {
        require(tx.size >= 4) { "APDU is too short" }
        val response = TelephonyHiddenApi.transmitLogical(
            telephony = telephony,
            slotIndex = slotIndex,
            portIndex = portIndex,
            channel = handle,
            cla = tx[0].toUByte().toInt(),
            ins = tx[1].toUByte().toInt(),
            p1 = tx[2].toUByte().toInt(),
            p2 = tx[3].toUByte().toInt(),
            p3 = tx.getOrNull(4)?.toUByte()?.toInt() ?: 0,
            data = if (tx.size > 5) tx.copyOfRange(5, tx.size).toHexString() else "",
        )
        return response.hexToByteArray()
    }
}

private object TelephonyHiddenApi {
    private val managerClass = TelephonyManager::class.java

    private val openBySlot by lazy {
        method("iccOpenLogicalChannelBySlot", Int::class.java, String::class.java, Int::class.java)
    }
    private val openByPort by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            method("iccOpenLogicalChannelByPort", Int::class.java, Int::class.java, String::class.java, Int::class.java)
        } else {
            method("iccOpenLogicalChannelByPort", Int::class.java, String::class.java, Int::class.java)
        }
    }
    private val closeBySlot by lazy {
        method("iccCloseLogicalChannelBySlot", Int::class.java, Int::class.java)
    }
    private val closeByPort by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            method("iccCloseLogicalChannelByPort", Int::class.java, Int::class.java, Int::class.java)
        } else {
            method("iccCloseLogicalChannelByPort", Int::class.java, Int::class.java)
        }
    }
    private val logicalBySlot by lazy {
        method(
            "iccTransmitApduLogicalChannelBySlot",
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java, Int::class.java, String::class.java,
        )
    }
    private val logicalByPort by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            method(
                "iccTransmitApduLogicalChannelByPort",
                Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                Int::class.java, Int::class.java, Int::class.java, String::class.java,
            )
        } else {
            method(
                "iccTransmitApduLogicalChannelByPort",
                Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                Int::class.java, Int::class.java, String::class.java,
            )
        }
    }
    private val basicBySlot by lazy {
        method(
            "iccTransmitApduBasicChannelBySlot",
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java, String::class.java,
        )
    }
    private val basicByPort by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            method(
                "iccTransmitApduBasicChannelByPort",
                Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                Int::class.java, Int::class.java, Int::class.java, String::class.java,
            )
        } else {
            method(
                "iccTransmitApduBasicChannelByPort",
                Int::class.java, Int::class.java, Int::class.java,
                Int::class.java, Int::class.java, String::class.java,
            )
        }
    }

    fun open(tm: TelephonyManager, slot: Int, port: Int, aid: String): IccOpenLogicalChannelResponse = invokeRoot {
        when {
            openByPort != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> openByPort!!.invoke(tm, slot, port, aid, 0)
            openByPort != null -> openByPort!!.invoke(tm, port, aid, 0)
            openBySlot != null -> openBySlot!!.invoke(tm, slot, aid, 0)
            else -> error("Telephony logical-channel API is unavailable")
        } as IccOpenLogicalChannelResponse
    }

    fun close(tm: TelephonyManager, slot: Int, port: Int, channel: Int) = invokeRoot {
        when {
            closeByPort != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> closeByPort!!.invoke(tm, slot, port, channel)
            closeByPort != null -> closeByPort!!.invoke(tm, port, channel)
            closeBySlot != null -> closeBySlot!!.invoke(tm, slot, channel)
            else -> error("Telephony logical-channel API is unavailable")
        }
        Unit
    }

    fun transmitLogical(
        telephony: TelephonyManager,
        slotIndex: Int,
        portIndex: Int,
        channel: Int,
        cla: Int,
        ins: Int,
        p1: Int,
        p2: Int,
        p3: Int,
        data: String,
    ): String = invokeRoot {
        when {
            logicalByPort != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> logicalByPort!!.invoke(
                telephony, slotIndex, portIndex, channel, cla, ins, p1, p2, p3, data,
            )
            logicalByPort != null -> logicalByPort!!.invoke(telephony, portIndex, channel, cla, ins, p1, p2, p3, data)
            logicalBySlot != null -> logicalBySlot!!.invoke(telephony, slotIndex, channel, cla, ins, p1, p2, p3, data)
            else -> error("Telephony APDU API is unavailable")
        } as String
    }

    fun transmitBasic(
        telephony: TelephonyManager,
        slotIndex: Int,
        portIndex: Int,
        cla: Int,
        ins: Int,
        p1: Int,
        p2: Int,
        p3: Int,
        data: String,
    ): String = invokeRoot {
        when {
            basicByPort != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> basicByPort!!.invoke(
                telephony, slotIndex, portIndex, cla, ins, p1, p2, p3, data,
            )
            basicByPort != null -> basicByPort!!.invoke(telephony, portIndex, cla, ins, p1, p2, p3, data)
            basicBySlot != null -> basicBySlot!!.invoke(telephony, slotIndex, cla, ins, p1, p2, p3, data)
            else -> error("Telephony basic-channel API is unavailable")
        } as String
    }

    private fun method(name: String, vararg types: Class<*>): Method? = runCatching {
        managerClass.getMethod(name, *types).apply { isAccessible = true }
    }.getOrNull()

    private inline fun <T> invokeRoot(block: () -> T): T = try {
        block()
    } catch (error: InvocationTargetException) {
        throw (error.targetException ?: error)
    }
}

