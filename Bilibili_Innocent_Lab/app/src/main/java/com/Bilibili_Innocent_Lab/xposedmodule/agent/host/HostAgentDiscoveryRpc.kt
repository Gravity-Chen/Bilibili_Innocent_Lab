package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method
import org.json.JSONArray
import org.json.JSONObject

/** 原生综合榜与分区目录；所有调用都由 Agent 的有界后台调度发起。 */
internal class HostAgentDiscoveryRpc(private val loader: ClassLoader) {
    private val all by lazy { RankingCall.resolve(loader, region = false) }
    private val regional by lazy { RankingCall.resolve(loader, region = true) }
    private val regionList by lazy { RegionListCall.resolve(loader) }
    private val popularFeed by lazy { PopularCall.resolve(loader) }

    val rankingAvailable: Boolean get() = all != null
    val regionsAvailable: Boolean get() = regionList != null
    val popularAvailable: Boolean get() = popularFeed != null

    fun ranking(regionId: String?, cursor: String?, timeoutMs: Long): HostAgentDataPage {
        val page = checkNotNull(HostAgentDiscoveryProjection.rankingPage(cursor)) { "ranking_cursor_invalid" }
        val region = if (regionId == null) null
            else checkNotNull(HostAgentDiscoveryProjection.regionId(regionId)) { "ranking_region_invalid" }.takeIf { it != 0 }
        return checkNotNull(if (region == null) all else regional) { "ranking_unavailable" }.query(region, page, timeoutMs)
    }

    fun popular(cursor: String?, timeoutMs: Long): HostAgentDataPage {
        val page = checkNotNull(HostAgentPopularCursor.parse(cursor)) { "popular_cursor_invalid" }
        return checkNotNull(popularFeed) { "popular_unavailable" }.query(page, timeoutMs)
    }

    fun regions(timeoutMs: Long): JSONObject = checkNotNull(regionList) { "ranking_regions_unavailable" }.query(timeoutMs)

    private class RankingCall(
        val call: DiscoveryListCall, val pn: Method, val ps: Method, val order: Method?, val rid: Method?,
        val reader: HostAgentRankItemReader
    ) {
        fun query(regionId: Int?, page: Int, timeoutMs: Long): HostAgentDataPage {
            val items = call.invoke(timeoutMs) { builder ->
                pn.invoke(builder, page)
                ps.invoke(builder, HostAgentDiscoveryProjection.PAGE_SIZE)
                // 9.14 RankApi 综合榜调用实际使用 all；分区请求没有 order 字段。
                order?.invoke(builder, "all")
                if (rid != null) rid.invoke(builder, checkNotNull(regionId))
            }
            return HostAgentDiscoveryProjection.ranking(items.take(HostAgentDiscoveryProjection.PAGE_SIZE).map(reader::read),
                items.size, page, regionId?.toString()).also { if (regionId == null) it.data.put("order", "all") }
        }

        companion object {
            fun resolve(loader: ClassLoader, region: Boolean): RankingCall? = runCatching {
                val call = DiscoveryListCall.resolve(loader, RANK_BASE, "RankMoss",
                    if (region) "RankRegionResultReq" else "RankAllResultReq", "RankListReply",
                    if (region) "executeRankRegion" else "executeRankAll", "getItemsList") ?: return null
                val item = KavaMemberLookup.classOrNull(loader, RANK_BASE + "Item") ?: return null
                RankingCall(call, discoverySetter(call.builderType, "setPn", classOf<Int>()) ?: return null,
                    discoverySetter(call.builderType, "setPs", classOf<Int>()) ?: return null,
                    if (region) null else (discoverySetter(call.builderType, "setOrder", classOf<String>()) ?: return null),
                    if (region) (discoverySetter(call.builderType, "setRid", classOf<Int>()) ?: return null) else null,
                    HostAgentRankItemReader.resolve(item) ?: return null)
            }.getOrNull()
        }
    }

