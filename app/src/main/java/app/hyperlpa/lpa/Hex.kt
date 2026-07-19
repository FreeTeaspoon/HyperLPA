package app.hyperlpa.lpa

fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02X".format(byte) }

fun String.hexToByteArray(): ByteArray {
    val normalized = filterNot(Char::isWhitespace)
    require(normalized.length % 2 == 0) { "Hex value must contain an even number of characters" }
    return ByteArray(normalized.length / 2) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
