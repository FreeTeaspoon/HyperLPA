package app.hyperlpa.lpa.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.hyperlpa.R
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.lpa.ReaderEndpoint
import app.hyperlpa.lpa.ReaderProvider
import app.hyperlpa.lpa.usb.UsbApduInterface
import app.hyperlpa.lpa.usb.UsbCcidContext
import app.hyperlpa.lpa.usb.interfaces
import app.hyperlpa.lpa.usb.smartCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout

internal class UsbCcidReaderProvider(
    context: Context,
    private val verboseLoggingFlow: Flow<Boolean> = flowOf(false),
) : ReaderProvider {
    companion object {
        private const val PermissionAction = "app.hyperlpa.USB_PERMISSION"
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)

    override suspend fun listReaders(): List<ReaderEndpoint> = usbManager.deviceList.values.mapNotNull { device ->
        val smartCardInterface = device.interfaces.smartCard ?: return@mapNotNull null
        val productName = device.productName
            ?.filterNot(Char::isISOControl)
            ?.trim()
            ?.take(96)
            ?.takeIf(String::isNotEmpty)
            ?: appContext.getString(R.string.reader_usb_default_name)
        val manufacturerName = device.manufacturerName
            ?.filterNot(Char::isISOControl)
            ?.trim()
            ?.take(96)
            ?.takeIf(String::isNotEmpty)
        ReaderEndpoint(
            info = ReaderInfo(
                id = "usb:${device.deviceId}:${smartCardInterface.id}",
                name = productName,
                kind = ReaderKind.USB_CCID,
                detail = listOfNotNull(
                    manufacturerName,
                    "${device.vendorId.toString(16)}:${device.productId.toString(16)}",
                ).joinToString(" · "),
            ),
            requiresProfileSwitchRefresh = false,
            openApduInterface = {
                ensurePermission(device)
                val context = UsbCcidContext.createFromUsbDevice(
                    context = appContext,
                    usbDevice = device,
                    usbInterface = smartCardInterface,
                    verboseLoggingFlow = verboseLoggingFlow,
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
                val returnedDevice = IntentCompat.getParcelableExtra(
                    intent,
                    UsbManager.EXTRA_DEVICE,
                    UsbDevice::class.java,
                )
                if (returnedDevice?.deviceId != device.deviceId) return
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
            check(withTimeout(30_000) { result.await() }) { "USB device permission was denied" }
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }
}
