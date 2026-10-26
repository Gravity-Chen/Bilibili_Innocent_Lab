package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method
import org.json.JSONArray
import org.json.JSONObject

/** 只缓存反射计划；账户授权、任务内身份与游标归属由 Runtime 检查。 */
internal class HostAgentContentRpc(
    private val loader: ClassLoader,
    private val commentsBase: String = "com.bapis.bilibili.main.community.reply.v1.",
    private val paginationBase: String = "com.bapis.bilibili.pagination.",
    private val subjectTypeName: String = "com.bilibili.app.comment3.data.model.SubjectId\$Type"
) {
    private val historyQuery by lazy { HostAgentHistoryQuery.resolve(loader) }
    private val userVideosQuery by lazy { HostAgentUserVideosQuery.resolve(loader) }
    private val commentsQuery by lazy { HostAgentCommentQuery.resolve(loader, commentsBase, paginationBase, subjectTypeName) }

    val historyAvailable: Boolean get() = historyQuery != null
    val userVideosAvailable: Boolean get() = userVideosQuery != null
    // related 仅确认 Moss 签名，请求与完整返回结构尚未验真。
    val relatedAvailable: Boolean get() = false
    val commentsAvailable: Boolean get() = commentsQuery != null

    fun history(cursor: String?, timeoutMs: Long): HostAgentDataPage =
        checkNotNull(historyQuery) { "history_rpc_unavailable" }.query(cursor, timeoutMs)

    fun userVideos(userId: String, cursor: String?, timeoutMs: Long): HostAgentDataPage =
        checkNotNull(userVideosQuery) { "user_videos_rpc_unavailable" }.query(userId, cursor, timeoutMs)

    @Suppress("UNUSED_PARAMETER")
    fun related(videoId: String, timeoutMs: Long): HostAgentDataPage = error("related_rpc_schema_unverified")

    fun comments(videoId: String, cursor: String?, timeoutMs: Long): HostAgentDataPage =
        checkNotNull(commentsQuery) { "comments_rpc_unavailable" }.query(videoId, cursor, timeoutMs)
}

private const val CONTENT_BASE = "com.bapis.bilibili.app.interfaces.v1."
private const val CONTENT_PAGE_SIZE = 20
private const val CONTENT_SCAN_LIMIT = 100

