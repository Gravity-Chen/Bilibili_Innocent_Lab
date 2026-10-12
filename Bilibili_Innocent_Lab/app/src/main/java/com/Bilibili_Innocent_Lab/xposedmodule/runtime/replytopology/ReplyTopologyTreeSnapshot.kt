package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import java.util.concurrent.CancellationException

internal data class ReplyTopologyTreeSnapshot(
    val nodes: List<ReplyTopologyNodeSnapshot>,
    val truncated: Boolean
)

/** 按迭代器逐个访问，宽树不批量入栈；无效项同样消耗预算，超限明确标为不完整。 */
internal fun snapshotReplyTopologyTree(
    root: Any,
    limit: Int,
    children: (Any) -> List<*>?,
    snapshot: (Any) -> ReplyTopologyNodeSnapshot?
): ReplyTopologyTreeSnapshot {
    require(limit > 0)
    val result = ArrayList<ReplyTopologyNodeSnapshot>(minOf(limit, 32))
    val stack = ArrayDeque<Iterator<*>>()
    stack.addLast(listOf(root).iterator())
    var visited = 0
    var incomplete = false
    while (stack.isNotEmpty()) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Reply tree snapshot cancelled")
        val next = stack.last()
        val current = try {
            if (!next.hasNext()) { stack.removeLast(); continue }
            if (visited >= limit) return ReplyTopologyTreeSnapshot(result, truncated = true)
            visited++
            next.next()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            incomplete = true
            stack.removeLast()
            continue
        }
        if (current == null) { incomplete = true; continue }
        try {
            val node = snapshot(current)
            if (node == null) incomplete = true else result.add(node)
            children(current)?.takeIf { it.isNotEmpty() }?.let { stack.addLast(it.iterator()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            incomplete = true
        }
    }
    return ReplyTopologyTreeSnapshot(result, truncated = incomplete)
}
