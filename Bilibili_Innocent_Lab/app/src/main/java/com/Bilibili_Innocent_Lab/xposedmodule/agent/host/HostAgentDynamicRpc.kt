package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** 一次调用至多一次 DynAll RPC。账号授权与身份绑定由 Runtime 在每次调用前后检查。 */
internal class HostAgentDynamicRpc(private val loader: ClassLoader, private val base: String = DYNAMIC_BASE) {
    private val query by lazy { HostAgentDynamicQuery.resolve(loader, base) }
    val followingAvailable: Boolean get() = query?.canReadPosts == true
    val liveStatusAvailable: Boolean get() = query?.canReadLive == true

    fun following(cursor: String?, timeoutMs: Long): HostAgentDataPage {
        val plan = checkNotNull(query?.takeIf { it.canReadPosts }) { "following_feed_rpc_unavailable" }
        return plan.read(cursor, timeoutMs, null, includePosts = true).page
    }

    /** 只查询当前账号本次返回的 UP 栏，不声称支持任意 UID 的全量直播检索。 */
    fun liveStatus(userId: String, timeoutMs: Long): JSONObject {
        val mid = HostAgentContentCursor.userId(userId)
        val plan = checkNotNull(query?.takeIf { it.canReadLive }) { "live_status_rpc_unavailable" }
        val user = plan.read(null, timeoutMs, mid, includePosts = false).matchedUser
        return (user ?: JSONObject().put("user_id", userId).put("name", JSONObject.NULL)
            .put("live_status", "unknown").put("live_room_id", JSONObject.NULL)
            .put("reason", "not_in_returned_up_list"))
            .put("source", "host_rpc").put("dataset", "up_live_status")
            .put("coverage", "returned_page").put("scope", "current_account_feed_up_list")
            .put("checked_pages", 1)
    }
}

private const val DYNAMIC_BASE = "com.bapis.bilibili.app.dynamic.v2."
private const val DYNAMIC_PAGE_SIZE = 20
private const val DYNAMIC_SCAN_LIMIT = 100

private data class DynamicReadPage(val page: HostAgentDataPage, val matchedUser: JSONObject?)