/** 9.14 CursorV2Req/Reply 与 CursorItem 的精确结构来自远端 run 38134040310/38134342314。 */
private class HostAgentHistoryQuery(
    private val call: ContentCall,
    private val cursorBuilder: Method,
    private val setMax: Method,
    private val setMaxTp: Method,
    private val cursorBuild: Method,
    private val setCursor: Method,
    private val setIsLocal: Method,
    private val getItems: Method,
    private val hasUgc: Method,
    private val getOid: Method,
    private val getTitle: Method,
    private val getUri: Method,
    private val getViewAt: Method,
    private val getUgc: Method,
    private val getUgcName: Method?,
    private val getUgcMid: Method?,
    private val getUgcDuration: Method?,
    private val getHasMore: Method,
    private val hasCursor: Method,
    private val getCursor: Method,
    private val getMax: Method,
    private val getMaxTp: Method
) {
    fun query(rawCursor: String?, timeoutMs: Long): HostAgentDataPage {
        val position = HostAgentContentCursor.history(rawCursor)
        val builder = call.builder()
        setIsLocal.invoke(builder, false)
        // 9.14 标准历史首屏消费者同样显式发送 Cursor(0, 0)，续页发送上页回报的位置。
        val cursor = checkNotNull(cursorBuilder.invoke(null))
        setMax.invoke(cursor, position?.first ?: 0L)
        setMaxTp.invoke(cursor, position?.second ?: 0)
        setCursor.invoke(builder, cursorBuild.invoke(cursor))
        // 不设置 playerArgs/playerPreload；不调用 Clear/Delete/SetHistory 等写方法。
        val reply = call.execute(builder, timeoutMs)
        val items = getItems.invoke(reply) as? List<*> ?: error("history_result_unavailable")
        val videos = mutableListOf<HostAgentVideo>()
        val rows = JSONArray()
        val seen = hashSetOf<String>()
        var inspected = 0
        for (item in items.take(CONTENT_SCAN_LIMIT)) {
            inspected++
            if (hostRead(hasUgc, item) != true) continue
            val oid = (hostRead(getOid, item) as? Number)?.toLong()?.takeIf { it > 0 }
            val id = HostAgentNavigationPolicy.candidateId(oid?.toString(), hostRead(getUri, item) as? String)
                ?: continue
            if (!seen.add(id)) continue
            val ugc = hostRead(getUgc, item)
            val duration = (hostRead(getUgcDuration, ugc) as? Number)?.toLong()?.takeIf { it >= 0 }
            val video = HostAgentVideo(id, hostText(hostRead(getTitle, item), 300),
                hostText(hostRead(getUgcName, ugc), 100),
                (hostRead(getUgcMid, ugc) as? Number)?.toLong()?.takeIf { it > 0 }, duration?.toString())
            videos += video
            rows.put(video.json().put("viewed_at", (hostRead(getViewAt, item) as? Number)?.toLong()
                ?.takeIf { it > 0 } ?: JSONObject.NULL).put("duration_seconds", duration ?: JSONObject.NULL))
            if (videos.size == CONTENT_PAGE_SIZE) break
        }
        val truncated = inspected < items.size
        // 原生页超过投影预算时，reply.cursor 指向整页之后；不能拿它继续而静默跳过中间结果。
        val next = if (!truncated && hostRead(getHasMore, reply) == true && hostRead(hasCursor, reply) == true) {
            val cursor = hostRead(getCursor, reply)
            val max = (hostRead(getMax, cursor) as? Number)?.toLong()
            val type = (hostRead(getMaxTp, cursor) as? Number)?.toInt()
            HostAgentContentCursor.historyNext(max, type)?.takeIf { it != rawCursor }
        } else null
        return HostAgentDataPage(JSONObject().put("source", "host_rpc").put("dataset", "history")
            .put("coverage", "returned_video_items").put("query_semantics", "host_default_business")
            .put("truncated", truncated).put("has_more_reported", hostRead(getHasMore, reply) == true)
            .put("pagination_status", if (truncated) "unavailable_truncated_page" else if (next != null) "available" else "complete_or_unknown")
            .put("items", rows).put("fields", JSONObject().put("author", if (getUgcName != null) "available" else "unavailable")
                .put("duration", if (getUgcDuration != null) "available" else "unavailable")), videos, next)
    }

    companion object {
        fun resolve(loader: ClassLoader): HostAgentHistoryQuery? = runCatching {
            val call = ContentCall.resolve(loader, "HistoryMoss", "CursorV2Req", "CursorV2Reply", "executeCursorV2")
                ?: return null
            val cursor = KavaMemberLookup.classOrNull(loader, CONTENT_BASE + "Cursor") ?: return null
            val item = KavaMemberLookup.classOrNull(loader, CONTENT_BASE + "CursorItem") ?: return null
            val cursorBuilder = hostBuilder(cursor) ?: return null
            val ugc = hostGetter(item, "getCardUgc")?.takeIf { it.returnType.name == CONTENT_BASE + "CardUGC" }
                ?: return null
            HostAgentHistoryQuery(call, cursorBuilder,
                contentSetter(cursorBuilder.returnType, "setMax", classOf<Long>()) ?: return null,
                contentSetter(cursorBuilder.returnType, "setMaxTp", classOf<Int>()) ?: return null,
                hostBuild(cursorBuilder.returnType)?.takeIf { it.returnType.isAssignableFrom(cursor) } ?: return null,
                contentSetter(call.newBuilder.returnType, "setCursor", cursor) ?: return null,
                contentSetter(call.newBuilder.returnType, "setIsLocal", classOf<Boolean>()) ?: return null,
                contentList(call.response, "getItemsList") ?: return null,
                hostGetter(item, "hasCardUgc", classOf<Boolean>()) ?: return null,
                hostGetter(item, "getOid", classOf<Long>()) ?: return null,
                hostGetter(item, "getTitle", classOf<String>()) ?: return null,
                hostGetter(item, "getUri", classOf<String>()) ?: return null,
                hostGetter(item, "getViewAt", classOf<Long>()) ?: return null,
                ugc, hostGetter(ugc.returnType, "getName", classOf<String>()),
                hostGetter(ugc.returnType, "getMid", classOf<Long>()), hostGetter(ugc.returnType, "getDuration", classOf<Long>()),
                hostGetter(call.response, "getHasMore", classOf<Boolean>()) ?: return null,
                hostGetter(call.response, "hasCursor", classOf<Boolean>()) ?: return null,
                hostGetter(call.response, "getCursor", cursor) ?: return null,
                hostGetter(cursor, "getMax", classOf<Long>()) ?: return null,
                hostGetter(cursor, "getMaxTp", classOf<Int>()) ?: return null)
        }.getOrNull()
    }
}

