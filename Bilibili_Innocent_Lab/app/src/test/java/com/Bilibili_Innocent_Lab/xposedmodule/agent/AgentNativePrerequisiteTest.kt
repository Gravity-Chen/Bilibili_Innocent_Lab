package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 模型请求之前取得明确目标的原生事实；未知字段和失败不能成为自动重试循环。 */
class AgentNativePrerequisiteTest {
    private fun next(evidence: AgentGoalEvidence, operation: String, identity: String? = null): Pair<String, JSONObject> {
        val read = checkNotNull(evidence.nextNativeRead { true })
        assertEquals(operation, read.first)
        if (identity != null) assertEquals(identity, read.second.optString(if (operation in setOf("get_live_status", "get_user_videos")) "user_id" else "video_id"))
        return read
    }
    private fun record(evidence: AgentGoalEvidence, read: Pair<String, JSONObject>, data: JSONObject) =
        evidence.record(read.first, read.second, JSONObject().put("ok", true).put("data", data))
    private fun ranking(vararg rows: Pair<Int, String>, region: String = "0") = JSONObject().put("source", "host_rpc").put("dataset", "ranking")
        .put("region_id", region).put("videos", JSONArray(rows.map { (position, id) -> JSONObject().put("video_id", id).put("ranking_position", position) }))
    private fun metrics(id: String, value: Any = 42L) = JSONObject().put("source", "host_rpc").put("video_id", id)
        .put("statistics", JSONObject().put("status", "reported").put("view_count", value))
    private fun comments(id: String) = JSONObject().put("source", "host_rpc").put("dataset", "comments").put("video_id", id)
        .put("comments", JSONArray().put(JSONObject().put("video_id", id).put("message", "实际评论正文")))

    @Test fun rankingThenItsVerifiedIdentityMetricsAreReadBeforePlanning() {
        val evidence = AgentGoalEvidence("查全站热榜第一名视频的真实视频ID、标题和当前播放量")
        record(evidence, next(evidence, "get_ranking"), ranking(2 to "av2", 1 to "av42"))
        assertFalse(evidence.complete())
        record(evidence, next(evidence, "get_video_metrics", "av42"), metrics("av42"))
        assertTrue(evidence.complete())
        assertNull(evidence.nextNativeRead { true })
    }

    @Test fun desiredRankMetricsDoNotUseFirstReturnedCard() {
        val evidence = AgentGoalEvidence("查热榜第二名视频的播放量")
        record(evidence, next(evidence, "get_ranking"), ranking(1 to "av1", 2 to "av2"))
        record(evidence, next(evidence, "get_video_metrics", "av2"), metrics("av2"))
        assertTrue(evidence.complete())
        assertNull(evidence.nextNativeRead { true })
    }

    @Test fun missingRankingPositionDoesNotGenerateAnInventedMetricIdentity() {
        val evidence = AgentGoalEvidence("查热榜第一名的播放量")
        record(evidence, next(evidence, "get_ranking"), ranking(2 to "av2"))
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
        assertTrue(evidence.limitation().contains("名次"))
    }

    @Test fun regionalCatalogMustVerifyRegionBeforeReadingRankingAndMetrics() {
        val evidence = AgentGoalEvidence("查游戏分区热榜第一名的播放量")
        record(evidence, next(evidence, "get_regions"), JSONObject().put("source", "host_rpc").put("dataset", "ranking_regions")
            .put("regions", JSONArray().put(JSONObject().put("name", "游戏").put("region_id", "4").put("region_type", 2))))
        val rank = next(evidence, "get_ranking")
        assertEquals("4", rank.second.getString("region_id"))
        record(evidence, rank, ranking(1 to "av4", region = "4"))
        record(evidence, next(evidence, "get_video_metrics", "av4"), metrics("av4"))
        assertTrue(evidence.complete())
    }

    @Test fun unverifiedRegionNeverFallsBackToGlobalRanking() {
        val evidence = AgentGoalEvidence("查游戏分区热榜第一名的播放量")
        record(evidence, next(evidence, "get_regions"), JSONObject().put("source", "host_rpc").put("dataset", "ranking_regions").put("regions", JSONArray()))
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
    }

    @Test fun missingStatisticIsRetainedAsLimitedAndNotAutomaticallyRetried() {
        val evidence = AgentGoalEvidence("查热榜第一名播放量")
        record(evidence, next(evidence, "get_ranking"), ranking(1 to "av1"))
        record(evidence, next(evidence, "get_video_metrics", "av1"), metrics("av1", JSONObject.NULL))
        repeat(4) { assertNull(evidence.nextNativeRead { true }) }
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
        assertTrue(evidence.limitation().contains("未知数值"))
    }

