package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class SettingWritePolicyTest {
    private fun store(initial: Boolean) = SettingStore(
        listOf(SettingDescriptor("feature", "key", 1, 1, "PURIFICATION", true)),
        mapOf("key" to initial),
    )

    @Test fun notificationBeforeReadbackFailureCannotTurnRejectionIntoAcceptance() {
        listOf(false, true).forEach { previous ->
            val requested = !previous
            val store = store(previous)
            var mirrorField = previous
            var writes = 0
            val observed = mutableListOf<Boolean>()
            store.observe("feature") { observed += it.checked }
            val readbackFailure = IllegalStateException("simulated readback failure")
            val result = store.request("feature", requested) {
                val outcome = SettingWritePolicy.attempt(mirrorField, requested) {
                    writes++
                    // SharedPreferences can synchronously notify before the command verifies the write.
                    mirrorField = requested
                    store.checked("feature", requested)
                    throw readbackFailure
                }
                assertSame(readbackFailure, outcome.failure)
                assertFalse(outcome.saved)
                mirrorField = outcome.confirmed
                outcome.confirmed
            }
            assertEquals(1, writes)
            assertFalse(result.accepted)
            assertEquals(previous, result.state.checked)
            assertEquals(previous, store.state("feature").checked)
            assertEquals(previous, mirrorField)
            assertTrue(observed.isEmpty())
        }
    }

    @Test fun successfulVerifiedWritePublishesItsFinalValueOnce() {
        val store = store(false)
        val observed = mutableListOf<Boolean>()
        store.observe("feature") { observed += it.checked }
        var writes = 0
        val result = store.request("feature", true) {
            val outcome = SettingWritePolicy.attempt(false, true) {
                writes++
                store.checked("feature", true)
                assertTrue(observed.isEmpty())
            }
            assertTrue(outcome.saved)
            assertNull(outcome.failure)
            outcome.confirmed
        }
        assertTrue(result.accepted)
        assertEquals(1, writes)
        assertEquals(listOf(true), observed)
    }
}