/** SpaceMoss 是只读投稿搜索；空关键词语义和覆盖范围随服务端返回，不能当成完整投稿清单。 */
private class HostAgentUserVideosQuery(
    private val call: ContentCall,
    private val setMid: Method,
    private val setPn: Method,
    private val setPs: Method,
    private val setKeyword: Method,
    private val getArchives: Method,
    private val getTotal: Method,
    private val hasArchive: Method,
    private val getUri: Method,
    private val getArchive: Method,
    private val getAid: Method,
    private val getTitle: Method,
    private val getDuration: Method?,
    private val hasAuthor: Method?,
    private val getAuthor: Method?,
    private val getAuthorMid: Method?,
    private val getAuthorName: Method?
) {
    fun query(userId: String, rawCursor: String?, timeoutMs: Long): HostAgentDataPage {
        val mid = HostAgentContentCursor.userId(userId)
        val page = HostAgentContentCursor.userPage(rawCursor)
        val builder = call.builder()
        setMid.invoke(builder, mid)
        setPn.invoke(builder, page)
        setPs.invoke(builder, CONTENT_PAGE_SIZE.toLong())
        setKeyword.invoke(builder, "")
        val reply = call.execute(builder, timeoutMs)
        val archives = getArchives.invoke(reply) as? List<*> ?: error("user_videos_result_unavailable")
        val videos = archives.asSequence().take(CONTENT_SCAN_LIMIT).filterNotNull().mapNotNull { item ->
            if (hostRead(hasArchive, item) != true) return@mapNotNull null
            val archive = hostRead(getArchive, item) ?: return@mapNotNull null
            val aid = (hostRead(getAid, archive) as? Number)?.toLong()?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val id = HostAgentNavigationPolicy.candidateId(aid.toString(), hostRead(getUri, item) as? String)
                ?: return@mapNotNull null
            val author = if (hostRead(hasAuthor, archive) == true) hostRead(getAuthor, archive) else null
            val authorMid = (hostRead(getAuthorMid, author) as? Number)?.toLong()?.takeIf { it > 0 }
            // 请求的是指定 UP；返回明确不同作者时拒绝将其当成该 UP 的投稿事实。
            if (authorMid != null && authorMid != mid) return@mapNotNull null
            HostAgentVideo(id, hostText(hostRead(getTitle, archive), 300), hostText(hostRead(getAuthorName, author), 100),
                authorMid, (hostRead(getDuration, archive) as? Number)?.toLong()?.takeIf { it >= 0 }?.toString())
        }.distinctBy { it.id }.take(CONTENT_PAGE_SIZE).toList()
        val total = (hostRead(getTotal, reply) as? Number)?.toLong()?.takeIf { it >= 0 }
        val truncated = archives.size > CONTENT_PAGE_SIZE
        val next = if (!truncated && archives.isNotEmpty() && total != null && total > page * CONTENT_PAGE_SIZE && page < HostAgentContentCursor.MAX_USER_PAGE)
            "uv1:${page + 1}" else null
        return HostAgentDataPage(JSONObject().put("source", "host_rpc").put("dataset", "user_videos")
            .put("coverage", "returned_page").put("query_semantics", "host_default_empty_keyword")
            .put("truncated", truncated)
            .put("pagination_status", if (truncated) "unavailable_truncated_page" else if (next != null) "available" else "complete_or_unknown")
            .put("requested_user_id", userId).put("items", JSONArray(videos.map { it.json() }))
            .put("reported_total", total ?: JSONObject.NULL)
            .put("fields", JSONObject().put("title", "available")
                .put("author", if (getAuthorName != null) "available" else "unavailable")
                .put("duration", if (getDuration != null) "available" else "unavailable")), videos, next)
    }

    companion object {
        fun resolve(loader: ClassLoader): HostAgentUserVideosQuery? = runCatching {
            val call = ContentCall.resolve(loader, "SpaceMoss", "SearchArchiveReq", "SearchArchiveReply", "executeSearchArchive")
                ?: return null
            val arc = KavaMemberLookup.classOrNull(loader, CONTENT_BASE + "Arc") ?: return null
            val archive = hostGetter(arc, "getArchive")?.takeIf { it.returnType.name == "com.bapis.bilibili.app.archive.v1.Arc" }
                ?: return null
            val author = hostGetter(archive.returnType, "getAuthor")
                ?.takeIf { it.returnType.name == "com.bapis.bilibili.app.archive.v1.Author" }
            HostAgentUserVideosQuery(call,
                contentSetter(call.newBuilder.returnType, "setMid", classOf<Long>()) ?: return null,
                contentSetter(call.newBuilder.returnType, "setPn", classOf<Long>()) ?: return null,
                contentSetter(call.newBuilder.returnType, "setPs", classOf<Long>()) ?: return null,
                contentSetter(call.newBuilder.returnType, "setKeyword", classOf<String>()) ?: return null,
                contentList(call.response, "getArchivesList") ?: return null,
                hostGetter(call.response, "getTotal", classOf<Long>()) ?: return null,
                hostGetter(arc, "hasArchive", classOf<Boolean>()) ?: return null,
                hostGetter(arc, "getUri", classOf<String>()) ?: return null,
                archive, hostGetter(archive.returnType, "getAid", classOf<Long>()) ?: return null,
                hostGetter(archive.returnType, "getTitle", classOf<String>()) ?: return null,
                hostGetter(archive.returnType, "getDuration", classOf<Long>()),
                hostGetter(archive.returnType, "hasAuthor", classOf<Boolean>()), author,
                author?.returnType?.let { hostGetter(it, "getMid", classOf<Long>()) },
                author?.returnType?.let { hostGetter(it, "getName", classOf<String>()) })
        }.getOrNull()
    }
}

