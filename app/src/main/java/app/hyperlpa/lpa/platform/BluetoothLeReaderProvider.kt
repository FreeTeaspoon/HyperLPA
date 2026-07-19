package app.hyperlpa.lpa.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.lpa.ReaderEndpoint
import app.hyperlpa.lpa.ReaderProvider
import app.hyperlpa.lpa.hexToByteArray
import app.hyperlpa.lpa.toHexString
import kotlinx.coroutines.delay
import net.typeblog.lpac_jni.ApduInterface
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

private val BluetoothBaseSuffix = "-0000-1000-8000-00805f9b34fb"
private val RedServiceUuid = shortUuid("4553")
private val RedRxUuid = shortUuid("544b")
private val RedTxUuid = shortUuid("6d65")
private val SimLinkServiceUuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val SimLinkTxUuid = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
private val SimLinkRxUuid = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

private fun shortUuid(value: String): UUID = UUID.fromString("0000${value.lowercase()}$BluetoothBaseSuffix")

private enum class BleProtocol(val label: String) {
    RED("ESTKme RED"),
    RED_2("ESTKme RED 2"),
    SIM_LINK("SimLink"),
    BEE_SIM("BeeSIM"),
}

private data class BleReader(
    val device: BluetoothDevice,
    val name: String,
    val protocol: BleProtocol,
)

internal class BluetoothLeReaderProvider(context: Context) : ReaderProvider {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    override suspend fun listReaders(): List<ReaderEndpoint> {
        if (!hasScanPermission() || adapter?.isEnabled != true) return emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()
        val found = linkedMapOf<String, BleReader>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.toBleReader()?.let { found[it.device.address] = it }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result -> result.toBleReader()?.let { found[it.device.address] = it } }
            }
        }
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
        try {
            delay(4_000)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        return found.values.map { reader ->
            ReaderEndpoint(
                info = ReaderInfo(
                    id = "ble:${reader.protocol.name}:${reader.device.address}",
                    name = reader.name,
                    kind = ReaderKind.BLE,
                    detail = "${reader.protocol.label} · ${reader.device.address}",
                ),
                openApduInterface = {
                    LogicalChannelBleApduInterface(
                        when (reader.protocol) {
                            BleProtocol.RED -> RedBleTransport(appContext, reader.device, false)
                            BleProtocol.RED_2 -> RedBleTransport(appContext, reader.device, true)
                            BleProtocol.SIM_LINK -> SimLinkBleTransport(appContext, reader.device)
                            BleProtocol.BEE_SIM -> BeeSimBleTransport(appContext, reader.device)
                        },
                    )
                },
            )
        }
    }

    private fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toBleReader(): BleReader? {
        val displayName = scanRecord?.deviceName.orEmpty().ifBlank { runCatching { device.name }.getOrNull().orEmpty() }
        val lowerName = displayName.lowercase()
        val serviceUuids = scanRecord?.serviceUuids.orEmpty().map { it.uuid }
        val protocol = when {
            lowerName.contains("beesim") -> BleProtocol.BEE_SIM
            lowerName.contains("esim_writer") -> BleProtocol.SIM_LINK
            lowerName.contains("estkme red") -> BleProtocol.RED_2
            lowerName.contains("estkme") || RedServiceUuid in serviceUuids -> BleProtocol.RED
            else -> null
        } ?: return null
        return BleReader(device, displayName.ifBlank { protocol.label }, protocol)
    }
}

private interface BleApduTransport {
    val connected: Boolean
    fun connect()
    fun disconnect()
    fun transceive(apdu: ByteArray): ByteArray
}

