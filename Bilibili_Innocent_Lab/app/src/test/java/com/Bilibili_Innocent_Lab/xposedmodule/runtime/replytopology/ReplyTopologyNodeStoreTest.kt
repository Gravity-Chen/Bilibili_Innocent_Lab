package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import org.junit.Assert.*
import org.junit.Test

class ReplyTopologyNodeStoreTest {
    private val key = ReplyTopologyThreadKey(1L, 1L, 100L)
    private fun node(id: Long, text: String = "reply") = ReplyTopologyNodeSnapshot.fromRaw(
        id, if (id == key.rootRpid) 0L else key.rootRpid,
        if (id == key.rootRpid) 0L else key.rootRpid, message = text
    )

    @Test fun aRejectedPageCannotPartlyMutateTheStore() {
        val store = ReplyTopologyNodeStore(key, maxEstimatedBytes = 7_000L)
        assertTrue(store.append(listOf(node(100L), node(101L))).accepted)
        assertFalse(store.append(listOf(node(102L), node(103L))).accepted)
        assertEquals(2, store.uniqueCount)
        assertEquals(1, store.replyCount)
        assertEquals(listOf(100L, 101L), store.snapshot().map { it.rpid })
        assertTrue(store.append(listOf(node(102L))).accepted)
    }

    @Test fun duplicateSnapshotsAreBoundedAndDoNotCountAsNewReplies() {
        val store = ReplyTopologyNodeStore(key)
        store.append(listOf(node(100L), node(101L)))
        repeat(100) { assertEquals(0, store.append(listOf(node(101L, "more complete"))).newUniqueNodes) }
        assertEquals(1, store.replyCount)
        assertEquals(3, store.snapshot().size)
        val graph = ReplyTopologyGraphBuilder.build(key, store.snapshot())
        assertTrue(graph.messagePreviews.contains("more complete"))
    }

    @Test fun duplicateAndFreshSnapshotsShareTheMemoryBudget() {
        val store = ReplyTopologyNodeStore(key, maxEstimatedBytes = 4_200L)
        store.append(listOf(node(100L), node(101L)))
        assertFalse(store.append(listOf(node(101L, "expanded"))).accepted)
        assertEquals(2, store.snapshot().size)
        store.clear()
        assertEquals(0, store.uniqueCount)
        assertTrue(store.append(listOf(node(100L))).accepted)
    }

    @Test fun differentThreadNodesCannotPolluteTheLoadedCount() {
        val store = ReplyTopologyNodeStore(key)
        assertThrows(IllegalArgumentException::class.java) {
            store.append(listOf(node(101L), node(102L).copy(rootRpid = 999L)))
        }
        assertEquals(0, store.uniqueCount)
    }

    @Test fun moreThanFiveThousandRepliesRemainAvailableForTheTree() {
        val store = ReplyTopologyNodeStore(key)
        assertTrue(store.append((100L..6_100L).map { node(it) }).accepted)
        assertEquals(6_000, store.replyCount)
        assertEquals(6_001, ReplyTopologyGraphBuilder.build(key, store.snapshot()).size)
    }

    @Test fun laterPagesCanUpgradeTheSameReplyWithoutAddingUnboundedSnapshots() {
        val store = ReplyTopologyNodeStore(key)
        store.append(listOf(node(100L), node(101L, "")))
        store.append(listOf(node(101L, "")))
        val richer = node(101L, "third page complete content").copy(authorName = "Alice", ctime = 42L)
        assertTrue(store.append(listOf(richer)).accepted)
        repeat(100) { store.append(listOf(node(101L, ""))) }
        assertEquals(3, store.snapshot().size)
        assertEquals(1, store.replyCount)
        val graph = ReplyTopologyGraphBuilder.build(key, store.snapshot())
        val index = graph.rpids.indexOf(101L)
        assertEquals("Alice", graph.authorNames[index])
        assertEquals("third page complete content", graph.messagePreviews[index])
        assertEquals(42L, graph.ctimes[index])
    }

    @Test fun rejectedReplacementAndNewReplyLeaveTheWholePreviousPageUnchanged() {
        val store = ReplyTopologyNodeStore(key, maxEstimatedBytes = 6_300L)
        store.append(listOf(node(100L), node(101L, "old")))
        store.append(listOf(node(101L, "short")))
        val before = store.snapshot()
        assertFalse(store.append(listOf(node(101L, "x".repeat(140)), node(102L))).accepted)
        assertEquals(before, store.snapshot())
        assertEquals(1, store.replyCount)
        assertTrue(store.append(listOf(node(101L, "slightly longer"))).accepted)
    }
}
