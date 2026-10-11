package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.HostAgentNavigationPolicy
import org.json.JSONArray
import org.json.JSONObject

/** 只识别明确业务目标；能力或工具成功本身不能充当榜单、统计或直播事实。 */
internal class AgentGoalEvidence(private val goal: String) {
    val seedVideos = seedVideoIds(goal)
    val seedUsers = seedUserIds(goal)
    private val literalSearch = Regex("搜索关键词|搜关键词|标题包含|搜索[‘\"“]|搜[‘\"“]").containsMatchIn(goal)
    val ranking = !literalSearch && Regex("热榜|排行榜|榜单|全站榜").containsMatchIn(goal)
    val regionRequested = ranking && Regex("分区|区(?:的)?(?:热榜|排行榜|榜单)|(?:游戏|音乐|动画|舞蹈|科技|鬼畜|知识|生活|影视|番剧|国创|娱乐|汽车|美食|运动)(?:热榜|排行榜|榜单|榜)").containsMatchIn(goal)
    private val rankText = Regex("第([零一二两三四五六七八九十百0-9]+)(?:名|个|条|位)?").find(goal)?.groupValues?.get(1)
    private val topText = Regex("(?:前|TOP)\\s*([零一二两三四五六七八九十百0-9]+)", RegexOption.IGNORE_CASE).find(goal)?.groupValues?.get(1)
    val desiredRank = rankText?.let(::boundedNumber) ?: 1
    val desiredTop = topText?.let(::boundedNumber)
    private val invalidRank = rankText != null && boundedNumber(rankText) == null || topText != null && boundedNumber(topText) == null
    val popular = !ranking && !literalSearch && goal.contains("热门")
    val history = Regex("历史记录|观看记录|最近看过").containsMatchIn(goal)
    val following = Regex("关注.*动态|动态.*关注|关注列表动态").containsMatchIn(goal)
    val live = Regex("直播状态|是否.*直播|在不在直播|有没有.*开播|正在直播|开播了吗").containsMatchIn(goal)
    val userVideos = Regex("投稿|发布的视频|作品列表").containsMatchIn(goal)
    val comments = Regex("评论内容|评论正文|评论区说|评论区.*观点|(?:看看|查看)评论(?!数|量|的数量)|读.*评论(?!数|量|的数量)").containsMatchIn(goal)
    val wantsPlaying = Regex("播放(?!量)").containsMatchIn(goal) && (seedVideos.isNotEmpty() || ranking || goal.contains("视频"))
    val wantsOpen = wantsPlaying || Regex("打开|点开").containsMatchIn(goal) && (seedVideos.isNotEmpty() || ranking && rankText != null)
    val statisticFields = linkedMapOf("view_count" to "播放量|观看量", "like_count" to "点赞数|点赞量",
        "coin_count" to "投币数|硬币数", "favorite_count" to "收藏数|收藏量", "share_count" to "分享数",
        "comment_count" to "评论(?:的)?(?:数量|数|量)", "danmaku_count" to "弹幕数|弹幕量")
        .filterValues { Regex(it).containsMatchIn(goal) }.keys
    private val satisfied = hashSetOf<String>()
    private val attempted = hashSetOf<String>()
    private val ranked = mutableMapOf<Int, String>()
    private var region: String? = if (regionRequested) null else "0"
    private var limitationReason = ""
    private val canonicalVideos = mutableMapOf<String, String>()
    private val metricVideos = linkedSetOf<String>()
    private val liveUsers = linkedSetOf<String>()
    private val archivedUsers = linkedSetOf<String>()
    private val commentVideos = linkedSetOf<String>()
    private val openedVideos = linkedSetOf<String>()
    private var openRequested: String? = null
    private var opened = false
    private var currentVideo: String? = null
    private var firstRanked: String? = null

    val required: Set<String> get() = linkedSetOf<String>().apply {
        if (ranking) add("get_ranking")
        if (popular) add("get_popular")
        if (history) add("get_history")
        if (following) add("get_following_feed")
        if (live) add("get_live_status")
        if (userVideos) add("get_user_videos")
        if (comments) add("get_video_comments")
        if (statisticFields.isNotEmpty()) add("get_video_metrics")
    }