/** V1 消费者使用 FeedPagination + 明确排序；type 来自宿主 MANUSCRIPT 枚举，不猜服务端默认。 */
private class HostAgentCommentQuery(
    private val call: ContentCall,
    private val setOid: Method, private val setType: Method, private val videoType: Long,
    private val setMode: Method, private val timeMode: Int,
    private val pageBuilder: Method, private val setOffset: Method, private val setPageSize: Method,
    private val pageBuild: Method, private val setPagination: Method,
    private val getReplies: Method, private val getId: Method, private val getOid: Method,
    private val getType: Method, private val getLike: Method, private val getCount: Method,
    private val getContent: Method, private val getMessage: Method,
    private val getMember: Method, private val getMid: Method, private val getName: Method,
    private val hasPagination: Method, private val getPagination: Method, private val getNext: Method
) {
    fun query(videoId: String, cursor: String?, timeoutMs: Long): HostAgentDataPage {
        val canonical = HostAgentNavigationPolicy.videoId(videoId)?.takeIf { it.startsWith("av") }
            ?: error("comment_aid_required")
        val aid = canonical.removePrefix("av").toLong()
        if (cursor != null) check(commentOffset(cursor) != null) { "invalid_comments_cursor" }
        val builder = call.builder()
        setOid.invoke(builder, aid)
        setType.invoke(builder, videoType)
        setMode.invoke(builder, timeMode)
        val page = checkNotNull(pageBuilder.invoke(null))
        setOffset.invoke(page, cursor ?: "")
        setPageSize.invoke(page, CONTENT_PAGE_SIZE)
        setPagination.invoke(builder, pageBuild.invoke(page))
        val reply = call.execute(builder, timeoutMs)
        val replies = getReplies.invoke(reply) as? List<*> ?: error("comments_result_unavailable")
        val comments = JSONArray()
        val seen = hashSetOf<Long>()
        var inspected = 0
        for (item in replies.take(CONTENT_SCAN_LIMIT)) {
            inspected++
            val oid = (hostRead(getOid, item) as? Number)?.toLong()
            val type = (hostRead(getType, item) as? Number)?.toLong()
            if (oid != aid || type != videoType) continue
            val id = (hostRead(getId, item) as? Number)?.toLong()?.takeIf { it > 0 } ?: continue
            if (!seen.add(id)) continue
            val member = hostRead(getMember, item)
            comments.put(JSONObject().put("comment_id", id.toString()).put("video_id", canonical)
                .put("message", hostText(hostRead(getMessage, hostRead(getContent, item)), 1000) ?: JSONObject.NULL)
                .put("author_uid", (hostRead(getMid, member) as? Number)?.toLong()?.takeIf { it > 0 }?.toString() ?: JSONObject.NULL)
                .put("author", hostText(hostRead(getName, member), 100) ?: JSONObject.NULL)
                .put("likes", (hostRead(getLike, item) as? Number)?.toLong()?.takeIf { it >= 0 } ?: JSONObject.NULL)
                .put("reply_count", (hostRead(getCount, item) as? Number)?.toLong()?.takeIf { it >= 0 } ?: JSONObject.NULL))
            if (comments.length() == CONTENT_PAGE_SIZE) break
        }
        val truncated = inspected < replies.size
        val next = if (!truncated && hostRead(hasPagination, reply) == true)
            commentOffset(hostRead(getNext, hostRead(getPagination, reply)) as? String)?.takeIf { it != cursor } else null
        return HostAgentDataPage(JSONObject().put("source", "host_rpc").put("dataset", "comments")
            .put("video_id", canonical).put("coverage", "returned_top_level_replies")
            .put("requested_sort", "time").put("comments", comments).put("truncated", truncated)
            .put("pagination_status", if (truncated) "unavailable_truncated_page" else if (next != null) "available" else "complete_or_unknown"), next = next)
    }

    companion object {
        fun resolve(loader: ClassLoader, base: String, paginationBase: String, subjectTypeName: String): HostAgentCommentQuery? = runCatching {
            val call = ContentCall.resolve(loader, "ReplyMoss", "MainListReq", "MainListReply", "executeMainList", base) ?: return null
            val typeClass = KavaMemberLookup.classOrNull(loader, subjectTypeName)?.takeIf { it.isEnum } ?: return null
            val typeValue = hostGetter(typeClass, "getValue", classOf<Long>()) ?: return null
            val videoType = typeClass.enumConstants.orEmpty().filterIsInstance<Enum<*>>()
                .singleOrNull { it.name == "MANUSCRIPT" }?.let { (hostRead(typeValue, it) as? Number)?.toLong() }
                ?.takeIf { it > 0 } ?: return null
            val modeClass = KavaMemberLookup.classOrNull(loader, base + "Mode")?.takeIf { it.isEnum } ?: return null
            val modeNumber = hostGetter(modeClass, "getNumber", classOf<Int>()) ?: return null
            val timeMode = modeClass.enumConstants.orEmpty().filterIsInstance<Enum<*>>()
                .singleOrNull { it.name == "MAIN_LIST_TIME" }?.let { (hostRead(modeNumber, it) as? Number)?.toInt() }
                ?.takeIf { it >= 0 } ?: return null
            val page = KavaMemberLookup.classOrNull(loader, paginationBase + "FeedPagination") ?: return null
            val pageBuilder = hostBuilder(page) ?: return null
            val item = KavaMemberLookup.classOrNull(loader, base + "ReplyInfo") ?: return null
            val content = hostGetter(item, "getContent")?.takeIf { it.returnType.name == base + "Content" } ?: return null
            val member = hostGetter(item, "getMember")?.takeIf { it.returnType.name == base + "Member" } ?: return null
            val pagination = hostGetter(call.response, "getPaginationReply")
                ?.takeIf { it.returnType.name == paginationBase + "FeedPaginationReply" } ?: return null
            HostAgentCommentQuery(call,
                contentSetter(call.newBuilder.returnType, "setOid", classOf<Long>()) ?: return null,
                contentSetter(call.newBuilder.returnType, "setType", classOf<Long>()) ?: return null, videoType,
                contentSetter(call.newBuilder.returnType, "setModeValue", classOf<Int>()) ?: return null, timeMode,
                pageBuilder, contentSetter(pageBuilder.returnType, "setOffset", classOf<String>()) ?: return null,
                contentSetter(pageBuilder.returnType, "setPageSize", classOf<Int>()) ?: return null,
                hostBuild(pageBuilder.returnType)?.takeIf { it.returnType.isAssignableFrom(page) } ?: return null,
                contentSetter(call.newBuilder.returnType, "setPagination", page) ?: return null,
                contentList(call.response, "getRepliesList") ?: return null,
                hostGetter(item, "getId", classOf<Long>()) ?: return null,
                hostGetter(item, "getOid", classOf<Long>()) ?: return null,
                hostGetter(item, "getType", classOf<Long>()) ?: return null,
                hostGetter(item, "getLike", classOf<Long>()) ?: return null,
                hostGetter(item, "getCount", classOf<Long>()) ?: return null, content,
                hostGetter(content.returnType, "getMessage", classOf<String>()) ?: return null, member,
                hostGetter(member.returnType, "getMid", classOf<Long>()) ?: return null,
                hostGetter(member.returnType, "getName", classOf<String>()) ?: return null,
                hostGetter(call.response, "hasPaginationReply", classOf<Boolean>()) ?: return null, pagination,
                hostGetter(pagination.returnType, "getNextOffset", classOf<String>()) ?: return null)
        }.getOrNull()
    }
}

