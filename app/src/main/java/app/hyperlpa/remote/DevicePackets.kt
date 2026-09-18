package app.hyperlpa.remote

/** Splits large snapshots before encryption. Each part has its own authenticated envelope. */
internal class DevicePackets {
    private data class Assembly(val expires: Long, val parts: Array<ByteArray?>, var size: Int = 0)
    private val assemblies = linkedMapOf<String, Assembly>()

    fun accept(peer: String, part: DevicePart, expires: Long, now: Long = System.currentTimeMillis()): DeviceMessage? {
        assemblies.entries.removeAll { it.value.expires <= now }
        require(part.transfer.matches(Regex("[a-f0-9-]{36}")) && part.count in 2..MaxParts && part.index in 0 until part.count)
        require(part.data.length <= 800_000)
        val key = "$peer/${part.transfer}"
        val assembly = assemblies[key] ?: run {
            require(assemblies.size < 4)
            Assembly(expires, arrayOfNulls(part.count)).also { assemblies[key] = it }
        }
        require(assembly.parts.size == part.count)
        val bytes = DeviceCrypto.decode(part.data)
        require(bytes.size <= PartBytes)
        val previous = assembly.parts[part.index]
        if (previous != null) { require(previous.contentEquals(bytes)); return null }
        require(assemblies.values.sumOf { it.size } + bytes.size <= MaxBytes)
        assembly.parts[part.index] = bytes
        assembly.size += bytes.size
        if (assembly.parts.any { it == null }) return null
        assemblies.remove(key)
        val payload = java.io.ByteArrayOutputStream(assembly.size)
        assembly.parts.forEach { payload.write(requireNotNull(it)) }
        return DeviceJson.decodeFromString(DeviceMessage.serializer(), payload.toString("UTF-8")).also { require(it.part == null) }
    }

    companion object {
        private const val PartBytes = 600_000
        private const val MaxParts = 28
        private const val MaxBytes = PartBytes * MaxParts

        fun encode(message: DeviceMessage): List<String> {
            val text = DeviceJson.encodeToString(DeviceMessage.serializer(), message)
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MaxBytes) { "Remote snapshot exceeds the transfer limit" }
            if (bytes.size <= PartBytes) return listOf(text)
            val transfer = newDeviceId()
            val count = (bytes.size + PartBytes - 1) / PartBytes
            return (0 until count).map { index ->
                val part = DevicePart(transfer, index, count,
                    DeviceCrypto.encode(bytes.copyOfRange(index * PartBytes, minOf(bytes.size, (index + 1) * PartBytes))))
                DeviceJson.encodeToString(DeviceMessage.serializer(), DeviceMessage("part", part = part))
            }
        }
    }
}