    private class RegionListCall(val call: DiscoveryListCall, val reader: HostAgentRegionReader) {
        fun query(timeoutMs: Long): JSONObject =
            HostAgentDiscoveryProjection.regions(call.invoke(timeoutMs).take(100).map(reader::read))

        companion object {
            fun resolve(loader: ClassLoader): RegionListCall? = runCatching {
                val call = DiscoveryListCall.resolve(loader, RANK_BASE, "RankMoss", "RankRegionListReq",
                    "RankRegionListReply", "executeRankRegionList", "getRegionsList") ?: return null
                val info = KavaMemberLookup.classOrNull(loader, RANK_BASE + "RankRegionInfo") ?: return null
                RegionListCall(call, HostAgentRegionReader.resolve(info) ?: return null)
            }.getOrNull()
        }
    }

    private class PopularCall(
        val call: DiscoveryListCall, val idx: Method, val param: Method, val version: Method, val source: Method,
        val login: Method, val entrance: Method, val location: Method, val flush: Method, val spmid: Method,
        val playerArgs: Method, val playerArgsFactory: Method, val getVersion: Method,
        val videoReader: HostAgentPopularCardReader, val positionReader: HostAgentPopularPositionReader
    ) {
        fun query(cursor: HostAgentPopularCursor, timeoutMs: Long): HostAgentDataPage {
            var responseVersion: String? = null
            val items = call.invoke(timeoutMs, readFacts = { response ->
                responseVersion = hostRead(getVersion, response) as? String
            }) { builder ->
                idx.invoke(builder, cursor.idx)
                param.invoke(builder, cursor.lastParam)
                version.invoke(builder, cursor.version)
                source.invoke(builder, 0)
                login.invoke(builder, 0)
                entrance.invoke(builder, 0L)
                location.invoke(builder, "")
                flush.invoke(builder, 0)
                spmid.invoke(builder, "creation.hot-tab.0.0")
                playerArgs.invoke(builder, checkNotNull(playerArgsFactory.invoke(null)) { "popular_player_args_unavailable" })
            }
            // 不发送广告 extra 或注册曝光；只把普通视频事实投影给本任务。
            val projected = items.take(100).map(videoReader::read)
            return HostAgentDiscoveryProjection.popular(projected, items.size, cursor,
                positionReader.read(items.lastOrNull()), responseVersion)
        }

        companion object {
            fun resolve(loader: ClassLoader): PopularCall? = runCatching {
                val call = DiscoveryListCall.resolve(loader, POPULAR_BASE, "PopularMoss", "PopularResultReq",
                    "PopularReply", "executeIndex", "getItemsList") ?: return null
                val response = KavaMemberLookup.classOrNull(loader, POPULAR_BASE + "PopularReply") ?: return null
                val card = KavaMemberLookup.classOrNull(loader, CARD_BASE + "Card") ?: return null
                val base = KavaMemberLookup.classOrNull(loader, CARD_BASE + "Base") ?: return null
                val utility = KavaMemberLookup.classOrNull(loader, "com.bilibili.app.comm.list.common.api.ListPlayerPreloadUtilKt")
                    ?: return null
                val argsFactory = KavaMemberLookup.methodOrNull(utility, "getPlayerArgsParams")?.takeIf {
                    it.isStatic && it.parameterCount == 0 &&
                        it.returnType.name == "com.bapis.bilibili.app.archive.middleware.v1.PlayerArgs"
                } ?: return null
                val builder = call.builderType
                PopularCall(call, discoverySetter(builder, "setIdx", classOf<Long>()) ?: return null,
                    discoverySetter(builder, "setLastParam", classOf<String>()) ?: return null,
                    discoverySetter(builder, "setVer", classOf<String>()) ?: return null,
                    discoverySetter(builder, "setSourceId", classOf<Int>()) ?: return null,
                    discoverySetter(builder, "setLoginEvent", classOf<Int>()) ?: return null,
                    discoverySetter(builder, "setEntranceId", classOf<Long>()) ?: return null,
                    discoverySetter(builder, "setLocationIds", classOf<String>()) ?: return null,
                    discoverySetter(builder, "setFlush", classOf<Int>()) ?: return null,
                    discoverySetter(builder, "setSpmid", classOf<String>()) ?: return null,
                    discoverySetter(builder, "setPlayerArgs", argsFactory.returnType) ?: return null, argsFactory,
                    hostGetter(response, "getVer", classOf<String>()) ?: return null,
                    HostAgentPopularCardReader.resolve(card, base) ?: return null,
                    HostAgentPopularPositionReader.resolve(card, base) ?: return null)
            }.getOrNull()
        }
    }

