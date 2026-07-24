package app.hyperlpa.lpa.platform

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothLeReaderPolicyTest {
    @Test
    fun acceptsOnlyBondedDevices() {
        assertTrue(isBondedBleState(BluetoothDevice.BOND_BONDED))
        assertFalse(isBondedBleState(BluetoothDevice.BOND_BONDING))
        assertFalse(isBondedBleState(BluetoothDevice.BOND_NONE))
    }
}
