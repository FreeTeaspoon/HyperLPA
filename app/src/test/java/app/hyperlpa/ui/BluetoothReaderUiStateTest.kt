package app.hyperlpa.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothReaderUiStateTest {
    @Test
    fun availabilityUsesActionablePrerequisiteOrder() {
        assertEquals(
            BluetoothReaderAvailability.DISABLED,
            bluetoothReaderAvailability(
                enabled = false,
                supported = false,
                permissionGranted = false,
                adapterEnabled = false,
            ),
        )
        assertEquals(
            BluetoothReaderAvailability.UNSUPPORTED,
            bluetoothReaderAvailability(
                enabled = true,
                supported = false,
                permissionGranted = false,
                adapterEnabled = false,
            ),
        )
        assertEquals(
            BluetoothReaderAvailability.PERMISSION_REQUIRED,
            bluetoothReaderAvailability(
                enabled = true,
                supported = true,
                permissionGranted = false,
                adapterEnabled = false,
            ),
        )
        assertEquals(
            BluetoothReaderAvailability.BLUETOOTH_OFF,
            bluetoothReaderAvailability(
                enabled = true,
                supported = true,
                permissionGranted = true,
                adapterEnabled = false,
            ),
        )
        assertEquals(
            BluetoothReaderAvailability.READY,
            bluetoothReaderAvailability(
                enabled = true,
                supported = true,
                permissionGranted = true,
                adapterEnabled = true,
            ),
        )
    }
}