    fun initial(): Pair<String, JSONObject>? = when {
        ranking && regionRequested && region == null -> "get_regions" to JSONObject()
        ranking -> "get_ranking" to JSONObject().apply { region?.takeIf { it != "0" }?.let { put("region_id", it) } }
        popular -> "get_popular" to JSONObject()
        history -> "get_history" to JSONObject()
        following -> "get_following_feed" to JSONObject()
        live && seedUsers.isNotEmpty() -> "get_live_status" to JSONObject().put("user_id", seedUsers.first())
        userVideos && seedUsers.isNotEmpty() -> "get_user_videos" to JSONObject().put("user_id", seedUsers.first())
        comments && seedVideos.isNotEmpty() -> (if (seedVideos.first().startsWith("BV")) "get_video_details" else "get_video_comments") to
            JSONObject().put("video_id", seedVideos.first())
        statisticFields.isNotEmpty() && seedVideos.isNotEmpty() -> "get_video_metrics" to JSONObject().put("video_id", seedVideos.first())
        statisticFields.isNotEmpty() -> "get_host_state" to JSONObject()
        wantsOpen && seedVideos.isNotEmpty() -> "get_video_details" to JSONObject().put("video_id", seedVideos.first())
        else -> null
    }

    fun missingNative(routing: AgentNativeRoutingPolicy): String? = required.firstOrNull {
        routing.nativeActive && routing.supported(it) == true && it !in attempted
    }

    fun wrongSearch(operation: String, args: JSONObject): Boolean = ranking && operation in setOf("search_videos", "input_ui_text") &&
        Regex("(?:热榜|排行榜|榜单)(?:第[一1]|第一名|首位|第一)").containsMatchIn(args.optString(if (operation == "search_videos") "query" else "text").replace(" ", ""))

