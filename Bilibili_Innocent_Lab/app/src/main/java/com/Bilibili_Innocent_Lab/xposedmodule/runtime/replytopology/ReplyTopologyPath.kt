package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

/** 路径聚焦只生成显示掩码，不修改真实深度、父子关系或主面板的筛选。 */
internal object ReplyTopologyPath {
    data class Selection(val indexes: IntArray, val baseDepth: Int, val omittedAncestors: Int)

    fun resolve(graph: ReplyTopologyGraph, rpid: Long, ancestorLimit: Int = 6): Selection? {
        return Index(graph).resolve(rpid, ancestorLimit)
    }

    /** 一份不可变图只建一次业务索引与子链；点击不再扫描无关节点。 */
    class Index(private val graph: ReplyTopologyGraph) {
        private val byId = HashMap<Long, Int>(graph.size)
        private val firstChild = IntArray(graph.size) { -1 }
        private val nextSibling = IntArray(graph.size) { -1 }
        private val work = IntArray(graph.size)

        init {
            for (index in graph.size - 1 downTo 0) {
                byId[graph.rpids[index]] = index
                val parent = graph.parentIndexes[index]
                if (parent in firstChild.indices && parent != index) {
                    nextSibling[index] = firstChild[parent]
                    firstChild[parent] = index
                }
            }
        }

        fun indexOf(rpid: Long): Int = byId[rpid] ?: -1

        fun focus(rpid: Long): BooleanArray? {
            val target = indexOf(rpid)
            if (target < 0) return null
            val focused = BooleanArray(graph.size)
            var cursor = target
            while (cursor in focused.indices && !focused[cursor]) {
                focused[cursor] = true
                cursor = graph.parentIndexes[cursor]
            }
            var pending = 1
            work[0] = target
            while (pending > 0) {
                val parent = work[--pending]
                var child = firstChild[parent]
                while (child >= 0) {
                    if (!focused[child]) {
                        focused[child] = true
                        work[pending++] = child
                    }
                    child = nextSibling[child]
                }
            }
            return focused
        }

        fun resolve(rpid: Long, ancestorLimit: Int = 6): Selection? {
            require(ancestorLimit >= 0)
            val target = indexOf(rpid)
            if (target < 0) return null
            val ancestors = ArrayList<Int>(minOf(ancestorLimit, graph.size))
            var cursor = graph.parentIndexes[target]
            while (cursor in graph.rpids.indices && cursor != target && cursor !in ancestors && ancestors.size < ancestorLimit) {
                ancestors += cursor
                cursor = graph.parentIndexes[cursor]
            }
            var child = firstChild[target]
            var children = 0
            while (child >= 0) {
                if (child !in ancestors) children++
                child = nextSibling[child]
            }
            val indexes = IntArray(ancestors.size + 1 + children)
            var output = 0
            for (ancestor in ancestors) indexes[output++] = ancestor
            indexes[output++] = target
            child = firstChild[target]
            while (child >= 0) {
                if (child !in ancestors) indexes[output++] = child
                child = nextSibling[child]
            }
            indexes.sort()
            val depth = indexes.minOfOrNull { graph.depths[it] }?.coerceAtLeast(0) ?: 0
            return Selection(indexes, depth, (graph.depths[target] - ancestors.size).coerceAtLeast(0))
        }
    }
}
