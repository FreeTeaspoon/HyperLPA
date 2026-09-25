package app.hyperlpa.lpa

import net.typeblog.lpac_jni.ApduInterface
import net.typeblog.lpac_jni.Version

private val NineEsimEidPattern = Regex("^89044045(84|21)67274948")
private val EstkmeProductAid = "A06573746B6D65FFFFFFFFFFFF6D6774".hexToByteArray()
private val EstkmeSkuCommand = byteArrayOf(0x00, 0x00, 0x03, 0x00, 0x00)
private const val MaxProductNameLength = 64

/**
 * 9eSIM cards do not report a product name, so the model is inferred from the firmware release.
 * Firmware newer than the last known release is reported as that release until this table is updated.
 */
internal fun nineEsimProductName(eid: String, firmwareVersion: Version?): String? {
    if (firmwareVersion == null || !NineEsimEidPattern.containsMatchIn(eid)) return null
    val model = when {
        // @formatter:off
        firmwareVersion >= Version(37,  4,  3) -> "v3.2 (beta 1)"
        firmwareVersion >= Version(37,  1, 41) -> "v3.1 (beta 1)"
        firmwareVersion >= Version(36, 18,  5) -> "v3 (final)"
        firmwareVersion >= Version(36, 17, 39) -> "v3 (beta)"
        firmwareVersion >= Version(36, 17,  4) -> "v2s"
        firmwareVersion >= Version(36,  9,  3) -> "v2.1"
        firmwareVersion >= Version(36,  7,  2) -> "v2"
        // @formatter:on
        else -> null
    }
    return if (model == null) "9eSIM" else "9eSIM $model"
}

/** Returns the SKU reported by the eSTK.me product applet, or null when the card has no such applet. */
internal fun readEstkmeProductName(apdu: ApduInterface): String? {
    val handle = runCatching { apdu.logicalChannelOpen(EstkmeProductAid) }.getOrNull()
        ?.takeIf { it >= 0 }
        ?: return null
    return try {
        runCatching { decodeEstkmeProductName(apdu.transmit(handle, EstkmeSkuCommand)) }.getOrNull()
    } finally {
        runCatching { apdu.logicalChannelClose(handle) }
    }
}

internal fun decodeEstkmeProductName(response: ByteArray): String? {
    if (response.size < 2) return null
    if (response[response.size - 2] != 0x90.toByte() || response.last() != 0x00.toByte()) return null
    return response.copyOfRange(0, response.size - 2)
        .decodeToString()
        .filterNot(Char::isISOControl)
        .trim()
        .take(MaxProductNameLength)
        .ifBlank { null }
}
