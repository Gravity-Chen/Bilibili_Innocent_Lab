package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

/** 按整页接纳快照；容量不足时保留旧图与当前游标，绝不跳过一页内的剩余回复。 */
internal class ReplyTopologyNodeStore(
    private val key: ReplyTopologyThreadKey,
    private val maxEstimatedBytes: Long = 32L * 1024L * 1024L
) {
    init { require(maxEstimatedBytes > 0L) }
    private val nodes = ArrayList<ReplyTopologyNodeSnapshot>()
    private val firstIndexes = HashMap<Long, Int>()
    private val mergedIndexes = HashMap<Long, Int>()
    private var estimatedBytes = 0L

    data class AppendResult(val accepted: Boolean, val newUniqueNodes: Int = 0)

    val uniqueCount: Int get() = firstIndexes.size
    val replyCount: Int get() = uniqueCount - if (key.rootRpid in firstIndexes) 1 else 0

    fun append(page: List<ReplyTopologyNodeSnapshot>): AppendResult {
        val additions = ArrayList<ReplyTopologyNodeSnapshot>()
        val newIndexes = HashMap<Long, Int>()
        val newMergedIndexes = HashMap<Long, Int>()
        val replacements = HashMap<Int, ReplyTopologyNodeSnapshot>()
        var additionalBytes = 0L
        for (node in page) {
            require(node.rpid > 0L && (node.rpid == key.rootRpid || node.rootRpid == key.rootRpid)) {
                "Reply topology page contains a node outside the requested thread"
            }
            val mergedIndex = newMergedIndexes[node.rpid] ?: mergedIndexes[node.rpid]
            val existingIndex = mergedIndex ?: newIndexes[node.rpid] ?: firstIndexes[node.rpid]
            if (existingIndex == null) {
                newIndexes[node.rpid] = nodes.size + additions.size
                additions += node
                additionalBytes += estimate(node)
            } else {
                val old = if (existingIndex < nodes.size) replacements[existingIndex] ?: nodes[existingIndex]
                    else additions[existingIndex - nodes.size]
                val combined = ReplyTopologyGraphBuilder.mergeDuplicateSnapshots(old, node)
                if (mergedIndex == null) {
                    newMergedIndexes[node.rpid] = nodes.size + additions.size
                    additions += combined
                    additionalBytes += estimate(combined)
                } else {
                    if (existingIndex < nodes.size) replacements[existingIndex] = combined
                    else additions[existingIndex - nodes.size] = combined
                    additionalBytes += estimate(combined) - estimate(old)
                }
            }
            if (additionalBytes > maxEstimatedBytes - estimatedBytes) return AppendResult(false)
        }
        replacements.forEach { (index, node) -> nodes[index] = node }
        nodes.addAll(additions)
        firstIndexes.putAll(newIndexes)
        mergedIndexes.putAll(newMergedIndexes)
        estimatedBytes += additionalBytes
        return AppendResult(true, newIndexes.size)
    }

    // 同时预留索引、构图工作集、新旧图与字符串开销；不是宿主堆用量的精确测量。
    private fun estimate(node: ReplyTopologyNodeSnapshot): Long = 2_048L +
        2L * (node.authorName.length.toLong() + (node.repliedAuthorName?.length ?: 0) + node.messagePreview.length)

    fun snapshot(): List<ReplyTopologyNodeSnapshot> = nodes.toList()

    fun clear() {
        nodes.clear()
        firstIndexes.clear()
        mergedIndexes.clear()
        estimatedBytes = 0L
    }
}