private fun commentOffset(raw: String?): String? = raw?.takeIf {
    it.isNotBlank() && it.length <= 2048 && it.none { char -> char.isISOControl() }
}

private class ContentCall(
    val newBuilder: Method,
    val response: Class<*>,
    private val request: Class<*>,
    private val build: Method,
    private val execute: Method,
    private val factory: HostAgentMossFactory
) {
    fun builder(): Any = checkNotNull(newBuilder.invoke(null))
    fun execute(builder: Any, timeoutMs: Long): Any {
        val input = checkNotNull(build.invoke(builder)).also { check(request.isInstance(it)) { "rpc_request_type_mismatch" } }
        return checkNotNull(execute.invoke(factory.create(timeoutMs), input))
    }

    companion object {
        fun resolve(loader: ClassLoader, service: String, req: String, reply: String, method: String, base: String = CONTENT_BASE): ContentCall? = runCatching {
            val moss = KavaMemberLookup.classOrNull(loader, base + service) ?: return null
            val request = KavaMemberLookup.classOrNull(loader, base + req) ?: return null
            val response = KavaMemberLookup.classOrNull(loader, base + reply) ?: return null
            val builder = hostBuilder(request) ?: return null
            ContentCall(builder, response, request,
                hostBuild(builder.returnType)?.takeIf { it.returnType.isAssignableFrom(request) } ?: return null,
                KavaMemberLookup.methodOrNull(moss, method, request)
                    ?.takeIf { !it.isStatic && it.returnType == response } ?: return null,
                HostAgentMossFactory.resolve(loader, moss) ?: return null)
        }.getOrNull()
    }
}