private class LogicalChannelBleApduInterface(
    private val transport: BleApduTransport,
) : ApduInterface {
    private val nextHandle = AtomicInteger(1)
    private val channels = linkedMapOf<Int, Int>()

    override val valid: Boolean
        get() = transport.connected

    override fun connect() = transport.connect()

    override fun disconnect() {
        synchronized(channels) {
            channels.toMap().forEach { (handle, _) -> runCatching { logicalChannelClose(handle) } }
            channels.clear()
        }
        transport.disconnect()
    }

    override fun logicalChannelOpen(aid: ByteArray): Int = synchronized(transport) {
        check(transport.connected) { "Bluetooth reader is not connected" }
        runCatching {
            transport.transceive("80AA00000AA9088100820101830107".hexToByteArray())
        }
        val channel = runCatching {
            val response = transport.transceive("0070000001".hexToByteArray())
            checkSuccessful(response, "MANAGE CHANNEL")
            response.firstOrNull()?.toUByte()?.toInt() ?: 0
        }.getOrDefault(0)

        val select = ByteArray(6 + aid.size)
        select[0] = mapCla(0x00, channel).toByte()
        select[1] = 0xA4.toByte()
        select[2] = 0x04
        select[3] = 0x00
        select[4] = aid.size.toByte()
        aid.copyInto(select, 5)
        select[select.lastIndex] = 0x00
        val response = transport.transceive(select)
        checkSuccessful(response, "SELECT ${aid.toHexString()}")

        val handle = nextHandle.getAndIncrement()
        synchronized(channels) { channels[handle] = channel }
        handle
    }

    override fun logicalChannelClose(handle: Int) = synchronized(transport) {
        val channel = synchronized(channels) { channels.remove(handle) }
            ?: throw IllegalArgumentException("Unknown Bluetooth channel $handle")
        if (channel != 0) {
            runCatching {
                transport.transceive(byteArrayOf(0x00, 0x70, 0x80.toByte(), channel.toByte(), 0x00))
            }
        }
    }

    override fun transmit(handle: Int, tx: ByteArray): ByteArray = synchronized(transport) {
        val channel = synchronized(channels) { channels[handle] }
            ?: throw IllegalArgumentException("Unknown Bluetooth channel $handle")
        val mapped = tx.copyOf()
        mapped[0] = mapCla(mapped[0].toUByte().toInt(), channel).toByte()
        transport.transceive(mapped)
    }

    private fun checkSuccessful(response: ByteArray, action: String) {
        require(response.size >= 2) { "$action returned a truncated APDU response" }
        val sw1 = response[response.lastIndex - 1].toUByte().toInt()
        val sw2 = response.last().toUByte().toInt()
        check((sw1 == 0x90 && sw2 == 0x00) || sw1 == 0x61 || sw1 == 0x9F) {
            "$action failed with %02X%02X".format(sw1, sw2)
        }
    }

    private fun mapCla(cla: Int, channel: Int): Int = when (channel) {
        in 0..3 -> (cla and 0xFC) or channel
        in 4..19 -> (cla and 0xF0) or 0x40 or (channel - 4)
        else -> error("Unsupported logical channel $channel")
    }
}