    companion object {
        private const val RANK_BASE = "com.bapis.bilibili.app.show.rank.v1."
        private const val POPULAR_BASE = "com.bapis.bilibili.app.show.popular.v1."
        private const val CARD_BASE = "com.bapis.bilibili.app.card.v1."
    }
}

internal data class HostAgentRegion(val id: Int, val name: String?, val type: Int)

internal class HostAgentRegionReader private constructor(private val tid: Method, private val name: Method, private val type: Method) {
    fun read(item: Any?): HostAgentRegion? {
        val id = (hostRead(tid, item) as? Number)?.toInt()?.takeIf { it >= 0 } ?: return null
        val regionType = (hostRead(type, item) as? Number)?.toInt()?.takeIf { it >= 0 } ?: return null
        return HostAgentRegion(id, hostText(hostRead(name, item), 100), regionType)
    }

    companion object {
        fun resolve(owner: Class<*>): HostAgentRegionReader? = runCatching {
            HostAgentRegionReader(hostGetter(owner, "getTid", classOf<Int>()) ?: return null,
                hostGetter(owner, "getName", classOf<String>()) ?: return null,
                hostGetter(owner, "getRegionType", classOf<Int>()) ?: return null)
        }.getOrNull()
    }
}

/** 只持有已验证的反射成员；每次创建有截止时间的 Moss，不保存请求或响应对象。 */
private class DiscoveryListCall private constructor(
    private val factory: HostAgentMossFactory,
    val requestType: Class<*>,
    private val responseType: Class<*>,
    private val newBuilder: Method,
    private val build: Method,
    private val execute: Method,
    private val list: Method
) {
    val builderType: Class<*> get() = newBuilder.returnType

    fun invoke(timeoutMs: Long, readFacts: (Any) -> Unit = {}, configure: (Any) -> Unit = {}): List<*> {
        val builder = checkNotNull(newBuilder.invoke(null))
        configure(builder)
        val request = checkNotNull(build.invoke(builder))
        check(requestType.isInstance(request)) { "discovery_request_unavailable" }
        val response = checkNotNull(execute.invoke(factory.create(timeoutMs), request))
        check(responseType.isInstance(response)) { "discovery_reply_unavailable" }
        readFacts(response)
        return list.invoke(response) as? List<*> ?: error("discovery_items_unavailable")
    }

    companion object {
        fun resolve(loader: ClassLoader, base: String, mossName: String, requestName: String,
                    responseName: String, executeName: String, listName: String): DiscoveryListCall? = runCatching {
            val moss = KavaMemberLookup.classOrNull(loader, base + mossName) ?: return null
            val request = KavaMemberLookup.classOrNull(loader, base + requestName) ?: return null
            val response = KavaMemberLookup.classOrNull(loader, base + responseName) ?: return null
            val builder = hostBuilder(request) ?: return null
            DiscoveryListCall(HostAgentMossFactory.resolve(loader, moss) ?: return null, request, response,
                builder, hostBuild(builder.returnType) ?: return null,
                KavaMemberLookup.methodOrNull(moss, executeName, request)
                    ?.takeIf { !it.isStatic && it.returnType == response } ?: return null,
                hostGetter(response, listName)?.takeIf { it.returnType isSubclassOf classOf<List<*>>() }
                    ?: return null)
        }.getOrNull()
    }
}

