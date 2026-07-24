package app.hyperlpa.ui

data class BluetoothReaderUiState(
    val enabled: Boolean,
    val supported: Boolean,
    val permissionGranted: Boolean,
    val adapterEnabled: Boolean,
) {
    val availability: BluetoothReaderAvailability
        get() = bluetoothReaderAvailability(
            enabled = enabled,
            supported = supported,
            permissionGranted = permissionGranted,
            adapterEnabled = adapterEnabled,
        )
}

enum class BluetoothReaderAvailability {
    DISABLED,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    BLUETOOTH_OFF,
    READY,
}

internal fun bluetoothReaderAvailability(
    enabled: Boolean,
    supported: Boolean,
    permissionGranted: Boolean,
    adapterEnabled: Boolean,
): BluetoothReaderAvailability = when {
    !enabled -> BluetoothReaderAvailability.DISABLED
    !supported -> BluetoothReaderAvailability.UNSUPPORTED
    !permissionGranted -> BluetoothReaderAvailability.PERMISSION_REQUIRED
    !adapterEnabled -> BluetoothReaderAvailability.BLUETOOTH_OFF
    else -> BluetoothReaderAvailability.READY
}