private class RedBleTransport(
    context: Context,
    device: BluetoothDevice,
    private val ccidProtocol: Boolean,
) : BleApduTransport {
    private val gatt = BleGattClient(context, device)
    private lateinit var tx: BluetoothGattCharacteristic
    private lateinit var rx: BluetoothGattCharacteristic

    override val connected: Boolean
        get() = gatt.connected

    override fun connect() {
        gatt.connect()
        val service = gatt.requireService(RedServiceUuid)
        tx = service.requireCharacteristic(RedTxUuid)
        rx = service.requireCharacteristic(RedRxUuid)
        gatt.enableNotifications(rx)
        if (ccidProtocol) {
            runCatching {
                val response = exchangeCcid(0x6F, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x02, 0x00, 0x00))
                if (response.size >= 2 && response[0] == 0x61.toByte()) {
                    exchangeCcid(0x6F, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01, response[1]))
                }
            }
            exchangeCcid(0x62, byteArrayOf(0x01))
        } else {
            exchangeRed(0x02, "ESTKme".toByteArray(StandardCharsets.US_ASCII))
            exchangeRed(0x03, byteArrayOf(0x01, 0x01))
        }
    }

    override fun disconnect() = gatt.close()

    override fun transceive(apdu: ByteArray): ByteArray = if (ccidProtocol) {
        exchangeCcid(0x6F, apdu)
    } else {
        val response = exchangeRed(0x04, apdu)
        require(response.size >= 3) { "RED BLE returned a truncated response" }
        response.copyOfRange(3, response.size)
    }

    private fun exchangeRed(command: Int, payload: ByteArray): ByteArray {
        val request = byteArrayOf(command.toByte(), payload.size.toByte(), (payload.size ushr 8).toByte()) + payload
        gatt.clearNotifications()
        gatt.writeChunks(tx, request)
        val output = ArrayList<Byte>()
        var expected = -1
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val packet = gatt.takeNotification(deadline) ?: continue
            output.addAll(packet.toList())
            if (expected < 0 && output.size >= 3) {
                expected = 3 + output[1].toUByte().toInt() + (output[2].toUByte().toInt() shl 8)
            }
            if (expected >= 0 && output.size >= expected) return output.take(expected).toByteArray()
        }
        throw TimeoutException("RED BLE response timed out")
    }

    private fun exchangeCcid(messageType: Int, payload: ByteArray): ByteArray {
        val request = ByteBuffer.allocate(10 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(messageType.toByte())
            .putInt(payload.size)
            .put(0).put(0).put(0).put(0).put(0)
            .put(payload)
            .array()
        gatt.clearNotifications()
        gatt.writeChunks(tx, request)
        val output = ArrayList<Byte>()
        var expected = -1
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val packet = gatt.takeNotification(deadline) ?: continue
            if (packet.size >= 10 && packet[7].toInt() and 0x80 != 0 && packet.sliceArray(1..4).all { it == 0.toByte() }) {
                continue
            }
            output.addAll(packet.toList())
            if (expected < 0 && output.size >= 10) {
                val header = output.take(10).toByteArray()
                expected = 10 + ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
            if (expected >= 0 && output.size >= expected) {
                val response = output.take(expected).toByteArray()
                val length = ByteBuffer.wrap(response, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                require(response.size >= 10 + length) { "RED BLE 2 returned a truncated CCID response" }
                return response.copyOfRange(10, 10 + length)
            }
        }
        throw TimeoutException("RED BLE 2 response timed out")
    }
}

private class SimLinkBleTransport(
    context: Context,
    device: BluetoothDevice,
) : BleApduTransport {
    private val gatt = BleGattClient(context, device)
    private lateinit var tx: BluetoothGattCharacteristic
    private lateinit var rx: BluetoothGattCharacteristic

    override val connected: Boolean
        get() = gatt.connected

    override fun connect() {
        gatt.connect()
        val service = gatt.requireService(SimLinkServiceUuid)
        tx = service.requireCharacteristic(SimLinkTxUuid)
        rx = service.requireCharacteristic(SimLinkRxUuid)
        gatt.enableNotifications(rx)
        sendJson(JSONObject().put("cmd", "APDU").put("action", 2), false)
        sendJson(JSONObject().put("cmd", "APDU").put("action", 0), false)
        runCatching { transceive("80AA00000AA9088100820101830107".hexToByteArray()) }
    }

    override fun disconnect() {
        runCatching { sendJson(JSONObject().put("cmd", "APDU").put("action", 2), false) }
        gatt.close()
    }

    override fun transceive(apdu: ByteArray): ByteArray {
        var command = apdu.toHexString()
        if (apdu.size == 5 && apdu[1] == 0xC0.toByte()) {
            val le = apdu.last().toUByte().toInt()
            if (le == 0 || le > 0x4F) command = command.dropLast(2) + "4F"
        }
        return sendJson(JSONObject().put("cmd", "APDU").put("data", command).put("action", 1), true).hexToByteArray()
    }

    private fun sendJson(request: JSONObject, retryChannel: Boolean): String {
        gatt.clearNotifications()
        gatt.writeChunks(tx, request.toString().toByteArray(StandardCharsets.UTF_8))
        val text = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val packet = gatt.takeNotification(deadline) ?: continue
            text.append(String(packet, StandardCharsets.UTF_8))
            val response = runCatching { JSONObject(text.toString()) }.getOrNull() ?: continue
            if (response.has("error")) {
                val error = response.optString("error")
                if (retryChannel && error == "APDU channel not open!") {
                    sendJson(JSONObject().put("cmd", "APDU").put("action", 0), false)
                    return sendJson(request, false)
                }
                throw IllegalStateException(error.ifBlank { "SimLink APDU failed" })
            }
            return response.optString("data")
        }
        throw TimeoutException("SimLink response timed out")
    }
}