/** 9.14.0 Rank.Item 的直接事实投影；不追随 redirect_url，也不调用未验真的额外信息。 */
internal class HostAgentRankItemReader private constructor(
    private val goto: Method, private val param: Method, private val uri: Method, private val title: Method,
    private val author: Method, private val mid: Method, private val duration: Method
) {
    fun read(item: Any?): HostAgentVideo? {
        if (hostRead(goto, item) != "av") return null
        val id = HostAgentNavigationPolicy.candidateId(hostRead(param, item) as? String, hostRead(uri, item) as? String)
            ?: return null
        return HostAgentVideo(id, hostText(hostRead(title, item), 300), hostText(hostRead(author, item), 100),
            (hostRead(mid, item) as? Number)?.toLong()?.takeIf { it > 0 },
            (hostRead(duration, item) as? Number)?.toLong()?.takeIf { it >= 0 }?.let(::discoveryDuration))
    }

    companion object {
        fun resolve(owner: Class<*>): HostAgentRankItemReader? = runCatching {
            HostAgentRankItemReader(hostGetter(owner, "getGoto", classOf<String>()) ?: return null,
                hostGetter(owner, "getParam", classOf<String>()) ?: return null,
                hostGetter(owner, "getUri", classOf<String>()) ?: return null,
                hostGetter(owner, "getTitle", classOf<String>()) ?: return null,
                hostGetter(owner, "getName", classOf<String>()) ?: return null,
                hostGetter(owner, "getMid", classOf<Long>()) ?: return null,
                hostGetter(owner, "getDuration", classOf<Long>()) ?: return null)
        }.getOrNull()
    }
}

/** 只读取普通 SmallCoverV5 视频；广告、入口、话题及未验证卡片不加入任务候选集合。 */
internal class HostAgentPopularCardReader private constructor(
    private val hasCard: Method, private val getCard: Method, private val hasBase: Method, private val getBase: Method,
    private val hasAd: Method, private val goto: Method, private val param: Method, private val uri: Method,
    private val title: Method, private val hasArgs: Method, private val getArgs: Method,
    private val author: Method, private val mid: Method
) {
    fun read(item: Any?): HostAgentVideo? {
        if (hostRead(hasCard, item) != true) return null
        val card = hostRead(getCard, item) ?: return null
        if (hostRead(hasBase, card) != true) return null
        val base = hostRead(getBase, card) ?: return null
        if (hostRead(hasAd, base) != false || hostRead(goto, base) != "av") return null
        val id = HostAgentNavigationPolicy.candidateId(hostRead(param, base) as? String, hostRead(uri, base) as? String)
            ?: return null
        val args = if (hostRead(hasArgs, base) == true) hostRead(getArgs, base) else null
        return HostAgentVideo(id, hostText(hostRead(title, base), 300), hostText(hostRead(author, args), 100),
            (hostRead(mid, args) as? Number)?.toLong()?.takeIf { it > 0 }, null)
    }

    companion object {
        fun resolve(owner: Class<*>, baseType: Class<*>): HostAgentPopularCardReader? = runCatching {
            val getCard = hostGetter(owner, "getSmallCoverV5") ?: return null
            val cardType = getCard.returnType
            val getBase = hostGetter(cardType, "getBase", baseType) ?: return null
            val getArgs = hostGetter(baseType, "getArgs") ?: return null
            HostAgentPopularCardReader(hostGetter(owner, "hasSmallCoverV5", classOf<Boolean>()) ?: return null,
                getCard, hostGetter(cardType, "hasBase", classOf<Boolean>()) ?: return null, getBase,
                hostGetter(baseType, "hasAdInfo", classOf<Boolean>()) ?: return null,
                hostGetter(baseType, "getGoto", classOf<String>()) ?: return null,
                hostGetter(baseType, "getParam", classOf<String>()) ?: return null,
                hostGetter(baseType, "getUri", classOf<String>()) ?: return null,
                hostGetter(baseType, "getTitle", classOf<String>()) ?: return null,
                hostGetter(baseType, "hasArgs", classOf<Boolean>()) ?: return null, getArgs,
                hostGetter(getArgs.returnType, "getUpName", classOf<String>()) ?: return null,
                hostGetter(getArgs.returnType, "getUpId", classOf<Long>()) ?: return null)
        }.getOrNull()
    }
}