/** 成员来自 9.14 静态样本；计划只保存 Class/Member、整数枚举与投影规则。 */
private class HostAgentDynamicQuery(
    private val request: Class<*>,
    private val newBuilder: Method,
    private val build: Method,
    private val execute: Method,
    private val factory: HostAgentMossFactory,
    private val setOffset: Method,
    private val setPage: Method,
    private val setRefresh: Method,
    private val refreshNew: Int,
    private val refreshHistory: Int,
    private val hasDynamicList: Method,
    private val getDynamicList: Method,
    private val getItems: Method,
    private val getHasMore: Method,
    private val getHistoryOffset: Method,
    private val posts: DynamicPosts?,
    private val media: DynamicMedia?,
    private val users: DynamicUsers?
) {
    val canReadPosts: Boolean get() = posts != null
    val canReadLive: Boolean get() = users?.canReadLive == true

    fun read(rawCursor: String?, timeoutMs: Long, target: Long?, includePosts: Boolean): DynamicReadPage {
        val cursor = HostAgentDynamicCursor.decode(rawCursor)
        check(!Thread.currentThread().isInterrupted) { "dynamic_query_interrupted" }
        val builder = checkNotNull(newBuilder.invoke(null))
        setOffset.invoke(builder, cursor?.offset ?: "")
        setPage.invoke(builder, cursor?.page ?: 1)
        setRefresh.invoke(builder, if (cursor == null) refreshNew else refreshHistory)
        // 不写 updateBaseline/sessionId，不请求 playerArgs/playurl，不调用关系或已读状态写接口。
        val input = checkNotNull(build.invoke(builder)).also { check(request.isInstance(it)) { "rpc_request_type_mismatch" } }
        val reply = checkNotNull(execute.invoke(factory.create(timeoutMs), input))
        val listReported = hostRead(hasDynamicList, reply) == true
        val list = if (listReported) hostRead(getDynamicList, reply) else null
        val items = (hostRead(getItems, list) as? List<*>).orEmpty()
        val projectedPosts = if (includePosts) posts?.project(items).orEmpty() else emptyList()
        val videos = if (includePosts) media?.project(items).orEmpty() else emptyList()
        val userPage = users?.project(reply, target) ?: DynamicUserPage(JSONArray(), null, false)
        val more = hostRead(getHasMore, list) == true
        val offset = HostAgentDynamicCursor.offset(hostRead(getHistoryOffset, list) as? String)
        val next = if (includePosts && items.size <= DYNAMIC_PAGE_SIZE && more && offset != null && offset != cursor?.offset)
            HostAgentDynamicCursor.encode(offset, (cursor?.page ?: 1) + 1) else null
        val truncated = includePosts && items.size > DYNAMIC_PAGE_SIZE
        val data = JSONObject().put("source", "host_rpc").put("dataset", "following_feed")
            .put("coverage", "returned_page").put("posts", JSONArray(projectedPosts))
            .put("videos", JSONArray(videos.map { it.json() }))
            .put("video_identity_state", if (media != null) "available" else "unavailable")
            .put("posts_state", if (listReported) "reported" else "unavailable")
            .put("users", userPage.rows).put("users_scope", "returned_feed_up_list")
            .put("users_truncated", userPage.truncated).put("truncated", truncated)
            .put("has_more_reported", more)
            .put("pagination_status", if (truncated) "unavailable_truncated_page" else if (next != null) "available" else "complete_or_unknown")
        return DynamicReadPage(HostAgentDataPage(data, videos = videos, next = next), userPage.matched)
    }

    companion object {
        fun resolve(loader: ClassLoader, base: String): HostAgentDynamicQuery? = runCatching {
            val moss = KavaMemberLookup.classOrNull(loader, base + "DynamicMoss") ?: return null
            val request = KavaMemberLookup.classOrNull(loader, base + "DynAllReq") ?: return null
            val response = KavaMemberLookup.classOrNull(loader, base + "DynAllReply") ?: return null
            val newBuilder = hostBuilder(request) ?: return null
            val getList = hostGetter(response, "getDynamicList")
                ?.takeIf { it.returnType.name == base + "DynamicList" } ?: return null
            val refresh = dynamicEnumNumbers(loader, base, "Refresh", setOf("refresh_new", "refresh_history")) ?: return null
            HostAgentDynamicQuery(request, newBuilder,
                hostBuild(newBuilder.returnType)?.takeIf { it.returnType.isAssignableFrom(request) } ?: return null,
                KavaMemberLookup.methodOrNull(moss, "executeDynAll", request)
                    ?.takeIf { !it.isStatic && it.returnType == response } ?: return null,
                HostAgentMossFactory.resolve(loader, moss) ?: return null,
                dynamicSetter(newBuilder.returnType, "setOffset", classOf<String>()) ?: return null,
                dynamicSetter(newBuilder.returnType, "setPage", classOf<Int>()) ?: return null,
                dynamicSetter(newBuilder.returnType, "setRefreshTypeValue", classOf<Int>()) ?: return null,
                checkNotNull(refresh["refresh_new"]), checkNotNull(refresh["refresh_history"]),
                hostGetter(response, "hasDynamicList", classOf<Boolean>()) ?: return null, getList,
                dynamicList(getList.returnType, "getListList") ?: return null,
                hostGetter(getList.returnType, "getHasMore", classOf<Boolean>()) ?: return null,
                hostGetter(getList.returnType, "getHistoryOffset", classOf<String>()) ?: return null,
                DynamicPosts.resolve(loader, base), DynamicMedia.resolve(loader, base), DynamicUsers.resolve(loader, base, response))
        }.getOrNull()
    }
}

