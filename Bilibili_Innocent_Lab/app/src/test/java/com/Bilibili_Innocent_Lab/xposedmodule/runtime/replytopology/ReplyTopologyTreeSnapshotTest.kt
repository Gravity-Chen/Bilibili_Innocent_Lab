package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ReplyTopologyTreeSnapshotTest {
    private class Node(val id: Long, val children: List<Any?> = emptyList())
    private fun read(root: Node, limit: Int) = snapshotReplyTopologyTree(root, limit,
        children = { (it as? Node)?.children },
        snapshot = { (it as? Node)?.let { n -> ReplyTopologyNodeSnapshot.fromRaw(n.id, 1L, 1L) } })

    @Test fun widePagesKeepEverySiblingWithoutSchedulingOnlyTheFirstNinetySix() {
        val root = Node(1L, (2L..202L).map { Node(it) })
        val result = read(root, 4_096)
        assertEquals(202, result.nodes.size)
        assertFalse(result.truncated)
        assertEquals(202L, result.nodes.last().rpid)
    }

    @Test fun exactlyFullBudgetIsCompleteOnlyWhenThereIsNoUnvisitedReply() {
        assertFalse(read(Node(1L, listOf(Node(2L))), 2).truncated)
        val result = read(Node(1L, listOf(Node(2L), Node(3L))), 2)
        assertTrue(result.truncated)
        assertEquals(2, result.nodes.size)
    }

    @Test fun invalidChildrenStillConsumeTheVisitBudget() {
        val result = read(Node(1L, listOf(null, "invalid", Node(2L))), 3)
        assertTrue(result.truncated)
        assertEquals(1, result.nodes.size)
    }

    @Test fun deepTreesDoNotUseTheJvmCallStack() {
        var root = Node(5_000L)
        for (id in 4_999L downTo 1L) root = Node(id, listOf(root))
        val result = read(root, 6_000)
        assertFalse(result.truncated)
        assertEquals(5_000, result.nodes.size)
    }

    @Test fun closingTheSessionInterruptsSnapshotWork() {
        Thread.currentThread().interrupt()
        try { assertThrows(CancellationException::class.java) { read(Node(1L), 4) } }
        finally { Thread.interrupted() }
    }

    @Test fun skippedInvalidNodesCannotProduceACompleteSnapshot() {
        val result = read(Node(1L, listOf(null, "invalid", Node(2L))), 10)
        assertEquals(listOf(1L, 2L), result.nodes.map { it.rpid })
        assertTrue(result.truncated)
    }

    @Test fun failingChildReadKeepsTheRootAndOtherReadableSiblings() {
        val root = Node(1L, listOf(Node(2L), Node(3L)))
        val result = snapshotReplyTopologyTree(root, 10,
            children = { if ((it as Node).id == 2L) error("getter failed") else it.children },
            snapshot = { ReplyTopologyNodeSnapshot.fromRaw((it as Node).id, 1L, 1L) })
        assertEquals(listOf(1L, 2L, 3L), result.nodes.map { it.rpid })
        assertTrue(result.truncated)
    }

    @Test fun failingIteratorPreservesTheAlreadyReadNodesAsPartial() {
        val children = object : AbstractList<Any?>() {
            override val size = 2
            override fun get(index: Int): Any = if (index == 0) Node(2L) else error("iterator failed")
        }
        val result = read(Node(1L, children), 10)
        assertEquals(listOf(1L, 2L), result.nodes.map { it.rpid })
        assertTrue(result.truncated)
    }

    @Test fun cancellationDuringAGetterStillStopsTraversal() {
        assertThrows(CancellationException::class.java) {
            snapshotReplyTopologyTree(Node(1L), 10,
                children = { throw CancellationException("closed") },
                snapshot = { ReplyTopologyNodeSnapshot.fromRaw(1L, 1L, 1L) })
        }
    }
}
