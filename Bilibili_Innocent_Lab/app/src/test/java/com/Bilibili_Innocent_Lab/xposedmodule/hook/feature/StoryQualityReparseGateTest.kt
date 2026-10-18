package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class StoryQualityReparseGateTest {
    class Resource
    interface Listener {
        fun success(resource: Resource)
        fun onError()
    }
    interface UnknownListener {
        fun success(resource: Resource)
        fun onError()
        fun progress(percent: Int)
    }
    interface ThrowableListener {
        fun success(resource: Resource)
        fun onError(throwable: Throwable)
    }
    interface NoErrorListener { fun success(resource: Resource) }
    private val success = Listener::class.java.getMethod("success", Resource::class.java)
    private val item = PlayerQualitySessions.ItemKey(123L, 456L)
    private class Callback : Listener {
        var onSuccess: (Resource) -> Unit = {}
        var onFailure: () -> Unit = {}
        override fun success(resource: Resource) = onSuccess(resource)
        override fun onError() = onFailure()
    }
    private class Fixture {
        val owner = Any()
        val params = Any()
        val sessions = PlayerQualitySessions(80)
        val token: PlayerQualitySessions.Token
        val lease: StoryQualityReparseGate.Lease
        init {
            val item = PlayerQualitySessions.ItemKey(123L, 456L)
            sessions.start(owner, item)
            token = requireNotNull(sessions.reserve(owner, item, PlayerQualitySessions.Admission.ALLOW,
                PlayerQualitySessions.Operation.REPARSE))
            lease = StoryQualityReparseGate.Lease(owner, params, item, token)
        }
        fun permitted(lease: StoryQualityReparseGate.Lease): Boolean = lease.owner()?.let { owner ->
            lease.params() === params && sessions.valid(lease.token, owner, lease.item, PlayerQualitySessions.Admission.ALLOW)
        } == true
    }

    @Test fun `success exposes weak lease only synchronously and duplicate terminal is ignored`() {
        val fixture = Fixture()
        var successes = 0
        var failures = 0
        lateinit var gate: StoryQualityReparseGate
        val callback = Callback().apply {
            onSuccess = {
                successes++
                assertSame(fixture.lease, gate.current())
                assertSame(it, gate.resource())
                assertSame(fixture.owner, gate.current()?.owner())
                assertSame(fixture.params, gate.current()?.params())
                assertSame(fixture.token, gate.current()?.token)
            }
        }
        gate = StoryQualityReparseGate(Listener::class.java, success, fixture::permitted, { failures++ })
        assertTrue(gate.available)
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        assertNull(gate.current())
        proxy.success(Resource())
        assertNull(gate.current())
        assertNull(gate.resource())
        proxy.success(Resource())
        proxy.onError()
        assertEquals(1, successes)
        assertEquals(0, failures)
    }

    @Test fun `manual fixed or auto discards only module reparse result`() {
        for (choice in PlayerQualitySessions.ManualChoice.entries) {
            val fixture = Fixture()
            var successes = 0
            var failures = 0
            val callback = Callback().apply { onSuccess = { successes++ } }
            val gate = StoryQualityReparseGate(Listener::class.java, success, fixture::permitted, { failures++ })
            val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
            fixture.sessions.manual(fixture.owner, item, choice)
            proxy.success(Resource())
            assertEquals(0, successes)
            assertEquals(1, failures)
            assertNull(gate.current())
            callback.success(Resource()) // 宿主原 listener 没有被全局改写。
            assertEquals(1, successes)
        }
    }

    @Test fun `new generation and denied revision reject old listener result`() {
        for (invalidate in listOf<(Fixture) -> Unit>(
            { it.sessions.start(it.owner, item) },
            { it.sessions.target(it.owner, item, PlayerQualitySessions.Admission.DENY) },
            { it.sessions.close(it.owner) })) {
            val fixture = Fixture()
            var successes = 0
            val gate = StoryQualityReparseGate(Listener::class.java, success, fixture::permitted, {})
            val proxy = requireNotNull(gate.wrap(Callback().apply { onSuccess = { successes++ } }, fixture.lease)) as Listener
            invalidate(fixture)
            proxy.success(Resource())
            assertEquals(0, successes)
            assertNull(gate.current())
        }
    }

    @Test fun `guard exception discards result and protects cleanup exception`() {
        val fixture = Fixture()
        var successes = 0
        var failures = 0
        val callback = Callback().apply { onSuccess = { successes++ } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { throw IllegalStateException("guard") }, {
            failures++
            throw IllegalStateException("cleanup")
        })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        proxy.success(Resource())
        proxy.success(Resource())
        assertEquals(0, successes)
        assertEquals(1, failures)
        assertNull(gate.current())
    }

    @Test fun `current error preserves delegate logging and invokes protected cleanup once`() {
        val fixture = Fixture()
        var errors = 0
        var failures = 0
        var guards = 0
        lateinit var gate: StoryQualityReparseGate
        val callback = Callback().apply {
            onFailure = {
                errors++
                assertNull(gate.current())
            }
        }
        gate = StoryQualityReparseGate(Listener::class.java, success, { guards++; true }, {
            failures++
            throw IllegalStateException("cleanup")
        })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        proxy.onError()
        proxy.onError()
        proxy.success(Resource())
        assertEquals(1, errors)
        assertEquals(1, failures)
        assertEquals(1, guards)
    }

    @Test fun `stale error cannot delegate into new state and cleanup remains protected once`() {
        val fixture = Fixture()
        var errors = 0
        var failures = 0
        val callback = Callback().apply { onFailure = { errors++ } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, fixture::permitted, {
            failures++
            throw IllegalStateException("cleanup")
        })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        fixture.sessions.start(fixture.owner, item)
        proxy.onError()
        proxy.onError()
        proxy.success(Resource())
        assertEquals(0, errors)
        assertEquals(1, failures)
        assertNull(gate.current())
        assertNull(gate.resource())
    }

    @Test fun `host delegate exception unwraps and restores current lease`() {
        val fixture = Fixture()
        val original = IllegalArgumentException("delegate")
        var failures = 0
        val callback = Callback().apply { onSuccess = { throw original } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { true }, { failures++ })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        try {
            proxy.success(Resource())
            fail("Original delegate exception must propagate")
        } catch (actual: IllegalArgumentException) {
            assertSame(original, actual)
        }
        assertNull(gate.current())
        assertEquals(1, failures)
        assertNull(gate.resource())
        proxy.success(Resource())
        assertEquals(1, failures)
    }

    @Test fun `error delegate exception survives failing cleanup without invocation wrapper`() {
        val fixture = Fixture()
        val original = IllegalStateException("original")
        val callback = Callback().apply { onFailure = { throw original } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { true }, { throw AssertionError("cleanup") })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        try {
            proxy.onError()
            fail("Original delegate exception must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(original, actual)
        }
        assertNull(gate.current())
    }

    @Test fun `nested successes restore parent lease even when child delegate throws`() {
        val first = Fixture()
        val second = Fixture()
        lateinit var gate: StoryQualityReparseGate
        val childError = IllegalStateException("child")
        val child = Callback().apply { onSuccess = {
            assertSame(second.lease, gate.current())
            assertSame(it, gate.resource())
            throw childError
        } }
        gate = StoryQualityReparseGate(Listener::class.java, success, { true }, {})
        val childProxy = requireNotNull(gate.wrap(child, second.lease)) as Listener
        val outer = Callback().apply {
            onSuccess = {
                assertSame(first.lease, gate.current())
                assertSame(it, gate.resource())
                try { childProxy.success(Resource()) } catch (actual: IllegalStateException) { assertSame(childError, actual) }
                assertSame(first.lease, gate.current())
                assertSame(it, gate.resource())
            }
        }
        (requireNotNull(gate.wrap(outer, first.lease)) as Listener).success(Resource())
        assertNull(gate.current())
        assertNull(gate.resource())
    }

    @Test fun `object methods use proxy identity without touching delegate or guards`() {
        val fixture = Fixture()
        val callback = object : Listener {
            override fun success(resource: Resource) = Unit
            override fun onError() = Unit
            override fun equals(other: Any?): Boolean = throw AssertionError("delegate equals")
            override fun hashCode(): Int = throw AssertionError("delegate hashCode")
            override fun toString(): String = throw AssertionError("delegate toString")
        }
        val gate = StoryQualityReparseGate(Listener::class.java, success,
            { throw AssertionError("guard") }, { throw AssertionError("cleanup") })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease))
        val another = requireNotNull(gate.wrap(callback, fixture.lease))
        assertTrue(proxy == proxy)
        assertFalse(proxy == another)
        assertFalse(proxy == callback)
        assertEquals(System.identityHashCode(proxy), proxy.hashCode())
        assertEquals("StoryQualityReparseListener", proxy.toString())
        assertNull(gate.current())
    }

    @Test fun `unsupported listener ABI and unrelated delegate are not wrapped`() {
        val fixture = Fixture()
        val unknown = object : UnknownListener {
            override fun success(resource: Resource) = Unit
            override fun onError() = Unit
            override fun progress(percent: Int) = Unit
        }
        val unknownGate = StoryQualityReparseGate(UnknownListener::class.java,
            UnknownListener::class.java.getMethod("success", Resource::class.java), { true }, {})
        assertFalse(unknownGate.available)
        assertNull(unknownGate.wrap(unknown, fixture.lease))
        val noError = object : NoErrorListener { override fun success(resource: Resource) = Unit }
        val noErrorGate = StoryQualityReparseGate(NoErrorListener::class.java,
            NoErrorListener::class.java.getMethod("success", Resource::class.java), { true }, {})
        assertNull(noErrorGate.wrap(noError, fixture.lease))
        assertNull(StoryQualityReparseGate(Listener::class.java, success, { true }, {}).wrap(Any(), fixture.lease))
        assertNull(StoryQualityReparseGate(Callback::class.java,
            Callback::class.java.getMethod("success", Resource::class.java), { true }, {}).wrap(Callback(), fixture.lease))
        val throwableGate = StoryQualityReparseGate(ThrowableListener::class.java,
            ThrowableListener::class.java.getMethod("success", Resource::class.java), { true }, {})
        assertFalse(throwableGate.available)
    }

    @Test fun `concurrent success and error accept only one terminal callback`() {
        val fixture = Fixture()
        val callbacks = AtomicInteger()
        val failures = AtomicInteger()
        val callback = Callback().apply {
            onSuccess = { callbacks.incrementAndGet() }
            onFailure = { callbacks.incrementAndGet() }
        }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { true }, { failures.incrementAndGet() })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        val start = CountDownLatch(1)
        val threads = List(8) { index -> Thread {
            start.await()
            if (index % 2 == 0) proxy.success(Resource()) else proxy.onError()
        }.also(Thread::start) }
        start.countDown()
        threads.forEach(Thread::join)
        assertEquals(1, callbacks.get())
        assertTrue(failures.get() in 0..1)
        assertNull(gate.current())
    }

    @Test fun `checked original exception is unwrapped inside invocation handler`() {
        val fixture = Fixture()
        val original = Exception("checked")
        val callback = Callback().apply { onSuccess = { throw original } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { true }, {})
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease))
        try {
            Proxy.getInvocationHandler(proxy).invoke(proxy, success, arrayOf(Resource()))
            fail("Original checked exception must propagate from handler")
        } catch (actual: Exception) {
            assertSame(original, actual)
        }
        assertNull(gate.current())
    }

    @Test fun `reflection argument failure drops module callback and cleans up only once`() {
        val fixture = Fixture()
        var errors = 0
        var failures = 0
        val callback = Callback().apply { onFailure = { errors++ } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { true }, { failures++ })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease))
        Proxy.getInvocationHandler(proxy).invoke(proxy,
            Listener::class.java.getMethod("onError"), arrayOf("invalid ABI"))
        assertEquals(0, errors)
        assertEquals(1, failures)
        assertNull(gate.current())
    }

    @Test fun `consumer reads latest params through lease instead of a captured seek position`() {
        class Params(var position: Long)
        val fixture = Fixture()
        val params = Params(10L)
        val lease = StoryQualityReparseGate.Lease(fixture.owner, params, item, fixture.token)
        lateinit var gate: StoryQualityReparseGate
        var consumed = -1L
        val callback = Callback().apply { onSuccess = { consumed = (gate.current()?.params() as Params).position } }
        gate = StoryQualityReparseGate(Listener::class.java, success, { true }, {})
        val proxy = requireNotNull(gate.wrap(callback, lease)) as Listener
        params.position = 99L
        proxy.success(Resource())
        assertEquals(99L, consumed)
        assertNull(gate.current())
    }
}
