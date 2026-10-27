package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class SettingStoreTest {
    private fun descriptor(id: String = "feature", page: Int = 1) =
        SettingDescriptor(id, "$id.key", 1, page, "PURIFICATION", true)

    @Test fun descriptorsAndAuthorityExistWithoutConstructingAnyPage() {
        val store = SettingStore(listOf(descriptor(), descriptor("other", 2)), mapOf("feature.key" to true))
        assertEquals(listOf("feature", "other"), store.descriptors.map { it.id })
        assertEquals(2, store.descriptor("other")!!.page)
        assertTrue(store.state("feature").checked)
        assertFalse(store.state("other").checked)
        assertTrue(SettingStore::class.java.declaredFields.none { it.type.name.startsWith("android.") })
    }

    @Test fun rejectedBusinessCommandMirrorsTheFinalAuthorityInsteadOfTheRequest() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        val mirrors = mutableListOf<Boolean>()
        store.observe("feature") { mirrors += it.checked }
        var invocations = 0
        val rejected = store.request("feature", true) { invocations++; false }
        assertFalse(rejected.accepted)
        assertFalse(rejected.state.checked)
        assertTrue(mirrors.isEmpty())
        val accepted = store.request("feature", true) { invocations++; true }
        assertTrue(accepted.accepted)
        assertEquals(listOf(true), mirrors)
        assertEquals(2, invocations)
    }

    @Test fun disabledOrReentrantCommandsDoNotExecuteBusinessEffects() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        store.confirm("feature", SettingState(false, enabled = false))
        val disabled = store.request("feature", true) { fail("disabled command executed"); true }
        assertFalse(disabled.accepted)
        store.confirm("feature", SettingState(false))
        store.request("feature", true) {
            assertFalse(store.request("feature", true) { fail("reentrant command executed"); true }.accepted)
            true
        }
        assertTrue(store.state("feature").checked)
    }

    @Test fun pendingConfirmationCanPublishLaterAndObserversSeeOnlyConfirmedData() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        val observed = mutableListOf<SettingState>()
        store.observe("feature", observed::add)
        val pending = store.request("feature", true) { false }
        assertFalse(pending.state.checked)
        assertTrue(observed.isEmpty())
        store.checked("feature", true)
        assertEquals(listOf(SettingState(true)), observed)
        store.checked("feature", true)
        assertEquals(1, observed.size)
    }

    @Test fun failureDoesNotRetryAndDoesNotInventRollbackOfEarlierEffects() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        var effects = 0
        assertThrows(IllegalStateException::class.java) {
            store.request("feature", true) { effects++; store.checked("feature", true); error("after a real side effect") }
        }
        assertEquals(1, effects)
        assertTrue(store.state("feature").checked)
        assertTrue(store.request("feature", false) { false }.accepted)
    }

    @Test fun closedBindingsStopDeliveryAndStoreCloseRejectsNewCommands() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        var deliveries = 0
        val stop = store.observe("feature") { deliveries++ }
        stop(); stop()
        store.checked("feature", true)
        assertEquals(0, deliveries)
        store.observe("feature") { deliveries++ }
        store.close(); store.close()
        store.checked("feature", false)
        assertEquals(0, deliveries)
        assertThrows(IllegalStateException::class.java) { store.request("feature", false) { false } }
    }

    @Test fun identityConflictsAndUnknownCommandsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingStore(listOf(descriptor(), descriptor()), emptyMap())
        }
        val store = SettingStore(listOf(descriptor()), emptyMap())
        assertThrows(IllegalStateException::class.java) { store.request("missing", true) { true } }
    }

    @Test fun navigationMetadataDoesNotInventBooleanStateOrFavoriteEligibility() {
        val action = descriptor("action").copy(directToggle = false, favoriteEligible = false)
        val store = SettingStore(listOf(action), emptyMap())
        assertEquals(action, store.descriptor(action.id))
        assertTrue(store.snapshot().isEmpty())
        assertThrows(IllegalStateException::class.java) { store.state(action.id) }
    }

    @Test fun intermediatePreferenceNotificationsDoNotPublishARejectedRequest() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        val observed = mutableListOf<Boolean>()
        store.observe("feature") { observed += it.checked }
        val result = store.request("feature", true) {
            store.checked("feature", true)
            assertTrue(observed.isEmpty())
            false
        }
        assertFalse(result.accepted)
        assertFalse(store.state("feature").checked)
        assertTrue(observed.isEmpty())
    }

    @Test fun closingDuringNotificationPreventsLaterSnapshotCallbacksForBothWritePaths() {
        listOf(false, true).forEach { command ->
            val store = SettingStore(listOf(descriptor()), emptyMap())
            var later = 0
            store.observe("feature") { store.close() }
            store.observe("feature") { later++ }
            if (command) store.request("feature", true) { true } else store.checked("feature", true)
            assertEquals(0, later)
        }
    }

    @Test fun removingAndReplacingSubscriptionDuringNotificationCannotReviveOldSnapshot() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        var later = 0
        lateinit var stop: () -> Unit
        val callback: (SettingState) -> Unit = { later++ }
        store.observe("feature") { stop(); store.observe("feature", callback) }
        stop = store.observe("feature", callback)
        store.checked("feature", true)
        assertEquals(0, later)
    }

    @Test fun supersededNotificationCannotOverwriteLatestStateInALaterObserver() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        val values = mutableListOf<Boolean>()
        store.observe("feature") { if (it.checked) store.checked("feature", false) }
        store.observe("feature") { values += it.checked }
        store.checked("feature", true)
        assertEquals(listOf(false), values)
        assertFalse(store.state("feature").checked)
    }
}
