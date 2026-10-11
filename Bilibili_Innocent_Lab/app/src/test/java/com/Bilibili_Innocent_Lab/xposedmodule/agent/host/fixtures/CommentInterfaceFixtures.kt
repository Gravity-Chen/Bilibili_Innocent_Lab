package com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.comments

import com.bapis.bilibili.app.interfaces.v1.ContentFixtureBuilder
import com.bapis.bilibili.app.interfaces.v1.ContentFixtureMessage
import com.bilibili.lib.moss.api.CallOptions

/** 独立命名空间中的只读评论协议替身，避免覆盖既有评论适配测试。 */
enum class SubjectType(private val code: Long) { UNKNOWN(0), MANUSCRIPT(1); fun getValue(): Long = code }
enum class Mode(private val code: Int) { DEFAULT(0), MAIN_LIST_TIME(2), MAIN_LIST_HOT(3); fun getNumber(): Int = code }
class FeedPagination(val offset: String, val size: Int) : ContentFixtureMessage() {
    class Builder : ContentFixtureBuilder() {
        private var offset = "wrong"
        private var size = -1
        fun setOffset(value: String): Builder = apply { offset = value }
        fun setPageSize(value: Int): Builder = apply { size = value }
        override fun build(): ContentFixtureMessage = FeedPagination(offset, size)
    }
    companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
}
class FeedPaginationReply(private val next: String) { fun getNextOffset(): String = next }
class MainListReq(val oid: Long, val type: Long, val mode: Int, val pagination: FeedPagination?) : ContentFixtureMessage() {
    class Builder : ContentFixtureBuilder() {
        private var oid = 0L
        private var type = -1L
        private var mode = -1
        private var pagination: FeedPagination? = null
        fun setOid(value: Long): Builder = apply { oid = value }
        fun setType(value: Long): Builder = apply { type = value }
        fun setModeValue(value: Int): Builder = apply { mode = value }
        fun setPagination(value: FeedPagination): Builder = apply { pagination = value }
        override fun build(): ContentFixtureMessage = MainListReq(oid, type, mode, pagination)
    }
    companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
}
class MainListReply(private val replies: List<ReplyInfo>, private val page: FeedPaginationReply?) {
    fun getRepliesList(): List<ReplyInfo> = replies
    fun hasPaginationReply(): Boolean = page != null
    fun getPaginationReply(): FeedPaginationReply = page ?: FeedPaginationReply("")
}
class ReplyInfo(private val id: Long, private val oid: Long, private val type: Long,
    private val message: String, private val mid: Long, private val likes: Long, private val replies: Long) {
    fun getId(): Long = id
    fun getOid(): Long = oid
    fun getType(): Long = type
    fun getLike(): Long = likes
    fun getCount(): Long = replies
    fun getContent(): Content = Content(message)
    fun getMember(): Member = Member(mid, if (mid > 0) "作者" else "")
}
class Content(private val message: String) { fun getMessage(): String = message }
class Member(private val mid: Long, private val name: String) { fun getMid(): Long = mid; fun getName(): String = name }
class ReplyMoss(val address: String, val port: Int, val options: CallOptions) {
    fun executeMainList(request: MainListReq): MainListReply {
        calls++
        lastRequest = request
        lastTimeout = options.getTimeoutInMs()
        return reply
    }
    companion object {
        var calls = 0
        var lastRequest: MainListReq? = null
        var lastTimeout: Long? = null
        var reply = MainListReply(emptyList(), null)
    }
}
