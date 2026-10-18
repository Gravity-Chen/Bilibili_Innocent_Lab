package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Member
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HookPointRegistryTest {

    private class FixtureException(message: String) : RuntimeException(message)

    private class Fixture {
        @Suppress("unused")
        private val stableField: String = "value"

        @Suppress("unused")
        private fun exact(value: Int): String = value.toString()

        @Suppress("unused")
        private fun overloaded(value: Int) = value

        @Suppress("unused")
        private fun overloaded(value: String) = value
    }

    private lateinit var registry: HookPointRegistry

    @Before
    fun setUp() {
        KavaMemberLookup.resetForTests()
        registry = HookPointRegistry(requireNotNull(Fixture::class.java.classLoader))
    }

    @After
    fun tearDown() {
        KavaMemberLookup.resetForTests()
    }

    @Test
    fun `resolves adapted primitive signature and prevents duplicate registration`() {
        val method = registry.resolveAdapted(
            id = "fixture.exact",
            className = Fixture::class.java.name,
            methodName = "exact",
            parameterClassNames = listOf("int")
        )

        assertNotNull(method)
        assertTrue(registry.claim("fixture.exact", requireNotNull(method)))
        registry.markInstalled("fixture.exact", method)
        assertFalse(registry.claim("fixture.exact", method))
        assertEquals(HookPointRegistry.State.DUPLICATE, registry.snapshot().single().state)
    }

    @Test
    fun `prevents duplicate constructor registration`() {
        val constructor = Fixture::class.java.getDeclaredConstructor()

        assertTrue(registry.claim("fixture.constructor", constructor))
        registry.markInstalled("fixture.constructor", constructor)
        assertFalse(registry.claim("fixture.constructor", constructor))

        val diagnostic = registry.snapshot().single()
        assertEquals(HookPointRegistry.State.DUPLICATE, diagnostic.state)
        assertTrue(diagnostic.member.orEmpty().contains("#<init>()"))
    }

    @Test
    fun `failed method and constructor registrations can retry without duplicating successful installs`() {
        val members = listOf<Member>(
            Fixture::class.java.getDeclaredMethod("exact", Int::class.javaPrimitiveType),
            Fixture::class.java.getDeclaredConstructor()
        )
        members.forEachIndexed { index, member ->
            val id = "fixture.retry.$index"
            val failure = FixtureException("native registration failed")
            var nativeAttempts = 0
            fun install(fails: Boolean): Boolean {
                if (!registry.claim(id, member)) return false
                try {
                    nativeAttempts++
                    if (fails) throw failure
                    registry.markInstalled(id, member)
                    return true
                } catch (throwable: Throwable) {
                    registry.markFailed(id, member, throwable)
                    throw throwable
                }
            }

            try {
                install(fails = true)
                fail("Expected the native registration failure")
            } catch (actual: FixtureException) {
                assertSame(failure, actual)
            }
            assertEquals(HookPointRegistry.State.FAILED, registry.snapshot().single { it.id == id }.state)
            assertTrue(install(fails = false))
            assertFalse(install(fails = false))
            assertEquals(2, nativeAttempts)
        }
    }

    @Test
    fun `concurrent in progress registration throws instead of reporting a successful duplicate`() {
        val member = Fixture::class.java.getDeclaredMethod("exact", Int::class.javaPrimitiveType)
        val id = "fixture.inflight"
        val claimed = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                assertTrue(registry.claim(id, member))
                claimed.countDown()
                assertTrue(finish.await(5, TimeUnit.SECONDS))
                registry.markInstalled(id, member)
            } catch (throwable: Throwable) {
                workerFailure.set(throwable)
            }
        }.also(Thread::start)

        try {
            assertTrue(claimed.await(5, TimeUnit.SECONDS))
            try {
                registry.claim(id, member)
                fail("An in progress registration must not return a duplicate success")
            } catch (_: IllegalStateException) {
                // 第一个原生安装尚未完成，调用方必须保留 partial 状态。
            }
        } finally {
            finish.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        assertNull(workerFailure.get())
        assertFalse(registry.claim(id, member))
    }

    @Test
    fun `unclaimed failure cannot release an already installed registration`() {
        val member = Fixture::class.java.getDeclaredMethod("exact", Int::class.javaPrimitiveType)
        val id = "fixture.installed"
        assertTrue(registry.claim(id, member))
        registry.markInstalled(id, member)

        registry.markFailed(id, member, FixtureException("legacy unclaimed failure"))

        assertFalse(registry.claim(id, member))
    }

    @Test
    fun `legacy failure without a claim does not reserve a registration`() {
        val member = Fixture::class.java.getDeclaredMethod("exact", Int::class.javaPrimitiveType)
        registry.markFailed("fixture.legacy", member, FixtureException("unclaimed failure"))
        registry.markFailed("fixture.unknown", null, FixtureException("unresolved failure"))

        assertTrue(registry.claim("fixture.legacy", member))
        assertTrue(registry.claim("fixture.unknown", member))
    }

    @Test
    fun `failed registration releases only its exact id and member`() {
        val method = Fixture::class.java.getDeclaredMethod("exact", Int::class.javaPrimitiveType)
        val constructor = Fixture::class.java.getDeclaredConstructor()
        assertTrue(registry.claim("fixture.shared", method))
        assertTrue(registry.claim("fixture.other", method))
        assertTrue(registry.claim("fixture.shared", constructor))
        registry.markInstalled("fixture.other", method)
        registry.markInstalled("fixture.shared", constructor)

        registry.markFailed("fixture.shared", method, FixtureException("scoped failure"))

        assertTrue(registry.claim("fixture.shared", method))
        assertFalse(registry.claim("fixture.other", method))
        assertFalse(registry.claim("fixture.shared", constructor))
    }

    @Test
    fun `reports ambiguous and missing hook points without throwing`() {
        assertNull(
            registry.resolveAdapted(
                id = "fixture.ambiguous",
                className = Fixture::class.java.name,
                methodName = "overloaded",
                parameterClassNames = null
            )
        )
        assertNull(
            registry.resolveFirst(
                id = "fixture.missing",
                className = "missing.Fixture",
                methodName = "none"
            )
        )

        val states = registry.snapshot().associate { it.id to it.state }
        assertEquals(HookPointRegistry.State.AMBIGUOUS_METHOD, states["fixture.ambiguous"])
        assertEquals(HookPointRegistry.State.MISSING_CLASS, states["fixture.missing"])
    }

    @Test
    fun `resolves fields through unified diagnostics`() {
        val field = registry.resolveField(
            "fixture.field",
            Fixture::class.java.name,
            "stableField"
        )
        val missing = registry.resolveField(
            "fixture.field.missing",
            Fixture::class.java,
            "absent"
        )

        assertNotNull(field)
        assertEquals("value", requireNotNull(field).get(Fixture()))
        assertNull(missing)
        val diagnostics = registry.snapshot().associateBy { it.id }
        assertEquals(HookPointRegistry.State.RESOLVED, diagnostics["fixture.field"]?.state)
        assertTrue(diagnostics["fixture.field"]?.member.orEmpty().endsWith("#stableField"))
        assertEquals(
            HookPointRegistry.State.MISSING_FIELD,
            diagnostics["fixture.field.missing"]?.state
        )
    }

    @Test
    fun `resolves constructor and reports missing signature`() {
        val constructor = registry.resolveConstructor(
            "fixture.exception",
            FixtureException::class.java.name,
            listOf(String::class.java.name)
        )
        val missing = registry.resolveConstructor(
            "fixture.exception.missing",
            FixtureException::class.java.name,
            listOf("int")
        )

        assertNotNull(constructor)
        assertTrue(requireNotNull(constructor).newInstance("blocked") is FixtureException)
        assertNull(missing)
        val diagnostics = registry.snapshot().associateBy { it.id }
        assertEquals(HookPointRegistry.State.RESOLVED, diagnostics["fixture.exception"]?.state)
        assertEquals(
            HookPointRegistry.State.MISSING_CONSTRUCTOR,
            diagnostics["fixture.exception.missing"]?.state
        )
    }

    /** 2026-09-30：自由复制缓存签名含 int，旧映射只认 long/boolean，整段注册被跳过。 */
    @Test
    fun `cached parameter names resolve every primitive and fail as a whole`() {
        val primitives = listOf("boolean", "byte", "char", "short", "int", "long", "float", "double")
        val resolved = requireNotNull(registry.resolveParameterClasses(primitives))
        assertEquals(primitives, resolved.map { it.name })
        assertTrue(resolved.all { it.isPrimitive })
        assertEquals(String::class.java, registry.resolveParameterClasses(listOf("java.lang.String"))!!.single())
        assertEquals(0, registry.resolveParameterClasses(emptyList())!!.size)
        // 任一个找不到就整体为 null，不拿半截签名去找方法。
        assertNull(registry.resolveParameterClasses(listOf("int", "does.not.Exist")))
        // 用缓存名解析出的类能精确命中 int 参数的方法。
        val params = requireNotNull(registry.resolveParameterClasses(listOf("int")))
        assertNotNull(registry.resolveExact("fixture:exact-int", Fixture::class.java, "exact", *params))
    }
}
