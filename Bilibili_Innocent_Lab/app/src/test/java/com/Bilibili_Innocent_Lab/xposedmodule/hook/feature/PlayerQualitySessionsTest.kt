package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Admission
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.ItemKey
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.ManualChoice
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Operation
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQualitySessionsTest {
    private val item = ItemKey(123L, 456L)

    @Test fun `follow host and unknown quality never create intervention`() {
        for (qn in listOf(0, -1, 999)) {
            val sessions = PlayerQualitySessions(qn)
            val owner = Any()
            assertNull(sessions.start(owner, item))
            assertNull(sessions.target(owner, item, Admission.ALLOW))
            assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        }
    }

    @Test fun `pipeline shares target after request and resource operations complete`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        for (operation in listOf(Operation.REQUEST_TARGET, Operation.RESOURCE_SELECT, Operation.REPARSE)) {
            repeat(3) { assertEquals(80, sessions.target(owner, item, Admission.ALLOW)) }
            val token = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, operation))
            assertEquals(80, token.qn)
            assertTrue(sessions.valid(token, owner, item, Admission.ALLOW))
            assertTrue(sessions.finish(token, owner, item, Admission.ALLOW, Outcome.COMPLETED))
            assertFalse(sessions.valid(token, owner, item, Admission.ALLOW))
            assertNull(sessions.reserve(owner, item, Admission.ALLOW, operation))
            assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
        }
        val render = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.PLAYBACK_SETTLE))
        assertTrue(sessions.settle(render, owner, item, Admission.ALLOW))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
        assertFalse(sessions.settle(render, owner, item, Admission.ALLOW))
    }

    @Test fun `owner identity ignores host equals and hash collisions`() {
        class EqualOwner {
            override fun equals(other: Any?) = other is EqualOwner
            override fun hashCode() = 1
        }
        val first = EqualOwner()
        val second = EqualOwner()
        val sessions = PlayerQualitySessions(80)
        sessions.start(first, item)
        sessions.start(second, item)
        val firstToken = requireNotNull(sessions.reserve(first, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        val secondToken = requireNotNull(sessions.reserve(second, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertFalse(sessions.valid(firstToken, second, item, Admission.ALLOW))
        assertTrue(sessions.valid(secondToken, second, item, Admission.ALLOW))
        assertTrue(sessions.manual(first, item, ManualChoice.FIXED))
        assertNull(sessions.target(first, item, Admission.ALLOW))
        assertEquals(80, sessions.target(second, item, Admission.ALLOW))
        assertTrue(sessions.valid(secondToken, second, item, Admission.ALLOW))
    }

    @Test fun `same item new playback revokes old generation without ending new one`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        val first = requireNotNull(sessions.start(owner, item))
        val old = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        val second = requireNotNull(sessions.start(owner, item))
        val current = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertNotEquals(first, second)
        assertFalse(sessions.finish(old, owner, item, Admission.UNKNOWN, Outcome.COMPLETED))
        assertFalse(sessions.settle(old, owner, item, Admission.ALLOW))
        assertTrue(sessions.valid(current, owner, item, Admission.ALLOW))
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
    }

    @Test fun `manual fixed and auto synchronously revoke pending asynchronous work`() {
        for (choice in ManualChoice.entries) {
            val sessions = PlayerQualitySessions(80)
            val owner = Any()
            sessions.start(owner, item)
            val pending = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
            assertTrue(sessions.manual(owner, item, choice))
            assertFalse(sessions.valid(pending, owner, item, Admission.ALLOW))
            assertFalse(sessions.settle(pending, owner, item, Admission.ALLOW))
            assertFalse(sessions.finish(pending, owner, item, Admission.ALLOW, Outcome.COMPLETED))
            assertNull(sessions.target(owner, item, Admission.ALLOW))
            assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
            sessions.start(owner, item)
            assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
        }
    }

    @Test fun `changing item after reservation cannot revive old work on return`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val pending = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        assertFalse(sessions.valid(pending, owner, ItemKey(123L, 789L), Admission.ALLOW))
        assertFalse(sessions.valid(pending, owner, item, Admission.ALLOW))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
        sessions.start(owner, item)
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
    }

    @Test fun `unknown or denied admission revokes token permanently without refunding budget`() {
        for (blocked in listOf(Admission.UNKNOWN, Admission.DENY)) {
            val sessions = PlayerQualitySessions(80)
            val owner = Any()
            sessions.start(owner, item)
            val pending = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
            assertFalse(sessions.valid(pending, owner, item, blocked))
            assertNull(sessions.target(owner, item, blocked))
            assertFalse(sessions.valid(pending, owner, item, Admission.ALLOW))
            assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
            val fresh = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
            assertNotEquals(pending.revision, fresh.revision)
            assertFalse(sessions.finish(pending, owner, item, Admission.UNKNOWN, Outcome.COMPLETED))
            assertTrue(sessions.valid(fresh, owner, item, Admission.ALLOW))
        }
    }

    @Test fun `blocked default read invalidates previously reserved work`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val pending = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        assertNull(sessions.target(owner, item, Admission.DENY))
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
        assertFalse(sessions.valid(pending, owner, item, Admission.ALLOW))
        assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
    }

    @Test fun `old request cannot settle a new resource revision after denied admission`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val request = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertNull(sessions.target(owner, item, Admission.DENY))
        val resource = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        assertNotEquals(request.revision, resource.revision)
        assertFalse(sessions.settle(request, owner, item, Admission.ALLOW))
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
        assertTrue(sessions.valid(resource, owner, item, Admission.ALLOW))
        assertTrue(sessions.settle(resource, owner, item, Admission.ALLOW))
    }

    @Test fun `old resource cannot settle or alter admission of newer pending revision`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val resource = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        assertFalse(sessions.valid(resource, owner, item, Admission.UNKNOWN))
        val reparse = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        assertFalse(sessions.settle(resource, owner, item, Admission.DENY))
        assertTrue(sessions.valid(reparse, owner, item, Admission.ALLOW))
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
    }

    @Test fun `request success and finished resource cannot replace fresh render lease`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val request = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertFalse(sessions.settle(request, owner, item, Admission.ALLOW))
        assertTrue(sessions.finish(request, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        assertFalse(sessions.settle(request, owner, item, Admission.ALLOW))
        val resource = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        assertTrue(sessions.finish(resource, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        assertFalse(sessions.settle(resource, owner, item, Admission.ALLOW))
        val render = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.PLAYBACK_SETTLE))
        assertTrue(sessions.settle(render, owner, item, Admission.ALLOW))
        assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.PLAYBACK_SETTLE))
    }

    @Test fun `failed operation remains spent and does not consume another pipeline operation`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val failed = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertTrue(sessions.finish(failed, owner, item, Admission.ALLOW, Outcome.FAILED))
        assertFalse(sessions.finish(failed, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
        assertTrue(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT) != null)
    }

    @Test fun `close revokes work and target reads never silently create a new session`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val old = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        sessions.close(owner)
        assertFalse(sessions.valid(old, owner, item, Admission.ALLOW))
        assertFalse(sessions.finish(old, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
        assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        sessions.start(owner, item)
        val current = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        assertFalse(sessions.valid(old, owner, item, Admission.ALLOW))
        assertTrue(sessions.valid(current, owner, item, Admission.ALLOW))
    }

    @Test fun `unknown item invalidates active session and invalid start preserves no old target`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val old = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertNull(sessions.target(owner, null, Admission.ALLOW))
        assertFalse(sessions.valid(old, owner, item, Admission.ALLOW))
        sessions.start(owner, item)
        assertNull(sessions.start(owner, ItemKey(0L, 456L)))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
    }

    @Test fun `sixteen owner bound evicts oldest session and revokes its token`() {
        val sessions = PlayerQualitySessions(80)
        val owners = List(17) { Any() }
        sessions.start(owners.first(), item)
        val oldest = requireNotNull(sessions.reserve(owners.first(), item, Admission.ALLOW, Operation.REQUEST_TARGET))
        owners.drop(1).forEach { sessions.start(it, item) }
        assertFalse(sessions.valid(oldest, owners.first(), item, Admission.ALLOW))
        assertNull(sessions.target(owners.first(), item, Admission.ALLOW))
        owners.drop(1).forEach { assertEquals(80, sessions.target(it, item, Admission.ALLOW)) }
    }

    @Test fun `restarting an existing owner counts once and refreshes eviction order`() {
        val sessions = PlayerQualitySessions(80)
        val owners = List(17) { Any() }
        owners.take(16).forEach { sessions.start(it, item) }
        sessions.start(owners.first(), item)
        sessions.start(owners.last(), item)
        assertEquals(80, sessions.target(owners.first(), item, Admission.ALLOW))
        assertNull(sessions.target(owners[1], item, Admission.ALLOW))
        assertEquals(80, sessions.target(owners.last(), item, Admission.ALLOW))
    }

    @Test fun `settled playback revokes pending operation and preserves user control`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val pending = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        assertTrue(sessions.settle(pending, owner, item, Admission.ALLOW))
        assertFalse(sessions.valid(pending, owner, item, Admission.ALLOW))
        assertFalse(sessions.finish(pending, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
        assertTrue(sessions.manual(owner, item, ManualChoice.AUTO))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
    }
}
