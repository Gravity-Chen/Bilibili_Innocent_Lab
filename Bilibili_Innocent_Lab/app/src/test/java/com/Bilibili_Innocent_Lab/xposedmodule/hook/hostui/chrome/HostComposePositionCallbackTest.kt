package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostComposeCardPositionAccess
import org.junit.Assert.*
import org.junit.Test

/** Execute the real Proxy callback without mocking an entire host Compose runtime. */
class HostComposePositionCallbackTest {
    private data class Coordinates(val packed: Long = 0, val failure: Throwable? = null, val root: Long = packed)
    private class State(var value: Int)
    private class Bridge {
        companion object {
            @JvmStatic fun positioned(modifier: Any, callback: (Any) -> Unit): Any = callback
            @JvmStatic fun inWindow(coordinates: Any): Long = (coordinates as Coordinates).let { it.failure?.let { error -> throw error }; it.packed }
            @JvmStatic fun inRoot(coordinates: Any): Long = (coordinates as Coordinates).root
            @JvmStatic fun state(value: Int): Any = State(value)
            @JvmStatic fun read(state: Any): Int = (state as State).value
            @JvmStatic fun write(state: Any, value: Int) { (state as State).value = value }
        }
    }
    private fun access(root: Boolean = true): HostComposeCardPositionAccess {
        fun method(name: String, vararg types: Class<*>) = Bridge::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
        val ctor = HostComposeCardPositionAccess::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        return ctor.newInstance(method("positioned", Any::class.java, Function1::class.java), method("inWindow", Any::class.java),
            Function1::class.java, Unit, method("state", Int::class.javaPrimitiveType!!), method("read", Any::class.java),
            method("write", Any::class.java, Int::class.javaPrimitiveType!!),
            if (root) method("inRoot", Any::class.java) else null) as HostComposeCardPositionAccess
    }
    @Suppress("UNCHECKED_CAST")
    private fun observer(callback: (Float, Float) -> Unit, onError: ((Throwable) -> Unit)?, root: Boolean = false): (Any) -> Unit =
        access().attach(Any(), callback, onError, root) as (Any) -> Unit
    private fun pack(x: Float, y: Float) = (x.toBits().toLong() shl 32) or (y.toBits().toLong() and 0xFFFFFFFFL)

    @Test fun `position decoder failure reaches error handler without escaping host layout`() {
        val expected = IllegalStateException("detached coordinates")
        var failure: Throwable? = null
        observer({ _, _ -> fail("invalid coordinates reached layout") }, { failure = it })(Coordinates(failure = expected))
        assertTrue(generateSequence(failure) { it.cause }.any { it === expected })
    }
    @Test fun `position consumer and diagnostic failures cannot escape protected callback`() {
        var reports = 0
        observer({ _, _ -> throw IllegalStateException("surface") }, { reports++; throw IllegalStateException("diagnostic") })(Coordinates())
        assertEquals(1, reports)
    }
    @Test fun `valid coordinates retain native float values`() {
        var position: Pair<Float, Float>? = null
        val packed = (12.5f.toBits().toLong() shl 32) or ((-3.25f).toBits().toLong() and 0xFFFFFFFFL)
        observer({ x, y -> position = x to y }, { throw AssertionError(it) })(Coordinates(packed))
        assertEquals(12.5f to -3.25f, position)
    }
    @Test fun `existing callers without error handler retain exception behavior`() {
        val expected = IllegalStateException("existing caller")
        try {
            observer({ _, _ -> throw expected }, null)(Coordinates())
            fail("the opt-in handler changed the existing caller contract")
        } catch (failure: Throwable) {
            assertTrue(generateSequence(failure) { it.cause }.any { it === expected })
        }
    }
    @Test fun `root mode reads the owner-local offset instead of the cached window position`() {
        var position: Pair<Float, Float>? = null
        observer({ x, y -> position = x to y }, { throw AssertionError(it) }, root = true)(Coordinates(pack(0f, 900f), root = pack(4f, 0f)))
        assertEquals(4f to 0f, position)
    }
    @Test fun `root mode is optional and never silently falls back to window coordinates`() {
        assertTrue(access().rootPositions)
        val missing = access(root = false)
        assertFalse(missing.rootPositions)
        try {
            missing.attach(Any(), { _, _ -> }, null, root = true)
            fail("window coordinates were used as root-local offsets")
        } catch (expected: IllegalArgumentException) {}
    }
}