private class DynamicPosts(
    private val hasExtend: Method, private val getExtend: Method, private val getId: Method,
    private val getUid: Method, private val getOrigName: Method, private val getModules: Method,
    private val hasDesc: Method, private val getDesc: Method, private val getText: Method
) {
    fun project(items: List<*>): List<JSONObject> = items.take(DYNAMIC_PAGE_SIZE).map { item ->
        val extend = if (hostRead(hasExtend, item) == true) hostRead(getExtend, item) else null
        val body = StringBuilder()
        val modules = (hostRead(getModules, item) as? List<*>).orEmpty()
        for (module in modules.take(16)) {
            if (hostRead(hasDesc, module) != true) continue
            val text = hostText(hostRead(getText, hostRead(getDesc, module)), 1000 - body.length) ?: continue
            if (body.isNotEmpty()) body.append('\n')
            body.append(text)
            if (body.length >= 1000) break
        }
        val id = (hostRead(getId, extend) as? String)?.takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) }
        JSONObject().put("post_id", id ?: JSONObject.NULL)
            .put("author_uid", (hostRead(getUid, extend) as? Number)?.toLong()?.takeIf { it > 0 }?.toString() ?: JSONObject.NULL)
            .put("author", JSONObject.NULL)
            .put("original_author_name", hostText(hostRead(getOrigName, extend), 100) ?: JSONObject.NULL)
            .put("text", body.toString().take(1000).takeIf { it.isNotBlank() } ?: JSONObject.NULL)
    }

    companion object {
        fun resolve(loader: ClassLoader, base: String): DynamicPosts? = runCatching {
            val item = KavaMemberLookup.classOrNull(loader, base + "DynamicItem") ?: return null
            val extend = hostGetter(item, "getExtend")?.takeIf { it.returnType.name == base + "Extend" } ?: return null
            val module = KavaMemberLookup.classOrNull(loader, base + "Module") ?: return null
            val desc = hostGetter(module, "getModuleDesc")?.takeIf { it.returnType.name == base + "ModuleDesc" } ?: return null
            DynamicPosts(hostGetter(item, "hasExtend", classOf<Boolean>()) ?: return null, extend,
                hostGetter(extend.returnType, "getDynIdStr", classOf<String>()) ?: return null,
                hostGetter(extend.returnType, "getUid", classOf<Long>()) ?: return null,
                hostGetter(extend.returnType, "getOrigName", classOf<String>()) ?: return null,
                dynamicList(item, "getModulesList") ?: return null,
                hostGetter(module, "hasModuleDesc", classOf<Boolean>()) ?: return null, desc,
                hostGetter(desc.returnType, "getText", classOf<String>()) ?: return null)
        }.getOrNull()
    }
}

/** 仅投影明确 UGC archive 的身份，不把动态原作者或转发者当作视频作者。 */
private class DynamicMedia(
    private val getModules: Method, private val hasDynamic: Method, private val getDynamic: Method,
    private val hasArchive: Method, private val getArchive: Method, private val isPgc: Method,
    private val getAid: Method, private val getBvid: Method?, private val getUri: Method,
    private val getTitle: Method, private val getDuration: Method?
) {
    fun project(items: List<*>): List<HostAgentVideo> {
        val videos = mutableListOf<HostAgentVideo>()
        val seen = hashSetOf<String>()
        for (item in items.take(DYNAMIC_PAGE_SIZE)) {
            val modules = (hostRead(getModules, item) as? List<*>).orEmpty()
            for (module in modules.take(16)) {
                if (hostRead(hasDynamic, module) != true) continue
                val dynamic = hostRead(getDynamic, module)
                if (hostRead(hasArchive, dynamic) != true) continue
                val archive = hostRead(getArchive, dynamic) ?: continue
                if (hostRead(isPgc, archive) != false) continue
                val aid = (hostRead(getAid, archive) as? Number)?.toLong()?.takeIf { it > 0 }?.let { "av$it" }
                val bvid = HostAgentNavigationPolicy.videoId(hostRead(getBvid, archive) as? String)
                val id = HostAgentNavigationPolicy.candidateId(aid ?: bvid, hostRead(getUri, archive) as? String) ?: continue
                if (!seen.add(id)) continue
                videos += HostAgentVideo(id, hostText(hostRead(getTitle, archive), 300), null, null,
                    (hostRead(getDuration, archive) as? Number)?.toLong()?.takeIf { it >= 0 }?.toString())
                if (videos.size == DYNAMIC_PAGE_SIZE) return videos
            }
        }
        return videos
    }

    companion object {
        fun resolve(loader: ClassLoader, base: String): DynamicMedia? = runCatching {
            val item = KavaMemberLookup.classOrNull(loader, base + "DynamicItem") ?: return null
            val module = KavaMemberLookup.classOrNull(loader, base + "Module") ?: return null
            val dynamic = hostGetter(module, "getModuleDynamic")?.takeIf { it.returnType.name == base + "ModuleDynamic" } ?: return null
            val archive = hostGetter(dynamic.returnType, "getDynArchive")?.takeIf { it.returnType.name == base + "MdlDynArchive" } ?: return null
            DynamicMedia(dynamicList(item, "getModulesList") ?: return null,
                hostGetter(module, "hasModuleDynamic", classOf<Boolean>()) ?: return null, dynamic,
                hostGetter(dynamic.returnType, "hasDynArchive", classOf<Boolean>()) ?: return null, archive,
                hostGetter(archive.returnType, "getIsPGC", classOf<Boolean>()) ?: return null,
                hostGetter(archive.returnType, "getAvid", classOf<Long>()) ?: return null,
                hostGetter(archive.returnType, "getBvid", classOf<String>()),
                hostGetter(archive.returnType, "getUri", classOf<String>()) ?: return null,
                hostGetter(archive.returnType, "getTitle", classOf<String>()) ?: return null,
                hostGetter(archive.returnType, "getDuration", classOf<Long>()))
        }.getOrNull()
    }
}

