package app.hyperlpa.remote

import org.junit.Assert.*
import org.junit.Test

class PhoneNotificationInboxTest {
    @Test fun onlyApprovedPhonePeersCanHostNotifications() {
        val phone = PairedDevice("phone", "Phone", "pair", "secret", approved = true)
        assertTrue(phone.canHostPhoneNotifications())
        assertTrue(phone.copy(incoming = true).canHostPhoneNotifications())
        assertFalse(phone.copy(approved = false).canHostPhoneNotifications())
        assertFalse(phone.copy(groupOwner = true).canHostPhoneNotifications())
        assertFalse(phone.copy(managedBy = "browser-owner").canHostPhoneNotifications())
    }

    private val now = 1_800_000_000_000L
    private fun entry(id: String = "entry", text: String = "Code 123456", time: Long = now) =
        PhoneNotificationEntry(id, "org.example.messages", "Messages", text, time, "private-key", "Messages")

    @Test fun openingInboxAcknowledgesArrivalsWithoutHidingMessagesAndSurvivesSerialization() {
        val empty = PhoneInboxDevice()
        val first = empty.snapshot(listOf(entry()), true, now)
        assertTrue(first.isNew(first.entries.single()))
        assertTrue(empty.arrivals(first).isEmpty())
        val read = first.acknowledgeArrivals()
        val restored = DeviceJson.decodeFromString(PhoneInboxDevice.serializer(),
            DeviceJson.encodeToString(PhoneInboxDevice.serializer(), read))
        assertFalse(restored.isNew(restored.entries.single()))
        assertEquals("", restored.entries.single().notificationKey)
        assertTrue(restored.arrivals(restored.snapshot(listOf(entry()), true, now)).isEmpty())
    }

    @Test fun newAndChangedContentRaisesArrivalIndicatorButTimestampUpdatesDoNot() {
        val first = PhoneInboxDevice().snapshot(listOf(entry()), true, now).acknowledgeArrivals()
        val repost = first.snapshot(listOf(entry(time = now + 100)), true, now)
        assertFalse(repost.isNew(repost.entries.single()))
        assertTrue(first.arrivals(repost).isEmpty())
        val next = first.snapshot(listOf(entry(text = "Code 654321"), entry("another", time = now + 10)), true, now)
        assertEquals(setOf("entry", "another"), first.arrivals(next).map { it.id }.toSet())
        assertTrue(next.entries.all(next::isNew))
    }

    @Test fun revocationRemovesContentAndReceiptsAndRegrantDoesNotReplayAlerts() {
        val first = PhoneInboxDevice().snapshot(listOf(entry()), true, now).acknowledgeArrivals()
        val revoked = first.snapshot(listOf(entry()), false, now)
        assertTrue(revoked.entries.isEmpty())
        assertTrue(revoked.acknowledgedVersions.isEmpty())
        assertFalse(revoked.available)
        assertTrue(revoked.arrivals(revoked.snapshot(listOf(entry()), true, now)).isEmpty())
    }

    @Test fun duplicateIdsExpiryOrderingAndCrossDeviceIdentityAreHandled() {
        val entries = listOf(entry("old", time = now - PhoneInboxRetentionMillis - 1),
            entry("same", time = now - 1), entry("same", time = now - 1), entry("new", time = now))
        val first = PhoneInboxDevice().snapshot(entries, true, now)
        assertEquals(listOf("new", "same"), first.entries.map { it.id })
        val inbox = mapOf("one" to first.acknowledgeArrivals(first.seenVersions.filterKeys { it == "same" }), "two" to first)
        assertFalse(inbox.getValue("one").isNew(first.entries.last()))
        assertTrue(inbox.getValue("two").isNew(first.entries.last()))
    }

    @Test fun journalCacheIsBoundedWithoutRepeatingAlertsForBodiesEvictedFromTheCache() {
        val inbox = (0..9).associate { device ->
            val entries = (0..149).map { entry("$device-$it", time = now + device * 150 + it) }
            "$device" to PhoneInboxDevice().snapshot(entries, true, now).acknowledgeArrivals()
        }
        val bounded = boundedPhoneInbox(inbox, now)
        assertEquals(600, bounded.values.sumOf { it.entries.size })
        assertTrue(bounded.values.all { it.acknowledgedVersions.size <= 150 && it.seenVersions.size <= 150 })
        val evicted = bounded.getValue("0")
        val repeat = evicted.snapshot(inbox.getValue("0").entries, true, now)
        assertTrue(evicted.arrivals(repeat).isEmpty())
        assertTrue(repeat.entries.none(repeat::isNew))
        assertTrue(bounded.getValue("0").entries.isEmpty())
        assertEquals(150, bounded.getValue("9").entries.size)
    }