private class BeeSimBleTransport(
    context: Context,
    device: BluetoothDevice,
) : BleApduTransport {
    private val gatt = BleGattClient(context, device)
    private lateinit var tx: BluetoothGattCharacteristic
    private lateinit var rx: BluetoothGattCharacteristic

    override val connected: Boolean
        get() = gatt.connected

    override fun connect() {
        gatt.connect()
        val characteristics = gatt.services.flatMap(BluetoothGattService::getCharacteristics)
        tx = characteristics.firstOrNull { it.uuid.toString().contains("ae01", true) && it.canWrite() }
            ?: characteristics.firstOrNull(BluetoothGattCharacteristic::canWrite)
            ?: error("BeeSIM write characteristic was not found")
        rx = characteristics.firstOrNull { it.uuid.toString().contains("ae02", true) && it.canNotify() }
            ?: characteristics.firstOrNull { it.canNotify() && it.uuid != tx.uuid }
            ?: error("BeeSIM notify characteristic was not found")
        gatt.enableNotifications(rx)
        Thread.sleep(200)
        exchange(byteArrayOf(0xA0.toByte(), 0x3E, 0x01, 0x00, 0x00))
    }

    override fun disconnect() = gatt.close()

    override fun transceive(apdu: ByteArray): ByteArray = exchange(apdu)

    private fun exchange(data: ByteArray): ByteArray {
        gatt.clearNotifications()
        val total = maxOf(1, (data.size + 17) / 18)
        repeat(total) { index ->
            val start = index * 18
            val end = minOf(start + 18, data.size)
            val payload = if (start < end) data.copyOfRange(start, end) else byteArrayOf()
            gatt.writeChunks(tx, byteArrayOf(total.toByte(), (index + 1).toByte()) + payload, maxChunkSize = 20)
            if (total > 1) Thread.sleep(10)
        }

        val assembled = ArrayList<Byte>()
        var expectedFrames = -1
        var lastFrame = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100)
        while (System.nanoTime() < deadline) {
            val packet = gatt.takeNotification(deadline) ?: continue
            if (packet.size < 2) continue
            val totalFrames = packet[0].toUByte().toInt()
            val frame = packet[1].toUByte().toInt()
            if (frame == 1) {
                assembled.clear()
                expectedFrames = totalFrames
            }
            if (frame != lastFrame + 1 && frame != 1) continue
            assembled.addAll(packet.copyOfRange(2, packet.size).toList())
            lastFrame = frame
            if (expectedFrames > 0 && frame == expectedFrames) return assembled.toByteArray()
        }
        throw TimeoutException("BeeSIM response timed out")
    }
}

