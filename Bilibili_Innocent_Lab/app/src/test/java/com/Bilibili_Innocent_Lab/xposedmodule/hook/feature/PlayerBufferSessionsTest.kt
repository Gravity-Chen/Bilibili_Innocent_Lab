package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class PlayerBufferSessionsTest {
    private class SameHash {
        override fun equals(other: Any?) = other is SameHash
        override fun hashCode() = 1
    }
    @Test fun `host equality cannot approve another item`() {
        val store = PlayerBufferSessions()
        val first = SameHash()
        val other = SameHash()
        val ticket = PlayerBufferSessions.Ticket()
        val entry = store.register(first, ticket, null, null)
        assertTrue(store.hasPlayIntent(entry))
        assertFalse(store.current(entry))
        assertNull(store.get(other))
        assertFalse(store.commit(ticket, other))
        assertFalse(store.hasPlayIntent(entry))
    }
    @Test fun `only committed current item is admitted for disk preload`() {
        val store = PlayerBufferSessions()
        val first = Any()
        val firstTicket = PlayerBufferSessions.Ticket()
        val firstEntry = store.register(first, firstTicket, null, null)
        val player = Any()
        assertFalse(store.current(firstEntry))
        store.select(player, first)
        assertTrue(store.commit(firstTicket, first))
        assertTrue(store.current(firstEntry))
        val next = Any()
        val nextTicket = PlayerBufferSessions.Ticket()
        val nextEntry = store.register(next, nextTicket, null, null)
        store.select(player, next)
        assertTrue(store.commit(nextTicket, next))
        assertFalse(store.current(firstEntry))
        assertFalse(store.hasPlayIntent(firstEntry))
        assertTrue(store.current(nextEntry))
        store.remove(next)
        assertFalse(store.current(nextEntry))
        assertFalse(store.hasPlayIntent(nextEntry))
    }
    @Test fun `cancelled or rejected preparation cannot commit later`() {
        val store = PlayerBufferSessions()
        val item = Any()
        val ticket = PlayerBufferSessions.Ticket()
        val entry = store.register(item, ticket, null, null)
        store.cancel(ticket)
        assertFalse(store.commit(ticket, item))
        assertFalse(store.current(entry))
        assertFalse(store.hasPlayIntent(entry))
    }
    @Test fun `two creations sharing one ticket cannot authorize the non current sibling`() {
        val store = PlayerBufferSessions()
        val first = Any()
        val sibling = Any()
        val ticket = PlayerBufferSessions.Ticket()
        val firstEntry = store.register(first, ticket, null, null)
        val siblingEntry = store.register(sibling, ticket, null, null)
        val player = Any()
        store.select(player, first)
        assertTrue(store.commit(ticket, first))
        assertTrue(store.current(firstEntry))
        assertFalse(store.current(siblingEntry))
        assertFalse(store.hasPlayIntent(siblingEntry))
        store.remove(sibling)
        assertTrue(store.current(firstEntry))
        store.select(player, null)
        assertFalse(store.current(firstEntry))
    }
    @Test fun `business current readback without native selection cannot authorize full preload`() {
        val store = PlayerBufferSessions()
        val item = Any()
        val ticket = PlayerBufferSessions.Ticket()
        val entry = store.register(item, ticket, null, null)
        assertFalse(store.commit(ticket, item))
        assertFalse(store.current(entry))
    }
    @Test fun `bounded weak registry evicts stale preparation even when host retains items`() {
        val store = PlayerBufferSessions()
        val retained = List(17) { Any() }
        val tickets = List(17) { PlayerBufferSessions.Ticket() }
        retained.forEachIndexed { i, item -> store.register(item, tickets[i], null, null) }
        assertNull(store.get(retained[0]))
        assertFalse(store.commit(tickets[0], retained[0]))
        assertNotNull(store.get(retained.last()))
    }
}