/** 分页跟随最后一条原始卡片，不能跟随过滤后的视频，否则会重复或跳过宿主条目。 */
internal class HostAgentPopularPositionReader private constructor(
    private val variants: List<Variant>, private val idx: Method, private val param: Method
) {
    fun read(item: Any?): Pair<Long, String>? {
        val variant = variants.filter { hostRead(it.has, item) == true }.singleOrNull() ?: return null
        val card = hostRead(variant.get, item) ?: return null
        if (hostRead(variant.hasBase, card) != true) return null
        val base = hostRead(variant.getBase, card) ?: return null
        val index = (hostRead(idx, base) as? Number)?.toLong()?.takeIf { it >= 0 } ?: return null
        val parameter = hostRead(param, base) as? String ?: return null
        return index to parameter
    }

    private class Variant(val has: Method, val get: Method, val hasBase: Method, val getBase: Method)

    companion object {
        private val VARIANTS = listOf("HotTopic", "LargeCoverV1", "LargeCoverV4", "MiddleCoverV3",
            "PopularTopEntrance", "RcmdOneItem", "SmallCoverV5", "SmallCoverV5Ad", "ThreeItemAllV2",
            "ThreeItemHV5", "ThreeItemV1", "TopicList")

        fun resolve(owner: Class<*>, baseType: Class<*>): HostAgentPopularPositionReader? = runCatching {
            val variants = VARIANTS.mapNotNull { suffix ->
                val has = hostGetter(owner, "has$suffix", classOf<Boolean>()) ?: return@mapNotNull null
                val get = hostGetter(owner, "get$suffix") ?: return@mapNotNull null
                val hasBase = hostGetter(get.returnType, "hasBase", classOf<Boolean>()) ?: return@mapNotNull null
                val getBase = hostGetter(get.returnType, "getBase", baseType) ?: return@mapNotNull null
                Variant(has, get, hasBase, getBase)
            }
            if (variants.isEmpty()) return null
            HostAgentPopularPositionReader(variants, hostGetter(baseType, "getIdx", classOf<Long>()) ?: return null,
                hostGetter(baseType, "getParam", classOf<String>()) ?: return null)
        }.getOrNull()
    }
}

private fun discoverySetter(owner: Class<*>, name: String, type: Class<*>): Method? =
    KavaMemberLookup.methodOrNull(owner, name, type)?.takeIf {
        !it.isStatic && it.parameterCount == 1 && it.returnType == owner
    }

private fun discoveryDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remainder = seconds % 60
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
    else "$minutes:${remainder.toString().padStart(2, '0')}"
}

/** 仅调用方会话内保存，外部 Agent 只能拿到会话绑定的无语义令牌。 */
internal data class HostAgentPopularCursor(val idx: Long, val lastParam: String, val version: String, val page: Int) {
    fun next(nextIdx: Long, nextParam: String, nextVersion: String): String? {
        if (page >= HostAgentDiscoveryProjection.MAX_PAGE || nextIdx <= 0 || nextIdx == idx ||
            !validPart(nextParam, 1000) || !validPart(nextVersion, 512)) return null
        return JSONObject().put("idx", nextIdx.toString()).put("last_param", nextParam).put("version", nextVersion)
            .put("page", page + 1).toString().takeIf { it.length <= 2048 }
    }

    companion object {
        fun parse(raw: String?): HostAgentPopularCursor? {
            if (raw == null) return HostAgentPopularCursor(0L, "", "", 1)
            if (raw.length > 2048) return null
            return runCatching {
                val json = JSONObject(raw)
                if (json.keys().asSequence().toSet() != setOf("idx", "last_param", "version", "page")) return null
                val id = json.get("idx") as? String ?: return null
                if (!Regex("[1-9][0-9]{0,18}").matches(id)) return null
                val idx = id.toLongOrNull()?.takeIf { it > 0 } ?: return null
                val param = json.get("last_param") as? String ?: return null
                val version = json.get("version") as? String ?: return null
                val page = (json.get("page") as? Int)?.takeIf { it in 2..HostAgentDiscoveryProjection.MAX_PAGE } ?: return null
                if (!validPart(param, 1000) || !validPart(version, 512)) return null
                HostAgentPopularCursor(idx, param, version, page)
            }.getOrNull()
        }

        private fun validPart(value: String, maximum: Int): Boolean =
            value.length <= maximum && value.none { it.isISOControl() }
    }
}