@SuppressLint("MissingPermission")
private class BleGattClient(
    private val context: Context,
    private val device: BluetoothDevice,
) {
    private val notifications = LinkedBlockingQueue<ByteArray>()
    private var connectionFuture: CompletableFuture<Unit>? = null
    private var servicesFuture: CompletableFuture<Unit>? = null
    private var writeFuture: CompletableFuture<Unit>? = null
    private var descriptorFuture: CompletableFuture<Unit>? = null
    private var mtuFuture: CompletableFuture<Int>? = null
    private var gatt: BluetoothGatt? = null
    var connected: Boolean = false
        private set
    var mtu: Int = 23
        private set

    val services: List<BluetoothGattService>
        get() = gatt?.services.orEmpty()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                connectionFuture?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                connectionFuture?.completeExceptionally(IllegalStateException("Bluetooth disconnected (status $status)"))
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionFuture?.completeExceptionally(IllegalStateException("Bluetooth connection failed with status $status"))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesFuture?.complete(Unit)
            else servicesFuture?.completeExceptionally(IllegalStateException("Service discovery failed with status $status"))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleGattClient.mtu = mtu
            mtuFuture?.complete(this@BleGattClient.mtu)
        }

        @Deprecated("Deprecated in Android")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            notifications.offer(characteristic.value?.copyOf() ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            notifications.offer(value.copyOf())
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) writeFuture?.complete(Unit)
            else writeFuture?.completeExceptionally(IllegalStateException("Characteristic write failed with status $status"))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) descriptorFuture?.complete(Unit)
            else descriptorFuture?.completeExceptionally(IllegalStateException("Descriptor write failed with status $status"))
        }
    }

    fun connect() {
        if (connected) return
        connectionFuture = CompletableFuture()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        connectionFuture!!.get(20, TimeUnit.SECONDS)
        servicesFuture = CompletableFuture()
        check(gatt?.discoverServices() == true) { "Bluetooth service discovery could not start" }
        servicesFuture!!.get(20, TimeUnit.SECONDS)
        mtuFuture = CompletableFuture()
        if (gatt?.requestMtu(247) == true) runCatching { mtuFuture!!.get(8, TimeUnit.SECONDS) }
    }

    fun requireService(uuid: UUID): BluetoothGattService = services.firstOrNull { it.uuid == uuid }
        ?: error("Bluetooth service $uuid was not found")

    fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        val currentGatt = requireNotNull(gatt)
        check(currentGatt.setCharacteristicNotification(characteristic, true)) { "Bluetooth notifications could not be enabled" }
        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            ?: error("Bluetooth notification descriptor was not found")
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        descriptorFuture = CompletableFuture()
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                currentGatt.writeDescriptor(descriptor)
            }
        }
        check(started) { "Bluetooth notification descriptor write could not start" }
        descriptorFuture!!.get(10, TimeUnit.SECONDS)
    }

    fun writeChunks(characteristic: BluetoothGattCharacteristic, bytes: ByteArray, maxChunkSize: Int = 240) {
        val chunkSize = minOf(maxChunkSize, maxOf(20, mtu - 3))
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            write(characteristic, bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    private fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val currentGatt = requireNotNull(gatt)
        val noResponse = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
        val writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writeFuture = if (noResponse) null else CompletableFuture()
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = value
                currentGatt.writeCharacteristic(characteristic)
            }
        }
        check(started) { "Bluetooth characteristic write could not start" }
        if (noResponse) Thread.sleep(12) else writeFuture!!.get(15, TimeUnit.SECONDS)
    }

    fun clearNotifications() = notifications.clear()

    fun takeNotification(deadlineNanos: Long): ByteArray? {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return null
        return notifications.poll(remaining, TimeUnit.NANOSECONDS)
    }

    fun close() {
        connected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        notifications.clear()
    }
}

private fun BluetoothGattService.requireCharacteristic(uuid: UUID): BluetoothGattCharacteristic =
    getCharacteristic(uuid) ?: error("Bluetooth characteristic $uuid was not found")

private fun BluetoothGattCharacteristic.canWrite(): Boolean =
    properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

private fun BluetoothGattCharacteristic.canNotify(): Boolean =
    properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
