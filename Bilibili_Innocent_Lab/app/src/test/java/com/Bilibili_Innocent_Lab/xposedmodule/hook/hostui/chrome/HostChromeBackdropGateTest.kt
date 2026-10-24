package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.*
import org.junit.Test

class HostChromeBackdropGateTest {
    @Test fun `successful retry arms callbacks retained from a partial registration`() {
        val original = HostChromeBackdropGate.installation(javaClass, "partial-row", listOf("native.Model", "Composer"))
        assertFalse(original.get())
        val retry = HostChromeBackdropGate.installation(javaClass, "partial-row", arrayListOf("native.Model", "Composer"))
        assertSame(original, retry)
        retry.set(true)
        assertTrue(original.get())
    }

    @Test fun `installation gates stay separate across owners overloads and methods`() {
        val live = HostChromeBackdropGate.installation(javaClass, "live-row", listOf("Model", "Composer"))
        live.set(true)
        assertFalse(HostChromeBackdropGate.installation(Any::class.java, "live-row", listOf("Model", "Composer")).get())
        assertFalse(HostChromeBackdropGate.installation(javaClass, "other-row", listOf("Model", "Composer")).get())
        assertFalse(HostChromeBackdropGate.installation(javaClass, "live-row", listOf("OtherModel", "Composer")).get())
    }

    @Test fun `native decorations are eligible only outside every foreground sibling`() {
        val gate = HostChromeBackdropGate()
        assertTrue(gate.decorative)
        val tabs = gate.enterForeground()
        val nestedMenu = gate.enterForeground()
        assertFalse(gate.decorative)
        gate.exitForeground(tabs)
        assertFalse(gate.decorative)
        gate.exitForeground(nestedMenu)
        assertTrue(gate.decorative)
    }

    @Test fun `stale and duplicate callbacks cannot reopen a live foreground`() {
        val gate = HostChromeBackdropGate()
        val old = gate.enterForeground()
        gate.exitForeground(old)
        val current = gate.enterForeground()
        gate.exitForeground(old)
        gate.exitForeground(Any())
        assertFalse(gate.decorative)
        gate.exitForeground(current)
        assertTrue(gate.decorative)
    }

    @Test fun `native exception releases only its own invocation and next row is independent`() {
        val outer = HostChromeBackdropGate()
        val token = outer.enterForeground()
        val inner = HostChromeBackdropGate()
        assertTrue(inner.decorative)
        runCatching {
            val child = outer.enterForeground()
            try { throw IllegalStateException("native") } finally { outer.exitForeground(child) }
        }
        assertFalse(outer.decorative)
        outer.exitForeground(token)
        assertTrue(outer.decorative)
    }
}
