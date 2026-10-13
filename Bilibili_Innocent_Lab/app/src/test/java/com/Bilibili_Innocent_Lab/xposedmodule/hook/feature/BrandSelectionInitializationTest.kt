package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class BrandSelectionInitializationTest {
    @Test fun `early selection write waits for mode and late storage fills only missing mode`() {
        val state = BrandSplashSelectionState<String>()
        val write = state.observeWrite(listOf("new selection"))
        assertFalse(state.isInitialized())
        val ready = state.initialize(listOf("stale selection"), true)
        assertTrue(state.isInitialized())
        assertEquals(listOf("new selection"), ready.selected)
        assertTrue(ready.customMode)
        assertFalse(state.isCurrent(write.revision))
        assertEquals(ready, state.initialize(listOf("another stale selection"), false))
    }

    @Test fun `explicit mode and newer selection both take precedence over late initialization`() {
        for (custom in listOf(false, true)) {
            val state = BrandSplashSelectionState<String>()
            state.observeMode(custom)
            assertFalse(state.isInitialized())
            val write = state.observeWrite(listOf("latest"))
            assertTrue(state.isInitialized())
            assertEquals(write, state.initialize(listOf("old"), !custom))
            assertEquals(custom, state.snapshot().customMode)
        }
    }

    @Test fun `mode observed before list initializes retains its value while accepting initial list`() {
        val state = BrandSplashSelectionState<String>()
        state.observeMode(true)
        val ready = state.initialize(listOf("initial"), false)
        assertTrue(state.isInitialized())
        assertTrue(ready.customMode)
        assertEquals(listOf("initial"), ready.selected)
    }
}