    fun record(operation: String, args: JSONObject, response: JSONObject) {
        if (!response.optBoolean("ok")) {
            val error = response.optString("error")
            if (error !in setOf("goal_route_required", "native_evidence_required")) {
                attempted += operation
                limitationReason = error.takeIf { it.matches(Regex("[a-z_]{1,80}")) }.orEmpty()
            }
            return
        }
        val data = response.optJSONObject("data") ?: return
        if (operation == "get_capabilities" && required.any { data.opt(it) == false }) limitationReason = "native_interface_unavailable"
        if (operation == "open_video" && data.optString("navigation") == "requested") {
            val requested = HostAgentNavigationPolicy.videoId(args.optString("video_id"))
            if (requested != null && (expectedVideos().isEmpty() || canonical(requested) in expectedVideos())) openRequested = requested
            if (wantsOpen) attempted += operation
        }
        if (operation == "get_host_state" && data.optBoolean("foreground") && data.optBoolean("interaction_guard"))
            currentVideo = HostAgentNavigationPolicy.videoId(data.optString("video_id"))
        if (operation == "get_host_state" && AgentNavigationEvidence.confirmed(data, openRequested, ::canonical)) {
            opened = true
            openRequested?.let { openedVideos += canonical(it) }
        }
        if (data.optString("source") != "host_rpc") { recordUi(data); return }
        if (operation in required || operation == "get_regions" || wantsOpen && operation in setOf("get_video_details", "open_video", "get_host_state")) attempted += operation
        val videos = data.optJSONArray("videos") ?: data.optJSONArray("items") ?: JSONArray()
        when (operation) {
            "get_video_details" -> {
                val canonical = HostAgentNavigationPolicy.videoId(data.optString("canonical_video_id"))?.takeIf { it.startsWith("av") }
                val requested = HostAgentNavigationPolicy.videoId(args.optString("video_id"))
                if (canonical != null && requested != null && data.optString("video_id") == requested) {
                    canonicalVideos[requested] = canonical
                    canonicalVideos[canonical] = canonical
                    while (canonicalVideos.size > 256) canonicalVideos.remove(canonicalVideos.keys.first())
                }
            }
            "get_regions" -> if (data.optString("dataset") == "ranking_regions" && regionRequested) {
                val entries = data.optJSONArray("regions") ?: JSONArray()
                val matched = (0 until minOf(entries.length(), 100)).mapNotNull { entries.optJSONObject(it) }.filter {
                    val name = (it.opt("name") as? String).orEmpty().removeSuffix("区")
                    name.isNotBlank() && (Regex(Regex.escape(name) + "(?:区|分区)?(?:的)?(?:热榜|排行榜|榜单|榜)").containsMatchIn(goal) ||
                        goal.contains(name + "分区") || goal.contains(name + "区")) &&
                        it.optInt("region_type", -1) == 2 && it.optString("region_id") != "0"
                }.map { it.optString("region_id") }.distinct()
                region = matched.singleOrNull()
                if (region == null) limitationReason = "requested_region_unverified"
            }
            "get_ranking" -> if (data.optString("dataset") == "ranking") {
                val actualRegion = if (data.isNull("region_id") || data.optString("region_id").isBlank()) "0" else data.optString("region_id")
                if (region == null || actualRegion != region) { limitationReason = "ranking_scope_mismatch"; return }
                for (index in 0 until minOf(videos.length(), 20)) {
                    val row = videos.optJSONObject(index) ?: continue
                    val position = row.optInt("ranking_position", -1).takeIf { it in 1..200 } ?: continue
                    HostAgentNavigationPolicy.videoId(row.optString("video_id"))?.let { ranked[position] = it }
                }
                firstRanked = ranked[desiredRank]
                if (!invalidRank && (desiredTop?.let { count -> (1..count).all(ranked::containsKey) } ?: (firstRanked != null))) satisfied += operation
                else limitationReason = "requested_rank_unverified"
            }
            "get_popular" -> if (data.optString("dataset") == "popular" && videos.length() > 0) satisfied += operation
            "get_history" -> if (data.optString("dataset") == "history" && videos.length() > 0) satisfied += operation
            "get_user_videos" -> if (data.optString("dataset") == "user_videos" && videos.length() > 0 &&
                data.optString("requested_user_id") == args.optString("user_id") && (seedUsers.isEmpty() || data.optString("requested_user_id") in seedUsers)) {
                archivedUsers += data.optString("requested_user_id")
                if (seedUsers.isEmpty() || seedUsers.all(archivedUsers::contains)) satisfied += operation
            }
            "get_following_feed" -> if (data.optString("dataset") == "following_feed" && data.optJSONArray("posts")?.length()?.let { it > 0 } == true)
                satisfied += operation
            "get_video_comments" -> if (data.optString("dataset") == "comments" && data.optJSONArray("comments")?.let { rows ->
                (0 until minOf(rows.length(), 20)).any { (rows.optJSONObject(it)?.opt("message") as? String)?.isNotBlank() == true }
            } == true && data.optString("video_id") == args.optString("video_id") &&
                (expectedVideos().isEmpty() || canonical(data.optString("video_id")) in expectedVideos())) {
                    commentVideos += canonical(data.optString("video_id"))
                    if (expectedVideos().isEmpty() || expectedVideos().all(commentVideos::contains)) satisfied += operation
                }
            "get_live_status" -> if (data.optString("dataset") == "up_live_status" && data.optString("live_status") in setOf("live", "offline", "replay") &&
                data.optString("user_id") == args.optString("user_id") && (seedUsers.isEmpty() || data.optString("user_id") in seedUsers)) {
                liveUsers += data.optString("user_id")
                if (seedUsers.isEmpty() || seedUsers.all(liveUsers::contains)) satisfied += operation
            }
            "get_video_metrics" -> {
                val id = HostAgentNavigationPolicy.videoId(data.optString("video_id")) ?: return
                val statistics = data.optJSONObject("statistics") ?: return
                if ((expectedVideos().isEmpty() || canonical(id) in expectedVideos()) && canonical(id) == canonical(args.optString("video_id")) && statistics.optString("status") == "reported" &&
                    statisticFields.all { key -> (statistics.opt(key) as? Number)?.toLong()?.let { it >= 0 } == true }) {
                    metricVideos += canonical(id)
                    if (expectedVideos().isEmpty() || expectedVideos().all(metricVideos::contains)) satisfied += operation
                }
            }
        }
        listOf(metricVideos, liveUsers, archivedUsers, commentVideos, openedVideos).forEach { values ->
            while (values.size > 256) values.remove(values.first())
        }
        if (operation in required && operation !in satisfied && limitationReason.isEmpty()) limitationReason = "target_fields_unknown"
    }

    private fun recordUi(data: JSONObject) {
        if (data.optString("backend") != "accessibility" || data.optBoolean("historical")) return
        if (data.optJSONArray("nodes")?.length()?.let { it > 0 } == true) attempted += "get_ui_state"
        // 当前快照没有排行行/视频身份/统计控件的验真绑定；标题和散落数字不是业务 DTO。
    }

