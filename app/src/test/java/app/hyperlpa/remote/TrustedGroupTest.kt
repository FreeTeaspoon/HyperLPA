package app.hyperlpa.remote

import org.junit.Assert.*
import org.junit.Test

class TrustedGroupTest {
    private val owner = PairedDevice(newDeviceId(), "Owner", newDeviceId(), DeviceCrypto.secret(), approved = true, groupOwner = true)
    private val browser = PairedDevice(newDeviceId(), "Laptop", newDeviceId(), DeviceCrypto.secret(), canManageProfiles = false, canViewNotifications = true)
    private fun state() = StoredDevices(peers = listOf(owner), groupOwnerId = owner.id)

    @Test fun onlyExplicitlyApprovedOwnerCanProvisionControllers() {
        assertThrows(IllegalArgumentException::class.java) { TrustedGroup.apply(state(), browser.id, GroupSync(1, listOf(browser))) }
        assertThrows(IllegalArgumentException::class.java) {
            TrustedGroup.apply(state().copy(peers = listOf(owner.copy(approved = false))), owner.id, GroupSync(1, listOf(browser)))
        }
        val next = TrustedGroup.apply(state(), owner.id, GroupSync(1, listOf(browser)))
        val added = next.peers.single { it.id == browser.id }
        assertTrue(added.approved)
        assertFalse(added.canManageProfiles)
        assertEquals(owner.id, added.managedBy)
        assertNotEquals(owner.secret, added.secret)
    }

    @Test fun notificationsRequireSourceSharingAndOwnerConsent() {
        var next = TrustedGroup.apply(state(), owner.id, GroupSync(1, listOf(browser)))
        val added = next.peers.single { it.id == browser.id }
        assertFalse(TrustedGroup.canViewNotifications(next, added))
        next = next.copy(sharePhoneNotifications = true)
        assertFalse(TrustedGroup.canViewNotifications(next, added))
        next = next.copy(notificationPeers = next.notificationPeers + owner.id)
        assertTrue(TrustedGroup.canViewNotifications(next, added))
        assertFalse(TrustedGroup.canViewNotifications(next, added.copy(canViewNotifications = false)))
    }

    @Test fun revisionsCannotReplayPermissionsAndRemovalPreservesRegularPairs() {
        val regular = browser.copy(id = newDeviceId(), name = "Regular pairing")
        val initial = state().copy(peers = listOf(owner, regular))
        val next = TrustedGroup.apply(initial, owner.id, GroupSync(2, listOf(browser)))
        assertThrows(IllegalArgumentException::class.java) { TrustedGroup.apply(next, owner.id, GroupSync(1)) }
        assertEquals(next, TrustedGroup.apply(next, owner.id, GroupSync(2)))
        val revoked = TrustedGroup.apply(next, owner.id, GroupSync(3))
        assertEquals(listOf(owner, regular), revoked.peers)
        assertFalse(browser.id in revoked.notificationPeers)
    }

    @Test fun rejectsDuplicateAndOverlappingIdentities() {
        assertThrows(IllegalArgumentException::class.java) { TrustedGroup.apply(state(), owner.id, GroupSync(1, listOf(browser, browser))) }
        assertThrows(IllegalArgumentException::class.java) { TrustedGroup.apply(state(), owner.id, GroupSync(1, listOf(browser.copy(id = owner.id)))) }
        assertThrows(IllegalArgumentException::class.java) { TrustedGroup.apply(state(), owner.id, GroupSync(1, listOf(browser.copy(secret = "bad")))) }
    }
}