    @Test fun archiveRestoreAndDeleteSurviveSyncAndSerialization() {
        val message = entry()
        val initial = PhoneInboxDevice().snapshot(listOf(message), true, now)
        val archived = initial.archive(message)
        assertTrue(archived.isArchived(message))
        assertFalse(archived.isNew(message))
        val restoredJournal = DeviceJson.decodeFromString(PhoneInboxDevice.serializer(),
            DeviceJson.encodeToString(PhoneInboxDevice.serializer(), archived))
        val synced = restoredJournal.snapshot(listOf(message), true, now + 1)
        assertTrue(synced.isArchived(message))
        assertTrue(archived.arrivals(synced).isEmpty())
        val restored = synced.restore(message)
        assertFalse(restored.isArchived(message))
        assertFalse(restored.isNew(message))
        assertEquals(1, restored.entries.size)
        val deleted = restored.delete(message)
        assertTrue(deleted.entries.isEmpty())
        val encoded = DeviceJson.encodeToString(PhoneInboxDevice.serializer(), deleted)
        assertFalse(encoded.contains(message.text))
        val persistedDeletion = DeviceJson.decodeFromString(PhoneInboxDevice.serializer(), encoded)
        val absentThenReappeared = persistedDeletion.snapshot(emptyList(), true, now + 1)
            .snapshot(listOf(message), true, now + 2)
        assertTrue(absentThenReappeared.entries.isEmpty())
        assertTrue(deleted.arrivals(absentThenReappeared).isEmpty())
    }

    @Test fun archivedCopiesRemainWhenSourceDropsThemAndRevocationPurgesThem() {
        val message = entry()
        val archived = PhoneInboxDevice().snapshot(listOf(message), true, now).archive(message)
        val missing = archived.snapshot(emptyList(), true, now + 1)
        assertEquals(listOf(message.copy(notificationKey = "")), missing.entries)
        assertTrue(missing.isArchived(message))
        assertFalse(missing.restore(message).isNew(message))
        val revoked = missing.snapshot(emptyList(), false, now + 1)
        assertTrue(revoked.entries.isEmpty())
        assertTrue(revoked.dispositions.isEmpty())
        assertTrue(revoked.acknowledgedVersions.isEmpty())
    }

    @Test fun changedContentReappearsAndActionsOnOldVersionsCannotHideIt() {
        val message = entry()
        val initial = PhoneInboxDevice().snapshot(listOf(message), true, now)
        val changed = entry(text = "New message from the same source", time = now + 1)
        for (hidden in listOf(initial.archive(message), initial.delete(message))) {
            val fresh = hidden.snapshot(listOf(changed), true, now + 2)
            assertFalse(fresh.isArchived(changed))
            assertTrue(fresh.isNew(changed))
            assertEquals(listOf(changed.copy(notificationKey = "")), hidden.arrivals(fresh))
            assertEquals(fresh, fresh.archive(message))
            assertEquals(fresh, fresh.delete(message))
            assertEquals(fresh, fresh.restore(message))
            assertTrue(fresh.acknowledgeArrivals(initial.seenVersions).isNew(changed))
        }
    }

    @Test fun oldReceiptsDecodeAndDispositionMetadataExpiresAndStaysBounded() {
        val old = """{"entries":[],"readVersions":{"old":"version"}}"""
        assertEquals(mapOf("old" to "version"), DeviceJson.decodeFromString(PhoneInboxDevice.serializer(), old).acknowledgedVersions)
        var history = PhoneInboxDevice()
        repeat(170) { index ->
            val message = entry("delete-$index", time = now + index)
            history = history.snapshot(listOf(message), true, now + index).delete(message)
        }
        assertEquals(150, history.dispositions.size)
        assertTrue(history.snapshot(emptyList(), true, now + PhoneInboxRetentionMillis + 200).dispositions.isEmpty())
    }
}
