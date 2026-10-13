package com.bapis.bilibili.app.dynamic.v2

data class DynamicRootPaging(
    val offset: String = "next", val readOffset: String = "read", val hasMore: Boolean = true,
    val relation: Any = Any(), val additionUp: Any = Any(), val title: String = "title", val titleSub: String = "subtitle"
)

data class DynAllPersonalReply(
    val items: List<DynamicItem> = emptyList(), val paging: DynamicRootPaging = DynamicRootPaging(),
    val failBuild: Boolean = false
) {
    fun getListList() = items
    fun getList(index: Int) = items[index]
    fun getOffset() = paging.offset
    fun getReadOffset() = paging.readOffset
    fun getHasMore() = paging.hasMore
    class Builder(private val original: DynAllPersonalReply) {
        private var items = original.items
        fun clearList() = apply { items = emptyList() }
        fun addAllList(values: Iterable<DynamicItem>) = apply { items = values.toList() }
        fun build(): DynAllPersonalReply {
            check(!original.failBuild)
            return DynAllPersonalReply(items, original.paging)
        }
    }
    companion object {
        private val DEFAULT = DynAllPersonalReply()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: DynAllPersonalReply) = Builder(original)
    }
}

data class DynVideoPersonalReply(
    val items: List<DynamicItem> = emptyList(), val paging: DynamicRootPaging = DynamicRootPaging(),
    val failBuild: Boolean = false
) {
    fun getListList() = items
    fun getList(index: Int) = items[index]
    fun getOffset() = paging.offset
    fun getReadOffset() = paging.readOffset
    fun getHasMore() = paging.hasMore
    class Builder(private val original: DynVideoPersonalReply) {
        private var items = original.items
        fun clearList() = apply { items = emptyList() }
        fun addAllList(values: Iterable<DynamicItem>) = apply { items = values.toList() }
        fun build(): DynVideoPersonalReply {
            check(!original.failBuild)
            return DynVideoPersonalReply(items, original.paging)
        }
    }
    companion object {
        private val DEFAULT = DynVideoPersonalReply()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: DynVideoPersonalReply) = Builder(original)
    }
}

data class FeedFilterReply(val items: List<DynamicItem> = emptyList(), val paging: DynamicRootPaging = DynamicRootPaging()) {
    fun getListList() = items
    fun getList(index: Int) = items[index]
    fun getOffset() = paging.offset
    fun getHasMore() = paging.hasMore
    class Builder(private val original: FeedFilterReply) {
        private var items = original.items
        fun clearList() = apply { items = emptyList() }
        fun addAllList(values: Iterable<DynamicItem>) = apply { items = values.toList() }
        fun build() = FeedFilterReply(items, original.paging)
    }
    companion object {
        private val DEFAULT = FeedFilterReply()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: FeedFilterReply) = Builder(original)
    }
}

data class DynFriendReply(val items: List<DynamicItem> = emptyList(), val paging: DynamicRootPaging = DynamicRootPaging()) {
    fun getDynListList() = items
    fun getDynList(index: Int) = items[index]
    fun getOffset() = paging.offset
    fun getHasMore() = paging.hasMore
    class Builder(private val original: DynFriendReply) {
        private var items = original.items
        fun clearDynList() = apply { items = emptyList() }
        fun addAllDynList(values: Iterable<DynamicItem>) = apply { items = values.toList() }
        fun build() = DynFriendReply(items, original.paging)
    }
    companion object {
        private val DEFAULT = DynFriendReply()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: DynFriendReply) = Builder(original)
    }
}