private data class DynamicUserPage(val rows: JSONArray, val matched: JSONObject?, val truncated: Boolean)

private class DynamicUsers(
    private val hasUpList: Method, private val getUpList: Method, private val getPrimary: Method,
    private val getSecondary: Method?, private val getUid: Method, private val getName: Method,
    private val getUri: Method?, private val getLiveState: Method?, private val liveStates: Map<Int, String>
) {
    val canReadLive: Boolean get() = getLiveState != null && liveStates.size == 3

    fun project(reply: Any, target: Long?): DynamicUserPage {
        if (hostRead(hasUpList, reply) != true) return DynamicUserPage(JSONArray(), null, false)
        val list = hostRead(getUpList, reply)
        val primary = (hostRead(getPrimary, list) as? List<*>).orEmpty()
        val secondary = (hostRead(getSecondary, list) as? List<*>).orEmpty()
        val rows = JSONArray()
        val seen = hashSetOf<Long>()
        var matched: JSONObject? = null
        for (item in primary.take(DYNAMIC_SCAN_LIMIT) + secondary.take(DYNAMIC_SCAN_LIMIT)) {
            val uid = (hostRead(getUid, item) as? Number)?.toLong()?.takeIf { it > 0 } ?: continue
            if (!seen.add(uid)) continue
            if (target != null && uid != target) continue
            val state = (hostRead(getLiveState, item) as? Number)?.toInt()?.let { liveStates[it] } ?: "unknown"
            val row = JSONObject().put("user_id", uid.toString()).put("name", hostText(hostRead(getName, item), 100) ?: JSONObject.NULL)
                .put("live_status", state).put("live_status_source", if (state == "unknown") "unavailable" else "host_live_state_enum")
                .put("live_room_id", HostAgentDynamicLivePolicy.roomId(hostRead(getUri, item) as? String) ?: JSONObject.NULL)
            if (target == uid) matched = row
            if (rows.length() < DYNAMIC_PAGE_SIZE) rows.put(row)
            if (target != null && matched != null) break
        }
        return DynamicUserPage(rows, matched, primary.size > DYNAMIC_SCAN_LIMIT || secondary.size > DYNAMIC_SCAN_LIMIT || seen.size > DYNAMIC_PAGE_SIZE)
    }

    companion object {
        fun resolve(loader: ClassLoader, base: String, response: Class<*>): DynamicUsers? = runCatching {
            val upList = hostGetter(response, "getUpList")?.takeIf { it.returnType.name == base + "CardVideoUpList" } ?: return null
            val item = KavaMemberLookup.classOrNull(loader, base + "UpListItem") ?: return null
            val codes = dynamicEnumNumbers(loader, base, "LiveState", setOf("live_none", "live_live", "live_rotation"))
            val states = codes?.entries?.associate { (name, code) ->
                code to when (name) { "live_none" -> "offline"; "live_live" -> "live"; "live_rotation" -> "replay"; else -> "unknown" }
            }.orEmpty()
            DynamicUsers(hostGetter(response, "hasUpList", classOf<Boolean>()) ?: return null, upList,
                dynamicList(upList.returnType, "getListList") ?: return null,
                dynamicList(upList.returnType, "getListSecondList"),
                hostGetter(item, "getUid", classOf<Long>()) ?: return null,
                hostGetter(item, "getName", classOf<String>()) ?: return null,
                hostGetter(item, "getUri", classOf<String>()), hostGetter(item, "getLiveStateValue", classOf<Int>()), states)
        }.getOrNull()
    }
}