    @Test fun failedIdentityIsNotRetriedWhileOtherExplicitVideosStillGetRead() {
        val evidence = AgentGoalEvidence("查看 av1 和 av2 的播放量")
        val first = next(evidence, "get_video_metrics", "av1")
        evidence.record(first.first, first.second, JSONObject().put("ok", false).put("error", "rpc_failed"))
        record(evidence, next(evidence, "get_video_metrics", "av2"), metrics("av2"))
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
    }

    @Test fun explicitMultipleVideosReadBothMetricsAndCommentsWithTheirOwnIdentity() {
        val evidence = AgentGoalEvidence("查看 av1 与 av2 的播放量和评论内容")
        record(evidence, next(evidence, "get_video_comments", "av1"), comments("av1"))
        record(evidence, next(evidence, "get_video_metrics", "av1"), metrics("av1"))
        assertFalse(evidence.complete())
        record(evidence, next(evidence, "get_video_comments", "av2"), comments("av2"))
        record(evidence, next(evidence, "get_video_metrics", "av2"), metrics("av2"))
        assertTrue(evidence.complete())
        assertNull(evidence.nextNativeRead { true })
    }

    @Test fun bvCommentsWaitForActualCanonicalAvThenQueryTheSameVideoMetrics() {
        val bv = "BV0000000001"
        val evidence = AgentGoalEvidence("查看 $bv 的播放量和评论内容")
        record(evidence, next(evidence, "get_video_details", bv), JSONObject().put("source", "host_rpc").put("video_id", bv).put("canonical_video_id", "av42"))
        record(evidence, next(evidence, "get_video_comments", "av42"), comments("av42"))
        record(evidence, next(evidence, "get_video_metrics", "av42"), metrics("av42"))
        assertTrue(evidence.complete())
        assertNull(evidence.nextNativeRead { true })
    }

    @Test fun failedBvResolutionNeverGuessesTheCommentOid() {
        val evidence = AgentGoalEvidence("查看 BV0000000001 的评论内容")
        val read = next(evidence, "get_video_details", "BV0000000001")
        evidence.record(read.first, read.second, JSONObject().put("ok", false).put("error", "rpc_failed"))
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
    }

    @Test fun actualAliasLearnedAfterMetricsPreservesEvidenceWithoutReadingTheSameVideoAgain() {
        val bv = "BV0000000001"
        val evidence = AgentGoalEvidence("查看 $bv 的播放量")
        record(evidence, next(evidence, "get_video_metrics", bv), metrics(bv))
        evidence.record("get_video_details", JSONObject().put("video_id", bv), JSONObject().put("ok", true)
            .put("data", JSONObject().put("source", "host_rpc").put("video_id", bv).put("canonical_video_id", "av42")))
        assertNull(evidence.nextNativeRead { true })
        assertTrue(evidence.complete())
    }

    @Test fun bothExplicitUsersReceiveSeparateLiveAndArchiveFirstReads() {
        val evidence = AgentGoalEvidence("查看 UID 1 与 UID 2 的直播状态及投稿")
        for (id in listOf("1", "2")) {
            record(evidence, next(evidence, "get_live_status", id), JSONObject().put("source", "host_rpc").put("dataset", "up_live_status")
                .put("user_id", id).put("live_status", "offline"))
            record(evidence, next(evidence, "get_user_videos", id), JSONObject().put("source", "host_rpc").put("dataset", "user_videos")
                .put("requested_user_id", id).put("items", JSONArray().put(JSONObject().put("video_id", "av$id"))))
            if (id == "1") assertFalse(evidence.complete())
        }
        assertTrue(evidence.complete())
        assertNull(evidence.nextNativeRead { true })
    }

    @Test fun unknownLiveStatusDoesNotCauseAutomaticRepeatOrSkipTheOtherUser() {
        val evidence = AgentGoalEvidence("查看 UID 1 和 UID 2 的直播状态")
        for (id in listOf("1", "2")) record(evidence, next(evidence, "get_live_status", id), JSONObject().put("source", "host_rpc")
            .put("dataset", "up_live_status").put("user_id", id).put("live_status", "unknown"))
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
        assertTrue(evidence.limitation().contains("未知状态"))
    }

    @Test fun modelReadAlsoPreventsDuplicateAutomaticFirstReadButUnlocksItsPrerequisite() {
        val evidence = AgentGoalEvidence("查热榜第一名的播放量")
        evidence.record("get_ranking", JSONObject(), JSONObject().put("ok", true).put("data", ranking(1 to "av42")))
        record(evidence, next(evidence, "get_video_metrics", "av42"), metrics("av42"))
        assertNull(evidence.nextNativeRead { true })
    }

