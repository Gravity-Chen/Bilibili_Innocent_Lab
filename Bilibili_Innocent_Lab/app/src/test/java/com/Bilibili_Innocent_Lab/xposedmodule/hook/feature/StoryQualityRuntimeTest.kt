package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Admission
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.ItemKey
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.ManualChoice
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Operation
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class StoryQualityRuntimeTest {
    private val item = ItemKey(123L, 456L)

    @Test fun `binding alone and unknown owner never create playback target`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val handler = Any()
        runtime.bind(owner, handler, null)
        assertSame(owner, runtime.ownerFor(handler))
        runtime.scoped(owner, item, Admission.ALLOW) {
            assertTrue(runtime.inStory())
            assertNull(runtime.target())
        }
        runtime.scoped(Any(), item, Admission.ALLOW) {
            assertTrue(runtime.inStory())
            assertNull(runtime.target())
        }
        assertFalse(runtime.inStory())
    }

    @Test fun `scope target and parameters are available only during current call`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val params = Any()
        runtime.start(owner, item)
        val scope = runtime.enter(owner, item, Admission.ALLOW, params)
        assertEquals(80, runtime.target())
        assertSame(params, runtime.params())
        assertTrue(runtime.leave(scope))
        assertNull(scope.owner)
        assertNull(scope.parameters)
        assertNull(scope.stackIdentity)
        assertFalse(runtime.inStory())
        assertNull(runtime.target())
        assertNull(runtime.params())
        assertFalse(runtime.leave(scope))
    }

    @Test fun `unknown nested scope suppresses global fallback until its own return`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        runtime.scoped(owner, item, Admission.ALLOW) {
            assertEquals(80, runtime.target())
            runtime.scoped(null, null, Admission.UNKNOWN) {
                assertTrue(runtime.inStory())
                assertNull(runtime.target())
            }
            assertEquals(80, runtime.target())
        }
        assertFalse(runtime.inStory())
    }

    @Test fun `original exception and suspended return both remove synchronous scope`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val failure = IllegalStateException("fixture")
        try {
            runtime.scoped<Unit>(owner, item, Admission.ALLOW) { throw failure }
            fail("Original exception must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertFalse(runtime.inStory())
        assertSame(COROUTINE_SUSPENDED, runtime.scoped(owner, item, Admission.ALLOW) { COROUTINE_SUSPENDED })
        assertFalse(runtime.inStory())
    }

    @Test fun `nested owner scopes restore previous frame without sharing manual state`() {
        val runtime = StoryQualityRuntime(80)
        val first = Any()
        val second = Any()
        runtime.start(first, item)
        runtime.start(second, item)
        runtime.scoped(first, item, Admission.ALLOW) {
            runtime.scoped(second, item, Admission.ALLOW) {
                assertTrue(runtime.manual(second, item, ManualChoice.AUTO))
                assertNull(runtime.target())
                assertTrue(runtime.inStory())
            }
            assertEquals(80, runtime.target())
        }
    }

    @Test fun `manual auto and fixed revoke pending result before asynchronous original starts`() {
        for (choice in ManualChoice.entries) {
            val runtime = StoryQualityRuntime(80)
            val owner = Any()
            runtime.start(owner, item)
            val pending = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
            runtime.scoped(owner, item, Admission.ALLOW) {
                assertEquals(80, runtime.target())
                assertTrue(runtime.manual(owner, item, choice))
                val originalStarted = {
                    assertFalse(runtime.sessions.valid(pending, owner, item, Admission.ALLOW))
                    assertNull(runtime.target())
                    assertTrue(runtime.inStory())
                }
                originalStarted()
            }
            assertFalse(runtime.sessions.finish(pending, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        }
    }

    @Test fun `same item new generation cannot borrow old synchronous frame`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val old = runtime.enter(owner, item, Admission.ALLOW)
        val pending = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        runtime.start(owner, item)
        assertNull(runtime.target())
        assertTrue(runtime.inStory())
        runtime.scoped(owner, item, Admission.ALLOW) { assertEquals(80, runtime.target()) }
        assertNull(runtime.target())
        assertFalse(runtime.sessions.valid(pending, owner, item, Admission.ALLOW))
        assertTrue(runtime.leave(old))
        assertFalse(runtime.inStory())
    }

    @Test fun `nested denied admission does not revive older allowed frame`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        runtime.scoped(owner, item, Admission.ALLOW) {
            assertEquals(80, runtime.target())
            runtime.scoped(owner, item, Admission.DENY) { assertNull(runtime.target()) }
            assertNull(runtime.target())
            runtime.scoped(owner, item, Admission.ALLOW) { assertEquals(80, runtime.target()) }
            assertNull(runtime.target())
        }
    }

    @Test fun `entering blocked branch revokes pending work even without a target read`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val pending = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        val scope = runtime.enter(owner, item, Admission.DENY)
        assertFalse(runtime.sessions.valid(pending, owner, item, Admission.ALLOW))
        assertTrue(runtime.leave(scope))
    }

    @Test fun `closed owner loses lookup and frame stays Story without target`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val handler = Any()
        val provider = Any()
        runtime.bind(owner, handler, provider)
        runtime.start(owner, item)
        runtime.scoped(owner, item, Admission.ALLOW) {
            runtime.close(owner)
            assertNull(runtime.ownerFor(owner))
            assertNull(runtime.ownerFor(handler))
            assertNull(runtime.ownerFor(provider))
            assertTrue(runtime.inStory())
            assertNull(runtime.target())
        }
    }

    @Test fun `component identity collision returns missing without conflating owners`() {
        class EqualOwner {
            override fun equals(other: Any?) = other is EqualOwner
            override fun hashCode() = 1
        }
        val runtime = StoryQualityRuntime(80)
        val first = EqualOwner()
        val second = EqualOwner()
        val shared = Any()
        val firstProvider = Any()
        val secondProvider = Any()
        runtime.bind(first, shared, firstProvider)
        runtime.bind(second, shared, secondProvider)
        assertNull(runtime.ownerFor(shared))
        assertSame(first, runtime.ownerFor(firstProvider))
        assertSame(second, runtime.ownerFor(secondProvider))
        runtime.close(first)
        assertSame(second, runtime.ownerFor(shared))
    }

    @Test fun `binding capacity invalidates evicted owner and pending session token`() {
        val runtime = StoryQualityRuntime(80)
        val owners = List(17) { Any() }
        runtime.start(owners.first(), item)
        val pending = requireNotNull(runtime.sessions.reserve(owners.first(), item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        val oldScope = runtime.enter(owners.first(), item, Admission.ALLOW)
        owners.drop(1).forEach { runtime.start(it, item) }
        assertNull(runtime.ownerFor(owners.first()))
        assertNull(runtime.target())
        assertTrue(runtime.inStory())
        assertFalse(runtime.sessions.valid(pending, owners.first(), item, Admission.ALLOW))
        assertSame(owners.last(), runtime.ownerFor(owners.last()))
        assertTrue(runtime.leave(oldScope))
    }

    @Test fun `overflow keeps Story suppression and duplicate leave cannot consume another frame`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val scopes = List(16) { runtime.enter(owner, item, Admission.ALLOW, Any()) }
        assertEquals(80, runtime.target())
        val firstOverflow = runtime.enter(owner, item, Admission.ALLOW, Any())
        val secondOverflow = runtime.enter(owner, item, Admission.ALLOW, Any())
        assertNull(firstOverflow.owner)
        assertNull(firstOverflow.parameters)
        assertTrue(runtime.inStory())
        assertNull(runtime.target())
        assertNull(runtime.params())
        assertTrue(runtime.leave(firstOverflow))
        assertFalse(runtime.leave(firstOverflow))
        assertNull(runtime.target())
        assertTrue(runtime.leave(secondOverflow))
        assertEquals(80, runtime.target())
        scopes.asReversed().forEach { assertTrue(runtime.leave(it)) }
        assertFalse(runtime.inStory())
    }

    @Test fun `out of order and old leaves never clear a newer current frame`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val outer = runtime.enter(owner, item, Admission.ALLOW)
        val params = Any()
        val inner = runtime.enter(owner, item, Admission.ALLOW, params)
        assertTrue(runtime.leave(outer))
        assertFalse(runtime.leave(outer))
        assertSame(params, runtime.params())
        assertEquals(80, runtime.target())
        assertTrue(runtime.leave(inner))
        val fresh = runtime.enter(owner, item, Admission.ALLOW)
        assertFalse(runtime.leave(outer))
        assertTrue(runtime.inStory())
        assertTrue(runtime.leave(fresh))
    }

    @Test fun `another thread cannot close original frame or inherit Story context`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val scope = runtime.enter(owner, item, Admission.ALLOW)
        val untouched = AtomicBoolean(false)
        Thread {
            untouched.set(!runtime.inStory() && runtime.target() == null && !runtime.leave(scope))
        }.also { it.start(); it.join() }
        assertTrue(untouched.get())
        assertEquals(80, runtime.target())
        assertTrue(runtime.leave(scope))
    }

    @Test fun `request completion keeps target but repeated reparse remains bounded`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        runtime.scoped(owner, item, Admission.ALLOW) {
            val request = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
            assertTrue(runtime.sessions.finish(request, owner, item, Admission.ALLOW, Outcome.COMPLETED))
            assertEquals(80, runtime.target())
            val reparse = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
            assertTrue(runtime.sessions.finish(reparse, owner, item, Admission.ALLOW, Outcome.FAILED))
            assertNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
            assertEquals(80, runtime.target())
        }
    }

    @Test fun `follow host setting never produces a Story override`() {
        val runtime = StoryQualityRuntime(0)
        val owner = Any()
        assertNull(runtime.start(owner, item))
        runtime.scoped(owner, item, Admission.ALLOW) { assertNull(runtime.target()) }
        assertFalse(runtime.inStory())
    }

    @Test fun `duplicate handler selection preserves manual choice and settled playback`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val first = runtime.select(owner, item, 0)
        assertTrue(runtime.manual(owner, item, ManualChoice.AUTO))
        assertEquals(first, runtime.select(owner, item, 0))
        runtime.scoped(owner, item, Admission.ALLOW) { assertNull(runtime.target()) }
        val second = runtime.select(owner, item, 1)
        assertTrue(first != second)
        val render = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.PLAYBACK_SETTLE))
        assertTrue(runtime.sessions.settle(render, owner, item, Admission.ALLOW))
        assertEquals(second, runtime.select(owner, item, 1))
        runtime.scoped(owner, item, Admission.ALLOW) { assertNull(runtime.target()) }
    }

    @Test fun `same ID different index and different item same index start new generations`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val first = runtime.select(owner, item, 0)
        val pending = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        val second = runtime.select(owner, item, 1)
        assertTrue(first != second)
        assertFalse(runtime.sessions.valid(pending, owner, item, Admission.ALLOW))
        runtime.scoped(owner, item, Admission.ALLOW) { assertEquals(80, runtime.target()) }
        val another = ItemKey(123L, 789L)
        assertTrue(second != runtime.select(owner, another, 1))
        runtime.scoped(owner, another, Admission.ALLOW) { assertEquals(80, runtime.target()) }
    }

    @Test fun `invalid selection closes mapping and pending work`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val handler = Any()
        runtime.bind(owner, handler, null)
        runtime.select(owner, item, 0)
        val pending = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        assertNull(runtime.select(owner, item, -1))
        assertNull(runtime.ownerFor(handler))
        assertFalse(runtime.sessions.valid(pending, owner, item, Admission.ALLOW))
        assertNull(runtime.select(owner, null, 0))
    }
}