    private fun canonical(id: String): String = canonicalVideos[id] ?: id
    private fun expectedVideos(): Set<String> = when {
        ranking && desiredTop != null -> (1..desiredTop).mapNotNull(ranked::get).map(::canonical).toSet()
        ranking -> listOfNotNull(firstRanked).map(::canonical).toSet()
        seedVideos.isNotEmpty() -> seedVideos.map(::canonical).toSet()
        else -> listOfNotNull(currentVideo).map(::canonical).toSet()
    }
    fun complete(): Boolean = !invalidRank && required.all { it in satisfied } && (!wantsOpen || opened && expectedVideos().all(openedVideos::contains)) && !wantsPlaying &&
        (statisticFields.isEmpty() || expectedVideos().isEmpty() || expectedVideos().all(metricVideos::contains)) &&
        (!comments || expectedVideos().isEmpty() || expectedVideos().all(commentVideos::contains)) &&
        !(userVideos && Regex("全部|所有|完整").containsMatchIn(goal))
    fun preferredVideo(): String? = (firstRanked ?: seedVideos.firstOrNull() ?: currentVideo)?.let { canonicalVideos[it] ?: it }
    fun targetVideoIds(): List<String> = expectedVideos().toList()
    fun selectedRegion(): String? = region
    fun canReportLimitation(): Boolean = attempted.isNotEmpty() || limitationReason.isNotEmpty()
    fun limitation(): String = buildString {
        append("尚未取得满足用户目标的完整证据。")
        when {
            invalidRank -> append("请求名次或前N范围尚未有效解析，当前有界读取最多200位。")
            ranking && regionRequested && region == null -> append("尚未核实目标分区及其原生榜单接口，不能用综合榜代替。")
            ranking && "get_ranking" !in satisfied -> append("尚未核实目标榜单范围和名次对应的视频身份，搜索结果或热门首条不能替代。")
            userVideos && Regex("全部|所有|完整").containsMatchIn(goal) -> append("当前投稿接口仅返回宿主默认空关键词范围的当前页，不能认定是全部投稿。")
            history -> append("当前历史读取只覆盖返回的视频条目，不代表全部历史类型。")
            live -> append("直播查询只覆盖当前账号本页UP名单；未知状态不能写成未开播。")
            statisticFields.isNotEmpty() -> append("所需统计字段仍缺失或身份未核实，不能把未知数值写成0。")
            wantsPlaying -> append("仅打开详情页不能证明正在播放；当前尚未验证真实播放控件或播放时间推进。")
            wantsOpen -> append("尚未观察到对应视频已实际进入前台任务详情页，导航请求不能替代打开结果。")
            following -> append("当前关注动态只覆盖返回页，空页不能证明没有动态。")
            comments -> append("尚未取得目标视频的真实评论正文；评论数不能替代评论内容。")
            else -> append("接口或目标字段没有提供足够可核对的资料。")
        }
    }

    companion object {
        private fun boundedNumber(raw: String): Int? {
            raw.toIntOrNull()?.let { return it.takeIf { number -> number in 1..200 } }
            val digits = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
            fun part(text: String): Int? {
                if (text.isEmpty()) return 0
                if (text.length == 1) return if (text == "十") 10 else digits[text.single()]
                val tens = text.split('十')
                if (tens.size != 2 || tens[0].length > 1 || tens[1].length > 1) return null
                val left = if (tens[0].isEmpty()) 1 else digits[tens[0].single()] ?: return null
                val right = if (tens[1].isEmpty()) 0 else digits[tens[1].single()] ?: return null
                return left * 10 + right
            }
            val hundreds = raw.split('百')
            val value = if (hundreds.size == 1) part(raw) else if (hundreds.size == 2 && hundreds[0].length == 1 && hundreds[1] != "零")
                digits[hundreds[0].single()]?.let { it * 100 + (part(hundreds[1].removePrefix("零")) ?: return null) } else null
            return value?.takeIf { it in 1..200 }
        }
        fun seedVideoIds(goal: String): List<String> = Regex("(?<![A-Za-z0-9])(?:BV[0-9A-Za-z]{10}|av[1-9][0-9]{0,18})(?![A-Za-z0-9])")
            .findAll(goal).mapNotNull { HostAgentNavigationPolicy.videoId(it.value) }.distinct().take(8).toList()
        fun seedUserIds(goal: String): List<String> = Regex("(?<![A-Za-z0-9])(?:UID|MID|用户\\s*ID)\\s*[:：=]?\\s*([1-9][0-9]{0,18})(?![0-9])", RegexOption.IGNORE_CASE)
            .findAll(goal).map { it.groupValues[1] }.filter { (it.toLongOrNull() ?: 0L) > 0 }.distinct().take(8).toList()
    }
}
