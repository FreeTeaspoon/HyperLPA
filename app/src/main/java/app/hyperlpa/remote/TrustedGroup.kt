package app.hyperlpa.remote

/** An owner provisions distinct browser-to-phone pairs, never shares an existing pair key. */
internal object TrustedGroup {
    fun apply(current: StoredDevices, sender: String, sync: GroupSync): StoredDevices {
        require(sender == current.groupOwnerId && current.peers.any { it.id == sender && it.approved && it.groupOwner })
        require(sync.revision > 0 && sync.revision >= current.groupRevision)
        if (sync.revision == current.groupRevision) return current
        require(sync.peers.size <= 31 && sync.peers.map { it.id }.distinct().size == sync.peers.size)
        val retained = current.peers.filter { it.managedBy != sender }
        val peers = sync.peers.map { peer ->
            require(peer.id != current.id && peer.id != sender && retained.none { it.id == peer.id })
            require(listOf(peer.id, peer.pair).all { it.matches(Regex("[a-f0-9-]{36}")) })
            require(peer.name.length in 1..80 && DeviceCrypto.decode(peer.secret).size == 32)
            peer.copy(approved = true, incoming = false, groupOwner = false, managedBy = sender)
        }
        require(retained.size + peers.size <= 32)
        val removed = current.peers.filter { it.managedBy == sender }.map { it.id }.toSet()
        val notificationPeers = (current.notificationPeers - removed) + peers.filter { it.canViewNotifications }.map { it.id }
        return current.copy(peers = retained + peers, groupRevision = sync.revision, notificationPeers = notificationPeers)
    }

    fun canViewNotifications(current: StoredDevices, peer: PairedDevice): Boolean =
        current.sharePhoneNotifications && peer.id in current.notificationPeers &&
            (peer.managedBy.isEmpty() || peer.canViewNotifications && current.groupOwnerId in current.notificationPeers)
}