    @Test fun currentVideoMetricsRequireActualForegroundGuardedIdentity() {
        val evidence = AgentGoalEvidence("查询当前视频播放量")
        record(evidence, next(evidence, "get_host_state"), JSONObject().put("source", "host_rpc").put("foreground", true)
            .put("interaction_guard", true).put("video_id", "av7"))
        record(evidence, next(evidence, "get_video_metrics", "av7"), metrics("av7"))
        assertTrue(evidence.complete())
    }

    @Test fun temporarilyUnfocusedOwnedReadContextSuppliesMetricsIdentityButDoesNotConfirmOpening() {
        val data = JSONObject().put("source", "host_rpc").put("foreground", false).put("native_read_context", true)
            .put("interaction_guard", true).put("video_id", "av7").put("page", "video").put("task_page", true).put("navigation_pending", false)
        val evidence = AgentGoalEvidence("查询当前视频播放量")
        record(evidence, next(evidence, "get_host_state"), data)
        record(evidence, next(evidence, "get_video_metrics", "av7"), metrics("av7"))
        assertTrue(evidence.complete())
        val open = AgentGoalEvidence("打开 av7")
        open.record("open_video", JSONObject().put("video_id", "av7"), JSONObject().put("ok", true)
            .put("data", JSONObject().put("source", "host_rpc").put("navigation", "requested")))
        open.record("get_host_state", JSONObject(), JSONObject().put("ok", true).put("data", data))
        assertFalse(open.complete())
        val navigation = AgentNavigationEvidence().apply { requested("av7") }
        assertFalse(navigation.observe(data))
        assertEquals("av7", navigation.pending)
    }

    @Test fun untrustedOrUnownedOrCoercedContextCannotSupplyCurrentVideoIdentity() {
        fun state() = JSONObject().put("source", "host_rpc").put("foreground", false).put("native_read_context", true)
            .put("interaction_guard", true).put("video_id", "av7")
        for (data in listOf(state().put("source", "accessibility").put("foreground", true), state().apply { remove("source") },
            state().put("interaction_guard", false), state().put("native_read_context", false), state().put("native_read_context", "true"))) {
            val evidence = AgentGoalEvidence("查询当前视频播放量")
            record(evidence, next(evidence, "get_host_state"), data)
            assertTrue(evidence.targetVideoIds().isEmpty())
            assertNull(evidence.nextNativeRead { true })
            assertFalse(evidence.complete())
        }
    }

    @Test fun unavailableNativeOperationIsSkippedForExistingUiFallback() {
        val evidence = AgentGoalEvidence("查热榜第一名的播放量")
        record(evidence, next(evidence, "get_ranking"), ranking(1 to "av42"))
        assertNull(evidence.nextNativeRead { it != "get_video_metrics" })
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
    }

    @Test fun accumulatedFirstReadsStayBoundedInsteadOfRestartingAnUnlimitedAutomaticQueue() {
        val evidence = AgentGoalEvidence("查看 av9999 的播放量")
        repeat(512) { index ->
            evidence.record("get_video_metrics", JSONObject().put("video_id", "av${index + 1}"), JSONObject().put("ok", false).put("error", "rpc_failed"))
        }
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
    }

    @Test fun negativeOpenAndPlaybackDoNotBlockACompletedReadGoal() {
        for (negative in listOf("不要播放视频", "不要打开或播放视频", "不用打开视频", "别自动播放视频", "无需点开视频")) {
            val evidence = AgentGoalEvidence("查热榜第一名视频播放量，$negative")
            assertFalse(evidence.wantsPlaying)
            assertFalse(evidence.wantsOpen)
            record(evidence, next(evidence, "get_ranking"), ranking(1 to "av1"))
            record(evidence, next(evidence, "get_video_metrics", "av1"), metrics("av1"))
            assertTrue(evidence.complete())
        }
    }

    @Test fun affirmativeActionInAnotherClauseRetainsItsRealVerificationRequirement() {
        val evidence = AgentGoalEvidence("不要搜索 av1，打开并播放这个视频")
        assertTrue(evidence.wantsOpen)
        assertTrue(evidence.wantsPlaying)
        assertFalse(evidence.complete())
        val open = AgentGoalEvidence("不要播放 av1，但打开该视频")
        assertFalse(open.wantsPlaying)
        assertTrue(open.wantsOpen)
        assertFalse(open.complete())
    }

    @Test fun optionalUiReadWithoutAccessibilityDoesNotStopAnActiveNativeTask() {
        assertFalse(agentHostFailureStopsTask("get_ui_state", "accessibility_not_connected", true))
        assertTrue(agentHostFailureStopsTask("get_ui_state", "accessibility_not_connected", false))
        assertTrue(agentHostFailureStopsTask("click_ui", "accessibility_not_connected", true))
        assertTrue(agentHostFailureStopsTask("get_video_metrics", "host_not_foreground", true))
        assertTrue(agentHostFailureStopsTask("get_ui_state", "device_locked", true))
    }
}