private fun contentSetter(owner: Class<*>, name: String, type: Class<*>): Method? =
    KavaMemberLookup.methodOrNull(owner, name, type)?.takeIf { !it.isStatic && it.returnType == owner }

private fun contentList(owner: Class<*>, name: String): Method? =
    hostGetter(owner, name)?.takeIf { it.returnType isSubclassOf classOf<List<*>>() }

/** Runtime 只接受本任务签发的 opaque 游标；这里仍对格式和算术边界作二次检查。 */
internal object HostAgentContentCursor {
    const val MAX_USER_PAGE = 1_000_000L
    private val historyPattern = Regex("hc1:([1-9][0-9]{0,18}):([0-9]{1,10})")
    private val userPattern = Regex("uv1:([1-9][0-9]{0,6})")
    private val userIdPattern = Regex("[1-9][0-9]{0,18}")

    fun history(raw: String?): Pair<Long, Int>? {
        if (raw == null) return null
        val match = historyPattern.matchEntire(raw) ?: error("invalid_history_cursor")
        val max = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: error("invalid_history_cursor")
        val type = match.groupValues[2].toIntOrNull()?.takeIf { it >= 0 } ?: error("invalid_history_cursor")
        return max to type
    }

    fun historyNext(max: Long?, type: Int?): String? =
        if (max == null || max <= 0 || type == null || type < 0) null else "hc1:$max:$type"

    fun userPage(raw: String?): Long {
        if (raw == null) return 1L
        val match = userPattern.matchEntire(raw) ?: error("invalid_user_videos_cursor")
        return match.groupValues[1].toLongOrNull()?.takeIf { it in 1L..MAX_USER_PAGE }
            ?: error("invalid_user_videos_cursor")
    }

    fun userId(raw: String): Long = raw.takeIf { userIdPattern.matches(it) }?.toLongOrNull()?.takeIf { it > 0 }
        ?: error("invalid_user_id")
}