/** 榜单位置属于原始响应，过滤卡片不能把后面的条目提升为第一名。 */
internal object HostAgentDiscoveryProjection {
    const val PAGE_SIZE = 20
    const val MAX_PAGE = 10

    fun rankingPage(cursor: String?): Int? = when {
        cursor == null -> 1
        !Regex("rank:[2-9]|rank:10").matches(cursor) -> null
        else -> cursor.removePrefix("rank:").toIntOrNull()
    }

    fun regionId(raw: String?): Int? = raw?.takeIf { Regex("0|[1-9][0-9]{0,8}").matches(it) }
        ?.toIntOrNull()?.takeIf { it >= 0 }

    fun regions(items: List<HostAgentRegion?>): JSONObject {
        val entries = JSONArray()
        val seen = hashSetOf<Pair<Int, Int>>()
        for (region in items.take(100)) {
            if (region == null || region.id < 0 || region.type < 0 || !seen.add(region.type to region.id)) continue
            entries.put(JSONObject().put("region_id", region.id.toString()).put("region_type", region.type)
                .put("name", hostText(region.name, 100) ?: JSONObject.NULL))
        }
        return JSONObject().put("source", "host_rpc").put("dataset", "ranking_regions").put("regions", entries)
    }

    fun popular(items: List<HostAgentVideo?>, rawCount: Int, cursor: HostAgentPopularCursor,
                lastPosition: Pair<Long, String>?, version: String?): HostAgentDataPage {
        val truncated = rawCount > 100 || items.filterNotNull().distinctBy { it.id }.size > PAGE_SIZE
        val next = if (!truncated && lastPosition != null && version != null)
            cursor.next(lastPosition.first, lastPosition.second, version) else null
        return page("popular", items, page = cursor.page, next = next).also {
            it.data.put("page", cursor.page).put("truncated", truncated)
        }
    }

    fun ranking(items: List<HostAgentVideo?>, rawCount: Int, page: Int, regionId: String?): HostAgentDataPage {
        val truncated = rawCount > PAGE_SIZE
        check(!truncated || page == 1) { "ranking_page_size_mismatch" }
        val next = if (rawCount == PAGE_SIZE && page < MAX_PAGE) "rank:${page + 1}" else null
        return page("ranking", items.take(PAGE_SIZE), page, regionId, next).also { it.data.put("truncated", truncated) }
    }

    fun page(dataset: String, items: List<HostAgentVideo?>, page: Int = 1, regionId: String? = null,
             next: String? = null): HostAgentDataPage {
        require(dataset == "ranking" || dataset == "popular")
        require(page in 1..MAX_PAGE)
        val videos = mutableListOf<HostAgentVideo>()
        val entries = JSONArray()
        val seen = hashSetOf<String>()
        for ((index, video) in items.take(100).withIndex()) {
            if (video == null || HostAgentNavigationPolicy.videoId(video.id) != video.id || !seen.add(video.id)) continue
            val entry = video.json()
            if (dataset == "ranking") entry.put("ranking_position", (page - 1) * PAGE_SIZE + index + 1)
                .put("position_basis", "server_rank_list_order")
            else entry.put("source_position", index + 1).put("position_basis", "popular_feed_order")
            entries.put(entry)
            videos += video
            if (videos.size == PAGE_SIZE) break
        }
        val data = JSONObject().put("source", "host_rpc").put("dataset", dataset).put("videos", entries)
        if (dataset == "ranking") data.put("region_id", regionId ?: JSONObject.NULL).put("page", page)
        return HostAgentDataPage(data, videos, next)
    }
}