private fun dynamicSetter(owner: Class<*>, name: String, type: Class<*>): Method? =
    KavaMemberLookup.methodOrNull(owner, name, type)?.takeIf { !it.isStatic && it.returnType == owner }
private fun dynamicList(owner: Class<*>, name: String): Method? =
    hostGetter(owner, name)?.takeIf { it.returnType isSubclassOf classOf<List<*>>() }

/** 枚举名称和 getNumber 都必须匹配已验真语义；未知值不猜测。 */
private fun dynamicEnumNumbers(loader: ClassLoader, base: String, name: String, required: Set<String>): Map<String, Int>? = runCatching {
    val type = KavaMemberLookup.classOrNull(loader, base + name)?.takeIf { it.isEnum } ?: return null
    val number = hostGetter(type, "getNumber", classOf<Int>()) ?: return null
    val values = type.enumConstants.orEmpty().filterIsInstance<Enum<*>>().filter { it.name in required }
        .associate { it.name to ((hostRead(number, it) as? Number)?.toInt()?.takeIf { code -> code >= 0 } ?: return null) }
    values.takeIf { it.keys == required && it.values.toSet().size == required.size }
}.getOrNull()

internal object HostAgentDynamicLivePolicy {
    /** 只投影明确的宿主 live 路由；URI 不参与 live_status 判定。 */
    fun roomId(raw: String?): String? = runCatching {
        if (raw == null || raw.length > 8192) return null
        val uri = URI(raw)
        if (!uri.scheme.equals("bilibili", true) || !uri.rawAuthority.equals("live", true) ||
            uri.userInfo != null || uri.port != -1 || uri.rawPath != uri.path) return null
        val id = uri.path?.removePrefix("/") ?: return null
        id.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) && (it.toLongOrNull() ?: 0) > 0 }
    }.getOrNull()
}

internal object HostAgentDynamicCursor {
    data class Position(val offset: String, val page: Int)
    fun offset(raw: String?): String? = raw?.takeIf { it.isNotBlank() && it.length <= 1024 && it.none { char -> char.isISOControl() } }
    fun decode(raw: String?): Position? {
        if (raw == null) return null
        check(raw.length <= 2048) { "invalid_following_cursor" }
        val value = runCatching { JSONObject(raw) }.getOrNull() ?: error("invalid_following_cursor")
        check(value.keys().asSequence().toSet() == setOf("offset", "page")) { "invalid_following_cursor" }
        val page = value.opt("page")
        check(page is Int || page is Long) { "invalid_following_cursor" }
        val number = (page as Number).toLong().takeIf { it in 2L..1_000_000L } ?: error("invalid_following_cursor")
        val offset = offset(value.opt("offset") as? String) ?: error("invalid_following_cursor")
        return Position(offset, number.toInt())
    }
    fun encode(offset: String, page: Int): String? =
        if (page !in 2..1_000_000 || HostAgentDynamicCursor.offset(offset) == null) null else JSONObject().put("offset", offset).put("page", page).toString()
}
