package app.hyperlpa.lpa.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.lpa.ReaderEndpoint
import app.hyperlpa.lpa.ReaderProvider
import app.hyperlpa.lpa.usb.UsbApduInterface
import app.hyperlpa.lpa.usb.UsbCcidContext
import app.hyperlpa.lpa.usb.interfaces
import app.hyperlpa.lpa.usb.smartCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf

internal class UsbCcidReaderProvider(context: Context) : ReaderProvider {
    companion object {
        private const val PermissionAction = "app.hyperlpa.USB_PERMISSION"
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)

    override suspend fun listReaders(): List<ReaderEndpoint> = usbManager.deviceList.values.mapNotNull { device ->
        val smartCardInterface = device.interfaces.smartCard ?: return@mapNotNull null
        ReaderEndpoint(
            info = ReaderInfo(
                id = "usb:${device.deviceId}:${smartCardInterface.id}",
                name = device.productName ?: "USB smart-card reader",
                kind = ReaderKind.USB_CCID,
                detail = listOfNotNull(
                    device.manufacturerName,
                    "${device.vendorId.toString(16)}:${device.productId.toString(16)}",
                ).joinToString(" · "),
            ),
            openApduInterface = {
                ensurePermission(device)
                val context = UsbCcidContext.createFromUsbDevice(
                    context = appContext,
                    usbDevice = device,
                    usbInterface = smartCardInterface,
                    verboseLoggingFlow = flowOf(false),
                ) ?: throw IllegalStateException("Unable to open the USB CCID interface")
                context.allowDisconnect = true
                UsbApduInterface(context)
            },
        )
    }

    private suspend fun ensurePermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) return
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != PermissionAction) return
                if (!result.isCompleted) {
                    result.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(PermissionAction),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                device.deviceId,
                Intent(PermissionAction).setPackage(appContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            usbManager.requestPermission(device, pendingIntent)
            check(result.await()) { "USB device permission was denied" }
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }
}
