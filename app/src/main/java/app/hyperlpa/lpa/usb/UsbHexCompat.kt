package app.hyperlpa.lpa.usb

import app.hyperlpa.lpa.hexToByteArray
import app.hyperlpa.lpa.toHexString

internal fun ByteArray.encodeHex(): String = toHexString()
internal fun String.decodeHex(): ByteArray = hexToByteArray()
